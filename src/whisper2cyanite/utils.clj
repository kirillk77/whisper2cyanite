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
