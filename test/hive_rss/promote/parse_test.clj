(ns hive-rss.promote.parse-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-dsl.result :as r]
            [hive-rss.promote.parse :as parse]
            [hive-rss.schema :as schema]))

;; SPDX-License-Identifier: MIT

(defn hive-store-xml []
  (slurp (io/resource "fixtures/hive-store-feed.xml")))

(deftest hive-store-feed-parses
  (testing "the RSS 2.0 hive-store renders (hive-store.promote.feed/render) reads back item for item"
    (let [res (parse/parse (hive-store-xml))
          {:feed/keys [format title link items] :as feed} (:ok res)]
      (is (r/ok? res))
      (is (schema/valid-feed? feed))
      (is (= :rss format))
      (is (= "hive store releases" title))
      (is (= "https://store.hive-mcp.com" link))
      (is (= ["hive-build@0.1.17" "hive-build@0.1.16" "hive-carto@2.4.0"] (mapv :item/key items)))
      (let [[newest _ undated] items]
        (is (= "hive-build 0.1.17" (:item/title newest)))
        (is (= "https://clojars.org/io.github.hive-agi/hive-build" (:item/link newest)))
        (is (= 1789329000 (:item/published-at newest)))
        (is (= ["public" "hive-build"] (:item/categories newest)))
        (testing "the changelog is HTML escaped inside XML: it is unescaped exactly once to text"
          (is (str/includes? (:item/summary newest) "<h3>Features</h3>"))
          (is (str/includes? (:item/text newest) "Features"))
          (is (str/includes? (:item/text newest) "refuse a jar that would drop deps.edn :paths <resources> & friends"))
          (is (not (str/includes? (:item/text newest) "<li>"))))
        (is (nil? (:item/published-at undated)))
        (is (= ["private" "hive-carto"] (:item/categories undated)))))))

(def atom-xml
  "<?xml version=\"1.0\" encoding=\"utf-8\"?>
<feed xmlns=\"http://www.w3.org/2005/Atom\">
  <title>Example</title><subtitle>An Atom feed</subtitle>
  <link href=\"https://example.org/feed\" rel=\"self\"/>
  <link href=\"https://example.org/\"/>
  <id>urn:uuid:60a76c80</id><updated>2026-09-01T18:30:02Z</updated>
  <entry>
    <title>First</title>
    <link rel=\"alternate\" href=\"https://example.org/1\"/>
    <id>urn:uuid:1225c695</id>
    <published>2026-09-01T18:30:02-03:00</published>
    <author><name>Ada</name></author>
    <category term=\"clj\" label=\"Clojure\"/>
    <content type=\"html\">&lt;p&gt;Hello &amp;amp; welcome&lt;/p&gt;</content>
  </entry>
  <entry><title>No id</title><link href=\"https://example.org/2\"/><summary>plain</summary></entry>
</feed>")

(deftest atom-feed-parses
  (let [{:feed/keys [format title link description items]} (:ok (parse/parse atom-xml))]
    (is (= :atom format))
    (is (= ["Example" "https://example.org/" "An Atom feed"] [title link description]))
    (is (= ["urn:uuid:1225c695" "https://example.org/2"] (mapv :item/key items)))
    (is (= 1788298202 (:item/published-at (first items))) "2026-09-01T21:30:02Z")
    (is (= "Ada" (:item/author (first items))))
    (is (= ["Clojure"] (:item/categories (first items))))
    (is (= "Hello & welcome" (:item/text (first items))))))

(def rdf-xml
  "<?xml version=\"1.0\"?>
<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns=\"http://purl.org/rss/1.0/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">
  <channel rdf:about=\"https://example.org/\"><title>RDF</title><link>https://example.org/</link><description>d</description></channel>
  <item rdf:about=\"https://example.org/a\"><title>A</title><link>https://example.org/a</link><dc:date>2026-09-02T10:00:00Z</dc:date><dc:subject>news</dc:subject></item>
</rdf:RDF>")

(deftest rss1-feed-parses
  (let [{:feed/keys [format title items]} (:ok (parse/parse rdf-xml))]
    (is (= :rdf format))
    (is (= "RDF" title))
    (is (= "https://example.org/a" (:item/key (first items))))
    (is (= ["news"] (:item/categories (first items))))
    (is (= 1788343200 (:item/published-at (first items))))))

(deftest hostile-and-broken-documents-are-refused
  (testing "a DOCTYPE is refused outright, so no entity is ever expanded"
    (let [xxe "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss version=\"2.0\"><channel><title>&x;</title></channel></rss>"
          res (parse/parse xxe)]
      (is (= :rss/malformed (:error res)))))
  (is (= :rss/malformed (:error (parse/parse "not xml at all"))))
  (is (= :rss/malformed (:error (parse/parse ""))))
  (is (= :rss/unknown-format (:error (parse/parse "<html><body>hi</body></html>")))))

(deftest a-large-item-parses
  (testing "one text node past the JDK's 100 KB secure-processing cap (clojure.org's feed has one)"
    (let [big (apply str (repeat 150000 "x"))
          xml (str "<rss version=\"2.0\"><channel><title>t</title><item><guid>g</guid><description><![CDATA["
                   big "]]></description></item></channel></rss>")
          res (parse/parse xml)]
      (is (r/ok? res) (pr-str (:error res) (:reason res)))
      (is (= 150000 (count (:item/summary (first (:feed/items (:ok res)))))))))
  (testing "an escaped post: 100 000 predefined entity references (clojure.org's feed is one)"
    (let [escaped (apply str (repeat 25000 "&lt;p&gt;a &amp; b&lt;/p&gt;"))
          res (parse/parse (str "<rss version=\"2.0\"><channel><title>t</title><item><guid>g</guid><description>"
                                escaped "</description></item></channel></rss>"))]
      (is (r/ok? res) (pr-str (:error res) (:reason res)))
      (is (= (* 25000 (count "<p>a & b</p>"))
             (count (:item/summary (first (:feed/items (:ok res))))))))))

(deftest items-without-identity-are-dropped
  (let [xml "<rss version=\"2.0\"><channel><title>t</title><item><description>only text</description></item><item><title>kept</title></item></channel></rss>"]
    (is (= ["title:kept@"] (mapv :item/key (:feed/items (:ok (parse/parse xml))))))))

(deftest dates
  (is (= 1789329000 (parse/parse-date "Sun, 13 Sep 2026 19:50:00 GMT")))
  (is (= 1789329000 (parse/parse-date "Sun, 13 Sep 2026 16:50:00 -0300")))
  (is (= 1789329000 (parse/parse-date "2026-09-13T19:50:00Z")))
  (is (nil? (parse/parse-date "yesterday")))
  (is (nil? (parse/parse-date nil))))

(deftest html-to-text
  (is (= "a\nb & c" (parse/html->text "<p>a</p><p>b &amp; c</p>")))
  (is (= "x" (parse/html->text "<script>alert(1)</script>x")))
  (is (= "é ☃" (parse/html->text "&#233; &#x2603;")))
  (is (nil? (parse/html->text "<br/>"))))

(defspec parse-never-throws 200
  (prop/for-all [s (gen/one-of [gen/string
                                (gen/fmap #(str "<rss version=\"2.0\"><channel><item><title>" % "</title></item></channel></rss>")
                                          gen/string-alphanumeric)])]
    (let [res (parse/parse s)]
      (or (r/err? res) (schema/valid-feed? (:ok res))))))
