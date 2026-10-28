(ns onyx.windowing.chained-job-completed-test
  "A windowed task downstream of another one must see everything the first
  emits when the job completes, and include it in what its own triggers emit
  then."
  (:require [clojure.core.async :refer [chan]]
            [clojure.test :refer [deftest is]]
            [onyx.api]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.plugin.seq]
            [onyx.replay :as replay]
            [onyx.test-helper :refer [load-config with-test-env feedback-exception!]]))

(def n-keys 50)

;; Three segments per key, far enough apart to land in three windows.
(def segments
  (vec (for [i (range (* 3 n-keys))] {:k (mod i n-keys) :t (* 1000 i)})))

(def out-chan (atom nil))

(def in-calls
  {:lifecycle/before-task-start (fn [_ _] {:seq/seq segments})})

(def out-calls
  {:lifecycle/before-task-start (fn [_ _] {:core.async/chan @out-chan})})

(defn emit-window [_ _ _ {:keys [lower-bound group-key]} n]
  {:k group-key :lower lower-bound :n n})

(defn emit-total [_ _ _ _ n]
  {:windows n})

(def job
  {:workflow [[:in :count] [:count :total] [:total :out]]
   :catalog [{:onyx/name :in
              :onyx/plugin :onyx.plugin.seq/input
              :onyx/type :input
              :onyx/medium :seq
              :onyx/batch-size 10
              :onyx/max-peers 1}
             {:onyx/name :count
              :onyx/type :reduce
              :onyx/group-by-key :k
              :onyx/flux-policy :recover
              :onyx/n-peers 1
              :onyx/batch-size 10}
             {:onyx/name :total
              :onyx/type :reduce
              :onyx/n-peers 1
              :onyx/batch-size 10}
             {:onyx/name :out
              :onyx/plugin :onyx.plugin.core-async/output
              :onyx/type :output
              :onyx/medium :core.async
              :onyx/batch-size 10
              :onyx/max-peers 1}]
   :lifecycles [{:lifecycle/task :in :lifecycle/calls ::in-calls}
                {:lifecycle/task :out :lifecycle/calls ::out-calls}]
   ;; No watermarks are assigned, so both tasks' windows fire only at job
   ;; completion; :total counts the windows :count emitted.
   :windows [{:window/id :w
              :window/task :count
              :window/type :fixed
              :window/aggregation :onyx.windowing.aggregation/count
              :window/window-key :t
              :window/range [20 :seconds]}
             {:window/id :all
              :window/task :total
              :window/type :global
              :window/aggregation :onyx.windowing.aggregation/count}]
   :triggers [{:trigger/window-id :w
               :trigger/id :emit
               :trigger/on :onyx.triggers/watermark
               :trigger/post-evictor [:all]
               :trigger/state-context [:window-state]
               :trigger/emit ::emit-window}
              {:trigger/window-id :all
               :trigger/id :emit-total
               :trigger/on :onyx.triggers/watermark
               :trigger/post-evictor [:all]
               :trigger/state-context [:window-state]
               :trigger/emit ::emit-total}]
   :task-scheduler :onyx.task-scheduler/balanced})

(deftest onyx-includes-upstream-job-completed-output
  (let [id (java.util.UUID/randomUUID)
        config (load-config)
        env-config (assoc (:env-config config) :onyx/tenancy-id id)
        peer-config (assoc (:peer-config config) :onyx/tenancy-id id)]
    (reset! out-chan (chan 100000))
    (with-test-env [_ [4 env-config peer-config]]
      (->> (onyx.api/submit-job peer-config job)
           :job-id
           (feedback-exception! peer-config))
      (is (= [{:windows (* 3 n-keys)}]
             (take-segments! @out-chan 1000))))))

(deftest replay-includes-upstream-job-completed-output
  (reset! out-chan (chan 100000))
  (replay/run-job job)
  (is (= [{:windows (* 3 n-keys)}]
         (take-segments! @out-chan 1000))))
