(ns hive-rss.boundary.http-test
  "fetch! against real sockets: two loopback servers on different ports are
   two origins."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-rss.boundary.http :as http]
            [hive-rss.promote.credential :as credential])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

;; SPDX-License-Identifier: MIT

(def the-key "hv_live_dddddddddddddddddddddddddddddddd")

(def opts {:timeout-ms 5000 :max-bytes 65536})

(defn- serve!
  "A loopback server answering each path with `(routes path request)` ->
   `{:status :headers :body}`. Records every request's path and Authorization."
  [routes seen]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex
                              path (.getPath (.getRequestURI ex))
                              req {:authorization (.getFirst (.getRequestHeaders ex) "Authorization")
                                   :if-none-match (.getFirst (.getRequestHeaders ex) "If-None-Match")}
                              _ (swap! seen conj (assoc req :path path))
                              {:keys [status headers body]} (routes path req)
                              bytes (.getBytes (str body) StandardCharsets/UTF_8)]
                          (doseq [[k v] headers] (.add (.getResponseHeaders ex) k v))
                          (.sendResponseHeaders ex status (if (seq bytes) (count bytes) -1))
                          (with-open [out (.getResponseBody ex)] (.write out bytes))))))
    (.start server)
    server))

(defn- base [^HttpServer s] (str "http://127.0.0.1:" (.getPort (.getAddress s))))

(defmacro ^:private with-servers [bindings & body]
  `(let ~bindings
     (try ~@body
          (finally ~@(for [s (take-nth 2 bindings)] `(.stop ~s 0))))))

(def feed-xml "<rss version=\"2.0\"><channel><title>t</title></channel></rss>")

(defn- keyed-feed [{:keys [authorization]}]
  (if (= authorization (str "Bearer " the-key))
    {:status 200 :headers {"Content-Type" "application/rss+xml" "ETag" "\"v1\""} :body feed-xml}
    {:status 401 :headers {"Content-Type" "text/plain" "WWW-Authenticate" "Basic"} :body "deny/token-unknown\n"}))

(def bearer {:auth/scheme :bearer :auth/username "" :auth/secret (credential/secret the-key)})

(deftest the-credential-is-sent
  (let [seen (atom [])]
    (with-servers [a (serve! (fn [_ req] (keyed-feed req)) seen)]
      (let [res (http/fetch! (str (base a) "/feed") (assoc opts :auth bearer))]
        (is (= :ok (get-in res [:ok :status])))
        (is (= "\"v1\"" (get-in res [:ok :etag]))))
      (testing "and a refusal says so, with the server's answer"
        (let [res (http/fetch! (str (base a) "/feed") opts)]
          (is (= :rss/http-status (:error res)))
          (is (= 401 (:status res)))
          (is (= "401 without credential: deny/token-unknown" (:reason res))))))))

(deftest userinfo-becomes-a-header-and-leaves-the-url
  (let [seen (atom [])]
    (with-servers [a (serve! (fn [_ _] {:status 401 :headers {"Content-Type" "text/plain"} :body "nope"}) seen)]
      (let [url (str "http://feed:" the-key "@127.0.0.1:" (.getPort (.getAddress a)) "/feed")
            res (http/fetch! url opts)]
        (is (= (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                                              (.getBytes (str "feed:" the-key) "UTF-8")))
               (:authorization (first @seen))))
        (is (= "401 with credential: nope" (:reason res)))
        (is (not (str/includes? (pr-str res) the-key)) "the error carries the clean URL")))))

(deftest a-credential-follows-only-a-same-origin-redirect
  (let [seen-a (atom [])
        seen-b (atom [])]
    (with-servers [b (serve! (fn [_ req] (keyed-feed req)) seen-b)
                   a (serve! (fn [path req]
                               (case path
                                 "/moved" {:status 301 :headers {"Location" "/feed"}}
                                 "/away" {:status 302 :headers {"Location" (str (base b) "/feed")}}
                                 (keyed-feed req)))
                             seen-a)]
      (testing "same origin: carried"
        (is (= :ok (get-in (http/fetch! (str (base a) "/moved") (assoc opts :auth bearer)) [:ok :status])))
        (is (= [(str "Bearer " the-key) (str "Bearer " the-key)] (mapv :authorization @seen-a))))
      (testing "another origin: dropped, so that server refuses"
        (let [res (http/fetch! (str (base a) "/away") (assoc opts :auth bearer))]
          (is (= 401 (:status res)))
          (is (nil? (:authorization (last @seen-b))) "the key never reached the other origin"))))))

(deftest redirects-are-bounded-and-never-downgrade
  (let [seen (atom [])]
    (with-servers [a (serve! (fn [_ _] {:status 302 :headers {"Location" "/loop"}}) seen)]
      (is (= :rss/too-many-redirects (:error (http/fetch! (str (base a) "/loop") opts))))
      (is (= (inc http/max-redirects) (count @seen))))))

(deftest not-modified-is-a-304
  (let [seen (atom [])]
    (with-servers [a (serve! (fn [_ {:keys [if-none-match]}]
                               (if (= "\"v1\"" if-none-match)
                                 {:status 304 :headers {"ETag" "\"v1\""}}
                                 {:status 200 :headers {"ETag" "\"v1\""} :body feed-xml}))
                             seen)]
      (is (= {:status :not-modified} (:ok (http/fetch! (str (base a) "/f") (assoc opts :etag "\"v1\""))))))))
