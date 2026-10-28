(ns onyx.pit
  "Point-in-time as-of joins, built from Onyx's own windows, triggers and
  refinements, so they run unchanged in onyx.replay and on a cluster.

  Segments of a point-in-time input carry :pit/known, the time (epoch ms
  or java.util.Date) their information became available: a filing's
  acceptance time, a data vintage, a quote's timestamp. Right-hand
  segments may also carry :pit/event, the time the information is about
  (a fiscal period end); it defaults to :pit/known.

  An as-of join emits each left segment with :pit/as-of set to the right
  segment with the same join key that has the latest :pit/event among
  those with :pit/known no later than the left segment's :pit/known (ties
  go to the later :pit/known, so a restatement replaces the original from
  the moment it is known, and then to the later segment), or nil when none
  was known yet. It emits a left segment only once the join task's
  watermark has passed its :pit/known, that is, once every right segment
  that could match has arrived. So no result uses information that was
  not known at its time.

  Each input must be ordered by :pit/known. A right segment that arrives
  after the join has emitted past its :pit/known would have changed
  results already emitted, so the join throws instead.

  Build the inputs with `input`, the join with `as-of-join`, and check a
  job with `check-job` before running it."
  (:import [java.util Date]))

(defn- ms [t]
  (if (instance? Date t) (.getTime ^Date t) (long t)))

(defn known-ms
  "A segment's :pit/known in epoch ms. Use as :onyx/assign-watermark-fn."
  [segment]
  (if-some [t (:pit/known segment)]
    (ms t)
    (throw (ex-info "Point-in-time segment has no :pit/known" {:segment segment}))))

(defn- event-ms [segment]
  (ms (or (:pit/event segment) (:pit/known segment))))

(defn tag
  "The :onyx/fn of inputs built with `input`."
  [side segment]
  (assoc segment :pit/side side))

(defn input
  "Makes task-map, an input catalog entry, the side (:left or :right) of
  an as-of join: its watermark is :pit/known and it tags segments with
  :pit/side."
  [task-map side]
  (when (:onyx/fn task-map)
    (throw (ex-info "A point-in-time input cannot have its own :onyx/fn" {:task-map task-map})))
  (assoc task-map
         :onyx/assign-watermark-fn ::known-ms
         :onyx/fn ::tag
         :pit/side side
         :onyx/params [:pit/side]))

(defn- match
  "The right segment a left segment known at t joins with, or nil."
  [rights t]
  (reduce (fn [best right]
            (if (and (<= (known-ms right) t)
                     (or (nil? best)
                         (not (neg? (compare [(event-ms right) (known-ms right)]
                                             [(event-ms best) (known-ms best)])))))
              right
              best))
          nil
          rights))

(defn- emitted-through
  "Left segments known before this time can be emitted."
  [{:keys [event-type watermarks]}]
  (if (= :job-completed event-type)
    Long/MAX_VALUE
    (:input watermarks)))

(def ^:export as-of-state
  "Aggregation: per join key, the right segments so far, the left
  segments not yet emitted, and the time emission has reached."
  {:aggregation/init (fn [_] {:right [] :pending [] :through Long/MIN_VALUE})
   :aggregation/create-state-update (fn [_ segment] segment)
   :aggregation/apply-state-update
   (fn [_ {:keys [through] :as state} segment]
     (case (:pit/side segment)
       :left (update state :pending conj segment)
       :right (if (< (known-ms segment) through)
                (throw (ex-info "Right segment arrived after the as-of join had emitted results it would have changed; inputs must be ordered by :pit/known"
                                {:segment segment :emitted-through through}))
                (update state :right conj segment))
       (throw (ex-info "Segment has no :pit/side; build the join's inputs with onyx.pit/input"
                       {:segment segment}))))})

(def ^:export watermark-passed
  "Trigger: fires on every watermark and at job completion."
  {:trigger/trigger-fire? (fn [_ _ {:keys [event-type]}]
                            (contains? #{:watermark :job-completed} event-type))})

(def ^:export drop-emitted
  "Refinement: drops the left segments the trigger emitted."
  {:refinement/create-state-update (fn [_ _ state-event] (emitted-through state-event))
   :refinement/apply-state-update
   (fn [_ state through]
     (-> state
         (update :pending (fn [pending] (into [] (remove #(< (known-ms %) through)) pending)))
         (update :through max through)))})

(defn emit-ready
  "Trigger emit: the pending left segments the watermark has passed,
  each with its :pit/as-of match."
  [_ _ _ state-event {:keys [right pending]}]
  (let [through (emitted-through state-event)]
    (into []
          (comp (filter #(< (known-ms %) through))
                (map (fn [left]
                       (assoc (dissoc left :pit/side)
                              :pit/as-of (some-> (match right (known-ms left))
                                                 (dissoc :pit/side))))))
          pending)))

(defn as-of-join
  "The catalog entry, window and trigger of an as-of join task named
  task-name that joins its :left and :right parents on key."
  [task-name key batch-size]
  (let [window-id (keyword (str (name task-name) "-as-of"))]
    {:catalog-entry {:onyx/name task-name
                     :onyx/type :reduce
                     :onyx/group-by-key key
                     :onyx/flux-policy :recover
                     :onyx/n-peers 1
                     :onyx/batch-size batch-size}
     :window {:window/id window-id
              :window/task task-name
              :window/type :global
              :window/aggregation ::as-of-state}
     :trigger {:trigger/window-id window-id
               :trigger/id window-id
               :trigger/on ::watermark-passed
               :trigger/state-context [:window-state]
               :trigger/refinement ::drop-emitted
               :trigger/emit ::emit-ready}}))

(defn- ancestors-of [workflow task]
  (loop [found #{} frontier #{task}]
    (let [parents (into #{} (keep (fn [[from to]] (when (frontier to) from))) workflow)
          new (remove found parents)]
      (if (seq new)
        (recur (into found new) (set new))
        found))))

(defn check-job
  "Returns job if each as-of join in it has what it needs to be point in
  time, else throws: a group-by key, the trigger and refinement from
  as-of-join, and only upstream inputs built with `input`, at least one
  per side. An input with another watermark could let the join emit
  before every right segment known by then has arrived."
  [{:keys [workflow catalog windows triggers] :as job}]
  (let [task-maps (into {} (map (juxt :onyx/name identity)) catalog)]
    (doseq [{:keys [window/id window/task window/aggregation]} windows
            :when (= ::as-of-state aggregation)]
      (let [fail (fn [msg data] (throw (ex-info (str "As-of join " task ": " msg) (assoc data :task task))))
            window-triggers (filter #(= id (:trigger/window-id %)) triggers)
            inputs (filter #(= :input (:onyx/type %))
                           (map task-maps (ancestors-of workflow task)))]
        (when-not (:onyx/group-by-key (task-maps task))
          (fail "needs :onyx/group-by-key, the join key" {}))
        (when-not (and (seq window-triggers)
                       (every? #(and (= ::watermark-passed (:trigger/on %))
                                     (= ::drop-emitted (:trigger/refinement %)))
                               window-triggers))
          (fail "its window must only have the trigger from onyx.pit/as-of-join" {:triggers window-triggers}))
        (doseq [{:keys [onyx/name onyx/assign-watermark-fn pit/side]} inputs]
          (when-not (= ::known-ms assign-watermark-fn)
            (fail (str "input " name " has watermark " (pr-str assign-watermark-fn)
                       ", not :pit/known; build it with onyx.pit/input")
                  {:input name}))
          (when-not (#{:left :right} side)
            (fail (str "input " name " has no :pit/side") {:input name})))
        (doseq [side [:left :right]]
          (when-not (some #(= side (:pit/side %)) inputs)
            (fail (str "has no " side " input") {})))))
    job))
