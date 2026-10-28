(ns onyx.tasks.seq
  (:require [onyx.schema :as os]
            [onyx.plugin.seq]
            [schema.core :as s])
  (:import [java.io FileReader BufferedReader]))

(s/defn input-serialized
  [task-name :- s/Keyword opts sequential]
   {:task {:task-map (merge {:onyx/name task-name
                             :onyx/plugin :onyx.plugin.seq/input
                             :onyx/type :input
                             :onyx/medium :seq
                             :onyx/n-peers 1
                             :onyx/doc "Reads segments from a seq"}
                            opts)
           :lifecycles [{:lifecycle/task task-name
                         :seq/sequential sequential
                         :lifecycle/calls :onyx.plugin.seq/inject-seq-via-lifecycle}]}})

(s/defn input-materialized
  [task-name :- s/Keyword opts lifecycle]
   {:task {:task-map (merge {:onyx/name task-name
                             :onyx/plugin :onyx.plugin.seq/input
                             :onyx/type :input
                             :onyx/medium :seq
                             :onyx/n-peers 1
                             :onyx/doc "Reads segments from a seq"}
                            opts)
           :lifecycles [lifecycle]}})


;; :seq/seq is a function, so the reader does not hold on to lines it has
;; read. Each call (one per recovery) opens the file again; all are closed
;; when the task stops.
(defn inject-in-reader [event lifecycle]
  (let [filename (:buffered-reader/filename lifecycle)
        readers (atom [])]
    {:seq/readers readers
     :seq/seq (fn []
                (let [rdr (FileReader. ^String filename)]
                  (swap! readers conj rdr)
                  (map (fn [line] {:line line})
                       (line-seq (BufferedReader. rdr)))))}))

(defn close-reader [event lifecycle]
  (run! (fn [^FileReader rdr] (.close rdr)) @(:seq/readers event)))

(def in-file-calls
  {:lifecycle/before-task-start inject-in-reader
   :lifecycle/after-task-stop close-reader})

(s/defn input-file
  [task-name :- s/Keyword opts filename]
   {:task {:task-map (merge {:onyx/name task-name
                             :onyx/plugin :onyx.plugin.seq/input
                             :onyx/type :input
                             :onyx/medium :seq
                             :onyx/n-peers 1
                             :onyx/doc "Reads segments from a seq"}
                            opts)
           :lifecycles [{:lifecycle/task task-name
                         :buffered-reader/filename filename
                         :lifecycle/calls ::in-file-calls}]}})
