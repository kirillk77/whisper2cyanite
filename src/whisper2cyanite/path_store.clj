(ns whisper2cyanite.path-store
  (:require [clojurewerkz.elastisch.rest :as esr]
            [clojurewerkz.elastisch.rest.index :as esri]
            [clojurewerkz.elastisch.rest.document :as esrd]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [whisper2cyanite.logging :as wlog]
            [whisper2cyanite.utils :as utils]))

(defprotocol PathStore
  (insert [this tenant path file])
  (exist? [this tenant path file])
  (get-stats [this])
  (shutdown [this]))

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
  "Construct an ID."
  [path tenant]
  (str path "_" tenant))

(defn- log-error
  "Log a error."
  [stats-error-files file error path]
  (swap! stats-error-files conj file)
  (wlog/error (format "Path store error: %s, path: %s" error path)))

(defn- put-path
  "Put a path."
  [exists-fn update-fn body stats-error-files file]
  (let [path (:path body)
        id (constuct-id (:tenant body) path)]
    (when-not (exists-fn id)
      (let [return (update-fn id body)
            status (:status return)]
        (when (and status (>= status 400))
          (log-error stats-error-files file
                     (str (:error return) ", status: " status
                          ", file: "file ) path))))))

(defn elasticsearch-metric-store
  "Create an Elasticsearch path store."
  [url options]
  (log/info "Creating the path store...")
  (let [index (:elasticsearch-index options default-es-index)
        conn (esr/connect url)
        exists-fn (partial esrd/present? conn index es-def-type)
        update-fn (partial esrd/put conn index es-def-type)
        data-stored? (atom false)
        stats-error-files (atom (sorted-set))
        stats-processed (atom 0)]
    (log/info (str "The path store has been created. "
                   "URL: " url ", "
                   "index: " index))
    (when-not (esri/exists? conn index)
      (log/info "Creating the path index...")
      (esri/create conn index :mappings es-type-map)
      (log/info "The path index has been created"))
    (reify
      PathStore
      (insert [this tenant path file]
        (try
          (swap! stats-processed inc)
          (dorun (map #(put-path exists-fn update-fn %
                                 stats-error-files file)
                      (get-all-paths tenant path)))
          (catch Exception e
            (log-error stats-error-files file e path))))
      (exist? [this tenant path file]
        (try
          (swap! stats-processed inc)
          (exists-fn (constuct-id tenant path))
          (catch Exception e
            (log-error stats-error-files file e path)
            :pstore-error)))
      (get-stats [this]
        {:processed @stats-processed
         :error-files @stats-error-files})
      (shutdown [this]
        (log/info "Shutting down the path store...")
        (log/info "The path store has been down")))))
