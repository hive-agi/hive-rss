(ns hive-rss.boundary.memory
  "File entries into hive memory through the hive-spi IMemoryStore port.

   The port upserts and nothing more, so this sink does what hive-mcp's
   `memory add` does around it: a content hash, a duplicate check in the
   project, the `scope:project:<id>` tag scope filters match on, and an expiry
   from the duration. It never throws; every outcome is a Result."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-spi.memory.ids :as ids]
            [hive-spi.memory.ports :as ports])
  (:import (java.time ZonedDateTime)))

;; SPDX-License-Identifier: MIT

(def duration-days
  "Days each duration lives, as hive-mcp counts them; nil is permanent."
  {"ephemeral" 1 "short" 7 "medium" 30 "long" 90 "permanent" nil})

(defn scope-tag [project-id]
  (when-not (str/blank? project-id) (str "scope:project:" project-id)))

(defn prepare
  "`entry` as the store receives it, stamped at `now` (a ZonedDateTime)."
  [entry ^ZonedDateTime now]
  (let [days (get duration-days (:duration entry))]
    (assoc entry
           :content-hash (ids/content-hash (:content entry))
           :tags (vec (distinct (cond-> (vec (:tags entry))
                                  (scope-tag (:project-id entry)) (conj (scope-tag (:project-id entry))))))
           :expires (if days (str (.plusDays now (long days))) ""))))

(defn file!
  "Result of filing `entry` into `store`: ok `{:id id :duplicate? bool}`.
   An entry whose content already exists in the project is not written again.
   Errors: `:rss/memory-refused` (the store answered without an id) and
   `:rss/memory-failed` (it threw)."
  [store entry]
  (try
    (let [e (prepare entry (ZonedDateTime/now))
          existing (ports/find-duplicate store (:type e) (:content-hash e)
                                         {:project-id (:project-id e)})]
      (if existing
        (r/ok {:id (:id existing) :duplicate? true})
        (let [id (ports/add-entry! store e)]
          (if (and (string? id) (not (str/blank? id)))
            (r/ok {:id id :duplicate? false})
            (r/err :rss/memory-refused {:answer (pr-str id)})))))
    (catch Exception e
      (r/err :rss/memory-failed {:reason (or (ex-message e) (.getName (class e)))}))))
