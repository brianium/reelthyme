(ns reelthyme.audio.java
  "Audio capture and playback for Clojure on the JVM. Powered by javax.sound.sampled"
  (:require [clojure.core.async :as a])
  (:import (javax.sound.sampled AudioSystem AudioFormat)
           (java.util Base64)))

(defn- resolve-aec-fn
  "Lazily resolve a function from the aec namespace."
  [sym]
  (requiring-resolve (symbol "reelthyme.audio.aec" (name sym))))

(defn- bytes->native-segment
  "Copy a byte array into an off-heap MemorySegment allocated from the given arena."
  ^java.lang.foreign.MemorySegment [^java.lang.foreign.Arena arena ^bytes ba]
  (let [seg (.allocate arena (long (alength ba)))]
    (java.lang.foreign.MemorySegment/copy ba 0 seg java.lang.foreign.ValueLayout/JAVA_BYTE 0 (alength ba))
    seg))

(defn- make-playback-processor
  "Create a frame processor that feeds playback audio to the echo canceller.
  Returns a function that accepts a byte array of PCM data."
  [echo-canceller]
  (let [frame-processor (resolve-aec-fn 'frame-processor)
        aec-playback!   (resolve-aec-fn 'aec-playback!)
        frame-bytes     (:frame-bytes echo-canceller)]
    (frame-processor
     frame-bytes
     (fn [^bytes frame]
       (let [arena (java.lang.foreign.Arena/ofConfined)
             seg   (bytes->native-segment arena frame)]
         (aec-playback! echo-canceller seg)
         (.close arena))))))

(defn- make-capture-processor
  "Create a frame processor that runs capture audio through the echo canceller.
  Returns a function that accepts a byte array and returns echo-cancelled bytes."
  [echo-canceller]
  (let [frame-processor (resolve-aec-fn 'frame-processor)
        aec-capture!    (resolve-aec-fn 'aec-capture!)
        frame-bytes     (:frame-bytes echo-canceller)
        out-buf         (volatile! (byte-array 0))]
    [(frame-processor
      frame-bytes
      (fn [^bytes frame]
        (let [arena   (java.lang.foreign.Arena/ofConfined)
              in-seg  (bytes->native-segment arena frame)
              out-seg (.allocate arena (long frame-bytes))
              _       (aec-capture! echo-canceller in-seg out-seg)
              result  (.toArray out-seg java.lang.foreign.ValueLayout/JAVA_BYTE)]
          (.close arena)
          (let [prev  @out-buf
                prev-n (alength prev)
                new-buf (byte-array (+ prev-n frame-bytes))]
            (System/arraycopy prev 0 new-buf 0 prev-n)
            (System/arraycopy result 0 new-buf prev-n frame-bytes)
            (vreset! out-buf new-buf)))))
     (fn drain-and-reset! []
       (let [result @out-buf]
         (vreset! out-buf (byte-array 0))
         result))]))

(defn stream-audio!
  "An audio streaming function that plays streamed audio via javax.sound.sampled on a separate thread.
  Optionally accepts an :echo-canceller to feed playback frames for AEC."
  [audio-ch & {:keys [echo-canceller]}]
  (let [fmt      (AudioFormat. 24000.0 16 1 true false)
        b64      (Base64/getDecoder)
        line     (doto (AudioSystem/getSourceDataLine fmt)
                   (.open fmt)
                   (.start))
        feed-aec (when echo-canceller
                   (make-playback-processor echo-canceller))
        stop-audio (fn [& {:keys [drain?] :or {drain? false}}]
                     (when drain?
                       (.drain line))
                     (.stop line)
                     (.close line))]
    (a/thread
      (loop []
        (when-let [{:keys [type delta]} (a/<!! audio-ch)]
          (case type
            "response.output_audio.delta"
            (let [pcm (.decode b64 ^String delta)
                  len (alength pcm)]

              ;; Feed playback frames to AEC before writing to speaker
              (when feed-aec
                (feed-aec pcm))

              ;; block here until the mixer has room
              (loop [off 0]
                (when (< off len)
                  (let [n (.write line pcm off (- len off))]
                    (recur (+ off n)))))))
          (recur))))
    stop-audio))

(defn capture-audio!
  "Capture audio from the default microphone in PCM16 format (24000Hz, mono), Base64-encodes chunks, and invokes callback with each chunk. Returns a stop-fn to stop recording."
  [callback & {:keys [buffer-size max-chunk-bytes interval-ms echo-canceller]
               :or   {buffer-size 4096
                      max-chunk-bytes (* 15 1024 1024)
                      interval-ms 1000}}]
  (let [fmt     (AudioFormat. 24000.0 16 1 true false)
        line    (doto (AudioSystem/getTargetDataLine fmt)
                  (.open fmt)
                  (.start))
        b64     (Base64/getEncoder)
        [process-capture drain-capture]
        (when echo-canceller
          (make-capture-processor echo-canceller))
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
                        data     (if process-capture
                                   (do (process-capture (java.util.Arrays/copyOf buf n))
                                       (drain-capture))
                                   (let [d (byte-array n)]
                                     (System/arraycopy buf 0 d 0 n)
                                     d))
                        data-n   (alength data)]
                    (let [combined (byte-array (+ (alength acc) data-n))
                          now      (System/currentTimeMillis)]
                      (System/arraycopy acc 0 combined 0 (alength acc))
                      (System/arraycopy data 0 combined (alength acc) data-n)
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
