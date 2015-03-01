(ns whisper2cyanite.cli
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [whisper2cyanite.logging :as wlog]
            [whisper2cyanite.metric-store :as mstore]
            [whisper2cyanite.path-store :as pstore]
            [whisper2cyanite.core :as core]
            [org.spootnik.logconfig :as logconfig])
  (:gen-class))

(def cli-commands #{"migrate" "validate" "list-files" "list-paths"
                    "info" "fetch" "help"})

(defn- check-rollups
  "Check rollups."
  [rollups]
  (not-any? nil? rollups))

(defn- parse-rollups
  "Parse rollups."
  [rollups]
  (->> (str/split rollups #",")
       (map #(re-matches #"^((\d+)(:(\d+))*)$" %))
       (map #(if % [(Integer/parseInt (nth % 2))
                    (Integer/parseInt (nth % 4))] %))))

(defn- usage
  "Construct usage message."
  [options-summary]
  (->> ["Whisper to Cyanite data migration tool"
        ""
        "Usage: "
        "  whisper2cyanite [options] migrate <directory | whisper file | filelist file> <tenant> <cassandra-host,...> <elasticsearch-url>"
        "  whisper2cyanite [options] validate <directory | file> <tenant> <cassandra-host,...> <elasticsearch-url>"
        "  whisper2cyanite list-files <directory>"
        "  whisper2cyanite list-paths <directory>"
        "  whisper2cyanite info <file>"
        "  whisper2cyanite [options] fetch <file> <rollup>"
        "  whisper2cyanite help"
        ""
        "Options:"
        options-summary]
       (str/join \newline)))

(defn- error-msg
  "Combine error messages."
  [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn- exit
  "Print message and exit with status."
  [status msg]
  (println msg)
  (System/exit status))

(defn- check-arguments
  [command arguments min max]
  (let [n-args (count arguments)]
    (when (or (< n-args min) (> n-args max))
      (exit 1 (error-msg
               [(format "Invalid number of arguments for the command \"%s\""
                        command)])))))

(defn- check-options
  "Check options."
  [command valid-options options]
  (doseq [option (keys options)]
    (when (not (contains? valid-options option))
      (exit 1 (error-msg
               [(format "Option \"--%s\" conflicts with the command \"%s\""
                        (name option) command)])))))

(defn- prepare-common-args
  "Prepare common arguments."
  [arguments options]
  (let [source (nth arguments 0)
        tenant (nth arguments 1)
        cass-hosts (str/split (nth arguments 2) #",")
        es-url (nth arguments 3)
        rollups (->> (:rollups options [])
                     (filter #(not (nil? %)))
                     (flatten)
                     (apply hash-map))
        options (assoc options :rollups rollups)]
    {:source source :tenant tenant :cass-hosts cass-hosts :es-url es-url
     :options options}))

(defn- run-migrate
  "Run command 'migrate'."
  [command arguments options summary]
  (check-arguments "migrate" arguments 4 4)
  (check-options command #{:from :to :run :rollups :jobs :min-ttl :root-dir
                           :cassandra-keyspace :cassandra-options
                           :cassandra-channel-size :disable-metric-store
                           :elasticsearch-index :elasticsearch-channel-size
                           :disable-path-store :log-file :log-level
                           :disable-log :errors-file :stop-on-error
                           :disable-progress}
                 options)
  (let [{:keys [source tenant cass-hosts es-url
                options]} (prepare-common-args arguments options)]
    (core/migrate source tenant cass-hosts es-url options)))

(defn- run-validate
  "Run command 'validate'."
  [command arguments options summary]
  (check-arguments "validate" arguments 4 4)
  (check-options command #{:from :to :rollups :jobs :min-ttl :root-dir
                           :cassandra-keyspace :cassandra-options
                           :disable-metric-store :elasticsearch-index
                           :disable-path-store :log-file :log-level
                           :disable-log :errors-file :stop-on-error
                           :disable-progress}
                 options)
  (let [{:keys [source tenant cass-hosts es-url
                options]} (prepare-common-args arguments options)]
    (core/validate source tenant cass-hosts es-url options)))

(defn- run-list-files
  "Run command 'list-files'."
  [command arguments options summary]
  (check-arguments command arguments 1 1)
  (check-options command #{} options)
  (core/list-files (first arguments)))

(defn- run-list-paths
  "Run command 'list-paths'."
  [command arguments options summary]
  (check-arguments command arguments 1 1)
  (check-options command #{} options)
  (core/list-paths (first arguments)))

(defn- run-info
  "Run command 'info'."
  [command arguments options summary]
  (check-arguments command arguments 1 1)
  (check-options command #{} options)
  (core/show-info (first arguments)))

(defn- run-fetch
  "Run command 'fetch'."
  [command arguments options summary]
  (check-arguments command arguments 2 2)
  (check-options command #{:from :to} options)
  (let [rollup (Integer/parseInt (second arguments))]
    (core/fetch (first arguments) rollup options)))

(defn- run-help
  "Run command 'help'."
  [command arguments options summary]
  (exit 0 (usage summary)))

(def cli-options
  [["-f" "--from FROM" "From time (Unix epoch)"
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 0 %)]]
   ["-t" "--to TO" "To time (Unix epoch)"
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 %)]]
   ["-r" "--run" "Force normal run (dry run using on default)"]
   ["-R" "--rollups ROLLUPS"
    "Define rollups. Format: <seconds_per_point[:retention],...> Example: 60,300:31536000"
    :parse-fn #(parse-rollups %)
    :validate [check-rollups]]
   ["-j" "--jobs JOBS" "Number of jobs to run simultaneously"
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 %)]]
   ["-T" "--min-ttl TTL" (str "Minimal TTL. Default: " core/default-min-ttl)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 %)]]
   ["-D" "--root-dir DIRECTORY" "Root directory"]
   [nil "--cassandra-keyspace KEYSPACE"
    (str "Cassandra keyspace. Default: " mstore/default-cassandra-keyspace)]
   ["-O" "--cassandra-options OPTIONS"
    "Cassandra options. Example: \"{:compression :lz4}\""
    :parse-fn #(read-string %)
    :validate [#(= clojure.lang.PersistentArrayMap (type %))]]
   [nil "--cassandra-channel-size SIZE"
    (str "Cassandra channel size. Default: "
         mstore/default-cassandra-channel-size)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 %)]]
   [nil "--disable-metric-store" "Disable writing to metric store"]
   [nil "--elasticsearch-index INDEX"
    (str "Elasticsearch index. Default: " pstore/default-es-index)]
   [nil "--elasticsearch-channel-size SIZE"
    (str "Elasticsearch channel size. Default: "
         pstore/default-es-channel-size)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 %)]]
   [nil "--disable-path-store" "Disable writing to path store"]
   ["-l" "--log-file FILE" (str "Log file. Default: " wlog/default-log-file)]
   ["-L" "--log-level LEVEL"
    (str "Log level (all, trace, debug, info, warn, error, fatal, off). "
         "Default: " wlog/default-log-level)
    :validate [#(or (= (count %) 0)
                    (not= (get logconfig/levels % :not-found) :not-found))]]
   ["-e" "--errors-file FILE"
    (str "Dump a list of files during processing which the errors occurred")]
   ["-S" "--stop-on-error" "Stop on first non-fatal error"]
   ["-P" "--disable-progress" "Disable progress bar"]])

(defn- run-command
  "Run command."
  [arguments options summary]
  (let [command (first arguments)]
    (when (not (contains? cli-commands command))
      (exit 1 (error-msg [(format "Unknown command: \"%s\"" command)])))
    (apply (resolve (symbol (str "whisper2cyanite.cli/run-" command)))
           [command (drop 1 arguments) options summary])))

(defn -main
  "Main function."
  [& args]
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args cli-options)]
    ;; Handle help and error conditions
    (cond
     (< (count args) 1) (exit 0 (usage summary))
     errors (exit 1 (error-msg errors)))
    ;; Run command
    (run-command arguments options summary)
    (System/exit 0)))
