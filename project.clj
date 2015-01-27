(defproject whisper2cyanite "0.1.0"
  :description "Whisper to Cyanite data migration tool."
  :url "https://github.com/cybem/whisper2cyanite"
  :license {:name "MIT License"
            :url "https://github.com/cybem/whisper2cyanite/blob/master/LICENSE"}
  :maintainer {:email "cybem@cybem.info"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [clj-whisper "0.1.0"]
                 [org.clojure/tools.cli "0.3.1"]
                 [cc.qbits/alia "2.3.1"]
                 [clojurewerkz/elastisch "2.1.0"]
                 [com.climate/claypoole "0.4.0"]
                 [intervox/clj-progress "0.1.6"]]
  :main ^:skip-aot whisper2cyanite.cli
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
