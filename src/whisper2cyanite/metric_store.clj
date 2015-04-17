(ns whisper2cyanite.metric-store
  (:require [qbits.alia :as alia]
            [qbits.alia.policy.load-balancing :as alia_lbp]
            [clojure.core.async :as async]
            [throttler.core :as trtl]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [whisper2cyanite.utils :as utils]
            [whisper2cyanite.logging :as wlog])
  (:import [com.datastax.driver.core
            PreparedStatement
            BatchStatement]))

(defprotocol MetricStore
  (insert [this tenant rollup period path timeseries file])
  (fetch-series [this tenant rollup period path from to file])
  (get-stats [this])
  (shutdown [this]))

(def ^:const default-cassandra-keyspace "metric")
(def ^:const default-cassandra-channel-size 500000)
(def ^:const default-cassandra-batch-size 1000)
(def ^:const default-cassandra-batch-rate nil)
(def ^:const default-cassandra-options {})

(def insert-cql
  (str
   "UPDATE metric USING TTL ? SET data = ? "
   "WHERE tenant = ? AND rollup = ? AND period = ? AND path = ? AND time = ?;"))

(def fetch-cql
  (str "SELECT data, time FROM metric WHERE "
       "tenant = '%s' AND rollup = %s AND period = %s AND "
       "path = '%s' AND time >= %s AND time <= %s;"))

(defn- get-cassandra-insert
  "Get a Cassandra prepared statement."
  [session]
  (alia/prepare session insert-cql))

(defn- batch
  "Create a batch of prepared statements"
  [^PreparedStatement statement values]
  (let [batch-statement (BatchStatement.)]
    (doseq [value values]
      (.add batch-statement (.bind statement (into-array Object value))))
    batch-statement))

(defn- log-error
  "Log a error."
  [stats-error-files file error rollup period path]
  (swap! stats-error-files conj file)
  (wlog/error (str "Metric store error: " error ", "
                   "rollup " rollup ", "
                   "period: " period ", "
                   "path: " path)))

(defn- get-channel
  "Get store channel."
  [session statement chan-size batch-rate data-stored? stats-error-files run]
  (let [ch-in (async/chan chan-size)
        ch (if batch-rate (trtl/throttle-chan ch-in batch-rate :second) ch-in)]
    (utils/go-while (not @data-stored?)
                    (let [data (async/<! ch)]
                      (if data
                        (let [{:keys [values rollup period path file]} data]
                          (try
                            (let [query (batch statement values)]
                              (when run
                                (async/take!
                                 (alia/execute-chan session
                                                    {:consistency :any})
                                 (fn [rows-or-e]
                                   (if (instance? Throwable rows-or-e)
                                     (log-error stats-error-files file rows-or-e
                                                rollup period path))))))
                            (catch Exception e
                              (log-error stats-error-files file e rollup period
                                         path))))
                        (when (not @data-stored?)
                          (swap! data-stored? (fn [_] true))))))
    ch-in))

(defn cassandra-metric-store
  "Cassandra metric store."
  [hosts options]
  (log/info "Creating the metric store...")
  (let [run (:run options false)
        keyspace (:cassandra-keyspace options default-cassandra-keyspace)
        c-options (merge {:contact-points hosts}
                         default-cassandra-options
                         (:cassandra-options options {}))
        _ (log/info "Cassandra options: " c-options)
        session (-> (alia/cluster c-options)
                    (alia/connect keyspace))
        insert! (get-cassandra-insert session)
        chan-size (:cassandra-channel-size options default-cassandra-channel-size)
        batch-size (:cassandra-batch-size options default-cassandra-batch-size)
        chan-size-batches (utils/ceil (/ chan-size batch-size))
        batch-rate (:cassandra-batch-rate options default-cassandra-batch-rate)
        data-stored? (atom false)
        stats-error-files (atom (sorted-set))
        stats-processed (atom 0)
        channel (get-channel session insert! chan-size-batches batch-rate
                             data-stored? stats-error-files run)]
    (log/info (str "The metric store has been created. "
                   "Keyspace: " keyspace ", "
                   "channel size: " chan-size ", "
                   "batch size: " batch-size ", "
                   "batch rate: " batch-rate))
    (reify
      MetricStore
      (insert [this tenant rollup period path timeseries file]
        (try
          (let [series (map #(let [[time value ttl] %]
                               [(int ttl) [(double value)] tenant (int rollup)
                                (int period) path (long time)])
                            timeseries)
                batches (partition-all batch-size series)]
            (doseq [values batches]
              (swap! stats-processed + (count values))
              (async/>!! channel {:values values
                                  :rollup rollup
                                  :period period
                                  :path path
                                  :file file})))
          (catch Exception e
            (log-error stats-error-files file e rollup period path))))
      (fetch-series [this tenant rollup period path from to file]
        (try
          (swap! stats-processed inc)
          (let [series (atom {})
                cql (format fetch-cql tenant rollup period path from to)]
            (log/debug "Fetch series CQL:" cql)
            (let [rows (.execute session cql)
                  first-row (.one rows)]
              (when first-row
                (loop [row first-row]
                  (when row
                    (let [time (long (.getLong row "time"))
                          value (into [] (.getList row "data" java.lang.Double))]
                      (swap! series assoc time value)
                      (recur (.one rows)))))))
            @series)
          (catch Exception e
            (log-error stats-error-files file e rollup period path)
            :mstore-error)))
      (get-stats [this]
        {:processed @stats-processed
         :error-files @stats-error-files})
      (shutdown [this]
        (log/info "Shutting down the metric store...")
        (async/close! channel)
        (while (not @data-stored?)
          (Thread/sleep 100))
        (.close session)
        (log/info "The metric store has been down")))))
