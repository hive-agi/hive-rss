(ns hive-rss.promote.credential-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-rss.promote.credential :as credential]
            [hive-rss.schema :as schema]
            [malli.core :as m]))

;; SPDX-License-Identifier: MIT

(def key-value "hv_live_dddddddddddddddddddddddddddddddd")

(defn- decoded-basic [header]
  (String. (.decode (java.util.Base64/getDecoder) (subs header 6)) "UTF-8"))

(deftest a-secret-never-prints
  (let [s (credential/secret key-value)
        cred {:auth/scheme :basic :auth/username "u" :auth/secret s}]
    (doseq [shown [(str s) (pr-str s) (pr-str cred) (str cred) (pr-str [cred])
                   (ex-message (ex-info (str "boom " cred) {:cred cred}))
                   (pr-str (ex-data (ex-info "boom" {:cred cred})))]]
      (is (not (str/includes? shown key-value)) shown)
      (is (str/includes? shown "#secret[redacted]") shown))
    (is (= s (credential/secret key-value)) "equal by value")
    (is (nil? (credential/secret "  ")))
    (is (m/validate schema/Credential cred))))

(deftest userinfo-is-split-off-the-url
  (testing "user and password: the password is the secret"
    (let [{:keys [url credential]} (credential/split-url (str " https://feed:" key-value "@store.test/api/feed?package=x "))]
      (is (= "https://store.test/api/feed?package=x" url))
      (is (= {:auth/scheme :basic :auth/username "feed" :auth/secret (credential/secret key-value)} credential))))
  (testing "a bare token as the user"
    (let [{:keys [url credential]} (credential/split-url (str "http://" key-value "@localhost:8599/api/feed"))]
      (is (= "http://localhost:8599/api/feed" url))
      (is (= credential/default-username (:auth/username credential)))
      (is (= (credential/secret key-value) (:auth/secret credential)))))
  (testing "percent-encoding is decoded, a plus stays a plus"
    (is (= (credential/secret "a:b+c@d")
           (:auth/secret (:credential (credential/split-url "https://u:a%3Ab+c%40d@h.test/f"))))))
  (testing "no userinfo, and an @ past the authority, are left alone"
    (is (= {:url "https://h.test/f" :credential nil} (credential/split-url "https://h.test/f")))
    (is (= {:url "https://h.test/u/@me" :credential nil} (credential/split-url "https://h.test/u/@me")))))

(deftest auth-config-resolves-against-the-environment
  (let [env {"HIVE_STORE_KEY" key-value "BLANK" " "}]
    (is (= {:auth/scheme :basic :auth/username "hive-rss" :auth/secret (credential/secret key-value)}
           (:ok (credential/from-config {:secret-env "HIVE_STORE_KEY"} env))))
    (is (= {:auth/scheme :bearer :auth/username "me" :auth/secret (credential/secret "t")}
           (:ok (credential/from-config {"scheme" "Bearer" "secret" "t" "username" "me"} env)))
        "JSON keys and any case")
    (is (= :basic (:auth/scheme (:ok (credential/from-config {:auth/secret "t"} env)))))
    (testing "every refusal names the problem and no secret"
      (doseq [[auth why] [[{:secret-env "MISSING"} #"MISSING is unset"]
                          [{:secret-env "BLANK"} #"BLANK is unset or blank"]
                          [{:scheme "digest" :secret key-value} #"unknown scheme"]
                          [{:secret ""} #"non-blank"]
                          ["not a map" #"must be a map"]]]
        (let [res (credential/from-config auth env)]
          (is (= :rss/invalid-auth (:error res)) (pr-str auth))
          (is (re-find why (:reason res)))
          (is (not (str/includes? (pr-str res) key-value))))))))

(deftest the-header-by-scheme
  (let [s (credential/secret key-value)]
    (is (= (str "feed:" key-value)
           (decoded-basic (credential/authorization {:auth/scheme :basic :auth/username "feed" :auth/secret s}))))
    (is (= (str "Bearer " key-value)
           (credential/authorization {:auth/scheme :bearer :auth/username "" :auth/secret s})))))

(deftest origins
  (is (credential/same-origin? "https://h.test/a" "https://H.test:443/b?x"))
  (is (credential/same-origin? "http://h.test/a" "http://h.test:80/b"))
  (is (not (credential/same-origin? "https://h.test/a" "http://h.test/a")))
  (is (not (credential/same-origin? "https://h.test/a" "https://evil.test/a")))
  (is (not (credential/same-origin? "https://h.test/a" "https://h.test:8443/a")))
  (is (not (credential/same-origin? "https://h.test/a" "not a url at all"))))

(deftest an-on-demand-url-finds-its-credential
  (let [cred {:auth/scheme :bearer :auth/username "" :auth/secret (credential/secret "t")}
        subs [{:feed/id "a" :feed/url "https://h.test/a"}
              {:feed/id "b" :feed/url "https://h.test/b" :feed/auth cred}]]
    (is (= {:url "https://h.test/b" :credential cred} (credential/for-url subs "https://h.test/b")))
    (is (= {:url "https://h.test/a" :credential nil} (credential/for-url subs "https://h.test/a")))
    (is (= :basic (:auth/scheme (:credential (credential/for-url subs "https://u:p@h.test/b"))))
        "credentials in the URL win over the subscription's")))
