(ns reelthyme.transport.websocket
  "A Good Enough ™ websocket implementation. Provides a server side websocket channel supporting puts, takes, and automatic reconnect.
  The intended use case for this transport type is server-to-server applications."
  (:require [clojure.core.async :as a :refer [>! <! go-loop]]
            [clojure.core.async.impl.protocols :as proto :refer [ReadPort WritePort Channel]])
  (:import (java.net.http HttpClient WebSocket WebSocket$Builder  WebSocket$Listener)
           (java.net URI)
           (java.nio ByteBuffer)
           (java.util.concurrent CompletableFuture)))

(defn- websocket!*
  "A websocket backed by java.net.http.HttpClient. A hopefully straightforward way
  of putting a java.net.http.WebSocket behind a core.async channel"
  [uri {:keys [buffer log ws-atom builder-fn]}]
  (let [in  (a/chan buffer)
        out (a/chan buffer)
        text-buf (StringBuilder.)
        bin-buf  (java.io.ByteArrayOutputStream.)
        ^HttpClient client (HttpClient/newHttpClient)
        listener
        (reify WebSocket$Listener
          (onOpen [_ ws]
            (log "open")
            (.request ws buffer)
            (go-loop []
              (when-let [m (<! in)]
                (cond
                  (:text m)    (.sendText ws (:text m) (get m :last? true))
                  (:ping? m)   (.sendPing ws (ByteBuffer/allocate 0))
                  (:binary m)  (.sendBinary ws (:binary m) (get m :last? true))
                  (:close? m)  (.sendClose ws
                                           (or (:code m) 1000)
                                           (or (:reason m) "bye"))
                  :else        (.sendClose ws 1008 "invalid input"))
                (recur))))
          (onText [_ ws chnk last?]
            (.append text-buf chnk)
            (when last?
              (let [msg (.toString text-buf)]
                (.setLength text-buf 0)
                (a/put! out {:text msg}))
              (log "text recv"))
            (.request ws 1)
            (CompletableFuture/completedFuture nil))
          (onBinary [_ ws byteBuffer last?]
            (.put bin-buf byteBuffer)
            (when last?
              (.flip bin-buf)
              (let [bytes (byte-array (.remaining bin-buf))]
                (.get bin-buf bytes)
                (a/put! out {:binary bytes})
                (.clear bin-buf)))
            (.request ws 1)
            (CompletableFuture/completedFuture nil))
          (onPong [_ ws _]
            (log "pong")
            (a/put! out {:pong? true})
            (.request ws 1)
            (CompletableFuture/completedFuture nil))
          (onPing [_ ws payload]
            (log "ping")
            (.sendPong ws payload)
            (.request ws 1)
            (CompletableFuture/completedFuture nil))
          (onClose [_ _ _ _]
            (log "close")
            (a/close! in)
            (a/close! out)
            (CompletableFuture/completedFuture nil)))]
    (let [cf (-> client
                 (.newWebSocketBuilder)
                 (builder-fn)
                 (.buildAsync (URI/create uri) listener))
          ws (.join cf)]
      (reset! ws-atom ws))
    {:in in :out out}))

(defn websocket!
  "Returns a Channel `ch` you can put to and take from without worrying
   about reconnects.

   Options:

   :buffer             – size of the internal buffers (default 10)
   :log                – optional fn called on lifecycle events (default (constantly nil)) - expects signature of (fn [& xs])
   :heartbeat-interval – millis between pings (default 5000)
   :pong-timeout       – max millis to wait for a pong (default 10000)
   :reconnect-interval – millis to wait before retrying (default 1000)
   :builder-fn         - optional function that will receive the WebSocket$Builder instance - useful for further configuration - i.e headers - return value is ignored
   :xf-in              - optional transducer applied to the write-ch
   :xf-out             - optional transducer applied to the read-ch
   :ex-handler         - optional ex-handler for the read-ch only- follows the same rules as clojure.core.async/chan"
  [uri {:keys [buffer log heartbeat-interval pong-timeout reconnect-interval builder-fn xf-in xf-out ex-handler]
        :or   {buffer             10
               log                (constantly nil)
               heartbeat-interval 5000
               pong-timeout       10000
               reconnect-interval 1000
               builder-fn         (constantly nil)}}]
  (let [write-ch    (a/chan buffer xf-in)
        read-ch     (a/chan buffer xf-out ex-handler)
        closed?     (atom false)
        current-io  (atom nil)
        *ws-atom    (atom nil)
        builder-fn' (fn [^WebSocket$Builder builder]
                      (builder-fn builder)
                      builder)

        ;; centralized close logic
        close-all!
        (fn []
          (when-not @closed?
            (reset! closed? true)
            (log "✖ websocket! — shutting down")
            (when-let [^WebSocket ws @*ws-atom]
              (.sendClose ws 1000 "bye"))
            (when-let [io @current-io]
              (a/close! (:in io))
              (a/close! (:out io)))
            (a/close! write-ch)
            (a/close! read-ch)))

        ;; the channel we hand back
        user-ch
        (reify
          ReadPort
          (take! [_ cb]   (proto/take! read-ch cb))
          WritePort
          (put!   [_ v cb] (proto/put! write-ch v cb))
          Channel
          (close! [_]     (close-all!))
          (closed? [_]    @closed?))]

    ;; ROUTE your writes into whichever io-chan is live
    (go-loop []
      (when-let [msg (<! write-ch)]
        (if (:close? msg)
          (close-all!)
          (when-let [io @current-io]
            (>! (:in io) msg)))
        (when-not @closed? (recur))))

    ;; RECONNECT SUPERVISOR
    (go-loop []
      (when-not @closed?
        (log "→ websocket! — connecting to" uri)
        (let [{:keys [in out] :as io} (websocket!* uri {:buffer buffer :log log :ws-atom *ws-atom :builder-fn builder-fn'})
              _                       (reset! current-io io)
              last-activity           (atom (System/currentTimeMillis))
              close-signal            (a/chan)]

          ;; 1) read-loop: forward everything to read-ch, tracks ALL activity (in spite of the :pong-timeout last-activity is checked against)
          (go-loop []
            (if-let [msg (<! out)]
              (do
                (reset! last-activity (System/currentTimeMillis))
                (>! read-ch msg)
                (recur))
              ;; underlying io closed
              (do
                (log "⚠ websocket!* — connection closed")
                (>! close-signal true))))

          ;; 2) heartbeat: ping every `heartbeat-interval`
          (go-loop []
            (<! (a/timeout heartbeat-interval))
            (when-not @closed?
              (>! in {:ping? true})
              (recur)))

          ;; 3) pong-watchdog: if no pong arrives in `pong-timeout`, drop conn
          (go-loop []
            (<! (a/timeout pong-timeout))
            (when (and (>= (- (System/currentTimeMillis)
                              @last-activity)
                           pong-timeout)
                       (not @closed?))
              (log "⚠ websocket! — no activity, closing connection")
              (a/close! (:in io))
              (a/close! (:out io)))
            (when-not @closed?
              (recur)))

          ;; wait for that read-loop to signal a closure…
          (<! close-signal)
          (when-not @closed?
            (log "… will reconnect in" reconnect-interval "ms")
            (<! (a/timeout reconnect-interval))
            (recur)))))

    user-ch))

(comment
  (def conn (websocket! "wss://echo.websocket.events" {:log (fn [& xs] (apply println xs))}))
  (a/go-loop []
    (when-some [msg (a/<! conn)]
      (println msg)
      (recur)))
  (a/put! conn {:text "Hello World"})
  (a/put! conn {:ping? true})
  (a/put! conn {:close? true})
  (a/close! conn))

