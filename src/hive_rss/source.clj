(ns hive-rss.source
  "The `rss` ingestion source: one document per item of one feed, for
   `memory ingest source source=rss rss-url=...`. The ingest pipeline chunks,
   embeds and stores what this returns; the scheduler does not go through it."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-rss.promote.config :as config]
            [hive-rss.promote.entry :as entry]
            [hive-rss.promote.parse :as parse]
            [hive-spi.ingest.model :as model]
            [hive-spi.ingest.ports :as ingest]
            [hive-rss.promote.credential :as credential])
  (:import (java.nio.charset StandardCharsets)
           (java.util UUID)))

;; SPDX-License-Identifier: MIT

(def source-id "rss")

(def params {"rss-url" "HTTP(S) URL of an RSS 2.0, RSS 1.0 or Atom feed"
             "rss-keys" "Optional item keys (guid, id or link) to ingest; the others are skipped"})

(defn- wanted-keys
  "The item keys `opts` narrows to, as a set, or nil for every item. Accepts a
   collection or a comma-separated string."
  [opts]
  (let [v (or (get opts "rss-keys") (:rss-keys opts) (get opts "rss_keys") (:rss_keys opts))
        ks (cond (string? v) (str/split v #",") (sequential? v) v :else nil)]
    (some->> ks (map #(str/trim (str %))) (remove str/blank?) seq set)))

(defn documents
  "Result of one ingestion document per item of `feed`, fetched from `url`."
  [url feed]
  (let [sub (config/subscription url)]
    (reduce (fn [acc item]
              (let [doc (model/make-document
                         {:id (str (UUID/nameUUIDFromBytes
                                    (.getBytes (str url "#" (:item/key item)) StandardCharsets/UTF_8)))
                          :source (or (:item/link item) (str url "#" (:item/key item)))
                          :format :format/text
                          :content (entry/content sub feed item)
                          :metadata (cond-> {:source-type source-id
                                             :feed-url url
                                             :feed-title (:feed/title feed)
                                             :item-key (:item/key item)
                                             :title (:item/title item)}
                                      (:item/published-at item) (assoc :published-at (:item/published-at item)))})]
                (if (r/ok? doc) (update acc :ok conj (:ok doc)) (reduced doc))))
            (r/ok [])
            (:feed/items feed))))

(defrecord RssSource [fetch cached-feed defaults]
  ingest/ISource
  (source-id [_] source-id)
  (fetch-documents [_ opts]
    (let [opts (merge defaults opts)
          raw (some-> (or (get opts "rss-url") (:rss-url opts) (get opts "rss_url") (:rss_url opts)) str)
          {url :url auth :credential} (credential/for-url (:rss/feeds defaults) raw)
          wanted (wanted-keys opts)
          narrow (fn [feed]
                   (update feed :feed/items
                           #(->> % (filter (fn [it] (or (nil? wanted) (wanted (:item/key it)))))
                                 (reduce (fn [acc it] (if (some (fn [x] (= (:item/key x) (:item/key it))) acc) acc (conj acc it))) []))))]
      (if-not (and raw (re-matches #"^https?://\S+$" url))
        (r/err :rss/invalid-url {:reason "rss-url must be an http(s) URL"})
        (if-let [feed (and cached-feed (cached-feed url))]
          ;; The scheduler fetched this feed moments ago; do not fetch it twice.
          (documents url (narrow feed))
          (let [fetched (fetch url (cond-> {:timeout-ms (:rss/timeout-ms defaults 20000)
                                            :max-bytes (:rss/max-bytes defaults (* 5 1024 1024))}
                                     auth (assoc :auth auth)))]
            (cond
              (r/err? fetched) fetched
              (= :not-modified (:status (:ok fetched))) (r/ok [])
              :else (r/bind (parse/parse (:body (:ok fetched)))
                            #(documents url (narrow %))))))))))
