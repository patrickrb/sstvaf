// sstv_demod.h — FM demodulator: quadrature discriminator.
//
// NCO mix at 1750 Hz (band center), lowpass FIR on I/Q (tap count scaled
// with the sample rate), then
// f[n] = 1750 + fs/(2π)·atan2(Im(z·conj(z_prev)), Re(z·conj(z_prev))),
// followed by a running median (~400 µs — 5 taps at 12 kHz, scaled with the
// rate so discriminator transient spikes at frequency steps are suppressed
// identically at every fs). One output frequency estimate (plus an in-band
// magnitude) per input sample.

#ifndef SSTV_LIB_SSTV_DEMOD_H
#define SSTV_LIB_SSTV_DEMOD_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int sample_rate;
    int ntaps;
    double* taps;
    double* di;      // circular I delay line (ntaps)
    double* dq;      // circular Q delay line (ntaps)
    int dpos;
    double nco_phase;
    double nco_inc;  // radians/sample at 1750 Hz
    double prev_i, prev_q;
    double* med;     // running-median history (med_len newest values)
    int med_len;
    int med_fill;
} sstv_demod_t;

// Returns 0 on success, negative on alloc failure / bad rate.
int  sstv_demod_init(sstv_demod_t* d, int sample_rate);
void sstv_demod_free(sstv_demod_t* d);

// Process one sample; returns the median-filtered instantaneous frequency
// (Hz) and writes the post-FIR analytic magnitude to *mag_out (may be NULL).
double sstv_demod_process(sstv_demod_t* d, double x, double* mag_out);

#ifdef __cplusplus
}
#endif

#endif // SSTV_LIB_SSTV_DEMOD_H
