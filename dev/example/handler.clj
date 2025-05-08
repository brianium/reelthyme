(ns example.handler
  (:require [reelthyme.core :as rt]
            [ring.util.response :refer [resource-response content-type]]
            [cheshire.core :as json]))

(defn handle-session
  [req]
  (let [body (slurp (:body req))
        data (json/parse-string body keyword)
        sess (rt/create-session data)]
    (if (= "realtime.session" (:object sess))
      {:status 200
       :headers {"content-type" "application/json"}
       :body    (json/generate-string sess)}
      {:status 500
       :headers {"content-type" "application/json"}
       :body    (json/generate-string sess)})))

(defn handler [req]
  (let [uri (:uri req)]
    (if (= uri "/session")
      (handle-session req)
      (some-> (resource-response "index.html" {:root "public"})
              (content-type "text/html; charset=utf-8")))))
