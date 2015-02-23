(ns whisper2cyanite.metric-store
  (:require [qbits.alia :as alia]
            [qbits.alia.policy.load-balancing :as alia_lbp]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [whisper2cyanite.utils :as utils]
            [whisper2cyanite.logging :as wlog])
  (:import [com.datastax.driver.core
            PreparedStatement]))

(defprotocol MetricStore
  (insert [this tenant rollup period path time value ttl])
  (fetch-series [this tenant rollup period path from to])
  (shutdown [this]))

(def ^:const default-cassandra-keyspace "metric")
(def ^:const default-cassandra-channel-size 10000)
(def ^:const default-cassandra-options {})

(def insert-cql
  (str
   "UPDATE metric USING TTL ? SET data = ? "
   "WHERE tenant = ? AND rollup = ? AND period = ? AND path = ? AND time = ?;"))

(def fetch-cql
  (str "SELECT data, time FROM metric WHERE "
       "tenant = '%s' AND rollup = %s  AND  period = %s AND "
       "path = '%s' AND time >= %s AND time <= %s;"))

(defn- get-cassandra-insert
  "Get a Cassandra prepared statement."
  [session]
  (alia/prepare session insert-cql))

(defn- log-error
  "Log a error."
  [e tenant rollup period path time]
  (wlog/error (str "Metric store error: " e ", "
                   "tenant: " tenant ", "
                   "rollup " rollup ", "
                   "period: " period ", "
                   "path: " path ", "
                   "time: " time)))

(defn- get-channel
  "Get store channel."
  [session statement chan-size data-stored?]
  (let [ch (async/chan chan-size)]
    (utils/go-while (not @data-stored?)
                    (let [values (async/<! ch)]
                      (if values
                        (let [[_ _ tenant rollup period path time] values]
                          (try
                            (async/take!
                             (alia/execute-chan session statement
                                                {:values values
                                                 :consistency :any})
                             (fn [rows-or-e]
                               (if (instance? Throwable rows-or-e)
                                 (log-error rows-or-e tenant rollup period path
                                            time))))
                            (catch Exception e
                              (log-error e tenant rollup period path time))))
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
        insert! (get-cassandra-insert session)
        chan-size (:cassandra-channel-size options default-cassandra-channel-size)
        data-stored? (atom false)
        channel (get-channel session insert! chan-size data-stored?)]
    (log/info (str "The metric store has been created. "
                   "Keyspace: " keyspace ", "
                   "channel size: " chan-size ", "))
    (reify
      MetricStore
      (insert [this tenant rollup period path time value ttl]
        (try
          (async/>!! channel [(int ttl) [(double value)] (str tenant)
                              (int rollup) (int period) (str path)
                              (long time)])
          (catch Exception e
            (log-error e tenant rollup period path time))))
      (fetch-series [this tenant rollup period path from to]
        (try
          (let [series (atom {})]
            (let [rows (.execute session (format fetch-cql tenant rollup period
                                                 path from to))
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
            (wlog/error "Metric store error: " e))))
      (shutdown [this]
        (log/info "Shutting down the metric store...")
        (async/close! channel)
        (while (not @data-stored?)
          (Thread/sleep 100))
        (.close session)
        (log/info "The metric store has been down")))))
