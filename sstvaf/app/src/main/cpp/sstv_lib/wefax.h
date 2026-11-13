// wefax.h — clean-room WeFax / HF radiofax codec (part of sstv_lib).
//
// WeFax (marine/aviation weather facsimile) is a DISTINCT modulation from the
// VIS+scanline SSTV family in sstv.h / sstv_modes.c. There is no VIS code and
// no per-mode segment table; a transmission is a continuous stream of scan
// lines whose left margin is established by a *phasing* signal rather than a
// per-line sync pulse. See SOURCES.md ("WeFax / HF radiofax") for the timing
// and IOC basis.
//
// Modulation: frequency-shift keyed, 800 Hz shift around a 1900 Hz center —
// black = 1500 Hz, white = 2300 Hz (identical band to the SSTV pixel scan, so
// the SSTV FM discriminator in sstv_demod.c is reused verbatim). Line rate is
// given in lines-per-minute (LPM, typically 120); the horizontal pixel count
// is fixed by the Index Of Cooperation (IOC, typically 576).
//
// Pure C11, host-compilable, no Android/JNI dependencies. The decision and
// geometry logic (frequency<->pixel mapping, line rendering, phasing-pulse
// location, line classification) is exposed as small pure functions so the
// host tests can exercise it directly; the push-model decoder is a thin
// wrapper over those plus the shared FM discriminator.

#ifndef SSTV_LIB_WEFAX_H
#define SSTV_LIB_WEFAX_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// ---------------------------------------------------------------------------
// Reference frequencies (Hz) and the standard IOC / line-rate values.
// ---------------------------------------------------------------------------
#define WEFAX_CENTER_HZ 1900.0
#define WEFAX_SHIFT_HZ   800.0
#define WEFAX_BLACK_HZ  1500.0   // center - shift/2
#define WEFAX_WHITE_HZ  2300.0   // center + shift/2
#define WEFAX_STOP_HZ    450.0   // APT stop-signal keying rate

#define WEFAX_IOC_576 576        // standard HF radiofax (marine/aviation)
#define WEFAX_IOC_288 288        // half-resolution variant

// Fraction of a line occupied by the white phasing pulse (leading edge marks
// the left margin). A common radiofax phasing line is ~5% white / ~95% black.
#define WEFAX_PHASE_PULSE_FRAC 0.05

// ---------------------------------------------------------------------------
// Decoder status
// ---------------------------------------------------------------------------
enum {
    WEFAX_STATUS_IDLE = 0,   // no phasing seen yet (pre-lock content ignored)
    WEFAX_STATUS_PHASING,    // phasing line(s) seen, line phase locked-in
    WEFAX_STATUS_IMAGE,      // locked; emitting picture rows
    WEFAX_STATUS_DONE        // finish() called
};

// ---------------------------------------------------------------------------
// Error codes (negative returns)
// ---------------------------------------------------------------------------
enum {
    WEFAX_ERR_BAD_ARGS = -1,
    WEFAX_ERR_CAPACITY = -2,
    WEFAX_ERR_NOMEM    = -3
};

// A classified line is either picture content or a phasing line.
typedef enum {
    WEFAX_LINE_PICTURE = 0,
    WEFAX_LINE_PHASING
} wefax_line_class_t;

// ---------------------------------------------------------------------------
// Pure timing / mapping helpers (fully unit-testable, no state)
// ---------------------------------------------------------------------------

// Pixels per scan line for an IOC: round(ioc * pi). 0 on non-positive ioc.
int    wefax_pixels_per_line(int ioc);

// Samples per scan line: sample_rate * 60 / lpm. 0 on bad args.
double wefax_samples_per_line(int lpm, int sample_rate);

// Frequency (Hz) -> 8-bit gray. black(1500 Hz)->0, white(2300 Hz)->255,
// clamped to [0,255].
int    wefax_freq_to_gray(double hz);

// 8-bit gray -> frequency (Hz), the exact inverse of wefax_freq_to_gray's
// mapping (input clamped to [0,255]).
double wefax_gray_to_freq(int gray);

// APT start-tone keying rate for an IOC: 300 Hz (IOC 576) / 675 Hz (IOC 288).
// Negative on an unknown IOC.
double wefax_start_tone_hz(int ioc);

// APT stop-tone keying rate (450 Hz), independent of IOC.
double wefax_stop_tone_hz(void);

// Nearest standard IOC for a measured start-tone rate (300->576, 675->288),
// within a small tolerance; -1 if it matches neither.
int    wefax_ioc_from_start_tone(double hz);

// ---------------------------------------------------------------------------
// Pure line geometry (used by the decoder, exposed for tests)
// ---------------------------------------------------------------------------

// Box-average `n` demodulated frequencies (Hz), spanning exactly one line,
// into `width` gray pixels. Column c covers freq_hz[c*n/width .. (c+1)*n/width).
void wefax_render_line(const double* freq_hz, int n, int width,
                       uint8_t* gray_out);

// Classify a rendered gray line. A phasing line is mostly black with a single
// (possibly wrap-split) white pulse; *pulse_col receives the leading-edge
// column of that pulse (may be NULL). For a picture line *pulse_col is set to
// -1. Robust to the pulse straddling the free-run line boundary.
wefax_line_class_t wefax_classify_line(const uint8_t* gray, int width,
                                       int* pulse_col);

// ---------------------------------------------------------------------------
// Encoder (TX; whole-buffer, phase-continuous)
// ---------------------------------------------------------------------------

// Exact number of float samples wefax_encode() produces for these parameters.
// Negative error on bad args.
int wefax_encode_num_samples(int lpm, int ioc, int height, int sample_rate,
                             int start_sec, int phasing_lines, int stop_sec);

// Encode a full radiofax transmission: [start tone][phasing lines][image]
// [stop tone]. `gray` is a row-major grayscale image, exactly
// wefax_pixels_per_line(ioc) wide and `height` tall. start_sec/stop_sec give
// the APT tone durations (0 to omit); phasing_lines the number of phasing
// lines. Returns samples written (== wefax_encode_num_samples) or negative.
int wefax_encode(int lpm, int ioc, const uint8_t* gray, int width, int height,
                 int sample_rate, float amplitude, int start_sec,
                 int phasing_lines, int stop_sec, float* out, int out_capacity);

// ---------------------------------------------------------------------------
// Decoder (RX; handle-based push model)
// ---------------------------------------------------------------------------
typedef struct wefax_decoder wefax_decoder_t;

// Create a decoder for a known line rate (lpm) and IOC. NULL on bad args /
// alloc failure.
wefax_decoder_t* wefax_decoder_create(int sample_rate, int lpm, int ioc);

void wefax_decoder_push(wefax_decoder_t* d, const float* samples, int n);

// Flush any partial line and mark the decode DONE.
void wefax_decoder_finish(wefax_decoder_t* d);

int  wefax_decoder_status(const wefax_decoder_t* d);     // WEFAX_STATUS_*
int  wefax_decoder_width(const wefax_decoder_t* d);      // pixels per line
int  wefax_decoder_rows_ready(const wefax_decoder_t* d);

// Copy up to n_rows decoded gray rows starting at first_row into gray_out
// (row-major, width per row). Returns rows copied, or negative on bad args.
int  wefax_decoder_read_rows(const wefax_decoder_t* d, int first_row,
                             int n_rows, uint8_t* gray_out);

void wefax_decoder_reset(wefax_decoder_t* d);
void wefax_decoder_destroy(wefax_decoder_t* d);

#ifdef __cplusplus
}
#endif

#endif // SSTV_LIB_WEFAX_H
