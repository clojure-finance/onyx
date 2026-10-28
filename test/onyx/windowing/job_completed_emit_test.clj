(ns onyx.windowing.job-completed-emit-test
  "Everything triggers emit when a job completes must reach the output. A
  task writes it after passing the sealing barrier on, so the coordinator
  waits for one more barrier before completing the job; without that,
  0.16.0 lost 26-74% of these windows in every run."
  (:require [clojure.core.async :refer [chan]]
            [clojure.test :refer [deftest is]]
            [onyx.api]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.plugin.seq]
            [onyx.test-helper :refer [load-config with-test-env feedback-exception!]]))

(def n-keys 5000)

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

(deftest every-window-emitted-at-job-completion-arrives
  (let [id (java.util.UUID/randomUUID)
        config (load-config)
        env-config (assoc (:env-config config) :onyx/tenancy-id id)
        peer-config (assoc (:peer-config config) :onyx/tenancy-id id)
        job {:workflow [[:in :count] [:count :out]]
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
                       {:onyx/name :out
                        :onyx/plugin :onyx.plugin.core-async/output
                        :onyx/type :output
                        :onyx/medium :core.async
                        :onyx/batch-size 10
                        :onyx/max-peers 1}]
             :lifecycles [{:lifecycle/task :in :lifecycle/calls ::in-calls}
                          {:lifecycle/task :out :lifecycle/calls ::out-calls}]
             ;; No watermarks are assigned, so every window fires at job completion.
             :windows [{:window/id :w
                        :window/task :count
                        :window/type :fixed
                        :window/aggregation :onyx.windowing.aggregation/count
                        :window/window-key :t
                        :window/range [20 :seconds]}]
             :triggers [{:trigger/window-id :w
                         :trigger/id :emit
                         :trigger/on :onyx.triggers/watermark
                         :trigger/post-evictor [:all]
                         :trigger/state-context [:window-state]
                         :trigger/emit ::emit-window}]
             :task-scheduler :onyx.task-scheduler/balanced}]
    (reset! out-chan (chan 100000))
    (with-test-env [_ [3 env-config peer-config]]
      (->> (onyx.api/submit-job peer-config job)
           :job-id
           (feedback-exception! peer-config))
      (let [out (take-segments! @out-chan 1000)]
        (is (= (* 3 n-keys) (count out)))
        (is (every? #(= 1 (:n %)) out))))))
