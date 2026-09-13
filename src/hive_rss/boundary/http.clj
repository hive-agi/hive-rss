(ns hive-rss.boundary.http
  "Fetch one feed over HTTP(S).

   Conditional: the ETag and Last-Modified of the previous answer are sent back,
   so an unchanged feed costs a 304 and no body. Bounded: a body past
   `max-bytes` is refused while it streams, and every request has a timeout.
   Redirects are followed, but never from HTTPS down to HTTP."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r])
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
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.connectTimeout (Duration/ofSeconds 10))
             (.build))))

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

(defn fetch!
  "Result of GETting `url`.

   ok `{:status :ok :body s :etag e :last-modified lm}` for a 2xx, or
   `{:status :not-modified}` for a 304 answering `etag`/`last-modified`.
   Errors: `:rss/http-status` (non-2xx), `:rss/too-large`, `:rss/insecure-redirect`,
   `:rss/fetch-failed` (network, timeout, bad URL)."
  [url {:keys [etag last-modified timeout-ms max-bytes]}]
  (try
    (let [req (cond-> (-> (HttpRequest/newBuilder (URI/create url))
                          (.timeout (Duration/ofMillis timeout-ms))
                          (.header "User-Agent" user-agent)
                          (.header "Accept" "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.1"))
                etag (.header "If-None-Match" etag)
                last-modified (.header "If-Modified-Since" last-modified))
          ^HttpResponse resp (.send ^HttpClient @client (.build req) (HttpResponse$BodyHandlers/ofInputStream))
          status (.statusCode resp)
          final (str (.uri resp))]
      (cond
        (and (str/starts-with? url "https:") (str/starts-with? final "http:"))
        (do (.close ^InputStream (.body resp))
            (r/err :rss/insecure-redirect {:url url :to final}))

        (= 304 status)
        (do (.close ^InputStream (.body resp))
            (r/ok {:status :not-modified}))

        (<= 200 status 299)
        (if-let [bytes (read-bounded (.body resp) max-bytes)]
          (r/ok {:status :ok
                 :body (String. ^bytes bytes (charset-of (header resp "content-type")))
                 :etag (header resp "etag")
                 :last-modified (header resp "last-modified")})
          (r/err :rss/too-large {:url url :max-bytes max-bytes}))

        :else
        (do (.close ^InputStream (.body resp))
            (r/err :rss/http-status {:url url :status status}))))
    (catch InterruptedException e
      (.interrupt (Thread/currentThread))
      (r/err :rss/fetch-failed {:url url :reason (str "interrupted: " (ex-message e))}))
    (catch Exception e
      (r/err :rss/fetch-failed {:url url :reason (or (ex-message e) (.getName (class e)))}))))
