(ns whisper2cyanite.logging
  (:require [clojure.string :as str]
            [clojure.stacktrace :as stacktrace]
            [clojure.tools.logging :as log]
            [org.spootnik.logconfig :as logconfig]))

(defmacro log-disable-logging!
  "Disable logging macro."
  []
  `(logconfig/start-logging! {:level "off" :console false :files ""}))

(log-disable-logging!)

(def ^:const default-log-file "whisper2cyanite.log")
(def ^:const default-log-level "info")

(def print-log? (atom false))
(def disable-log? (atom false))
(def stop-on-error? (atom false))

(defn set-logging!
  "Set options."
  [options]
  (swap! print-log? (fn [_] (:disable-progress options @print-log?)))
  (swap! disable-log? (fn [_] (:disable-log options @disable-log?)))
  (swap! stop-on-error? (fn [_] (:stop-on-error options @stop-on-error?)))
  (if-not @disable-log?
    (logconfig/start-logging! {:level (:log-level options default-log-level)
                               :files [(:log-file options default-log-file)]})
    (log-disable-logging!)))

(defmacro log-log
  [f msg throwable]
  `(if (instance? Throwable ~throwable)
     (~f ~throwable ~msg)
     (~f ~msg)))

(defmacro log-print
  [msg throwable]
  `(do
     (println ~msg)
     (when (instance? Throwable ~throwable)
       (stacktrace/print-stack-trace ~throwable))))

(defmacro info-always
  "Always log info."
  [msg & [throwable]]
  `(do
     (log-print ~msg ~throwable)
     (log-log log/info ~msg ~throwable)))

(defn exit
  "Exit."
  [ret-code]
  (log/info "Shutting down agents...")
  (shutdown-agents)
  (log/info "Agents have been down")
  (let [exit-msg (format "Exiting with code %s..." ret-code)]
    (log/info exit-msg)
    (when (not= ret-code 0)
      (println exit-msg)))
  (System/exit ret-code))

(defmacro info
  "Log info."
  [msg & [throwable]]
  `(do
     (when @print-log?
       (log-print ~msg ~throwable))
     (log-log log/info ~msg ~throwable)))

(defmacro warning
  "Log warning."
  [msg & [throwable]]
  `(do
     (when @print-log?
       (log-print ~msg ~throwable))
     (log-log log/warn ~msg ~throwable)))

(defmacro error
  "Log error."
  [msg & [throwable]]
  `(do
     (when (and (not @print-log?) @stop-on-error?)
       (newline))
     (when (or @print-log? @stop-on-error?)
       (log-print ~msg ~throwable))
     (log-log log/error ~msg ~throwable)
     (when @stop-on-error?
       (exit 1))))

(defmacro fatal
  "Log fatal."
  [msg & [throwable]]
  `(do
     (when-not @print-log?
       (newline))
     (log-print ~msg ~throwable)
     (log-log log/fatal ~msg ~throwable)
     (exit 1)))

(defn unhandled-error
  "Log unhandled error."
  [throwable]
  (fatal (str "Fatal error: " throwable) throwable))
