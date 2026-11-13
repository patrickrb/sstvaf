// sstv_modes.h — the SSTV mode timing table: the single source of truth for
// the encoder AND decoder. All timings are double microseconds, taken from
// JL Barber N7CXI, "Proposal for SSTV Mode Specifications" (Dayton 2000) —
// see SOURCES.md.
//
// The Kotlin engine mirrors this table (mode ids, dims, VIS codes, durations)
// in radio.ks3ckc.sstvaf.sstv.SstvMode; SstvModeTest pins every SSTV_MODE_*
// id and table entry against hardcoded expectations. If you reorder/extend
// the mode ids in sstv.h or change this table, update SstvMode.kt and
// SstvModeTest.kt together.

#ifndef SSTV_LIB_SSTV_MODES_H
#define SSTV_LIB_SSTV_MODES_H

#include "sstv.h"

#ifdef __cplusplus
extern "C" {
#endif

// Shared reference frequencies (Hz).
#define SSTV_FREQ_SYNC     1200.0
#define SSTV_FREQ_BLACK    1500.0
#define SSTV_FREQ_WHITE    2300.0
#define SSTV_FREQ_LEADER   1900.0
#define SSTV_FREQ_VIS_ONE  1100.0
#define SSTV_FREQ_VIS_ZERO 1300.0

// Pixel value v in [0,255] -> f = 1500 + v * (800/255) Hz.
#define SSTV_HZ_PER_UNIT  (800.0 / 255.0)

// Calibration header timing (µs).
#define SSTV_LEADER_US   300000.0
#define SSTV_BREAK_US     10000.0
#define SSTV_VIS_BIT_US   30000.0
// leader + break + leader + 10 VIS cells (start, 7 data, parity, stop).
#define SSTV_HEADER_US   (SSTV_LEADER_US + SSTV_BREAK_US + SSTV_LEADER_US + 10.0 * SSTV_VIS_BIT_US)

// Segment kinds.
typedef enum {
    SSTV_SEG_TONE = 0,      // fixed tone for dur_us
    SSTV_SEG_SCAN,          // pixel scan of `component` for dur_us
    SSTV_SEG_TONE_EVENODD   // tone whose freq depends on frame parity
                            // (Robot 36 separator: freq_hz on even frames,
                            //  freq_hz_odd on odd frames)
} sstv_seg_kind_t;

// Scan components.
typedef enum {
    SSTV_COMP_NONE = -1,
    SSTV_COMP_G = 0,
    SSTV_COMP_B,
    SSTV_COMP_R,
    SSTV_COMP_Y,      // luma of the frame's row
    SSTV_COMP_CR,     // R-Y
    SSTV_COMP_CB,     // B-Y
    SSTV_COMP_C_ALT,  // Robot 36: R-Y on even frames, B-Y on odd frames
    SSTV_COMP_Y_A,    // PD: luma of the first row of the pair
    SSTV_COMP_Y_B,    // PD: luma of the second row of the pair
    SSTV_COMP_COUNT
} sstv_component_t;

// Color models.
typedef enum {
    SSTV_COLOR_GBR = 0,   // Martin / Scottie: sequential G, B, R scans
    SSTV_COLOR_YC_ALT,    // Robot 36: Y each line + alternating R-Y / B-Y
    SSTV_COLOR_YC_422,    // Robot 72: Y, R-Y, B-Y each line
    SSTV_COLOR_YC_PD      // PD: Y(odd row), R-Y, B-Y, Y(even row) per frame
} sstv_color_model_t;

// Where the sync pulse sits inside the line.
typedef enum {
    SSTV_SYNC_LINE_START = 0,
    SSTV_SYNC_BEFORE_RED      // Scottie: sync precedes the red scan
} sstv_sync_pos_t;

typedef struct {
    sstv_seg_kind_t kind;
    double dur_us;
    double freq_hz;       // TONE freq; TONE_EVENODD even-frame freq; 0 for SCAN
    double freq_hz_odd;   // TONE_EVENODD odd-frame freq; else 0
    sstv_component_t component;  // SCAN component; else SSTV_COMP_NONE
} sstv_segment_t;

typedef struct {
    int id;                    // SSTV_MODE_*
    const char* name;
    int vis_code;              // 7-bit VIS code
    int width, height;
    sstv_color_model_t color;
    sstv_sync_pos_t sync_pos;
    int rows_per_frame;        // 2 for PD, else 1
    double starting_sync_us;   // Scottie: one-off 9 ms sync after VIS; else 0
    double line_us;            // duration of one frame (== sum of segments)
    double sync_us;            // sync pulse duration
    double sync_offset_us;     // offset of the sync start within the frame
    int n_segments;
    const sstv_segment_t* segments;
} sstv_mode_t;

const sstv_mode_t* sstv_mode_get(int mode_id);        // NULL if out of range
const sstv_mode_t* sstv_mode_by_vis(int vis_code);    // NULL if unknown

// starting_sync + (height / rows_per_frame) * line_us.
double sstv_mode_image_us(const sstv_mode_t* m);
// SSTV_HEADER_US + image.
double sstv_mode_total_us(const sstv_mode_t* m);

#ifdef __cplusplus
}
#endif

#endif // SSTV_LIB_SSTV_MODES_H
