(ns hive-rss.promote.parse
  "Feed XML to data: RSS 2.0, RSS 1.0 (RDF) and Atom 1.0. No IO.

   The document is read by the JDK's DOM parser with DOCTYPEs refused, so an
   entity declaration (billion laughs, external entities) cannot be expanded."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-rss.schema :as schema]
            [malli.core :as m])
  (:import (java.io StringReader)
           (java.time Instant OffsetDateTime ZonedDateTime)
           (java.time.format DateTimeFormatter DateTimeParseException)
           (javax.xml XMLConstants)
           (javax.xml.parsers DocumentBuilderFactory)
           (org.w3c.dom Element Node)
           (org.xml.sax ErrorHandler InputSource)))

;; SPDX-License-Identifier: MIT

(def ^:private atom-ns "http://www.w3.org/2005/Atom")
(def ^:private rss1-ns "http://purl.org/rss/1.0/")
(def ^:private dc-ns "http://purl.org/dc/elements/1.1/")
(def ^:private content-ns "http://purl.org/rss/1.0/modules/content/")

;; ---------------------------------------------------------------------------
;; text

(def ^:private entities
  {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'" "#39" "'" "nbsp" " "})

(defn- decode-entity [[whole name]]
  (or (get entities name)
      (when-let [[_ hex dec] (re-matches #"#(?:[xX]([0-9a-fA-F]{1,6})|([0-9]{1,7}))" name)]
        (let [cp (if hex (Long/parseLong hex 16) (Long/parseLong dec))]
          (when (Character/isValidCodePoint (int (min cp Integer/MAX_VALUE)))
            (String. (Character/toChars (int cp))))))
      whole))

(defn html->text
  "The readable text of an HTML fragment: block ends become newlines, tags go,
   entities are decoded once, runs of blank space collapse."
  [html]
  (when html
    (let [s (-> html
                (str/replace #"(?is)<(script|style)[^>]*>.*?</\1>" "")
                (str/replace #"(?i)<br\s*/?>|</(p|div|li|h[1-6]|tr|ul|ol)>" "\n")
                (str/replace #"(?s)<[^>]*>" "")
                (str/replace #"&([a-zA-Z]+|#[xX]?[0-9a-fA-F]+);" decode-entity)
                (str/replace #"[ \t\x0B\f\r]+" " ")
                (str/replace #" *\n[ \n]*" "\n")
                str/trim)]
      (not-empty s))))

;; ---------------------------------------------------------------------------
;; dates

(defn- try-parse [f]
  (try (f) (catch DateTimeParseException _ nil)))

(defn parse-date
  "Epoch seconds of an RFC 1123 (RSS) or ISO 8601 (Atom, Dublin Core) date, or
   nil when the text is neither."
  [text]
  (when-let [t (some-> text str/trim not-empty)]
    (some-> (or (try-parse #(.toInstant (ZonedDateTime/parse t DateTimeFormatter/RFC_1123_DATE_TIME)))
                (try-parse #(.toInstant (OffsetDateTime/parse t)))
                (try-parse #(Instant/parse t))
                ;; RSS in the wild: a single-digit day, or a zone name like EST.
                (try-parse #(.toInstant (ZonedDateTime/parse t (DateTimeFormatter/ofPattern "EEE, d MMM yyyy HH:mm:ss zzz" java.util.Locale/ENGLISH)))))
            (.getEpochSecond)
            (as-> s (when-not (neg? s) s)))))

;; ---------------------------------------------------------------------------
;; DOM

(defn- secure-factory ^DocumentBuilderFactory []
  (doto (DocumentBuilderFactory/newInstance)
    (.setNamespaceAware true)
    (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true)
    (.setFeature "http://xml.org/sax/features/external-general-entities" false)
    (.setFeature "http://xml.org/sax/features/external-parameter-entities" false)
    (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING true)
    ;; Secure processing also caps text built from entity references at 100 KB
    ;; per node and in total, and their count at 64 000. Real feeds exceed
    ;; them: a whole post escaped into one <description> is mostly &lt; and
    ;; &amp;. The caps exist to bound expansion of declared entities; with
    ;; DOCTYPEs refused only the five predefined ones can occur, and the fetch
    ;; already bounds the document's size.
    (.setAttribute "http://www.oracle.com/xml/jaxp/properties/maxGeneralEntitySizeLimit" "0")
    (.setAttribute "http://www.oracle.com/xml/jaxp/properties/totalEntitySizeLimit" "0")
    (.setAttribute "http://www.oracle.com/xml/jaxp/properties/entityExpansionLimit" "0")
    (.setXIncludeAware false)
    (.setExpandEntityReferences false)))

(defn- children [^Node node]
  (let [nl (.getChildNodes node)]
    (into [] (comp (map #(.item nl %))
                   (filter #(instance? Element %)))
          (range (.getLength nl)))))

(defn- local-name [^Element e]
  (or (.getLocalName e) (.getNodeName e)))

(defn- in-ns? [^Element e ns]
  (= ns (.getNamespaceURI e)))

(defn- kids
  "Child elements of `node` named `nm`, in namespace `ns` when one is given
   (nil means no namespace)."
  [node nm ns]
  (filterv #(and (= nm (local-name %)) (= ns (.getNamespaceURI ^Element %))) (children node)))

(defn- kid [node nm ns] (first (kids node nm ns)))

(defn- text-of [^Element e]
  (some-> e .getTextContent str/trim not-empty))

(defn- attr [^Element e a]
  (some-> e (.getAttribute a) not-empty))

;; ---------------------------------------------------------------------------
;; items

(defn- item-key
  "A stable identity for an item: its guid or id, else its link, else its title
   and date. Two polls of one feed agree on it."
  [{:item/keys [title link published-at]} guid]
  (or guid link
      (when (or title published-at) (str "title:" title "@" published-at))))

(defn- finish-item [item guid]
  (when-let [k (item-key item guid)]
    (assoc item
           :item/key k
           :item/text (html->text (:item/summary item)))))

(defn- rss-item [el ns]
  (let [summary (or (text-of (kid el "encoded" content-ns))
                    (text-of (kid el "description" ns)))]
    (finish-item
     {:item/title (text-of (kid el "title" ns))
      :item/link (text-of (kid el "link" ns))
      :item/summary summary
      :item/published-at (parse-date (or (text-of (kid el "pubDate" ns))
                                         (text-of (kid el "date" dc-ns))))
      :item/categories (into (vec (keep text-of (kids el "category" ns)))
                             (keep text-of (kids el "subject" dc-ns)))
      :item/author (or (text-of (kid el "author" ns)) (text-of (kid el "creator" dc-ns)))}
     (or (text-of (kid el "guid" ns)) (attr el "rdf:about")))))

(defn- atom-link
  "The alternate link of an Atom element: rel absent or \"alternate\"."
  [el]
  (some (fn [l] (when (contains? #{nil "alternate"} (attr l "rel")) (attr l "href")))
        (kids el "link" atom-ns)))

(defn- atom-item [el]
  (finish-item
   {:item/title (text-of (kid el "title" atom-ns))
    :item/link (atom-link el)
    :item/summary (or (text-of (kid el "content" atom-ns))
                      (text-of (kid el "summary" atom-ns)))
    :item/published-at (parse-date (or (text-of (kid el "published" atom-ns))
                                       (text-of (kid el "updated" atom-ns))))
    :item/categories (vec (keep #(or (attr % "label") (attr % "term")) (kids el "category" atom-ns)))
    :item/author (some-> (kid el "author" atom-ns) (kid "name" atom-ns) text-of)}
   (text-of (kid el "id" atom-ns))))

;; ---------------------------------------------------------------------------
;; feeds

(defn- rss2 [^Element root]
  (let [ch (kid root "channel" nil)]
    (when ch
      {:feed/format :rss
       :feed/title (text-of (kid ch "title" nil))
       :feed/link (text-of (kid ch "link" nil))
       :feed/description (text-of (kid ch "description" nil))
       :feed/items (into [] (keep #(rss-item % nil)) (kids ch "item" nil))})))

(defn- rdf [^Element root]
  (let [ch (kid root "channel" rss1-ns)]
    {:feed/format :rdf
     :feed/title (some-> ch (kid "title" rss1-ns) text-of)
     :feed/link (some-> ch (kid "link" rss1-ns) text-of)
     :feed/description (some-> ch (kid "description" rss1-ns) text-of)
     :feed/items (into [] (keep #(rss-item % rss1-ns)) (kids root "item" rss1-ns))}))

(defn- atom-feed [^Element root]
  {:feed/format :atom
   :feed/title (text-of (kid root "title" atom-ns))
   :feed/link (atom-link root)
   :feed/description (text-of (kid root "subtitle" atom-ns))
   :feed/items (into [] (keep atom-item) (kids root "entry" atom-ns))})

(defn parse
  "Result of the feed in `xml`: `{:feed/format :feed/title :feed/link
   :feed/description :feed/items}`, items in document order. Items with no
   guid, link, title or date have no identity and are dropped.

   Errors: `:rss/malformed` (not XML, or a DOCTYPE) and `:rss/unknown-format`
   (XML, but not a feed)."
  [xml]
  (let [doc (try
              (.parse (doto (.newDocumentBuilder (secure-factory))
                        ;; The default handler prints every fatal error to
                        ;; stderr before throwing; the throw is the report.
                        (.setErrorHandler (reify ErrorHandler
                                            (warning [_ _])
                                            (error [_ e] (throw e))
                                            (fatalError [_ e] (throw e)))))
                      (InputSource. (StringReader. (str xml))))
              (catch Exception e e))]
    (if (instance? Exception doc)
      (r/err :rss/malformed {:reason (ex-message doc)})
      (let [root (.getDocumentElement ^org.w3c.dom.Document doc)
            feed (cond
                   (and (= "rss" (local-name root)) (nil? (.getNamespaceURI root))) (rss2 root)
                   (= "RDF" (local-name root)) (rdf root)
                   (and (= "feed" (local-name root)) (in-ns? root atom-ns)) (atom-feed root))]
        (if (and feed (schema/valid-feed? feed))
          (r/ok feed)
          (r/err :rss/unknown-format {:root (local-name root)}))))))

(m/=> html->text [:=> [:cat [:maybe :string]] [:maybe :string]])
(m/=> parse-date [:=> [:cat [:maybe :string]] [:maybe schema/EpochSeconds]])
