(ns whisper2cyanite.core
  (:import (java.io RandomAccessFile))
  (:require [clj-whisper.core :as whisper]
            [com.climate.claypoole :as cp]
            [clojure.java.io :as io]
            [clj-progress.core :as prog]
            [whisper2cyanite.utils :as utils]
            [whisper2cyanite.metric-store :as mstore]
            [whisper2cyanite.path-store :as pstore]))

(def ^:const default-jobs 1)
(def ^:const default-min-ttl 3600)

(defn- migrate-archive
  "Migrate an archive."
  [ra-file file archive mstore pstore tenant path from to retention options]
  (let [rollup (:seconds-per-point archive)
        points (:points archive)
        period (if retention (/ retention rollup) points)
        retention (if retention retention (* rollup points))
        data (whisper/fetch-archive-seq-ra ra-file file archive from to)
        series (:series data)
        path-stored? (atom false)
        run (:run options false)
        min-ttl (:min-ttl options default-min-ttl)
        disable-metric-store (:disable-metric-store options false)
        disable-path-store (:disable-path-store options false)]
    (doseq [point series]
      (let [time (first point)
            value (last point)]
        (when-not (= time 0)
          (let [ttl (- time (- (utils/now) retention))]
            (when (> ttl min-ttl)
              (when run
                (when-not disable-metric-store
                  (mstore/insert mstore tenant rollup period path time
                                 value ttl))
                (when-not @path-stored?
                  (when-not disable-path-store
                    (pstore/insert pstore tenant path))
                  (swap! path-stored? (fn [_] true)))))))))))

(defn- migrate-file
  "Migrate a file."
  [file dir mstore pstore tenant from to options]
  (let [path (whisper/file-to-name file dir)
        ra-file (RandomAccessFile. file "r")
        rollups (:rollups options)]
    (try
      (let [info (whisper/read-info-ra ra-file file)
            archives (:archives info)]
        (doseq [archive archives]
          (let [seconds-per-point (:seconds-per-point archive)]
            (when (or (not (> (count rollups) 0))
                      (contains? rollups seconds-per-point))
              (let [retention (get rollups seconds-per-point
                                   (:retention archive))]
                (migrate-archive ra-file file archive mstore pstore tenant path
                                 from to retention options))))))
      (finally
        (.close ra-file)))))

(defn migrate
  "Do migration."
  [dir tenant from to cass-host es-url options]
  (let [files (sort (whisper/get-paths dir))
        files-count (count files)
        from (if from from 0)
        to (if to to utils/epoch-future)
        jobs (:jobs options default-jobs)
        pool (cp/threadpool jobs)
        mstore (mstore/cassandra-metric-store cass-host options)
        pstore (pstore/elasticsearch-metric-store es-url options)
        disable-progress (:disable-progress options false)
        progress-fn (if disable-progress #(println %) (fn [_] (prog/tick)))
        migrate-fn (fn [file]
                     (progress-fn file)
                     (migrate-file file dir mstore pstore tenant from to
                                   options))]
    (try
      (prog/set-progress-bar!
       "[:bar] :percent :done/:total Elapsed :elapseds ETA :etas")
      (prog/config-progress-bar! :width 35)
      (when-not disable-progress
        (println)
        (prog/init files-count))
      (dorun (cp/pmap pool migrate-fn files))
      (when-not disable-progress
       (prog/done))
      (finally
        (mstore/shutdown mstore)
        (pstore/shutdown pstore)))))

(defn list-paths
  "List paths."
  [dir]
  (doseq [path (sort (whisper/get-paths dir))]
    (println (whisper/file-to-name path dir))))

(defn show-info
  "Show path info."
  [file]
  (let [info (whisper/read-info file)
        archives (:archives info)]
    (println "Aggregation method:" (name (:aggregation-method info)))
    (println "Max retention:     " (:max-retention info))
    (println "X files factor:    " (:x-files-factor info))
    (println "Archives:")
    (doseq [[archive number] (map list archives (range (count archives)))]
      (println (format "  Archive %s:" number))
      (println "    Seconds per point:" (:seconds-per-point archive))
      (println "    Retention:        " (str (:retention archive)))
      (println "    Points:           " (:points archive))
      (println "    Offset:           " (:offset archive))
      (println "    Size:             " (str (:size archive))))))
