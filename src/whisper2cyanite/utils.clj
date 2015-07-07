(ns whisper2cyanite.utils
  (:require [clojure.core.async :refer [chan timeout go alts! >! close!]]
            [me.raynes.fs :as fs]))

(defn now
  "Get now as a Unix epoch."
  []
  (quot (System/currentTimeMillis) 1000))

(defmacro go-while
  [test body]
  `(go
     (while ~test
       ~body)))

(defn extract-directory
  "Extract a directory from a path."
  [path]
  (if (fs/directory? path) path (str (fs/parent path))))

(defn whisper?
  "Is the file a Whisper database?"
  [path]
  (= (fs/extension path) ".wsp"))

(defn ceil
  "Returns the least integer greater than or equal to n."
  [n]
  (int (Math/ceil (double n))))
