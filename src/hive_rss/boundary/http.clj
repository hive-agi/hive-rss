(ns hive-rss.boundary.http
  "Fetch one feed over HTTP(S).

   Conditional: the ETag and Last-Modified of the previous answer are sent back,
   so an unchanged feed costs a 304 and no body. Bounded: a body past
   `max-bytes` is refused while it streams, and every request has a timeout.
   Redirects are followed, but never from HTTPS down to HTTP."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-rss.promote.credential :as credential])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse
                          HttpResponse$BodyHandlers)
           (java.nio.charset Charset StandardCharsets)
           (java.time Duration)))

;; SPDX-License-Identifier: MIT

(def user-agent "hive-rss/0.1 (+https://github.com/hive-agi/hive-rss)")

(defonce ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.followRedirects HttpClient$Redirect/NEVER)
             (.connectTimeout (Duration/ofSeconds 10))
             (.build))))

(def max-redirects 5)

(defn- read-bounded
  "The bytes of `in`, or nil once more than `max-bytes` have arrived."
  [^InputStream in max-bytes]
  (with-open [in in]
    (let [out (ByteArrayOutputStream.)
          buf (byte-array 8192)]
      (loop [total 0]
        (let [n (.read in buf)]
          (cond
            (neg? n) (.toByteArray out)
            (> (+ total n) max-bytes) nil
            :else (do (.write out buf 0 n) (recur (+ total n)))))))))

(defn- charset-of
  "The charset a Content-Type names, else UTF-8. XML declares its own encoding,
   but the DOM parser is handed a String, so the transport's word is used."
  [content-type]
  (or (some-> (re-find #"(?i)charset=\"?([A-Za-z0-9._:-]+)" (str content-type))
              second
              (as-> cs (try (Charset/forName cs) (catch Exception _ nil))))
      StandardCharsets/UTF_8))

(defn- header [^HttpResponse resp nm]
  (-> resp .headers (.firstValue nm) (.orElse nil)))

(defn- refusal-reason
  "Why the server refused: the status, whether a credential was sent, and the
   first line of a short plain-text answer. Closes the body."
  [status ^HttpResponse resp credential]
  (let [text (when (str/starts-with? (str/lower-case (str (header resp "content-type"))) "text/plain")
               (some-> (read-bounded (.body resp) 512) (String. StandardCharsets/UTF_8)
                       str/split-lines first str/trim not-empty))]
    (when-not text (.close ^InputStream (.body resp)))
    (str status
         (if credential " with credential" " without credential")
         (when text (str ": " (subs text 0 (min 120 (count text))))))))

(defn fetch!
  "Result of GETting `url`.

   ok `{:status :ok :body s :etag e :last-modified lm}` for a 2xx, or
   `{:status :not-modified}` for a 304 answering `etag`/`last-modified`.
   `auth` (a Credential) is sent as Authorization, and so is a credential in
   the URL's userinfo when `auth` is absent; it follows a redirect only to the
   same origin. At most `max-redirects` redirects are followed.
   Errors: `:rss/http-status` (non-2xx; for 401/402/403 `:reason` carries the
   status and the server's short plain-text answer), `:rss/too-large`,
   `:rss/insecure-redirect`, `:rss/too-many-redirects`, `:rss/fetch-failed`.
   Error data carries the URL without userinfo."
  [url {:keys [etag last-modified timeout-ms max-bytes auth]}]
  (let [{origin-url :url in-url :credential} (credential/split-url url)
        auth (or auth in-url)]
    (try
      (loop [current origin-url
             hops 0]
        (let [carried (when (and auth (credential/same-origin? origin-url current)) auth)
              req (cond-> (-> (HttpRequest/newBuilder (URI/create current))
                              (.timeout (Duration/ofMillis timeout-ms))
                              (.header "User-Agent" user-agent)
                              (.header "Accept" "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.1"))
                    etag (.header "If-None-Match" etag)
                    last-modified (.header "If-Modified-Since" last-modified)
                    carried (.header "Authorization" (credential/authorization carried)))
              ^HttpResponse resp (.send ^HttpClient @client (.build req) (HttpResponse$BodyHandlers/ofInputStream))
              status (.statusCode resp)
              location (header resp "location")]
          (cond
            (and (<= 300 status 399) (not= 304 status) location)
            (let [target (str (.resolve (URI/create current) ^String location))]
              (.close ^InputStream (.body resp))
              (cond
                (and (str/starts-with? current "https:") (str/starts-with? target "http:"))
                (r/err :rss/insecure-redirect {:url origin-url :to (:url (credential/split-url target))})

                (>= hops max-redirects)
                (r/err :rss/too-many-redirects {:url origin-url :max max-redirects})

                :else (recur target (inc hops))))

            (= 304 status)
            (do (.close ^InputStream (.body resp))
                (r/ok {:status :not-modified}))

            (<= 200 status 299)
            (if-let [bytes (read-bounded (.body resp) max-bytes)]
              (r/ok {:status :ok
                     :body (String. ^bytes bytes (charset-of (header resp "content-type")))
                     :etag (header resp "etag")
                     :last-modified (header resp "last-modified")})
              (r/err :rss/too-large {:url origin-url :max-bytes max-bytes}))

            (#{401 402 403} status)
            (r/err :rss/http-status {:url origin-url :status status
                                     :reason (refusal-reason status resp carried)})

            :else
            (do (.close ^InputStream (.body resp))
                (r/err :rss/http-status {:url origin-url :status status})))))
      (catch InterruptedException e
        (.interrupt (Thread/currentThread))
        (r/err :rss/fetch-failed {:url origin-url :reason (str "interrupted: " (ex-message e))}))
      (catch Exception e
        (r/err :rss/fetch-failed {:url origin-url :reason (or (ex-message e) (.getName (class e)))})))))
