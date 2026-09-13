(ns hive-rss.contracts-test
  "The `m/=>` contracts of the pure layer, enforced: every function is
   instrumented and driven through a real poll, so a contract that disagrees
   with its function fails here instead of rotting as documentation."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-dsl.result :as r]
            [hive-rss.pipeline.poll :as poll]
            [hive-rss.promote.config]
            [hive-rss.promote.entry]
            [hive-rss.promote.parse :as parse]
            [hive-rss.promote.parse-test :refer [hive-store-xml]]
            [hive-rss.promote.schedule]
            [malli.instrument :as mi]))

;; SPDX-License-Identifier: MIT

(def instrumented-nses
  '#{hive-rss.promote.config hive-rss.promote.entry hive-rss.promote.parse hive-rss.promote.schedule})

(use-fixtures :once
  (fn [t]
    (mi/collect! {:ns instrumented-nses})
    (mi/instrument! {:filters [(mi/-filter-ns 'hive-rss.promote.config 'hive-rss.promote.entry
                                              'hive-rss.promote.parse 'hive-rss.promote.schedule)]
                     :report (fn [type data] (throw (ex-info (str "contract " type) data)))})
    (try (t) (finally (mi/unstrument! nil)))))

(deftest a-poll-honours-every-contract
  (let [sub {:feed/id "hive-store" :feed/url "https://store.hive-mcp.com/api/feed"}
        settings (:ok (hive-rss.promote.config/settings {:rss/feeds [sub]} {"HOME" "/tmp"}))
        filed (atom 0)
        deps {:fetch (fn [_ _] (r/ok {:status :ok :body (hive-store-xml)}))
              :file! (fn [_] (swap! filed inc) (r/ok {:id "x" :duplicate? false}))
              :save! identity
              :now (constantly 1789329000)}
        {:keys [reports]} (poll/poll-due! deps settings {} {})]
    (is (= :ok (:status (first reports))))
    (is (= 3 @filed))))

(deftest a-contract-violation-is-caught
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"contract"
                        (hive-rss.promote.schedule/period-seconds 0)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"contract"
                        (parse/parse-date 42))))
