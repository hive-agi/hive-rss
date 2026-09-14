(ns hive-rss.store-feed-check
  "Fetch a hive store release feed the way the scheduler does, anonymously and
   with a key, and print what came back. For checking a store, or
   hive-store's dev/feed_preview.clj, from this side of the wire.

     HIVE_STORE_KEY=hv_live_... clojure -M:dev -m hive-rss.store-feed-check http://localhost:8599/api/feed"
  (:require [hive-dsl.result :as r]
            [hive-rss.boundary.http :as http]
            [hive-rss.promote.config :as config]
            [hive-rss.promote.parse :as parse]))

;; SPDX-License-Identifier: MIT

(def ^:private opts {:timeout-ms 10000 :max-bytes (* 5 1024 1024)})

(defn check
  "One fetch of `subscription`, then a conditional refetch with the ETag it
   returned. A summary map."
  [subscription]
  (let [first-fetch (http/fetch! (:feed/url subscription)
                                 (cond-> opts (:feed/auth subscription) (assoc :auth (:feed/auth subscription))))]
    (if (r/err? first-fetch)
      {:feed (:feed/id subscription) :error (dissoc first-fetch :url)}
      (let [{:keys [body etag]} (:ok first-fetch)
            parsed (parse/parse body)
            again (http/fetch! (:feed/url subscription)
                               (cond-> (assoc opts :etag etag)
                                 (:feed/auth subscription) (assoc :auth (:feed/auth subscription))))]
        {:feed (:feed/id subscription)
         :auth (some-> subscription :feed/auth :auth/scheme name)
         :items (count (get-in parsed [:ok :feed/items]))
         :categories (frequencies (mapcat :item/categories (get-in parsed [:ok :feed/items])))
         :etag etag
         :refetch (or (get-in again [:ok :status]) (:error again))}))))

(defn -main [& [url]]
  (let [url (or url "http://localhost:8599/api/feed")
        env (into {} (System/getenv))
        feeds (cond-> [{:feed/url url :feed/id "anonymous"}]
                (get env "HIVE_STORE_KEY")
                (conj {:feed/url url :feed/id "keyed" :feed/auth {:secret-env "HIVE_STORE_KEY"}}
                      {:feed/url url :feed/id "keyed-bearer" :feed/auth {:scheme "bearer" :secret-env "HIVE_STORE_KEY"}})
                (get env "HIVE_STORE_BAD_KEY")
                (conj {:feed/url url :feed/id "refused" :feed/auth {:secret-env "HIVE_STORE_BAD_KEY"}}))
        settings (config/settings {:rss/feeds feeds} env)]
    (if (r/err? settings)
      (prn settings)
      (doseq [s (:rss/feeds (:ok settings))]
        (prn (check s))))
    (shutdown-agents)))
