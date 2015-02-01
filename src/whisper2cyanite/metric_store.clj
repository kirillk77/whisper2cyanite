(ns whisper2cyanite.metric-store
  (:require [qbits.alia :as alia]
            [qbits.alia.policy.load-balancing :as alia_lbp]
            [clojure.core.async :refer [chan <! take! >!!]]
            [whisper2cyanite.utils :as utils])
  (:import [com.datastax.driver.core
            BatchStatement
            PreparedStatement]))

(defprotocol MetricStore
  (insert [this tenant rollup period path time value ttl])
  (shutdown [this]))

(def ^:const default-cassandra-keyspace "metric")
(def ^:const default-cassandra-channel-size 10000)
(def ^:const default-cassandra-batch-size 500)

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
  [session statement chan_size batch_size]
  (let [ch (chan chan_size)
        ch-p (utils/partition-or-time batch_size ch batch_size 5)]
    (utils/go-forever
     (let [values (<! ch-p)]
       (try
         (take!
          (alia/execute-chan session (batch statement values)
                             {:consistency :any})
          (fn [rows-or-e]
            (if (instance? Throwable rows-or-e)
              (println rows-or-e "Cassandra error"))))
         (catch Exception e
           (println e "Store processing exception")))))
    ch))

(defn cassandra-metric-store
  "Cassandra metric store."
  [host options]
  (let [keyspace (:cassandra-keyspace options default-cassandra-keyspace)
        session (-> (alia/cluster {:contact-points host})
                    (alia/connect keyspace))
        insert! (get-cassandra-query session)
        chan_size (:cassandra-channel-size options default-cassandra-channel-size)
        batch_size (:cassandra-batch-size options default-cassandra-batch-size)
        channel (get-channel session insert! chan_size batch_size)]
    (reify
      MetricStore
      (insert [this tenant rollup period path time value ttl]
        (>!! channel [(int ttl) [(double value)] (str tenant) (int rollup)
                      (int period) (str path) (long time)]))
      (shutdown [this]
        (.close session)))))
