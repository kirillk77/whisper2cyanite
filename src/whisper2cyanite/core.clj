(ns whisper2cyanite.core
  (:import (java.io RandomAccessFile)
           (org.joda.time.format PeriodFormatterBuilder))
  (:require [clj-whisper.core :as whisper]
            [clojure.string :as str]
            [clojure.set :as set]
            [com.climate.claypoole :as cp]
            [clj-progress.core :as prog]
            [clojure.contrib.humanize :as humanize]
            [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [clj-time.core :as time]
            [whisper2cyanite.logging :as wlog]
            [whisper2cyanite.utils :as utils]
            [whisper2cyanite.metric-store :as mstore]
            [whisper2cyanite.path-store :as pstore]))

(def ^:const default-jobs 1)
(def ^:const default-min-ttl 3600)

(def ^:const pbar-width 35)

(def mstore-processed (atom 0))
(def pstore-processed (atom 0))

(def mstore-error-files (atom (sorted-set)))
(def pstore-error-files (atom (sorted-set)))

(def cassandra-data-size (atom 0))
(def points-count (atom 0))

(def starting-str "==================== Starting ====================")

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
  (let [min-ttl (:min-ttl options default-min-ttl)]
    (reify
      Processor
      (process-metrics [this rollup period retention points path file series]
        (let [points-processed (atom 0)
              series-ttl (->> series
                              (map #(let [time (first %)
                                          value (last %)
                                          ttl (calc-ttl time retention)]
                                      (if (> ttl min-ttl)
                                        (do (log-point "Migrating point" rollup
                                                       period path time value ttl)
                                            [time value ttl])
                                        nil)))
                              (remove nil?))]
          (swap! points-processed + (count series-ttl))
          (mstore/insert mstore tenant rollup period path series-ttl file)
          (log/debug (format (str "Metric migrated: path %s, rollup %s, "
                                  "period: %s, points migrated: %s") file
                                  rollup period @points-processed))
          @points-processed))
      (process-path [this path file]
        (pstore/insert pstore tenant path file))
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
                  "whisper value: %s, store value: %s")
             msg rollup period path time w-value (str s-value))]
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
  (let [min-ttl (:min-ttl options default-min-ttl)
        validators [[#(not= %2 nil) "Point not found"]
                    [#(= (count %2) 1) "Array size is not equal to 1"]
                    [#(= %1 (first %2)) "Values are not equal"]]
        points-processed (atom 0)]
    (reify
      Processor
      (process-metrics [this rollup period retention points path file series]
        (let [series (whisper/sort-series series)
              times (map first series)
              from (apply min times)
              to (apply max times)
              error-reported? (atom false)
              mstore-series (mstore/fetch-series mstore tenant rollup period path
                                                 from to file)]
          (when (not= mstore-series :mstore-error)
            (doseq [point series]
              (let [time (first point)
                    w-value (last point)
                    ttl (calc-ttl time retention)]
                (when (> ttl min-ttl)
                  (log-point "Validating point" rollup period path time
                             w-value ttl)
                  (let [s-value (get mstore-series time)]
                    (every? #(validate-value rollup period path time w-value
                                             s-value (first %) (second %)
                                             error-reported? mstore-error-files
                                             file)
                            validators))))))))
      (process-path [this path file]
        (let [ret (pstore/exist? pstore tenant path file)]
          (when (and (not= ret :pstore-error) (not ret))
            (swap! pstore-error-files conj file)
            (wlog/error (str "Path not found in the path store: " path)))))
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
          (swap! cassandra-data-size + archive-size)
          (swap! points-count + points))
        points)
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
    (when (not-empty series)
      (process-metrics processor rollup period retention points path
                       file series))))

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
  (if (utils/whisper? file)
    [file]
    (do
      (wlog/info (str "Loading paths from file: " file))
      (remove str/blank? (map str/trim (str/split-lines (slurp file)))))))

(defn- get-paths
  "Get paths."
  [source]
  (let [is-dir? (fs/directory? source)
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
        (doseq [_ paths]
          (prog/tick))
        (prog/done)))
    (wlog/info (format "Found %s paths" (count paths)))
    (sort paths)))

(defn- get-root-dir
  "Get root directory."
  [source options]
  (let [dir (utils/extract-directory (fs/normalized source))
        root-dir (:root-dir options nil)]
    (if-not root-dir
      dir
      (let [root-dir-c (fs/normalized root-dir)]
        (when (or (> (count root-dir-c) (count dir))
                  (not= (subs dir 0 (count root-dir-c)) root-dir-c))
          (throw (Exception. (str "Invalid root directory: " root-dir))))
        root-dir-c))))

(defn- get-root-dir-and-files
  [source options]
  (if (or (fs/directory? source) (utils/whisper? source))
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
          (wlog/fatal (str "Error creating metric store: " e) e)))
      nil)
    :true))

(defn- create-pstore
  "Create a path store."
  [es-url options]
  (if-not (:disable-path-store options false)
    (try
      (pstore/elasticsearch-metric-store es-url options)
      (catch Exception e
        (wlog/fatal (str "Error creating path store: " e) e)))
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
                    "occurred to the file: " file))
    (spit file (str/join "\n" error-files))))

(defn- show-error-files
  "Show erroneous files."
  [data-error-files store-stats title error-files]
  (when store-stats
    (let [files (set/union @data-error-files (:error-files store-stats))]
      (log-error-files title files)
      (swap! error-files set/union @error-files files))))

(defn- show-duration
  "Show duration."
  [interval]
  ;; https://stackoverflow.com/questions/3471397/pretty-print-duration-in-java
  (let [formatter (-> (PeriodFormatterBuilder.)
                      (.appendDays) (.appendSuffix "d ")
                      (.appendHours) (.appendSuffix "h ")
                      (.appendMinutes) (.appendSuffix "m ")
                      (.appendSeconds) (.appendSuffix "s")
                      (.toFormatter))
        duration-pp (.print formatter (.toPeriod interval))
        duration-sec (.getSeconds (.toStandardSeconds (.toDuration interval)))
        duration-str (format "Duration: %ss%s" duration-sec
                             (if (> duration-sec 59)
                               (format " (%s)" duration-pp) ""))]
    (log/info duration-str)
    (newline)
    (println duration-str)))

(defn- show-stats
  "Show stats."
  [mstore pstore interval options]
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
      (dump-error-files errors-file @error-files)))
  (show-duration interval))

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
    (log/info starting-str)
    (wlog/info start-title)
    (let [start-time (time/now)
          [root-dir files] (get-root-dir-and-files source options)
          files-count (count files)
          {:keys [from to]} (get-from-to options)
          jobs (:jobs options default-jobs)
          pool (cp/threadpool jobs)
          mstore (create-mstore cass-host options)
          pstore (create-pstore es-url options)
          processor (processor-fn mstore pstore options tenant)
          process-file-fn (fn [file]
                            (wlog/info (str "Processing path: " file))
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
          (show-stats mstore pstore (time/interval start-time (time/now))
                      options))))
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
  (println "Number of points:" @points-count)
  (println "Don't forget to multiply size by replication factor!")
  (wlog/exit 0))

(defn list-files
  "List files."
  [dir]
  (doseq [path (sort (whisper/get-paths dir))]
    (println (fs/normalized path))))

(defn list-paths
  "List paths."
  [source options]
  (let [[root-dir files] (get-root-dir-and-files source options)]
    (doseq [file (sort files)]
      (println (whisper/file-to-name file root-dir)))))

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
