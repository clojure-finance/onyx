(ns onyx.seq-plugin-test
  (:require [clojure.test :refer [deftest is]]
            [onyx.plugin.protocols :as p]
            [onyx.plugin.seq :as seq-plugin]))

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
