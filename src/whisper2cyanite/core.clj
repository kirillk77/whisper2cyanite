(ns whisper2cyanite.core
  (:import (java.io RandomAccessFile))
  (:require [clj-whisper.core :as whisper]
            [com.climate.claypoole :as cp]
            [clojure.java.io :as io]
            [clj-progress.core :as prog]
            [clojure.tools.logging :as log]
            [whisper2cyanite.utils :as utils]
            [whisper2cyanite.logging :as wlog]
            [whisper2cyanite.metric-store :as mstore]
            [whisper2cyanite.path-store :as pstore]))

(def ^:const default-jobs 1)
(def ^:const default-min-ttl 3600)

(def ^:const pbar-width 35)

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
        min-ttl (:min-ttl options default-min-ttl)]
    (doseq [point series]
      (let [time (first point)
            value (last point)]
        (when-not (= time 0)
          (let [ttl (- time (- (utils/now) retention))]
            (when (> ttl min-ttl)
              (when run
                (when mstore
                  (mstore/insert mstore tenant rollup period path time
                                 value ttl))
                (when-not @path-stored?
                  (when pstore
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

(defn- get-paths
  "Get paths."
  [dir]
  (let [paths (whisper/get-paths dir)]
    (newline)
    (wlog/info "Getting paths...")
    (when-not @wlog/print-log?
      (println "Getting paths")
      (prog/set-progress-bar! "[:bar] :done")
      (prog/config-progress-bar! :width pbar-width)
      (prog/init 0)
      (doseq [path paths]
        (prog/tick))
      (prog/done))
    (wlog/info (format "Found %s paths" (count paths)))
    (sort paths)))

(defn- get-root-dir
  "Get root directory."
  [dir options]
  (let [dir (.getCanonicalPath (io/file dir))
        root-dir (:root-dir options nil)]
    (if-not root-dir
      dir
      (let [root-dir-c (.getCanonicalPath (io/file root-dir))]
        (when (or (> (count root-dir-c) (count dir))
                  (not= (subs dir 0 (count root-dir-c)) root-dir-c))
          (throw (Exception. (str "Invalid root directory: " root-dir))))
        root-dir-c))))

(defn- create-mstore
  "Create metric store."
  [cass-host options]
  (if-not (:disable-metric-store options false)
    (try
      (mstore/cassandra-metric-store cass-host options)
      (catch Exception e
        (wlog/fatal "Error creating metric store: " e)))
    nil))

(defn- create-pstore
  "Create path store."
  [es-url options]
  (if-not (:disable-path-store options false)
    (try
      (pstore/elasticsearch-metric-store es-url options)
      (catch Exception e
        (wlog/fatal "Error creating path store: " e)))
    nil))

(defn migrate
  "Do migration."
  [dir tenant from to cass-host es-url options]
  (wlog/set-logging! options)
  (try
    (wlog/info "Starting migration")
    (let [root-dir (get-root-dir dir options)
          files (get-paths dir)
          files-count (count files)
          from (if from from 0)
          to (if to to utils/epoch-future)
          jobs (:jobs options default-jobs)
          pool (cp/threadpool jobs)
          mstore (create-mstore cass-host options)
          pstore (create-pstore es-url options)
          migrate-fn (fn [file]
                       (wlog/info "Processing path: " file)
                       (when-not @wlog/print-log?
                         (prog/tick))
                       (migrate-file file root-dir mstore pstore tenant from to
                                     options))]
      (try
        (prog/set-progress-bar!
         "[:bar] :percent :done/:total Elapsed :elapseds ETA :etas")
        (prog/config-progress-bar! :width pbar-width)
        (newline)
        (wlog/info "Migrating:")
        (when-not @wlog/print-log?
          (println "Migrating")
          (prog/init files-count))
        (dorun (cp/pmap pool migrate-fn files))
        (when-not @wlog/print-log?
          (prog/done))
        (catch Exception e
          (wlog/unhandled-error e))
        (finally
          (when mstore
            (mstore/shutdown mstore))
          (when pstore
            (pstore/shutdown pstore)))))
    (catch Exception e
      (wlog/unhandled-error e)))
  (wlog/exit 0))

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
