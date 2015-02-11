(ns whisper2cyanite.metric-store
  (:require [qbits.alia :as alia]
            [qbits.alia.policy.load-balancing :as alia_lbp]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [whisper2cyanite.utils :as utils]
            [whisper2cyanite.logging :as wlog])
  (:import [com.datastax.driver.core
            BatchStatement
            PreparedStatement]))

(defprotocol MetricStore
  (insert [this tenant rollup period path time value ttl])
  (shutdown [this]))

(def ^:const default-cassandra-keyspace "metric")
(def ^:const default-cassandra-channel-size 10000)
(def ^:const default-cassandra-batch-size 500)
(def ^:const default-cassandra-options {})

(def cassandra-cql
  (str
   "UPDATE metric USING TTL ? SET data = ? "
   "WHERE tenant = ? AND rollup = ? AND period = ? AND path = ? AND time = ?;"))

(defn- get-cassandra-query
  "Get a Cassandra prepared statement."
  [session]
  (alia/prepare session cassandra-cql))

(defn- batch
  "Creates a batch of prepared statements"
  [^PreparedStatement s values]
  (let [b (BatchStatement.)]
    (doseq [v values]
      (.add b (.bind s (into-array Object v))))
    b))

(defn- get-channel
  "Get store channel."
  [session statement chan_size batch_size data-stored?]
  (let [ch (async/chan chan_size)
        ch-p (utils/partition-or-time batch_size ch batch_size 5)]
    (utils/go-while (not @data-stored?)
                    (let [values (async/<! ch-p)]
                      (if values
                        (try
                          (async/take!
                           (alia/execute-chan session (batch statement values)
                                              {:consistency :any})
                           (fn [rows-or-e]
                             (if (instance? Throwable rows-or-e)
                               (wlog/error "Metric store error: " rows-or-e))))
                          (catch Exception e
                            (wlog/error "Metric store error: " e)))
                        (when (not @data-stored?)
                          (swap! data-stored? (fn [_] true))))))
    ch))

(defn cassandra-metric-store
  "Cassandra metric store."
  [hosts options]
  (log/info "Creating the metric store...")
  (let [keyspace (:cassandra-keyspace options default-cassandra-keyspace)
        c-options (merge {:contact-points hosts}
                         default-cassandra-options
                         (:cassandra-options options {}))
        _ (log/info "Cassandra options: " c-options)
        session (-> (alia/cluster c-options)
                    (alia/connect keyspace))
        insert! (get-cassandra-query session)
        chan_size (:cassandra-channel-size options default-cassandra-channel-size)
        batch_size (:cassandra-batch-size options default-cassandra-batch-size)
        data-stored? (atom false)
        channel (get-channel session insert! chan_size batch_size data-stored?)]
    (log/info (str "The metric store has been created. "
                   "Keyspace: " keyspace ", "
                   "channel size: " chan_size ", "
                   "batch size: " batch_size))
    (reify
      MetricStore
      (insert [this tenant rollup period path time value ttl]
        (try
          (async/>!! channel [(int ttl) [(double value)] (str tenant)
                              (int rollup) (int period) (str path)
                              (long time)])
          (catch Exception e
            (wlog/error "Metric store error: " e))))
      (shutdown [this]
        (log/info "Shutting down the metric store...")
        (async/close! channel)
        (while (not @data-stored?)
          (Thread/sleep 100))
        (.close session)
        (log/info "The metric store has been down")))))
