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
             (frequencies (take-segments! @out-chan 1000)))))))

(def input (atom nil))

(def inject-input
  {:lifecycle/before-task-start (fn [_ _] {:seq/seq @input})})

(defn event-time-ms [{:keys [event-time]}]
  (.getTime ^java.util.Date event-time))

(defn t-ms [{:keys [t]}]
  t)

(defn emit-window [_ _ _ {:keys [lower-bound upper-bound group-key]} extent-state]
  (cond-> {:window [lower-bound upper-bound] :state extent-state}
    group-key (assoc :key group-key)))

(defn windowed-job [segments batch-size watermark-fn window-task window trigger]
  (reset! input segments)
  {:workflow [[:in :window] [:window :out]]
   :catalog [{:onyx/name :in
              :onyx/plugin :onyx.plugin.seq/input
              :onyx/type :input
              :onyx/medium :seq
              :onyx/assign-watermark-fn watermark-fn
              :onyx/batch-size batch-size
              :onyx/max-peers 1}
             (merge {:onyx/name :window :onyx/batch-size batch-size} window-task)
             {:onyx/name :out
              :onyx/plugin :onyx.plugin.core-async/output
              :onyx/type :output
              :onyx/medium :core.async
              :onyx/batch-size batch-size
              :onyx/max-peers 1}]
   :lifecycles [{:lifecycle/task :in :lifecycle/calls ::inject-input}
                {:lifecycle/task :out :lifecycle/calls ::out-calls}]
   :windows [(merge {:window/id :w :window/task :window :window/type :fixed} window)]
   :triggers [(merge {:trigger/window-id :w
                      :trigger/id :emit
                      :trigger/on :onyx.triggers/watermark
                      :trigger/post-evictor [:all]
                      :trigger/state-context [:window-state]
                      :trigger/emit ::emit-window}
                     trigger)]
   :task-scheduler :onyx.task-scheduler/balanced})

(def watermark-input
  [{:id 1 :event-time #inst "2015-09-13T03:00:00.829-00:00"}
   {:id 11 :event-time #inst "2015-09-13T03:03:00.829-00:00"}
   {:id 2 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   {:id 3 :event-time #inst "2015-09-13T03:05:00.829-00:00"}
   {:id 4 :event-time #inst "2015-09-13T03:06:00.829-00:00"}
   {:id 5 :event-time #inst "2015-09-13T03:07:00.829-00:00"}
   {:id 6 :event-time #inst "2015-09-13T03:08:00.829-00:00"}
   {:id 7 :event-time #inst "2015-09-13T03:09:00.829-00:00"}
   {:id 8 :event-time #inst "2015-09-13T03:15:00.829-00:00"}
   {:id 15 :event-time #inst "2015-09-13T03:16:00.829-00:00"}
   {:id 9 :event-time #inst "2015-09-13T03:25:00.829-00:00"}
   {:id 14 :event-time #inst "2015-09-13T03:32:00.829-00:00"}
   {:id 10 :event-time #inst "2015-09-13T03:45:00.829-00:00"}
   {:id 12 :event-time #inst "2015-09-13T03:56:00.829-00:00"}
   {:id 13 :event-time #inst "2015-09-13T03:59:00.829-00:00"}])

(deftest watermark-trigger-fires-at-the-same-point-every-run
  ;; Port of onyx.windowing.window-watermark-test, which upstream disabled
  ;; as brittle: there, watermarks reach the task on timed barriers.
  (let [job (fn [batch-size]
              (windowed-job watermark-input batch-size ::event-time-ms
                            {:onyx/type :function :onyx/fn :clojure.core/identity}
                            {:window/aggregation :onyx.windowing.aggregation/count
                             :window/window-key :event-time
                             :window/range [5 :minutes]}
                            {}))
        w (fn [lower n] {:window [lower (+ lower 299999)] :state n})
        windows [(w 1442113200000 3) (w 1442113500000 5) (w 1442114100000 2)
                 (w 1442114700000 1) (w 1442115000000 1) (w 1442115900000 1)
                 (w 1442116500000 2)]
        in (fn [from to] (subvec watermark-input from to))]
    (testing "one segment per batch: each window comes right after the segment whose watermark closed it, the last at job completion"
      (is (= (concat (in 0 4) [(windows 0)]
                     (in 4 9) [(windows 1)]
                     (in 9 11) [(windows 2)]
                     (in 11 12) [(windows 3)]
                     (in 12 13) [(windows 4)]
                     (in 13 14) [(windows 5)]
                     (in 14 15) [(windows 6)])
             (:out (run (job 1) {:parallelism 4})))))
    (testing "any batch size: the same windows in the same order"
      (doseq [batch-size [2 5 20]]
        (is (= windows (filter :window (:out (run (job batch-size) {:parallelism 4})))))))))

(def grouped-input
  (vec (for [i (range 300)] {:k (mod (* 7 i) 5) :t (* 1000 i) :v i})))

(def grouped-sums
  (for [[[k lower] segments] (group-by (fn [{:keys [k t]}] [k (* 20000 (quot t 20000))])
                                       grouped-input)]
    {:key k :window [lower (+ lower 19999)] :state (reduce + (map :v segments))}))

(defn grouped-job [segments]
  (windowed-job segments 10 ::t-ms
                {:onyx/type :reduce
                 :onyx/group-by-key :k
                 :onyx/flux-policy :recover
                 :onyx/n-peers 1}
                {:window/aggregation [:onyx.windowing.aggregation/sum :v]
                 :window/window-key :t
                 :window/range [20 :seconds]}
                {}))

(deftest grouped-windows
  (let [runs (repeatedly 3 #(:out (run (grouped-job grouped-input) {:parallelism 8})))]
    (testing "each key's window is emitted once, with its sum"
      (is (= (frequencies grouped-sums) (frequencies (first runs)))))
    (testing "in the same order on every run"
      (is (apply = runs)))))

(deftest same-windows-as-onyx
  (let [id (java.util.UUID/randomUUID)
        config (load-config)
        env-config (assoc (:env-config config) :onyx/tenancy-id id)
        peer-config (assoc (:peer-config config) :onyx/tenancy-id id)
        ;; Many keys, so that many windows fire at job completion.
        segments (vec (for [i (range 3000)] {:k (mod i 1000) :t (* 1000 i) :v i}))
        replayed (:out (run (grouped-job segments) {}))
        job (grouped-job segments)]
    (reset! out-chan (chan 100000))
    (with-test-env [test-env [3 env-config peer-config]]
      (->> (onyx.api/submit-job peer-config job)
           :job-id
           (feedback-exception! peer-config))
      (is (= 3000 (count replayed)))
      (is (= (frequencies replayed)
             (frequencies (take-segments! @out-chan 1000)))))))
