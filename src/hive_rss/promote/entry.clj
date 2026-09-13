(ns hive-rss.promote.entry
  "A feed item as a hive memory entry, and as an ingestion document. Pure."
  (:require [clojure.string :as str]
            [hive-rss.schema :as schema]
            [malli.core :as m])
  (:import (java.time Instant)))

;; SPDX-License-Identifier: MIT

(def max-text-chars
  "Item text past this is cut: a memory entry is a pointer with a summary, and
   the link carries the rest."
  6000)

(def ^:private max-category-tags 5)

(defn- tag-slug [s]
  (let [t (-> (str s) str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))]
    (when-not (str/blank? t) (subs t 0 (min 48 (count t))))))

(defn- clip [s n]
  (when s (if (> (count s) n) (str (subs s 0 n) " [...]") s)))

(defn content
  "The text an item is remembered by: title, link, feed, date, categories, then
   the item's own text."
  [subscription feed item]
  (let [{:item/keys [title link published-at categories author text]} item]
    (str/join "\n"
              (concat
               [(or (not-empty title) link (:item/key item))]
               (when link [link])
               [(str "Feed: " (or (not-empty (:feed/title feed)) (:feed/id subscription))
                     " <" (:feed/url subscription) ">")]
               (when published-at [(str "Published: " (Instant/ofEpochSecond published-at))])
               (when author [(str "Author: " author)])
               (when (seq categories) [(str "Categories: " (str/join ", " categories))])
               (when text ["" (clip text max-text-chars)])))))

(defn tags
  "Tags of an item's entry: `rss`, the feed, the subscription's own tags, and a
   few of the item's categories."
  [subscription item]
  (->> (concat ["rss" (str "rss-feed:" (:feed/id subscription))]
               (:feed/tags subscription)
               (->> (:item/categories item) (keep tag-slug) (take max-category-tags)
                    (map #(str "rss-category:" %))))
       distinct
       vec))

(defn memory-entry
  "The entry to file for `item` of `feed`, polled through `subscription`.
   Scope and content hash are the sink's to add."
  [settings subscription feed item]
  {:type (:rss/memory-type settings)
   :content (content subscription feed item)
   :tags (tags subscription item)
   :duration (:rss/duration settings)
   :project-id (:rss/project-id settings)})

(m/=> content [:=> [:cat schema/Subscription schema/Feed schema/Item] :string])
(m/=> tags [:=> [:cat schema/Subscription schema/Item] [:vector :string]])
(m/=> memory-entry [:=> [:cat schema/Settings schema/Subscription schema/Feed schema/Item]
                    [:map [:type :string] [:content :string] [:tags [:vector :string]]
                     [:duration :string] [:project-id :string]]])
