(ns whisper2cyanite.logging
  (:require [whisper2cyanite.utils :as utils]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.spootnik.logconfig :as logconfig]))

(def ^:const default-log-file "whisper2cyanite.log")
(def ^:const default-log-level "info")

(def print-log? (atom false))
(def disable-log? (atom false))
(def ignore-errors? (atom false))

(def total-errors (atom 0))

(defn set-logging!
  "Set options."
  [options]
  (swap! print-log? (fn [_] (:disable-progress options @print-log?)))
  (swap! disable-log? (fn [_] (:disable-log options @disable-log?)))
  (swap! ignore-errors? (fn [_] (:ignore-errors options @ignore-errors?)))
  (logconfig/start-logging!
   {:level (:log-level options default-log-level)
    :files [(:log-file options default-log-file)]}))

(defn info-always
  "Always log info."
  [& args]
  (let [args-str (str/join "" args)]
    (println args-str)
    (log/info args-str)))

(defn exit
  "Exit."
  [ret-code]
  (log/info "Shutting down agents...")
  (shutdown-agents)
  (log/info "Agents have been down")
  (info-always (format "Exiting with code %s..." ret-code))
  (System/exit ret-code))

(defn info
  "Log info."
  [& args]
  (let [args-str (str/join "" args)]
    (when @print-log?
      (println args-str))
    (log/info args-str)))

(defn warning
  "Log warning."
  [& args]
  (let [args-str (str/join "" args)]
    (when @print-log?
      (println args-str))
    (log/warn args-str)))

(defn error
  "Log error."
  [& args]
  (let [args-str (str/join "" args)]
    (when (and (not @print-log?) (not @ignore-errors?))
      (newline))
    (when (or @print-log? (not @ignore-errors?))
      (println args-str))
    (log/error args-str))
  (when-not @ignore-errors?
    (exit 1)))

(defn fatal
  "Log fatal."
  [& args]
  (let [args-str (str/join "" args)]
    (when-not @print-log?
      (newline))
    (println args-str)
    (log/fatal args-str))
  (exit 1))

(defn unhandled-error
  "Log unhandled error."
  [e]
  (fatal "Error: " e))
