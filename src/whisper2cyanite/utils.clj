(ns whisper2cyanite.utils
  (:require [clojure.string :as str]))

(def ^:const epoch-future 2085436800)

(defn now
  "Get now as a Unix epoch."
  []
  (quot (System/currentTimeMillis) 1000))
