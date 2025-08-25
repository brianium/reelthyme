(ns dev
  (:require [clojure.core.async :as a]
            [clojure.tools.namespace.repl :as repl]
            [reelthyme.core :as rt]
            [reelthyme.schema :as sc]))

(defn start []
  (println "Anything to start?"))

(defn stop []
  (println "Anything to stop?"))

(defn refresh []
  (repl/refresh :after 'dev/start))

;;; Usage (Execute at the REPL for a good time)

(comment
;;; Create an OpenAI realtime session. For development, we add a transducer
;;; to the input channel that throws if we send an invalid client event.
;;; puts to this channel are sent as client events
;;; server events are received as takes
  (def session-ch (rt/connect! {:xf-in (map sc/validate)}))

;;; The stream! function returns a [channel stop-audio] tuple.
;;; The channel receives all non response.audio.delta server events
;;; The stop-audio function can be called with 0 or 1 arguments and stops audio immediately
;;; The single argument to stop-audio is platform dependent - i.e {:drain? true} for jvm
;;; All audio response.audio.delta events will be audibly played. All additional arguments to
;;; the stream function follow the same rules as a clojure.core.async/chan & [buf-or-n xf ex-handler]
;;; and will be applied to the event-ch
  (let [[event-ch stop-audio] (rt/stream! session-ch)]
;;; We will def some vars for some REPL convenience
    (def event-ch event-ch)
    (def stop-audio stop-audio))

;;; The event-ch returned by stream! can be useful for application flow.
;;; Here we start capturing microphone audio as soon as a session.updated
;;; event is received. We also attempt to be clever and turn the mic off and
;;; back on based on server events.
  (a/go-loop [stop-fn nil]
    (let [ev (a/<! event-ch)]
      (println (:type ev)) ;;; Take a look at server events coming in
      (if (nil? ev)
        (when stop-fn
          (stop-fn))
        (condp = (:type ev)
          "session.updated"
          (recur (rt/capture-audio! session-ch))

          "input_audio_buffer.committed"
          (recur (stop-fn))

          "response.audio.done"
          (recur (rt/capture-audio! session-ch))

          (recur stop-fn)))))

;;; From here, we can just put client events onto the session channel
;;; If we are using the reelthyme.validate function, we can just let em rip with reckless
;;; abandon - trusting that we will get yelled at with an explanation of our mistakes
  (a/put! session-ch {:type    "session.update"
                      :session {:voice "verse"
                                :output_audio_format "pcm16"
                                :turn_detection
                                {:create_response true
                                 :interrupt_response false
                                 :type "server_vad"}}})

;;; Text input is allowed
  (a/put! session-ch {:type "conversation.item.create"
                      :item {:type "message"
                             :role "user"
                             :content [{:type "input_text"
                                        :text "What Prince album sold the most copies?"}]}})

;;; We can create a response after giving our text input AND hear the response
  (a/put! session-ch {:type     "response.create"
                      :response {:modalities ["audio" "text"]}})

;;; We can shut the audio down immediately if we want
;;; Note: This just cuts audio, you may want to also send a conversation.item.truncate client event
  (stop-audio {:drain? false})

;;; Shut it all down
  (a/close! session-ch)
  )

;;; Server Response Samples

(def sample-response-done
  {:type "response.done",
   :event_id "event_C8R3K7zFQbnMWFSfwqRMS",
   :response
   {:voice "alloy",
    :conversation_id "conv_C8R3JHAJYGsn7sQEB1RPc",
    :output
    [{:id "item_C8R3J2I5qVaw7ZupWCqDo",
      :object "realtime.item",
      :type "message",
      :status "completed",
      :role "assistant",
      :content [{:type "audio", :transcript "¡Hola! ¿Qué tal?"}]}],
    :usage
    {:total_tokens 162,
     :input_tokens 123,
     :output_tokens 39,
     :input_token_details
     {:text_tokens 119,
      :audio_tokens 4,
      :image_tokens 0,
      :cached_tokens 64,
      :cached_tokens_details
      {:text_tokens 64, :audio_tokens 0, :image_tokens 0}},
     :output_token_details {:text_tokens 15, :audio_tokens 24}},
    :output_audio_format "pcm16",
    :status "completed",
    :id "resp_C8R3JX7zFQKzJwJI7VmyA",
    :modalities ["audio" "text"],
    :max_output_tokens "inf",
    :metadata nil,
    :object "realtime.response",
    :temperature 0.8,
    :status_details nil}})

(def sample-response-rate-limites-updated
  {:type "rate_limits.updated"
   :event_id "event_C8R3KFyNLdGwwH5JSnGmt"
   :rate_limits [{:name "tokens", :limit 800000, :remaining 799467, :reset_seconds "0.039"}]})
