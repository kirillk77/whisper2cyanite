(ns whisper2cyanite.path-store
  (:require [clojure.string :as str]
            [clojurewerkz.elastisch.rest :as esr]
            [clojurewerkz.elastisch.rest.index :as esri]
            [clojurewerkz.elastisch.rest.document :as esrd]))

(def ^:const default-es-index "cyanite_paths")
(def ^:const es-def-type "path")

(def ^:const es-type-map
  {es-def-type {:_all { :enabled false }
                :_source { :compress false }
                :properties {:tenant {:type "string" :index "not_analyzed"}
                             :path {:type "string" :index "not_analyzed"}}}})

(defn get-all-paths
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

(defprotocol PathStore
  (insert [this tenant path])
  (shutdown [this]))

(defn elasticsearch-metric-store
  "Elasticsearch path store."
  [url options]
  (let [index (:elasticsearch-index options default-es-index)
        conn (esr/connect url)
        exists-fn (partial esrd/present? conn index es-def-type)
        update-fn (partial esrd/put conn index es-def-type)]
    (when-not (esri/exists? conn index)
      (esri/create conn index :mappings es-type-map))
    (reify
      PathStore
      (insert [this tenant path]
        (dorun (map #(when-not (exists-fn (:path %))
                       (update-fn (:path %) %))
                    (get-all-paths tenant path))))
      (shutdown [this]))))
