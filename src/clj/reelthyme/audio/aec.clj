(ns reelthyme.audio.aec
  "Acoustic Echo Cancellation via SpeexDSP using Panama FFM (Java 22+).

  Provides frame-aligned AEC processing designed for the two-thread pattern:
  - Playback thread calls aec-playback! to tell the canceller what's being played
  - Capture thread calls aec-capture! to filter echo from mic input

  The frame-processor utility handles buffering raw audio into fixed-size frames."
  (:import (java.lang.foreign Arena FunctionDescriptor Linker
                              MemorySegment SymbolLookup ValueLayout)
           (java.lang.invoke MethodHandle)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;;; ---------------------------------------------------------------------------
;;; Platform detection
;;; ---------------------------------------------------------------------------

(defn- detect-platform
  "Returns [os arch lib-name] for the current platform."
  []
  (let [os-name   (System/getProperty "os.name")
        os-arch   (System/getProperty "os.arch")
        os   (cond
               (.contains os-name "Mac")   "darwin"
               (.contains os-name "Linux") "linux"
               (.contains os-name "Win")   "windows"
               :else (throw (ex-info (str "Unsupported OS: " os-name) {:os os-name})))
        arch (case os-arch
               ("aarch64" "arm64") "aarch64"
               ("amd64" "x86_64") "x86_64"
               (throw (ex-info (str "Unsupported arch: " os-arch) {:arch os-arch})))
        ext  (case os
               "darwin"  "dylib"
               "linux"   "so"
               "windows" "dll")
        prefix (if (= os "windows") "" "lib")
        lib-name (str prefix "reelthyme_aec." ext)]
    [os arch lib-name]))

;;; ---------------------------------------------------------------------------
;;; Native library loading (lazy)
;;; ---------------------------------------------------------------------------

(defonce ^:private lib-lookup
  (delay
    (let [[os arch lib-name] (detect-platform)
          resource-path (str "native/" os "-" arch "/" lib-name)
          resource      (.getResource (.getContextClassLoader (Thread/currentThread)) resource-path)]
      (when-not resource
        (throw (ex-info (str "Native AEC library not found on classpath: " resource-path)
                        {:resource-path resource-path})))
      (let [tmp-dir  (Files/createTempDirectory "reelthyme-aec" (into-array FileAttribute []))
            tmp-file (.resolve tmp-dir lib-name)]
        (with-open [in (.openStream resource)]
          (Files/copy in tmp-file (into-array java.nio.file.CopyOption
                                              [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))
        (SymbolLookup/libraryLookup tmp-file (Arena/global))))))

(defn- lookup ^SymbolLookup []
  @lib-lookup)

;;; ---------------------------------------------------------------------------
;;; Downcall handles
;;; ---------------------------------------------------------------------------

(defonce ^:private native-linker (delay (Linker/nativeLinker)))

(defn- downcall ^MethodHandle [name ^FunctionDescriptor fd]
  (let [sym (.find (lookup) name)]
    (when (.isEmpty sym)
      (throw (ex-info (str "Symbol not found: " name) {:symbol name})))
    (.downcallHandle ^Linker @native-linker
                     ^MemorySegment (.get sym)
                     fd
                     (into-array java.lang.foreign.Linker$Option []))))

(def ^:private ^ValueLayout ptr ValueLayout/ADDRESS)
(def ^:private ^ValueLayout c-int ValueLayout/JAVA_INT)

(defn- layouts ^"[Ljava.lang.foreign.MemoryLayout;" [& layouts]
  (into-array java.lang.foreign.MemoryLayout layouts))

(defonce ^:private h-create
  (delay (downcall "rt_aec_create"
                   (FunctionDescriptor/of ptr (layouts c-int c-int c-int)))))

(defonce ^:private h-playback
  (delay (downcall "rt_aec_playback"
                   (FunctionDescriptor/ofVoid (layouts ptr ptr)))))

(defonce ^:private h-capture
  (delay (downcall "rt_aec_capture"
                   (FunctionDescriptor/ofVoid (layouts ptr ptr ptr)))))

(defonce ^:private h-reset
  (delay (downcall "rt_aec_reset"
                   (FunctionDescriptor/ofVoid (layouts ptr)))))

(defonce ^:private h-destroy
  (delay (downcall "rt_aec_destroy"
                   (FunctionDescriptor/ofVoid (layouts ptr)))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn create-echo-canceller
  "Create an AEC instance. Returns an opaque map with :state-ptr, :arena, :frame-size, :frame-bytes.

  Options:
    :frame-size    - samples per frame (default 480 = 20ms at 24kHz)
    :filter-length - echo tail in samples (default 4800 = 200ms at 24kHz)
    :sample-rate   - sample rate in Hz (default 24000)"
  [& {:keys [frame-size filter-length sample-rate]
      :or   {frame-size 480 filter-length 4800 sample-rate 24000}}]
  (let [arena     (Arena/ofShared)
        state-ptr (.invokeWithArguments ^MethodHandle @h-create
                                        (object-array [(int frame-size) (int filter-length) (int sample-rate)]))]
    (when (.equals MemorySegment/NULL state-ptr)
      (.close arena)
      (throw (ex-info "Failed to create echo canceller" {})))
    {:state-ptr   state-ptr
     :arena       arena
     :frame-size  frame-size
     :frame-bytes (* frame-size 2)}))

(defn aec-playback!
  "Feed a playback frame to the echo canceller. Call from the playback thread.
  frame-segment must be a MemorySegment of frame-bytes size containing PCM16 audio."
  [ec ^MemorySegment frame-segment]
  (.invokeWithArguments ^MethodHandle @h-playback
                        (object-array [(:state-ptr ec) frame-segment])))

(defn aec-capture!
  "Process a capture frame through the echo canceller. Call from the capture thread.
  in-segment and out-segment must be MemorySegments of frame-bytes size."
  [ec ^MemorySegment in-segment ^MemorySegment out-segment]
  (.invokeWithArguments ^MethodHandle @h-capture
                        (object-array [(:state-ptr ec) in-segment out-segment])))

(defn reset-echo-canceller!
  "Reset the echo canceller state (e.g. after a pause)."
  [ec]
  (.invokeWithArguments ^MethodHandle @h-reset
                        (object-array [(:state-ptr ec)])))

(defn destroy-echo-canceller!
  "Free all native resources associated with the echo canceller."
  [ec]
  (.invokeWithArguments ^MethodHandle @h-destroy
                        (object-array [(:state-ptr ec)]))
  (.close ^Arena (:arena ec))
  nil)

;;; ---------------------------------------------------------------------------
;;; Frame-alignment utility
;;; ---------------------------------------------------------------------------

(defn frame-processor
  "Returns a stateful function that buffers incoming byte arrays and invokes
  callback with exactly frame-bytes sized byte arrays for each complete frame.

  Usage:
    (def fp (frame-processor 960 process-frame!))
    (fp some-pcm-bytes) ;; calls process-frame! 0 or more times with 960-byte chunks"
  [frame-bytes callback]
  (let [buf (volatile! (byte-array 0))]
    (fn [^bytes data]
      (let [prev    @buf
            prev-n  (alength prev)
            data-n  (alength data)
            total-n (+ prev-n data-n)
            combined (byte-array total-n)]
        (System/arraycopy prev 0 combined 0 prev-n)
        (System/arraycopy data 0 combined prev-n data-n)
        (loop [off 0]
          (if (<= (+ off frame-bytes) total-n)
            (let [frame (byte-array frame-bytes)]
              (System/arraycopy combined off frame 0 frame-bytes)
              (callback frame)
              (recur (+ off frame-bytes)))
            (let [remainder (- total-n off)
                  rest-buf  (byte-array remainder)]
              (when (pos? remainder)
                (System/arraycopy combined off rest-buf 0 remainder))
              (vreset! buf rest-buf))))))))
