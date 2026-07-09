// sstv_decode.c — SSTV decoder: push-model state machine.
//
// Pipeline: FM demod (sstv_demod) -> per-sample frequency track kept in a
// ring buffer timestamped by absolute sample count -> state machine:
//
//   IDLE    sliding ~100 ms window; >=80% of samples near 1900 Hz => LEADER.
//           The window mean becomes f_cal; every downstream threshold is
//           shifted by (f_cal - 1900) — constant tuning-offset calibration.
//   LEADER  hunt for the VIS start bit: a >=25 ms run of f <= 1250 Hz.
//           (The 10 ms 1200 Hz break also forms a run but never reaches
//           25 ms, so it can't false-trigger.)
//   VIS     sample each 30 ms cell at its center ±5 ms with a majority vote
//           (< 1200 Hz => bit 1), LSB first; check even parity + stop bit;
//           unknown code or parity failure => back to IDLE.
//   IMAGE   per frame k predict the sync centroid from a least-squares
//           regression over the last 32 accepted syncs (slant estimate),
//           search ±max(2 ms, 3 px) for the best run of f < 1350 Hz of
//           width ≈ sync_us, take its centroid; reject outliers > 1.5 ms
//           from the fit; freewheel through missed syncs. Pixels are the
//           fraction-weighted mean frequency over each pixel window.
//   DONE    after the last row.
//   ABORTED > 5 line periods without an accepted sync AND the in-band
//           energy has collapsed (partial image retained).
//
// Robot 36 chroma pairing is keyed off the measured separator tone (1500 Hz
// => the following chroma is R-Y, 2300 Hz => B-Y), NOT off assumed row
// parity. PD frames emit two rows.

#include "sstv.h"
#include "sstv_modes.h"
#include "sstv_demod.h"
#include "sstv_color.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

// ---------------------------------------------------------------------------
// Tunables
// ---------------------------------------------------------------------------
#define LEADER_WIN_US        100000.0  // leader detection window
#define LEADER_TOL_HZ        350.0     // per-sample |f-1900| band (in-band gate)
#define LEADER_MEAN_TOL_HZ   120.0     // window-mean bound (covers ±80 Hz mistuning)
#define LEADER_FRac          0.80
#define LEADER_MAG_FRAC      0.15      // samples quieter than this vs the EMA
                                       // don't count (silence demods to 1750)
#define LEADER_TIMEOUT_US    2.0e6     // give up on LEADER after this long
#define DET_MA_US            1500.0    // detection-track moving average length
#define STARTBIT_WIN_US      25000.0   // start-bit detection window (>= 25 ms low)
// Trigger when the trailing-window mean has fallen 65% of the leader ->
// start-bit drop (1900 -> 1200). 65% keeps the decision on the linear part
// of the windowed ramp (deeper thresholds sit in the convolution knee and
// fire milliseconds late) while staying far above the dip the 10 ms header
// break can produce (a full break in the window only reaches 1900 - 280).
#define STARTBIT_FRAC        0.65
#define STARTBIT_HIGH_HZ     1600.0    // the window before must read as leader
#define STARTBIT_EDGE_FRAC   0.5       // refine t0 at the 50% edge crossing
#define STARTBIT_REFINE_US   5000.0    // refinement search span around coarse t0
#define VIS_CELL_US          30000.0
#define VIS_HALF_WIN_US      5000.0    // cell sampled at center ±5 ms
#define STOP_TOL_HZ          120.0
#define SYNC_THRESH_HZ       1350.0    // sync run threshold (pre-offset)
#define SYNC_OUTLIER_US      1500.0    // reject syncs farther than this from fit
#define SYNC_SEARCH_MIN_US   2000.0    // ± search window floor
#define REG_MAX              32        // regression history depth
#define MISS_LIMIT           5         // frames without sync before abort check
#define MAG_COLLAPSE_FRAC    0.15      // energy collapse threshold vs baseline
#define COH_MAG_FRAC         0.30      // "coherent" magnitude threshold

struct sstv_decoder {
    int sr;
    double usps;              // µs per sample
    sstv_demod_t dm;
    int dm_ok;

    // Demodulated frequency rings, absolute sample count n. `ring` is the
    // raw (median-filtered) track used for pixel reads; `det` is the same
    // track through a short moving average (~DET_MA_US), stored at its
    // center index so both rings share timestamps — leader/VIS/sync
    // decisions read `det`, pixels read `ring`. `gate` flags det samples
    // loud enough to count toward leader detection.
    float* ring;
    float* det;
    uint8_t* gate;
    int ring_size;
    long long n;
    int ma_len;               // moving-average length, samples
    int ma_delay;             // (ma_len - 1) / 2
    double ma_sum;            // running sum of the trailing ma_len raw values

    // In-band magnitude tracking.
    double mag_ema;
    double mag_alpha;
    double mag_base;

    int status;
    const sstv_mode_t* mode;
    double f_off;             // tuning offset (f_cal - 1900), pre-VIS states

    // Two-point frequency calibration, fixed at VIS time: the measured
    // leader anchors 1900 Hz and the measured start bit anchors 1200 Hz.
    // Noise compresses the discriminator's scale toward the 1750 Hz band
    // center (a plain offset can't model that), so downstream thresholds
    // and the pixel mapping all go through cal0/cal_slope.
    double cal0;              // measured frequency of a nominal 1200 Hz
    double cal_slope;         // measured Hz per nominal Hz

    // IDLE: sliding leader window (maintained continuously): count and sum
    // of loud in-band (1900 ± LEADER_TOL_HZ) det samples over the trailing
    // lead_w samples.
    int lead_w;               // window length, samples
    int lead_cnt;             // gated in-range count over the window
    double lead_sum;          // sum of gated in-range freqs over the window

    // LEADER: start-bit hunt via two sliding STARTBIT_WIN_US means over the
    // det track: [cur-2W, cur-W) must look like leader while [cur-W, cur]
    // has fallen to the 1200 Hz start bit.
    long long leader_at;
    int sb_w;                 // STARTBIT_WIN_US in samples
    double sb_sum_a;          // trailing window sum
    double sb_sum_b;          // window-before sum

    // VIS.
    long long vis_t0;         // start-bit leading edge (sample index)
    long long wait_n;         // resume the state machine at this sample count

    // IMAGE.
    double t_img;             // µs: frame-0 base (after starting sync)
    int frame_idx, n_frames;
    double T_nom;             // nominal frame duration µs
    double A, B;              // sync centroid fit: c(k) = A + B*k
    double reg_k[REG_MAX], reg_c[REG_MAX];
    int reg_n, reg_pos;
    int miss_streak;
    long long sync_attempts, sync_hits;
    long long coh_tot, coh_good;

    uint32_t* image;
    int rows_done;

    // Per-frame component scan buffers, indexed by sstv_component_t.
    uint8_t* comp_pool;
    uint8_t* comp[SSTV_COMP_COUNT];

    // Robot 36 pairing (slot 0 = even frame of the pair).
    uint8_t* r36_y0;
    uint8_t* r36_c0;
    int r36_type0;            // SSTV_COMP_CR or SSTV_COMP_CB
    int r36_have0;
};

// ---------------------------------------------------------------------------
// Ring access helpers
// ---------------------------------------------------------------------------
static double getf(const sstv_decoder_t* d, long long i)
{
    return (double)d->ring[i % (long long)d->ring_size];
}

static double getd(const sstv_decoder_t* d, long long i)
{
    return (double)d->det[i % (long long)d->ring_size];
}

static double t_of(const sstv_decoder_t* d, long long i)
{
    return (double)i * d->usps;
}

// Newest valid det-track index + 1 (the det track lags by ma_delay).
static long long det_end(const sstv_decoder_t* d)
{
    long long e = d->n - (long long)d->ma_delay;
    return (e > 0) ? e : 0;
}

// Expected measured frequency of a nominal tone, through the two-point
// calibration (identity until the VIS fixes it).
static double map_hz(const sstv_decoder_t* d, double f_nom)
{
    return d->cal0 + (f_nom - SSTV_FREQ_SYNC) * d->cal_slope;
}

// Fraction-weighted mean frequency over [a_us, b_us) (sample i covers
// [i, i+1) sample-times). raw != 0 reads the pixel track, else the det track.
static double win_mean_track(const sstv_decoder_t* d, double a_us, double b_us,
                             int raw)
{
    double fa = a_us / d->usps, fb = b_us / d->usps;
    long long ia = (long long)floor(fa), ib = (long long)floor(fb);
    long long lo = d->n - (long long)d->ring_size + 1;
    long long hi = raw ? d->n : det_end(d);
    if (ia < 0) ia = 0;
    if (ia < lo) ia = lo;
    double sum = 0.0, wsum = 0.0;
    for (long long i = ia; i <= ib && i < hi; i++) {
        double w0 = (double)i, w1 = (double)i + 1.0;
        if (w0 < fa) w0 = fa;
        if (w1 > fb) w1 = fb;
        double w = w1 - w0;
        if (w <= 0.0) continue;
        sum += w * (raw ? getf(d, i) : getd(d, i));
        wsum += w;
    }
    return (wsum > 0.0) ? sum / wsum : 0.0;
}

static double win_mean(const sstv_decoder_t* d, double a_us, double b_us)
{
    return win_mean_track(d, a_us, b_us, 1);
}

static double win_mean_det(const sstv_decoder_t* d, double a_us, double b_us)
{
    return win_mean_track(d, a_us, b_us, 0);
}

// Majority count of det-track samples with f < thr over [a_us, b_us).
static void count_below(const sstv_decoder_t* d, double a_us, double b_us,
                        double thr, int* below, int* total)
{
    long long ia = (long long)ceil(a_us / d->usps);
    long long ib = (long long)floor(b_us / d->usps);
    long long lo = d->n - (long long)d->ring_size + 1;
    long long hi = det_end(d);
    if (ia < 0) ia = 0;
    if (ia < lo) ia = lo;
    int b = 0, t = 0;
    for (long long i = ia; i <= ib && i < hi; i++) {
        t++;
        if (getd(d, i) < thr) b++;
    }
    *below = b;
    *total = t;
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------
static void free_image_state(sstv_decoder_t* d)
{
    free(d->image);
    d->image = 0;
    free(d->comp_pool);
    d->comp_pool = 0;
    free(d->r36_y0);
    d->r36_y0 = 0;
    free(d->r36_c0);
    d->r36_c0 = 0;
}

sstv_decoder_t* sstv_decoder_create(int sample_rate)
{
    if (sample_rate < 8000 || sample_rate > 192000) return 0;
    sstv_decoder_t* d = (sstv_decoder_t*)calloc(1, sizeof(*d));
    if (!d) return 0;
    d->sr = sample_rate;
    d->usps = 1e6 / (double)sample_rate;

    if (sstv_demod_init(&d->dm, sample_rate) != 0) {
        free(d);
        return 0;
    }
    d->dm_ok = 1;

    // Ring: 2x the slowest frame (PD 90: 703040 µs) plus slack — enough
    // lookback for a full frame decode, the VIS cells and the leader window.
    double ring_us = 2.0 * 703040.0 + 200000.0;
    d->ring_size = (int)(ring_us / d->usps) + 16;
    d->ring = (float*)calloc((size_t)d->ring_size, sizeof(float));
    d->det = (float*)calloc((size_t)d->ring_size, sizeof(float));
    d->gate = (uint8_t*)calloc((size_t)d->ring_size, sizeof(uint8_t));
    if (!d->ring || !d->det || !d->gate) {
        free(d->ring);
        free(d->det);
        free(d->gate);
        sstv_demod_free(&d->dm);
        free(d);
        return 0;
    }

    d->ma_len = (int)lround(DET_MA_US / d->usps);
    if (d->ma_len < 1) d->ma_len = 1;
    d->ma_delay = (d->ma_len - 1) / 2;
    d->lead_w = (int)(LEADER_WIN_US / d->usps);
    d->sb_w = (int)(STARTBIT_WIN_US / d->usps);
    d->mag_alpha = d->usps / 50000.0;  // ~50 ms EMA
    d->cal0 = SSTV_FREQ_SYNC;
    d->cal_slope = 1.0;
    d->status = SSTV_STATUS_IDLE;
    return d;
}

void sstv_decoder_reset(sstv_decoder_t* d)
{
    if (!d) return;
    free_image_state(d);
    sstv_demod_free(&d->dm);
    sstv_demod_init(&d->dm, d->sr);
    memset(d->ring, 0, (size_t)d->ring_size * sizeof(float));
    memset(d->det, 0, (size_t)d->ring_size * sizeof(float));
    memset(d->gate, 0, (size_t)d->ring_size * sizeof(uint8_t));
    d->n = 0;
    d->ma_sum = 0.0;
    d->mag_ema = d->mag_base = 0.0;
    d->status = SSTV_STATUS_IDLE;
    d->mode = 0;
    d->f_off = 0.0;
    d->cal0 = SSTV_FREQ_SYNC;
    d->cal_slope = 1.0;
    d->lead_cnt = 0;
    d->lead_sum = 0.0;
    d->sb_sum_a = d->sb_sum_b = 0.0;
    d->wait_n = 0;
    d->frame_idx = d->n_frames = 0;
    d->reg_n = d->reg_pos = 0;
    d->A = d->B = d->T_nom = 0.0;
    d->miss_streak = 0;
    d->sync_attempts = d->sync_hits = 0;
    d->coh_tot = d->coh_good = 0;
    d->rows_done = 0;
    d->r36_have0 = 0;
}

void sstv_decoder_destroy(sstv_decoder_t* d)
{
    if (!d) return;
    free_image_state(d);
    if (d->dm_ok) sstv_demod_free(&d->dm);
    free(d->ring);
    free(d->det);
    free(d->gate);
    free(d);
}

// ---------------------------------------------------------------------------
// Regression over accepted sync centroids: c(k) = A + B*k.
// ---------------------------------------------------------------------------
static void reg_add(sstv_decoder_t* d, int k, double c)
{
    d->reg_k[d->reg_pos] = (double)k;
    d->reg_c[d->reg_pos] = c;
    d->reg_pos = (d->reg_pos + 1) % REG_MAX;
    if (d->reg_n < REG_MAX) d->reg_n++;

    if (d->reg_n == 1) {
        d->A = c - d->B * (double)k;  // keep the current slope
        return;
    }
    double mk = 0.0, mc = 0.0;
    for (int i = 0; i < d->reg_n; i++) {
        mk += d->reg_k[i];
        mc += d->reg_c[i];
    }
    mk /= d->reg_n;
    mc /= d->reg_n;
    double num = 0.0, den = 0.0;
    for (int i = 0; i < d->reg_n; i++) {
        double dk = d->reg_k[i] - mk;
        num += dk * (d->reg_c[i] - mc);
        den += dk * dk;
    }
    if (den > 0.0) {
        d->B = num / den;
        d->A = mc - d->B * mk;
    }
}

// ---------------------------------------------------------------------------
// Sync pulse search: best run of f < 1350+off within [a_us, b_us), of width
// ≈ want_us. Returns 1 and the pulse's effective centroid (µs) on success.
//
// The centroid is anchored on the run's TRAILING edge (minus want_us/2), not
// on the run's sample centroid: the sync's trailing edge is always the fixed
// 1200 -> 1500 Hz porch transition (every mode), whose 1350 Hz midpoint the
// threshold crosses symmetrically — while the LEADING edge descends from
// whatever the previous scan's last pixel was (Scottie's B scan, Robot/PD's
// chroma/luma scans), so a bright pixel there would bias a plain centroid by
// a large content-dependent fraction of the demod transition time.
//
// The run itself is found on the noise-averaged det track, but the edge
// instant is then refined on the raw track: the det moving average is wider
// than Martin's 572 µs porch, so a det-track crossing would leak the first
// scan pixels into the timing; the raw track's transition (~FIR rise +
// median) fits inside every mode's porch.
// ---------------------------------------------------------------------------
static int find_sync(const sstv_decoder_t* d, double a_us, double b_us,
                     double want_us, double* c_out)
{
    long long ia = (long long)ceil(a_us / d->usps);
    long long ib = (long long)floor(b_us / d->usps);
    long long lo = d->n - (long long)d->ring_size + 1;
    long long hi = det_end(d);
    if (ia < 0) ia = 0;
    if (ia < lo) ia = lo;
    if (ib >= hi) ib = hi - 1;

    double thr = map_hz(d, SYNC_THRESH_HZ);
    int in = 0;
    long long cnt = 0;
    double best_err = 1e18, best_c = 0.0;
    int found = 0;

    for (long long i = ia; i <= ib + 1; i++) {
        int below = (i <= ib) && (getd(d, i) < thr);
        if (below) {
            if (!in) {
                in = 1;
                cnt = 0;
            }
            cnt++;
        } else if (in) {
            in = 0;
            double width = (double)cnt * d->usps;
            if (width >= 0.5 * want_us && width <= 1.5 * want_us) {
                double err = fabs(width - want_us);
                if (err < best_err) {
                    // Refine on the raw track: find the upward crossing of
                    // thr nearest the det-track run end (det index i).
                    long long j0 = i - (long long)d->ma_len - 4;
                    long long j1 = i + (long long)d->ma_len + 4;
                    if (j0 < lo) j0 = lo;
                    if (j1 >= d->n) j1 = d->n - 1;
                    double best_dist = 1e18, edge = -1.0;
                    for (long long j = j0; j < j1; j++) {
                        double f0 = getf(d, j), f1 = getf(d, j + 1);
                        if (f0 < thr && f1 >= thr) {
                            double fr = (thr - f0) / (f1 - f0);
                            double cand = ((double)j + 0.5 + fr) * d->usps;
                            double dist = fabs(cand - (double)i * d->usps);
                            if (dist < best_dist) {
                                best_dist = dist;
                                edge = cand;
                            }
                        }
                    }
                    if (edge >= 0.0) {
                        best_err = err;
                        best_c = edge - want_us * 0.5;
                        found = 1;
                    }
                }
            }
        }
    }
    if (found) *c_out = best_c;
    return found;
}

// ---------------------------------------------------------------------------
// Frame pixel decode + row emission
// ---------------------------------------------------------------------------
static uint8_t freq_to_val(const sstv_decoder_t* d, double f)
{
    double v = (f - map_hz(d, SSTV_FREQ_BLACK))
               / (SSTV_HZ_PER_UNIT * d->cal_slope);
    if (v < 0.0) v = 0.0;
    if (v > 255.0) v = 255.0;
    return (uint8_t)lrint(v);
}

static void read_scan(const sstv_decoder_t* d, double start_us, double dur_us,
                      int width, uint8_t* dst)
{
    // Sample the central 50% of each pixel window: by the pixel's center the
    // demod filter's step response has settled, so this bleeds far less of
    // each neighbor into the value than averaging the full period would.
    double px = dur_us / (double)width;
    for (int x = 0; x < width; x++) {
        double a = start_us + (double)x * px;
        dst[x] = freq_to_val(d, win_mean(d, a + 0.25 * px, a + 0.75 * px));
    }
}

static uint32_t pack_argb(uint8_t r, uint8_t g, uint8_t b)
{
    return 0xFF000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | (uint32_t)b;
}

static void emit_ycc_row(sstv_decoder_t* d, int row, const uint8_t* y,
                         const uint8_t* cr, const uint8_t* cb)
{
    const sstv_mode_t* m = d->mode;
    uint32_t* out = d->image + (size_t)row * m->width;
    for (int x = 0; x < m->width; x++) {
        uint8_t r, g, b;
        sstv_ycc_to_rgb((double)y[x],
                        cr ? (double)cr[x] : 128.0,
                        cb ? (double)cb[x] : 128.0, &r, &g, &b);
        out[x] = pack_argb(r, g, b);
    }
}

static void emit_frame_rows(sstv_decoder_t* d, int sep_low)
{
    const sstv_mode_t* m = d->mode;
    int w = m->width;
    int k = d->frame_idx;

    switch (m->color) {
    case SSTV_COLOR_GBR: {
        uint32_t* out = d->image + (size_t)k * w;
        for (int x = 0; x < w; x++) {
            out[x] = pack_argb(d->comp[SSTV_COMP_R][x], d->comp[SSTV_COMP_G][x],
                               d->comp[SSTV_COMP_B][x]);
        }
        d->rows_done = k + 1;
        break;
    }

    case SSTV_COLOR_YC_422:
        emit_ycc_row(d, k, d->comp[SSTV_COMP_Y], d->comp[SSTV_COMP_CR],
                     d->comp[SSTV_COMP_CB]);
        d->rows_done = k + 1;
        break;

    case SSTV_COLOR_YC_ALT: {
        // sep_low: 1500 Hz separator => this frame's chroma is R-Y.
        // Fall back to nominal parity if the separator was unreadable.
        int type = (sep_low >= 0) ? (sep_low ? SSTV_COMP_CR : SSTV_COMP_CB)
                                  : ((k & 1) == 0 ? SSTV_COMP_CR : SSTV_COMP_CB);
        if (!d->r36_have0) {
            memcpy(d->r36_y0, d->comp[SSTV_COMP_Y], (size_t)w);
            memcpy(d->r36_c0, d->comp[SSTV_COMP_C_ALT], (size_t)w);
            d->r36_type0 = type;
            d->r36_have0 = 1;
        } else {
            const uint8_t* cr = 0;
            const uint8_t* cb = 0;
            if (d->r36_type0 == SSTV_COMP_CR) cr = d->r36_c0;
            else cb = d->r36_c0;
            if (type == SSTV_COMP_CR) cr = d->comp[SSTV_COMP_C_ALT];
            else cb = d->comp[SSTV_COMP_C_ALT];
            emit_ycc_row(d, k - 1, d->r36_y0, cr, cb);
            emit_ycc_row(d, k, d->comp[SSTV_COMP_Y], cr, cb);
            d->r36_have0 = 0;
            d->rows_done = k + 1;
        }
        break;
    }

    case SSTV_COLOR_YC_PD:
        emit_ycc_row(d, 2 * k, d->comp[SSTV_COMP_Y_A], d->comp[SSTV_COMP_CR],
                     d->comp[SSTV_COMP_CB]);
        emit_ycc_row(d, 2 * k + 1, d->comp[SSTV_COMP_Y_B], d->comp[SSTV_COMP_CR],
                     d->comp[SSTV_COMP_CB]);
        d->rows_done = 2 * k + 2;
        break;
    }
}

static void decode_frame(sstv_decoder_t* d)
{
    const sstv_mode_t* m = d->mode;
    double s = d->B / d->T_nom;  // slant scale
    double F = (d->A + d->B * (double)d->frame_idx)
               - (m->sync_offset_us + m->sync_us * 0.5) * s;
    double o = 0.0;
    int sep_low = -1;

    for (int si = 0; si < m->n_segments; si++) {
        const sstv_segment_t* seg = &m->segments[si];
        double du = seg->dur_us * s;
        switch (seg->kind) {
        case SSTV_SEG_TONE:
            break;
        case SSTV_SEG_TONE_EVENODD: {
            // Measure the separator over its central half; 1500 vs 2300 Hz
            // split at 1900.
            double mf = win_mean_det(d, F + o + 0.25 * du, F + o + 0.75 * du);
            sep_low = (mf < map_hz(d, SSTV_FREQ_LEADER)) ? 1 : 0;
            break;
        }
        case SSTV_SEG_SCAN:
            read_scan(d, F + o, du, m->width, d->comp[seg->component]);
            break;
        }
        o += du;
    }

    emit_frame_rows(d, sep_low);
    d->frame_idx++;
}

// Largest pixel duration among the mode's scan segments (µs).
static double max_scan_px_us(const sstv_mode_t* m)
{
    double best = 0.0;
    for (int i = 0; i < m->n_segments; i++) {
        if (m->segments[i].kind != SSTV_SEG_SCAN) continue;
        double px = m->segments[i].dur_us / (double)m->width;
        if (px > best) best = px;
    }
    return best;
}

// ---------------------------------------------------------------------------
// IMAGE state: sync-track + decode as many frames as the data allows.
// ---------------------------------------------------------------------------
static void image_step(sstv_decoder_t* d)
{
    const sstv_mode_t* m = d->mode;
    for (;;) {
        if (d->frame_idx >= d->n_frames) {
            d->status = SSTV_STATUS_DONE;
            return;
        }
        double s = d->B / d->T_nom;
        double predc = d->A + d->B * (double)d->frame_idx;
        double wnd = SYNC_SEARCH_MIN_US;
        double px3 = 3.0 * max_scan_px_us(m) * s;
        if (px3 > wnd) wnd = px3;
        double half_sync = m->sync_us * s * 0.5;
        double r0 = predc - half_sync - wnd;
        double r1 = predc + half_sync + wnd;
        double F = predc - (m->sync_offset_us + m->sync_us * 0.5) * s;
        double need = F + d->T_nom * s;
        if (r1 > need) need = r1;
        need += 2000.0;

        if (t_of(d, d->n) <= need) {
            d->wait_n = (long long)(need / d->usps) + 2;
            return;
        }

        d->sync_attempts++;
        double cm;
        if (find_sync(d, r0, r1, m->sync_us * s, &cm) &&
            fabs(cm - predc) <= SYNC_OUTLIER_US) {
            reg_add(d, d->frame_idx, cm);
            d->sync_hits++;
            d->miss_streak = 0;
        } else {
            d->miss_streak++;
        }

        decode_frame(d);

        // Coherence bookkeeping at frame granularity.
        d->coh_tot++;
        if (d->mag_ema > COH_MAG_FRAC * d->mag_base) d->coh_good++;
    }
}

// ---------------------------------------------------------------------------
// VIS evaluation
// ---------------------------------------------------------------------------
static void vis_reject(sstv_decoder_t* d)
{
    d->status = SSTV_STATUS_IDLE;
}

static void vis_evaluate(sstv_decoder_t* d)
{
    double t0 = t_of(d, d->vis_t0);
    int code = 0, ones = 0;

    // Fix the two-point calibration: the leader mean anchored 1900 Hz
    // (f_off); the start bit's own body anchors 1200 Hz. Under noise the
    // discriminator compresses every reading toward the 1750 Hz band
    // center, so the gain matters as much as the offset.
    double m_l = SSTV_FREQ_LEADER + d->f_off;
    double m_s = win_mean_det(d, t0 + 5000.0, t0 + 25000.0);
    double slope = (m_l - m_s) / (SSTV_FREQ_LEADER - SSTV_FREQ_SYNC);
    if (slope < 0.7 || slope > 1.3) {
        // Not a credible start bit level.
        vis_reject(d);
        return;
    }
    d->cal0 = m_s;
    d->cal_slope = slope;

    // 1100/1300 Hz sit symmetrically around 1200, so the calibrated split
    // is exactly the measured start-bit level.
    double split = m_s;

    for (int c = 0; c < 9; c++) {  // 7 data + parity + stop
        double center = t0 + (double)(c + 1) * VIS_CELL_US + VIS_CELL_US * 0.5;
        double a = center - VIS_HALF_WIN_US, b = center + VIS_HALF_WIN_US;
        if (c == 8) {
            // Stop bit: mean must sit near the calibrated 1200 Hz level.
            double mf = win_mean_det(d, a, b);
            if (fabs(mf - m_s) > STOP_TOL_HZ) {
                vis_reject(d);
                return;
            }
            break;
        }
        // Decide the bit from the ±5 ms center window: mean below the
        // 1200 Hz split => 1. (A per-sample majority vote is noisier than
        // the mean here: the tones sit only ±100 Hz from the split, and the
        // det-track samples are correlated, so individual samples flip in
        // packs while the window mean stays put.) The majority count still
        // gates degenerate cases where the window is empty.
        int below, total;
        count_below(d, a, b, split, &below, &total);
        if (total <= 0) {
            vis_reject(d);
            return;
        }
        int bit = (win_mean_det(d, a, b) < split) ? 1 : 0;
        if (c < 7) {
            code |= bit << c;
        }
        ones += bit;
    }

    if (ones & 1) {  // even parity over data+parity bits
        vis_reject(d);
        return;
    }
    const sstv_mode_t* m = sstv_mode_by_vis(code);
    if (!m) {
        vis_reject(d);
        return;
    }

    // Accept.
    d->mode = m;
    free_image_state(d);
    d->image = (uint32_t*)calloc((size_t)m->width * (size_t)m->height,
                                 sizeof(uint32_t));
    d->comp_pool = (uint8_t*)malloc((size_t)SSTV_COMP_COUNT * (size_t)m->width);
    d->r36_y0 = (uint8_t*)malloc((size_t)m->width);
    d->r36_c0 = (uint8_t*)malloc((size_t)m->width);
    if (!d->image || !d->comp_pool || !d->r36_y0 || !d->r36_c0) {
        free_image_state(d);
        d->mode = 0;
        vis_reject(d);
        return;
    }
    for (int i = 0; i < SSTV_COMP_COUNT; i++) {
        d->comp[i] = d->comp_pool + (size_t)i * m->width;
    }

    d->rows_done = 0;
    d->r36_have0 = 0;
    d->n_frames = m->height / m->rows_per_frame;
    d->frame_idx = 0;
    d->T_nom = m->line_us;
    d->t_img = t0 + 10.0 * VIS_CELL_US + m->starting_sync_us;
    d->B = d->T_nom;
    d->A = d->t_img + m->sync_offset_us + m->sync_us * 0.5;
    d->reg_n = 0;
    d->reg_pos = 0;
    d->miss_streak = 0;
    d->sync_attempts = d->sync_hits = 0;
    d->coh_tot = d->coh_good = 0;
    d->mag_base = d->mag_ema;
    d->status = SSTV_STATUS_IMAGE;
    d->wait_n = d->n;  // image_step computes its own needs
}

// ---------------------------------------------------------------------------
// Push
// ---------------------------------------------------------------------------
// Append a raw (median-filtered) frequency sample; derive the det-track
// sample (moving average, stored at its center index) and keep the sliding
// leader-window and start-bit-window statistics current on the det track.
// `mag` is the demod in-band magnitude of this input sample, used to gate
// silence out of the leader statistics (pure silence demodulates to a rock
// steady 1750 Hz, which must not look like a carrier).
static void append_sample(sstv_decoder_t* d, double f, double mag)
{
    long long rs = (long long)d->ring_size;
    d->ring[d->n % rs] = (float)f;

    // Trailing moving average of ma_len raw values ending at n.
    d->ma_sum += f;
    if (d->n >= (long long)d->ma_len) {
        d->ma_sum -= getf(d, d->n - d->ma_len);
    }
    long long have = (d->n + 1 < (long long)d->ma_len) ? d->n + 1
                                                       : (long long)d->ma_len;
    double ma = d->ma_sum / (double)have;

    // Store at the center index: det[n - ma_delay] describes that instant.
    long long dc = d->n - (long long)d->ma_delay;
    if (dc >= 0) {
        d->det[dc % rs] = (float)ma;
        // The absolute floor matters: digital silence has mag == mag_ema ==
        // 0, which would pass a purely relative gate — and silence
        // demodulates to a steady in-band 1750 Hz.
        int inc = (mag > 1e-6) && (mag >= LEADER_MAG_FRAC * d->mag_ema) &&
                  (fabs(ma - SSTV_FREQ_LEADER) <= LEADER_TOL_HZ);
        d->gate[dc % rs] = (uint8_t)inc;

        // Sliding leader window over the det track (gated samples only).
        if (dc >= (long long)d->lead_w) {
            long long old_i = dc - d->lead_w;
            if (d->gate[old_i % rs]) {
                d->lead_cnt--;
                d->lead_sum -= getd(d, old_i);
            }
        }
        if (inc) {
            d->lead_cnt++;
            d->lead_sum += ma;
        }

        // Sliding start-bit windows.
        d->sb_sum_a += ma;
        if (dc >= (long long)d->sb_w) {
            double leaving = getd(d, dc - d->sb_w);
            d->sb_sum_a -= leaving;
            d->sb_sum_b += leaving;
            if (dc >= 2LL * d->sb_w) {
                d->sb_sum_b -= getd(d, dc - 2LL * d->sb_w);
            }
        }
    }
    d->n++;
}

void sstv_decoder_push(sstv_decoder_t* d, const float* samples, int n)
{
    if (!d || !samples || n <= 0) return;
    if (d->status == SSTV_STATUS_DONE || d->status == SSTV_STATUS_ABORTED) return;

    for (int i = 0; i < n; i++) {
        double mag;
        double f = sstv_demod_process(&d->dm, (double)samples[i], &mag);
        d->mag_ema += d->mag_alpha * (mag - d->mag_ema);
        append_sample(d, f, mag);

        // The state machine advances on the det track, which lags by
        // ma_delay samples.
        long long cur = d->n - 1 - (long long)d->ma_delay;
        if (cur < 0) continue;

        switch (d->status) {
        case SSTV_STATUS_IDLE:
            // Leader: >=80% of the 100 ms window is loud in-band samples
            // AND their mean sits within the mistuning budget of 1900 Hz.
            if (cur >= d->lead_w &&
                d->lead_cnt >= (int)(LEADER_FRac * (double)d->lead_w) &&
                fabs(d->lead_sum / (double)d->lead_cnt - SSTV_FREQ_LEADER)
                    <= LEADER_MEAN_TOL_HZ) {
                d->f_off = d->lead_sum / (double)d->lead_cnt - SSTV_FREQ_LEADER;
                d->status = SSTV_STATUS_LEADER;
                d->leader_at = cur;
            }
            break;

        case SSTV_STATUS_LEADER: {
            // Start bit: trailing 25 ms mean has fallen STARTBIT_FRAC of the
            // leader -> start-bit drop while the 25 ms before still reads as
            // leader. The trailing mean crosses that level when the start
            // bit fills the same fraction of the window, which puts the
            // bit's leading edge STARTBIT_FRAC * window back from `cur`.
            double drop = SSTV_FREQ_LEADER - SSTV_FREQ_SYNC;  // 700 Hz
            double trig = SSTV_FREQ_LEADER + d->f_off - STARTBIT_FRAC * drop;
            if (cur >= 2LL * d->sb_w &&
                d->sb_sum_b / (double)d->sb_w > STARTBIT_HIGH_HZ + d->f_off &&
                d->sb_sum_a / (double)d->sb_w < trig) {
                long long t0 = cur - (long long)((double)d->sb_w * STARTBIT_FRAC);
                // Refine on the det track: the downward crossing of the 50%
                // level near the coarse estimate is steep and symmetric.
                double mid = SSTV_FREQ_LEADER + d->f_off
                             - STARTBIT_EDGE_FRAC * drop;
                long long span = (long long)(STARTBIT_REFINE_US / d->usps);
                for (long long j = t0 - span; j < t0 + span; j++) {
                    if (j < 1) continue;
                    if (getd(d, j - 1) >= mid && getd(d, j) < mid) {
                        t0 = j;
                        break;
                    }
                }
                d->status = SSTV_STATUS_VIS;
                d->vis_t0 = t0;
                // All ten 30 ms cells plus margin.
                d->wait_n = d->vis_t0 + (long long)(310000.0 / d->usps) + 1;
            }
            if (d->status == SSTV_STATUS_LEADER &&
                (double)(cur - d->leader_at) * d->usps > LEADER_TIMEOUT_US) {
                d->status = SSTV_STATUS_IDLE;
            }
            break;
        }

        case SSTV_STATUS_VIS:
            if (cur >= d->wait_n) vis_evaluate(d);
            break;

        case SSTV_STATUS_IMAGE:
            if (cur >= d->wait_n) image_step(d);
            if (d->status == SSTV_STATUS_IMAGE &&
                d->miss_streak > MISS_LIMIT &&
                d->mag_ema < MAG_COLLAPSE_FRAC * d->mag_base) {
                d->status = SSTV_STATUS_ABORTED;
            }
            break;

        default:
            break;
        }

        if (d->status == SSTV_STATUS_DONE || d->status == SSTV_STATUS_ABORTED) {
            return;
        }
    }
}

// ---------------------------------------------------------------------------
// Getters
// ---------------------------------------------------------------------------
int sstv_decoder_status(const sstv_decoder_t* d)
{
    return d ? d->status : SSTV_STATUS_IDLE;
}

int sstv_decoder_mode(const sstv_decoder_t* d)
{
    return (d && d->mode) ? d->mode->id : -1;
}

int sstv_decoder_rows_ready(const sstv_decoder_t* d)
{
    return d ? d->rows_done : 0;
}

int sstv_decoder_read_rows(const sstv_decoder_t* d, int first_row, int n_rows,
                           uint32_t* argb)
{
    if (!d || !argb || first_row < 0 || n_rows < 0) return SSTV_ERR_BAD_ARGS;
    if (!d->mode || !d->image) return SSTV_ERR_BAD_MODE;
    int avail = d->rows_done - first_row;
    if (avail <= 0) return 0;
    int ncopy = (n_rows < avail) ? n_rows : avail;
    memcpy(argb, d->image + (size_t)first_row * d->mode->width,
           (size_t)ncopy * (size_t)d->mode->width * sizeof(uint32_t));
    return ncopy;
}

float sstv_decoder_slant_ppm(const sstv_decoder_t* d)
{
    if (!d || !d->mode || d->T_nom <= 0.0 || d->reg_n < 2) return 0.0f;
    return (float)((d->B / d->T_nom - 1.0) * 1e6);
}

float sstv_decoder_quality(const sstv_decoder_t* d)
{
    if (!d || d->sync_attempts <= 0) return 0.0f;
    double hit = (double)d->sync_hits / (double)d->sync_attempts;
    double coh = (d->coh_tot > 0) ? (double)d->coh_good / (double)d->coh_tot : 0.0;
    double q = 0.5 * hit + 0.5 * coh;
    if (q < 0.0) q = 0.0;
    if (q > 1.0) q = 1.0;
    return (float)q;
}
