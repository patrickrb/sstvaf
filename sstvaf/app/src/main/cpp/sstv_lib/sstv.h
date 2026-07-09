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
    SSTV_STATUS_IDLE = 0,   // hunting for the 1900 Hz leader
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

void   sstv_decoder_reset(sstv_decoder_t* d);
void   sstv_decoder_destroy(sstv_decoder_t* d);

#ifdef __cplusplus
}
#endif

#endif // SSTV_LIB_SSTV_H
