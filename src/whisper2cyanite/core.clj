(ns whisper2cyanite.core
  (:import (java.io RandomAccessFile))
  (:require [clj-whisper.core :as whisper]
            [clojure.string :as str]
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

(def mstore-processed (atom 0))
(def pstore-processed (atom 0))

(def mstore-error-paths (atom (sorted-set)))
(def pstore-error-paths (atom (sorted-set)))

(defprotocol Processor
  (process-metrics [this rollup period retention path series])
  (process-path [this path]))

(defn- calc-ttl
  "Calc a TTL value."
  [time retention]
  (- time (- (utils/now) retention)))

(defn- log-point
  "Log a point."
  [msg rollup period path time value ttl]
  (log/trace
   (format (str "%s: rollup: %s, period: %s, path: %s, time: %s, "
                "value: %s, ttl: %s") msg rollup period path time value ttl)))

(defn- migrator
  "Migrator."
  [mstore pstore options tenant]
  (let [run (:run options false)
        min-ttl (:min-ttl options default-min-ttl)]
    (reify
      Processor
      (process-metrics [this rollup period retention path series]
        (doseq [point series]
          (let [time (first point)
                value (last point)
                ttl (calc-ttl time retention)]
            (when (> ttl min-ttl)
              (log-point "Migrating point" rollup period path time value ttl)
              (when run
                (mstore/insert mstore tenant rollup period path time value
                               ttl))))))
      (process-path [this path]
        (when run
          (pstore/insert pstore tenant path))))))

(defn- validate-value
  "Validate a value."
  [rollup period path time w-value s-value validate-fn msg error-reported?
   error-paths]
  (let [valid? (validate-fn w-value s-value)
        err (format
             (str "Metric store validation. "
                  "%s: rollup: %s, period: %s, path: %s, time: %s, "
                  "whisper value: %s, store value %s")
             msg rollup period path time w-value s-value)]
    (when-not valid?
      (if-not @error-reported?
        (do
          (swap! mstore-error-paths conj path)
          (wlog/error err)
          (swap! error-reported? (fn [_] true)))
        (log/debug err)))
    valid?))

(defn- validator
  "Validator."
  [mstore pstore options tenant]
  (let [min-ttl (:min-ttl options default-min-ttl)]
    (reify
      Processor
      (process-metrics [this rollup period retention path series]
        (let [series (whisper/sort-series series)
              from (first (first series))
              to (first (last series))
              mstore-series (mstore/fetch-series mstore tenant rollup period path
                                                 from to)
              error-reported? (atom false)
              validators [[#(not= %2 nil) "Point not found"]
                          [#(sequential? %2) "Value is not an array"]
                          [#(= (count %2) 1) "Array size is not equal to 1"]
                          [#(= %1 (first %2)) "Values are not equal"]]]
          (doseq [point series]
            (let [time (first point)
                  w-value (last point)
                  ttl (calc-ttl time retention)]
              (when (> ttl min-ttl)
                (log-point "Validating point" rollup period path time w-value ttl)
                (let [s-value (get mstore-series time)]
                  (every? #(validate-value rollup period path time w-value
                                           s-value (first %) (second %)
                                           error-reported? mstore-error-paths)
                          validators)))))))
      (process-path [this path]
        (when-not (pstore/exist? pstore tenant path)
          (swap! pstore-error-paths conj path)
          (wlog/error "Path not found in the path store: " path))))))

(defn- process-archive
  "Process an archive."
  [ra-file file archive mstore tenant path from to retention options processor]
  (let [rollup (:seconds-per-point archive)
        points (:points archive)
        period (if retention (/ retention rollup) points)
        retention (if retention retention (* rollup points))
        data (whisper/fetch-archive-seq-ra ra-file file archive from to)
        series (whisper/remove-nulls (:series data))]
    (process-metrics processor rollup period retention path series)))

(defn- process-file
  "Process a file."
  [file dir mstore pstore tenant from to options processor]
  (let [path (whisper/file-to-name file dir)]
    (when pstore
      (swap! pstore-processed inc)
      (process-path processor path))
    (when mstore
      (let [ra-file (RandomAccessFile. file "r")
            rollups (:rollups options)]
        (try
          (swap! mstore-processed inc)
          (let [info (whisper/read-info-ra ra-file file)
                archives (:archives info)]
            (doseq [archive archives]
              (let [seconds-per-point (:seconds-per-point archive)]
                (when (or (not (> (count rollups) 0))
                          (contains? rollups seconds-per-point))
                  (let [retention (get rollups seconds-per-point
                                       (:retention archive))]
                    (process-archive ra-file file archive mstore tenant path
                                     from to retention options processor))))))
          (finally
            (.close ra-file)))))))

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
  "Create a metric store."
  [cass-host options]
  (if-not (:disable-metric-store options false)
    (try
      (mstore/cassandra-metric-store cass-host options)
      (catch Exception e
        (wlog/fatal "Error creating metric store: " e)))
    nil))

(defn- create-pstore
  "Create a path store."
  [es-url options]
  (if-not (:disable-path-store options false)
    (try
      (pstore/elasticsearch-metric-store es-url options)
      (catch Exception e
        (wlog/fatal "Error creating path store: " e)))
    nil))

(defn- get-from-to
  "Get and check FROM and TO time values."
  [options]
  (let [from (:from options)
        to (:to options)]
    (when (and from to (> from to))
      (throw (Exception. (str "Invalid TO value: " to))))
    {:from from :to to}))

;; (defn- merge-stats
;;   "Merge stats structures."
;;   [total store]
;;   (let [store (if store store {:processed 0 :error-paths (sorted-set)})
;;         merged (merge total store)]
;;     (assoc merged :error-paths (clojure.set/union (:error-paths total)
;;                                                   (:error-paths store)))))

(defn- show-store-stats
  "Show store stats."
  [title data-stats store-stats]
  (let [processed (:processed data-stats)
        data-errors (count (:error-paths data-stats))
        store-errors (count (:error-paths store-stats))]
    ;; (log/info (format "%s: %s, errors: %s%s" title processed errors
    ;;                   (if (> (+ data-errors store-errors) 0)
    ;;                     (str ", erroneous paths:\n"
    ;;                          (str/join "\n" error-paths))
    ;;                     "")))
    (log/info (format "%s: processed %s, store errors: %s, data errors: %s"
                      title processed store-errors data-errors))
    (newline)
    (println (str title ":"))
    (println "  Processed:   " processed)
    (println "  Store errors:" (count (:error-paths store-stats)))
    (println "  Data errors: " data-errors)))

(defn- show-stats
  "Show stats."
  [mstore pstore]
  (when mstore
    (show-store-stats "Metric store stats" {:processed @mstore-processed
                                            :error-paths @mstore-error-paths}
                      (mstore/get-stats mstore)))
  (when pstore
    (show-store-stats "Path store stats" {:processed @pstore-processed
                                          :error-paths @pstore-error-paths}
                      (pstore/get-stats pstore))))

(defn- shutdown
  "Shutdown."
  [mstore pstore]
  (when mstore
    (mstore/shutdown mstore))
  (when pstore
    (pstore/shutdown pstore)))

(defn- process
  "Process a Whisper database."
  [source tenant cass-host es-url options processor-fn start-title title]
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
          processor (processor-fn mstore pstore options tenant)
          process-file-fn (fn [file]
                            (wlog/info "Processing path: " file)
                            (when-not @wlog/print-log?
                              (prog/tick))
                            (process-file file root-dir mstore pstore tenant
                                          from to options processor))]
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
          (show-stats mstore pstore)
          (shutdown mstore pstore))))
    (catch Exception e
      (wlog/unhandled-error e)))
  (wlog/exit 0))

(defn migrate
  "Do the migration."
  [source tenant cass-host es-url options]
  (process source tenant cass-host es-url options migrator "Starting migration"
           "Migrating"))

(defn validate
  "Do the validation."
  [source tenant cass-host es-url options]
  (process source tenant cass-host es-url options validator "Starting validation"
           "Validating"))

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
  "Get an archive by a rollup."
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
