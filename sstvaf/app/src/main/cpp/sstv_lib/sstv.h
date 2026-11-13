// sstv.h — public API of the clean-room SSTV codec (sstv_lib).
//
// Pure C11, no Android/JNI dependencies; host-compilable. Implemented from
// published mode specifications only — see SOURCES.md for the exact sources
// and the licensing statement.
//
// Encoder: whole-buffer, phase-continuous, absolute-time-accurate synthesis
// of a complete SSTV transmission (calibration header + VIS + image).
// Decoder: handle-based push model; feed arbitrary-size float sample blocks,
// poll status/rows.

#ifndef SSTV_LIB_SSTV_H
#define SSTV_LIB_SSTV_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// ---------------------------------------------------------------------------
// Mode ids
// ---------------------------------------------------------------------------
enum {
    SSTV_MODE_ROBOT36 = 0,
    SSTV_MODE_ROBOT72,
    SSTV_MODE_MARTIN1,
    SSTV_MODE_MARTIN2,
    SSTV_MODE_SCOTTIE1,
    SSTV_MODE_SCOTTIE2,
    SSTV_MODE_PD50,
    SSTV_MODE_PD90,
    SSTV_MODE_PD120,
    // Appended in a later batch (issue #16). New ids MUST be appended, never
    // renumbered — the id crosses the JNI boundary verbatim (see SstvMode.kt).
    SSTV_MODE_SCOTTIEDX,
    SSTV_MODE_MARTIN3,
    SSTV_MODE_MARTIN4,
    SSTV_MODE_PD160,
    SSTV_MODE_PD180,
    SSTV_MODE_PD240,
    SSTV_MODE_PD290,
    SSTV_NUM_MODES
};

// ---------------------------------------------------------------------------
// Decoder status
// ---------------------------------------------------------------------------
enum {
    SSTV_STATUS_IDLE = 0,   // hunting for the 1900 Hz leader / a sync train
    SSTV_STATUS_LEADER,     // leader seen; hunting for the VIS start bit
    SSTV_STATUS_VIS,        // start bit seen; sampling the VIS cells
    SSTV_STATUS_IMAGE,      // VIS accepted; decoding image lines
    SSTV_STATUS_DONE,       // full image decoded
    SSTV_STATUS_ABORTED     // signal lost mid-image (partial image retained)
};

// ---------------------------------------------------------------------------
// Error codes (negative returns)
// ---------------------------------------------------------------------------
enum {
    SSTV_ERR_BAD_MODE = -1,
    SSTV_ERR_BAD_ARGS = -2,
    SSTV_ERR_CAPACITY = -3,
    SSTV_ERR_RANGE    = -4,
    SSTV_ERR_NOMEM    = -5
};

// ---------------------------------------------------------------------------
// Encoder
// ---------------------------------------------------------------------------

// Encode flags for sstv_encode_ex().
//
// SWAP_ROBOT36_PARITY: transmit Robot 36's alternating chroma starting with
// B-Y (2300 Hz separator) on the first line instead of R-Y (1500 Hz). Some
// real-world encoders do this; the decoder keys the chroma pairing off the
// measured separator tone, so it must decode either. Exists so tests can
// exercise that robustness; normal callers pass 0.
#define SSTV_ENCODE_SWAP_ROBOT36_PARITY 0x1

// Exact number of float samples sstv_encode() will produce for this mode at
// this sample rate (header + VIS + full image). Negative error on bad args.
int sstv_encode_num_samples(int mode_id, int sample_rate);

// Encode a full transmission into out[]. argb is 0xAARRGGBB, row-major,
// exactly width x height, which must match the mode's native dimensions
// (SSTV_ERR_BAD_ARGS otherwise). Returns samples written (equal to
// sstv_encode_num_samples()) or a negative error.
int sstv_encode(int mode_id, const uint32_t* argb, int width, int height,
                int sample_rate, float amplitude, float* out, int out_capacity);

// As sstv_encode, with SSTV_ENCODE_* flags.
int sstv_encode_ex(int mode_id, const uint32_t* argb, int width, int height,
                   int sample_rate, float amplitude, int flags,
                   float* out, int out_capacity);

// ---------------------------------------------------------------------------
// Decoder (handle-based, push model)
// ---------------------------------------------------------------------------
typedef struct sstv_decoder sstv_decoder_t;

sstv_decoder_t* sstv_decoder_create(int sample_rate);
void   sstv_decoder_push(sstv_decoder_t* d, const float* samples, int n);
int    sstv_decoder_status(const sstv_decoder_t* d);   // SSTV_STATUS_*
int    sstv_decoder_mode(const sstv_decoder_t* d);     // mode id or -1
int    sstv_decoder_rows_ready(const sstv_decoder_t* d);

// Copy up to n_rows decoded rows starting at first_row into argb (row-major,
// mode width). Returns rows actually copied (limited by rows_ready), or a
// negative error (no mode yet / bad args).
int    sstv_decoder_read_rows(const sstv_decoder_t* d, int first_row,
                              int n_rows, uint32_t* argb);

// Estimated sample-clock slant in parts-per-million (+ = lines arriving
// slower than nominal). 0 until enough syncs have been tracked.
float  sstv_decoder_slant_ppm(const sstv_decoder_t* d);

// 0..1 blend of sync-hit-rate and in-band signal coherence.
float  sstv_decoder_quality(const sstv_decoder_t* d);

// How the current image was locked: 1 = timed off a decoded VIS header,
// 0 = locked onto the line-sync train alone (transmission joined
// mid-image, or its header was unreadable; rows then start at the top of
// the frame). 0 before any lock.
int    sstv_decoder_vis_locked(const sstv_decoder_t* d);

// Manual mode lock. With a forced mode set (SSTV_MODE_*), the decoder still
// hunts for the calibration header (leader + VIS start bit) — that anchors
// line timing and frequency calibration — but ignores the VIS data, parity
// and stop-bit checks and decodes as `mode_id`, so a transmission whose VIS
// is garbled by QRM/QSB still produces an image. Pass -1 to return to
// automatic VIS mode selection. Takes effect at the next header detection;
// an in-flight image decode is not disturbed. The lock survives
// sstv_decoder_reset() — it is an operator setting, not decode state.
// Returns 0, or SSTV_ERR_BAD_ARGS for a null handle / unknown mode id.
int    sstv_decoder_set_forced_mode(sstv_decoder_t* d, int mode_id);
int    sstv_decoder_forced_mode(const sstv_decoder_t* d);  // id or -1

// ---------------------------------------------------------------------------
// Header-hunt diagnostics
// ---------------------------------------------------------------------------
// The status API cannot tell "no leader was ever heard" from "a header was
// read and thrown away", and that difference is the whole diagnosis when an
// operator reports audible SSTV that never produced a picture. The live
// header hunt therefore records what it did with every candidate header
// (the shadow hunt that runs under IMAGE is silent — image content imitates
// headers routinely and its rejections mean nothing). A receiver drains the
// queue with sstv_decoder_events() and logs it.
enum {
    // A 1900 Hz leader was recognised but no VIS start bit followed within
    // the leader timeout. f_off_hz carries the measured tuning offset.
    SSTV_EVT_LEADER_TIMEOUT = 1,
    // A start bit was found but the header was rejected: arg = SSTV_VISREJ_*,
    // arg2 = the 7-bit VIS code when the cells were read (parity / unknown
    // mode), else -1.
    SSTV_EVT_VIS_REJECTED   = 2,
    // A header was accepted: arg = mode id, arg2 = VIS code (the mode's own
    // code under a forced mode).
    SSTV_EVT_VIS_ACCEPTED   = 3,
    // The header-less sync-train lock picked a mode: arg = mode id.
    SSTV_EVT_SYNC_LOCK      = 4
};

enum {
    SSTV_VISREJ_LEADER_LEVEL = 1,  // tone before the start bit not near 1900 Hz
    SSTV_VISREJ_START_LEVEL  = 2,  // start-bit level not credible (slope out of range)
    SSTV_VISREJ_STOP_BIT     = 3,  // stop bit not at the 1200 Hz level
    SSTV_VISREJ_PARITY       = 4,  // data + parity cells failed even parity
    SSTV_VISREJ_NO_AUDIO     = 5,  // a VIS cell fell outside the audio history
    SSTV_VISREJ_UNKNOWN_MODE = 6   // clean VIS code, but no mode has it
};

typedef struct {
    int    type;       // SSTV_EVT_*
    int    arg, arg2;  // per type, see above
    double t_s;        // decoder time of the event: seconds of audio pushed
    double f_off_hz;   // measured leader tuning offset (Hz); 0 when n/a
} sstv_event_t;

// Queue depth. Older events are dropped when a caller falls behind.
#define SSTV_EVENT_CAPACITY 16

// Drain up to `max` pending events into `out`, oldest first; returns the
// count (0 for a null handle). The queue survives sstv_decoder_reset() —
// the rejection that explains a miss happens right before the reset that
// follows the previous image.
int    sstv_decoder_events(sstv_decoder_t* d, sstv_event_t* out, int max);

// Drop the current image and go back to hunting. The demodulated audio
// history is kept (only the state machine rewinds), so a calibration header
// that preempted the previous image (status ABORTED with a new header
// decoded underneath it) puts the decoder straight into IMAGE for the new
// transmission — check sstv_decoder_status() after a reset, not before.
void   sstv_decoder_reset(sstv_decoder_t* d);
void   sstv_decoder_destroy(sstv_decoder_t* d);

#ifdef __cplusplus
}
#endif

#endif // SSTV_LIB_SSTV_H
