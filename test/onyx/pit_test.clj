(ns onyx.pit-test
  (:require [clojure.core.async :refer [chan]]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [onyx.api]
            [onyx.pit :as pit]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.plugin.seq]
            [onyx.replay :as replay]
            [onyx.test-helper :refer [load-config with-test-env feedback-exception!]])
  (:import [clojure.lang ExceptionInfo]))

;; Quarterly EPS with fiscal period end (:pit/event) and filing date
;; (:pit/known), ordered by filing date. AAA's 2024Q1 is restated on June 1.
(def fundamentals
  [{:tic "AAA" :quarter "2023Q4" :eps 1.0 :pit/event #inst "2023-12-31" :pit/known #inst "2024-02-15"}
   {:tic "BBB" :quarter "2024Q1" :eps 2.0 :pit/event #inst "2024-03-31" :pit/known #inst "2024-04-20"}
   {:tic "AAA" :quarter "2024Q1" :eps 1.2 :pit/event #inst "2024-03-31" :pit/known #inst "2024-05-01"}
   {:tic "AAA" :quarter "2024Q1" :eps 1.1 :pit/event #inst "2024-03-31" :pit/known #inst "2024-06-01"}])

(def prices
  [{:tic "AAA" :close 10.0 :pit/known #inst "2024-02-01"}
   {:tic "AAA" :close 11.0 :pit/known #inst "2024-04-15"}
   {:tic "BBB" :close 20.0 :pit/known #inst "2024-04-15"}
   {:tic "BBB" :close 21.0 :pit/known #inst "2024-04-25"}
   {:tic "AAA" :close 12.0 :pit/known #inst "2024-05-02"}
   {:tic "AAA" :close 13.0 :pit/known #inst "2024-06-15"}])

(def inputs (atom nil))
(def out-chan (atom nil))

(def inject-prices
  {:lifecycle/before-task-start (fn [_ _] {:seq/seq (:prices @inputs)})})

(def inject-fundamentals
  {:lifecycle/before-task-start (fn [_ _] {:seq/seq (:fundamentals @inputs)})})

(def inject-out
  {:lifecycle/before-task-start (fn [_ _] {:core.async/chan @out-chan})})

(defn seq-input [task-name batch-size]
  {:onyx/name task-name
   :onyx/plugin :onyx.plugin.seq/input
   :onyx/type :input
   :onyx/medium :seq
   :onyx/batch-size batch-size
   :onyx/max-peers 1})

(defn job [batch-size]
  (let [{:keys [catalog-entry window trigger]} (pit/as-of-join :join :tic batch-size)]
    {:workflow [[:prices :join] [:fundamentals :join] [:join :out]]
     :catalog [(pit/input (seq-input :prices batch-size) :left)
               (pit/input (seq-input :fundamentals batch-size) :right)
               catalog-entry
               {:onyx/name :out
                :onyx/plugin :onyx.plugin.core-async/output
                :onyx/type :output
                :onyx/medium :core.async
                :onyx/batch-size batch-size
                :onyx/max-peers 1}]
     :lifecycles [{:lifecycle/task :prices :lifecycle/calls ::inject-prices}
                  {:lifecycle/task :fundamentals :lifecycle/calls ::inject-fundamentals}
                  {:lifecycle/task :out :lifecycle/calls ::inject-out}]
     :windows [window]
     :triggers [trigger]
     :task-scheduler :onyx.task-scheduler/balanced}))

(defn run [job]
  (reset! out-chan (chan 10000))
  (replay/run-job (pit/check-job job) {:parallelism 4})
  (take-segments! @out-chan 50))

(defn summary [{:keys [tic close pit/as-of]}]
  [tic close (when as-of [(:quarter as-of) (:eps as-of)])])

(def expected
  #{["AAA" 10.0 nil]                ; nothing filed yet
    ["AAA" 11.0 ["2023Q4" 1.0]]     ; 2024Q1 ended Mar 31 but was filed May 1
    ["BBB" 20.0 nil]                ; BBB's first filing is Apr 20
    ["BBB" 21.0 ["2024Q1" 2.0]]
    ["AAA" 12.0 ["2024Q1" 1.2]]     ; as first reported
    ["AAA" 13.0 ["2024Q1" 1.1]]})   ; as restated on Jun 1

(defn period-end-ms [{:keys [pit/event]}]
  (.getTime ^java.util.Date event))

(deftest as-of-join-uses-only-what-was-known
  (reset! inputs {:prices prices :fundamentals fundamentals})
  (doseq [batch-size [1 2 3 10]]
    (testing (str "batch size " batch-size)
      (let [runs (repeatedly 3 #(run (job batch-size)))]
        (is (= expected (set (map summary (first runs)))))
        (is (= (count prices) (count (first runs))))
        (is (apply = runs) "same order on every run"))))
  (testing "a join on fiscal period end instead would have used the June restatement in April"
    (let [april-15 (.getTime #inst "2024-04-15")
          by-period-end (->> fundamentals
                             (filter #(and (= "AAA" (:tic %)) (<= (period-end-ms %) april-15)))
                             (sort-by (juxt period-end-ms pit/known-ms))
                             last)]
      (is (= ["2024Q1" 1.1] ((juxt :quarter :eps) by-period-end))))))

(deftest late-right-segment-throws
  (reset! inputs {:prices [{:tic "AAA" :close 1.0 :pit/known #inst "2024-04-15"}
                           {:tic "AAA" :close 2.0 :pit/known #inst "2024-05-10"}]
                  :fundamentals [(fundamentals 2) (fundamentals 0)]})
  (is (thrown-with-msg? ExceptionInfo #"arrived after the as-of join had emitted"
                        (run (job 1)))))

(defn update-task [job task-name f & args]
  (update job :catalog (partial mapv #(if (= task-name (:onyx/name %)) (apply f % args) %))))

(deftest check-job-rejects-joins-that-are-not-point-in-time
  (let [job (job 2)]
    (is (= job (pit/check-job job)))
    (is (thrown-with-msg? ExceptionInfo #"input :fundamentals has watermark :onyx.pit-test/period-end-ms"
                          (pit/check-job (update-task job :fundamentals assoc
                                                      :onyx/assign-watermark-fn ::period-end-ms))))
    (is (thrown-with-msg? ExceptionInfo #"input :prices has watermark nil"
                          (pit/check-job (update-task job :prices dissoc :onyx/assign-watermark-fn))))
    (is (thrown-with-msg? ExceptionInfo #"has no :right input"
                          (pit/check-job (update-task job :fundamentals assoc :pit/side :left))))
    (is (thrown-with-msg? ExceptionInfo #"needs :onyx/group-by-key"
                          (pit/check-job (update-task job :join dissoc :onyx/group-by-key))))
    (is (thrown-with-msg? ExceptionInfo #"must only have the trigger"
                          (pit/check-job (update-in job [:triggers 0] dissoc :trigger/refinement))))))

(deftest same-joins-as-onyx
  (reset! inputs {:prices prices :fundamentals fundamentals})
  (let [id (java.util.UUID/randomUUID)
        config (load-config)
        env-config (assoc (:env-config config) :onyx/tenancy-id id)
        peer-config (assoc (:peer-config config) :onyx/tenancy-id id)
        job (job 2)
        replayed (run job)]
    (reset! out-chan (chan 10000))
    (with-test-env [test-env [4 env-config peer-config]]
      (->> (onyx.api/submit-job peer-config job)
           :job-id
           (feedback-exception! peer-config))
      (is (= (set replayed) (set (take-segments! @out-chan 1000)))))))

(def gen-case
  (gen/let [tics (gen/elements [["A"] ["A" "B"] ["A" "B" "C"]])
            prices (gen/vector (gen/tuple (gen/elements tics) (gen/choose 0 40)) 0 25)
            fundamentals (gen/vector (gen/tuple (gen/elements tics) (gen/choose 0 40) (gen/choose 0 40)) 0 15)
            batch-size (gen/choose 1 6)]
    {:prices (vec (sort-by :pit/known (map-indexed (fn [i [tic known]]
                                                     {:tic tic :close (double i) :pit/known known})
                                                   prices)))
     :fundamentals (vec (sort-by :pit/known (map-indexed (fn [i [tic event known]]
                                                           {:tic tic :id i :pit/event event :pit/known known})
                                                         fundamentals)))
     :batch-size batch-size}))

(defn reference-join
  "Each price with the latest-period fundamentals filed by its time, by brute force."
  [{:keys [prices fundamentals]}]
  (for [{:keys [tic] :as price} prices]
    (assoc price :pit/as-of (->> fundamentals
                                 (filter #(and (= tic (:tic %)) (<= (:pit/known %) (:pit/known price))))
                                 (sort-by (juxt :pit/event :pit/known))
                                 last))))

(defspec as-of-join-matches-brute-force 100
  (prop/for-all [{:keys [batch-size] :as case} gen-case]
    (reset! inputs case)
    (let [joined (run (job batch-size))]
      (and (= (count (:prices case)) (count joined))
           (= (set (reference-join case)) (set joined))))))
