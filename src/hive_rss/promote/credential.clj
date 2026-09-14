(ns hive-rss.promote.credential
  "Feed credentials. Pure: the environment is passed in.

   `:feed/auth` config forms (string keys accepted too):
     {:secret-env \"HIVE_STORE_KEY\"}                 Basic, key from the environment
     {:secret \"hv_live_...\" :username \"me\"}         Basic, key inline
     {:scheme \"bearer\" :secret-env \"FEED_TOKEN\"}    Bearer
   A URL carrying `user:secret@` yields a Basic credential and loses the
   userinfo. The secret reaches the wire only through `authorization`."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-rss.schema :as schema]
            [malli.core :as m])
  (:import (hive_rss.schema Secret)
           (java.net URI URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.util Base64)))

;; SPDX-License-Identifier: MIT

(def default-username
  "The Basic user sent when none is configured."
  "hive-rss")

(defn secret
  "`s` as a Secret, or nil when blank."
  [s]
  (when-not (str/blank? (str s)) (schema/->Secret (str s))))

(defn- decode [s]
  (URLDecoder/decode (str/replace (str s) "+" "%2B") StandardCharsets/UTF_8))

(defn split-url
  "`{:url :credential}`: `url` trimmed and without userinfo, and the Basic
   credential its userinfo names, or nil. Userinfo with no password uses the
   user part as the secret."
  [url]
  (let [s (str/trim (str url))
        [_ scheme userinfo tail] (re-matches #"(?s)^([a-zA-Z][a-zA-Z0-9+.-]*://)([^/?#@]*)@(.*)$" s)]
    (if-not userinfo
      {:url s :credential nil}
      (let [[user pass] (str/split userinfo #":" 2)
            sec (secret (decode (if (some? pass) pass user)))]
        {:url (str scheme tail)
         :credential (when sec
                       {:auth/scheme :basic
                        :auth/username (if (some? pass) (decode user) default-username)
                        :auth/secret sec})}))))

(defn- lookup [m & ks]
  (some #(get m %) ks))

(defn from-config
  "Result of the Credential that `auth` describes under environment `env`.
   Error `:rss/invalid-auth` with a `:reason` that never contains a secret."
  [auth env]
  (if-not (map? auth)
    (r/err :rss/invalid-auth {:reason "auth must be a map"})
    (let [scheme (some-> (lookup auth :auth/scheme :scheme "auth/scheme" "scheme") name str/lower-case)
          username (lookup auth :auth/username :username "auth/username" "username")
          inline (lookup auth :auth/secret :secret "auth/secret" "secret")
          env-var (lookup auth :auth/secret-env :secret-env "auth/secret-env" "secret-env" "secret_env")
          sec (cond
                (schema/secret? inline) inline
                (some? inline) (secret inline)
                env-var (secret (get env (str env-var))))]
      (cond
        (not (contains? #{nil "basic" "bearer"} scheme))
        (r/err :rss/invalid-auth {:reason (str "unknown scheme " (pr-str scheme) ", expected basic or bearer")})

        (and env-var (nil? inline) (nil? sec))
        (r/err :rss/invalid-auth {:reason (str "environment variable " env-var " is unset or blank")})

        (nil? sec)
        (r/err :rss/invalid-auth {:reason "auth needs a non-blank :secret or :secret-env"})

        :else
        (r/ok {:auth/scheme (keyword (or scheme "basic"))
               :auth/username (str (or username default-username))
               :auth/secret sec})))))

(defmulti authorization
  "The Authorization header value for a Credential, by `:auth/scheme`."
  :auth/scheme)

(defmethod authorization :basic [{:auth/keys [username ^Secret secret]}]
  (str "Basic " (.encodeToString (Base64/getEncoder)
                                 (.getBytes (str username ":" (.-value secret)) StandardCharsets/UTF_8))))

(defmethod authorization :bearer [{:auth/keys [^Secret secret]}]
  (str "Bearer " (.-value secret)))

(defn- origin [u]
  (let [uri (URI/create (str u))
        scheme (some-> (.getScheme uri) str/lower-case)
        port (.getPort uri)]
    [scheme (some-> (.getHost uri) str/lower-case)
     (if (neg? port) ({"https" 443 "http" 80} scheme) port)]))

(defn same-origin?
  "Whether absolute URLs `a` and `b` share scheme, host and port."
  [a b]
  (try (= (origin a) (origin b)) (catch Exception _ false)))

(defn for-url
  "`{:url :credential}` for an on-demand fetch of `url`: the URL without
   userinfo, and its userinfo credential, else the `:feed/auth` of the
   subscription in `subscriptions` with that URL, else nil."
  [subscriptions url]
  (let [{clean :url in-url :credential} (split-url url)]
    {:url clean
     :credential (or in-url
                     (some #(when (= clean (:feed/url %)) (:feed/auth %)) subscriptions))}))

(m/=> secret [:=> [:cat :any] [:maybe [:fn schema/secret?]]])
(m/=> split-url [:=> [:cat :any] schema/CredentialUrl])
(m/=> same-origin? [:=> [:cat :any :any] :boolean])
(m/=> from-config [:=> [:cat :any [:maybe [:map-of :string :string]]]
                   [:or [:map [:ok schema/Credential]] [:map [:error [:= :rss/invalid-auth]] [:reason :string]]]])
(m/=> for-url [:=> [:cat [:maybe [:sequential schema/Subscription]] :any] schema/CredentialUrl])
