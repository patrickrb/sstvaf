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
//           regression over the last 64 accepted syncs (slant estimate),
//           search ±max(2 ms, 3 px) for the run of f < 1350 Hz of width
//           ≈ sync_us nearest the prediction, take its centroid; reject
//           outliers > 2.5 ms from the fit; freewheel through missed
//           syncs. Pixels are the fraction-weighted mean frequency over
//           each pixel window.
//   DONE    after the last row, when a picture was there for at least
//           half the frame.
//   ABORTED > 5 line periods without a sync AND the in-band energy has
//           collapsed (partial image retained); OR a header lock showed no
//           syncs through its probation (it was never a picture); OR a new
//           transmission was found underneath the image (preemption,
//           below); OR the last row was reached but the picture had ended
//           less than half-way down the frame (the row count is cut back
//           to the rows that were picture).
//
// Real-signal robustness (all learned from off-air recordings):
//
//   * Sync re-acquisition. A dropped capture buffer, a sender glitch or a
//     YouTube edit shifts every following sync by a constant offset; the
//     ±few-ms prediction window then misses forever and the image shears.
//     Once SYNC_REACQ_FRAMES frames have gone by with no sync where it was
//     predicted, the running mode's sync comb is searched across the whole
//     line; found, it re-anchors the fit (keeping its slope history) and
//     the affected frames are re-decoded from the ring.
//   * Ragged syncs. Off the air a sync's edges wander by a millisecond or
//     more, line by line, on signals that are otherwise strong (ripple on
//     the sync tone; the porch only 150 Hz above the threshold). The
//     tracker therefore times a ragged pulse by whichever edge agrees
//     better with the fit, accepts it within a gate wide enough to hold
//     that scatter on both sides, and averages over a long history — the
//     raster stays rigid instead of following each line's noise. While
//     the image is young the slope is also held toward the nominal period,
//     but only as hard as the syncs are scattered about their own straight
//     line: clean syncs from a sender whose clock is off (a fraction of a
//     per cent is common, a per cent or more turns up) are believed at
//     once.
//   * A grid that has slipped. When the syncs are still seen near their
//     predicted places but none has been close enough to enter the fit for
//     SYNC_REACQ_FRAMES frames (a few milliseconds of audio cut out or
//     repeated), the same comb search runs and the fit moves if the comb
//     sits further off than the gate.
//   * Young-fit window. Until REG_YOUNG syncs are in the regression the
//     slope is still the nominal line period, so the search window is at
//     least SYNC_YOUNG_FRAC of a line: a 1-2 % sound-card clock error is
//     common and the VIS-to-first-sync gap varies between encoders.
//   * Header-less lock. On a live band the header is the part most often
//     lost: someone is still talking, or tuning up, when the picture
//     starts. While hunting, each mode's sync comb is evaluated over the
//     last couple of seconds of audio (see "Sync comb"): a sync-width place
//     in the line that is low on line after line, and not low either side.
//     A comb that passes twice in a row locks the mode with no VIS at all,
//     and the lock is then followed back through the ring to the first
//     line that has a sync, so a transmission whose header was drowned
//     still decodes from its first line (and ends DONE), and one joined
//     mid-image from the first line still buffered. Plain band noise and
//     speech cross the sync threshold dozens of times a second in
//     sync-length runs; they do not form a comb. A sender whose clock is
//     too far off for the rigid nominal comb is found by combs at line
//     periods either side of nominal, tried once a second (hl_scan); the
//     fit then starts from where the syncs on the comb's lines actually
//     are, not from the comb.
//   * Preemption. The header hunt keeps running underneath IMAGE (a
//     "shadow" copy of the IDLE/LEADER/VIS machine). While the image's own
//     syncs are arriving, only a header image content cannot imitate (the
//     strict profile) counts. Once they have stopped arriving for
//     IMG_UNHEALTHY_US — a deep fade, a station that stopped, a lock that
//     was never a picture — a sync comb on another grid takes over, and
//     once nothing sync-shaped has been seen for that long, so does any
//     credible header: a receiver's AGC brings the noise up to signal
//     level when a station stops, so the energy never "collapses", and a
//     decoder that waited for that sat on the dead image to its nominal
//     end, deaf to the next transmission. ("Arriving" means two syncs
//     within IMG_CONFIRM_US of each other: noise, and the syncs of a
//     station in another mode crossing this image's grid, put a single
//     sync-shaped low on it every so often.) Either way the current image
//     ends ABORTED and
//     the following sstv_decoder_reset() enters IMAGE for the new
//     transmission immediately, from its first line. For that reason
//     reset() keeps the demodulated audio history and only rewinds the
//     state machine.
//   * Probation. A header lock that shows no syncs over the whole of its
//     first seconds was read out of speech or noise; it is dropped
//     (ABORTED) instead of running for the length of an image. A picture
//     that fades right after its header has shown some and plays on.
//   * Not a complete picture. An image runs to its last row whether or
//     not its signal lasts (it may be a fade). At the end, one whose
//     picture stopped less than half-way down — a station that stopped, a
//     transmission joined so late that the frame ran on long past it —
//     ends ABORTED with the rows that were picture, instead of DONE with
//     a frame that is mostly noise.
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
#define SYNC_OUTLIER_US      1500.0    // comb seed: a fit this far off the comb is replaced
#define SYNC_SEARCH_MIN_US   2000.0    // ± search window floor
#define REG_MAX              64        // regression history depth
#define REG_PRIOR_LINES      8         // slope prior: worth this many consecutive lines,
#define REG_PRIOR_HITS       24        // ... dropped after this many accepted syncs
#define REG_PRIOR_CLEAN_US   250.0     // ... absent while the syncs scatter less than this
#define REG_PRIOR_JITTER_US  750.0     // ... at full weight when they scatter this much
#define MISS_LIMIT           5         // frames without sync before abort check
#define MAG_COLLAPSE_FRAC    0.15      // energy collapse threshold vs baseline
#define COH_MAG_FRAC         0.30      // "coherent" magnitude threshold
#define REG_YOUNG            4         // fit is "young" below this many syncs
#define SYNC_YOUNG_FRAC      0.02      // young-fit search half-width, of a line
#define SYNC_REACQ_FRAMES    3         // frames with no sync before a re-anchor is tried
#define SYNC_GATE_US         2500.0    // tracked sync accepted this far from the fit
#define SYNC_CLEAN_FRAC      0.10      // pulse width this close to nominal: trust its edge
#define SYNC_REFIT_HITS      12        // accepted syncs before the first frames are
#define SYNC_REFIT_STAGES    2         // re-decoded again, doubling this many times
#define SYNC_GAP_US          2000.0    // noise gap bridged inside one sync pulse
#define COMB_SPAN_US         4500000.0 // comb seed: lines buffered (fits the 6 s ring)
#define COMB_MAX_LINES       8
#define COMB_SLANT           0.02      // comb seed: line-period search, ± fraction
#define COMB_PHASE_FRAC      0.02      // comb seed: phase search, ± fraction of a line
#define COMB_MIN_SCORE       0.40      // mean sync-box fill to trust the comb
#define COMB_MIN_CONTRAST    2.0       // ... and that many times the median fill
#define COMB_MIN_GAIN        0.15      // ... and this much above the running fit's comb
// Sync comb (header-less lock, re-anchor, preemption of a dead image).
#define HLC_EVAL_US          100000.0  // evaluation cadence
#define HLC_PHASE_US         500.0     // coarse phase step of the blind search, at least;
#define HLC_PHASE_DIV        6.0       // ... a sync this many steps wide, for the wider syncs
#define HLC_REFINE_US        125.0     // fine step (phase, comb-end drift)
#define HLC_SPAN_US          5300000.0 // longest comb (fits the 6 s ring)
#define HLC_MIN_SPAN_US      2000000.0 // shortest
#define HLC_MIN_LINES        4
#define HLC_MAX_LINES        12
#define HLC_INDEP_US         1500.0    // one independent det sample (DET_MA_US)
#define HLC_NEED_INDEP       40.0      // independent sync-box samples wanted
#define HLC_LINE_SCORE       0.35      // per-line score for the line to count
#define HLC_GUARD_GAP_US     1500.0    // sync box to guard box spacing
#define HLC_CONFIRM_TOL_US   1000.0    // phase agreement between two evaluations
#define HLC_CLOCK_MAX        0.006     // sender clock error the comb is tried out to, ±
#define HLC_CLOCK_EVERY      10        // ... on every this-many-th evaluation
#define HLC_FIT_SHARE        0.75      // lines of an off-nominal comb that must show a sync
#define HLC_FIT_RMS_US       750.0     // ... lying on one straight line this closely
#define HLC_BACK_MISSES      1         // faded lines bridged when extending back
#define HLC_BRIDGE_SCORE     0.6       // ... to a line that scores at least this
#define HLC_LEVEL_TOL_HZ     150.0     // ... a line counts when its box reads this near the sync tone
// Line scores (sync_mf_live) measured on live 20 m audio: band noise averages
// -0.06 to -0.10 a line in every mode (sd 0.25, 0.35 on Martin's short sync)
// and reaches HLC_LINE_SCORE on one line in twenty (one in eight on Martin);
// the weakest complete pictures averaged +0.2 to +0.3.
#define IMG_UNHEALTHY_US     2000000.0 // no confirmed sync this long: the image is preemptible
#define IMG_CONFIRM_US       1500000.0 // two syncs this close together confirm each other
#define IMG_PROBATION_US     4000000.0 // a header lock must show syncs by this long ...
#define IMG_PROBATION_MIN    16        // ... or this many frames, whichever is more,
#define IMG_PROBATION_SCORE  0.0       // as this mean line score over all of them
#define IMG_MIN_SCORE        0.0       // mean line score for an image to count as one
#define IMG_LINE_FLOOR       0.10      // line score that separates picture from noise
#define IMG_DONE_FRAC        0.5       // share of the frame the picture must reach to be complete
#define IMG_REANCHOR_GAP_US  3000000.0 // off-grid syncs after a longer silence are
                                       // a new transmission, not a dropout
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

    // A lock found underneath a running image — the shadow hunt's VIS, or a
    // sync comb once the image has gone quiet — applied by
    // sstv_decoder_reset(). pend_kind: 0 none, 1 VIS, 2 sync comb.
    int pend_kind;
    const sstv_mode_t* pend_mode;
    double pend_t0, pend_cal0, pend_slope;  // VIS
    double pend_c, pend_B;                  // comb: newest sync centre µs, period
    int pend_lines;                         // ... and the lines it was seen on

    // Sync comb scratch: prefix sums of "det sample below the sync
    // threshold" over det indices [cps_i0, cps_i0 + cps_n).
    int* cps;
    long long cps_i0, cps_n;
    int cps_hunt;             // the sums are the header-less hunt's (cps_slide may extend them)

    // Header-less lock: the comb is evaluated every HLC_EVAL_US of audio; a
    // mode locks when it passes two evaluations in a row at one phase.
    long long hl_next;        // det index of the next evaluation
    long long hl_floor;       // oldest det index the comb may read (audio
                              // before it belongs to an earlier image)
    int hl_seq;               // evaluation counter
    int hl_pass_seq[SSTV_NUM_MODES];   // evaluation the mode last passed at
    double hl_pass_c[SSTV_NUM_MODES];  // ... and its newest sync centre, µs
    double hl_pass_T[SSTV_NUM_MODES];  // ... and the line period it passed at, µs

    long long wait_n;         // IMAGE: resume image_step at this det index

    // IMAGE.
    double t_img;             // µs: frame-0 base (after starting sync)
    int frame_idx, n_frames;
    double T_nom;             // nominal frame duration µs
    double A, B;              // sync centroid fit: c(k) = A + B*k
    double B0;                // slope the fit is held to until the syncs outweigh it
    double reg_k[REG_MAX], reg_c[REG_MAX];
    int reg_n, reg_pos;
    int miss_streak;          // frames since a sync sat at its predicted place
    int nohit_streak;         // frames since a sync was accepted into the fit
    double score_sum;         // line scores (sync_mf_live) over the image so far
    double ext_run;           // running sum of (line score - IMG_LINE_FLOOR) ...
    double ext_best;          // ... its maximum so far ...
    int ext_k;                // ... and the frame it was reached at: where the picture ends
    int last_present_k;       // newest frame whose sync was there, -1 none
    int last_ok_k;            // ... and had another within ok_gap frames before it
    int last_hit_k;           // newest frame whose sync went into the fit, -1 none
    int ok_gap;               // frames in IMG_CONFIRM_US
    int unhealthy_frames;     // frames without one that make the image preemptible
    int prob_frames;          // frame count at which probation is judged
    long long sync_attempts, sync_hits;
    long long coh_tot, coh_good;

    int fit_refined;          // 0: nominal slope; 1: 2-point; 2: REG_YOUNG-point;
                              // then +1 per SYNC_REFIT_HITS stage passed
    int comb_done;            // VIS-locked image: comb seed evaluated (1) or not
    int rewritten_row;        // lowest already-readable row re-decoded, -1 none

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

    // Header-hunt diagnostics (sstv_decoder_events): a ring of the live
    // hunt's verdicts, oldest at evt_head, evt_n deep.
    sstv_event_t evt[SSTV_EVENT_CAPACITY];
    int evt_head, evt_n;
};

// ---------------------------------------------------------------------------
// Diagnostics ring
// ---------------------------------------------------------------------------
static void evt_push(sstv_decoder_t* d, int type, int arg, int arg2, double f_off)
{
    int slot;
    if (d->evt_n < SSTV_EVENT_CAPACITY) {
        slot = (d->evt_head + d->evt_n) % SSTV_EVENT_CAPACITY;
        d->evt_n++;
    } else {
        // Full: overwrite the oldest so a reader that fell behind still
        // sees the most recent verdicts, which are the ones it will ask about.
        slot = d->evt_head;
        d->evt_head = (d->evt_head + 1) % SSTV_EVENT_CAPACITY;
    }
    d->evt[slot].type = type;
    d->evt[slot].arg = arg;
    d->evt[slot].arg2 = arg2;
    d->evt[slot].t_s = (double)d->n * d->usps * 1e-6;
    d->evt[slot].f_off_hz = f_off;
}

int sstv_decoder_events(sstv_decoder_t* d, sstv_event_t* out, int max)
{
    if (!d || !out || max <= 0) return 0;
    int n = 0;
    while (n < max && d->evt_n > 0) {
        out[n++] = d->evt[d->evt_head];
        d->evt_head = (d->evt_head + 1) % SSTV_EVENT_CAPACITY;
        d->evt_n--;
    }
    return n;
}

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
static void enter_image_vis(sstv_decoder_t* d, const sstv_mode_t* m, double t0,
                            double cal0, double slope);
static void enter_image_sync(sstv_decoder_t* d, const sstv_mode_t* m, double c_last,
                             double B, int lines);
static int cps_build(sstv_decoder_t* d, long long i0, long long i1, double thr);
static int cps_build_history(sstv_decoder_t* d);
static double sync_mf(const sstv_decoder_t* d, double c, double w);

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
    d->cps = (int*)calloc((size_t)d->ring_size + 2, sizeof(int));
    if (!d->ring || !d->det || !d->gate || !d->cps) {
        free(d->ring);
        free(d->det);
        free(d->gate);
        free(d->cps);
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
    d->sync_attempts = d->sync_hits = 0;
    d->coh_tot = d->coh_good = 0;
    d->rows_done = 0;
    d->r36_have0 = 0;
    hdr_init(&d->hdr);
    hdr_init(&d->shadow);
    d->hl_next = 0;
    memset(d->hl_pass_seq, 0, sizeof(d->hl_pass_seq));
    // d->forced_mode is deliberately NOT cleared: the mode lock is an
    // operator setting that must survive the between-frames reset the RX
    // engine issues after every decode.

    int kind = d->pend_kind;
    d->pend_kind = 0;
    if (kind == 1) {
        // The previous image was preempted by a new calibration header:
        // decode the new transmission from its first line.
        enter_image_vis(d, d->pend_mode, d->pend_t0, d->pend_cal0, d->pend_slope);
    } else if (kind == 2) {
        // ... or by a sync comb: lock onto it, back to the first line the
        // ring still holds.
        if (cps_build_history(d)) {
            enter_image_sync(d, d->pend_mode, d->pend_c, d->pend_B, d->pend_lines);
        }
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
    free(d->cps);
    free(d);
}

// ---------------------------------------------------------------------------
// Regression over accepted sync centroids: c(k) = A + B*k.
// ---------------------------------------------------------------------------
static void reg_add(sstv_decoder_t* d, int k, double c)
{
    // One point per frame: a frame measured again (seeded from a comb and
    // then tracked, or re-evaluated after a re-anchor) replaces its point.
    int at = -1;
    for (int i = 0; i < d->reg_n && at < 0; i++) {
        if (d->reg_k[i] == (double)k) at = i;
    }
    if (at >= 0) {
        d->reg_c[at] = c;
    } else {
        d->reg_k[d->reg_pos] = (double)k;
        d->reg_c[d->reg_pos] = c;
        d->reg_pos = (d->reg_pos + 1) % REG_MAX;
        if (d->reg_n < REG_MAX) d->reg_n++;
    }

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
        // While the image is young, a ridge prior on the slope, centred on
        // B0 (the nominal line period, or the comb's once it has spoken)
        // with up to the weight of REG_PRIOR_LINES consecutive lines. Off
        // the air each sync is a millisecond or so uncertain, so a slope
        // taken freely from the first handful is good to ±1000 ppm at best
        // — enough to walk the prediction off the syncs before there are
        // enough of them to correct it, which is how a weak but readable
        // picture lost its grid a third of the way down.
        //
        // The prior is for THAT case only, so its weight follows the
        // scatter of the syncs about their own straight line. Syncs that
        // lie on a line to within REG_PRIOR_CLEAN_US are telling the truth
        // about the sender's clock, however far from nominal it is, and
        // get the free fit: a clean PD 180 from a sender 0.15 % fast moves
        // 1.1 ms a frame, and held to nominal the prediction left the gate
        // within four frames (with only a few syncs in, even a hundredth
        // of the prior is a quarter of the fit, hence a dead band and not
        // a smooth weight). Syncs scattered by REG_PRIOR_JITTER_US or more
        // get the whole prior. A fit still on its first REG_YOUNG syncs has
        // no scatter worth measuring and a wide search window besides: it
        // runs free. The prior is dropped altogether after REG_PRIOR_HITS
        // syncs, by when the syncs know better (a prior kept on would shave
        // a real slant by its last few per cent, a pixel or two of skew).
        double prior = 0.0;
        if (d->sync_hits < REG_PRIOR_HITS && d->reg_n >= REG_YOUNG) {
            double b_ls = num / den, a_ls = mc - b_ls * mk, sse = 0.0;
            for (int i = 0; i < d->reg_n; i++) {
                double r = d->reg_c[i] - a_ls - b_ls * d->reg_k[i];
                sse += r * r;
            }
            double rms = sqrt(sse / (double)(d->reg_n - 2));
            double g = (rms - REG_PRIOR_CLEAN_US) / (REG_PRIOR_JITTER_US - REG_PRIOR_CLEAN_US);
            if (g < 0.0) g = 0.0;
            if (g > 1.0) g = 1.0;
            prior = g * (double)REG_PRIOR_LINES *
                    ((double)REG_PRIOR_LINES * REG_PRIOR_LINES - 1.0) / 12.0;
        }
        d->B = (num + prior * d->B0) / (den + prior);
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

// `pref_us` picks among several sync-width runs in the window: >= 0 takes
// the one whose centroid is nearest that instant (a tracked sync sits where
// the fit predicts it; a noise run of a better width elsewhere in the window
// is not it), < 0 the one whose width is closest to want_us (no usable
// prediction yet).
//
// `run_out` (may be NULL) receives the chosen run as det indices
// [run_out[0], run_out[1]).
static int find_sync_run(const sstv_decoder_t* d, double a_us, double b_us,
                         double want_us, double pref_us, double* c_out,
                         long long* run_out)
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
            double edge = refine_edge(d, end, thr);
            if (edge < 0.0) continue;
            double c = edge - want_us * 0.5;
            if (pref_us >= 0.0 && fabs(width - want_us) > SYNC_CLEAN_FRAC * want_us) {
                // A ragged pulse — the usual case off the air. The trailing
                // edge is the 1200 -> 1500 Hz porch step, whose upper level
                // sits only 150 Hz above the threshold: ripple on the sync
                // tone or noise on the porch moves the crossing by a
                // millisecond or more (measured on a strong 20 m Scottie 2
                // signal: 1.3 ms rms against the line grid). The leading
                // edge has its own enemy, the brightness of the scan before
                // it. They rarely fail together, so with a prediction to
                // judge by, time the pulse by whichever edge puts it nearer.
                // (A run already low at the window's first sample began
                // somewhere before it: there is no leading edge to time.)
                double c_lead = ((double)run_start - 0.5) * d->usps + want_us * 0.5;
                if (run_start > ia && fabs(c_lead - pref_us) < fabs(c - pref_us)) c = c_lead;
            }
            double err = (pref_us >= 0.0) ? fabs(c - pref_us) : fabs(width - want_us);
            if (err < best_err) {
                best_err = err;
                best_c = c;
                found = 1;
                if (run_out) {
                    run_out[0] = run_start;
                    run_out[1] = end;
                }
            }
        }
    }
    if (found) *c_out = best_c;
    return found;
}

static int find_sync(const sstv_decoder_t* d, double a_us, double b_us,
                     double want_us, double pref_us, double* c_out)
{
    return find_sync_run(d, a_us, b_us, want_us, pref_us, c_out, 0);
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
    // Rows a caller may already have copied out are about to change.
    int row0 = k0 * d->mode->rows_per_frame;
    if (row0 < d->rows_done && (d->rewritten_row < 0 || row0 < d->rewritten_row)) {
        d->rewritten_row = row0;
    }
    if (step == 2) d->r36_have0 = 0;
    for (int k = k0; k < k1; k++) decode_frame(d, k);
}

// ---------------------------------------------------------------------------
// Comb seed (VIS-locked images).
//
// On a weak HF signal Martin's 4.9 ms sync is chopped up by noise: line by
// line, the best sync-shaped run in the young (±2 % of a line) window is as
// often a noise run in the picture as the real pulse. A few of those in the
// first REG_YOUNG lines bend the fit — a G4KJS 20 m recording came out at
// 5118 ppm "slant" with 12 of 256 syncs found and a sheared, unreadable
// picture, although the sender's clock was exact. Across several lines,
// though, the syncs still stand out clearly: they are the only low-frequency
// stretch that recurs at one period.
//
// So once the first L lines are buffered, search line period (±COMB_SLANT)
// and phase (±COMB_PHASE_FRAC of a line) together for the comb of sync-width
// boxes with the highest mean fill of below-threshold det samples. When that
// comb is credible (absolute fill, and contrast against the median comb) and
// the running fit disagrees with it by more than the outlier tolerance on
// any of those lines, the fit is replaced by the comb and the frames decoded
// so far are re-decoded. A fit that already agrees (every clean signal) is
// left alone, so the trailing-edge sync timing is unchanged there.
//
// Returns 0 while the L lines are not yet buffered, 1 once evaluated.
// ---------------------------------------------------------------------------
// The decoder's prefix sums of "det sample below the sync threshold"
// (cps_build) over the seed's span, for O(1) sync-width box fills.
typedef struct {
    const int* ps;
    long long i0, n, wn;
    double half, usps;
    int lines;
} comb_box_t;

// Mean sync-box fill of the comb c(j) = cA + cB*j, j = 0..lines-1.
static double comb_score(const comb_box_t* cb, double cA, double cB)
{
    double sum = 0.0;
    for (int j = 0; j < cb->lines; j++) {
        double c = cA + cB * (double)j;
        long long a = (long long)lrint((c - cb->half) / cb->usps) - cb->i0;
        long long b = a + cb->wn;
        if (a < 0) a = 0;
        if (b > cb->n) b = cb->n;
        if (b > a) sum += (double)(cb->ps[b] - cb->ps[a]) / (double)cb->wn;
    }
    return sum / (double)cb->lines;
}

static int comb_seed(sstv_decoder_t* d)
{
    const sstv_mode_t* m = d->mode;
    double T = d->T_nom;
    int L = (int)(COMB_SPAN_US / T);
    if (L > COMB_MAX_LINES) L = COMB_MAX_LINES;
    if (L < 2) L = 2;

    // Comb anchored on the running fit's frame 0, not on the VIS-predicted
    // sync: some encoders start the picture a sync width off the header
    // timing (a PD 180 sender, 20 ms early), which the tracker's
    // re-acquisition has already corrected by the time the comb runs.
    double A0 = d->A;
    double half = m->sync_us * 0.5;
    double ph_r = COMB_PHASE_FRAC * T;
    double start = A0 - ph_r - half - 1000.0;
    double end = A0 + ph_r + (double)(L - 1) * T * (1.0 + COMB_SLANT) + half + 1000.0;

    if (t_of(d, d->n) <= end + 2000.0) return 0;
    if (start < ring_oldest_us(d)) return 1;

    comb_box_t cb;
    if (!cps_build(d, (long long)floor(start / d->usps), (long long)ceil(end / d->usps) + 1,
                   map_hz(d, SYNC_THRESH_HZ))) {
        return 1;
    }
    cb.ps = d->cps;
    cb.i0 = d->cps_i0;
    cb.n = d->cps_n;
    cb.wn = (long long)lrint(m->sync_us / d->usps);
    if (cb.wn < 1) cb.wn = 1;
    cb.half = half;
    cb.usps = d->usps;
    cb.lines = L;

    double cur = comb_score(&cb, d->A, d->B);

    // Period step: the comb's end drifts at most ~0.25 ms per step; phase
    // step 0.25 ms.
    double step_e = 250.0 / ((double)(L - 1) * T);
    int ne = (int)(COMB_SLANT / step_e);
    int np = (int)(ph_r / 250.0);
    int hist[101] = {0};
    long long total = 0;
    double best = -1.0, best_e = 0.0, best_ph = 0.0;
    int best_edge = 0;
    for (int ei = -ne; ei <= ne; ei++) {
        double Bc = T * (1.0 + (double)ei * step_e);
        for (int pi = -np; pi <= np; pi++) {
            double ph = (double)pi * 250.0;
            double sc = comb_score(&cb, A0 + ph, Bc);
            int hb = (int)lrint(sc * 100.0);
            if (hb < 0) hb = 0;
            if (hb > 100) hb = 100;
            hist[hb]++;
            total++;
            if (sc > best) {
                best = sc;
                best_e = (double)ei * step_e;
                best_ph = ph;
                best_edge = (ei == -ne || ei == ne || pi == -np || pi == np);
            }
        }
    }

    long long acc = 0;
    int med_b = 0;
    for (int b = 0; b <= 100; b++) {
        acc += hist[b];
        if (acc * 2 >= total) { med_b = b; break; }
    }
    double median = (double)med_b / 100.0;
    // Credible: well filled, well above the typical comb, an interior peak
    // (a best at the search edge means the real comb lies outside it), and
    // a clear improvement on the line-up the running fit already has.
    int credible = best >= COMB_MIN_SCORE && best >= COMB_MIN_CONTRAST * median &&
                   !best_edge && best >= cur + COMB_MIN_GAIN;

    double cA = A0 + best_ph, cB = T * (1.0 + best_e);
    int disagree = 0, shown = 0;
    for (int j = 0; j < L; j++) {
        double c = cA + cB * (double)j;
        if (fabs((d->A + d->B * (double)j) - c) > SYNC_OUTLIER_US) disagree = 1;
        if (sync_mf(d, c, m->sync_us) >= HLC_LINE_SCORE) shown++;
    }
    // ... and a comb of syncs, not of noise. The fill alone cannot tell:
    // band noise is below the threshold a third of the time, and over the
    // thousands of periods and phases searched some comb of it always
    // fills well. When the first lines were a fade (two real syncs, then
    // noise), that comb replaced a good fit with a slope 0.7 % off and the
    // picture was lost. A sync has its guards clear; most of the comb's
    // lines must show one.
    if ((double)shown < HLC_FIT_SHARE * (double)L) credible = 0;
    if (!credible || !disagree) return 1;

    // Replace the fit: the comb's lines become the regression history (they
    // count as REG_YOUNG-mature, so the tracker narrows its window around
    // them), and frames decoded against the bent fit are re-decoded.
    d->reg_n = 0;
    d->reg_pos = 0;
    d->B = cB;
    d->B0 = cB;
    int seed = L > REG_YOUNG ? L : REG_YOUNG;
    for (int j = 0; j < seed; j++) reg_add(d, j, cA + cB * (double)j);
    d->fit_refined = 2;
    d->miss_streak = 0;
    redecode_frames(d, 0, d->frame_idx);
    return 1;
}

// ---------------------------------------------------------------------------
// Sync comb: line-sync detection by folding, without a header.
//
// A receiver on a live band hears noise, speech and carriers between (and
// under) pictures. The discriminator drops below the sync threshold some
// forty times a second on plain band noise, in runs as long as a sync
// pulse, so single pulses prove nothing: a detector that matches a handful
// of them either locks onto noise or, told to distrust a busy span, never
// locks onto a weak picture. What only a picture has is ONE place in the
// line that is low on line after line. So the evidence is the "comb": the
// fill of a sync-width box (share of det samples below the threshold) at one
// phase of the line period, averaged over the last several lines, less the
// fill of a guard box either side of it (a real sync is flanked by picture
// or porch, above the threshold; a stretch of speech or a VIS header is low
// on both sides). The number of lines is chosen per mode so the boxes hold
// about HLC_NEED_INDEP independent samples, and the score a mode must reach
// rises as that number falls (hlc_threshold): on an hour of live 20 m audio
// the highest score noise and speech reached stayed under it for every
// mode, while every picture, down to ones barely readable, cleared it.
//
// The same comb, restricted to the running mode, re-anchors an image whose
// sync has left its predicted place (image_reanchor).
// ---------------------------------------------------------------------------

// Build the below-threshold prefix sums over det indices [i0, i1), clamped
// to what the ring still holds. Returns 0 when nothing is readable.
static int cps_build(sstv_decoder_t* d, long long i0, long long i1, double thr)
{
    long long lo = d->n - (long long)d->ring_size + 1;
    long long hi = det_end(d);
    if (lo < 0) lo = 0;
    if (i0 < lo) i0 = lo;
    if (i1 > hi) i1 = hi;
    d->cps_hunt = 0;
    if (i1 - i0 < 2) {
        d->cps_n = 0;
        return 0;
    }
    d->cps_i0 = i0;
    d->cps_n = i1 - i0;
    d->cps[0] = 0;
    for (long long i = 0; i < d->cps_n; i++) {
        d->cps[i + 1] = d->cps[i] + (getd(d, i0 + i) < thr ? 1 : 0);
    }
    return 1;
}

// The header-less hunt asks for the same sums ten times a second, each time
// over a span that has moved on by a tenth of a second: slide what is there
// and count only the new samples, instead of counting five seconds of audio
// again (a quarter of the cost of listening to an empty band). The sums are
// only ever used as differences, so they need not start at zero; they are
// brought back down before they can overflow. Falls back to a full build
// whenever the sums on hand are not the hunt's own.
static int cps_slide(sstv_decoder_t* d, long long i0, long long i1)
{
    long long have_end = d->cps_i0 + d->cps_n;
    if (!d->cps_hunt || i0 < d->cps_i0 || i0 >= have_end || i1 < have_end) {
        int ok = cps_build(d, i0, i1, SYNC_THRESH_HZ);
        d->cps_hunt = ok;
        return ok;
    }
    long long hi = det_end(d);
    if (i1 > hi) i1 = hi;
    long long drop = i0 - d->cps_i0;
    if (drop > 0) {
        memmove(d->cps, d->cps + drop, (size_t)(d->cps_n - drop + 1) * sizeof(int));
        d->cps_i0 = i0;
        d->cps_n -= drop;
    }
    for (long long i = d->cps_i0 + d->cps_n; i < i1; i++) {
        d->cps[d->cps_n + 1] = d->cps[d->cps_n] + (getd(d, i) < SYNC_THRESH_HZ ? 1 : 0);
        d->cps_n++;
    }
    if (d->cps[d->cps_n] > (1 << 30)) {
        int base = d->cps[0];
        for (long long i = 0; i <= d->cps_n; i++) d->cps[i] -= base;
    }
    return d->cps_n >= 2;
}

// Share of below-threshold det samples over [a_us, b_us); the part of the
// box outside the built span counts as above the threshold.
static double cps_fill(const sstv_decoder_t* d, double a_us, double b_us)
{
    long long a = (long long)lrint(a_us / d->usps) - d->cps_i0;
    long long b = (long long)lrint(b_us / d->usps) - d->cps_i0;
    long long len = b - a;
    if (len <= 0) return 0.0;
    if (a < 0) a = 0;
    if (b > d->cps_n) b = d->cps_n;
    if (b <= a) return 0.0;
    return (double)(d->cps[b] - d->cps[a]) / (double)len;
}

// Matched-filter score of a sync of width w centred at c: the box fill less
// the fuller of the two guard boxes. 1 for a clean sync, about 0 for noise
// and for anything that is low on both sides.
static double sync_mf(const sstv_decoder_t* d, double c, double w)
{
    double h = 0.5 * w;
    double box = cps_fill(d, c - h, c + h);
    double gl = cps_fill(d, c - h - HLC_GUARD_GAP_US - w, c - h - HLC_GUARD_GAP_US);
    double gr = cps_fill(d, c + h + HLC_GUARD_GAP_US, c + h + HLC_GUARD_GAP_US + w);
    return box - (gl > gr ? gl : gr);
}

// The same score read straight off the det ring against the image's own
// calibrated threshold (no prefix sums): the tracker's test for "there is a
// sync here". Band noise half-fills a sync box about one time in three; with
// the guards taken off it reaches HLC_LINE_SCORE only a few times in a
// hundred, so this is what tells a picture that is still arriving from an
// image that is playing on over noise.
static double sync_mf_live(const sstv_decoder_t* d, double c, double w)
{
    double h = 0.5 * w, thr = map_hz(d, SYNC_THRESH_HZ);
    int b, t, gl_b, gl_t, gr_b, gr_t;
    count_below(d, c - h, c + h, thr, &b, &t);
    if (t <= 0) return 0.0;
    count_below(d, c - h - HLC_GUARD_GAP_US - w, c - h - HLC_GUARD_GAP_US, thr, &gl_b, &gl_t);
    count_below(d, c + h + HLC_GUARD_GAP_US, c + h + HLC_GUARD_GAP_US + w, thr, &gr_b, &gr_t);
    double gl = gl_t > 0 ? (double)gl_b / (double)gl_t : 0.0;
    double gr = gr_t > 0 ? (double)gr_b / (double)gr_t : 0.0;
    return (double)b / (double)t - (gl > gr ? gl : gr);
}

// Lines a comb for line period T and sync width w spans.
static int hlc_lines(double T, double w)
{
    int L = (int)ceil(HLC_NEED_INDEP / (w / HLC_INDEP_US));
    int span_min = (int)ceil(HLC_MIN_SPAN_US / T);
    int span_max = (int)(HLC_SPAN_US / T);
    if (L < span_min) L = span_min;
    if (L > span_max) L = span_max;
    if (L > HLC_MAX_LINES) L = HLC_MAX_LINES;
    if (L < HLC_MIN_LINES) L = HLC_MIN_LINES;
    return L;
}

// Score a comb of `lines` lines must reach: the noise maximum over a line of
// phases falls as 1/sqrt(independent samples), plus a fixed margin.
static double hlc_threshold(int lines, double w)
{
    return 2.4 / sqrt((double)lines * w / HLC_INDEP_US) + 0.15;
}

typedef struct {
    double score;    // mean line score at the best phase
    double c_last;   // sync centre of the newest line there, µs
    int lines_ok;    // lines scoring HLC_LINE_SCORE or more
} comb_hit_t;

// Sum of the L line scores (in samples) of the comb whose newest box starts
// at span-relative sample s0; `ok` (may be NULL) counts the lines that pass.
static long long comb_sum(const sstv_decoder_t* d, double s0, double Tn, int L,
                          long long wn, long long gn, int* ok)
{
    const int* ps = d->cps;
    long long sum = 0;
    int n_ok = 0;
    for (int j = 0; j < L; j++) {
        long long s = (long long)(s0 - (double)j * Tn + 0.5);
        int box = ps[s + wn] - ps[s];
        int gl = ps[s - gn] - ps[s - gn - wn];
        int gr = ps[s + 2 * wn + gn] - ps[s + wn + gn];
        int v = box - (gl > gr ? gl : gr);
        sum += v;
        if ((double)v >= HLC_LINE_SCORE * (double)wn) n_ok++;
    }
    if (ok) *ok = n_ok;
    return sum;
}

// Best comb of L lines of a mode's timing (nominal period T, sync width w)
// over the built span, the newest line's sync anywhere in the last line
// period. Returns 0 when the span is too short for it.
static int comb_blind(const sstv_decoder_t* d, double T, double w, int L,
                      comb_hit_t* out)
{
    long long wn = (long long)lrint(w / d->usps);
    long long gn = (long long)lrint(HLC_GUARD_GAP_US / d->usps);
    double Tn = T / d->usps;
    // The line score falls off linearly either side of a sync, over its own
    // width: a sixth of that is step enough to land on the peak, which the
    // fine pass then climbs a sample at a time.
    double step_us = w / HLC_PHASE_DIV;
    if (step_us < HLC_PHASE_US) step_us = HLC_PHASE_US;
    double step = step_us / d->usps;
    if (wn < 2) wn = 2;
    // The newest box at phase 0 leaves room for its right guard; the oldest
    // box at the last phase needs its left guard inside the span.
    double last0 = (double)(d->cps_n - 2 * wn - gn);
    double first = (double)L * Tn + (double)(wn + gn) + 1.0;
    if (last0 < first) return 0;

    int nph = (int)(Tn / step);
    long long best = -1;
    double best_s0 = last0;
    int have = 0;
    for (int p = 0; p < nph; p++) {
        double s0 = last0 - (double)p * step;
        long long sum = comb_sum(d, s0, Tn, L, wn, gn, 0);
        if (!have || sum > best) {
            best = sum;
            best_s0 = s0;
            have = 1;
        }
    }
    // Fine pass, a sample at a time, one coarse step either side.
    double centre = best_s0;
    for (double s0 = centre - step; s0 <= centre + step; s0 += 1.0) {
        if (s0 > last0 || s0 < first - Tn) continue;
        long long sum = comb_sum(d, s0, Tn, L, wn, gn, 0);
        if (sum > best) {
            best = sum;
            best_s0 = s0;
        }
    }
    int ok = 0;
    comb_sum(d, best_s0, Tn, L, wn, gn, &ok);
    out->score = (double)best / ((double)L * (double)wn);
    out->lines_ok = ok;
    out->c_last = ((double)d->cps_i0 + best_s0 + 0.5 * (double)wn) * d->usps;
    return 1;
}

// Refine a comb's phase: the newest sync centre, within ±ph_max of c_last,
// whose comb of L lines at line period T scores highest.
static double comb_phase(const sstv_decoder_t* d, double T, double w, int L, double c_last,
                         double ph_max)
{
    int np = (int)(ph_max / HLC_REFINE_US);
    double best = -1e18, best_ph = 0.0;
    for (int pi = -np; pi <= np; pi++) {
        double c0 = c_last + (double)pi * HLC_REFINE_US;
        double sum = 0.0;
        for (int j = 0; j < L; j++) sum += sync_mf(d, c0 - (double)j * T, w);
        if (sum > best) {
            best = sum;
            best_ph = (double)pi * HLC_REFINE_US;
        }
    }
    return c_last + best_ph;
}

// Where the syncs on a comb's lines actually are: each line's own best
// position within ±reach of the comb's (line score at least HLC_LINE_SCORE),
// and the straight line through those. `a` is the newest line's offset from
// the comb (µs), `slope` the correction to the comb's period (µs a line),
// `rms` the scatter about the line, `n` the lines that showed a sync.
typedef struct {
    double a, slope, rms;
    int n;
} comb_fit_t;

static void comb_fit_lines(const sstv_decoder_t* d, double c_last, double B, double w, int L,
                           double reach, comb_fit_t* f)
{
    double x[HLC_MAX_LINES], y[HLC_MAX_LINES];
    int n = 0;
    if (L > HLC_MAX_LINES) L = HLC_MAX_LINES;
    for (int j = 0; j < L; j++) {
        double c = c_last - (double)j * B;
        double best = -1e18, lo = 0.0, hi = 0.0;
        for (double ph = -reach; ph <= reach; ph += HLC_REFINE_US) {
            double v = sync_mf(d, c + ph, w);
            if (v > best + 1e-9) {
                best = v;
                lo = hi = ph;
            } else if (v > best - 1e-9) {
                hi = ph;  // a flat top: take its middle
            }
        }
        if (best >= HLC_LINE_SCORE) {
            x[n] = -(double)j;
            y[n] = 0.5 * (lo + hi);
            n++;
        }
    }
    f->n = n;
    f->a = f->slope = 0.0;
    f->rms = 1e18;
    if (n < 3) return;
    double mx = 0.0, my = 0.0;
    for (int i = 0; i < n; i++) {
        mx += x[i];
        my += y[i];
    }
    mx /= n;
    my /= n;
    double num = 0.0, den = 0.0;
    for (int i = 0; i < n; i++) {
        num += (x[i] - mx) * (y[i] - my);
        den += (x[i] - mx) * (x[i] - mx);
    }
    if (den <= 0.0) return;
    f->slope = num / den;
    f->a = my - f->slope * mx;
    double sse = 0.0;
    for (int i = 0; i < n; i++) {
        double r = y[i] - f->a - f->slope * x[i];
        sse += r * r;
    }
    f->rms = sqrt(sse / (double)(n - 2));
}

// The comb places a sync by where its box and guards balance, which sits a
// few tenths of a millisecond off the trailing-edge timing the tracker
// uses (the porch after the sync, the scan before it and the det average
// all lean on the box). Measure the pulses on the comb's lines the
// tracker's way and return the median offset from the comb (0 when fewer
// than three can be measured), so a fit laid on the comb starts where the
// tracker will find the syncs.
static double comb_edge_offset(const sstv_decoder_t* d, double c0, double B, double w,
                               int lines)
{
    double off[HLC_MAX_LINES];
    int n = 0;
    if (lines > HLC_MAX_LINES) lines = HLC_MAX_LINES;
    for (int j = 0; j < lines; j++) {
        double c = c0 - (double)j * B;
        double cm;
        if (find_sync(d, c - 0.5 * w - SYNC_SEARCH_MIN_US, c + 0.5 * w + SYNC_SEARCH_MIN_US,
                      w, c, &cm) &&
            fabs(cm - c) <= SYNC_GATE_US) {
            off[n++] = cm - c;
        }
    }
    if (n < 3) return 0.0;
    for (int i = 1; i < n; i++) {
        double key = off[i];
        int j = i - 1;
        while (j >= 0 && off[j] > key) {
            off[j + 1] = off[j];
            j--;
        }
        off[j + 1] = key;
    }
    return (n & 1) ? off[n / 2] : 0.5 * (off[n / 2 - 1] + off[n / 2]);
}

// Seed the regression from a comb: frame k_last's sync is at c_last, the
// `lines` frames up to it one period B apart. Each line's pulse is measured
// the tracker's way and goes in where it actually is, so a sender whose
// clock is off shows its real slope from the first fit; points laid on the
// comb itself would be evidence for the period the comb merely assumed (a
// Robot 36 sender 0.7 % fast, re-anchored on such points, took eighty rows
// to shake them off). A line with no measurable pulse is left out. Fewer
// than two measurable: the comb's own line stands in, at period B.
static void reg_seed_comb(sstv_decoder_t* d, int k_last, double c_last, double B, double w,
                          int lines)
{
    d->reg_n = 0;
    d->reg_pos = 0;
    d->B = B;
    double reach = SYNC_GATE_US;
    if (w / 3.0 > reach) reach = w / 3.0;
    int got = 0;
    for (int j = lines - 1; j >= 0; j--) {
        if (k_last - j < 0) continue;
        double c = c_last - (double)j * B, cm;
        if (find_sync(d, c - 0.5 * w - reach, c + 0.5 * w + reach, w, c, &cm) &&
            fabs(cm - c) <= reach && sync_mf_live(d, cm, w) >= HLC_LINE_SCORE) {
            reg_add(d, k_last - j, cm);
            got++;
        }
    }
    if (got >= 2) return;
    d->reg_n = 0;
    d->reg_pos = 0;
    d->B = B;
    for (int j = lines - 1; j >= 0; j--) {
        if (k_last - j >= 0) reg_add(d, k_last - j, c_last - (double)j * B);
    }
}

// The sync has been missing from its predicted place for SYNC_REACQ_FRAMES
// or more frames while the picture plays on: an audio dropout, a dropped
// capture buffer or a sender glitch has moved every later sync by one
// constant offset, and the prediction window will miss them all. Look for
// the running mode's comb across the whole line over the frames missed so
// far; when it is there, move the fit onto it (the slope history rides
// along) and re-decode those frames. A single off-grid pulse, or a dark
// vertical edge in the picture, does not form a comb; noise does not reach
// the score.
//
// A comb that turns up after the image has been silent for longer than
// IMG_REANCHOR_GAP_US, further off than a clock error explains, is not this
// picture resuming but the next transmission in the same mode: the image
// ends (ABORTED, partial rows kept) and the reset that follows locks onto it.
//
// `adrift` is the other way a fit comes loose: the syncs are still near
// enough to their predicted places to be seen there, but further off than
// the gate takes into the fit (a few milliseconds of audio cut out or
// repeated), so the fit never learns where they went. The comb is laid over
// the frames since the last accepted sync, and the fit moves only when the
// comb sits further from it than the gate.
//
// Returns 1 when the fit moved (the caller re-evaluates frame k).
static int image_reanchor(sstv_decoder_t* d, int k, int adrift)
{
    const sstv_mode_t* m = d->mode;
    // A fit still on its first few syncs has no slope worth keeping: the
    // comb is laid at the nominal period and replaces it outright. (Twice
    // REG_YOUNG accepted syncs, not REG_YOUNG points: in a fade right after
    // the header the young fit's wide window picks up a noise run or two,
    // which make the fit look older without making it any better.)
    int young = d->reg_n < REG_YOUNG || d->sync_hits < 2 * REG_YOUNG;
    double T = young ? d->T_nom : d->B;
    double w = m->sync_us * (T / d->T_nom);
    int n_max = adrift ? d->nohit_streak : d->miss_streak;
    int lines_max = hlc_lines(d->T_nom, m->sync_us);
    if (n_max > lines_max) n_max = lines_max;
    if (n_max > k + 1) n_max = k + 1;
    if (n_max < SYNC_REACQ_FRAMES) return 0;

    double predk = d->A + d->B * (double)k;
    double reach = 1.5 * w + HLC_GUARD_GAP_US + 1000.0;
    double a = predk - (double)(n_max - 1) * T - 0.5 * T - reach;
    double b = predk + 0.5 * T + reach;
    if (!cps_build(d, (long long)floor(a / d->usps), (long long)ceil(b / d->usps) + 1,
                   map_hz(d, SYNC_THRESH_HZ))) {
        return 0;
    }

    // An established fit asks for the comb on every frame missed: a glitch
    // moves all the later syncs, so a comb on only the newest of them is
    // something else (a station starting up in the same mode, which is the
    // comb lock's to take, from its first line). A young fit has nothing to
    // lose and may have been knocked about by noise in a fade right after
    // the header, which a comb over all the missed frames fails on: for it
    // the comb is tried over all of them, then over the newest half, and so
    // on down to SYNC_REACQ_FRAMES.
    int n = 0;
    double best_ph = 0.0;
    double half = 0.5 * T - HLC_PHASE_US;
    for (int nt = n_max; nt >= SYNC_REACQ_FRAMES && n == 0;) {
        // The mode is known and its sync was just there, so the bar is the
        // noise ceiling itself, without the margin a blind lock adds.
        double want = hlc_threshold(nt, m->sync_us) - 0.15;
        if (want > 0.75) break;  // too few lines for a sync this short
        double best = -1e18, ph_best = 0.0;
        for (double ph = -half; ph <= half; ph += HLC_PHASE_US) {
            double sum = 0.0;
            for (int j = 0; j < nt; j++) sum += sync_mf(d, predk - (double)j * T + ph, w);
            if (sum > best) {
                best = sum;
                ph_best = ph;
            }
        }
        double centre = ph_best;
        for (double ph = centre - HLC_PHASE_US; ph <= centre + HLC_PHASE_US;
             ph += HLC_REFINE_US) {
            double sum = 0.0;
            for (int j = 0; j < nt; j++) sum += sync_mf(d, predk - (double)j * T + ph, w);
            if (sum > best) {
                best = sum;
                ph_best = ph;
            }
        }
        int ok = best / (double)nt >= want;
        for (int j = 0; j < nt && ok; j++) {
            ok = sync_mf(d, predk - (double)j * T + ph_best, w) >= HLC_LINE_SCORE;
        }
        if (ok) {
            n = nt;
            best_ph = ph_best;
        } else if (nt == SYNC_REACQ_FRAMES || !young) {
            break;
        } else {
            nt = nt / 2 < SYNC_REACQ_FRAMES ? SYNC_REACQ_FRAMES : nt / 2;
        }
    }
    if (n == 0) return 0;

    best_ph += comb_edge_offset(d, predk + best_ph, T, w, n);
    if (adrift && fabs(best_ph) <= SYNC_GATE_US) return 0;  // ragged edges, not a moved grid

    // (Not for a young fit: it has no grid of its own to be off, and what
    // it is being moved onto is the picture its header announced.)
    double gap_us = (double)(k - d->last_ok_k) * T;
    if (!adrift && !young && gap_us > IMG_REANCHOR_GAP_US &&
        fabs(best_ph) > 3000.0 + 0.002 * gap_us) {
        d->pend_kind = 2;
        d->pend_mode = m;
        d->pend_c = predk + best_ph;
        d->pend_B = d->T_nom;
        d->pend_lines = n;
        d->status = SSTV_STATUS_ABORTED;
        return 0;
    }

    if (young) {
        reg_seed_comb(d, k, predk + best_ph, T, w, n);
        d->fit_refined = 2;
        d->miss_streak = 0;
        d->nohit_streak = 0;
        redecode_frames(d, 0, k);
        return 1;
    }
    for (int i = 0; i < d->reg_n; i++) d->reg_c[i] += best_ph;
    d->A += best_ph;
    d->miss_streak = 0;
    d->nohit_streak = 0;
    redecode_frames(d, k - n + 1, k);
    return 1;
}

// ---------------------------------------------------------------------------
// IMAGE state: sync-track + decode as many frames as the data allows.
// ---------------------------------------------------------------------------
static void image_step(sstv_decoder_t* d)
{
    const sstv_mode_t* m = d->mode;
    for (;;) {
        if (d->frame_idx >= d->n_frames) {
            // An "image" that reached its last row is a complete picture
            // only if a picture was there for most of the frame. Two ways
            // it was not: a mean line score under IMG_MIN_SCORE (noise from
            // top to bottom: a header read out of speech), and a picture
            // that ended less than IMG_DONE_FRAC of the way down (the
            // station stopped, or was joined so late that the frame ran on
            // long past it; the mean cannot see that, twenty good lines
            // outweigh two hundred of noise). Where the picture ended is
            // the frame at which the running sum of (line score -
            // IMG_LINE_FLOOR) peaked: it climbs while syncs arrive, even on
            // one line in three, and falls steadily over noise. Such an
            // image ends ABORTED, so it is not presented, or saved, as a
            // complete picture, and its row count is cut back to the rows
            // that were picture.
            int ext_frames = d->ext_k + 1;
            if (d->score_sum < IMG_MIN_SCORE * (double)d->n_frames ||
                (double)ext_frames < IMG_DONE_FRAC * (double)d->n_frames) {
                int rows = ext_frames * m->rows_per_frame;
                if (rows < d->rows_done) d->rows_done = rows;
                d->status = SSTV_STATUS_ABORTED;
            } else {
                d->status = SSTV_STATUS_DONE;
            }
            return;
        }
        // Comb seed: evaluated once, as soon as its lines are buffered;
        // until then frames decode on the running fit as before.
        if (d->vis_locked && !d->comb_done && comb_seed(d)) d->comb_done = 1;

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
        // On the air a sync's edges are not clean: ripple on the sync tone
        // and the 1500 Hz porch sitting just above the threshold move the
        // measured trailing edge by a millisecond or more either way, line
        // by line, on signals that are otherwise strong. A gate tighter
        // than that scatter keeps only one side of it and walks the fit
        // off the picture; a symmetric one wide enough to hold it lets the
        // regression average it out.
        double tol = SYNC_GATE_US;
        if (young && wnd > tol) tol = wnd;
        double sync_w = m->sync_us * s;
        double half_sync = sync_w * 0.5;
        double r0 = predc - half_sync - wnd;
        double r1 = predc + half_sync + wnd;
        // The re-anchor comb reads the whole line around the prediction,
        // guard boxes included.
        double w1 = predc + 0.5 * T + 1.5 * sync_w + HLC_GUARD_GAP_US + 1000.0;

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
        int hit = find_sync(d, r0, r1, sync_w, young ? -1.0 : predc, &cm) &&
                  fabs(cm - predc) <= tol;
        // Band noise throws up a sync-width run inside the window on one
        // line in four or five. A real sync stands clear of what is either
        // side of it; a run that does not is noise (or picture), and during
        // a fade a fit fed on those wanders off the grid it should hold.
        // (The first sync of a header-locked Martin, Robot or PD image
        // abuts the VIS stop bit, which is at the sync tone too: its left
        // guard is as full as its box, and it is taken on its width alone.)
        int abuts_header = d->vis_locked && k == 0;
        double mf_hit = hit ? (abuts_header ? 1.0 : sync_mf_live(d, cm, sync_w)) : 0.0;
        if (hit && mf_hit < HLC_LINE_SCORE) hit = 0;
        if (hit) {
            reg_add(d, k, cm);
            d->sync_hits++;
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
            } else if (d->fit_refined >= 2 && d->fit_refined < 2 + SYNC_REFIT_STAGES &&
                       d->sync_hits >= (long long)SYNC_REFIT_HITS << (d->fit_refined - 2)) {
                // ... and again as the slope settles: on the air each sync
                // edge is a millisecond or so uncertain, so the slant takes
                // a few dozen lines to pin down, and the top of the picture
                // would otherwise keep the skew of the early estimate.
                d->fit_refined++;
                redecode_frames(d, 0, k);
            }
        }

        // "Present" is looser than a hit: a sync-shaped low at the
        // predicted place, whatever its edges did. A sync that noise or
        // multipath has moved a couple of milliseconds is still there; the
        // image is healthy, the fit just does not learn from it.
        double mf = sync_mf_live(d, predc, sync_w);
        if (hit && mf_hit > mf) mf = mf_hit;
        int present = hit || mf >= HLC_LINE_SCORE;
        int stuck = 0, adrift = 0;
        if (hit) {
            d->nohit_streak = 0;
        } else {
            d->nohit_streak++;
        }
        if (present) {
            d->miss_streak = 0;
            // The sync was there, but nothing has been accepted into the
            // fit for a while: see whether the grid has moved (tried every
            // SYNC_REACQ_FRAMES frames, not every frame).
            adrift = d->nohit_streak >= SYNC_REACQ_FRAMES &&
                     d->nohit_streak % SYNC_REACQ_FRAMES == 0;
        } else {
            d->miss_streak++;
            stuck = d->miss_streak >= SYNC_REACQ_FRAMES;
        }
        if (stuck || adrift) {
            if (image_reanchor(d, k, adrift)) {
                d->sync_attempts--;  // counted again on re-evaluation
                continue;
            }
            if (d->status != SSTV_STATUS_IMAGE) return;  // next transmission
        }
        if (present) {
            // One sync-shaped low where a sync is due proves little: band
            // noise makes one on a line in twenty (one in eight on
            // Martin's short sync), and a station that has taken the
            // frequency in another mode puts one of its own syncs on this
            // image's grid every few seconds (Scottie 1 on a Robot 36
            // grid: every twentieth line). Two within IMG_CONFIRM_US of
            // each other keep the image healthy.
            if (d->last_present_k >= 0 && k - d->last_present_k <= d->ok_gap) d->last_ok_k = k;
            d->last_present_k = k;
        }
        if (hit) {
            // Two frames running whose syncs went into the fit: the audio
            // up to here belongs to this image, and a header-less lock that
            // follows must not reach back past it. (Stricter than "healthy"
            // on purpose. A floor left behind costs nothing; one moved
            // forward by noise takes the first lines off the next picture.)
            if (d->last_hit_k == k - 1) {
                long long fl = (long long)((predc + half_sync) / d->usps);
                if (fl > d->hl_floor) d->hl_floor = fl;
            }
            d->last_hit_k = k;
        }
        // The line score itself, summed, is the image's health: over a
        // dozen lines it tells a weak picture (a sync on one line in three
        // still averages well above zero) from noise (which averages
        // below zero: the guards are as full as the box) far better than
        // counting the lines that pass. (Summed here, past the re-anchor,
        // so a frame that is re-evaluated counts once.)
        d->score_sum += mf;
        d->ext_run += mf - IMG_LINE_FLOOR;
        if (d->ext_run > d->ext_best) {
            d->ext_best = d->ext_run;
            d->ext_k = k;
        }

        decode_frame(d, k);
        d->frame_idx++;

        // Coherence bookkeeping at frame granularity.
        d->coh_tot++;
        if (d->mag_ema > COH_MAG_FRAC * d->mag_base) d->coh_good++;

        // Probation: a header lock that has shown no syncs where it
        // predicts them by now was read out of speech or noise (it happens
        // a few times an hour on a busy band). Stop instead of painting
        // noise until its nominal end. The test is for NO sign of a
        // picture over the whole of the first frames, not for a picture at
        // the moment they end: a real one that fades a second or two after
        // its header has shown its syncs already, and dropping it there
        // lost the whole picture (what was left came back as a second,
        // mis-rowed partial). A comb lock is not judged at all: it was
        // made on syncs.
        if (d->vis_locked && d->frame_idx == d->prob_frames) {
            if (d->score_sum < IMG_PROBATION_SCORE * (double)d->prob_frames) {
                d->status = SSTV_STATUS_ABORTED;
                return;
            }
        }
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
    d->B0 = m->line_us;
    d->reg_n = 0;
    d->reg_pos = 0;
    d->miss_streak = 0;
    d->nohit_streak = 0;
    d->score_sum = 0.0;
    d->ext_run = 0.0;
    d->ext_best = 0.0;
    d->ext_k = -1;
    d->last_present_k = -1;
    d->last_ok_k = -1;
    d->last_hit_k = -1;
    d->ok_gap = (int)(IMG_CONFIRM_US / m->line_us);
    if (d->ok_gap < 1) d->ok_gap = 1;
    d->unhealthy_frames = (int)ceil(IMG_UNHEALTHY_US / m->line_us);
    if (d->unhealthy_frames < HLC_MIN_LINES) d->unhealthy_frames = HLC_MIN_LINES;
    d->prob_frames = (int)ceil(IMG_PROBATION_US / m->line_us);
    if (d->prob_frames < IMG_PROBATION_MIN) d->prob_frames = IMG_PROBATION_MIN;
    if (d->prob_frames > d->n_frames) d->prob_frames = d->n_frames;
    d->fit_refined = 0;
    d->comb_done = 0;
    d->rewritten_row = -1;
    d->sync_attempts = d->sync_hits = 0;
    d->coh_tot = d->coh_good = 0;
    d->mag_base = d->mag_ema;
    d->status = SSTV_STATUS_IMAGE;
    d->wait_n = d->n;  // image_step computes its own needs
    d->hl_next = 0;
    memset(d->hl_pass_seq, 0, sizeof(d->hl_pass_seq));
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

// Sync tone level of the pulse on a comb line centred at c: the middle half
// of the pulse as the det track actually shows it, or of the comb's box
// when the pulse cannot be picked out. (The box is good to a fraction of a
// millisecond, and that far off the middle of Martin's 4.9 ms sync already
// reads its edges: 60 Hz high, 19 grey levels on every pixel.)
static double sync_body_hz(const sstv_decoder_t* d, double c, double w)
{
    double cm;
    long long run[2];
    if (find_sync_run(d, c - 0.5 * w - SYNC_SEARCH_MIN_US, c + 0.5 * w + SYNC_SEARCH_MIN_US,
                      w, c, &cm, run)) {
        double a = t_of(d, run[0]), len = t_of(d, run[1]) - a;
        return win_mean_det(d, a + 0.25 * len, a + 0.75 * len);
    }
    return win_mean_det(d, c - 0.25 * w, c + 0.25 * w);
}

// Lock `m` onto a sync comb, with no header: `c_last` is the sync centre of
// the newest line heard (µs), `B` the line period the comb passed at
// (nominal, or the measured one of a sender whose clock is off), `lines`
// the lines the comb was seen on. The caller has built the below-threshold sums
// (cps_build, uncalibrated threshold) over everything the comb may read.
//
// The lock comes a couple of seconds into the picture — the comb needs its
// lines — but the lines before it are still in the ring: the comb is
// followed back, line by line, for as long as there is a sync on it, and
// frame 0 is the earliest one. A transmission whose header was drowned by
// speech or a carrier is then decoded from its first line (so it also ends
// DONE, on time, instead of being cut short as a partial), and one joined
// mid-image from the first line still buffered. The VIS cells in front of
// the first line are low for 300 ms on end — a sync box there is low, but
// so are its guards, and the walk stops.
static void enter_image_sync(sstv_decoder_t* d, const sstv_mode_t* m, double c_last,
                             double B, int lines)
{
    double w = m->sync_us;
    if (lines < 1) lines = 1;
    // Phase only: the period stays the one the comb passed at (nominal
    // for nearly every sender, whose clocks are within a few hundred ppm).
    // The first lines of a picture whose header was drowned are the ones
    // most likely to be distorted by whatever drowned it — a period fitted
    // freely to them started a strong off-air Scottie 2 picture 900 ppm
    // off. The regression learns the real slant from the syncs themselves
    // (reg_seed_comb, then the tracker), and the first frames are
    // re-decoded as it firms up (SYNC_REFIT_HITS).
    //
    // Unless those lines are clean. Syncs that lie on one straight line to
    // within REG_PRIOR_CLEAN_US are not distorted, and the line through
    // them is the sender's clock: the comb's period only has to be good
    // enough to find them, and left as it is it puts the walk back, and
    // the sync-level measurement below, a little further off the pulses
    // on every line.
    c_last = comb_phase(d, B, w, lines, c_last, 1000.0);
    {
        comb_fit_t f;
        comb_fit_lines(d, c_last, B, w, lines, w / 3.0 + 1000.0, &f);
        if (f.n >= 4 && (double)f.n >= HLC_FIT_SHARE * (double)lines &&
            f.rms <= REG_PRIOR_CLEAN_US) {
            c_last += f.a;
            B += f.slope;
        }
    }
    c_last += comb_edge_offset(d, c_last, B, w, lines);
    double scale = B / m->line_us;

    double lo_us = t_of(d, d->cps_i0);
    double ring_lo = ring_oldest_us(d);
    if (ring_lo > lo_us) lo_us = ring_lo;
    // Calibration: the sync bodies anchor 1200 Hz; with no 1900 Hz
    // reference the slope stays at unity (plain tuning offset). Median over
    // the comb's lines: one of them may have no sync at all.
    double level[HLC_MAX_LINES];
    int nlev = 0;
    for (int j = 0; j < lines && j < HLC_MAX_LINES; j++) {
        double c = c_last - (double)j * B;
        if (sync_mf(d, c, w) < HLC_LINE_SCORE) continue;
        double f = sync_body_hz(d, c, w);
        int i = nlev++;
        while (i > 0 && level[i - 1] > f) {
            level[i] = level[i - 1];
            i--;
        }
        level[i] = f;
    }
    double f_sync = SSTV_FREQ_SYNC;
    if (nlev > 0) {
        f_sync = (nlev & 1) ? level[nlev / 2] : 0.5 * (level[nlev / 2 - 1] + level[nlev / 2]);
    }

    // Walk back from the newest line. The comb itself tolerates a line
    // without a sync, and that line may be the one before the picture
    // began; so every line, the comb's own included, has to show its sync
    // to become frame 0 (HLC_BACK_MISSES faded lines in a row are bridged).
    // "Shows its sync" is the comb's line score AND a tone at the sync
    // level: on Martin's 4.9 ms sync a box holds three independent samples,
    // and loud noise in front of the picture passes the score alone one
    // time in eight, which would start the picture a row early.
    // A frame starts before its sync (Scottie: most of a line before), so
    // the walk also ends where a frame would begin outside the history.
    int oldest = 0;  // comb line index (0 = newest) of frame 0
    int misses = 0;
    for (int j = 0;; j++) {
        double c = c_last - (double)j * B;
        double F = c - (m->sync_offset_us + 0.5 * w) * scale;
        if (F - 2000.0 < lo_us) break;
        if (c - 1.5 * w - HLC_GUARD_GAP_US < lo_us) break;
        // A line reached across a faded one has to be an unmistakable sync:
        // noise in front of the picture passes the plain line score on one
        // line in twenty, and bridging to it put two rows of noise on top
        // of the picture (and cost it its last two).
        double need = misses > 0 ? HLC_BRIDGE_SCORE : HLC_LINE_SCORE;
        if (sync_mf(d, c, w) >= need &&
            win_mean_det(d, c - 0.25 * w, c + 0.25 * w) <= f_sync + HLC_LEVEL_TOL_HZ) {
            oldest = j;
            misses = 0;
        } else if (++misses > HLC_BACK_MISSES) {
            break;
        }
    }
    int nl = oldest + 1;

    if (!enter_image(d, m)) return;
    d->cal0 = f_sync;
    d->cal_slope = 1.0;
    d->vis_locked = 0;
    d->B = B;
    d->B0 = B;
    double A0 = c_last - (double)oldest * B;
    d->A = A0;
    // Seed the regression with the syncs on the comb's lines, so the
    // tracker starts mature (narrow window) on what those lines say about
    // the sender's clock.
    reg_seed_comb(d, nl - 1, c_last, B, w * scale, nl);
    d->fit_refined = 2;
    d->comb_done = 1;
    d->t_img = frame_start_us(d, 0);
    evt_push(d, SSTV_EVT_SYNC_LOCK, m->id, -1, f_sync - SSTV_FREQ_SYNC);
}

// ---------------------------------------------------------------------------
// VIS evaluation
// ---------------------------------------------------------------------------
// Fallback calibration from the VIS data + parity cells (see vis_evaluate):
// two-means split of the eight cell means. Accepts only a spread that fits
// 1100/1300 Hz under a scale of VIS_CELLS_SLOPE_MIN..1.3, and a leader level
// m_l within VIS_CELLS_LEADER_TOL_HZ of where that calibration puts 1900 Hz.
// Returns 1 with the measured 1200 Hz level and the scale.
#define VIS_CELLS_SLOPE_MIN     0.55
#define VIS_CELLS_LEADER_TOL_HZ 150.0
static int vis_cells_calibrate(const sstv_decoder_t* d, double t0, double m_l,
                               double* split_out, double* slope_out)
{
    double cell[8];
    double lo = 1e9, hi = -1e9;
    for (int c = 0; c < 8; c++) {
        double center = t0 + (double)(c + 1) * VIS_CELL_US + VIS_CELL_US * 0.5;
        cell[c] = win_mean_det(d, center - VIS_HALF_WIN_US, center + VIS_HALF_WIN_US);
        if (cell[c] < lo) lo = cell[c];
        if (cell[c] > hi) hi = cell[c];
    }
    for (int it = 0; it < 8; it++) {
        double mid = 0.5 * (lo + hi);
        double sl = 0.0, sh = 0.0;
        int nl = 0, nh = 0;
        for (int c = 0; c < 8; c++) {
            if (cell[c] < mid) { sl += cell[c]; nl++; }
            else { sh += cell[c]; nh++; }
        }
        if (nl == 0 || nh == 0) return 0;  // one tone only: nothing to split
        lo = sl / nl;
        hi = sh / nh;
    }
    double slope = (hi - lo) / 200.0;
    if (slope < VIS_CELLS_SLOPE_MIN || slope > 1.3) return 0;
    double split = 0.5 * (lo + hi);
    double want_leader = split + (SSTV_FREQ_LEADER - SSTV_FREQ_SYNC) * slope;
    if (fabs(m_l - want_leader) > VIS_CELLS_LEADER_TOL_HZ) return 0;
    *split_out = split;
    *slope_out = slope;
    return 1;
}

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
//
// `why_out` (may be NULL) receives 0 on acceptance or the SSTV_VISREJ_*
// reason for a rejection; `code_out` (may be NULL) the 7-bit VIS code once
// the data cells were read, else -1. These feed the diagnostics ring for
// the live hunt.
static const sstv_mode_t* vis_evaluate(const sstv_decoder_t* d, const hdr_t* h,
                                       int strict, double* t0_out,
                                       double* cal0_out, double* slope_out,
                                       int* why_out, int* code_out)
{
    double t0 = t_of(d, h->vis_t0);
    int code = 0, ones = 0;
    int forced = d->forced_mode >= 0;
    int why_dummy, code_dummy;
    if (!why_out) why_out = &why_dummy;
    if (!code_out) code_out = &code_dummy;
    *why_out = 0;
    *code_out = -1;

    if (strict &&
        (double)(h->vis_t0 - h->leader_at) * d->usps < HDR_STRICT_LEADER_US) {
        *why_out = SSTV_VISREJ_LEADER_LEVEL;
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
    if (fabs(m_l - SSTV_FREQ_LEADER) > LEADER_MEAN_TOL_HZ) {
        *why_out = SSTV_VISREJ_LEADER_LEVEL;
        return 0;
    }
    double m_s = win_mean_det(d, t0 + 5000.0, t0 + 25000.0);
    double slope = (m_l - m_s) / (SSTV_FREQ_LEADER - SSTV_FREQ_SYNC);
    double stop_center = t0 + 9.0 * VIS_CELL_US + VIS_CELL_US * 0.5;
    double stop_mf = win_mean_det(d, stop_center - VIS_HALF_WIN_US,
                                  stop_center + VIS_HALF_WIN_US);
    int cells_cal = 0;
    if (!strict && !forced &&
        (slope < 0.7 || slope > 1.3 || fabs(stop_mf - m_s) > STOP_TOL_HZ)) {
        // The start bit is one 30 ms cell, read 5-25 ms in, right after the
        // leader's falling edge: on a weak signal the smoothing and noise
        // lift it far enough that the scale (or the stop-bit comparison)
        // fails while the data cells are still plainly readable (an AG5RT
        // 20 m Martin 1 header: start bit 1330 Hz, scale 0.70, cells a clean
        // 1340/1190 Hz split). Calibrate on the cells instead: their two tone
        // groups straddle 1200 Hz symmetrically, so their midpoint is the
        // 1200 Hz level and their spread the scale. The leader must agree
        // with that scale, and the stop-bit and parity checks below still
        // apply against it.
        double split2, slope2;
        if (vis_cells_calibrate(d, t0, m_l, &split2, &slope2)) {
            m_s = split2;
            slope = slope2;
            cells_cal = 1;
        }
    }
    if (!cells_cal && (slope < 0.7 || slope > 1.3)) {
        if (!forced || strict) {
            // Not a credible start bit level.
            *why_out = SSTV_VISREJ_START_LEVEL;
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
    int stop_ok = fabs(stop_mf - m_s) <= STOP_TOL_HZ;

    // The operator's mode lock bypasses the VIS payload entirely — the
    // header found above still anchors timing and calibration, but the
    // data/parity cells (the parts QRM garbles first) are not read.
    if (forced) {
        if (strict && !stop_ok) {
            *why_out = SSTV_VISREJ_STOP_BIT;
            return 0;
        }
        const sstv_mode_t* fm = sstv_mode_get(d->forced_mode);
        *code_out = fm ? fm->vis_code : -1;
        return fm;
    }
    if (!stop_ok) {
        *why_out = SSTV_VISREJ_STOP_BIT;
        return 0;
    }

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
        if (total <= 0) {
            *why_out = SSTV_VISREJ_NO_AUDIO;
            return 0;
        }
        double mf = win_mean_det(d, a, b);
        int bit = (mf < split) ? 1 : 0;
        if (strict) {
            double want = split + (bit ? -100.0 : 100.0) * slope;
            if (fabs(mf - want) > VIS_STRICT_TOL_HZ) {
                *why_out = SSTV_VISREJ_START_LEVEL;
                return 0;
            }
        }
        if (c < 7) {
            code |= bit << c;
        }
        ones += bit;
    }

    *code_out = code;
    if (ones & 1) {  // even parity over data+parity bits
        *why_out = SSTV_VISREJ_PARITY;
        return 0;
    }
    const sstv_mode_t* m = sstv_mode_by_vis(code);
    if (!m) *why_out = SSTV_VISREJ_UNKNOWN_MODE;
    return m;
}

// ---------------------------------------------------------------------------
// Header hunt: one det-track step of IDLE -> LEADER -> VIS. Returns 1 when
// the machine sits in VIS with every cell available (evaluate now).
// ---------------------------------------------------------------------------
static int hdr_step(sstv_decoder_t* d, hdr_t* h, long long cur)
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
            // Only the live hunt reports: the shadow hunt under IMAGE hears
            // "leaders" in every mid-gray stretch of picture.
            if (h == &d->hdr) {
                evt_push(d, SSTV_EVT_LEADER_TIMEOUT, 0, -1, h->f_off);
            }
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
// Header-less lock: one sync-comb evaluation over the audio up to det index
// `cur` (see "Sync comb" above). Returns the mode whose comb has now passed
// two evaluations in a row at one phase — its newest sync centre in *c_out
// (µs), the lines it spans in *lines_out — or NULL.
//
// Candidates are tried in preference order; among the modes that pass, the
// shortest line period wins (a Robot 36 train also fills every slot of a
// Robot 72 comb, never the reverse), and of two modes with identical timing
// (Martin 1/3, Martin 2/4) only the earlier, more common one is tried. A
// forced mode restricts the search to that mode.
//
// A comb is rigid: a sender whose clock is off walks its syncs out of the
// boxes across the comb's lines (Martin 1 at 0.15 % moves 7 ms over twelve
// lines, more than its sync is wide), and at the nominal period such a
// picture never locks. So when a mode's nominal comb fails, the comb is
// also tried at line periods either side of nominal, out to
// ±HLC_CLOCK_MAX, in steps that let the syncs drift a third of their width
// at most between one step and the next. That is several times the work of
// the nominal comb, so it runs on every HLC_CLOCK_EVERY-th evaluation only.
//
// Many more combs are that many more chances for noise, or for a picture in
// another mode, to pass one. An off-nominal comb therefore has to show more
// than its score: its syncs, located line by line, must lie on one straight
// line (HLC_FIT_SHARE of the lines, to within HLC_FIT_RMS_US), the period
// that line gives must still be off nominal (a step that passes on a
// sender who is on nominal is the nominal comb's business, a moment
// later), and the comb laid at exactly that period must pass too. That
// measured period is the one tried again on the very next evaluation,
// which is what confirms it. *T_out receives the period the comb passed at.
// ---------------------------------------------------------------------------
static int hl_comb_pass(const sstv_decoder_t* d, const sstv_mode_t* m, double T, int L,
                        comb_hit_t* h)
{
    return comb_blind(d, T, m->sync_us, L, h) && h->score >= hlc_threshold(L, m->sync_us) &&
           h->lines_ok >= L - (L >= 8 ? 1 : 0);
}

static const sstv_mode_t* hl_scan(sstv_decoder_t* d, long long cur, double* c_out,
                                  int* lines_out, double* T_out)
{
    static const int kPref[] = {
        SSTV_MODE_ROBOT36,  SSTV_MODE_ROBOT72, SSTV_MODE_MARTIN1,
        SSTV_MODE_MARTIN2,  SSTV_MODE_SCOTTIE1, SSTV_MODE_SCOTTIE2,
        SSTV_MODE_PD50,     SSTV_MODE_PD90,    SSTV_MODE_PD120,
        SSTV_MODE_PD160,    SSTV_MODE_PD180,   SSTV_MODE_PD240,
        SSTV_MODE_PD290,    SSTV_MODE_SCOTTIEDX, SSTV_MODE_MARTIN3,
        SSTV_MODE_MARTIN4,
    };
    const int n_pref = (int)(sizeof(kPref) / sizeof(kPref[0]));

    d->hl_next = cur + (long long)(HLC_EVAL_US / d->usps);
    d->hl_seq++;
    long long end = cur + 1;
    long long i0 = end - (long long)((HLC_SPAN_US + 100000.0) / d->usps);
    if (i0 < d->hl_floor) i0 = d->hl_floor;
    if (!cps_slide(d, i0, end)) return 0;

    const sstv_mode_t* best = 0;
    double best_c = 0.0, best_T = 0.0;
    int best_lines = 0;
    int sweep = d->hl_seq % HLC_CLOCK_EVERY == 0;
    for (int p = 0; p < n_pref; p++) {
        const sstv_mode_t* m = sstv_mode_get(kPref[p]);
        if (!m) continue;
        if (d->forced_mode >= 0) {
            if (kPref[p] != d->forced_mode) continue;
        } else {
            int dup = 0;
            for (int q = 0; q < p && !dup; q++) {
                const sstv_mode_t* o = sstv_mode_get(kPref[q]);
                dup = o && o->line_us == m->line_us && o->sync_us == m->sync_us;
            }
            if (dup) continue;
        }
        int L = hlc_lines(m->line_us, m->sync_us);
        comb_hit_t h;
        double T = m->line_us;
        int prev = d->hl_pass_seq[m->id] == d->hl_seq - 1;
        int pass = hl_comb_pass(d, m, T, L, &h);
        if (!pass && prev && d->hl_pass_T[m->id] != m->line_us) {
            // The off-nominal period that passed a moment ago.
            T = d->hl_pass_T[m->id];
            pass = hl_comb_pass(d, m, T, L, &h);
        }
        if (!pass && sweep && L > 1) {
            double step = 2.0 * m->sync_us / (3.0 * (double)(L - 1) * m->line_us);
            int n_e = (int)ceil(HLC_CLOCK_MAX / step);
            comb_hit_t hb, hc;
            int found = 0;
            for (int i = 1; i <= n_e; i++) {
                for (int sg = -1; sg <= 1; sg += 2) {
                    double Te = m->line_us * (1.0 + (double)(sg * i) * step);
                    if (hl_comb_pass(d, m, Te, L, &hb) && (!found || hb.score > hc.score)) {
                        found = 1;
                        hc = hb;
                        T = Te;
                    }
                }
            }
            if (found) {
                comb_fit_t f;
                comb_fit_lines(d, hc.c_last, T, m->sync_us, L, m->sync_us / 3.0 + 1000.0, &f);
                double Tm = T + f.slope;
                if ((double)f.n >= HLC_FIT_SHARE * (double)L && f.rms <= HLC_FIT_RMS_US &&
                    fabs(Tm / m->line_us - 1.0) > 0.5 * step &&
                    fabs(Tm / m->line_us - 1.0) <= HLC_CLOCK_MAX + step &&
                    hl_comb_pass(d, m, Tm, L, &h)) {
                    pass = 1;
                    T = Tm;
                }
            }
        }
        if (!pass) {
            d->hl_pass_seq[m->id] = 0;
            continue;
        }
        int confirmed = 0;
        if (prev && d->hl_pass_T[m->id] == T) {
            double r = fmod(h.c_last - d->hl_pass_c[m->id], T);
            if (r < 0.0) r += T;
            if (r > 0.5 * T) r -= T;
            confirmed = fabs(r) <= HLC_CONFIRM_TOL_US;
        }
        d->hl_pass_seq[m->id] = d->hl_seq;
        d->hl_pass_c[m->id] = h.c_last;
        d->hl_pass_T[m->id] = T;
        if (confirmed && (!best || m->line_us < best->line_us - 1.0)) {
            best = m;
            best_c = h.c_last;
            best_T = T;
            best_lines = L;
        }
    }
    if (!best) return 0;

    // A mode with half the line period and the same sync (Robot 36 under a
    // Robot 72 comb) fills every slot of this comb AND the slots half-way
    // between; under speech its own comb, which needs twice the lines in a
    // row, can fail to pass while this one does. If the half-way slots hold
    // syncs too, it is the faster mode.
    if (d->forced_mode < 0) {
        for (int p = 0; p < n_pref; p++) {
            const sstv_mode_t* h = sstv_mode_get(kPref[p]);
            if (!h || h->sync_us != best->sync_us ||
                fabs(2.0 * h->line_us / best->line_us - 1.0) > 0.01) {
                continue;
            }
            double mid = 0.0;
            for (int j = 0; j < best_lines; j++) {
                mid += sync_mf(d, best_c - ((double)j + 0.5) * best_T, best->sync_us);
            }
            if (mid / (double)best_lines >= HLC_LINE_SCORE) {
                best_T *= h->line_us / best->line_us;
                best = h;
                best_lines = hlc_lines(h->line_us, h->sync_us);
            }
            break;
        }
    }
    *c_out = best_c;
    *lines_out = best_lines;
    *T_out = best_T;
    return best;
}

// Build the comb sums over everything a header-less lock may read: from the
// end of the previous image (hl_floor) to the newest det sample.
static int cps_build_history(sstv_decoder_t* d)
{
    long long lo = d->n - (long long)d->ring_size + 1;
    if (lo < d->hl_floor) lo = d->hl_floor;
    return cps_build(d, lo, det_end(d), SYNC_THRESH_HZ);
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
                int why, code;
                double f_off = d->hdr.f_off;
                const sstv_mode_t* m = vis_evaluate(d, &d->hdr, 0, &t0, &cal0, &slope,
                                                    &why, &code);
                hdr_init(&d->hdr);
                if (m) {
                    evt_push(d, SSTV_EVT_VIS_ACCEPTED, m->id, code, f_off);
                    enter_image_vis(d, m, t0, cal0, slope);
                    break;
                }
                evt_push(d, SSTV_EVT_VIS_REJECTED, why, code, f_off);
            }
            d->status = d->hdr.st;
            // Header-less lock runs alongside the header hunt: a sync comb
            // locks even when no VIS was (or will be) heard.
            if (cur >= d->hl_next) {
                double c_last, T;
                int lines;
                const sstv_mode_t* m = hl_scan(d, cur, &c_last, &lines, &T);
                if (m && cps_build_history(d)) {
                    enter_image_sync(d, m, c_last, T, lines);
                }
            }
            break;

        case SSTV_STATUS_IMAGE: {
            if (cur >= d->wait_n) image_step(d);
            if (d->status == SSTV_STATUS_IMAGE &&
                d->miss_streak > MISS_LIMIT &&
                d->mag_ema < MAG_COLLAPSE_FRAC * d->mag_base) {
                d->status = SSTV_STATUS_ABORTED;
            }
            // An image whose sync has been gone for IMG_UNHEALTHY_US is not
            // being received right now — a deep fade, a transmission that
            // stopped, a lock that was never a picture. It plays on to its
            // nominal end in case the signal comes back, but it no longer
            // shuts out the next transmission: any credible header, and any
            // sync comb, takes over.
            // "Unhealthy" (no two syncs near each other for that long)
            // lets a sync comb on another grid take over: a comb is hard
            // evidence, so the test for letting it in can be quick. A
            // header is easier to imitate, so the relaxed header profile
            // waits for the stricter "silent": nothing sync-shaped at all.
            int unhealthy = d->frame_idx - 1 - d->last_ok_k >= d->unhealthy_frames;
            int silent = d->miss_streak >= d->unhealthy_frames;
            // VIS preemption: a new calibration header under the running
            // image ends it (partial rows retained); the pending lock is
            // applied by the reset that follows. Under a healthy image only
            // a header image content cannot imitate counts.
            if (d->status == SSTV_STATUS_IMAGE && hdr_step(d, &d->shadow, cur)) {
                double t0, cal0, slope;
                const sstv_mode_t* m = vis_evaluate(d, &d->shadow, !silent, &t0, &cal0,
                                                    &slope, 0, 0);
                hdr_init(&d->shadow);
                if (m) {
                    d->pend_kind = 1;
                    d->pend_mode = m;
                    d->pend_t0 = t0;
                    d->pend_cal0 = cal0;
                    d->pend_slope = slope;
                    d->status = SSTV_STATUS_ABORTED;
                }
            }
            if (d->status == SSTV_STATUS_IMAGE && unhealthy && cur >= d->hl_next) {
                double c_last, T;
                int lines;
                const sstv_mode_t* m = hl_scan(d, cur, &c_last, &lines, &T);
                if (m && m == d->mode) {
                    // This image's own syncs, back on its own grid after a
                    // fade: the tracker picks them up; nothing to preempt.
                    double r = fmod(c_last - d->A, d->B);
                    if (r < 0.0) r += d->B;
                    if (r > 0.5 * d->B) r -= d->B;
                    if (fabs(r) <= 0.5 * m->sync_us) m = 0;
                }
                if (m) {
                    d->pend_kind = 2;
                    d->pend_mode = m;
                    d->pend_c = c_last;
                    d->pend_B = T;
                    d->pend_lines = lines;
                    d->status = SSTV_STATUS_ABORTED;
                }
            }
            // The image ended on this sample: what was heard up to here is
            // its audio, not the start of the next picture. (A pending comb
            // lock keeps the floor its last sync set — the new picture's
            // first lines lie between there and here.)
            if (d->status != SSTV_STATUS_IMAGE && d->pend_kind != 2 && cur + 1 > d->hl_floor) {
                d->hl_floor = cur + 1;
            }
            break;
        }

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

int sstv_decoder_take_rewritten_row(sstv_decoder_t* d)
{
    if (!d || !d->mode) return -1;
    int r = d->rewritten_row;
    d->rewritten_row = -1;
    return r;
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
