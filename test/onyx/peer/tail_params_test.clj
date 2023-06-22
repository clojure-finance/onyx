(ns onyx.peer.tail-params-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [clojure.test :refer [deftest is testing]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.test-helper :refer [load-config with-test-env add-test-env-peers!]]
            [onyx.static.uuid :refer [onyx-random-uuid]]
            [onyx.api]))

(defn handle-exception [event lifecycle lf-kw exception]
  :kill)

(def exception-calls
  {:lifecycle/handle-exception handle-exception})  

(def n-messages 1)

(def in-chan (atom nil))
(def in-buffer (atom nil))

(def out-chan (atom nil))

(defn inject-in-ch [event lifecycle]
  {:core.async/buffer in-buffer
   :core.async/chan @in-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan @out-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(defn my-adder [factor {:keys [n] :as segment}]
  (throw (ex-info "fail" {:x 50})))

(deftest tail-params
  (let [id (onyx-random-uuid)
        config (load-config)
        env-config (assoc (:env-config config) :onyx/tenancy-id id)
        peer-config (assoc (:peer-config config) :onyx/tenancy-id id)
        batch-size 20

        workflow [[:in :out]]

        catalog [{:onyx/name :in
                  :onyx/plugin :onyx.plugin.core-async/input
                  :onyx/type :input
                  :onyx/medium :core.async
                  :onyx/batch-size batch-size
                  :onyx/max-peers 1
                  :onyx/doc "Reads segments from a core.async channel"}

                 {:onyx/name :out
                  :onyx/plugin :onyx.plugin.core-async/output
                  :onyx/type :output
                  :onyx/medium :core.async
                  :onyx/fn :onyx.peer.tail-params-test/my-adder
                  :some/factor 42
                  :onyx/params [:some/factor]
                  :onyx/batch-size batch-size
                  :onyx/max-peers 1
                  :onyx/doc "Writes segments to a core.async channel"}]

        lifecycles [{:lifecycle/task :in
                     :lifecycle/calls :onyx.peer.tail-params-test/in-calls}
                    {:lifecycle/task :out
                     :lifecycle/calls :onyx.peer.tail-params-test/out-calls}
                    {:lifecycle/task :all
                     :lifecycle/calls ::exception-calls}]]

    (reset! in-chan (chan (inc n-messages)))
    (reset! in-buffer {})
    (reset! out-chan (chan (sliding-buffer (inc n-messages))))

    (with-test-env [test-env [3 env-config peer-config]]
      (doseq [n (range n-messages)]
        (>!! @in-chan {:n n}))
      (close! @in-chan)

      (let [{:keys [job-id]} (onyx.api/submit-job peer-config
                                                  {:catalog catalog
                                                   :workflow workflow
                                                   :lifecycles lifecycles
                                                   :task-scheduler :onyx.task-scheduler/balanced})]
        (try
          (onyx.test-helper/feedback-exception! peer-config job-id)
          (is false)
          (catch Exception e
            (is (= "fail" (.getMessage e)))
            (is (= {:x 50
                    :original-exception :clojure.lang.ExceptionInfo
                    :offending-task :out
                    :offending-segment {:n 0}}
                   (ex-data e)))))))))
