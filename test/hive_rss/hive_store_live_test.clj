(ns hive-rss.hive-store-live-test
  "Against the real hive-store: `clojure -M:integration`. Needs the network."
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [hive-rss.boundary.http :as http]
            [hive-rss.promote.parse :as parse]
            [hive-rss.schema :as schema]))

;; SPDX-License-Identifier: MIT

(def feed-url "https://store.hive-mcp.com/api/feed")

(deftest ^:integration hive-store-release-feed-is-live-and-parses
  (let [fetched (http/fetch! feed-url {:timeout-ms 20000 :max-bytes (* 5 1024 1024)})]
    (is (r/ok? fetched) (str feed-url " answered " (pr-str fetched)))
    (when (r/ok? fetched)
      (let [parsed (parse/parse (:body (:ok fetched)))]
        (is (r/ok? parsed) (pr-str parsed))
        (is (schema/valid-feed? (:ok parsed)))
        (is (= :rss (:feed/format (:ok parsed))))
        (is (seq (:feed/items (:ok parsed))))
        (is (every? #(re-find #"@" (:item/key %)) (:feed/items (:ok parsed)))
            "hive-store guids are <package>@<version>")))))
