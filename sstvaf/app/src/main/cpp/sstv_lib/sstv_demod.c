// sstv_demod.c — quadrature FM discriminator. See sstv_demod.h.

#include "sstv_demod.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define SSTV_DEMOD_CENTER_HZ 1750.0
// Wide enough to track pixel-rate frequency steps (PD120 runs ~5.3 kpx/s;
// every mode's tones live in 1100..2300 => ±650 Hz around the NCO), narrow
// enough to reject the mixing image during pixel scans: the lowest in-scan
// tone is black at 1500 Hz, whose image lands at 1500 + 1750 = 3250 Hz, and
// with the 63-tap base length the Hamming transition is ~3.3/63*12000 ≈
// 630 Hz, putting the -53 dB stopband at ~2830 Hz. (Sync/VIS tones at
// 1100..1200 Hz have images at 2850..2950 Hz that only get partial
// rejection, but every decision made on those tones runs through the
// det-track moving average in sstv_decode.c, which absorbs the ripple.)
#define SSTV_DEMOD_CUTOFF_HZ 2200.0

int sstv_demod_init(sstv_demod_t* d, int sample_rate)
{
    memset(d, 0, sizeof(*d));
    if (sample_rate < 8000 || sample_rate > 192000) return -1;
    d->sample_rate = sample_rate;

    // ~63 taps at 12 kHz; scale with fs to keep the time response constant.
    int nt = (int)lround(63.0 * (double)sample_rate / 12000.0);
    if ((nt & 1) == 0) nt++;
    if (nt < 11) nt = 11;
    if (nt > 511) nt = 511;
    d->ntaps = nt;

    // Running median over ~417 µs (5 samples at 12 kHz), scaled with fs so
    // the discriminator's step-transient spikes are suppressed equally at
    // every rate.
    int nm = (int)lround((double)sample_rate / 2400.0);
    if ((nm & 1) == 0) nm++;
    if (nm < 5) nm = 5;
    if (nm > 41) nm = 41;
    d->med_len = nm;

    d->taps = (double*)malloc(sizeof(double) * (size_t)nt);
    d->di = (double*)calloc((size_t)nt, sizeof(double));
    d->dq = (double*)calloc((size_t)nt, sizeof(double));
    d->med = (double*)calloc((size_t)nm, sizeof(double));
    if (!d->taps || !d->di || !d->dq || !d->med) {
        sstv_demod_free(d);
        return -1;
    }

    // Blackman-windowed sinc lowpass, unity DC gain. Blackman rather than
    // Hamming: its step response barely overshoots, so sharp pixel edges
    // don't ring into their neighbors (visible as 2-3 px echoes around
    // color-bar edges with harder windows); the wider transition
    // (~5.5/N * fs ≈ 1050 Hz at the base length) still puts the stopband
    // below the lowest in-scan mixing image at 3250 Hz.
    int mid = nt / 2;
    double fc = SSTV_DEMOD_CUTOFF_HZ / (double)sample_rate;  // normalized
    double sum = 0.0;
    for (int i = 0; i < nt; i++) {
        double k = (double)(i - mid);
        double s = (k == 0.0) ? 2.0 * fc
                              : sin(2.0 * M_PI * fc * k) / (M_PI * k);
        double ph = 2.0 * M_PI * (double)i / (double)(nt - 1);
        double w = 0.42 - 0.5 * cos(ph) + 0.08 * cos(2.0 * ph);
        d->taps[i] = s * w;
        sum += d->taps[i];
    }
    for (int i = 0; i < nt; i++) d->taps[i] /= sum;

    d->nco_inc = 2.0 * M_PI * SSTV_DEMOD_CENTER_HZ / (double)sample_rate;
    d->prev_i = 1e-12;  // avoid atan2(0,0) on the first sample
    d->prev_q = 0.0;
    return 0;
}

void sstv_demod_free(sstv_demod_t* d)
{
    free(d->taps);
    free(d->di);
    free(d->dq);
    free(d->med);
    d->taps = 0;
    d->di = 0;
    d->dq = 0;
    d->med = 0;
}

static double median_of(const double* v, int n)
{
    double a[41];
    memcpy(a, v, (size_t)n * sizeof(double));
    for (int i = 1; i < n; i++) {
        double key = a[i];
        int j = i - 1;
        while (j >= 0 && a[j] > key) {
            a[j + 1] = a[j];
            j--;
        }
        a[j + 1] = key;
    }
    return a[n / 2];
}

double sstv_demod_process(sstv_demod_t* d, double x, double* mag_out)
{
    // Mix down: y = x * e^{-j*w0*n}.
    double c = cos(d->nco_phase), s = sin(d->nco_phase);
    d->nco_phase += d->nco_inc;
    if (d->nco_phase > 2.0 * M_PI) d->nco_phase -= 2.0 * M_PI;

    d->di[d->dpos] = x * c;
    d->dq[d->dpos] = -x * s;
    d->dpos = (d->dpos + 1) % d->ntaps;

    // FIR lowpass on I and Q (newest sample gets taps[0]).
    double zi = 0.0, zq = 0.0;
    int idx = d->dpos;  // oldest
    for (int i = d->ntaps - 1; i >= 0; i--) {
        zi += d->taps[i] * d->di[idx];
        zq += d->taps[i] * d->dq[idx];
        idx++;
        if (idx == d->ntaps) idx = 0;
    }

    if (mag_out) *mag_out = sqrt(zi * zi + zq * zq);

    // Discriminator: angle of z * conj(z_prev).
    double re = zi * d->prev_i + zq * d->prev_q;
    double im = zq * d->prev_i - zi * d->prev_q;
    d->prev_i = zi;
    d->prev_q = zq;

    double f = SSTV_DEMOD_CENTER_HZ;
    if (re != 0.0 || im != 0.0) {
        f += (double)d->sample_rate / (2.0 * M_PI) * atan2(im, re);
    }

    // Running median to kill impulsive clicks and step transients.
    int nm = d->med_len;
    if (d->med_fill < nm) {
        d->med[d->med_fill++] = f;
        for (int i = d->med_fill; i < nm; i++) d->med[i] = f;
    } else {
        memmove(d->med, d->med + 1, (size_t)(nm - 1) * sizeof(double));
        d->med[nm - 1] = f;
    }
    return median_of(d->med, nm);
}
