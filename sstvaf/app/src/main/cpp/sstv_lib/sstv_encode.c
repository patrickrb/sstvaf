// sstv_encode.c — SSTV encoder: header + VIS + image, whole-buffer output.
//
// Timing discipline: every segment ends at an ABSOLUTE double-µs target time
// accumulated across the whole transmission; the oscillator (sstv_osc)
// derives each sample's instant from the integer sample count, so nothing
// drifts no matter how fractional the per-line sample counts are. Phase is
// continuous from the first leader sample to the last pixel.

#include "sstv.h"
#include "sstv_modes.h"
#include "sstv_vis.h"
#include "sstv_osc.h"
#include "sstv_color.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

int sstv_encode_num_samples(int mode_id, int sample_rate)
{
    const sstv_mode_t* m = sstv_mode_get(mode_id);
    if (!m) return SSTV_ERR_BAD_MODE;
    if (sample_rate < 8000 || sample_rate > 192000) return SSTV_ERR_BAD_ARGS;
    // All mode timings are integer µs, so the total is exactly representable;
    // do the ceil in integer arithmetic: n = ceil(total_us * fs / 1e6).
    long long total_us = llround(sstv_mode_total_us(m));
    long long n = (total_us * (long long)sample_rate + 999999LL) / 1000000LL;
    return (int)n;
}

static uint8_t clamp_u8d(double v)
{
    if (v < 0.0) return 0;
    if (v > 255.0) return 255;
    return (uint8_t)lrint(v);
}

// Fill the per-frame component scan buffers (each m->width bytes) for frame
// k. `bufs` is indexed by sstv_component_t.
static void prep_frame(const sstv_mode_t* m, const uint32_t* argb, int k,
                       int swap_r36, uint8_t* bufs[SSTV_COMP_COUNT])
{
    int w = m->width;
    const uint32_t* rowa = argb + (size_t)k * (size_t)m->rows_per_frame * (size_t)w;

    switch (m->color) {
    case SSTV_COLOR_GBR:
        for (int x = 0; x < w; x++) {
            uint32_t p = rowa[x];
            bufs[SSTV_COMP_R][x] = (uint8_t)((p >> 16) & 0xFF);
            bufs[SSTV_COMP_G][x] = (uint8_t)((p >> 8) & 0xFF);
            bufs[SSTV_COMP_B][x] = (uint8_t)(p & 0xFF);
        }
        break;

    case SSTV_COLOR_YC_ALT: {
        int parity = (k & 1) ^ (swap_r36 ? 1 : 0);
        for (int x = 0; x < w; x++) {
            uint32_t p = rowa[x];
            double y, cr, cb;
            sstv_rgb_to_ycc((double)((p >> 16) & 0xFF),
                            (double)((p >> 8) & 0xFF),
                            (double)(p & 0xFF), &y, &cr, &cb);
            bufs[SSTV_COMP_Y][x] = clamp_u8d(y);
            bufs[SSTV_COMP_C_ALT][x] = clamp_u8d(parity == 0 ? cr : cb);
        }
        break;
    }

    case SSTV_COLOR_YC_422:
        for (int x = 0; x < w; x++) {
            uint32_t p = rowa[x];
            double y, cr, cb;
            sstv_rgb_to_ycc((double)((p >> 16) & 0xFF),
                            (double)((p >> 8) & 0xFF),
                            (double)(p & 0xFF), &y, &cr, &cb);
            bufs[SSTV_COMP_Y][x] = clamp_u8d(y);
            bufs[SSTV_COMP_CR][x] = clamp_u8d(cr);
            bufs[SSTV_COMP_CB][x] = clamp_u8d(cb);
        }
        break;

    case SSTV_COLOR_YC_PD: {
        const uint32_t* rowb = rowa + w;
        for (int x = 0; x < w; x++) {
            uint32_t pa = rowa[x], pb = rowb[x];
            double ya, cra, cba, yb, crb, cbb;
            sstv_rgb_to_ycc((double)((pa >> 16) & 0xFF),
                            (double)((pa >> 8) & 0xFF),
                            (double)(pa & 0xFF), &ya, &cra, &cba);
            sstv_rgb_to_ycc((double)((pb >> 16) & 0xFF),
                            (double)((pb >> 8) & 0xFF),
                            (double)(pb & 0xFF), &yb, &crb, &cbb);
            bufs[SSTV_COMP_Y_A][x] = clamp_u8d(ya);
            bufs[SSTV_COMP_Y_B][x] = clamp_u8d(yb);
            // PD chroma per frame is the average of the two rows' chroma.
            bufs[SSTV_COMP_CR][x] = clamp_u8d((cra + crb) * 0.5);
            bufs[SSTV_COMP_CB][x] = clamp_u8d((cba + cbb) * 0.5);
        }
        break;
    }
    }
}

int sstv_encode_ex(int mode_id, const uint32_t* argb, int width, int height,
                   int sample_rate, float amplitude, int flags,
                   float* out, int out_capacity)
{
    const sstv_mode_t* m = sstv_mode_get(mode_id);
    if (!m) return SSTV_ERR_BAD_MODE;
    if (!argb || !out) return SSTV_ERR_BAD_ARGS;
    if (width != m->width || height != m->height) return SSTV_ERR_BAD_ARGS;

    int need = sstv_encode_num_samples(mode_id, sample_rate);
    if (need < 0) return need;
    if (out_capacity < need) return SSTV_ERR_CAPACITY;

    int swap_r36 = (flags & SSTV_ENCODE_SWAP_ROBOT36_PARITY) ? 1 : 0;

    uint8_t* pool = (uint8_t*)malloc((size_t)SSTV_COMP_COUNT * (size_t)m->width);
    if (!pool) return SSTV_ERR_NOMEM;
    uint8_t* bufs[SSTV_COMP_COUNT];
    for (int i = 0; i < SSTV_COMP_COUNT; i++) bufs[i] = pool + (size_t)i * m->width;

    sstv_osc_t osc;
    sstv_osc_init(&osc, sample_rate, amplitude, out, out_capacity);

    double t_end = 0.0;
    int rc = 0;

    // ---- calibration header ------------------------------------------------
    t_end += SSTV_LEADER_US;
    rc |= sstv_osc_run_tone(&osc, SSTV_FREQ_LEADER, t_end);
    t_end += SSTV_BREAK_US;
    rc |= sstv_osc_run_tone(&osc, SSTV_FREQ_SYNC, t_end);
    t_end += SSTV_LEADER_US;
    rc |= sstv_osc_run_tone(&osc, SSTV_FREQ_LEADER, t_end);

    // ---- VIS: start + 7 data (LSB first) + even parity + stop --------------
    for (int cell = 0; cell < 10; cell++) {
        t_end += SSTV_VIS_BIT_US;
        rc |= sstv_osc_run_tone(&osc, sstv_vis_cell_freq(m->vis_code, cell), t_end);
    }

    // ---- Scottie's one-off starting sync ------------------------------------
    if (m->starting_sync_us > 0.0) {
        t_end += m->starting_sync_us;
        rc |= sstv_osc_run_tone(&osc, SSTV_FREQ_SYNC, t_end);
    }

    // ---- image frames --------------------------------------------------------
    int frames = m->height / m->rows_per_frame;
    for (int k = 0; k < frames && rc == 0; k++) {
        prep_frame(m, argb, k, swap_r36, bufs);
        int parity = (k & 1) ^ swap_r36;
        for (int si = 0; si < m->n_segments; si++) {
            const sstv_segment_t* seg = &m->segments[si];
            double seg_start = t_end;
            t_end += seg->dur_us;
            switch (seg->kind) {
            case SSTV_SEG_TONE:
                rc |= sstv_osc_run_tone(&osc, seg->freq_hz, t_end);
                break;
            case SSTV_SEG_TONE_EVENODD:
                rc |= sstv_osc_run_tone(&osc, parity == 0 ? seg->freq_hz
                                                          : seg->freq_hz_odd, t_end);
                break;
            case SSTV_SEG_SCAN:
                rc |= sstv_osc_run_scan(&osc, bufs[seg->component], m->width,
                                        seg_start, t_end);
                break;
            }
            if (rc) break;
        }
    }

    free(pool);
    if (rc) return rc;
    return osc.pos;
}

int sstv_encode(int mode_id, const uint32_t* argb, int width, int height,
                int sample_rate, float amplitude, float* out, int out_capacity)
{
    return sstv_encode_ex(mode_id, argb, width, height, sample_rate, amplitude,
                          0, out, out_capacity);
}
