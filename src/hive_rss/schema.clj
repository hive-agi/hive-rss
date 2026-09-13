(ns hive-rss.schema
  "The values hive-rss passes between its layers: a subscription, a parsed feed
   and its items, the schedule, and the per-feed state that survives restarts."
  (:require [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(def NonBlank [:string {:min 1}])

(def HttpUrl
  [:and NonBlank [:re #"^https?://[^\s]+$"]])

(def EpochSeconds
  [:int {:min 0}])

(def Subscription
  "One feed the scheduler polls. `:feed/id` names it in tags and state; it
   defaults to a slug of the URL."
  [:map
   [:feed/id NonBlank]
   [:feed/url HttpUrl]
   [:feed/tags {:optional true} [:vector NonBlank]]])

(def Item
  [:map
   [:item/key NonBlank]
   [:item/title [:maybe :string]]
   [:item/link [:maybe :string]]
   [:item/summary [:maybe :string]]
   [:item/text [:maybe :string]]
   [:item/published-at [:maybe EpochSeconds]]
   [:item/categories [:vector :string]]
   [:item/author [:maybe :string]]])

(def Feed
  [:map
   [:feed/format [:enum :rss :atom :rdf]]
   [:feed/title [:maybe :string]]
   [:feed/link [:maybe :string]]
   [:feed/description [:maybe :string]]
   [:feed/items [:vector Item]]])

(def FetchesPerDay
  "How many times a day every subscription is polled: at least once, at most
   every 15 minutes."
  [:int {:min 1 :max 96}])

(def Settings
  [:map {:closed true}
   [:rss/feeds [:vector Subscription]]
   [:rss/fetches-per-day FetchesPerDay]
   [:rss/project-id NonBlank]
   [:rss/memory-type [:re #"^[a-zA-Z][a-zA-Z0-9_-]{0,63}$"]]
   [:rss/duration [:enum "ephemeral" "short" "medium" "long" "permanent"]]
   ;; memory: one note per item, through the memory store port.
   ;; ingestor: each new item through hive-ingestor's pipeline (chunks,
   ;; document entry, KG edges). auto: ingestor when the host offers it.
   [:rss/sink [:enum "auto" "memory" "ingestor"]]
   [:rss/max-items-per-poll [:int {:min 1 :max 500}]]
   [:rss/state-file NonBlank]
   [:rss/timeout-ms [:int {:min 1000 :max 120000}]]
   [:rss/max-bytes [:int {:min 1024 :max 52428800}]]
   [:rss/initial-delay-ms [:int {:min 0}]]])

(def FeedState
  "What the scheduler remembers about one feed between polls."
  [:map
   [:seen [:set NonBlank]]
   [:etag {:optional true} [:maybe :string]]
   [:last-modified {:optional true} [:maybe :string]]
   [:last-poll-at {:optional true} [:maybe EpochSeconds]]
   [:last-attempt-at {:optional true} [:maybe EpochSeconds]]
   [:last-status {:optional true} [:maybe :keyword]]
   [:last-error {:optional true} [:maybe :string]]])

(def State
  [:map-of NonBlank FeedState])

(def valid-subscription? (m/validator Subscription))
(def valid-feed? (m/validator Feed))
(def valid-settings? (m/validator Settings))
(def explain-settings (m/explainer Settings))
