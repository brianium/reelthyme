(ns reelthyme.audio.aec-test
  (:require [clojure.test :refer [deftest is testing]]
            [reelthyme.audio.aec :as aec]))

;;; ---------------------------------------------------------------------------
;;; frame-processor tests
;;; ---------------------------------------------------------------------------

(deftest frame-processor-exact-frame
  (testing "Input exactly equal to frame size produces one callback"
    (let [calls (atom [])
          fp    (aec/frame-processor 4 #(swap! calls conj (vec %)))]
      (fp (byte-array [1 2 3 4]))
      (is (= [[1 2 3 4]] @calls)))))

(deftest frame-processor-undersized
  (testing "Input smaller than frame size produces no callbacks"
    (let [calls (atom [])
          fp    (aec/frame-processor 4 #(swap! calls conj (vec %)))]
      (fp (byte-array [1 2]))
      (is (empty? @calls)))))

(deftest frame-processor-oversized
  (testing "Input larger than frame size produces multiple callbacks"
    (let [calls (atom [])
          fp    (aec/frame-processor 4 #(swap! calls conj (vec %)))]
      (fp (byte-array [1 2 3 4 5 6 7 8 9]))
      (is (= [[1 2 3 4] [5 6 7 8]] @calls)))))

(deftest frame-processor-remainder-carry-over
  (testing "Remainder from one call carries over to next"
    (let [calls (atom [])
          fp    (aec/frame-processor 4 #(swap! calls conj (vec %)))]
      (fp (byte-array [1 2 3]))
      (is (empty? @calls))
      (fp (byte-array [4 5]))
      (is (= [[1 2 3 4]] @calls))
      (fp (byte-array [6 7 8]))
      (is (= [[1 2 3 4] [5 6 7 8]] @calls)))))

(deftest frame-processor-multi-frame-with-remainder
  (testing "Large input with remainder carries over correctly"
    (let [calls (atom [])
          fp    (aec/frame-processor 3 #(swap! calls conj (vec %)))]
      (fp (byte-array [1 2 3 4 5 6 7 8]))
      (is (= [[1 2 3] [4 5 6]] @calls))
      (fp (byte-array [9 10]))
      (is (= [[1 2 3] [4 5 6] [7 8 9]] @calls)))))

(deftest frame-processor-empty-input
  (testing "Empty input produces no callbacks and no error"
    (let [calls (atom [])
          fp    (aec/frame-processor 4 #(swap! calls conj (vec %)))]
      (fp (byte-array 0))
      (is (empty? @calls)))))

;;; ---------------------------------------------------------------------------
;;; Native AEC tests (require native library on classpath + Java 22+)
;;; ---------------------------------------------------------------------------

(deftest ^:native echo-canceller-lifecycle
  (testing "Create, reset, and destroy echo canceller"
    (let [ec (aec/create-echo-canceller :frame-size 480 :filter-length 4800 :sample-rate 24000)]
      (is (some? (:state-ptr ec)))
      (is (= 480 (:frame-size ec)))
      (is (= 960 (:frame-bytes ec)))
      (aec/reset-echo-canceller! ec)
      (aec/destroy-echo-canceller! ec))))

(defn- byte-array->native-segment
  "Copy a byte array into a native (off-heap) MemorySegment."
  ^java.lang.foreign.MemorySegment [^java.lang.foreign.Arena arena ^bytes ba]
  (let [seg (.allocate arena (long (alength ba)))]
    (java.lang.foreign.MemorySegment/copy ba 0 seg java.lang.foreign.ValueLayout/JAVA_BYTE 0 (alength ba))
    seg))

(deftest ^:native echo-cancellation-reduces-amplitude
  (testing "Synthetic echo: feeding identical signal as playback and capture reduces output amplitude"
    (let [ec          (aec/create-echo-canceller :frame-size 480 :filter-length 4800 :sample-rate 24000)
          frame-bytes 960
          ;; Generate a 1kHz sine wave frame at 24kHz sample rate
          sine-frame  (let [ba (byte-array frame-bytes)]
                        (dotimes [i 480]
                          (let [sample (short (* 16000 (Math/sin (* 2.0 Math/PI 1000.0 (/ i 24000.0)))))
                                lo     (unchecked-byte (bit-and sample 0xFF))
                                hi     (unchecked-byte (bit-shift-right sample 8))]
                            ;; little-endian PCM16
                            (aset-byte ba (* i 2) lo)
                            (aset-byte ba (inc (* i 2)) hi)))
                        ba)
          test-arena  (java.lang.foreign.Arena/ofShared)
          play-seg    (byte-array->native-segment test-arena sine-frame)
          in-seg      (byte-array->native-segment test-arena sine-frame)
          out-bytes   (atom nil)]

      ;; Feed several frames to let the canceller adapt
      (dotimes [_ 50]
        (aec/aec-playback! ec play-seg)
        (let [out-seg (.allocate test-arena (long frame-bytes))]
          (aec/aec-capture! ec in-seg out-seg)
          (reset! out-bytes (.toArray out-seg java.lang.foreign.ValueLayout/JAVA_BYTE))))

      ;; Compute RMS of input and output
      (let [rms (fn [^bytes ba]
                  (let [n (quot (alength ba) 2)]
                    (Math/sqrt
                     (/ (double
                         (reduce + 0
                                 (map (fn [i]
                                        (let [lo  (bit-and (aget ba (* i 2)) 0xFF)
                                              hi  (aget ba (inc (* i 2)))
                                              s   (short (bit-or lo (bit-shift-left hi 8)))]
                                          (* (long s) (long s))))
                                      (range n))))
                        n))))
            input-rms  (rms sine-frame)
            output-rms (rms @out-bytes)]

        ;; After adaptation, the output should have lower RMS than the input
        (is (< output-rms input-rms)
            (str "Expected output RMS (" output-rms ") < input RMS (" input-rms ")")))

      (.close test-arena)
      (aec/destroy-echo-canceller! ec))))
