(ns hive-rss.addon
  "IAddon `hive.rss`. Construction is pure. `initialize!` validates the config,
   loads feed state, registers the `rss` ingestion source and starts the
   scheduler; `shutdown!` undoes all three.

   The scheduler is the addon's own: one daemon thread that wakes every
   `tick` seconds (a quarter hour, or the period when that is shorter) and
   polls the feeds that are due. Due-ness is read from the persisted time of
   each feed's last poll, so feeds are polled `:rss/fetches-per-day` times a
   day across restarts, never more, and a restart never skips a day.

   Config (manifest `:addon/config`, then config.edn `:addons {\"hive.rss\" {..}}`):
     :rss/feeds             [\"https://..\" {:feed/url .. :feed/id .. :feed/tags [..]}]
     :rss/fetches-per-day   1..96 (default 1)
     :rss/project-id        memory scope items are filed under (default \"hive-rss\")
     :rss/memory-type       entry type (default \"note\")
     :rss/duration          entry duration (default \"medium\")
     :rss/sink              \"auto\" | \"memory\" | \"ingestor\" (default \"auto\": through
                            hive-ingestor when the host offers :tools/invoke)
     :rss/max-items-per-poll, :rss/timeout-ms, :rss/max-bytes,
     :rss/initial-delay-ms, :rss/state-file"
  (:require [clojure.data.json :as json]
            [hive-addon.protocol :as addon]
            [hive-dsl.result :as r]
            [hive-rss.boundary.http :as http]
            [hive-rss.boundary.memory :as memory]
            [hive-rss.boundary.state :as state]
            [hive-rss.pipeline.deliver :as deliver]
            [hive-rss.pipeline.poll :as poll]
            [hive-rss.promote.config :as config]
            [hive-rss.promote.schedule :as schedule]
            [hive-rss.source :as source]
            [hive-spi.ingest.registry :as ingest-registry]
            [hive-spi.memory.registry :as memory-registry])
  (:import (java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit)
           (java.util.concurrent.atomic AtomicBoolean)))

;; SPDX-License-Identifier: MIT

(def addon-id-value "hive.rss")

(defn tick-seconds
  "How often the scheduler wakes to look for due feeds."
  [fetches-per-day]
  (min 900 (schedule/period-seconds fetches-per-day)))

(defn- now-s [] (quot (System/currentTimeMillis) 1000))

(defn- daemon-scheduler ^ScheduledExecutorService []
  (Executors/newSingleThreadScheduledExecutor
   (reify ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. ^Runnable runnable "hive-rss-scheduler")
         (.setDaemon true))))))

(defn- file-into-registered-store!
  "File `entry` into the registered memory store, looked up per item so a store
   registered after this addon starts is still found."
  [entry]
  (if (memory-registry/store-set?)
    (memory/file! (memory-registry/get-store) entry)
    (r/err :rss/no-memory-store {:reason "no memory store registered"})))

(defn- default-deps
  "Production effects: the network, the memory store or hive-ingestor (through
   the host's `:tools/invoke` runtime port), the state file."
  [settings runtime-config feed-cache]
  (let [invoke (get-in runtime-config [:runtime/ports :tools/invoke])
        deliver! (deliver/choose settings invoke feed-cache file-into-registered-store!)]
    (cond-> {:fetch http/fetch!
             :file! file-into-registered-store!
             :save! #(state/save-state! (:rss/state-file settings) %)
             :now now-s}
      deliver! (assoc :deliver! deliver!))))

;; ---------------------------------------------------------------------------
;; polling

(defn poll!
  "Run one pass over the feeds now, on the calling thread. `opts`:
   `:force?` polls feeds that are not due, `:feed-id` narrows to one feed.
   Returns the pass's reports, or `{:skipped :busy}` when a pass is running."
  ([a] (poll! a {}))
  ([{:keys [state]} opts]
   (let [{:keys [settings deps ^AtomicBoolean busy]} @state]
     (cond
       (nil? settings) {:skipped :not-initialized}
       (not (.compareAndSet busy false true)) {:skipped :busy}
       :else
       (try
         (let [{st :state reports :reports}
               (poll/poll-due! deps settings (:feed-state @state) opts)]
           (swap! state assoc :feed-state st
                  :last-pass {:at ((:now deps)) :reports reports})
           {:reports reports})
         (catch Throwable t
           (swap! state assoc :last-pass {:at ((:now deps)) :error (str t)})
           {:error (str t)})
         (finally (.set busy false)))))))

;; ---------------------------------------------------------------------------
;; status

(defn status
  "What the addon knows: settings summary, each feed's state and next poll."
  [{:keys [state]}]
  (let [{:keys [settings feed-state last-pass lifecycle errors]} @state]
    (if-not settings
      {:lifecycle (or lifecycle :new) :errors errors}
      (let [period (schedule/period-seconds (:rss/fetches-per-day settings))
            t (now-s)]
        {:lifecycle lifecycle
         :fetches-per-day (:rss/fetches-per-day settings)
         :period-seconds period
         :tick-seconds (tick-seconds (:rss/fetches-per-day settings))
         :project-id (:rss/project-id settings)
         :sink (:rss/sink settings)
         :ingestor-port? (contains? (:deps @state) :deliver!)
         :state-file (:rss/state-file settings)
         :last-pass last-pass
         :feeds (mapv (fn [{:feed/keys [id url]}]
                        (let [fs (get feed-state id {})
                              due (if (schedule/feed-due? t fs period)
                                    t
                                    (max (schedule/next-due-at t (:last-poll-at fs) period)
                                         (if-let [a (:last-attempt-at fs)]
                                           (+ a (schedule/retry-seconds period))
                                           0)))]
                          {:id id :url url
                           :seen (count (:seen fs))
                           :last-poll-at (:last-poll-at fs)
                           :last-status (:last-status fs)
                           :last-error (:last-error fs)
                           :next-poll-in-seconds (max 0 (- due t))}))
                      (:rss/feeds settings))}))))

;; ---------------------------------------------------------------------------
;; tool

(defn- text-result [x]
  {:content [{:type "text" :text (json/write-str x :escape-slash false)}]})

(defn tool [a]
  {:name "rss"
   :description "hive-rss feeds. status: schedule, feeds and last results. poll: fetch due feeds now (force=true fetches every feed, feed_id narrows to one); new items are filed into hive memory."
   :inputSchema {:type "object"
                 :properties {"command" {:type "string" :enum ["status" "poll"]}
                              "force" {:type "boolean" :description "[poll] poll feeds that are not due"}
                              "feed_id" {:type "string" :description "[poll] only this feed"}}
                 :required ["command"]}
   :handler (fn [{:strs [command force feed_id] :as params}]
              (let [command (or command (:command params))]
                (case command
                  "status" (text-result (status a))
                  "poll" (text-result (poll! a {:force? (true? (or force (:force params)))
                                                :feed-id (or feed_id (:feed_id params))}))
                  {:content [{:type "text" :text (str "unknown command: " command)}] :isError true})))})

;; ---------------------------------------------------------------------------
;; lifecycle

(defn- start! [a settings deps]
  (let [{:keys [state]} a
        ^ScheduledExecutorService sched (daemon-scheduler)
        tick (tick-seconds (:rss/fetches-per-day settings))
        registered (ingest-registry/register-source!
                    addon-id-value source/source-id
                    {:factory (fn [opts] (source/->RssSource (:fetch deps)
                                                             #(get @(:feed-cache @state) %)
                                                             (merge settings opts)))
                     :description "Fetch an RSS or Atom feed: one document per item."
                     :params source/params})]
    (when-not (r/ok? registered)
      (.shutdownNow sched)
      (throw (ex-info "Cannot register the rss ingestion source." {:result registered})))
    (swap! state assoc
           :lifecycle :active :settings settings :deps deps :scheduler sched
           :feed-state (state/load-state (:rss/state-file settings)))
    (.scheduleWithFixedDelay sched
                             ^Runnable (fn [] (try (poll! a {}) (catch Throwable _ nil)))
                             (long (:rss/initial-delay-ms settings))
                             (long (* 1000 tick))
                             TimeUnit/MILLISECONDS)
    {:success? true :errors []
     :metadata {:feeds (count (:rss/feeds settings))
                :fetches-per-day (:rss/fetches-per-day settings)
                :tick-seconds tick}}))

(defn- initialize-addon! [a seed runtime-config]
  (let [{:keys [state]} a]
    (locking state
      (if (= :active (:lifecycle @state))
        {:success? true :already-initialized? true}
        (let [cfg (merge (:addon/config seed) seed (:addon/config runtime-config) runtime-config)
              settings (config/settings cfg (into {} (System/getenv)))]
          (if (r/err? settings)
            (do (swap! state assoc :lifecycle :error :errors [(pr-str (:problems settings))])
                {:success? false :errors [(str "invalid hive.rss config: " (pr-str (:problems settings)))]})
            (try
              (start! a (:ok settings)
                      (merge (default-deps (:ok settings) cfg (:feed-cache @state)) (:rss/deps cfg)))
              (catch Exception e
                (swap! state assoc :lifecycle :error :errors [(ex-message e)])
                {:success? false :errors [(ex-message e)]}))))))))

(defn- shutdown-addon! [{:keys [state]}]
  (locking state
    (when-let [^ScheduledExecutorService sched (:scheduler @state)]
      (.shutdownNow sched))
    (ingest-registry/retract-all! addon-id-value)
    (swap! state #(-> % (dissoc :scheduler :settings :deps) (assoc :lifecycle :stopped))))
  nil)

(defrecord HiveRssAddon [state seed]
  addon/IAddon
  (addon-id [_] addon-id-value)
  (addon-type [_] :native)
  (capabilities [_] #{:tools :sources :health-reporting})
  (initialize! [this runtime-config] (initialize-addon! this seed runtime-config))
  (shutdown! [this] (shutdown-addon! this))
  (tools [this] [(tool this)])
  (schema-extensions [_]
    {"memory" {"rss-url" {:type "string" :maxLength 2048
                          :description "[source rss] HTTP(S) URL of an RSS or Atom feed"}}})
  (health [this]
    (let [{:keys [lifecycle feeds] :as s} (status this)
          failing (filterv #(= :error (:last-status %)) feeds)]
      (if (= :active lifecycle)
        {:status (if (and (seq feeds) (= (count failing) (count feeds))) :degraded :ok)
         :details (assoc (select-keys s [:fetches-per-day :tick-seconds :project-id])
                         :feeds (count feeds)
                         :failing (mapv :id failing))}
        {:status :down :details (select-keys s [:lifecycle :errors])})))
  (excluded-tools [_] #{})
  (hooks [this]
    {:rss/poll! (fn ([] (poll! this {})) ([opts] (poll! this opts)))
     :rss/status (fn [] (status this))}))

(defn make-addon
  "Uninitialized IAddon. No thread, file or registry is touched."
  ([] (make-addon {}))
  ([seed] (->HiveRssAddon (atom {:lifecycle :new :busy (AtomicBoolean. false) :feed-cache (atom {})})
                          (or seed {}))))

(defn addon-ctor
  "hive-addon.mount constructor: config -> uninitialized IAddon."
  [config]
  (make-addon config))
