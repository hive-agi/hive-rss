(ns hive-rss.demo
  "Poll real feeds from a shell, without a hive:

     clojure -M:dev -m hive-rss.demo https://github.com/hive-agi/hive-build/tags.atom

   Each URL goes through the `rss` ingestion source (network, parser, document
   builder) and the addon's entry builder; entries are printed, not filed."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-rss.boundary.http :as http]
            [hive-rss.promote.config :as config]
            [hive-rss.promote.entry :as entry]
            [hive-rss.promote.parse :as parse]
            [hive-rss.source :as source]
            [hive-spi.ingest.ports :as ingest]))

;; SPDX-License-Identifier: MIT

(defn run [url]
  (let [settings (:ok (config/settings {:rss/feeds [url]} (into {} (System/getenv))))
        sub (first (:rss/feeds settings))
        fetched (http/fetch! url {:timeout-ms 20000 :max-bytes (* 5 1024 1024)})
        parsed (when (r/ok? fetched) (parse/parse (:body (:ok fetched))))
        docs (ingest/fetch-documents (source/->RssSource http/fetch! nil settings) {"rss-url" url})]
    (println "==" url)
    (cond
      (r/err? fetched) (println "  fetch:" (pr-str fetched))
      (r/err? parsed) (println "  parse:" (pr-str parsed))
      :else
      (let [feed (:ok parsed)]
        (println " " (:feed/format feed) "|" (:feed/title feed) "|" (count (:feed/items feed)) "items |"
                 "source documents:" (if (r/ok? docs) (count (:ok docs)) (pr-str docs)))
        (doseq [item (take 2 (:feed/items feed))]
          (let [e (entry/memory-entry settings sub feed item)]
            (println "  --" (:tags e))
            (println (str "    " (str/replace (subs (:content e) 0 (min 300 (count (:content e)))) "\n" "\n    ")))))))))

(defn -main [& urls]
  (doseq [u urls] (run u))
  (shutdown-agents))
