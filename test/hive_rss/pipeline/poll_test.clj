(ns hive-rss.pipeline.poll-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-rss.pipeline.poll :as poll]
            [hive-rss.promote.config :as config]
            [hive-rss.promote.parse-test :refer [hive-store-xml]]))

;; SPDX-License-Identifier: MIT

(def sub {:feed/id "hive-store" :feed/url "https://store.hive-mcp.com/api/feed" :feed/tags ["hive-store"]})

(defn settings [& {:as over}]
  (merge (:ok (config/settings {:rss/feeds [sub]} {"HOME" "/tmp"})) over))

(defn harness
  "Deps over atoms: `responses` is a vector of fetch Results served in turn;
   `refuse` is a set of item titles the fake store refuses."
  [{:keys [responses refuse clock] :or {refuse #{} clock (atom 1000)}}]
  (let [served (atom responses)
        filed (atom [])
        requests (atom [])
        saved (atom nil)]
    {:deps {:fetch (fn [url opts]
                     (swap! requests conj [url opts])
                     (let [[x & more] @served] (reset! served (vec more)) x))
            :file! (fn [entry]
                     (if (some #(.startsWith ^String (:content entry) %) refuse)
                       (r/err :rss/memory-refused {:answer "nil"})
                       (let [dup? (some #(= (:content %) (:content entry)) @filed)]
                         (swap! filed conj entry)
                         (r/ok {:id (str "id-" (count @filed)) :duplicate? (boolean dup?)}))))
            :save! #(reset! saved %)
            :now #(deref clock)}
     :filed filed :requests requests :saved saved :clock clock}))

(def ok-body (r/ok {:status :ok :body (hive-store-xml) :etag "\"v1\"" :last-modified nil}))

(deftest first-poll-files-every-item-then-nothing-twice
  (let [{:keys [deps filed requests]} (harness {:responses [ok-body ok-body]})
        s (settings)
        {st :state report :report} (poll/poll-feed! deps s sub {})]
    (is (= {:feed "hive-store" :status :ok :items 3 :new 3 :created 3 :duplicates 0 :failed 0} report))
    (is (= #{"hive-build@0.1.17" "hive-build@0.1.16" "hive-carto@2.4.0"} (:seen st)))
    (is (= "\"v1\"" (:etag st)))
    (is (= 1000 (:last-poll-at st)))
    (testing "entries carry the feed, scope project and tags"
      (let [e (first @filed)]
        (is (= "hive-rss" (:project-id e)))
        (is (= "note" (:type e)))
        (is (= ["rss" "rss-feed:hive-store" "hive-store" "rss-category:public" "rss-category:hive-build"] (:tags e)))
        (is (.startsWith ^String (:content e) "hive-build 0.1.17\nhttps://clojars.org/io.github.hive-agi/hive-build\nFeed: hive store releases"))))
    (testing "the second poll sends the validator back and files nothing new"
      (let [{report2 :report} (poll/poll-feed! deps s sub st)]
        (is (= "\"v1\"" (:etag (second (second @requests)))))
        (is (= 0 (:new report2)))
        (is (= 3 (count @filed)))))))

(deftest a-feed-with-credentials-is-fetched-with-them
  (let [{:keys [deps requests]} (harness {:responses [ok-body ok-body]})
        s (:ok (config/settings {:rss/feeds ["https://store.hive-mcp.com/api/feed"
                                             {:feed/url "https://store.hive-mcp.com/api/feed?package=hive-carto"
                                              :feed/auth {:secret-env "K"}}]}
                                {"HOME" "/tmp" "K" "hv_live_k"}))
        [open keyed] (:rss/feeds s)]
    (poll/poll-feed! deps s open {})
    (poll/poll-feed! deps s keyed {})
    (is (not (contains? (second (first @requests)) :auth)) "an open feed sends nothing")
    (is (= (:feed/auth keyed) (:auth (second (second @requests)))))))

(deftest not-modified-is-a-successful-poll
  (let [{:keys [deps]} (harness {:responses [(r/ok {:status :not-modified})]})
        {st :state report :report} (poll/poll-feed! deps (settings) sub {:seen #{"x"} :etag "e"})]
    (is (= :not-modified (:status report)))
    (is (= #{"x"} (:seen st)))
    (is (= 1000 (:last-poll-at st)))))

(deftest a-failed-fetch-records-the-attempt-and-keeps-the-last-success
  (let [{:keys [deps]} (harness {:responses [(r/err :rss/http-status {:url "u" :status 404})]})
        {st :state report :report} (poll/poll-feed! deps (settings) sub {:seen #{} :last-poll-at 5})]
    (is (= {:feed "hive-store" :status :error :error "rss/http-status: 404"} report))
    (is (= 5 (:last-poll-at st)))
    (is (= 1000 (:last-attempt-at st)))
    (is (= :error (:last-status st)))))

(deftest a-broken-body-is-an-error-not-a-crash
  (let [{:keys [deps]} (harness {:responses [(r/ok {:status :ok :body "<html/>"})]})]
    (is (= :error (:status (:report (poll/poll-feed! deps (settings) sub {})))))))

(deftest refused-items-are-offered-again
  (let [{:keys [deps filed]} (harness {:responses [ok-body ok-body] :refuse #{"hive-carto"}})
        s (settings)
        {st :state report :report} (poll/poll-feed! deps s sub {})]
    (is (= :partial (:status report)))
    (is (= 1 (:failed report)))
    (is (not (contains? (:seen st) "hive-carto@2.4.0")))
    (let [{report2 :report} (poll/poll-feed! (assoc deps :file! (fn [_] (r/ok {:id "late" :duplicate? false}))) s sub st)]
      (is (= 1 (:new report2)))
      (is (= 2 (count @filed))))))

(deftest poll-due-respects-the-rate
  (let [clock (atom 1000)
        {:keys [deps requests saved]} (harness {:responses [ok-body ok-body ok-body] :clock clock})
        s (settings :rss/fetches-per-day 2)
        {st :state} (poll/poll-due! deps s {} {})]
    (is (= 1 (count @requests)) "never polled: due")
    (is (= st @saved) "state is saved after the feed")
    (reset! clock (+ 1000 43199))
    (poll/poll-due! deps s st {})
    (is (= 1 (count @requests)) "not yet half a day")
    (reset! clock (+ 1000 43200))
    (poll/poll-due! deps s st {})
    (is (= 2 (count @requests)) "half a day later: due again")
    (poll/poll-due! deps s st {:force? true :feed-id "hive-store"})
    (is (= 3 (count @requests)) "force ignores the rate")
    (poll/poll-due! deps s st {:force? true :feed-id "other"})
    (is (= 3 (count @requests)) "feed-id narrows")))
