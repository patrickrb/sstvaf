// sstv_osc.h — phase-continuous oscillator with an ABSOLUTE microsecond
// time base.
//
// Every segment of an SSTV transmission ends at an absolute target time;
// samples per segment are NEVER rounded (Scottie 2's line is 3332.304
// samples at 12 kHz — per-line rounding would drift ~9 px over an image).
// Sample n's instant is t(n) = n * 1e6 / sample_rate µs, computed fresh from
// the integer sample count each time so no floating accumulation error can
// build up. Phase is continuous across the whole transmission (header +
// image) and never resets.

#ifndef SSTV_LIB_SSTV_OSC_H
#define SSTV_LIB_SSTV_OSC_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int sample_rate;
    float amplitude;
    float* out;
    int capacity;
    int pos;          // samples emitted so far (== integer time base)
    double phase;     // radians, wrapped
} sstv_osc_t;

void sstv_osc_init(sstv_osc_t* o, int sample_rate, float amplitude,
                   float* out, int capacity);

// Absolute time (µs) of the NEXT sample to be emitted.
double sstv_osc_time_us(const sstv_osc_t* o);

// Emit one sample of frequency freq_hz. Returns 0 or SSTV_ERR_CAPACITY.
int sstv_osc_emit(sstv_osc_t* o, double freq_hz);

// Emit a fixed tone until the next sample instant reaches end_us (absolute).
int sstv_osc_run_tone(sstv_osc_t* o, double freq_hz, double end_us);

// Emit a pixel scan: `width` values v[0..width-1] spread over
// [start_us, end_us); the frequency at instant t is a step function of the
// absolute time: idx = (t - start_us) / px_us.
int sstv_osc_run_scan(sstv_osc_t* o, const uint8_t* v, int width,
                      double start_us, double end_us);

#ifdef __cplusplus
}
#endif

#endif // SSTV_LIB_SSTV_OSC_H
