// sstv_osc.c — phase-continuous absolute-time oscillator. See sstv_osc.h.

#include "sstv_osc.h"
#include "sstv.h"
#include "sstv_modes.h"

#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

void sstv_osc_init(sstv_osc_t* o, int sample_rate, float amplitude,
                   float* out, int capacity)
{
    o->sample_rate = sample_rate;
    o->amplitude = amplitude;
    o->out = out;
    o->capacity = capacity;
    o->pos = 0;
    o->phase = 0.0;
}

double sstv_osc_time_us(const sstv_osc_t* o)
{
    return (double)o->pos * 1e6 / (double)o->sample_rate;
}

int sstv_osc_emit(sstv_osc_t* o, double freq_hz)
{
    if (o->pos >= o->capacity) return SSTV_ERR_CAPACITY;
    o->out[o->pos++] = o->amplitude * (float)sin(o->phase);
    o->phase += 2.0 * M_PI * freq_hz / (double)o->sample_rate;
    if (o->phase > 2.0 * M_PI) o->phase -= 2.0 * M_PI;
    return 0;
}

int sstv_osc_run_tone(sstv_osc_t* o, double freq_hz, double end_us)
{
    while (sstv_osc_time_us(o) < end_us) {
        int rc = sstv_osc_emit(o, freq_hz);
        if (rc) return rc;
    }
    return 0;
}

int sstv_osc_run_scan(sstv_osc_t* o, const uint8_t* v, int width,
                      double start_us, double end_us)
{
    double px_us = (end_us - start_us) / (double)width;
    while (sstv_osc_time_us(o) < end_us) {
        double t = sstv_osc_time_us(o);
        int idx = (int)((t - start_us) / px_us);
        if (idx < 0) idx = 0;
        if (idx >= width) idx = width - 1;
        double f = SSTV_FREQ_BLACK + (double)v[idx] * SSTV_HZ_PER_UNIT;
        int rc = sstv_osc_emit(o, f);
        if (rc) return rc;
    }
    return 0;
}
