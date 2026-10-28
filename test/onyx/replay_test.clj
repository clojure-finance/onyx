(ns onyx.replay-test
  (:require [clojure.core.async :refer [chan]]
            [clojure.test :refer [deftest is testing]]
            [onyx.api]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.plugin.seq]
            [onyx.replay :as replay]
            [onyx.test-helper :refer [load-config with-test-env feedback-exception!]]))

(def out-chan (atom nil))

(defn inject-out-ch [_ _]
  {:core.async/chan @out-chan})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(defn parse [{:keys [n]}]
  {:n n :even? (even? n)})

(defn scale [k {:keys [n] :as segment}]
  (assoc segment :n (* k n)))

(defn jitter [segment]
  (Thread/sleep (long (rand-int 3)))
  segment)

(defn drop-odd [segments]
  (map (fn [{:keys [even?] :as segment}] (if even? segment [])) segments))

(defn boom [{:keys [n] :as segment}]
  (if (= n 7)
    (throw (ex-info "boom" {:n n}))
    segment))

(defn job [n workflow fns]
  {:workflow workflow
   :catalog (into [{:onyx/name :in
                    :onyx/plugin :onyx.plugin.seq/input
                    :onyx/type :input
                    :onyx/medium :seq
                    :onyx/batch-size 10
                    :onyx/max-peers 1}
                   {:onyx/name :out
                    :onyx/plugin :onyx.plugin.core-async/output
                    :onyx/type :output
                    :onyx/medium :core.async
                    :onyx/batch-size 10
                    :onyx/max-peers 1}]
                  (map (fn [[task-name task-map]]
                         (merge {:onyx/name task-name
                                 :onyx/type :function
                                 :onyx/batch-size 10}
                                task-map)))
                  fns)
   :lifecycles [{:lifecycle/task :in
                 :lifecycle/calls :onyx.plugin.seq/inject-seq-via-lifecycle
                 :seq/sequential (map (fn [n] {:n n}) (range n))}
                {:lifecycle/task :out
                 :lifecycle/calls ::out-calls}]
   :task-scheduler :onyx.task-scheduler/balanced})

(defn run [job opts]
  (reset! out-chan (chan 100000))
  (let [result (replay/run-job job opts)]
    (assoc result :out (take-segments! @out-chan 50))))

(def diamond
  (job 1000
       [[:in :parse] [:parse :double] [:parse :triple] [:double :out] [:triple :out]]
       {:parse {:onyx/fn ::parse}
        :double {:onyx/fn ::scale :scale/k 2 :onyx/params [:scale/k]}
        :triple {:onyx/fn ::scale :scale/k 3 :onyx/params [:scale/k]}}))

(deftest linear-job
  (let [{:keys [out segments]}
        (run (job 100
                  [[:in :parse] [:parse :evens] [:evens :out]]
                  {:parse {:onyx/fn ::parse}
                   :evens {:onyx/fn ::drop-odd :onyx/batch-fn? true}})
             {:parallelism 4})]
    (is (= (map (fn [n] {:n n :even? true}) (range 0 100 2)) out))
    (is (= {:in 100 :parse 100 :evens 50 :out 50} segments))))

(deftest diamond-is-deterministic
  (testing "fan-out sends every batch to both children; fan-in alternates batches in workflow order"
    (let [expected (mapcat (fn [ns]
                             (concat (map (fn [n] {:n (* 2 n) :even? (even? n)}) ns)
                                     (map (fn [n] {:n (* 3 n) :even? (even? n)}) ns)))
                           (partition 10 (range 1000)))]
      (is (= expected (:out (run diamond {:parallelism 1}))))))
  (testing "the same order with parallel tasks and random delays"
    (let [jittery (update diamond :catalog
                          (partial mapv (fn [{:keys [onyx/name] :as task-map}]
                                          (if (= :parse name)
                                            (assoc task-map :onyx/fn ::jitter)
                                            task-map))))
          runs (repeatedly 3 #(:out (run jittery {:parallelism 8})))]
      (is (= 2000 (count (first runs))))
      (is (apply = runs)))))

(deftest exceptions-fail-the-run
  (let [e (try
            (run (job 100 [[:in :boom] [:boom :out]] {:boom {:onyx/fn ::boom}})
                 {:parallelism 4})
            nil
            (catch clojure.lang.ExceptionInfo e e))]
    (is (= "Segment threw exception" (ex-message e)))
    (is (= {:task :boom :segment {:n 7}} (ex-data e)))
    (is (= {:n 7} (ex-data (ex-cause e))))))

(deftest unsupported-features-are-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not support flow conditions"
                        (replay/run-job (assoc diamond :flow-conditions
                                               [{:flow/from :parse :flow/to [:double]
                                                 :flow/predicate ::even?}])))))

(deftest same-segments-as-onyx
  (let [id (java.util.UUID/randomUUID)
        config (load-config)
        env-config (assoc (:env-config config) :onyx/tenancy-id id)
        peer-config (assoc (:peer-config config) :onyx/tenancy-id id)
        replayed (:out (run diamond {}))]
    (reset! out-chan (chan 100000))
    (with-test-env [test-env [5 env-config peer-config]]
      (->> (onyx.api/submit-job peer-config diamond)
           :job-id
           (feedback-exception! peer-config))
      (is (= (frequencies replayed)
             (frequencies (take-segments! @out-chan 50)))))))
