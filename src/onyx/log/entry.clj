(ns onyx.log.entry
  (:require [clojure.set :refer [union difference]]
            [clojure.data :refer [diff]]
            [onyx.extensions :as extensions]))

(defn create-log-entry [kw args]
  {:fn kw :args args})

(defmethod extensions/apply-log-entry :prepare-join-cluster
  [kw args]
  (fn [replica message-id]
    (let [n (count (:peers replica))]
      (if (> n 0)
        (let [joining-peer (:joiner args)
              all-joined-peers (into #{} (keys (:pairs replica)))
              lone-peer #{(:lone-peer replica)}
              all-prepared-peers #(into {} (keys (:prepared replica)))
              all-prepared-deps (into #{} (vals (:prepared replica)))
              cluster (union all-joined-peers lone-peer)
              candidates (difference cluster all-prepared-deps)
              sorted-candidates (sort (filter identity candidates))]
          (if (seq sorted-candidates)
            (let [index (mod message-id (count sorted-candidates))
                  target (nth sorted-candidates index)]
              (update-in replica [:prepared] merge {joining-peer target}))
            replica))))))

(defmethod extensions/replica-diff :prepare-join-cluster
  [kw old new]
  (second (diff (:prepared old) (:prepared new))))

