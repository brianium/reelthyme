(ns example.handler
  (:require [clojure.core.async :as a :refer [go-loop]]
            [reelthyme.core :as rt]
            [ring.util.response :refer [resource-response content-type]]
            [charred.api :as json]))

(defn handle-client-secret
  [req]
  (let [body   (slurp (:body req))
        data   (json/read-json body {:key-fn keyword})
        secret (rt/create-client-secret {:session data})
        sess   (:session secret)]
    (if (= "realtime.session" (:object sess))
      {:status 200
       :headers {"content-type" "application/json"}
       :body    (json/write-json-str secret)}
      {:status 500
       :headers {"content-type" "application/json"}
       :body    (json/write-json-str sess)})))

(defonce *sideband-ch (atom nil))

(def TIMEOUT_MS 15000)

(defn follow-conversation
  "Start a websocket channel that follows the client side channel. Channel shuts down
   if TIMEOUT_MS seconds pass without a server event"
  [req]
  (let [body        (slurp (:body req))
        data        (json/read-json body {:key-fn keyword})
        sideband-ch (reset! *sideband-ch (rt/connect! {:api-key (:client_secret data)
                                                       :call-id (:call_id data)}))]
    (go-loop [t (a/timeout TIMEOUT_MS)]
      (let [[v p] (a/alts! [sideband-ch t])]
        (if (= p t)
          (println "timeout")
          (do
            (println v)
            (recur (a/timeout TIMEOUT_MS))))))
    {:status 204}))

(defn handler [req]
  (let [uri (:uri req)]
    (condp = uri
      "/client-secret" (handle-client-secret req)
      "/sideband" (follow-conversation req)
      (some-> (resource-response "index.html" {:root "public"})
              (content-type "text/html; charset=utf-8")))))
