(ns reelthyme.transport.webrtc
  "WebRTC is clearly superior for this sort of application. "
  (:require [cljs.core.async :as a :refer [go go-loop chan put! <!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [cljs.core.async.impl.protocols :as proto :refer [ReadPort Channel WritePort]]))

(defn stop-tracks!
  "Stop all tracks attached to the peer connection"
  [peer-connection]
  (let [stop!     (fn [has-track]
                    (when-some [track (.-track has-track)]
                      (when (= "audio" (.-kind track))
                        (.stop track))))
        senders   (array-seq (.getSenders peer-connection))
        receivers (array-seq (.getReceivers peer-connection))]
    (doseq [sender senders]
      (stop! sender))
    (doseq [receiver receivers]
      (stop! receiver))))

(defrecord RtcChan [in out peer-connection audio-elem]
  ReadPort
  (take! [_ fn-1] (proto/take! out fn-1))

  WritePort
  (put! [_ v cb] (proto/put! in v cb))

  Channel
  (close! [_]
    (proto/close! in)
    (proto/close! out)
    (stop-tracks! peer-connection)
    (when audio-elem
      (set! (.-srcObject audio-elem) nil))
    (.close peer-connection))
  (closed? [_]
    (and (proto/closed? in)
         (proto/closed? out))))

(defn rtc-chan
  "A bi-directional channel that stores a reference
  to the audio element audio playback occurs on"
  [in out peer-connection audio-elem]
  (RtcChan. in out peer-connection audio-elem))

(defn add-audio-track!
  "sdp offer expects one audio track - we can use a silent track
   if a text only modality is requested, however infrequent"
  [{:keys [silent?]}]
  (let [done (chan)]
    (if silent?
      (let [ctx (js/AudioContext.)
            dst (.createMediaStreamDestination ctx)
            stream (.-stream dst)
            track (aget (.getAudioTracks stream) 0)]
        (put! done track))
      (go
        (let [stream (<p! (.getUserMedia js/navigator.mediaDevices #js {:audio true}))
              track  (aget (.getAudioTracks stream) 0)]
          (put! done track))))
    done))

(defn create-audio-elem []
  (let [elem (.createElement js/document "audio")]
    (set! (.-autoplay elem) true)
    elem))

(defn connect!
  "Return a channel backed by an RTCPeerConnection. puts are sent of the connection's
  data channel and takes are received on it. Closing the channel will close all audio
  tracks attached to the connection, as well as the connection itself"
  ([client-secret]
   (connect! client-secret {}))
  ([client-secret {:keys [buffer xf-out ex-handler xf-in content-types] :or {buffer 10 content-types #{"input_audio" "input_text"}}}]
   (let [session       (:session client-secret)
         ephemeral-key (:value client-secret)
         audio?        (some? ((set (:output_modalities session)) "audio"))
         in-ch         (chan buffer xf-in)
         out-ch        (chan buffer xf-out ex-handler)
         pc            (js/RTCPeerConnection.)
         dc            (.createDataChannel pc "oai-events")
         audio-elem    (when audio?
                         (let [el (create-audio-elem)]
                           (set! (.-ontrack pc) #(set! (.-srcObject el) (aget (.-streams %) 0)))
                           el))]

     ;;; Stream messages from the data channel to the output stream
     (.addEventListener dc "message" #(put! out-ch %))

     ;;; Forward input to the data channel
     (go-loop []
       (let [ev (<! in-ch)]
         (if (nil? ev)
           (.close pc)
           (do
             (.send dc ev)
             (recur)))))

     ;;; Start the session using the Session Description Protocol (SDP)
     (go
       (let [track (<! (add-audio-track! {:silent? (not (contains? content-types "input_audio"))}))
             _     (.addTrack pc track)
             offer (<p! (.createOffer pc))
             _     (<p! (.setLocalDescription pc offer))
             url   "https://api.openai.com/v1/realtime/calls"
             res   (<p! (js/fetch url #js{:method "post"
                                          :body   (.-sdp offer)
                                          :headers
                                          #js{"Authorization" (str "Bearer " ephemeral-key)
                                              "Content-Type"  "application/sdp"}}))
             answer #js{:type "answer"
                        :sdp  (<p! (.text res))}]
         (<p! (.setRemoteDescription pc answer))))

     (rtc-chan in-ch out-ch pc audio-elem))))
