(ns hive-rss.boundary.memory-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-rss.boundary.memory :as memory]
            [hive-spi.memory.ids :as ids]
            [hive-spi.memory.ports :as ports])
  (:import (java.time ZonedDateTime ZoneOffset)))

;; SPDX-License-Identifier: MIT

(def entry {:type "note" :content "hive-build 0.1.17\nhttps://x" :tags ["rss" "rss"]
            :duration "medium" :project-id "hive-rss"})

(defn store [{:keys [duplicate add-answer]}]
  (let [added (atom [])
        lookups (atom [])]
    {:added added :lookups lookups
     :store (reify ports/IMemoryStore
              (find-duplicate [_ type hash opts] (swap! lookups conj [type hash opts]) duplicate)
              (add-entry! [_ e] (swap! added conj e) (if (contains? #{:none} add-answer) nil (or add-answer "20260913120000-abcdef12"))))}))

(deftest prepare-adds-hash-scope-and-expiry
  (let [now (ZonedDateTime/of 2026 9 13 12 0 0 0 ZoneOffset/UTC)
        e (memory/prepare entry now)]
    (is (= (ids/content-hash (:content entry)) (:content-hash e)))
    (is (= ["rss" "scope:project:hive-rss"] (:tags e)))
    (is (= "2026-10-13T12:00Z" (:expires e)))
    (is (= "" (:expires (memory/prepare (assoc entry :duration "permanent") now))))))

(deftest file-writes-once
  (testing "new content is added and its id returned"
    (let [{:keys [store added lookups]} (store {})]
      (is (= {:ok {:id "20260913120000-abcdef12" :duplicate? false}} (memory/file! store entry)))
      (is (= 1 (count @added)))
      (is (= [["note" (ids/content-hash (:content entry)) {:project-id "hive-rss"}]] @lookups))))
  (testing "content already in the project is not written again"
    (let [{:keys [store added]} (store {:duplicate {:id "old"}})]
      (is (= {:ok {:id "old" :duplicate? true}} (memory/file! store entry)))
      (is (empty? @added))))
  (testing "a store that answers without an id refused the write"
    (let [{:keys [store]} (store {:add-answer {:success? false}})]
      (is (= :rss/memory-refused (:error (memory/file! store entry))))))
  (testing "a store that throws is an error value"
    (is (= :rss/memory-failed
           (:error (memory/file! (reify ports/IMemoryStore
                                   (find-duplicate [_ _ _ _] (throw (ex-info "down" {}))))
                                 entry))))))
