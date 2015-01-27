(ns whisper2cyanite.metric-store
  (:require [qbits.alia :as alia]
            [qbits.alia.policy.load-balancing :as alia_lbp]))

(defprotocol MetricStore
  (insert [this tenant rollup period path time value ttl])
  (shutdown [this]))

(def ^:const default-cassandra-keyspace "metric")

(defn get-cassandra-query
  "Get a Cassandra prepared statement."
  [session]
  (alia/prepare
   session
   (str
    "UPDATE metric USING TTL ? SET data = ? "
    "WHERE tenant = ? AND rollup = ? AND period = ? AND path = ? AND time = ?;")))

(defn cassandra-metric-store
  "Cassandra metric store."
  [host options]
  (let [keyspace (:cassandra-keyspace options default-cassandra-keyspace)
        session (-> (alia/cluster {:contact-points host})
                    (alia/connect keyspace))
        insert! (get-cassandra-query session)]
    (reify
      MetricStore
      (insert [this tenant rollup period path time value ttl]
        (alia/execute session insert!
                      {:values [(int ttl) [(double value)] (str tenant)
                                (int rollup) (int period) (str path)
                                (long time)]}))
      (shutdown [this]
        (.close session)))))
