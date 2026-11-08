(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.github.clojure-finance/onyx)
(def version "0.17.2")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  ;; write-pom takes ./pom.xml as its template if there is one (Leiningen
  ;; used to leave one), so the repo must not have one.
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :scm {:url "https://github.com/clojure-finance/onyx"
                      :connection "scm:git:git://github.com/clojure-finance/onyx.git"
                      :developerConnection "scm:git:ssh://git@github.com/clojure-finance/onyx.git"
                      :tag version}
                :basis @basis
                :src-dirs ["src"]
                :resource-dirs ["resources"]
                :pom-data [[:description
                            "Distributed, masterless, high performance, fault tolerant data processing for Clojure"]
                           [:url "https://github.com/clojure-finance/onyx"]
                           [:licenses
                            [:license
                             [:name "Eclipse Public License"]
                             [:url "http://www.eclipse.org/legal/epl-v10.html"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis @basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

;; Builds the jar, then pushes it to Clojars. Reads CLOJARS_USERNAME and
;; CLOJARS_PASSWORD from the environment.
(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib
                                     :class-dir class-dir})}))
