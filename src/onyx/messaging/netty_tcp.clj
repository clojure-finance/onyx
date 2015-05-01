(ns ^:no-doc onyx.messaging.netty-tcp
    (:require [clojure.core.async :refer [chan >!! >! <!! alts!! timeout close! thread go-loop dropping-buffer]]
              [com.stuartsierra.component :as component]
              [taoensso.timbre :as timbre]
              [onyx.messaging.protocol-netty :as protocol]
              [onyx.messaging.acking-daemon :as acker]
              [onyx.messaging.common :refer [bind-addr external-addr allowable-ports]]
              [onyx.compression.nippy :refer [compress decompress]]
              [onyx.extensions :as extensions]
              [onyx.static.default-vals :refer [defaults]])
    (:import [java.net InetSocketAddress]
             [java.util.concurrent TimeUnit Executors]
             [java.nio]
             [io.netty.buffer ByteBuf]
             [io.netty.util.internal SystemPropertyUtil]
             [io.netty.util.concurrent Future EventExecutorGroup DefaultThreadFactory 
              DefaultEventExecutorGroup ImmediateEventExecutor]
             [io.netty.channel Channel ChannelOption ChannelFuture ChannelInitializer 
              ChannelHandler ChannelHandlerContext ChannelInboundHandlerAdapter]
             [io.netty.channel.epoll Epoll EpollEventLoopGroup EpollServerSocketChannel EpollSocketChannel]
             [io.netty.channel.socket SocketChannel]
             [io.netty.channel.socket.nio NioServerSocketChannel NioSocketChannel]
             [io.netty.channel.nio NioEventLoopGroup]
             [io.netty.channel.group ChannelGroup DefaultChannelGroup]
             [io.netty.handler.codec LengthFieldBasedFrameDecoder LengthFieldPrepender]
             [io.netty.util ResourceLeakDetector ResourceLeakDetector$Level]
             [io.netty.bootstrap Bootstrap ServerBootstrap]))

(def ^String client-event-thread-pool-name "onyx-netty-client-event-pool")
(def ^String worker-event-thread-pool-name "onyx-netty-worker-event-pool")
(def ^String boss-event-thread-pool-name "onyx-netty-boss-event-pool")

(defn leak-detector-level! [level]
  (ResourceLeakDetector/setLevel
   (case level
     :disabled ResourceLeakDetector$Level/DISABLED
     :simple ResourceLeakDetector$Level/SIMPLE
     :advanced ResourceLeakDetector$Level/ADVANCED
     :paranoid ResourceLeakDetector$Level/PARANOID)))

(defn event-executor
  "Creates a new netty execution handler for processing events. 
  Defaults to 1 thread per core."
  []
  (DefaultEventExecutorGroup. (.. Runtime getRuntime availableProcessors)))

(defn epoll? []
  (Epoll/isAvailable))

(defonce ^DefaultEventExecutorGroup shared-event-executor (event-executor))

(defrecord NettyPeerGroup [opts]
  component/Lifecycle
  (start [component]
    (let [thread-count (or (:onyx.messaging.netty/thread-pool-sizes opts)
                           (:onyx.messaging.netty/thread-pool-sizes defaults))

          client-thread-factory (DefaultThreadFactory. client-event-thread-pool-name true)
          client-group (if (epoll?)
                         (EpollEventLoopGroup. thread-count client-thread-factory)
                         (NioEventLoopGroup. thread-count client-thread-factory))
          boss-thread-factory (DefaultThreadFactory. boss-event-thread-pool-name true)
          boss-group (if (epoll?)
                       (EpollEventLoopGroup. thread-count boss-thread-factory)
                       (NioEventLoopGroup. thread-count boss-thread-factory))
          worker-thread-factory (DefaultThreadFactory. worker-event-thread-pool-name true)
          worker-group (if (epoll?)
                         (EpollEventLoopGroup. thread-count worker-thread-factory)
                         (NioEventLoopGroup. thread-count worker-thread-factory))]

      (timbre/info "Starting Netty Peer Group")
      (assoc component
        :shared-event-executor shared-event-executor
        :client-group client-group
        :worker-group worker-group
        :boss-group boss-group)))

  (stop [{:keys [client-group worker-group boss-group] :as component}]
    (timbre/info "Stopping Netty Peer Group")
    (.shutdownGracefully client-group)
    (.shutdownGracefully boss-group)
    (.shutdownGracefully worker-group)
    (assoc component 
      :shared-event-executor nil :client-group nil
      :worker-group nil :boss-group nil)))

(defn netty-peer-group [opts]
  (map->NettyPeerGroup {:opts opts}))

(defmethod extensions/assign-site-resources :netty
  [config peer-site peer-sites]
  (let [used-ports (->> (vals peer-sites) 
                        (filter 
                         (fn [s]
                           (= (:netty/external-addr peer-site) 
                              (:netty/external-addr s))))
                        (map :netty/port)
                        set)
        port (first (remove used-ports
                            (:netty/ports peer-site)))]
    (assert port "Couldn't assign port - ran out of available ports.")
    {:netty/port port}))

(defn int32-frame-decoder
  []
  ; Offset 0, 4 byte header, skip those 4 bytes.
  (LengthFieldBasedFrameDecoder. Integer/MAX_VALUE, 0, 4, 0, 4))

(defn int32-frame-encoder
  []
  (LengthFieldPrepender. 4))

(defn gen-tcp-handler
  [^ChannelGroup channel-group handler]
  (proxy [ChannelInboundHandlerAdapter] []
    (channelActive [ctx]
      (.add channel-group (.channel ctx)))
    (channelRead [^ChannelHandlerContext ctx ^Object message]
      (try
        (handler ctx message)
        (catch java.nio.channels.ClosedChannelException e
          (timbre/warn "Channel closed"))))
    (exceptionCaught [^ChannelHandlerContext ctx ^Throwable cause]
      (timbre/error cause "TCP handler caught")
      (.close (.channel ctx)))
    (isSharable [] true)))

(defn channel-initializer-done [handler]
  (proxy [ChannelInitializer] []
    (initChannel [ch]
      (let [pipeline (.pipeline ^Channel ch)]
        (doto pipeline 
          (.addLast "int32-frame-decoder" (int32-frame-decoder))
          (.addLast "int32-frame-encoder" (int32-frame-encoder))
          (.addLast shared-event-executor "handler" handler))))))

(defn create-server-handler
  "Given a core, a channel, and a message, applies the message to 
  core and writes a response back on this channel."
  [messenger inbound-ch release-ch retry-ch] 
  (fn [^ChannelHandlerContext ctx ^ByteBuf buf]
    (try 
      (let [msg (protocol/read-buf (:decompress-f messenger) buf)]
        (let [t ^byte (:type msg)]
          (cond (= t protocol/messages-type-id) 
                (doseq [message (:messages msg)]
                  (>!! inbound-ch message))

                (= t protocol/ack-type-id)
                (acker/ack-message (:acking-daemon messenger)
                                   (:id msg)
                                   (:completion-id msg)
                                   (:ack-val msg))

                (= t protocol/completion-type-id)
                (>!! release-ch (:id msg))

                (= t protocol/retry-type-id)
                (>!! retry-ch (:id msg))

                :else
                (throw (ex-info "Unexpected message received from Netty" {:message msg})))))
      (catch Throwable e
        (taoensso.timbre/error e)
        (throw e)))))

(defn start-netty-server
  [boss-group worker-group host port messenger inbound-ch release-ch retry-ch]
  (let [bootstrap (ServerBootstrap.)
        channel (if (epoll?)
                  EpollServerSocketChannel
                  NioServerSocketChannel)
        channel-group (DefaultChannelGroup. (str "tcp-server " host ":" port)
                        (ImmediateEventExecutor/INSTANCE))
        initializer (channel-initializer-done 
                     (gen-tcp-handler channel-group 
                                      (create-server-handler messenger 
                                                             inbound-ch 
                                                             release-ch 
                                                             retry-ch)))] 
    (doto bootstrap
      (.group boss-group worker-group)
      (.channel channel)
      (.option ChannelOption/SO_REUSEADDR true)
      (.option ChannelOption/TCP_NODELAY true)
      (.childOption ChannelOption/SO_REUSEADDR true)
      (.childOption ChannelOption/TCP_NODELAY true)
      (.childOption ChannelOption/SO_KEEPALIVE true)
      (.childHandler initializer))
    (let [ch (->> (InetSocketAddress. host port)
                  (.bind bootstrap)
                  (.sync)
                  (.channel))
          _ (.add channel-group ch)
          assigned-port (.. ch localAddress getPort)]
      (timbre/info "Netty server" host assigned-port "online")
      (fn killer []
        (.. channel-group close awaitUninterruptibly)
        (timbre/info "TCP server" host port "shut down")))))

(defn new-client-handler []
  (proxy [ChannelHandler] []
    (handlerAdded [ctx])
    (handlerRemoved [ctx])
    (exceptionCaught [context cause]
      (timbre/error cause "TCP client exception.")
      (.close context))))

(defn client-channel-initializer [handler]
  (proxy [ChannelInitializer] []
    (initChannel [ch]
      (timbre/info "Initializing client channel")
      (try (let [pipeline (.pipeline ^Channel ch)]
             (doto pipeline 
               (.addLast "int32-frame-decoder" (int32-frame-decoder))
               (.addLast "int32-frame-encoder" (int32-frame-encoder))
               (.addLast "handler" handler)))
           (catch Throwable e
             (timbre/fatal e))))))

(defn create-client [client-group host port]
  (let [channel (if (epoll?)
                  EpollSocketChannel
                  NioSocketChannel)]
    (let [b (doto (Bootstrap.)
              (.option ChannelOption/SO_REUSEADDR true)
              (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
              (.group client-group)
              (.channel channel)
              (.handler (client-channel-initializer (new-client-handler))))]
      (.channel ^ChannelFuture (.awaitUninterruptibly (.connect b host port))))))

(defrecord NettyTcpSockets [peer-group]
  component/Lifecycle

  (start [component]
    (taoensso.timbre/info "Starting Netty TCP Sockets")
    (let [{:keys [client-group worker-group boss-group]} (:messaging-group peer-group)
          config (:config peer-group)
          release-ch (chan (dropping-buffer (:onyx.messaging/release-ch-buffer-size defaults)))
          retry-ch (chan (dropping-buffer (:onyx.messaging/retry-ch-buffer-size defaults)))
          bind-addr (bind-addr config)
          external-addr (external-addr config)
          ports (allowable-ports config)]
      (assoc component
             :bind-addr bind-addr 
             :external-addr external-addr
             :boss-group boss-group
             :client-group client-group
             :worker-group worker-group
             :ports ports
             :resources (atom nil)
             :release-ch release-ch
             :retry-ch retry-ch
             :decompress-f (or (:onyx.messaging/decompress-fn (:config peer-group)) decompress)
             :compress-f (or (:onyx.messaging/compress-fn (:config peer-group)) compress))))

  (stop [{:keys [resources release-ch] :as component}]
    (taoensso.timbre/info "Stopping Netty TCP Sockets")
    (try 
      (when-let [rs @resources]
        (let [{:keys [shutdown-fn]} rs] 
          (shutdown-fn))
        (reset! resources nil)) 
      (catch Throwable e (timbre/fatal e)))
    (assoc component :bind-addr nil :external-addr nil 
           :worker-group nil :client-group nil :boss-group nil 
           :resources nil :release-ch nil :retry-ch nil)))

(defn netty-tcp-sockets [peer-group]
  (map->NettyTcpSockets {:peer-group peer-group}))

(defmethod extensions/peer-site NettyTcpSockets
  [messenger]
  {:netty/ports (:ports messenger)
   :netty/external-addr (:external-addr messenger)})

(defmethod extensions/open-peer-site NettyTcpSockets
  [messenger assigned]
  (let [inbound-ch (:inbound-ch (:messenger-buffer messenger))
        release-ch (:release-ch messenger)
        retry-ch (:retry-ch messenger)
        shutdown-fn (start-netty-server (:boss-group messenger)
                                        (:worker-group messenger)
                                        (:bind-addr messenger) 
                                        (:netty/port assigned) 
                                        messenger 
                                        inbound-ch 
                                        release-ch 
                                        retry-ch)]
    (reset! (:resources messenger)
            {:shutdown-fn shutdown-fn})))

(defmethod extensions/connect-to-peer NettyTcpSockets
  [messenger event {:keys [netty/external-addr netty/port]}]
  (create-client (:client-group messenger) external-addr port))

(defmethod extensions/receive-messages NettyTcpSockets
  [messenger {:keys [onyx.core/task-map] :as event}]
  (let [ms (or (:onyx/batch-timeout task-map) (:onyx/batch-timeout defaults))
        ch (:inbound-ch (:onyx.core/messenger-buffer event))
        timeout-ch (timeout ms)]
    (loop [segments [] i 0]
      (if (< i (:onyx/batch-size task-map))
        (if-let [v (first (alts!! [ch timeout-ch]))]
          (recur (conj segments v) (inc i))
          segments)
        segments))))

(defmethod extensions/send-messages NettyTcpSockets
  [messenger event ^Channel peer-link messages]
  ;(taoensso.timbre/info "SENDING messages " messages)
  (.writeAndFlush peer-link 
                  ^ByteBuf (protocol/build-messages-msg-buf (:compress-f messenger) messages) 
                  (.voidPromise ^Channel peer-link)))

(defmethod extensions/internal-ack-message NettyTcpSockets
  [messenger event ^Channel peer-link message-id completion-id ack-val]
  ;(taoensso.timbre/info "SENDING ACK: " message-id ack-val)
  (.writeAndFlush peer-link 
                  ^ByteBuf (protocol/build-ack-msg-buf message-id completion-id ack-val)
                  (.voidPromise ^Channel peer-link)))

(defmethod extensions/internal-complete-message NettyTcpSockets
  [messenger event id ^Channel peer-link]
  (.writeAndFlush peer-link 
                  ^ByteBuf (protocol/build-completion-msg-buf id)
                  (.voidPromise ^Channel peer-link)))

(defmethod extensions/internal-retry-message NettyTcpSockets
  [messenger event id ^Channel peer-link]
  (.writeAndFlush peer-link 
                  ^ByteBuf (protocol/build-retry-msg-buf id)
                  (.voidPromise ^Channel peer-link)))

(defmethod extensions/close-peer-connection NettyTcpSockets
  [messenger event ^Channel peer-link]
  (-> peer-link .close .sync))