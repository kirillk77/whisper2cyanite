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

(defn- calc-ttl
  "Calc TTL."
  [time retention]
  (- time (- (utils/now) retention)))

(defn- migrate-points
  "Migrate points."
  [mstore options tenant rollup period retention path series]
  (let [min-ttl (:min-ttl options default-min-ttl)
        run (:run options false)]
    (doseq [point series]
      (let [time (first point)
            value (last point)
            ttl (calc-ttl time retention)]
        (when (not= time 0)
          (when (> ttl min-ttl)
            (log/trace
             (format (str "Migrating point: rollup: %s, period: %s, "
                          "path: %s, time: %s, value: %s, ttl: %s")
                     rollup period path time value ttl))
            (when (and run mstore)
              (mstore/insert mstore tenant rollup period path time value
                             ttl))))))))

(defn- process-archive
  "Process an archive."
  [ra-file file archive mstore tenant path from to retention options points-fn]
  (let [rollup (:seconds-per-point archive)
        points (:points archive)
        period (if retention (/ retention rollup) points)
        retention (if retention retention (* rollup points))
        data (whisper/fetch-archive-seq-ra ra-file file archive from to)
        series (:series data)]
    (points-fn mstore options tenant rollup period retention path series)))

(defn- migrate-path
  "Migrate a path."
  [pstore tenant path]
  (when pstore
    (pstore/insert pstore tenant path)))

(defn- process-file
  "Process a file."
  [file dir mstore pstore tenant from to options path-fn points-fn]
  (let [path (whisper/file-to-name file dir)]
    (path-fn pstore tenant path)
    (let [ra-file (RandomAccessFile. file "r")
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
                  (process-archive ra-file file archive mstore tenant path
                                   from to retention options points-fn))))))
        (finally
          (.close ra-file))))))

(defn- get-paths
  "Get paths."
  [source]
  (let [paths (if (.isDirectory (io/file source))
                (whisper/get-paths source)
                [source])]
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
  (let [dir (utils/get-cpath dir)
        root-dir (:root-dir options nil)]
    (if-not root-dir
      dir
      (let [root-dir-c (utils/get-cpath root-dir)]
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

(defn- get-from-to
  "Get and check FROM and TO time."
  [options]
  (let [from (:from options)
        to (:to options)]
    (when (and from to (> from to))
      (throw (Exception. (str "Invalid TO value: " to))))
    {:from from :to to}))

(defn- process
  "Process a Whisper database."
  [source tenant cass-host es-url options path-fn points-fn start-title title]
  (wlog/set-logging! options)
  (try
    (wlog/info start-title)
    (let [root-dir (get-root-dir source options)
          files (get-paths source)
          files-count (count files)
          {:keys [from to]} (get-from-to options)
          jobs (:jobs options default-jobs)
          pool (cp/threadpool jobs)
          mstore (create-mstore cass-host options)
          pstore (create-pstore es-url options)
          process-file-fn (fn [file]
                            (wlog/info "Processing path: " file)
                            (when-not @wlog/print-log?
                              (prog/tick))
                            (process-file file root-dir mstore pstore tenant
                                          from to options path-fn points-fn))]
      (try
        (prog/set-progress-bar!
         "[:bar] :percent :done/:total Elapsed :elapseds ETA :etas")
        (prog/config-progress-bar! :width pbar-width)
        (newline)
        (wlog/info (str title ":"))
        (when-not @wlog/print-log?
          (println title)
          (prog/init files-count))
        (dorun (cp/pmap pool process-file-fn files))
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

(defn migrate
  "Do migration."
  [source tenant cass-host es-url options]
  (process source tenant cass-host es-url options migrate-path migrate-points
           "Starting migration" "Migrating"))

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

(defn- get-archive-by-rollup
  "Get archive by rollup."
  [archives rollup]
  (let [archive (filter #(= rollup (:seconds-per-point %)) archives)]
    (if (count archive)
      (first archive)
      nil)))

(defn fetch
  "Fetch time series."
  [file rollup options]
  (let [{:keys [from to]} (get-from-to options)
        ra-file (RandomAccessFile. file "r")]
    (try
      (let [info (whisper/read-info-ra ra-file file)
            archives (:archives info)
            archive (get-archive-by-rollup archives rollup)]
        (when-not archive
          (throw (Exception. (str "Unknown rollup: " rollup))))
        (let [series (-> (whisper/fetch-archive-seq-ra ra-file file archive
                                                       from to)
                         (:series)
                         (whisper/sort-series))]
          (doseq [point series]
            (let [time (first point)
                  value (last point)]
              (println time value)))))
      (finally
        (.close ra-file)))))
