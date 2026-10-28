(ns onyx.seq-plugin-test
  (:require [clojure.core.async :refer [chan]]
            [clojure.test :refer [deftest is]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.plugin.protocols :as p]
            [onyx.plugin.seq :as seq-plugin]
            [onyx.replay :as replay]
            [onyx.tasks.seq :as seq-tasks]))

(defn- read-all [reader]
  (loop [segments []]
    (if-some [segment (p/poll! reader nil 0)]
      (recur (conj segments segment))
      segments)))

(deftest seq-may-be-a-function
  (let [opened (atom 0)
        reader (seq-plugin/input {:onyx.core/task-map {}
                                  :seq/seq (fn []
                                             (swap! opened inc)
                                             (map (fn [n] {:n n}) (range 5)))})]
    (p/recover! reader 0 nil)
    (is (= [{:n 0} {:n 1}] [(p/poll! reader nil 0) (p/poll! reader nil 0)]))
    (is (= 2 (p/checkpoint reader)))
    (p/recover! reader 1 2)
    (is (= [{:n 2} {:n 3} {:n 4}] (read-all reader)))
    (is (p/completed? reader))
    (is (= 2 @opened) "each recovery opens the input again")))

(def out-chan (atom nil))

(def out-calls
  {:lifecycle/before-task-start (fn [_ _] {:core.async/chan @out-chan})})

(deftest input-file-reads-lines
  (let [file (doto (java.io.File/createTempFile "onyx-seq" ".txt") (.deleteOnExit))
        _ (spit file "a\nb\nc\n")
        {:keys [task-map lifecycles]} (:task (seq-tasks/input-file :lines {:onyx/batch-size 2} (.getPath file)))]
    (reset! out-chan (chan 10))
    (replay/run-job {:workflow [[:lines :out]]
                     :catalog [task-map
                               {:onyx/name :out
                                :onyx/plugin :onyx.plugin.core-async/output
                                :onyx/type :output
                                :onyx/medium :core.async
                                :onyx/batch-size 2}]
                     :lifecycles (conj lifecycles {:lifecycle/task :out :lifecycle/calls ::out-calls})
                     :task-scheduler :onyx.task-scheduler/balanced})
    (is (= [{:line "a"} {:line "b"} {:line "c"}] (take-segments! @out-chan 50)))))
