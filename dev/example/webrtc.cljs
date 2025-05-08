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

(defn render!
  "Cutting edge rendering technique using Real DOM ™ - the only thing better
  than Virtual DOM"
  [state & {:keys [btn text-group audio text]}]
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

(defn app
  "State of the art browser application. Very reactive!"
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
                     :text       ""}]
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
              ;;; If we are using a text only modality, assume we want to immediately
              ;;; create a response from a created conversation item
              (condp = type
                "conversation.item.created" (when (= #{"text"} (:modalities state))
                                              (put! session-ch {:type "response.create"
                                                                :response {:modalities ["text"]}}))
                nil)
              (recur state))

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
