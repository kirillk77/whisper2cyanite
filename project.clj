(defproject whisper2cyanite "0.8.0"
  :description "Whisper to Cyanite data migration tool."
  :url "https://github.com/cybem/whisper2cyanite"
  :license {:name "MIT License"
            :url "https://github.com/cybem/whisper2cyanite/blob/master/LICENSE"}
  :maintainer {:email "cybem@cybem.info"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [clj-whisper "0.2.1"]
                 [org.clojure/tools.cli "0.3.1"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.spootnik/logconfig "0.7.2"]
                 [cc.qbits/alia "2.3.1"]
                 [net.jpountz.lz4/lz4 "1.3.0"]
                 [org.xerial.snappy/snappy-java "1.1.1.6"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [clojurewerkz/elastisch "2.1.0"]
                 [com.climate/claypoole "0.4.0"]
                 [throttler "1.0.0"]
                 [intervox/clj-progress "0.1.6"]
                 [clojure-humanize "0.1.0"]]
  :main ^:skip-aot whisper2cyanite.cli
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
