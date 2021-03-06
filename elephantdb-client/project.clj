(def ROOT-DIR (subs *file* 0 (- (count *file*) (count "project.clj"))))
(def VERSION (-> ROOT-DIR (str "/../VERSION") slurp))

(defproject elephantdb/elephantdb-client VERSION
  :description "A client interface to ElephantDB"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [ch.qos.logback/logback-classic "1.0.11"]
                 [elephantdb/elephantdb-thrift ~VERSION
                  :exclusions [org.slf4j/slf4j-api]]]
  :profiles {:dev
             {:dependencies [[midje "1.6.3"]]
              :plugins [[lein-midje "3.1.3"]]}}
  :source-paths ["src/clj"])
