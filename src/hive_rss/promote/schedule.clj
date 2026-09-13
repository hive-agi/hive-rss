(ns hive-rss.promote.schedule
  "When a feed is due, and which of its items are new. No IO, no clock: the
   caller passes `now` in epoch seconds.

   The scheduler does not sleep a whole period between polls. It ticks often
   and asks `due?` of each feed against the time of that feed's last poll, which
   is persisted. A restart, a suspended laptop or a slow poll therefore never
   skips a day, and never polls a feed more often than the configured rate."
  (:require [hive-rss.schema :as schema]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(def day-seconds 86400)

(defn period-seconds
  "Seconds between two polls of one feed when it is polled `fetches-per-day`
   times a day."
  [fetches-per-day]
  (quot day-seconds fetches-per-day))

(defn due?
  "True when a feed last polled at `last-poll-at` (nil: never) should be polled
   at `now`. A last poll in the future (clock moved back) counts as due, so a
   wrong clock cannot silence a feed."
  [now last-poll-at period]
  (or (nil? last-poll-at)
      (> last-poll-at now)
      (>= (- now last-poll-at) period)))

(defn retry-seconds
  "Seconds to wait after a failed poll before trying again: a tenth of the
   period, between five minutes and an hour. A feed that is down at its daily
   poll is retried the same day instead of waiting for the next one."
  [period]
  (-> (quot period 10) (max 300) (min 3600) (min period)))

(defn feed-due?
  "True when a feed with `feed-state` should be polled at `now`: its last
   successful poll is a period old, and no failed attempt is more recent than
   the retry delay."
  [now {:keys [last-poll-at last-attempt-at]} period]
  (and (due? now last-poll-at period)
       (or (nil? last-attempt-at)
           (and last-poll-at (<= last-attempt-at last-poll-at))
           (due? now last-attempt-at (retry-seconds period)))))

(defn next-due-at
  "Epoch seconds at which the feed becomes due, or `now` when it already is."
  [now last-poll-at period]
  (if (due? now last-poll-at period) now (+ last-poll-at period)))

(def ^:private seen-cap
  "The seen set keeps at most this many keys. Past it, only the keys still in
   the feed are kept: an item that left the feed will not come back."
  5000)

(defn- distinct-by-key
  "`items` keeping the first occurrence of each `:item/key`."
  [items]
  (let [ks (volatile! #{})]
    (filterv (fn [{k :item/key}] (when-not (@ks k) (vswap! ks conj k))) items)))

(defn select-new
  "`{:new items :seen seen'}` for a poll of `items` against the `seen` keys.

   `:new` holds at most `max-items` unseen items, newest first (undated items
   after dated ones, in feed order). Only the keys of the items returned are
   added to `seen'`, so items past the cap arrive on a later poll rather than
   being skipped."
  [items seen max-items]
  (let [fresh (->> items
                   (remove #(contains? seen (:item/key %)))
                   (map-indexed vector)
                   (sort-by (fn [[i it]] [(- (or (:item/published-at it) -1)) i]))
                   (map second)
                   distinct-by-key
                   (take max-items)
                   vec)
        seen' (into seen (map :item/key) fresh)
        seen' (if (> (count seen') seen-cap)
                (into (set (keep :item/key items)) (map :item/key) fresh)
                seen')]
    {:new fresh :seen seen'}))

(m/=> period-seconds [:=> [:cat schema/FetchesPerDay] pos-int?])
(m/=> due? [:=> [:cat schema/EpochSeconds [:maybe schema/EpochSeconds] pos-int?] :boolean])
(m/=> retry-seconds [:=> [:cat pos-int?] pos-int?])
(m/=> feed-due? [:=> [:cat schema/EpochSeconds schema/FeedState pos-int?] :boolean])
(m/=> next-due-at [:=> [:cat schema/EpochSeconds [:maybe schema/EpochSeconds] pos-int?] schema/EpochSeconds])
(m/=> select-new [:=> [:cat [:sequential schema/Item] [:set schema/NonBlank] pos-int?]
                  [:map [:new [:vector schema/Item]] [:seen [:set schema/NonBlank]]]])
