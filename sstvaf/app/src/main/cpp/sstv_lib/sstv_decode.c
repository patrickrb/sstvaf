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
//           energy has collapsed (partial image retained), OR a new
//           calibration header was decoded underneath the image (VIS
//           preemption, below).
//
// Real-signal robustness (all learned from off-air recordings):
//
//   * Sync re-acquisition. A dropped capture buffer, a sender glitch or a
//     YouTube edit shifts every following sync by a constant offset; the
//     ±few-ms prediction window then misses forever and the image shears.
//     On every miss the whole line is searched; SYNC_REACQ_FRAMES
//     consecutive off-grid syncs at a consistent offset re-anchor the fit
//     (keeping its slope history) and the affected frames are re-decoded
//     from the ring.
//   * Young-fit window. Until REG_YOUNG syncs are in the regression the
//     slope is still the nominal line period, so the search window is at
//     least SYNC_YOUNG_FRAC of a line: a 1-2 % sound-card clock error is
//     common and the VIS-to-first-sync gap varies between encoders.
//   * Header-less lock. While hunting, every sync-shaped pulse (3-26 ms of
//     f < 1350 Hz) is logged; HL_NEED_LINES consecutive pulses at one
//     mode's line period (and sync width) lock that mode with no VIS at
//     all. This is how a transmission joined mid-image (or one whose VIS
//     was unreadable) still decodes; rows start at the top of the frame.
//   * VIS preemption. The header hunt keeps running underneath IMAGE (a
//     "shadow" copy of the IDLE/LEADER/VIS machine with a strict profile
//     image content cannot imitate). A valid header ends the current image
//     as ABORTED; the following sstv_decoder_reset() enters IMAGE for the
//     new mode immediately, timed off that header, so nothing of the new
//     picture is lost. For that reason reset() keeps the demodulated audio
//     history and only rewinds the state machine.
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
#define LEADER_MEAN_TOL_HZ   200.0     // window-mean bound (mistuning budget)
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
#define REG_YOUNG            4         // fit is "young" below this many syncs
#define SYNC_YOUNG_FRAC      0.02      // young-fit search half-width, of a line
#define SYNC_REACQ_FRAMES    3         // consistent off-grid syncs to re-anchor
#define SYNC_REACQ_TOL_US    1500.0    // agreement between those offsets
#define SYNC_GAP_US          2000.0    // noise gap bridged inside one sync pulse
#define HL_MAX_PULSES        64        // header-less lock: pulse history depth
#define HL_MIN_WIDTH_US      3000.0    // narrowest sync accepted (Martin 4.86 ms)
#define HL_MAX_WIDTH_US      26000.0   // widest (PD 20 ms); VIS cells are 30 ms
#define HL_NEED_LINES        4         // consecutive line periods to lock
#define HL_TOL_US            1200.0    // per-line timing agreement (+ slant term)
#define HL_SLANT_TOL         0.02      // tolerated line-period deviation
#define HL_MAX_UNEXPLAINED   1         // stray sync-width pulses allowed in span
#define HDR_STRICT_LEADER_US 180000.0  // shadow hunt: leader heard this long
                                       // before the start bit (a squelch
                                       // that opens on the second 300 ms
                                       // leader still gives ~200 ms)
#define VIS_STRICT_TOL_HZ    80.0      // shadow hunt: data cells this close to 1100/1300

// Header hunt sub-machine (IDLE -> LEADER -> VIS). One instance is the main
// state machine while hunting; a second ("shadow") instance runs the same
// hunt underneath IMAGE for VIS preemption.
typedef struct {
    int st;                   // SSTV_STATUS_IDLE / LEADER / VIS
    double f_off;             // tuning offset (leader mean - 1900)
    long long leader_at;      // det index where the leader was recognised
    long long vis_t0;         // start-bit leading edge (det index)
    long long wait_n;         // VIS cells complete at this det index
} hdr_t;

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
    int forced_mode;          // operator mode lock (SSTV_MODE_*), -1 = auto
    int vis_locked;           // 1: image timed off a decoded VIS; 0: sync lock

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
    int sb_w;                 // STARTBIT_WIN_US in samples
    double sb_sum_a;          // trailing window sum
    double sb_sum_b;          // window-before sum

    // Header hunts: `hdr` is the live IDLE/LEADER/VIS machine, `shadow`
    // the copy that runs underneath IMAGE.
    hdr_t hdr, shadow;

    // VIS accepted by the shadow hunt, applied by sstv_decoder_reset().
    int pend_valid;
    const sstv_mode_t* pend_mode;
    double pend_t0, pend_cal0, pend_slope;

    // Header-less lock: sync-shaped pulses seen while hunting (raw-track
    // trailing-edge time µs, det-track width µs, body frequency Hz), newest
    // at hl_pos-1.
    double hl_te[HL_MAX_PULSES], hl_w[HL_MAX_PULSES], hl_f[HL_MAX_PULSES];
    int hl_n, hl_pos;
    int hl_in;                // inside a below-threshold run
    long long hl_start;       // det index where that run began
    long long hl_above;       // consecutive above-threshold samples in it

    long long wait_n;         // IMAGE: resume image_step at this det index

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

    // Sync re-acquisition: off-grid syncs found by the whole-line search
    // on consecutive misses (centroid µs, frame index).
    double reacq_c[SYNC_REACQ_FRAMES];
    int reacq_k[SYNC_REACQ_FRAMES];
    int reacq_n;
    int fit_refined;          // 0: nominal slope; 1: 2-point; 2: REG_YOUNG-point

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
static void hdr_init(hdr_t* h);
static void hl_clear(sstv_decoder_t* d);
static void enter_image_vis(sstv_decoder_t* d, const sstv_mode_t* m, double t0,
                            double cal0, double slope);

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

    // Ring: 6 s — the header plus five of the slowest frames (Scottie DX:
    // 1050 ms), so a whole frame plus its search windows is always
    // readable, and the frames decoded against an immature or stale fit
    // (the first REG_YOUNG lines after a lock; the lines before a phase
    // re-anchor) can still be re-decoded for every mode.
    double ring_us = 6000000.0;
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
    d->forced_mode = -1;
    hdr_init(&d->hdr);
    hdr_init(&d->shadow);
    return d;
}

void sstv_decoder_reset(sstv_decoder_t* d)
{
    if (!d) return;
    free_image_state(d);
    // The demodulated history — rings, moving-average / leader-window /
    // start-bit sums, magnitude EMA, demod filter state — is a view of the
    // audio, not decode state, and is deliberately kept: a VIS accepted by
    // the shadow hunt during the previous image anchors the next image in
    // that history. Only the state machine is rewound.
    d->status = SSTV_STATUS_IDLE;
    d->mode = 0;
    d->vis_locked = 0;
    d->cal0 = SSTV_FREQ_SYNC;
    d->cal_slope = 1.0;
    d->wait_n = 0;
    d->frame_idx = d->n_frames = 0;
    d->reg_n = d->reg_pos = 0;
    d->A = d->B = d->T_nom = 0.0;
    d->miss_streak = 0;
    d->reacq_n = 0;
    d->sync_attempts = d->sync_hits = 0;
    d->coh_tot = d->coh_good = 0;
    d->rows_done = 0;
    d->r36_have0 = 0;
    hdr_init(&d->hdr);
    hdr_init(&d->shadow);
    hl_clear(d);
    // d->forced_mode is deliberately NOT cleared: the mode lock is an
    // operator setting that must survive the between-frames reset the RX
    // engine issues after every decode.

    if (d->pend_valid) {
        // The previous image was preempted by a new calibration header:
        // decode the new transmission from its first line.
        d->pend_valid = 0;
        enter_image_vis(d, d->pend_mode, d->pend_t0, d->pend_cal0, d->pend_slope);
    }
}

int sstv_decoder_set_forced_mode(sstv_decoder_t* d, int mode_id)
{
    if (!d) return SSTV_ERR_BAD_ARGS;
    if (mode_id != -1 && !sstv_mode_get(mode_id)) return SSTV_ERR_BAD_ARGS;
    d->forced_mode = mode_id;
    return 0;
}

int sstv_decoder_forced_mode(const sstv_decoder_t* d)
{
    return d ? d->forced_mode : -1;
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
// Raw-track refinement of a det-track run end: the upward crossing of thr
// nearest det index i_end, as an absolute time in µs (-1 if none).
static double refine_edge(const sstv_decoder_t* d, long long i_end, double thr)
{
    long long lo = d->n - (long long)d->ring_size + 1;
    long long j0 = i_end - (long long)d->ma_len - 4;
    long long j1 = i_end + (long long)d->ma_len + 4;
    if (j0 < lo) j0 = lo;
    if (j0 < 0) j0 = 0;
    if (j1 >= d->n) j1 = d->n - 1;
    double best_dist = 1e18, edge = -1.0;
    for (long long j = j0; j < j1; j++) {
        double f0 = getf(d, j), f1 = getf(d, j + 1);
        if (f0 < thr && f1 >= thr) {
            double fr = (thr - f0) / (f1 - f0);
            double cand = ((double)j + 0.5 + fr) * d->usps;
            double dist = fabs(cand - (double)i_end * d->usps);
            if (dist < best_dist) {
                best_dist = dist;
                edge = cand;
            }
        }
    }
    return edge;
}

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
    long long gap_n = (long long)(SYNC_GAP_US / d->usps);
    int in = 0;
    long long run_start = 0, above = 0;
    double best_err = 1e18, best_c = 0.0;
    int found = 0;

    for (long long i = ia; i <= ib + 1; i++) {
        int below = (i <= ib) && (getd(d, i) < thr);
        if (below) {
            if (!in) {
                in = 1;
                run_start = i;
            }
            above = 0;
            continue;
        }
        if (!in) continue;
        // Noise splits a weak pulse into pieces: bridge gaps shorter than
        // SYNC_GAP_US (nothing in a line legitimately returns below the
        // threshold that soon after a sync).
        above++;
        if (i <= ib && above < gap_n) continue;
        long long end = i - above + 1;  // first above-threshold sample
        in = 0;
        double width = (double)(end - run_start) * d->usps;
        if (width >= 0.5 * want_us && width <= 1.5 * want_us) {
            double err = fabs(width - want_us);
            if (err < best_err) {
                double edge = refine_edge(d, end, thr);
                if (edge >= 0.0) {
                    best_err = err;
                    best_c = edge - want_us * 0.5;
                    found = 1;
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

static void bump_rows(sstv_decoder_t* d, int rows)
{
    if (rows > d->rows_done) d->rows_done = rows;
}

static void emit_frame_rows(sstv_decoder_t* d, int k, int sep_low)
{
    const sstv_mode_t* m = d->mode;
    int w = m->width;

    switch (m->color) {
    case SSTV_COLOR_GBR: {
        uint32_t* out = d->image + (size_t)k * w;
        for (int x = 0; x < w; x++) {
            out[x] = pack_argb(d->comp[SSTV_COMP_R][x], d->comp[SSTV_COMP_G][x],
                               d->comp[SSTV_COMP_B][x]);
        }
        bump_rows(d, k + 1);
        break;
    }

    case SSTV_COLOR_YC_422:
        emit_ycc_row(d, k, d->comp[SSTV_COMP_Y], d->comp[SSTV_COMP_CR],
                     d->comp[SSTV_COMP_CB]);
        bump_rows(d, k + 1);
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
            bump_rows(d, k + 1);
        }
        break;
    }

    case SSTV_COLOR_YC_PD:
        emit_ycc_row(d, 2 * k, d->comp[SSTV_COMP_Y_A], d->comp[SSTV_COMP_CR],
                     d->comp[SSTV_COMP_CB]);
        emit_ycc_row(d, 2 * k + 1, d->comp[SSTV_COMP_Y_B], d->comp[SSTV_COMP_CR],
                     d->comp[SSTV_COMP_CB]);
        bump_rows(d, 2 * k + 2);
        break;
    }
}

// Frame start (µs) of frame k under the current sync fit.
static double frame_start_us(const sstv_decoder_t* d, int k)
{
    const sstv_mode_t* m = d->mode;
    double s = d->B / d->T_nom;
    return (d->A + d->B * (double)k) - (m->sync_offset_us + m->sync_us * 0.5) * s;
}

static void decode_frame(sstv_decoder_t* d, int k)
{
    const sstv_mode_t* m = d->mode;
    double s = d->B / d->T_nom;  // slant scale
    double F = frame_start_us(d, k);
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

    emit_frame_rows(d, k, sep_low);
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

// Oldest instant (µs) still fully readable from both rings, with margin
// for the det track's moving-average lag and the raw-edge refinement.
static double ring_oldest_us(const sstv_decoder_t* d)
{
    long long lo = d->n - (long long)d->ring_size + 1 + (long long)d->ma_len + 8;
    if (lo < 0) lo = 0;
    return t_of(d, lo);
}

// After a phase re-anchor, frames [k0, k1) were decoded against the stale
// fit; re-decode the ones whose audio is still in the ring. Robot 36 pairs
// rows by frame index ((0,1), (2,3), ...), so its range starts on an even
// frame and the pair slot is rebuilt — the slot state at k1 then matches
// what the original pass left.
static void redecode_frames(sstv_decoder_t* d, int k0, int k1)
{
    int step = (d->mode->color == SSTV_COLOR_YC_ALT) ? 2 : 1;
    if (k0 < 0) k0 = 0;
    if (step == 2) k0 &= ~1;
    while (k0 < k1 && frame_start_us(d, k0) - 2000.0 < ring_oldest_us(d)) k0 += step;
    if (k0 >= k1) return;
    if (step == 2) d->r36_have0 = 0;
    for (int k = k0; k < k1; k++) decode_frame(d, k);
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
        int k = d->frame_idx;
        double s = d->B / d->T_nom;
        double T = d->T_nom * s;
        double predc = d->A + d->B * (double)k;

        // Narrow (tracking) window: ±max(2 ms, 3 px); while the fit is
        // young, at least ±SYNC_YOUNG_FRAC of a line (slope still nominal,
        // first-sync gap encoder-dependent). The outlier gate widens with it.
        int young = d->reg_n < REG_YOUNG;
        double wnd = SYNC_SEARCH_MIN_US;
        double px3 = 3.0 * max_scan_px_us(m) * s;
        if (px3 > wnd) wnd = px3;
        if (young && SYNC_YOUNG_FRAC * T > wnd) wnd = SYNC_YOUNG_FRAC * T;
        double tol = SYNC_OUTLIER_US;
        if (young && wnd > tol) tol = wnd;
        double half_sync = m->sync_us * s * 0.5;
        double r0 = predc - half_sync - wnd;
        double r1 = predc + half_sync + wnd;
        // Wide (re-acquisition) window: the whole line around the prediction.
        double w0 = predc - 0.5 * T;
        double w1 = predc + 0.5 * T;

        double F = frame_start_us(d, k);
        double need = F + T;
        if (r1 > need) need = r1;
        if (w1 > need) need = w1;
        need += 2000.0;

        if (t_of(d, d->n) <= need) {
            d->wait_n = (long long)(need / d->usps) + 2;
            return;
        }

        d->sync_attempts++;
        double cm;
        if (find_sync(d, r0, r1, m->sync_us * s, &cm) && fabs(cm - predc) <= tol) {
            reg_add(d, k, cm);
            d->sync_hits++;
            d->miss_streak = 0;
            d->reacq_n = 0;
            // The first frames are decoded before the slope is known (a VIS
            // lock starts from the nominal line period; with a 0.8 % clock
            // error a 700 ms PD frame then drifts 5 ms end to end — a
            // visibly skewed first row pair). Once two syncs give a slope,
            // and again once REG_YOUNG firm it up, re-decode the frames
            // the ring still holds against the better fit.
            if (d->fit_refined == 0 && d->reg_n >= 2) {
                d->fit_refined = 1;
                redecode_frames(d, 0, k);
            } else if (d->fit_refined == 1 && d->reg_n >= REG_YOUNG) {
                d->fit_refined = 2;
                redecode_frames(d, 0, k);
            }
        } else {
            d->miss_streak++;
            // The sync may have jumped phase (audio dropout, dropped capture
            // buffer, sender glitch): look across the whole line, and once
            // SYNC_REACQ_FRAMES consecutive syncs sit at one consistent
            // offset from the prediction, move the fit onto them.
            double cw;
            if (find_sync(d, w0, w1, m->sync_us * s, &cw)) {
                double off = cw - predc;
                if (d->reacq_n > 0) {
                    int pk = d->reacq_k[d->reacq_n - 1];
                    double poff = d->reacq_c[d->reacq_n - 1] - (d->A + d->B * (double)pk);
                    if (fabs(off - poff) > SYNC_REACQ_TOL_US) d->reacq_n = 0;
                }
                if (d->reacq_n < SYNC_REACQ_FRAMES) {
                    d->reacq_c[d->reacq_n] = cw;
                    d->reacq_k[d->reacq_n] = k;
                    d->reacq_n++;
                }
                if (d->reacq_n >= SYNC_REACQ_FRAMES) {
                    double shift = 0.0;
                    for (int i = 0; i < d->reacq_n; i++) {
                        shift += d->reacq_c[i] - (d->A + d->B * (double)d->reacq_k[i]);
                    }
                    shift /= (double)d->reacq_n;
                    // Carry the slope history across the jump: every stored
                    // centroid moves onto the new phase, then the off-grid
                    // syncs join the regression as ordinary hits.
                    for (int i = 0; i < d->reg_n; i++) d->reg_c[i] += shift;
                    d->A += shift;
                    for (int i = 0; i < d->reacq_n; i++) {
                        reg_add(d, d->reacq_k[i], d->reacq_c[i]);
                    }
                    d->sync_hits += d->reacq_n;
                    d->miss_streak = 0;
                    redecode_frames(d, d->reacq_k[0], k);
                    d->reacq_n = 0;
                }
            } else {
                d->reacq_n = 0;
            }
        }

        decode_frame(d, k);
        d->frame_idx++;

        // Coherence bookkeeping at frame granularity.
        d->coh_tot++;
        if (d->mag_ema > COH_MAG_FRAC * d->mag_base) d->coh_good++;
    }
}

// ---------------------------------------------------------------------------
// Entering IMAGE
// ---------------------------------------------------------------------------
static void hdr_init(hdr_t* h)
{
    h->st = SSTV_STATUS_IDLE;
    h->f_off = 0.0;
    h->leader_at = h->vis_t0 = h->wait_n = 0;
}

static void hl_clear(sstv_decoder_t* d)
{
    d->hl_n = d->hl_pos = 0;
    d->hl_in = 0;
}

// Allocate the frame buffers for `m` and reset every per-image tracker;
// the caller then sets the calibration and the sync fit. Returns 0 (and
// leaves the decoder hunting) on allocation failure.
static int enter_image(sstv_decoder_t* d, const sstv_mode_t* m)
{
    free_image_state(d);
    d->mode = m;
    d->image = (uint32_t*)calloc((size_t)m->width * (size_t)m->height,
                                 sizeof(uint32_t));
    d->comp_pool = (uint8_t*)malloc((size_t)SSTV_COMP_COUNT * (size_t)m->width);
    d->r36_y0 = (uint8_t*)malloc((size_t)m->width);
    d->r36_c0 = (uint8_t*)malloc((size_t)m->width);
    if (!d->image || !d->comp_pool || !d->r36_y0 || !d->r36_c0) {
        free_image_state(d);
        d->mode = 0;
        d->status = SSTV_STATUS_IDLE;
        return 0;
    }
    for (int i = 0; i < SSTV_COMP_COUNT; i++) {
        d->comp[i] = d->comp_pool + (size_t)i * m->width;
    }

    d->rows_done = 0;
    d->r36_have0 = 0;
    d->n_frames = m->height / m->rows_per_frame;
    d->frame_idx = 0;
    d->T_nom = m->line_us;
    d->reg_n = 0;
    d->reg_pos = 0;
    d->miss_streak = 0;
    d->reacq_n = 0;
    d->fit_refined = 0;
    d->sync_attempts = d->sync_hits = 0;
    d->coh_tot = d->coh_good = 0;
    d->mag_base = d->mag_ema;
    d->status = SSTV_STATUS_IMAGE;
    d->wait_n = d->n;  // image_step computes its own needs
    hl_clear(d);
    hdr_init(&d->hdr);
    hdr_init(&d->shadow);
    return 1;
}

// Accept `m` from a decoded VIS: anchor the line timing at the end of the
// stop bit (t0 is the start-bit leading edge, µs) with the header's
// two-point calibration.
static void enter_image_vis(sstv_decoder_t* d, const sstv_mode_t* m, double t0,
                            double cal0, double slope)
{
    if (!enter_image(d, m)) return;
    d->cal0 = cal0;
    d->cal_slope = slope;
    d->vis_locked = 1;
    d->t_img = t0 + 10.0 * VIS_CELL_US + m->starting_sync_us;
    d->B = d->T_nom;
    d->A = d->t_img + m->sync_offset_us + m->sync_us * 0.5;
}

// ---------------------------------------------------------------------------
// VIS evaluation
// ---------------------------------------------------------------------------
// Read the VIS cells of the header `h` found. Returns the mode (plus the
// start-bit time and the two-point calibration through the out-params), or
// NULL to reject.
//
// `strict` is the shadow-hunt profile used to preempt a running image.
// Image content can imitate a leader (any ~mid-gray stretch) and a start
// bit (a sync pulse), so a preemption additionally demands a leader that
// was heard for HDR_STRICT_LEADER_US before the start bit, a readable stop
// bit even under a forced mode, and data cells that sit within
// VIS_STRICT_TOL_HZ of a clean 1100/1300 Hz tone — pixels never drop below
// the 1500 Hz black level for a 30 ms cell at a time.
static const sstv_mode_t* vis_evaluate(const sstv_decoder_t* d, const hdr_t* h,
                                       int strict, double* t0_out,
                                       double* cal0_out, double* slope_out)
{
    double t0 = t_of(d, h->vis_t0);
    int code = 0, ones = 0;
    int forced = d->forced_mode >= 0;

    if (strict &&
        (double)(h->vis_t0 - h->leader_at) * d->usps < HDR_STRICT_LEADER_US) {
        return 0;
    }

    // Fix the two-point calibration: the leader anchors 1900 Hz and the
    // start bit's own body anchors 1200 Hz. Under noise the discriminator
    // compresses every reading toward the 1750 Hz band center, so the gain
    // matters as much as the offset. The leader level is taken from the
    // 120 ms right before the start bit (the tail of the second 300 ms
    // leader) rather than from where the hunt first recognised a leader:
    // the shadow hunt in particular may have "recognised" a mid-gray
    // stretch of picture a second earlier, whose mean is not 1900 Hz. That
    // stretch must itself read as a leader, or this is not a header.
    double m_l = win_mean_det(d, t0 - 150000.0, t0 - 30000.0);
    if (fabs(m_l - SSTV_FREQ_LEADER) > LEADER_MEAN_TOL_HZ) return 0;
    double m_s = win_mean_det(d, t0 + 5000.0, t0 + 25000.0);
    double slope = (m_l - m_s) / (SSTV_FREQ_LEADER - SSTV_FREQ_SYNC);
    if (slope < 0.7 || slope > 1.3) {
        if (!forced || strict) {
            // Not a credible start bit level.
            return 0;
        }
        // Locked mode: the operator asserts a transmission is here, so a
        // start bit too garbled to calibrate on falls back to the plain
        // leader offset instead of rejecting the header.
        m_s = SSTV_FREQ_SYNC + (m_l - SSTV_FREQ_LEADER);
        slope = 1.0;
    }
    *t0_out = t0;
    *cal0_out = m_s;
    *slope_out = slope;

    // Stop bit: mean must sit near the calibrated 1200 Hz level.
    double stop_center = t0 + 9.0 * VIS_CELL_US + VIS_CELL_US * 0.5;
    double stop_mf = win_mean_det(d, stop_center - VIS_HALF_WIN_US,
                                  stop_center + VIS_HALF_WIN_US);
    int stop_ok = fabs(stop_mf - m_s) <= STOP_TOL_HZ;

    // The operator's mode lock bypasses the VIS payload entirely — the
    // header found above still anchors timing and calibration, but the
    // data/parity cells (the parts QRM garbles first) are not read.
    if (forced) {
        if (strict && !stop_ok) return 0;
        return sstv_mode_get(d->forced_mode);
    }
    if (!stop_ok) return 0;

    // 1100/1300 Hz sit symmetrically around 1200, so the calibrated split
    // is exactly the measured start-bit level.
    double split = m_s;

    for (int c = 0; c < 8; c++) {  // 7 data + parity
        double center = t0 + (double)(c + 1) * VIS_CELL_US + VIS_CELL_US * 0.5;
        double a = center - VIS_HALF_WIN_US, b = center + VIS_HALF_WIN_US;
        // Decide the bit from the ±5 ms center window: mean below the
        // 1200 Hz split => 1. (A per-sample majority vote is noisier than
        // the mean here: the tones sit only ±100 Hz from the split, and the
        // det-track samples are correlated, so individual samples flip in
        // packs while the window mean stays put.) The majority count still
        // gates degenerate cases where the window is empty.
        int below, total;
        count_below(d, a, b, split, &below, &total);
        if (total <= 0) return 0;
        double mf = win_mean_det(d, a, b);
        int bit = (mf < split) ? 1 : 0;
        if (strict) {
            double want = split + (bit ? -100.0 : 100.0) * slope;
            if (fabs(mf - want) > VIS_STRICT_TOL_HZ) return 0;
        }
        if (c < 7) {
            code |= bit << c;
        }
        ones += bit;
    }

    if (ones & 1) return 0;  // even parity over data+parity bits
    return sstv_mode_by_vis(code);
}

// ---------------------------------------------------------------------------
// Header hunt: one det-track step of IDLE -> LEADER -> VIS. Returns 1 when
// the machine sits in VIS with every cell available (evaluate now).
// ---------------------------------------------------------------------------
static int hdr_step(const sstv_decoder_t* d, hdr_t* h, long long cur)
{
    switch (h->st) {
    case SSTV_STATUS_IDLE:
        // Leader: >=80% of the 100 ms window is loud in-band samples
        // AND their mean sits within the mistuning budget of 1900 Hz.
        if (cur >= d->lead_w &&
            d->lead_cnt >= (int)(LEADER_FRac * (double)d->lead_w) &&
            fabs(d->lead_sum / (double)d->lead_cnt - SSTV_FREQ_LEADER)
                <= LEADER_MEAN_TOL_HZ) {
            h->f_off = d->lead_sum / (double)d->lead_cnt - SSTV_FREQ_LEADER;
            h->st = SSTV_STATUS_LEADER;
            h->leader_at = cur;
        }
        return 0;

    case SSTV_STATUS_LEADER: {
        // Start bit: trailing 25 ms mean has fallen STARTBIT_FRAC of the
        // leader -> start-bit drop while the 25 ms before still reads as
        // leader. The trailing mean crosses that level when the start
        // bit fills the same fraction of the window, which puts the
        // bit's leading edge STARTBIT_FRAC * window back from `cur`.
        double drop = SSTV_FREQ_LEADER - SSTV_FREQ_SYNC;  // 700 Hz
        double trig = SSTV_FREQ_LEADER + h->f_off - STARTBIT_FRAC * drop;
        if (cur >= 2LL * d->sb_w &&
            d->sb_sum_b / (double)d->sb_w > STARTBIT_HIGH_HZ + h->f_off &&
            d->sb_sum_a / (double)d->sb_w < trig) {
            long long t0 = cur - (long long)((double)d->sb_w * STARTBIT_FRAC);
            // Refine on the det track: the downward crossing of the 50%
            // level near the coarse estimate is steep and symmetric.
            double mid = SSTV_FREQ_LEADER + h->f_off - STARTBIT_EDGE_FRAC * drop;
            long long span = (long long)(STARTBIT_REFINE_US / d->usps);
            for (long long j = t0 - span; j < t0 + span; j++) {
                if (j < 1) continue;
                if (getd(d, j - 1) >= mid && getd(d, j) < mid) {
                    t0 = j;
                    break;
                }
            }
            h->st = SSTV_STATUS_VIS;
            h->vis_t0 = t0;
            // All ten 30 ms cells plus margin.
            h->wait_n = t0 + (long long)(310000.0 / d->usps) + 1;
            return 0;
        }
        if ((double)(cur - h->leader_at) * d->usps > LEADER_TIMEOUT_US) {
            h->st = SSTV_STATUS_IDLE;
        }
        return 0;
    }

    case SSTV_STATUS_VIS:
        return cur >= h->wait_n;

    default:
        return 0;
    }
}

// ---------------------------------------------------------------------------
// Header-less lock: sync-train detection while hunting.
// ---------------------------------------------------------------------------
static void hl_push_pulse(sstv_decoder_t* d, double te, double w, double f)
{
    d->hl_te[d->hl_pos] = te;
    d->hl_w[d->hl_pos] = w;
    d->hl_f[d->hl_pos] = f;
    d->hl_pos = (d->hl_pos + 1) % HL_MAX_PULSES;
    if (d->hl_n < HL_MAX_PULSES) d->hl_n++;
}

// Index of the i-th most recent pulse (0 = newest).
static int hl_idx(const sstv_decoder_t* d, int i)
{
    return (d->hl_pos - 1 - i + HL_MAX_PULSES) % HL_MAX_PULSES;
}

// Does a measured pulse width fit a mode's sync? Robot/Scottie (9 ms),
// Martin (4.86 ms) and PD (20 ms) stay separable; the det-track moving
// average shaves a little off real pulses.
static int hl_width_ok(double w, double want)
{
    double tol = 0.35 * want;
    if (tol < 1500.0) tol = 1500.0;
    return fabs(w - want) <= tol;
}

// Test mode `m` against the pulse history ending at the newest pulse: one
// sync-width pulse at each of the last HL_NEED_LINES line periods (±HL_TOL_US
// plus a slant allowance), no more than HL_MAX_UNEXPLAINED stray sync-width
// pulses inside that span (a Robot 72 hypothesis over a Robot 36 train
// leaves every other pulse unexplained), and a fitted period within
// HL_SLANT_TOL of nominal. Fills idx[0..HL_NEED_LINES] (newest first) and
// the fitted period; returns 1 on a match.
static int hl_match(const sstv_decoder_t* d, const sstv_mode_t* m, int* idx,
                    double* period_out)
{
    double T = m->line_us;
    int i0 = hl_idx(d, 0);
    if (!hl_width_ok(d->hl_w[i0], m->sync_us)) return 0;
    double te0 = d->hl_te[i0];
    idx[0] = i0;
    for (int k = 1; k <= HL_NEED_LINES; k++) {
        double want = te0 - (double)k * T;
        double tol = HL_TOL_US + (double)k * T * HL_SLANT_TOL;
        int best = -1;
        double best_err = 1e18;
        for (int i = 1; i < d->hl_n; i++) {
            int ii = hl_idx(d, i);
            if (d->hl_te[ii] < want - tol) break;  // history is newest-first
            double e = fabs(d->hl_te[ii] - want);
            if (e <= tol && e < best_err && hl_width_ok(d->hl_w[ii], m->sync_us)) {
                best = ii;
                best_err = e;
            }
        }
        if (best < 0) return 0;
        idx[k] = best;
    }
    double span0 = te0 - (double)HL_NEED_LINES * T
                   - (HL_TOL_US + (double)HL_NEED_LINES * T * HL_SLANT_TOL);
    int unexplained = 0;
    for (int i = 1; i < d->hl_n; i++) {
        int ii = hl_idx(d, i);
        if (d->hl_te[ii] < span0) break;
        int used = 0;
        for (int k = 0; k <= HL_NEED_LINES; k++) {
            if (idx[k] == ii) used = 1;
        }
        if (!used && hl_width_ok(d->hl_w[ii], m->sync_us)) unexplained++;
    }
    if (unexplained > HL_MAX_UNEXPLAINED) return 0;
    double period = (te0 - d->hl_te[idx[HL_NEED_LINES]]) / (double)HL_NEED_LINES;
    if (fabs(period / T - 1.0) > HL_SLANT_TOL) return 0;
    *period_out = period;
    return 1;
}

// Try to lock a mode onto the pulse train. Candidates are tried in
// preference order; among matches the shortest line period wins (it
// explains the most pulses), and for identical timings (Martin 1/3,
// Martin 2/4) the earlier, more common entry wins. A forced mode restricts
// the search to that mode.
static void hl_try_lock(sstv_decoder_t* d)
{
    static const int kPref[] = {
        SSTV_MODE_ROBOT36,  SSTV_MODE_ROBOT72, SSTV_MODE_MARTIN1,
        SSTV_MODE_MARTIN2,  SSTV_MODE_SCOTTIE1, SSTV_MODE_SCOTTIE2,
        SSTV_MODE_PD50,     SSTV_MODE_PD90,    SSTV_MODE_PD120,
        SSTV_MODE_PD160,    SSTV_MODE_PD180,   SSTV_MODE_PD240,
        SSTV_MODE_PD290,    SSTV_MODE_SCOTTIEDX, SSTV_MODE_MARTIN3,
        SSTV_MODE_MARTIN4,
    };
    if (d->hl_n < HL_NEED_LINES + 1) return;

    const sstv_mode_t* best = 0;
    int best_idx[HL_NEED_LINES + 1];
    double best_T = 0.0;
    for (int p = 0; p < (int)(sizeof(kPref) / sizeof(kPref[0])); p++) {
        if (d->forced_mode >= 0 && kPref[p] != d->forced_mode) continue;
        const sstv_mode_t* m = sstv_mode_get(kPref[p]);
        int idx[HL_NEED_LINES + 1];
        double per;
        if (!m || !hl_match(d, m, idx, &per)) continue;
        if (!best || m->line_us < best->line_us - 1.0) {
            best = m;
            memcpy(best_idx, idx, sizeof(idx));
            best_T = per;
        }
    }
    if (!best) return;

    // Calibration: the sync bodies anchor 1200 Hz; with no 1900 Hz
    // reference the slope stays at unity (plain tuning offset).
    double f_sync = 0.0;
    for (int k = 0; k <= HL_NEED_LINES; k++) f_sync += d->hl_f[best_idx[k]];
    f_sync /= (double)(HL_NEED_LINES + 1);

    if (!enter_image(d, best)) return;
    d->cal0 = f_sync;
    d->cal_slope = 1.0;
    d->vis_locked = 0;
    d->B = best_T;

    // Frame 0 = the oldest matched line whose audio is still in the ring
    // (all of them for the faster modes), so the rows already heard are
    // decoded too. idx[k] is k lines back.
    int first = HL_NEED_LINES;
    for (; first > 0; first--) {
        double c = d->hl_te[best_idx[first]] - best->sync_us * 0.5;
        double F = c - (best->sync_offset_us + best->sync_us * 0.5) * (d->B / d->T_nom);
        if (F - 2000.0 >= ring_oldest_us(d)) break;
    }
    for (int j = first; j >= 0; j--) {
        reg_add(d, first - j, d->hl_te[best_idx[j]] - best->sync_us * 0.5);
    }
    d->sync_attempts = d->sync_hits = first + 1;
    d->t_img = frame_start_us(d, 0);
    hl_clear(d);
}

// Per det-sample pulse tracker: runs of f < 1350 Hz (nominal — nothing is
// calibrated yet, so this tolerates roughly ±150 Hz of mistuning) between
// HL_MIN_WIDTH_US and HL_MAX_WIDTH_US are logged with their raw-track
// trailing edge and body frequency. VIS cells (30 ms) are too wide to count.
static void hl_track(sstv_decoder_t* d, long long cur)
{
    double thr = SYNC_THRESH_HZ;
    if (getd(d, cur) < thr) {
        if (!d->hl_in) {
            d->hl_in = 1;
            d->hl_start = cur;
        }
        d->hl_above = 0;
        return;
    }
    if (!d->hl_in) return;
    // Bridge sub-SYNC_GAP_US dropouts inside a noisy pulse (see find_sync).
    d->hl_above++;
    if (d->hl_above < (long long)(SYNC_GAP_US / d->usps)) return;
    long long end = cur - d->hl_above + 1;
    d->hl_in = 0;
    double w = (double)(end - d->hl_start) * d->usps;
    if (w < HL_MIN_WIDTH_US || w > HL_MAX_WIDTH_US) return;
    double edge = refine_edge(d, end, thr);
    if (edge < 0.0) return;
    double a = t_of(d, d->hl_start);
    double f = win_mean_det(d, a + 0.25 * w, a + 0.75 * w);
    hl_push_pulse(d, edge, w, f);
    hl_try_lock(d);
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

    for (int i = 0; i < n; i++) {
        double mag;
        double f = sstv_demod_process(&d->dm, (double)samples[i], &mag);
        d->mag_ema += d->mag_alpha * (mag - d->mag_ema);
        append_sample(d, f, mag);

        // In a terminal state the audio keeps flowing into the history (the
        // caller is about to read the image and reset; a header that
        // preempted the image must not lose its first lines to the rest of
        // this buffer) but the state machine stands still until reset.
        if (d->status == SSTV_STATUS_DONE || d->status == SSTV_STATUS_ABORTED) continue;

        // The state machine advances on the det track, which lags by
        // ma_delay samples.
        long long cur = d->n - 1 - (long long)d->ma_delay;
        if (cur < 0) continue;

        switch (d->status) {
        case SSTV_STATUS_IDLE:
        case SSTV_STATUS_LEADER:
        case SSTV_STATUS_VIS:
            if (hdr_step(d, &d->hdr, cur)) {
                double t0, cal0, slope;
                const sstv_mode_t* m = vis_evaluate(d, &d->hdr, 0, &t0, &cal0, &slope);
                hdr_init(&d->hdr);
                if (m) {
                    enter_image_vis(d, m, t0, cal0, slope);
                    break;
                }
            }
            d->status = d->hdr.st;
            // Header-less lock runs alongside the header hunt: a sync
            // train locks even when no VIS was (or will be) heard.
            hl_track(d, cur);
            break;

        case SSTV_STATUS_IMAGE:
            if (cur >= d->wait_n) image_step(d);
            if (d->status == SSTV_STATUS_IMAGE &&
                d->miss_streak > MISS_LIMIT &&
                d->mag_ema < MAG_COLLAPSE_FRAC * d->mag_base) {
                d->status = SSTV_STATUS_ABORTED;
            }
            // VIS preemption: a new calibration header under the running
            // image ends it (partial rows retained); the pending lock is
            // applied by the reset that follows.
            if (d->status == SSTV_STATUS_IMAGE && hdr_step(d, &d->shadow, cur)) {
                double t0, cal0, slope;
                const sstv_mode_t* m = vis_evaluate(d, &d->shadow, 1, &t0, &cal0, &slope);
                hdr_init(&d->shadow);
                if (m) {
                    d->pend_valid = 1;
                    d->pend_mode = m;
                    d->pend_t0 = t0;
                    d->pend_cal0 = cal0;
                    d->pend_slope = slope;
                    d->status = SSTV_STATUS_ABORTED;
                }
            }
            break;

        default:
            break;
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

int sstv_decoder_vis_locked(const sstv_decoder_t* d)
{
    return (d && d->mode) ? d->vis_locked : 0;
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
