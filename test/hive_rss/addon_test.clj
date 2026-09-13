(ns hive-rss.addon-test
  "The addon end to end on one machine: a loopback HTTP server serves the
   hive-store feed, the real scheduler polls it, the real memory sink files
   into a store registered in hive-spi, and state lands in a temp file."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-rss.addon :as sut]
            [hive-rss.promote.parse-test :refer [hive-store-xml]]
            [hive-spi.ingest.ports :as ingest]
            [hive-spi.ingest.registry :as ingest-registry]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.registry :as memory-registry])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; SPDX-License-Identifier: MIT

(defn- serve!
  "A loopback server answering /feed with `body` and ETag \"v1\", 304 when the
   client already holds it. Returns {:server :url :hits}."
  [body]
  (let [hits (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/feed"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex
                              inm (.getFirst (.getRequestHeaders ex) "If-None-Match")]
                          (swap! hits conj inm)
                          (if (= "\"v1\"" inm)
                            (do (.sendResponseHeaders ex 304 -1) (.close ex))
                            (let [bytes (.getBytes ^String body StandardCharsets/UTF_8)]
                              (.add (.getResponseHeaders ex) "Content-Type" "application/rss+xml; charset=utf-8")
                              (.add (.getResponseHeaders ex) "ETag" "\"v1\"")
                              (.sendResponseHeaders ex 200 (alength bytes))
                              (with-open [out (.getResponseBody ex)] (.write out bytes))))))))
    (.start server)
    {:server server :hits hits
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/feed")}))

(defn- memory-store []
  (let [entries (atom [])]
    {:entries entries
     :store (reify ports/IMemoryStore
              (find-duplicate [_ _ hash _] (some #(when (= hash (:content-hash %)) %) @entries))
              (add-entry! [_ e]
                (let [id (format "20260913120000-%08x" (count @entries))]
                  (swap! entries conj (assoc e :id id))
                  id)))}))

(defn- wait-until [pred ms]
  (loop [left ms]
    (cond (pred) true
          (neg? left) false
          :else (do (Thread/sleep 25) (recur (- left 25))))))

(defn- temp-state-file []
  (str (Files/createTempDirectory "hive-rss-test" (make-array FileAttribute 0)) "/state/state.edn"))

(deftest scheduler-polls-files-and-persists
  (let [{:keys [server url hits]} (serve! (hive-store-xml))
        {:keys [store entries]} (memory-store)
        state-file (temp-state-file)
        a (sut/addon-ctor {:rss/feeds [{:feed/id "hive-store" :feed/url url}]
                           :rss/fetches-per-day 24})]
    (memory-registry/set-store! store)
    (try
      (is (= :down (:status (addon/health a))) "not initialized yet")
      (let [init (addon/initialize! a {:rss/initial-delay-ms 0 :rss/state-file state-file})]
        (is (:success? init))
        (is (= {:feeds 1 :fetches-per-day 24 :tick-seconds 900} (:metadata init))))
      (testing "the scheduler's first tick polls the never-polled feed, without being asked"
        (is (wait-until #(= 3 (count @entries)) 10000))
        (is (wait-until #(some? (:last-pass (sut/status a))) 5000)))
      (testing "entries are scoped, tagged and hashed by the sink"
        (let [e (first @entries)]
          (is (some #{"scope:project:hive-rss"} (:tags e)))
          (is (some #{"rss-feed:hive-store"} (:tags e)))
          (is (string? (:content-hash e)))))
      (testing "state is on disk: seen keys, validator, poll time"
        (let [st (get (edn/read-string (slurp state-file)) "hive-store")]
          (is (= 3 (count (:seen st))))
          (is (= "\"v1\"" (:etag st)))
          (is (int? (:last-poll-at st)))))
      (testing "not due again for an hour: a poll pass does nothing"
        (is (= {:reports []} (sut/poll! a {})))
        (is (= 1 (count @hits))))
      (testing "the rss tool forces a poll: conditional GET, 304, nothing new"
        (let [out (json/read-str (-> ((:handler (first (addon/tools a))) {"command" "poll" "force" true})
                                     :content first :text)
                                 :key-fn keyword)]
          (is (= "not-modified" (-> out :reports first :status)))
          (is (= [nil "\"v1\""] @hits))
          (is (= 3 (count @entries)))))
      (testing "status reports the schedule"
        (let [{:keys [feeds period-seconds]} (sut/status a)]
          (is (= 3600 period-seconds))
          (is (< 3500 (:next-poll-in-seconds (first feeds)) 3601))
          (is (= :not-modified (:last-status (first feeds))))))
      (testing "the rss ingestion source is registered while active"
        (let [factory (ingest-registry/source-factory "rss")
              docs (ingest/fetch-documents (factory {}) {"rss-url" url})]
          (is (= 3 (count (:ok docs))))
          (is (= "https://clojars.org/io.github.hive-agi/hive-build" (:document/source (first (:ok docs)))))))
      (is (= :ok (:status (addon/health a))))
      (finally
        (addon/shutdown! a)
        (memory-registry/reset-registry!)
        (.stop ^HttpServer server 0)))
    (testing "shutdown retracts the source and stops polling"
      (is (nil? (ingest-registry/source-factory "rss")))
      (is (= :down (:status (addon/health a)))))
    (testing "a restart reads the state back and does not refetch a feed that is not due"
      (let [{:keys [server url hits]} (serve! (hive-store-xml))
            b (sut/addon-ctor {:rss/feeds [{:feed/id "hive-store" :feed/url url}]
                               :rss/fetches-per-day 24})]
        (try
          (is (:success? (addon/initialize! b {:rss/initial-delay-ms 0 :rss/state-file state-file})))
          (Thread/sleep 500)
          (is (empty? @hits))
          (is (= 3 (:seen (first (:feeds (sut/status b))))))
          (finally
            (addon/shutdown! b)
            (.stop ^HttpServer server 0)))))))

(deftest with-a-host-ingestor-new-items-go-through-the-registered-source
  (let [{:keys [server url hits]} (serve! (hive-store-xml))
        calls (atom [])
        ;; What hive-ingestor's handle-source does: resolve the registered
        ;; factory, build the source from the params, fetch its documents.
        invoke (fn [tool {:keys [source] :as args}]
                 (swap! calls conj [tool args])
                 (let [docs (ingest/fetch-documents ((ingest-registry/source-factory source) args) args)]
                   {:content [{:type "text"
                               :text (json/write-str {:total (count (:ok docs)) :success (count (:ok docs)) :failed 0
                                                      :results (mapv (fn [_] {:status "ok"}) (:ok docs))})}]}))
        a (sut/addon-ctor {:rss/feeds [{:feed/id "hive-store" :feed/url url}]})]
    (try
      (is (:success? (addon/initialize! a {:rss/initial-delay-ms 3600000
                                           :rss/state-file (temp-state-file)
                                           :runtime/ports {:tools/invoke invoke}})))
      (is (true? (:ingestor-port? (sut/status a))))
      (let [{[report] :reports} (sut/poll! a {:force? true})]
        (is (= {:feed "hive-store" :status :ok :items 3 :new 3 :created 3 :duplicates 0 :failed 0} report)))
      (is (= 1 (count @calls)))
      (is (= "ingest source" (:command (second (first @calls)))))
      (is (= 1 (count @hits)) "the ingest read the feed the poll fetched; it did not fetch again")
      (is (= 3 (:seen (first (:feeds (sut/status a))))))
      (finally
        (addon/shutdown! a)
        (.stop ^HttpServer server 0)))))

(deftest invalid-config-fails-initialize-cleanly
  (let [a (sut/addon-ctor {:rss/fetches-per-day 0})
        res (addon/initialize! a {})]
    (is (false? (:success? res)))
    (is (re-find #"invalid hive.rss config" (first (:errors res))))
    (is (= :down (:status (addon/health a))))
    (is (nil? (ingest-registry/source-factory "rss")))))

(deftest no-memory-store-is-an-error-per-item-not-a-crash
  (let [{:keys [server url]} (serve! (hive-store-xml))
        a (sut/addon-ctor {:rss/feeds [url]})]
    (memory-registry/reset-registry!)
    (try
      (addon/initialize! a {:rss/initial-delay-ms 3600000 :rss/state-file (temp-state-file)})
      (let [{[report] :reports} (sut/poll! a {:force? true})]
        (is (= :partial (:status report)))
        (is (= 3 (:failed report)))
        (is (= "rss/no-memory-store: no memory store registered" (:error report))))
      (finally
        (addon/shutdown! a)
        (.stop ^HttpServer server 0)))))
