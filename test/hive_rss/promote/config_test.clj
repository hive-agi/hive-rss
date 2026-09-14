(ns hive-rss.promote.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-rss.promote.config :as config]))

;; SPDX-License-Identifier: MIT

(def env {"HOME" "/home/u"})

(deftest defaults-poll-once-a-day
  (let [s (:ok (config/settings {} env))]
    (is (= 1 (:rss/fetches-per-day s)))
    (is (= [] (:rss/feeds s)))
    (is (= "/home/u/.local/state/hive-rss/state.edn" (:rss/state-file s)))))

(deftest xdg-state-home-wins
  (is (= "/x/state/hive-rss/state.edn"
         (:rss/state-file (:ok (config/settings {} {"HOME" "/home/u" "XDG_STATE_HOME" "/x/state"}))))))

(deftest feeds-from-urls-maps-and-json-keys
  (let [s (:ok (config/settings {"rss/fetches-per-day" 4
                                 "rss/feeds" ["https://store.hive-mcp.com/api/feed"
                                              {"url" "https://example.org/atom.xml" "id" "ex" "tags" ["a"]}
                                              {:feed/url "https://example.org/other" :feed/id "ex"}
                                              {:feed/id "no-url"}]}
                                env))]
    (is (= 4 (:rss/fetches-per-day s)))
    (is (= [{:feed/id "store-hive-mcp-com-api-feed" :feed/url "https://store.hive-mcp.com/api/feed"}
            {:feed/id "ex" :feed/url "https://example.org/atom.xml" :feed/tags ["a"]}]
           (:rss/feeds s))
        "a feed without a URL is dropped; a repeated id keeps its first entry")))

(deftest invalid-config-is-refused-with-reasons
  (testing "the rate is bounded: at least daily, at most every 15 minutes"
    (is (= :rss/invalid-config (:error (config/settings {:rss/fetches-per-day 0} env))))
    (is (= :rss/invalid-config (:error (config/settings {:rss/fetches-per-day 97} env))))
    (is (some? (:problems (config/settings {:rss/fetches-per-day 0} env)))))
  (is (= :rss/invalid-config (:error (config/settings {:rss/feeds ["ftp://nope"]} env))))
  (is (= :rss/invalid-config (:error (config/settings {:rss/duration "forever"} env)))))

(deftest a-feed-carries-credentials-without-leaking-them
  (let [the-key "hv_live_dddddddddddddddddddddddddddddddd"
        s (:ok (config/settings {"rss/feeds" [{"url" "https://store.hive-mcp.com/api/feed"
                                               "id" "store-private"
                                               "auth" {"secret-env" "HIVE_STORE_KEY"}}
                                              (str "https://feed:" the-key "@store.test/api/feed")]}
                                (assoc env "HIVE_STORE_KEY" the-key)))
        [by-env by-url] (:rss/feeds s)]
    (is (= :basic (get-in by-env [:feed/auth :auth/scheme])))
    (is (= "https://store.test/api/feed" (:feed/url by-url)) "userinfo leaves the URL")
    (is (= "store-test-api-feed" (:feed/id by-url)) "and never reaches the feed id")
    (is (= (get-in by-env [:feed/auth :auth/secret]) (get-in by-url [:feed/auth :auth/secret])))
    (is (not (clojure.string/includes? (pr-str s) the-key)) "settings print without the key"))
  (testing "a credential that cannot be resolved refuses the config, by feed, without a secret"
    (let [res (config/settings {:rss/feeds [{:feed/url "https://h.test/f" :feed/id "f"
                                             :feed/auth {:secret-env "NOPE"}}]}
                               env)]
      (is (= :rss/invalid-config (:error res)))
      (is (re-find #"NOPE is unset" (get-in res [:problems :rss/feeds :feed/auth "f"]))))))

(deftest slugs
  (is (= "example-org-feed-xml" (config/slug "https://www.example.org/feed.xml")))
  (is (= "feed" (config/slug "https://"))))
