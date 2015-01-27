(ns leiningen.fatdeb
  "Build a deb-package from Leininen. Stolen from Riemann project."
  (:refer-clojure :exclude [replace])
  (:use [clojure.java.shell :only [sh]]
        [clojure.java.io :only [file delete-file writer copy]]
        [clojure.string :only [join capitalize trim-newline replace]]
        [leiningen.uberjar :only [uberjar]])
  (:import java.text.SimpleDateFormat
           java.util.Date))

(defn md5
  [input]
  (let [digest (-> (doto (java.security.MessageDigest/getInstance "MD5")
                     (.reset)
                     (.update (.getBytes input)))
                   (.digest))]
    (.toString (java.math.BigInteger. 1 digest) 16)))

(defn delete-file-recursively
    "Delete file f. If it's a directory, recursively delete all its contents.
    Raise an exception if any deletion fails unless silently is true."
    [f & [silently]]
    (System/gc) ; This sometimes helps release files for deletion on windows.
    (let [f (file f)]
          (if (.isDirectory f)
                  (doseq [child (.listFiles f)]
                            (delete-file-recursively child silently)))
          (delete-file f silently)))

(defn deb-dir
  "Debian package working directory."
  [project]
  (file (:root project) "target/deb" (:name project)))

(defn cleanup
  [project]
  ; Delete working dir.
  (when (.exists (deb-dir project))
    (delete-file-recursively (deb-dir project))))

(defn reset
  [project]
  (cleanup project)
  (sh "rm" (str (:root project) "/target/*.deb")))

(def build-date (Date.))
(defn get-version
  [project]
  (let [df (SimpleDateFormat. "yyyyMMdd-HHmmss")]
    (replace (:version project) #"SNAPSHOT" (.format df build-date))))

(defn control
  "Control file"
  [project]
  (join "\n"
        (map (fn [[k v]] (str (capitalize (name k)) ": " v))
             {:package (:name project)
              :version (get-version project)
              :section "java"
              :priority "optional"
              :architecture "all"
              ; See riemann#200, riemann#248, riemann#419 for why this is not :depends
              :recommends (join ", " ["default-jre-headless (<= 1.7) | java7-runtime"])
              :maintainer (:email (:maintainer project))
              :description (:description project)})))

(defn write
  "Write string to file, plus newline"
  [file string]
  (with-open [w (writer file)]
    (.write w (str (trim-newline string) "\n"))))

(defn make-deb-dir
  "Creates the debian package structure in a new directory."
  [project]
  (let [dir (deb-dir project)
        name (:name project)]
    (.mkdirs dir)
    ; Meta
    (.mkdirs (file dir "DEBIAN"))
    (write (file dir "DEBIAN" "control") (control project))
    ; Jar
    (.mkdirs (file dir "usr" "lib" name))
    (copy (file (:root project) "target" "uberjar"
                (str name "-" (:version project) "-standalone.jar"))
          (file dir "usr" "lib" name (str name ".jar")))
    ; Binary
    (.mkdirs (file dir "usr" "bin"))
    (copy (file (:root project) name)
          (file dir "usr" "bin" name))
    (.setExecutable (file dir "usr" "bin" name) true false)
    ;
    dir))

(defn dpkg
  "Convert given package directory to a .deb."
  [project deb-dir]
  (print (:err (sh "fakeroot" "dpkg" "--build"
                   (str deb-dir)
                   (str (file (:root project) "target")))))
  (let [deb-file-name (str (:name project) "_"
                           (get-version project) "_"
                           "all" ".deb")
        deb-file (file (:root project) "target" deb-file-name)]
    (write (str deb-file ".md5")
           (str (md5 (slurp deb-file)) "  " deb-file-name))))

(defn fatdeb
  ([project] (fatdeb project true))
  ([project uberjar?]
   (reset project)
   (when uberjar? (uberjar project))
   (dpkg project (make-deb-dir project))
   (cleanup project)
   (flush)))
