(ns hive-rss.source-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-rss.promote.config :as config]
            [hive-rss.promote.parse-test :refer [hive-store-xml]]
            [hive-rss.source :as source]
            [hive-spi.ingest.ports :as ingest]))

;; SPDX-License-Identifier: MIT

(def the-key "hv_live_dddddddddddddddddddddddddddddddd")

(defn- recording-fetch [calls]
  (fn [url opts]
    (swap! calls conj [url opts])
    (r/ok {:status :ok :body (hive-store-xml)})))

(deftest an-on-demand-ingest-uses-credentials-and-never-stores-them
  (testing "credentials in rss-url"
    (let [calls (atom [])
          src (source/->RssSource (recording-fetch calls) nil {})
          docs (ingest/fetch-documents src {"rss-url" (str "https://feed:" the-key "@store.test/api/feed")})]
      (is (r/ok? docs))
      (is (= "https://store.test/api/feed" (ffirst @calls)))
      (is (= :basic (get-in (first @calls) [1 :auth :auth/scheme])))
      (is (not (str/includes? (pr-str (:ok docs)) the-key)) "no document, id or metadata carries the key")))
  (testing "a configured feed's credential, found by its URL"
    (let [calls (atom [])
          settings (:ok (config/settings {:rss/feeds [{:feed/url "https://store.test/api/feed"
                                                       :feed/auth {:scheme "bearer" :secret-env "K"}}]}
                                         {"HOME" "/tmp" "K" the-key}))
          src (source/->RssSource (recording-fetch calls) nil settings)]
      (ingest/fetch-documents src {"rss-url" "https://store.test/api/feed"})
      (is (= :bearer (get-in (first @calls) [1 :auth :auth/scheme])))))
  (testing "an open URL fetches without"
    (let [calls (atom [])
          src (source/->RssSource (recording-fetch calls) nil {})]
      (ingest/fetch-documents src {"rss-url" "https://example.org/feed"})
      (is (not (contains? (second (first @calls)) :auth))))))
