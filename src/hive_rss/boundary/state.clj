(ns hive-rss.boundary.state
  "Per-feed state on disk: seen item keys, validators, and when each feed was
   last polled. One EDN file, written atomically, readable only by its owner.

   A missing or unreadable file is an empty state rather than an error: the
   worst case is that items already filed are offered again, and the memory
   sink refuses duplicates by content hash."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [hive-rss.schema :as schema]
            [malli.core :as m])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file CopyOption Files LinkOption OpenOption Path StandardCopyOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

;; SPDX-License-Identifier: MIT

(def ^:private valid-state? (m/validator schema/State))

(defn load-state
  "The state in `path`, or {} when it is absent, unreadable or malformed."
  [path]
  (try
    (let [f (io/file path)]
      (if (.exists f)
        (let [s (edn/read-string (slurp f))]
          (if (valid-state? s) s {}))
        {}))
    (catch Exception _ {})))

(defn- ensure-private-dir! [^Path dir]
  (when-not (Files/exists dir (make-array LinkOption 0))
    (Files/createDirectories
     dir
     (into-array FileAttribute
                 (try [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString "rwx------"))]
                      (catch UnsupportedOperationException _ []))))))

(defn save-state!
  "Write `state` to `path` via a sibling temp file and an atomic move."
  [path state]
  (let [target (.toPath (io/file path))
        dir (or (.getParent target) (.toPath (io/file ".")))]
    (ensure-private-dir! dir)
    (let [tmp (Files/createTempFile dir ".state" ".edn" (make-array FileAttribute 0))]
      (Files/writeString tmp (pr-str state) StandardCharsets/UTF_8 (make-array OpenOption 0))
      (Files/move tmp target (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                     StandardCopyOption/REPLACE_EXISTING])))
    state))
