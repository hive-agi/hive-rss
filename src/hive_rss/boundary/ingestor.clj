(ns hive-rss.boundary.ingestor
  "Deliver new items through hive-ingestor instead of filing notes.

   hive-rss never compile-depends on hive-ingestor. Its `rss` source is
   registered through hive-spi, and the ingest is started through the host's
   `:tools/invoke` runtime port as `memory ingest source`, the same call a
   user makes. The ingestor then chunks, embeds and stores each item, writes
   its document entry and its KG edges."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hive-dsl.result :as r]))

;; SPDX-License-Identifier: MIT

(defn- response-text [resp]
  (some->> (:content resp) (keep :text) seq (str/join "\n")))

(defn- read-json [s]
  (try (json/read-str s :key-fn keyword) (catch Exception _ nil)))

(defn parse-response
  "Result of an ingest tool response: ok `{:total :success :failed :statuses}`
   where `:statuses` is \"ok\"/\"error\" per document in source order."
  [resp]
  (let [text (response-text resp)
        body (when text (read-json text))
        body (if (and (map? body) (map? (:data body)) (contains? (:data body) :total)) (:data body) body)]
    (cond
      (or (:isError resp) (not (map? body)) (not (contains? body :total)))
      (r/err :rss/ingest-failed {:reason (or (some-> body :error str) (some-> text (subs 0 (min 300 (count text)))) "no response")})

      :else
      (r/ok {:total (:total body) :success (:success body) :failed (:failed body)
             :statuses (mapv #(str (:status %)) (:results body))
             :errors (vec (keep :error (:results body)))}))))

(defn ingest!
  "Result of ingesting the items keyed `item-keys` of the feed at `url` into
   `project-id`, through `invoke` (the host's `:tools/invoke`)."
  [invoke {:keys [url item-keys project-id]}]
  (try
    (parse-response
     (invoke "memory" {:command "ingest source"
                       :source "rss"
                       :rss-url url
                       :rss-keys (vec item-keys)
                       :project-id project-id}))
    (catch Exception e
      (r/err :rss/ingest-failed {:reason (or (ex-message e) (.getName (class e)))
                                 :unavailable? (= :addon/tool-unavailable (:error (ex-data e)))}))))
