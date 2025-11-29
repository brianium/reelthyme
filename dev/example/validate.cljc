(ns example.validate
  (:require [malli.core :as m]
            [malli.error :as me]
            [reelthyme.schema :as sc]))

(defn validate
  "Validate the given client event. Returns if valid, otherwise throws an ex-info
  containing the humanized explanation and the event that was given. Should probably
  be used in development only"
  [event]
  (if-some [explain (m/explain sc/ClientEvent event)]
    (throw (ex-info "Invalid client event given" {:humanized (me/humanize explain)
                                                  :event     event}))
    event))
