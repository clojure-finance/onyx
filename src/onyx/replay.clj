(ns onyx.replay
  "Runs an Onyx job inside the calling JVM: no ZooKeeper, no Aeron, no
  peers and no JVM flags. Takes the same job map as onyx.api/submit-job,
  with the same plugins, task functions, lifecycles, windows and
  triggers.

  Each task runs on its own thread. A task's :onyx/fn is applied to
  batches on a pool of :onyx/n-peers (else :onyx/max-peers, else
  :parallelism) threads, and its results are emitted in input order. A
  task with several parents takes one batch from each parent in turn, in
  workflow order. So for deterministic inputs and functions, the output
  is the same on every run, order included.

  Windows and triggers run on Onyx's own window executors with an
  in-memory state store, one per task (group-by keys become groups in
  it). Watermarks travel with the batches instead of with barriers: an
  input task's watermark is the largest :onyx/assign-watermark-fn value
  (or WatermarkedInput watermark) read so far, any other task's is the
  smallest of its parents' latest, and a windowed task fires a
  :watermark state event after any batch that advanced it. So watermark
  triggers fire at the same point in the data on every run; in Onyx they
  fire on the next barrier, which depends on timing. Timer triggers are
  only evaluated when a batch arrives and at job completion.

  Not supported, and rejected rather than ignored: flow conditions, Java
  tasks, and lifecycle phases other than :lifecycle/before-task-start
  and :lifecycle/after-task-stop. There is no checkpointing: an
  exception anywhere fails the whole run."
  (:require [onyx.lifecycles.lifecycle-compile :as lc]
            [onyx.peer.grouping :as g]
            [onyx.peer.operation :as operation]
            [onyx.peer.task-compile :as tc]
            [onyx.peer.window-state :as ws]
            [onyx.plugin.protocols :as p]
            [onyx.state.protocol.db :as db]
            [onyx.state.serializers.utils :as serializers]
            [onyx.static.util :refer [kw->fn]]
            [onyx.static.validation :as validation]
            [onyx.types :as types]
            [onyx.windowing.window-compile :as wc])
  (:import [java.util ArrayDeque]
           [java.util.concurrent ArrayBlockingQueue BlockingQueue Callable
            ExecutionException ExecutorCompletionService ExecutorService
            Executors Future ThreadFactory]
           [java.util.concurrent.atomic AtomicLong]))

(def ^:private done ::done)

(def ^:private supported-lifecycle-calls
  #{:lifecycle/doc :lifecycle/before-task-start :lifecycle/after-task-stop})

(defn- unsupported! [what data]
  (throw (ex-info (str "onyx.replay does not support " what) data)))

(defn- check-supported! [{:keys [catalog lifecycles flow-conditions]}]
  (when (seq flow-conditions)
    (unsupported! "flow conditions" {}))
  (doseq [task-map catalog
          :when (= :java (:onyx/language task-map))]
    (unsupported! "Java tasks" {:task (:onyx/name task-map)}))
  (doseq [lifecycle lifecycles
          k (keys (lc/resolve-lifecycle-calls (:lifecycle/calls lifecycle)))
          :when (not (supported-lifecycle-calls k))]
    (unsupported! (str "the lifecycle call " k) {:lifecycle lifecycle})))

(defn- thread-factory [prefix]
  (let [n (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ r]
        (doto (Thread. ^Runnable r (str prefix "-" (.incrementAndGet n)))
          (.setDaemon true))))))

(defn- task-event
  "The task's event map as Onyx builds it, after :lifecycle/before-task-start."
  [{:keys [catalog workflow lifecycles windows triggers metadata]} task-map fn-params]
  (let [task (:onyx/name task-map)
        windows (filterv #(= task (:window/task %)) windows)
        window-ids (set (map :window/id windows))
        event {:onyx.core/task task
               :onyx.core/task-map task-map
               :onyx.core/catalog catalog
               :onyx.core/workflow workflow
               :onyx.core/lifecycles lifecycles
               :onyx.core/windows windows
               :onyx.core/triggers (filterv #(window-ids (:trigger/window-id %)) triggers)
               :onyx.core/metadata metadata
               :onyx.core/params (into (vec (get fn-params task))
                                       (map #(get task-map %))
                                       (:onyx/params task-map))}]
    ((or (lc/compile-lifecycle-functions event :lifecycle/before-task-start) identity)
     event)))

(defn- segments [x]
  (if (sequential? x) x [x]))

(defn- batch-fn
  "Returns a fn from a batch to the vector of segments the task emits for
  it, or nil when the task has no :onyx/fn."
  [{:keys [onyx.core/task-map onyx.core/params]}]
  (when (or (:onyx/fn task-map) (= :function (:onyx/type task-map)))
    (let [task (:onyx/name task-map)
          f (operation/resolve-task-fn task-map)
          f (if (seq params) (apply partial f params) f)]
      (if (:onyx/batch-fn? task-map)
        (fn [batch]
          (let [results (f batch)]
            (when-not (= (count results) (count batch))
              (throw (ex-info ":onyx/batch-fn? functions must return the same number of elements as its input argument."
                              {:task task})))
            (into [] (mapcat segments) results)))
        (fn [batch]
          (into []
                (mapcat (fn [segment]
                          (segments
                           (try
                             (f segment)
                             (catch Throwable e
                               (throw (ex-info "Segment threw exception"
                                               {:task task :segment segment}
                                               e)))))))
                batch))))))

(defn- plugin [{:keys [onyx.core/task-map] :as event}]
  (when-let [kw (:onyx/plugin task-map)]
    (let [plugin (p/start ((kw->fn kw) event) event)]
      (p/recover! plugin 0 nil)
      plugin)))

(defn- spin-until [f]
  (loop []
    (when-not (f)
      (Thread/sleep 1)
      (recur))))

(defn- poll-batch
  "Up to batch-size segments from the input plugin, nil once it is completed."
  [plugin event batch-size timeout-ms]
  (loop [batch (transient [])]
    (if (< (count batch) batch-size)
      (if-some [segment (p/poll! plugin event timeout-ms)]
        (recur (conj! batch segment))
        (let [batch (persistent! batch)]
          (when-not (and (empty? batch) (p/completed? plugin))
            batch)))
      (persistent! batch))))

(defn- input-source
  "Calls (emit batch watermark) for each batch read from the input plugin."
  [plugin {:keys [onyx.core/task-map] :as event}]
  (let [batch-size (:onyx/batch-size task-map)
        timeout-ms (or (:onyx/batch-timeout task-map) 50)
        watermark-fn (tc/resolve-watermark-fn task-map)
        watermarked? (satisfies? p/WatermarkedInput plugin)]
    (fn [emit]
      (loop [watermark 0]
        (when-some [batch (poll-batch plugin event batch-size timeout-ms)]
          (let [next-watermark (long (cond watermark-fn
                                           (reduce (fn [w segment] (max w (long (watermark-fn segment))))
                                                   watermark
                                                   batch)
                                           watermarked? (max watermark (long (p/watermark plugin)))
                                           :else watermark))]
            (if (or (seq batch) (> next-watermark watermark))
              (emit batch next-watermark)
              (Thread/sleep 1))
            (recur next-watermark)))))))

(defn- parents-source
  "Calls (emit batch watermark) for each batch from the parent queues,
  taking one batch from each parent in turn until all are done. The
  watermark is the smallest of the parents' latest; a finished parent
  keeps its last one, as in Onyx. Every task emits one batch per batch
  it takes (possibly empty), so the paths of a diamond stay in step and
  this cannot deadlock."
  [queues]
  (fn [emit]
    (let [watermarks (long-array (count queues))]
      (loop [live (vec (map-indexed vector queues))]
        (when (seq live)
          (recur (reduce (fn [live [i ^BlockingQueue q :as parent]]
                           (let [item (.take q)]
                             (if (identical? item done)
                               live
                               (let [[batch watermark] item]
                                 (aset watermarks (int i) (long watermark))
                                 (emit batch (reduce min (seq watermarks)))
                                 (conj live parent)))))
                         []
                         live)))))))

(defn- windowed-sink
  "Wraps sink with the task's windows. Returns {:sink f :complete! g}:
  f puts a batch's segments into the windows, fires a :watermark state
  event if the watermark advanced, and passes on the segments (none for
  :onyx/type :reduce) followed by everything the triggers emitted; g
  fires :job-completed and passes on what that emitted."
  [{:keys [onyx.core/task-map onyx.core/windows onyx.core/triggers] :as event} sink]
  (let [store (db/create-db {:onyx.peer/state-store-impl :memory}
                            (str (random-uuid))
                            (serializers/event->state-serializers event))
        indices (ws/state-indices event)
        executors (volatile! (mapv #(wc/build-window-executor % triggers store indices task-map)
                                   windows))
        grouping-fn (g/task-map->grouping-fn task-map)
        reduce? (= :reduce (:onyx/type task-map))
        last-watermark (volatile! 0)
        state-event (fn [event-type]
                      (assoc (types/new-state-event event-type event 0 0)
                             :grouped? (boolean grouping-fn)))
        fire! (fn [state-event triggered]
                (vswap! executors ws/fire-state-event state-event triggered))]
    {:store store
     :sink (fn [batch watermark]
             (let [triggered (transient [])]
               (if (seq batch)
                 (let [new-segment (state-event :new-segment)]
                   (run! (fn [segment]
                           (fire! (if grouping-fn
                                    (let [group-key (grouping-fn segment)]
                                      (assoc new-segment
                                             :segment segment
                                             :group-key group-key
                                             :group-id (db/group-id store group-key)))
                                    (assoc new-segment :segment segment))
                                  triggered))
                         batch))
                 (fire! (state-event :task-iteration) triggered))
               (when (> watermark @last-watermark)
                 (vreset! last-watermark watermark)
                 (fire! (assoc (state-event :watermark) :watermarks {:input watermark})
                        triggered))
               (sink (into (if reduce? [] batch) (persistent! triggered)) watermark)))
     :complete! (fn []
                  (when (seq triggers)
                    (let [triggered (transient [])]
                      (fire! (state-event :job-completed) triggered)
                      (sink (persistent! triggered) @last-watermark))))}))

(defn- run-task!
  "Feeds every batch from source through f (on pool, if any) to sink, in
  order, with at most max-in-flight batches being applied at once."
  [source f ^ExecutorService pool max-in-flight sink]
  (let [in-flight (ArrayDeque.)
        emit-oldest! #(let [[result watermark] (.poll in-flight)]
                        (sink (.get ^Future result) watermark))]
    (source (fn [batch watermark]
              (if pool
                (do (.add in-flight [(.submit pool ^Callable (fn [] (f batch))) watermark])
                    (when (>= (.size in-flight) max-in-flight)
                      (emit-oldest!)))
                (sink (if f (f batch) batch) watermark))))
    (while (not (.isEmpty in-flight))
      (emit-oldest!))))

(defn- task-callable
  [job task-map in-queues out-queues {:keys [parallelism fn-params]} pools ^AtomicLong emitted]
  (fn []
    (let [event (task-event job task-map fn-params)
          plugin (plugin event)
          f (batch-fn event)
          n (or (:onyx/n-peers task-map) (:onyx/max-peers task-map) parallelism)
          pool (when f
                 (let [pool (Executors/newFixedThreadPool
                             n (thread-factory (str "onyx-replay-" (name (:onyx/name task-map)))))]
                   (swap! pools conj pool)
                   pool))
          source (if (= :input (:onyx/type task-map))
                   (input-source plugin event)
                   (parents-source in-queues))
          sink (if (= :output (:onyx/type task-map))
                 (fn [batch _]
                   (.addAndGet emitted (count batch))
                   (let [event (assoc event :onyx.core/write-batch batch)]
                     (spin-until #(p/prepare-batch plugin event nil nil))
                     (spin-until #(p/write-batch plugin event nil nil))))
                 (fn [batch watermark]
                   (.addAndGet emitted (count batch))
                   (doseq [^BlockingQueue q out-queues]
                     (.put q [batch watermark]))))
          windowed (when (seq (:onyx.core/windows event))
                     (windowed-sink event sink))]
      (try
        (run-task! source f pool (* 2 n) (if windowed (:sink windowed) sink))
        (when windowed
          ((:complete! windowed)))
        (when (= :output (:onyx/type task-map))
          (spin-until #(p/completed? plugin)))
        (doseq [^BlockingQueue q out-queues]
          (.put q done))
        (finally
          (when pool
            (.shutdown ^ExecutorService pool))
          (when windowed
            (db/drop! (:store windowed))
            (db/close! (:store windowed)))
          (when plugin
            (p/stop plugin event))
          ((or (lc/compile-lifecycle-functions event :lifecycle/after-task-stop) identity)
           event))))))

(defn- execution-cause [^Throwable t]
  (if (and (instance? ExecutionException t) (.getCause t))
    (recur (.getCause t))
    t))

(defn run-job
  "Runs job, an onyx.api/submit-job job map, to completion in this JVM and
  returns {:segments {task-name number-of-segments-it-emitted}}. Blocks
  until every input is completed and every output has written everything;
  a job with a streaming input never returns. Throws the first exception
  any task throws, after stopping the others.

  Options:
    :parallelism  threads per task without :onyx/n-peers or :onyx/max-peers
                  (default: available processors)
    :queue-size   batches buffered per workflow edge (default 8)
    :fn-params    as :onyx.peer/fn-params in a peer config"
  ([job] (run-job job {}))
  ([{:keys [catalog workflow] :as job} opts]
   (validation/validate-job job)
   (check-supported! job)
   (let [opts (merge {:parallelism (.availableProcessors (Runtime/getRuntime))
                      :queue-size 8}
                     opts)
         task-maps (into {} (map (juxt :onyx/name identity)) catalog)
         tasks (distinct (apply concat workflow))
         queues (into {}
                      (map (fn [edge] [edge (ArrayBlockingQueue. (int (:queue-size opts)))]))
                      workflow)
         emitted (into {} (map (fn [task] [task (AtomicLong.)])) tasks)
         pools (atom [])
         drivers (Executors/newCachedThreadPool (thread-factory "onyx-replay-task"))
         completion (ExecutorCompletionService. drivers)
         futures (mapv (fn [task]
                         (.submit completion
                                  ^Callable (task-callable
                                             job
                                             (task-maps task)
                                             (keep (fn [[_ to :as edge]] (when (= to task) (queues edge))) workflow)
                                             (keep (fn [[from :as edge]] (when (= from task) (queues edge))) workflow)
                                             opts
                                             pools
                                             (emitted task))))
                       tasks)]
     (try
       (dotimes [_ (count futures)]
         (.get (.take completion)))
       {:segments (into {} (map (fn [[task ^AtomicLong n]] [task (.get n)])) emitted)}
       (catch ExecutionException e
         (run! #(.cancel ^Future % true) futures)
         (throw (execution-cause e)))
       (finally
         (.shutdownNow drivers)
         (run! #(.shutdownNow ^ExecutorService %) @pools))))))
