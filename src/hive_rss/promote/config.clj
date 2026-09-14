(ns hive-rss.promote.config
  "User config to validated Settings. No IO: the environment is passed in.

   Config arrives flattened from the manifest's `:addon/config` and the host's
   runtime config. A feed may be written as a bare URL or as
   `{:feed/url .. :feed/id .. :feed/tags [..]}`; keys may be keywords or the
   strings a JSON config carries (\"rss/feeds\", \"url\", \"id\")."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-rss.promote.credential :as credential]
            [hive-rss.schema :as schema]
            [malli.core :as m]
            [malli.error :as me]))

;; SPDX-License-Identifier: MIT

(def defaults
  {:rss/feeds []
   :rss/fetches-per-day 1
   :rss/project-id "hive-rss"
   :rss/memory-type "note"
   :rss/duration "medium"
   :rss/sink "auto"
   :rss/max-items-per-poll 50
   :rss/timeout-ms 20000
   :rss/max-bytes (* 5 1024 1024)
   :rss/initial-delay-ms 30000})

(defn slug
  "A feed id from its URL: host and path, lowercased, runs of anything else as
   one hyphen."
  [url]
  (let [s (-> (str url)
              (str/replace #"^[a-zA-Z][a-zA-Z0-9+.-]*://" "")
              (str/replace #"^www\." "")
              str/lower-case
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"^-+|-+$" ""))]
    (if (str/blank? s) "feed" (subs s 0 (min 64 (count s))))))

(defn- lookup [m & ks]
  (some #(get m %) ks))

(defn subscription
  "A Subscription from a URL string or a feed map, or nil when it has no URL.

   Credentials come from the map's `:feed/auth` (resolved against `env`) or
   from `user:secret@` in the URL, which is removed from the URL either way.
   A `:feed/auth` that cannot be resolved leaves `::auth-problem` on the
   subscription for `settings` to refuse; it names the problem, not the key."
  ([feed] (subscription feed {}))
  ([feed env]
   (let [raw (if (string? feed) feed (lookup feed :feed/url :url "feed/url" "url"))
         id (when (map? feed) (lookup feed :feed/id :id "feed/id" "id"))
         tags (when (map? feed) (lookup feed :feed/tags :tags "feed/tags" "tags"))
         auth (when (map? feed) (lookup feed :feed/auth :auth "feed/auth" "auth"))
         {:keys [url credential]} (credential/split-url raw)
         resolved (when (some? auth) (credential/from-config auth env))]
     (when-not (str/blank? url)
       (cond-> {:feed/id (if (str/blank? (str id)) (slug url) (str id))
                :feed/url url}
         (seq tags) (assoc :feed/tags (mapv str tags))
         credential (assoc :feed/auth credential)
         (and resolved (r/ok? resolved)) (assoc :feed/auth (:ok resolved))
         (and resolved (r/err? resolved)) (assoc ::auth-problem (:reason resolved)))))))

(defn default-state-file
  "Where feed state lives: `$XDG_STATE_HOME/hive-rss/state.edn`, else under
   `~/.local/state`."
  [{:strs [XDG_STATE_HOME HOME]}]
  (str (or (not-empty XDG_STATE_HOME)
           (str (or (not-empty HOME) ".") "/.local/state"))
       "/hive-rss/state.edn"))

(def ^:private setting-keys (keys (assoc defaults :rss/state-file nil)))

(defn settings
  "Result of the Settings for `config` under environment `env` (a string map).
   Unknown keys are ignored; a feed listed twice by id keeps its first entry.
   Error `:rss/invalid-config` carries the humanized problems, and for a feed
   whose `:feed/auth` cannot be resolved, the reason by feed id."
  [config env]
  (let [picked (into {}
                     (keep (fn [k]
                             (let [v (lookup config k (subs (str k) 1))]
                               (when (some? v) [k v]))))
                     setting-keys)
        merged (merge defaults {:rss/state-file (default-state-file env)} picked)
        feeds (->> (:rss/feeds merged)
                   (keep #(subscription % env))
                   (reduce (fn [acc f] (if (some #(= (:feed/id f) (:feed/id %)) acc) acc (conj acc f))) []))
        auth-problems (into {} (keep (fn [f] (when-let [p (::auth-problem f)] [(:feed/id f) p]))) feeds)
        s (assoc merged :rss/feeds feeds)]
    (cond
      (seq auth-problems)
      (r/err :rss/invalid-config {:problems {:rss/feeds {:feed/auth auth-problems}}})

      (schema/valid-settings? s)
      (r/ok s)

      :else
      (r/err :rss/invalid-config
             {:problems (-> (schema/explain-settings s) me/humanize)}))))

(m/=> slug [:=> [:cat :any] schema/NonBlank])
(m/=> subscription [:function
                    [:=> [:cat :any] [:maybe :map]]
                    [:=> [:cat :any [:maybe [:map-of :string :string]]] [:maybe :map]]])
