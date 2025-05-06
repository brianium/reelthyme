(ns reelthyme.core
  "A core.async backed interface for leveraging OpenAI's realtime API. Calling
  connect! will return a core.async channel suitable for the environment. Clojure on the JVM
  will use a websocket backed transport, while ClojureScript will use a WebRTC backed channel."
  (:require [clojure.core.async :as a]
            #?(:clj [cheshire.core :as json])
            #?(:clj [reelthyme.transport.websocket :as ws])
            #?(:clj [reelthyme.audio.java :as audio]))
  #?(:clj (:import (java.net.http WebSocket$Builder))))

;;; Transformations

#?(:clj
   (defn in->json
     [event]
     {:text (json/generate-string event)}))

#?(:cljs
   (defn in->json
     [event]
     {:text (.stringify js/JSON (clj->js event))}))

#?(:clj
   (defn out->json
     "Likewise, lets get the server sent events as a Clojure map"
     [{:keys [text]}]
     (json/parse-string text keyword)))

#?(:cljs
   (defn out->json
     [event]
     {:text (.parse js/JSON (.-data event))}))

(defn pong?
  "Check if the given event map represents a pong event"
  [m]
  (and (map? m) (contains? m :pong?)))

(defn event?
  "Check if the given map is an event from openai"
  [m]
  (contains? m :type))

#?(:clj
   (defn connect!
     "Open a websocket connection with OpenAI. This channel type is intended for use in server-to-server
     applications.

     Options:
     :api-key    - (optional) A valid api key for OpenAI. Defaults to reading the OPENAI_API_KEY environment variable
     :uri        - (optional) The websocket uri to connect to. Defaults to wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview-2024-12-17
     :xf-out     - (optional) A transducer that will be applied to all outputs. Note: this xf will be applied AFTER filtering and json serialization
     :ex-handler - (optional) An ex-handler for the output channel. Generally pairs with :xf - follows the same rules as clojure.core.async/chan
     :xf-in      - (optional) A transducer that will be applied to every input value BEFORE json serialization. Should return an event map or throw.
                              No ex-handler is supported for inputs.
     :log-fn     - (optional) A function of form (fn [& xs)). Can be useful for debugging"
     ([]
      (connect! {}))
     ([{:keys [xf-out ex-handler xf-in log-fn] :as params}]
      (let [xf-out* (comp (remove pong?) ;;; The default out channel transducer ensures a stream of ONLY openai events as Clojure maps
                          (map out->json)
                          (filter event?))
            xf-out (if xf-out
                     (comp xf-out* xf-out)
                     xf-out*)
            xf-in  (if xf-in
                     (comp xf-in (map in->json))
                     (map in->json))]
        (if-some [api-key (get params :api-key (System/getenv "OPENAI_API_KEY"))]
          (ws/websocket!
           (get params :uri "wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview-2024-12-17")
           (cond-> params
             (some? log-fn) (assoc :log log-fn)
             :always (merge {:builder-fn (fn [^WebSocket$Builder builder]
                                           (-> builder
                                               (.header "Authorization" (str "Bearer " api-key))
                                               (.header "OpenAI-Beta" "realtime=v1")))
                             :xf-in      xf-in
                             :xf-out     xf-out
                             :ex-handler ex-handler})))
          (throw (ex-info "OPENAI_API_KEY not found in environment" {:reason :no-key-provided})))))))

(defn stream!
  "Returns a [channel stop-audio] tuple. The channel receives all non response.audio.delta events. stop-audio
  is a function that can be invoked with 0 or 1 arguments and is used to control audio playback. stop-audio
  accepts an optional map of options that will be passed to the platform specific stop-audio function.

  Options for java:
  - :drain? - (bool) If true, will finish all playback before shutting down. Otherwise shuts down immediately. Default is false

  Note: This function aims to be a sensible default for a multimodal session. If greater control
  is desired, just take from a session channel directly"
  [session & [buf-or-n xf ex-handler]]
  (let [event-ch   (a/chan buf-or-n xf ex-handler)
        audio-ch   (a/chan)
        stop-audio (audio/stream-audio! audio-ch)]
    ;;; Route audio events to audio thread, and text events to text-ch
    (a/go-loop []
      (let [event (a/<! session)]
        (if (nil? event)
          (do (a/close! event-ch)
              (a/close! audio-ch)
              (stop-audio {:drain? true}))
          (let [{:keys [type]} event]
            (condp = type
              "response.audio.delta"
              (when (a/put! audio-ch event)
                (recur))

              (when (a/put! event-ch event)
                (recur)))))))
    [event-ch stop-audio]))

(defn capture-audio!
  "Start capturing audio. Returns a stop function. Options given
   will be forwarded to a platform specific API. Common options:

  - :timeout-ms - How often to append chunks to the OpenAI input buffer. defaults to 500 ms"
  ([session-ch]
   (capture-audio! session-ch {:interval-ms 500}))
  ([session-ch options]
   (audio/capture-audio!
    (fn [chunk]
      (a/put! session-ch {:type  "input_audio_buffer.append"
                          :audio chunk}))
    options)))
