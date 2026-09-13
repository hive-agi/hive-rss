(ns hive-rss.pipeline.poll
  "One poll of one feed, and one pass over every due feed.

   Effects arrive as functions so the pipeline runs the same against the
   network and the memory store as against test doubles:

     :fetch    (fn [url {:keys [etag last-modified timeout-ms max-bytes]}]) -> Result
     :file!    (fn [entry]) -> Result {:id :duplicate?}
     :deliver! (fn [settings subscription feed items]) -> delivery, optional:
               replaces filing entry by entry (hive-ingestor delivery).
               A delivery is {:filed [item-key] :created :duplicates :failed :last-error}
     :save!  (fn [state])            persists the whole state
     :now    (fn []) -> epoch seconds"
  (:require [hive-dsl.result :as r]
            [hive-rss.promote.entry :as entry]
            [hive-rss.promote.parse :as parse]
            [hive-rss.promote.schedule :as schedule]))

;; SPDX-License-Identifier: MIT

(defn- error-text [result]
  (let [{:keys [error] :as e} result]
    (str (some-> error symbol) (when-let [why (or (:reason e) (:status e))] (str ": " why)))))

(defn file-items!
  "Deliver `items` by filing each as a memory entry through `file!`: the keys
   of those filed or already present."
  [{:keys [file!]} settings subscription feed items]
  (reduce (fn [acc item]
            (let [res (file! (entry/memory-entry settings subscription feed item))]
              (if (r/ok? res)
                (-> acc
                    (update :filed conj (:item/key item))
                    (update (if (:duplicate? (:ok res)) :duplicates :created) inc))
                (-> acc
                    (update :failed inc)
                    (assoc :last-error (error-text res))))))
          {:filed [] :created 0 :duplicates 0 :failed 0 :last-error nil}
          items))

(defn poll-feed!
  "Poll `subscription` once. Returns `{:state feed-state' :report report}`.

   An item's key joins the seen set only once it is in memory (created or
   already there), so an item the store refused is offered again next poll. A
   poll whose fetch or parse fails keeps the last successful poll time and
   records the attempt, which `feed-due?` turns into a retry."
  [{:keys [fetch now] :as deps} settings subscription feed-state]
  (let [t (now)
        base (merge {:seen #{}} feed-state)
        fetched (fetch (:feed/url subscription)
                       {:etag (:etag base) :last-modified (:last-modified base)
                        :timeout-ms (:rss/timeout-ms settings) :max-bytes (:rss/max-bytes settings)})
        failed (fn [res]
                 {:state (assoc base :last-attempt-at t :last-status :error :last-error (error-text res))
                  :report {:feed (:feed/id subscription) :status :error :error (error-text res)}})]
    (cond
      (r/err? fetched) (failed fetched)

      (= :not-modified (:status (:ok fetched)))
      {:state (assoc base :last-poll-at t :last-attempt-at t :last-status :not-modified :last-error nil)
       :report {:feed (:feed/id subscription) :status :not-modified :new 0}}

      :else
      (let [{:keys [body etag last-modified]} (:ok fetched)
            parsed (parse/parse body)]
        (if (r/err? parsed)
          (failed parsed)
          (let [feed (:ok parsed)
                {fresh :new} (schedule/select-new (:feed/items feed) (:seen base)
                                                  (:rss/max-items-per-poll settings))
                {:keys [filed created duplicates failed last-error]}
                (if (empty? fresh)
                  {:filed [] :created 0 :duplicates 0 :failed 0}
                  (if-let [deliver! (:deliver! deps)]
                    (deliver! settings subscription feed fresh)
                    (file-items! deps settings subscription feed fresh)))
                status (if (pos? failed) :partial :ok)]
            {:state (assoc base
                           :seen (into (:seen base) filed)
                           :etag etag :last-modified last-modified
                           :last-poll-at t :last-attempt-at t
                           :last-status status :last-error last-error)
             :report (cond-> {:feed (:feed/id subscription) :status status
                              :items (count (:feed/items feed)) :new (count fresh)
                              :created created :duplicates duplicates :failed failed}
                       last-error (assoc :error last-error))}))))))

(defn poll-due!
  "Poll every subscription due at now (all of them when `force?`), saving the
   state after each. Returns `{:state state' :reports [..]}`.

   A feed that throws is reported and the pass goes on to the next one."
  [{:keys [now save!] :as deps} settings state {:keys [force? feed-id]}]
  (let [period (schedule/period-seconds (:rss/fetches-per-day settings))]
    (reduce (fn [{:keys [state] :as acc} sub]
              (let [id (:feed/id sub)
                    fs (get state id {:seen #{}})]
                (if (or (and feed-id (not= feed-id id))
                        (and (not force?) (not (schedule/feed-due? (now) fs period))))
                  acc
                  (let [{fs' :state report :report}
                        (try (poll-feed! deps settings sub fs)
                             (catch Exception e
                               {:state (assoc fs :last-attempt-at (now) :last-status :error
                                              :last-error (ex-message e))
                                :report {:feed id :status :error :error (ex-message e)}}))
                        state' (assoc state id fs')]
                    (save! state')
                    {:state state' :reports (conj (:reports acc) report)}))))
            {:state state :reports []}
            (:rss/feeds settings))))
