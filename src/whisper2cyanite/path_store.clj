(ns whisper2cyanite.path-store
  (:require [clojurewerkz.elastisch.rest :as esr]
            [clojurewerkz.elastisch.rest.index :as esri]
            [clojurewerkz.elastisch.rest.document :as esrd]
            [clojure.string :as str]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [whisper2cyanite.logging :as wlog]
            [whisper2cyanite.utils :as utils]))

(defprotocol PathStore
  (insert [this tenant path])
  (exist? [this tenant path])
  (get-stats [this])
  (shutdown [this]))

(def ^:const default-es-channel-size 10000)
(def ^:const default-es-index "cyanite_paths")
(def ^:const es-def-type "path")

(def ^:const es-type-map
  {es-def-type {:_all { :enabled false }
                :_source { :compress false }
                :properties {:tenant {:type "string" :index "not_analyzed"}
                             :path {:type "string" :index "not_analyzed"}}}})

(defn- get-all-paths
  "Get all paths."
  [tenant path]
  (let [parts (str/split path #"\.")
        parts-count (count parts)]
    (map (fn [depth]
           {:path (str/join "." (take depth parts))
            :depth depth
            :tenant tenant
            :leaf (= depth parts-count)})
         (range 1 (inc parts-count)))))

(defn- constuct-id
  "Construct ID."
  [path tenant]
  (str path "_" tenant))

(defn- log-error
  "Log a error."
  [stats-error-paths error path]
  (swap! stats-error-paths conj path)
  (wlog/error (format "Path store error: %s, path: %s" error path)))

(defn show-stats
  "Show stats."
  [stats]
  (let [{:keys [processed errors error-paths]} stats]
    (log/info (format "Path store stats: processed: %s, errors: %s%s"
                      processed errors (if (> (count error-paths) 0)
                                         (str ", erroneous paths:\n"
                                              (str/join "\n" error-paths))
                                         "")))
    (newline)
    (println "Path store stats:")
    (println "  Processed:" processed)
    (println "  Errors:   " errors)))

(defn- get-channel
  "Get store channel."
  [exists-fn update-fn chan-size data-stored? stats-error-paths]
  (let [ch (async/chan chan-size )]
    (utils/go-while (not @data-stored?)
                    (let [value (async/<! ch)]
                      (if value
                        (let [[tenant path] value]
                          (try
                            (dorun (map #(let [id (constuct-id (:tenant %)
                                                               (:path %))]
                                           (when-not (exists-fn id)
                                             (update-fn id %)))
                                        (get-all-paths tenant path)))
                            (catch Exception e
                              (log-error stats-error-paths e path))))
                        (when (not @data-stored?)
                          (swap! data-stored? (fn [_] true))))))
    ch))

(defn elasticsearch-metric-store
  "Elasticsearch path store."
  [url options]
  (log/info "Creating the path store...")
  (let [index (:elasticsearch-index options default-es-index)
        conn (esr/connect url)
        exists-fn (partial esrd/present? conn index es-def-type)
        update-fn (partial esrd/put conn index es-def-type)
        chan-size (:elasticsearch-channel-size options default-es-channel-size)
        data-stored? (atom false)
        stats-error-paths (atom (sorted-set))
        stats-processed (atom 0)
        channel (get-channel exists-fn update-fn chan-size data-stored?
                             stats-error-paths)]
    (log/info (str "The path store has been created. "
                   "URL: " url ", "
                   "index: " index ", "
                   "channel size: " chan-size))
    (when-not (esri/exists? conn index)
      (log/info "Creating the path index...")
      (esri/create conn index :mappings es-type-map)
      (log/info "The path index has been created"))
    (reify
      PathStore
      (insert [this tenant path]
        (try
          (swap! stats-processed inc)
          (async/>!! channel [tenant path])
          (catch Exception e
            (log-error stats-error-paths e path))))
      (exist? [this tenant path]
        (try
          (swap! stats-processed inc)
          (let [exist? (exists-fn (constuct-id tenant path))]
            (when-not exist?
              (swap! stats-error-paths conj path))
            exist?)
          (catch Exception e
            (log-error stats-error-paths e path))))
      (get-stats [this]
        {:processed @stats-processed
         :errors (count @stats-error-paths)
         :error-paths @stats-error-paths})
      (shutdown [this]
        (log/info "Shutting down the path store...")
        (async/close! channel)
        (while (not @data-stored?)
          (Thread/sleep 100))
        (log/info "The path store has been down")))))
