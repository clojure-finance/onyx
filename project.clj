(defproject com.github.clojure-finance/onyx "0.16.0"
  :description "Distributed, masterless, high performance, fault tolerant data processing for Clojure"
  :url "https://github.com/clojure-finance/onyx"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories {"snapshots" {:url "https://clojars.org/repo"
                              :username :env/clojars_username
                              :password :env/clojars_password
                              :sign-releases false}
                 "releases" {:url "https://clojars.org/repo"
                             :username :env/clojars_username
                             :password :env/clojars_password
                             :sign-releases false}}
  :deploy-repositories [["releases"  {:sign-releases false :url "https://clojars.org/repo"}]
                        ["snapshots" {:sign-releases false :url "https://clojars.org/repo"}]]
  :dependencies [[org.clojure/clojure "1.11.0"]
                 [org.clojure/core.async "1.6.673"]
                 [org.apache.curator/curator-test "5.9.0"]
                 [org.apache.curator/curator-framework "5.9.0"]
                 [org.apache.curator/curator-client "5.9.0" :exclusions [org.apache.zookeeper/zookeeper]]
                 [org.apache.zookeeper/zookeeper "3.9.5"]
                 [org.slf4j/slf4j-api "1.7.36"]
                 [org.slf4j/slf4j-nop "1.7.36"]
                 [org.btrplace/scheduler-api "0.46"]
                 [org.btrplace/scheduler-choco "0.46"]
                 [com.stuartsierra/dependency "1.0.0"]
                 [com.stuartsierra/component "1.1.0"]
                 [metrics-clojure "2.10.0"]
                 [com.taoensso/timbre "4.8.0"]
                 [com.taoensso/nippy "2.14.0"]
                 [io.aeron/aeron-all "1.53.1"]
                 [io.replikativ/hasch "0.3.4"
                  :exclusions [org.clojure/clojurescript com.cognitect/transit-clj
                               com.cognitect/transit-cljs org.clojure/data.fressian
                               com.cemerick/austin]]
                 [prismatic/schema "1.4.1"]
                 [com.amazonaws/aws-java-sdk-s3 "1.11.271"]
                 [primitive-math "0.1.6"]
                 [clj-tuple "0.2.2"]
                 [clj-fuzzy "0.4.1" :exclusions [org.clojure/clojurescript]]
                 [org.deephacks.lmdbjni/lmdbjni "0.4.6"]
                 [org.deephacks.lmdbjni/lmdbjni-linux64 "0.4.6"]
                 [org.deephacks.lmdbjni/lmdbjni-win64 "0.4.6"]
                 [org.deephacks.lmdbjni/lmdbjni-osx64 "0.4.6"]
                 [org.clojure/tools.analyzer "1.1.1"]
                 [com.taoensso/encore "3.60.0"]
                 [io.aviso/pretty "1.4.4"]]
                 :jvm-opts ^:replace ["-server"
                                      "-Xmx2400M"
                                      "-XX:+UseG1GC"
                                      "-XX:-OmitStackTraceInFastThrow"
                                      "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"]
                 :profiles {:dev {:global-vars {*warn-on-reflection* true}
                                  :dependencies [[org.clojure/tools.nrepl "0.2.13"]
                                                 [org.clojure/java.jmx "1.0.0"]
                                                 [org.clojure/test.check "1.1.1"]
                                                 [org.senatehouse/expect-call "0.3.0"]
                                                 [mdrogalis/stateful-check "0.3.2"]
                                                 [com.gfredericks/test.chuck "0.2.14"]
                                                 [joda-time/joda-time "2.12.5"]]
                                  :plugins [[codox "0.8.8"]]
                                  :resource-paths ["test-resources/"]}}
                 :test-selectors {:default (fn [t] (if-not (or (:stress t) (:broken t))
                                                     t))
                                  :stress :stress
                                  :broken :broken
                                  :smoke :smoke}
                 :codox {:output-dir "doc/api"})
