(ns whisper2cyanite.utils
  (:require [clojure.core.async :refer [chan timeout go alts! >! close!]]))

(def ^:const epoch-future 2085436800)

(defn now
  "Get now as a Unix epoch."
  []
  (quot (System/currentTimeMillis) 1000))

(defmacro go-while
  [test body]
  `(go
     (while ~test
       ~body)))

(defmacro go-forever
  [body]
  `(go-while true ~body))

(defn partition-or-time
  "Returns a channel that will either contain vectors of n items taken from ch or
  if beat-rate millis elapses then a vector with the available items. The
  final vector in the return channel may be smaller than n if ch closed before
  the vector could be completely filled."
  [n ch beat-rate buf-or-n]
  (let [out (chan buf-or-n)]
    (go (loop [arr (make-array Object n)
               idx 0
               beat (timeout beat-rate)]
          (let [[v c] (alts! [ch beat])]
            (if (= c beat)
              (do
                (if (> idx 0)
                  (do (>! out (vec (take idx arr)))
                      (recur (make-array Object n)
                             0
                             (timeout beat-rate)))
                  (recur arr idx (timeout beat-rate))))
              (if-not (nil? v)
                (do (aset ^objects arr idx v)
                    (let [new-idx (inc idx)]
                      (if (< new-idx n)
                        (recur arr new-idx beat)
                        (do (>! out (vec arr))
                            (recur (make-array Object n) 0 (timeout beat-rate))))))
                (do (when (> idx 0)
                      (let [narray (make-array Object idx)]
                        (System/arraycopy arr 0 narray 0 idx)
                        (>! out (vec narray))))
                    (close! out)))))))
    out))
