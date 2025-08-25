(ns example.webrtc
  "An example app for using a WebRTC backed channel"
  (:require [clojure.string :as string]
            [reelthyme.core :as rt]
            [reelthyme.schema :as sch]
            [cljs.core.async :as a :refer [go go-loop chan put! <!]]
            [cljs.core.async.interop :refer-macros [<p!]]))

(enable-console-print!)

(defn fetch-session
  "See dev/example/handler.clj for the server side implementation. Nothing terribly fancy here -
  just getting a session (with an ephemeral client secret) via POST https://api.openai.com/v1/realtime/sessions. This is needed to connect!"
  ([]
   (fetch-session nil))
  ([params]
   (let [result-ch (chan)
         params' (clj->js params)]
     (go
       (let [res  (<p! (js/fetch "/session"
                                 (clj->js (cond-> {:method "post"}
                                            (some? params)
                                            (assoc :body (.stringify js/JSON params'))))))
             data (<p! (.json res))]
         (put! result-ch (js->clj data :keywordize-keys true))))
     result-ch)))

(defn dispatch-click
  "State of the art hyper reactive click event delegation"
  [e event-ch]
  (let [target (.-target e)]
    (cond
      (.matches target "#thebutton") (put! event-ch {:type :toggle-session})
      (.matches target "#send")      (put! event-ch {:type :create-response}))
    nil))

(defn dispatch-change
  "State of the art hyper reactive change event delegation"
  [e event-ch]
  (let [target (.-target e)]
    (cond
      (.matches target "input[name=\"modalities\"]")
      (put! event-ch {:type :update-modality :checked (.-checked target) :value (.-value target)})

      (.matches target "#text")
      (put! event-ch {:type :update-text :value (.-value target)}))
    nil))

(defn $
  "We once lived as royalty"
  [selector]
  (let [items (array-seq (.querySelectorAll js/document selector))]
    (if (= 1 (count items))
      (first items)
      items)))

(defn render-usage!
  "Render usage data for tokens and rate limits"
  [{:keys [input-tokens output-tokens rate-limits]}]
  (let [input-text         ($ "#input-tokens-text")
        input-text-cached  ($ "#input-tokens-text-cached")
        input-audio        ($ "#input-tokens-audio")
        input-audio-cached ($ "#input-tokens-audio-cached")
        output-text        ($ "#output-tokens-text")
        output-audio       ($ "#output-tokens-audio")
        limit              ($ "#rate-limits-limit")
        remaining          ($ "#rate-limits-remaining")]
    (set! (.-innerText input-text) (:text input-tokens))
    (set! (.-innerText input-text-cached) (:text-cached input-tokens))
    (set! (.-innerText input-audio) (:audio input-tokens))
    (set! (.-innerText input-audio-cached) (:audio-cached input-tokens))
    (set! (.-innerText output-text) (:text output-tokens))
    (set! (.-innerText output-audio) (:audio output-tokens))
    (set! (.-innerText limit) (:limit rate-limits))
    (set! (.-innerText remaining) (:remaining rate-limits))))

(defn render!
  "Cutting edge rendering technique using Real DOM ™ - the only thing better
  than Virtual DOM"
  [state & {:keys [btn text-group audio text]}]
  (render-usage! state)
  (let [{:keys [fetching? active?]} state]
    (if fetching?
      (set! (.-disabled btn) true)
      (set! (.-disabled btn) false))
    (if active?
      (do
        (set! (.-disabled audio) true)
        (set! (.-innerText btn) "No Mo Reelthyme")
        (.add (.-classList text-group) "visible"))
      (do
        (set! (.-disabled audio) false)
        (set! (.-innerText btn) "Commence Reelthyme")
        (.remove (.-classList text-group) "visible")))
    (when (and (= "" (:text state)) (not (string/blank? (.-value text))))
      (set! (.-value text) ""))))

(defn update-tokens
  "Update token state based on the payload of the given response.done event"
  [state {:keys [response]}]
  (let [usage (:usage response)
        input-token-details (:input_token_details usage)
        cached-input-details (:cached_tokens_details input-token-details)
        output-token-details (:output_token_details usage)]
    (-> state
        (update-in [:input-tokens :text] + (:text_tokens input-token-details))
        (update-in [:input-tokens :text-cached] + (:text_tokens cached-input-details))
        (update-in [:input-tokens :audio] + (:audio_tokens input-token-details))
        (update-in [:input-tokens :audio-cached] + (:audio_tokens cached-input-details))
        (update-in [:output-tokens :text] + (:text_tokens output-token-details))
        (update-in [:output-tokens :audio] + (:audio_tokens output-token-details)))))

(defn update-limits
  "Update limit state based on the payload of the given rate_limits.updated event"
  [state {:keys [rate_limits]}]
  (if-some [tokens (first (filterv #(= "tokens" (:name %)) rate_limits))]
    (-> (assoc-in state [:rate-limits :limit] (:limit tokens))
        (assoc-in [:rate-limits :remaining] (:remaining tokens)))
    state))

(defn app
  "State of the art browser application. Very reactive! If we are using a text only modality, assume we want to immediately
  create a response from a created conversation item (see condp for type conversation.item.created)"
  []
  (let [body        (.-body js/document)
        btn         ($ "#thebutton")
        text-group  ($ "#text-group")
        text        ($ "#text")
        audio       ($ "#modality-audio")
        event-ch    (chan)]
    (go-loop [state {:mounted?   false
                     :modalities #{"text"}
                     :session-ch (chan)
                     :active?    false
                     :fetching?  false
                     :text       ""
                     :input-tokens {:text 0
                                    :text-cached 0
                                    :audio 0
                                    :audio-cached 0}
                     :output-tokens {:text 0
                                     :audio 0}
                     :rate-limits   {:limit 0
                                     :remaining 0}}]
      (render! state :btn btn :text-group text-group :audio audio :text text)
      (cond
        (not (:mounted? state))
        (do (.addEventListener body "click" #(dispatch-click % event-ch))
            (.addEventListener body "change" #(dispatch-change % event-ch))
            (recur (assoc state :mounted? true)))

        :else
        (let [session-ch (:session-ch state)
              [v p] (a/alts! [event-ch session-ch])]
          (condp = p
            session-ch ;;; Read server events from OpenAI
            (let [{:keys [type] :as ev} v]
              (println ev)
              (condp = type
                "conversation.item.created"
                (do (when (= #{"text"} (:modalities state))
                      (put! session-ch {:type "response.create"
                                        :response {:modalities ["text"]}}))
                    (recur state))

                "response.done"
                (recur (update-tokens state ev))

                "rate_limits.updated"
                (recur (update-limits state ev))

                (recur state)))

            event-ch
            (condp = (:type v)
              :create-response
              (let [text-input (:text state)]
                (put! session-ch {:type "conversation.item.create"
                                  :item {:type "message"
                                         :role "user"
                                         :content [{:type "input_text"
                                                    :text text-input}]}})
                (recur (assoc state :text "")))
              :toggle-session
              (if-not (:active? state)
                (do
                  (put! event-ch {:type :session-created
                                  :session (<! (fetch-session (select-keys state [:modalities])))})
                  (recur (assoc state :fetching? true)))
                (do
                  (a/close! session-ch)
                  (recur (assoc state :session-ch (chan) :active? false))))
              :session-created
              (let [session-ch (rt/connect! (:session v) {:xf-in (map sch/validate)})]
                (recur (assoc state :fetching? false :session-ch session-ch :active? true)))
              :update-modality
              (let [{:keys [checked value]} v]
                (recur (update state :modalities (if checked conj disj) value)))
              :update-text
              (let [{:keys [value]} v]
                (recur (assoc state :text value))))))))))

(app)
