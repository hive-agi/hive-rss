(ns hive-rss.pipeline.deliver-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-rss.boundary.ingestor :as ingestor]
            [hive-rss.pipeline.deliver :as deliver]
            [hive-rss.promote.config :as config]
            [hive-rss.promote.parse :as parse]
            [hive-rss.promote.parse-test :refer [hive-store-xml]]
            [hive-rss.source :as source]
            [hive-spi.ingest.ports :as ingest]))

;; SPDX-License-Identifier: MIT

(def sub {:feed/id "hive-store" :feed/url "https://store.hive-mcp.com/api/feed"})
(def feed (:ok (parse/parse (hive-store-xml))))
(defn settings [sink] (:ok (config/settings {:rss/feeds [sub] :rss/sink sink} {"HOME" "/tmp"})))

(defn mcp [body] {:content [{:type "text" :text (json/write-str body)}]})

(defn fake-ingestor
  "An `invoke` that does what hive-ingestor's handle-source does with a source:
   build it (here directly, with the addon's cache), fetch its documents, and
   answer per document. `fail-sources` answer error for those document sources."
  [calls feed-cache & {:keys [fail-sources]}]
  (fn [tool args]
    (swap! calls conj [tool args])
    (let [src (source/->RssSource (fn [& _] (throw (ex-info "fetched twice" {})))
                                  #(get @feed-cache %) {})
          docs (ingest/fetch-documents src args)]
      (mcp {:total (count (:ok docs))
            :success (count (remove #(contains? fail-sources (:document/source %)) (:ok docs)))
            :failed (count (filter #(contains? fail-sources (:document/source %)) (:ok docs)))
            :results (mapv #(if (contains? fail-sources (:document/source %))
                              {:status "error" :error "embedder down"}
                              {:status "ok" :data {:source (:document/source %)}})
                           (:ok docs))}))))

(deftest ingestor-delivery-ingests-only-the-new-items-from-the-cached-feed
  (let [calls (atom []) cache (atom {})
        d (deliver/choose (settings "ingestor") (fake-ingestor calls cache) cache nil)
        newest (take 2 (:feed/items feed))
        out (d (settings "ingestor") sub feed newest)]
    (is (= {:filed ["hive-build@0.1.17" "hive-build@0.1.16"] :created 2 :duplicates 0 :failed 0 :last-error nil} out))
    (let [[tool args] (first @calls)]
      (is (= "memory" tool))
      (is (= {:command "ingest source" :source "rss" :rss-url (:feed/url sub)
              :rss-keys ["hive-build@0.1.17" "hive-build@0.1.16"] :project-id "hive-rss"}
             args)))
    (is (empty? @cache) "the cache holds a feed only for the call")))

(deftest a-document-the-ingestor-failed-stays-unseen
  (let [calls (atom []) cache (atom {})
        d (deliver/choose (settings "ingestor")
                          (fake-ingestor calls cache :fail-sources #{"https://store.hive-mcp.com/addons/hive-carto"})
                          cache nil)
        out (d (settings "ingestor") sub feed (:feed/items feed))]
    (is (= ["hive-build@0.1.17" "hive-build@0.1.16"] (:filed out)))
    (is (= 1 (:failed out)))
    (is (= "rss/ingest-document-failed: embedder down" (:last-error out)))))

(deftest auto-falls-back-to-notes-when-the-host-has-no-ingestor
  (let [filed (atom [])
        file! (fn [e] (swap! filed conj e) (r/ok {:id "n" :duplicate? false}))
        unavailable (fn [_ _] (throw (ex-info "Host tool unavailable." {:error :addon/tool-unavailable})))]
    (testing "auto: an ingest call that fails delivers the batch as notes"
      (let [d (deliver/choose (settings "auto") unavailable (atom {}) file!)
            out (d (settings "auto") sub feed (:feed/items feed))]
        (is (= 3 (:created out)))
        (is (= 3 (count @filed)))))
    (testing "ingestor: no fallback, the items stay unseen for the next poll"
      (let [d (deliver/choose (settings "ingestor") unavailable (atom {}) file!)
            out (d (settings "ingestor") sub feed (:feed/items feed))]
        (is (= [] (:filed out)))
        (is (= 3 (:failed out)))))
    (testing "no invoke port: auto files notes (nil deliver), ingestor reports why"
      (is (nil? (deliver/choose (settings "auto") nil (atom {}) file!)))
      (is (nil? (deliver/choose (settings "memory") unavailable (atom {}) file!)))
      (is (re-find #"no :tools/invoke"
                   (:last-error ((deliver/choose (settings "ingestor") nil (atom {}) file!)
                                 (settings "ingestor") sub feed (:feed/items feed))))))))

(deftest ingest-responses
  (is (= {:ok {:total 1 :success 1 :failed 0 :statuses ["ok"] :errors []}}
         (ingestor/parse-response (mcp {:total 1 :success 1 :failed 0 :results [{:status "ok"}]}))))
  (is (= :rss/ingest-failed (:error (ingestor/parse-response {:content [{:type "text" :text "{\"error\":\"No source registered as rss\"}"}] :isError true}))))
  (is (= :rss/ingest-failed (:error (ingestor/parse-response {:content [{:type "text" :text "not json"}]}))))
  (is (= :rss/ingest-failed (:error (ingestor/parse-response nil)))))

(deftest the-source-narrows-to-keys
  (let [src (source/->RssSource (fn [_ _] (r/ok {:status :ok :body (hive-store-xml)})) nil {})]
    (is (= 3 (count (:ok (ingest/fetch-documents src {"rss-url" (:feed/url sub)})))))
    (is (= ["https://store.hive-mcp.com/addons/hive-carto"]
           (mapv :document/source (:ok (ingest/fetch-documents src {"rss-url" (:feed/url sub) "rss-keys" "hive-carto@2.4.0"})))))
    (is (= :rss/invalid-url (:error (ingest/fetch-documents src {"rss-url" "file:///etc/passwd"}))))))
