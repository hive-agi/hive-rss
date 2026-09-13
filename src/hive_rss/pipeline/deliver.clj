(ns hive-rss.pipeline.deliver
  "Where new items go: filed as memory notes, or ingested by hive-ingestor.

   `:rss/sink` picks. `\"memory\"` files notes. `\"ingestor\"` ingests, and a
   call that fails leaves the items unseen for the next poll. `\"auto\"` ingests
   when the host offers a `:tools/invoke` port and falls back to notes for a
   batch the ingestor could not take, so items are delivered either way."
  (:require [hive-dsl.result :as r]
            [hive-rss.boundary.ingestor :as ingestor]
            [hive-rss.pipeline.poll :as poll]))

;; SPDX-License-Identifier: MIT

(defn- ingested
  "The delivery of an ingest result for `items` of `feed`. Documents come back
   in feed order, one per distinct wanted key, so statuses map onto that order."
  [res feed items]
  (let [wanted (set (map :item/key items))
        order (distinct (filter wanted (map :item/key (:feed/items feed))))]
    (if (r/err? res)
      {:filed [] :created 0 :duplicates 0 :failed (count items)
       :last-error (str "rss/ingest-failed: " (:reason res))}
      (let [{:keys [statuses errors]} (:ok res)
            ok-keys (vec (keep-indexed (fn [i k] (when (= "ok" (get statuses i)) k)) order))]
        {:filed ok-keys :created (count ok-keys) :duplicates 0
         :failed (- (count order) (count ok-keys))
         :last-error (some->> (first errors) (str "rss/ingest-document-failed: "))}))))

(defn ingestor-deliver
  "A `:deliver!` that ingests through `invoke`. The feed is handed to the `rss`
   source through `feed-cache` for the duration of the call, so the ingest does
   not fetch it again."
  [invoke feed-cache]
  (fn [settings subscription feed items]
    (let [url (:feed/url subscription)]
      (swap! feed-cache assoc url feed)
      (try
        (ingested (ingestor/ingest! invoke {:url url
                                            :item-keys (map :item/key items)
                                            :project-id (:rss/project-id settings)})
                  feed items)
        (finally (swap! feed-cache dissoc url))))))

(defn choose
  "The `:deliver!` for `settings` given the host's `invoke` (nil when none), or
   nil to file notes entry by entry through `file!`."
  [settings invoke feed-cache file!]
  (let [sink (:rss/sink settings)]
    (cond
      (= "memory" sink) nil
      (nil? invoke) (when (= "ingestor" sink)
                      (fn [_ _ _ items]
                        {:filed [] :created 0 :duplicates 0 :failed (count items)
                         :last-error "rss/ingest-failed: the host offers no :tools/invoke port"}))
      (= "ingestor" sink) (ingestor-deliver invoke feed-cache)
      :else
      (let [ingest (ingestor-deliver invoke feed-cache)]
        (fn [settings subscription feed items]
          (let [d (ingest settings subscription feed items)]
            (if (and (empty? (:filed d)) (some-> (:last-error d) (.startsWith "rss/ingest-failed")))
              (poll/file-items! {:file! file!} settings subscription feed items)
              d)))))))
