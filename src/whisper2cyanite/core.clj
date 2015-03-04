(ns whisper2cyanite.core
  (:import (java.io RandomAccessFile))
  (:require [clj-whisper.core :as whisper]
            [clojure.string :as str]
            [clojure.set :as set]
            [com.climate.claypoole :as cp]
            [clojure.java.io :as io]
            [clj-progress.core :as prog]
            [clojure.contrib.humanize :as humanize]
            [clojure.tools.logging :as log]
            [whisper2cyanite.utils :as utils]
            [whisper2cyanite.metric-store :as mstore]
            [whisper2cyanite.path-store :as pstore]
            [whisper2cyanite.logging :as wlog]))

(def ^:const default-jobs 1)
(def ^:const default-min-ttl 3600)

(def ^:const pbar-width 35)

(def mstore-processed (atom 0))
(def pstore-processed (atom 0))

(def mstore-error-files (atom (sorted-set)))
(def pstore-error-files (atom (sorted-set)))

(def cassandra-data-size (atom 0))

(defprotocol Processor
  (process-metrics [this rollup period retention points path file series])
  (process-path [this path file])
  (fetch-data? [this]))

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
      (process-metrics [this rollup period retention points path file series]
        (doseq [point series]
          (let [time (first point)
                value (last point)
                ttl (calc-ttl time retention)]
            (when (> ttl min-ttl)
              (log-point "Migrating point" rollup period path time value ttl)
              (when run
                (mstore/insert mstore tenant rollup period path time value
                               ttl file))))))
      (process-path [this path file]
        (when run
          (pstore/insert pstore tenant path file)))
      (fetch-data? [this]
        true))))

(defn- validate-value
  "Validate a value."
  [rollup period path time w-value s-value validate-fn msg error-reported?
   error-files file]
  (let [valid? (validate-fn w-value s-value)
        err (format
             (str "Metric store validation. "
                  "%s: rollup: %s, period: %s, path: %s, time: %s, "
                  "whisper value: %s, store value %s")
             msg rollup period path time w-value s-value)]
    (when-not valid?
      (if-not @error-reported?
        (do
          (swap! mstore-error-files conj file)
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
      (process-metrics [this rollup period retention points path file series]
        (let [series (whisper/sort-series series)
              from (first (first series))
              to (first (last series))
              mstore-series (mstore/fetch-series mstore tenant rollup period path
                                                 from to file)
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
                                           error-reported? mstore-error-files
                                           file)
                          validators)))))))
      (process-path [this path file]
        (when-not (pstore/exist? pstore tenant path file)
          (swap! pstore-error-files conj file)
          (wlog/error "Path not found in the path store: " path)))
      (fetch-data? [this]
        true))))

(defn- calculator
  "Cassandra data size calculator."
  [mstore pstore options tenant]
  (let [period-size 4
        rollup-size 4
        tenant-size (count tenant)
        time-size 8
        data-size 8
        row-overhead 23
        key-overhead 32
        row-size (+ data-size row-overhead)
        part-key-size-const (+ period-size rollup-size tenant-size)
        clust-col-size (+ time-size key-overhead)]
    (reify
      Processor
      (process-metrics [this rollup period retention points path file series]
        (let [part-key-size (+ part-key-size-const (count path))
              index-size (+ part-key-size (* clust-col-size points))
              rows-size (* row-size points)
              archive-size (+ index-size rows-size)]
          (swap! cassandra-data-size + archive-size)))
      (process-path [this path file])
      (fetch-data? [this]
        false))))

(defn- process-archive
  "Process an archive."
  [ra-file file archive mstore tenant path from to retention options processor]
  (let [rollup (:seconds-per-point archive)
        points (:points archive)
        period (if retention (/ retention rollup) points)
        retention (if retention retention (* rollup points))
        data (if (fetch-data? processor)
               (whisper/fetch-archive-seq-ra ra-file file archive from to) nil)
        series (if data (whisper/remove-nulls (:series data)) nil)]
    (process-metrics processor rollup period retention points path file series)))

(defn- process-file
  "Process a file."
  [file dir mstore pstore tenant from to options processor]
  (let [path (whisper/file-to-name file dir)]
    (when pstore
      (swap! pstore-processed inc)
      (process-path processor path file))
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

(defn- get-paths-from-file
  "Get paths form a file."
  [file]
  (if (utils/is-whisper? file)
    [file]
    (do
      (wlog/info (str "Loading paths from file: " file))
      (remove str/blank? (map str/trim (str/split-lines (slurp file)))))))

(defn- get-paths
  "Get paths."
  [source]
  (let [is-dir? (utils/is-directory? source)
        paths (if is-dir?
                (whisper/get-paths source)
                (get-paths-from-file source))]
    (when is-dir?
      (newline)
      (wlog/info "Getting paths...")
      (when-not @wlog/print-log?
        (println "Getting paths")
        (prog/set-progress-bar! "[:bar] :done")
        (prog/config-progress-bar! :width pbar-width)
        (prog/init 0)
        (doseq [path paths]
          (prog/tick))
        (prog/done)))
    (wlog/info (format "Found %s paths" (count paths)))
    (sort paths)))

(defn- get-root-dir
  "Get root directory."
  [source options]
  (let [dir (utils/extract-directory (utils/get-cpath source))
        root-dir (:root-dir options nil)]
    (if-not root-dir
      dir
      (let [root-dir-c (utils/get-cpath root-dir)]
        (when (or (> (count root-dir-c) (count dir))
                  (not= (subs dir 0 (count root-dir-c)) root-dir-c))
          (throw (Exception. (str "Invalid root directory: " root-dir))))
        root-dir-c))))

(defn- get-root-dir-and-files
  [source options]
  (if (or (utils/is-directory? source) (utils/is-whisper? source))
    [(get-root-dir source options) (get-paths source)]
    (let [files (get-paths source)]
      [(get-root-dir (first files) options) files])))

(defn- create-mstore
  "Create a metric store."
  [cass-host options]
  (if-not (= cass-host :true)
    (if-not (:disable-metric-store options false)
      (try
        (mstore/cassandra-metric-store cass-host options)
        (catch Exception e
          (wlog/fatal "Error creating metric store: " e)))
      nil)
    :true))

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

(defn- show-store-stats
  "Show store stats."
  [title data-stats store-stats]
  (let [processed (:processed data-stats)
        data-errors (count (:error-files data-stats))
        store-errors (count (:error-files store-stats))]
    (log/info (format "%s: processed %s, store errors: %s, data errors: %s"
                      title processed store-errors data-errors))
    (newline)
    (println (str title ":"))
    (println "  Processed:   " processed)
    (println "  Store errors:" store-errors)
    (println "  Data errors: " data-errors)))

(defn- log-error-files
  "Log erroneous files."
  [title files]
  (when (> (count files) 0)
    (log/info (str title ":\n" (str/join "\n" files)))))

(defn- dump-error-files
  "Dump erroneous files."
  [file error-files]
  (when (> (count error-files) 0)
    (wlog/info (str "Dumping a list of files during processing which errors "
                    "occurred to the file: ") file)
    (spit file (str/join "\n" error-files))))

(defn- show-error-files
  "Show erroneous files."
  [data-error-files store-stats title error-files]
  (when store-stats
    (let [files (set/union @data-error-files (:error-files store-stats))]
      (log-error-files title files)
      (swap! error-files set/union @error-files files))))

(defn- show-stats
  "Show stats."
  [mstore pstore options]
  (let [mstore-stats (if (and mstore (not= mstore :true))
                       (mstore/get-stats mstore) nil)
        pstore-stats (if pstore (pstore/get-stats pstore) nil)
        errors-file (:errors-file options)
        error-files (atom #{})]
    (when mstore-stats
      (show-store-stats "Metric store stats" {:processed @mstore-processed
                                              :error-files @mstore-error-files}
                        mstore-stats))
    (when pstore-stats
      (show-store-stats "Path store stats" {:processed @pstore-processed
                                            :error-files @pstore-error-files}
                        pstore-stats))
    (show-error-files mstore-error-files mstore-stats
                      (str "Files during processing which errors occurred "
                           "in the metric store") error-files)
    (show-error-files pstore-error-files pstore-stats
                      (str "Files during processing which errors occurred "
                           "in the path store") error-files)
    (when errors-file
      (dump-error-files errors-file @error-files))))

(defn- shutdown
  "Shutdown."
  [mstore pstore]
  (when (and mstore (not= mstore :true))
    (mstore/shutdown mstore))
  (when pstore
    (pstore/shutdown pstore)))

(defn- process
  "Process a Whisper database."
  [source tenant cass-host es-url options processor-fn start-title title]
  (wlog/set-logging! options)
  (try
    (wlog/info start-title)
    (let [[root-dir files] (get-root-dir-and-files source options)
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
          (shutdown mstore pstore)
          (show-stats mstore pstore options))))
    (catch Exception e
      (wlog/unhandled-error e))))

(defn migrate
  "Do the migration."
  [source tenant cass-host es-url options]
  (when-not (:run options false)
    (newline)
    (let [warn-str (str "DRY MODE IS ON! "
                        "To run in the normal mode use the '--run' option.")]
      (println warn-str)
      (log/warn warn-str)))
  (process source tenant cass-host es-url options migrator "Starting migration"
           "Migrating")
  (wlog/exit 0))

(defn validate
  "Do the validation."
  [source tenant cass-host es-url options]
  (process source tenant cass-host es-url options validator "Starting validation"
           "Validating")
  (wlog/exit 0))

(defn calc-size
  "Do the Cassandra data size calculation."
  [source tenant options]
  (let [options (-> options
                    (assoc :disable-path-store true)
                    (assoc :disable-log true)
                    (assoc :stop-on-error true))]
    (process source tenant :true nil options calculator
             "Starting Cassandra data size calculation"
             "Calculating"))
  (newline)
  (println "Estimated Cassandra data size:"
           (humanize/filesize @cassandra-data-size))
  (println "Don't forget to multiply size by replication factor!")
  (wlog/exit 0))

(defn list-files
  "List files."
  [dir]
  (doseq [path (sort (whisper/get-paths dir))]
    (println (utils/get-cpath path))))

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
