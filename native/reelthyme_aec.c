/*
 * reelthyme_aec.c — Thin AEC wrapper around SpeexDSP
 *
 * Uses SpeexDSP's async echo cancellation API (speex_echo_playback /
 * speex_echo_capture) which is designed for the two-thread pattern where
 * playback and capture run on separate threads. A preprocessor is attached
 * for residual echo suppression and noise reduction.
 */
#include "reelthyme_aec.h"
#include "speexdsp/include/speex/speex_echo.h"
#include "speexdsp/include/speex/speex_preprocess.h"
#include <stdlib.h>

struct RtAecState {
    SpeexEchoState        *echo;
    SpeexPreprocessState  *preprocess;
    int                    frame_size;
};

RT_EXPORT RtAecState *rt_aec_create(int frame_size, int filter_length, int sample_rate) {
    RtAecState *st = (RtAecState *)calloc(1, sizeof(RtAecState));
    if (!st) return NULL;

    st->frame_size = frame_size;

    st->echo = speex_echo_state_init(frame_size, filter_length);
    if (!st->echo) {
        free(st);
        return NULL;
    }
    speex_echo_ctl(st->echo, SPEEX_ECHO_SET_SAMPLING_RATE, &sample_rate);

    st->preprocess = speex_preprocess_state_init(frame_size, sample_rate);
    if (!st->preprocess) {
        speex_echo_state_destroy(st->echo);
        free(st);
        return NULL;
    }
    speex_preprocess_ctl(st->preprocess, SPEEX_PREPROCESS_SET_ECHO_STATE, st->echo);

    return st;
}

RT_EXPORT void rt_aec_playback(RtAecState *st, const short *play) {
    if (!st) return;
    speex_echo_playback(st->echo, play);
}

RT_EXPORT void rt_aec_capture(RtAecState *st, const short *rec, short *out) {
    if (!st) return;
    speex_echo_capture(st->echo, rec, out);
    speex_preprocess_run(st->preprocess, out);
}

RT_EXPORT void rt_aec_reset(RtAecState *st) {
    if (!st) return;
    speex_echo_state_reset(st->echo);
}

RT_EXPORT void rt_aec_destroy(RtAecState *st) {
    if (!st) return;
    if (st->preprocess) speex_preprocess_state_destroy(st->preprocess);
    if (st->echo)       speex_echo_state_destroy(st->echo);
    free(st);
}
