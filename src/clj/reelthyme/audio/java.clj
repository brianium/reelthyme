(ns reelthyme.audio.java
  "Audio capture and playback for Clojure on the JVM. Powered by javax.sound.sampled"
  (:require [clojure.core.async :as a])
  (:import (javax.sound.sampled AudioSystem AudioFormat)
           (java.util Base64)))

(defn stream-audio!
  "An audio streaming function that plays streamed audio via javax.sound.sampled on a separate thread"
  [audio-ch]
  (let [fmt      (AudioFormat. 24000.0 16 1 true false)
        b64      (Base64/getDecoder)
        line     (doto (AudioSystem/getSourceDataLine fmt)
                   (.open fmt)
                   (.start))
        stop-audio (fn [& {:keys [drain?] :or {drain? false}}]
                     (when drain?
                       (.drain line))
                     (.stop line)
                     (.close line))]
    (a/thread
      (loop []
        (when-let [{:keys [type delta]} (a/<!! audio-ch)]
          (case type
            "response.audio.delta"
            (let [pcm (.decode b64 ^String delta)
                  len (alength pcm)]
              
              ;; block here until the mixer has room
              (loop [off 0]
                (when (< off len)
                  (let [n (.write line pcm off (- len off))]
                    (recur (+ off n)))))))
          (recur))))
    stop-audio))

 (defn capture-audio!
  "Capture audio from the default microphone in PCM16 format (24000Hz, mono), Base64-encodes chunks, and invokes callback with each chunk. Returns a stop-fn to stop recording."
  [callback & {:keys [buffer-size max-chunk-bytes interval-ms] :or {buffer-size 4096
                                                                    max-chunk-bytes (* 15 1024 1024)
                                                                    interval-ms 1000}}]
  (let [fmt     (AudioFormat. 24000.0 16 1 true false)
        line    (doto (AudioSystem/getTargetDataLine fmt)
                  (.open fmt)
                  (.start))
        b64     (Base64/getEncoder)
        stop?   (promise)
        fut
        (future
          (let [buf-size (int buffer-size)
                max-bytes (int max-chunk-bytes)]
            (try
              (loop [acc (byte-array 0)
                     last-flush (System/currentTimeMillis)]
                (if (realized? stop?)
                  (when (pos? (alength acc))
                    (callback (.encodeToString b64 acc)))
                  (let [buf      (byte-array buf-size)
                        n        (.read line buf 0 buf-size)
                        data     (byte-array n)]
                    (System/arraycopy buf 0 data 0 n)
                    (let [combined (byte-array (+ (alength acc) n))
                          now      (System/currentTimeMillis)]
                      (System/arraycopy acc 0 combined 0 (alength acc))
                      (System/arraycopy data 0 combined (alength acc) n)
                      (if (or (>= (alength combined) max-bytes)
                              (>= (- now last-flush) interval-ms))
                        (do
                          (callback (.encodeToString b64 combined))
                          (recur (byte-array 0) now))
                        (recur combined last-flush))))))
              (finally
                (.stop line)
                (.flush line)
                (.close line)))))]
    (fn []
      (deliver stop? true)
      @fut
      nil)))
