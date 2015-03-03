(ns whisper2cyanite.utils
  (:require [clojure.core.async :refer [chan timeout go alts! >! close!]]
            [clojure.java.io :as io]))

(defn now
  "Get now as a Unix epoch."
  []
  (quot (System/currentTimeMillis) 1000))

(defmacro go-while
  [test body]
  `(go
     (while ~test
       ~body)))

(defn get-cpath
  "Get canonical path."
  [path]
  (.getCanonicalPath (io/file path)))

(defn is-directory?
  "Is path a directory?"
  [path]
  (.isDirectory (io/file path)))

(defn extract-file
  "Extract a file name from a path."
  [path]
  (let [f (io/file path)]
    (if (.isFile f) (.getName f) "")))

(defn extract-extension
  "Extract a file extension from a path."
  [path]
  (if-let [ext (re-find #"\..*$" (extract-file path))] ext ""))

(defn extract-directory
  "Extract a directory from a path."
  [path]
  (if (is-directory? path)
    path
    (.getParent (io/file path))))
