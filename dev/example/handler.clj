(ns example.handler
  (:require [reelthyme.core :as rt]
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

(defn handler [req]
  (let [uri (:uri req)]
    (if (= uri "/client-secret")
      (handle-client-secret req)
      (some-> (resource-response "index.html" {:root "public"})
              (content-type "text/html; charset=utf-8")))))
