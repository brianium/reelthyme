/*
 * reelthyme_aec.h — Thin AEC wrapper around SpeexDSP
 *
 * Provides a minimal 5-function API for acoustic echo cancellation
 * using SpeexDSP's async (two-thread) echo canceller plus preprocessor
 * for residual echo suppression and noise reduction.
 */
#ifndef REELTHYME_AEC_H
#define REELTHYME_AEC_H

#ifdef _WIN32
#define RT_EXPORT __declspec(dllexport)
#else
#define RT_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct RtAecState RtAecState;

/* Create an echo canceller.
 *   frame_size    — samples per frame (e.g. 480 for 20 ms at 24 kHz)
 *   filter_length — tail length in samples (e.g. 4800 for 200 ms at 24 kHz)
 *   sample_rate   — audio sample rate in Hz (e.g. 24000)
 * Returns NULL on failure. */
RT_EXPORT RtAecState *rt_aec_create(int frame_size, int filter_length, int sample_rate);

/* Feed a playback frame to the canceller (call from the playback thread).
 *   play — frame_size samples of PCM16 audio about to be played. */
RT_EXPORT void rt_aec_playback(RtAecState *st, const short *play);

/* Process a capture frame through the canceller (call from the capture thread).
 *   rec — frame_size samples of raw microphone PCM16
 *   out — frame_size samples of echo-cancelled PCM16 (caller-allocated) */
RT_EXPORT void rt_aec_capture(RtAecState *st, const short *rec, short *out);

/* Reset the echo canceller state (e.g. after a pause in conversation). */
RT_EXPORT void rt_aec_reset(RtAecState *st);

/* Free all resources. */
RT_EXPORT void rt_aec_destroy(RtAecState *st);

#ifdef __cplusplus
}
#endif

#endif /* REELTHYME_AEC_H */
