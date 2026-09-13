(ns hive-rss.promote.schedule-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-rss.promote.schedule :as schedule]))

;; SPDX-License-Identifier: MIT

(deftest period-follows-the-daily-rate
  (is (= 86400 (schedule/period-seconds 1)))
  (is (= 43200 (schedule/period-seconds 2)))
  (is (= 900 (schedule/period-seconds 96))))

(deftest due
  (let [p 86400]
    (is (schedule/due? 1000 nil p) "never polled")
    (is (not (schedule/due? (+ 1000 (dec p)) 1000 p)))
    (is (schedule/due? (+ 1000 p) 1000 p))
    (is (schedule/due? 1000 5000 p) "a last poll in the future does not silence the feed")))

(deftest failed-polls-are-retried-within-the-day
  (let [p 86400
        retry (schedule/retry-seconds p)]
    (is (= 3600 retry))
    (is (= 300 (schedule/retry-seconds 900)))
    (testing "never succeeded, just failed: wait the retry delay, not a tick"
      (is (not (schedule/feed-due? 1100 {:last-attempt-at 1000} p)))
      (is (schedule/feed-due? (+ 1000 retry) {:last-attempt-at 1000} p)))
    (testing "succeeded a day ago, failed an hour later: retry after the delay"
      (let [fs {:last-poll-at 0 :last-attempt-at (+ p 10)}]
        (is (not (schedule/feed-due? (+ p 20) fs p)))
        (is (schedule/feed-due? (+ p 10 retry) fs p))))
    (testing "a success clears the retry"
      (is (not (schedule/feed-due? 500 {:last-poll-at 100 :last-attempt-at 100} p))))))

(defn- item [k at] {:item/key k :item/title k :item/link nil :item/summary nil :item/text nil
                    :item/published-at at :item/categories [] :item/author nil})

(deftest select-new-items
  (let [items [(item "a" 10) (item "b" 30) (item "c" nil) (item "d" 20) (item "b" 30)]]
    (testing "unseen, newest first, undated last, duplicates within the feed once"
      (is (= ["b" "d" "a" "c"] (mapv :item/key (:new (schedule/select-new items #{} 10))))))
    (testing "seen keys are skipped"
      (is (= ["d" "c"] (mapv :item/key (:new (schedule/select-new items #{"a" "b"} 10))))))
    (testing "past the cap, the rest wait for the next poll instead of being marked seen"
      (let [{fresh :new seen :seen} (schedule/select-new items #{} 2)]
        (is (= ["b" "d"] (mapv :item/key fresh)))
        (is (= #{"b" "d"} seen))
        (is (= ["a" "c"] (mapv :item/key (:new (schedule/select-new items seen 2)))))))))

(defspec select-new-only-returns-unseen-within-cap 300
  (prop/for-all [keys (gen/vector (gen/elements ["a" "b" "c" "d" "e" "f"]))
                 seen (gen/set (gen/elements ["a" "b" "c"]))
                 cap (gen/choose 1 5)]
    (let [{fresh :new seen' :seen} (schedule/select-new (map #(item % (count %)) keys) seen cap)]
      (and (<= (count fresh) cap)
           (not-any? #(contains? seen (:item/key %)) fresh)
           (= (count fresh) (count (distinct (map :item/key fresh))))
           (every? #(contains? seen' (:item/key %)) fresh)))))
