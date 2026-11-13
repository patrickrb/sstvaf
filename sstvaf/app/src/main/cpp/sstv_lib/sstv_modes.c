// sstv_modes.c — mode timing table. See sstv_modes.h / SOURCES.md.
//
// A typo here silently ships a broken mode: test_sstv_modes.c pins every
// segment sum, image duration and sample count against independent goldens.

#include "sstv_modes.h"

#define TONE(us, hz)        { SSTV_SEG_TONE, (us), (hz), 0.0, SSTV_COMP_NONE }
#define TONE_EO(us, e, o)   { SSTV_SEG_TONE_EVENODD, (us), (e), (o), SSTV_COMP_NONE }
#define SCAN(us, comp)      { SSTV_SEG_SCAN, (us), 0.0, 0.0, (comp) }

// ---------------------------------------------------------------------------
// Martin (GBR, sync at line start). Line: sync 4862 -> porch 572 ->
// G -> sep 572 -> B -> sep 572 -> R -> sep 572. Porch/separators 1500 Hz.
// ---------------------------------------------------------------------------
static const sstv_segment_t kMartin1Segs[] = {
    TONE(4862.0, SSTV_FREQ_SYNC),
    TONE(572.0, SSTV_FREQ_BLACK),
    SCAN(146432.0, SSTV_COMP_G),
    TONE(572.0, SSTV_FREQ_BLACK),
    SCAN(146432.0, SSTV_COMP_B),
    TONE(572.0, SSTV_FREQ_BLACK),
    SCAN(146432.0, SSTV_COMP_R),
    TONE(572.0, SSTV_FREQ_BLACK),
};

static const sstv_segment_t kMartin2Segs[] = {
    TONE(4862.0, SSTV_FREQ_SYNC),
    TONE(572.0, SSTV_FREQ_BLACK),
    SCAN(73216.0, SSTV_COMP_G),
    TONE(572.0, SSTV_FREQ_BLACK),
    SCAN(73216.0, SSTV_COMP_B),
    TONE(572.0, SSTV_FREQ_BLACK),
    SCAN(73216.0, SSTV_COMP_R),
    TONE(572.0, SSTV_FREQ_BLACK),
};

// ---------------------------------------------------------------------------
// Scottie (GBR, sync before the red scan). Line: sep 1500 -> G -> sep 1500 ->
// B -> sync 9000 -> porch 1500 -> R. One-off 9 ms sync after the VIS, before
// line 1.
// ---------------------------------------------------------------------------
static const sstv_segment_t kScottie1Segs[] = {
    TONE(1500.0, SSTV_FREQ_BLACK),
    SCAN(138240.0, SSTV_COMP_G),
    TONE(1500.0, SSTV_FREQ_BLACK),
    SCAN(138240.0, SSTV_COMP_B),
    TONE(9000.0, SSTV_FREQ_SYNC),
    TONE(1500.0, SSTV_FREQ_BLACK),
    SCAN(138240.0, SSTV_COMP_R),
};

static const sstv_segment_t kScottie2Segs[] = {
    TONE(1500.0, SSTV_FREQ_BLACK),
    SCAN(88064.0, SSTV_COMP_G),
    TONE(1500.0, SSTV_FREQ_BLACK),
    SCAN(88064.0, SSTV_COMP_B),
    TONE(9000.0, SSTV_FREQ_SYNC),
    TONE(1500.0, SSTV_FREQ_BLACK),
    SCAN(88064.0, SSTV_COMP_R),
};

// Scottie DX: same line structure as S1/S2, 1.0800 ms/pixel -> 345.6 ms scan.
static const sstv_segment_t kScottieDXSegs[] = {
    TONE(1500.0, SSTV_FREQ_BLACK),
    SCAN(345600.0, SSTV_COMP_G),
    TONE(1500.0, SSTV_FREQ_BLACK),
    SCAN(345600.0, SSTV_COMP_B),
    TONE(9000.0, SSTV_FREQ_SYNC),
    TONE(1500.0, SSTV_FREQ_BLACK),
    SCAN(345600.0, SSTV_COMP_R),
};

// ---------------------------------------------------------------------------
// Robot 36 (Y + alternating chroma). Line: sync 9000 -> porch 3000 (1500) ->
// Y 88000 -> sep 4500 (1500 on even frames = R-Y follows, 2300 on odd =
// B-Y follows) -> porch 1500 (1900) -> chroma 44000.
// ---------------------------------------------------------------------------
static const sstv_segment_t kRobot36Segs[] = {
    TONE(9000.0, SSTV_FREQ_SYNC),
    TONE(3000.0, SSTV_FREQ_BLACK),
    SCAN(88000.0, SSTV_COMP_Y),
    TONE_EO(4500.0, SSTV_FREQ_BLACK, SSTV_FREQ_WHITE),
    TONE(1500.0, SSTV_FREQ_LEADER),
    SCAN(44000.0, SSTV_COMP_C_ALT),
};

// ---------------------------------------------------------------------------
// Robot 72 (Y + both chroma each line). Line: sync 9000 -> porch 3000 (1500)
// -> Y 138000 -> sep 4500 (1500) -> porch 1500 (1900) -> R-Y 69000 ->
// sep 4500 (2300) -> porch 1500 (1500) -> B-Y 69000.
// Chroma is the full 320 px transmitted in the shorter segment (subsampled
// in time, not in array size).
// ---------------------------------------------------------------------------
static const sstv_segment_t kRobot72Segs[] = {
    TONE(9000.0, SSTV_FREQ_SYNC),
    TONE(3000.0, SSTV_FREQ_BLACK),
    SCAN(138000.0, SSTV_COMP_Y),
    TONE(4500.0, SSTV_FREQ_BLACK),
    TONE(1500.0, SSTV_FREQ_LEADER),
    SCAN(69000.0, SSTV_COMP_CR),
    TONE(4500.0, SSTV_FREQ_WHITE),
    TONE(1500.0, SSTV_FREQ_BLACK),
    SCAN(69000.0, SSTV_COMP_CB),
};

// ---------------------------------------------------------------------------
// PD (two image rows per frame; chroma is the average of the pair's chroma).
// Frame: sync 20000 -> porch 2080 (1500) -> Y(first row) -> R-Y -> B-Y ->
// Y(second row).
// ---------------------------------------------------------------------------
#define PD_SEGS(scan_us)                       \
    TONE(20000.0, SSTV_FREQ_SYNC),             \
    TONE(2080.0, SSTV_FREQ_BLACK),             \
    SCAN((scan_us), SSTV_COMP_Y_A),            \
    SCAN((scan_us), SSTV_COMP_CR),             \
    SCAN((scan_us), SSTV_COMP_CB),             \
    SCAN((scan_us), SSTV_COMP_Y_B)

static const sstv_segment_t kPd50Segs[]  = { PD_SEGS(91520.0) };
static const sstv_segment_t kPd90Segs[]  = { PD_SEGS(170240.0) };
static const sstv_segment_t kPd120Segs[] = { PD_SEGS(121600.0) };
static const sstv_segment_t kPd160Segs[] = { PD_SEGS(195584.0) };
static const sstv_segment_t kPd180Segs[] = { PD_SEGS(183040.0) };
static const sstv_segment_t kPd240Segs[] = { PD_SEGS(244480.0) };
static const sstv_segment_t kPd290Segs[] = { PD_SEGS(228800.0) };

#define NSEG(a) ((int)(sizeof(a) / sizeof((a)[0])))

static const sstv_mode_t kModes[SSTV_NUM_MODES] = {
    { SSTV_MODE_ROBOT36, "Robot 36", 8, 320, 240,
      SSTV_COLOR_YC_ALT, SSTV_SYNC_LINE_START, 1,
      0.0, 150000.0, 9000.0, 0.0, NSEG(kRobot36Segs), kRobot36Segs },

    { SSTV_MODE_ROBOT72, "Robot 72", 12, 320, 240,
      SSTV_COLOR_YC_422, SSTV_SYNC_LINE_START, 1,
      0.0, 300000.0, 9000.0, 0.0, NSEG(kRobot72Segs), kRobot72Segs },

    { SSTV_MODE_MARTIN1, "Martin 1", 44, 320, 256,
      SSTV_COLOR_GBR, SSTV_SYNC_LINE_START, 1,
      0.0, 446446.0, 4862.0, 0.0, NSEG(kMartin1Segs), kMartin1Segs },

    { SSTV_MODE_MARTIN2, "Martin 2", 40, 320, 256,
      SSTV_COLOR_GBR, SSTV_SYNC_LINE_START, 1,
      0.0, 226798.0, 4862.0, 0.0, NSEG(kMartin2Segs), kMartin2Segs },

    { SSTV_MODE_SCOTTIE1, "Scottie 1", 60, 320, 256,
      SSTV_COLOR_GBR, SSTV_SYNC_BEFORE_RED, 1,
      9000.0, 428220.0, 9000.0,
      1500.0 + 138240.0 + 1500.0 + 138240.0,  // 279480
      NSEG(kScottie1Segs), kScottie1Segs },

    { SSTV_MODE_SCOTTIE2, "Scottie 2", 56, 320, 256,
      SSTV_COLOR_GBR, SSTV_SYNC_BEFORE_RED, 1,
      9000.0, 277692.0, 9000.0,
      1500.0 + 88064.0 + 1500.0 + 88064.0,    // 179128
      NSEG(kScottie2Segs), kScottie2Segs },

    { SSTV_MODE_PD50, "PD 50", 93, 320, 256,
      SSTV_COLOR_YC_PD, SSTV_SYNC_LINE_START, 2,
      0.0, 388160.0, 20000.0, 0.0, NSEG(kPd50Segs), kPd50Segs },

    { SSTV_MODE_PD90, "PD 90", 99, 320, 256,
      SSTV_COLOR_YC_PD, SSTV_SYNC_LINE_START, 2,
      0.0, 703040.0, 20000.0, 0.0, NSEG(kPd90Segs), kPd90Segs },

    { SSTV_MODE_PD120, "PD 120", 95, 640, 496,
      SSTV_COLOR_YC_PD, SSTV_SYNC_LINE_START, 2,
      0.0, 508480.0, 20000.0, 0.0, NSEG(kPd120Segs), kPd120Segs },

    // ---- appended batch (issue #16) ---------------------------------------

    { SSTV_MODE_SCOTTIEDX, "Scottie DX", 76, 320, 256,
      SSTV_COLOR_GBR, SSTV_SYNC_BEFORE_RED, 1,
      9000.0, 1050300.0, 9000.0,
      1500.0 + 345600.0 + 1500.0 + 345600.0,  // 694200
      NSEG(kScottieDXSegs), kScottieDXSegs },

    // Martin 3 / 4: identical horizontal timing to Martin 1 / 2 (same lpm in
    // the SSTV mode list), transmitted for 128 lines instead of 256.
    { SSTV_MODE_MARTIN3, "Martin 3", 36, 320, 128,
      SSTV_COLOR_GBR, SSTV_SYNC_LINE_START, 1,
      0.0, 446446.0, 4862.0, 0.0, NSEG(kMartin1Segs), kMartin1Segs },

    { SSTV_MODE_MARTIN4, "Martin 4", 32, 320, 128,
      SSTV_COLOR_GBR, SSTV_SYNC_LINE_START, 1,
      0.0, 226798.0, 4862.0, 0.0, NSEG(kMartin2Segs), kMartin2Segs },

    { SSTV_MODE_PD160, "PD 160", 98, 512, 400,
      SSTV_COLOR_YC_PD, SSTV_SYNC_LINE_START, 2,
      0.0, 804416.0, 20000.0, 0.0, NSEG(kPd160Segs), kPd160Segs },

    { SSTV_MODE_PD180, "PD 180", 96, 640, 496,
      SSTV_COLOR_YC_PD, SSTV_SYNC_LINE_START, 2,
      0.0, 754240.0, 20000.0, 0.0, NSEG(kPd180Segs), kPd180Segs },

    { SSTV_MODE_PD240, "PD 240", 97, 640, 496,
      SSTV_COLOR_YC_PD, SSTV_SYNC_LINE_START, 2,
      0.0, 1000000.0, 20000.0, 0.0, NSEG(kPd240Segs), kPd240Segs },

    { SSTV_MODE_PD290, "PD 290", 94, 800, 616,
      SSTV_COLOR_YC_PD, SSTV_SYNC_LINE_START, 2,
      0.0, 937280.0, 20000.0, 0.0, NSEG(kPd290Segs), kPd290Segs },
};

const sstv_mode_t* sstv_mode_get(int mode_id)
{
    if (mode_id < 0 || mode_id >= SSTV_NUM_MODES) return 0;
    // Table order matches the enum order but is keyed by id anyway.
    for (int i = 0; i < SSTV_NUM_MODES; i++) {
        if (kModes[i].id == mode_id) return &kModes[i];
    }
    return 0;
}

const sstv_mode_t* sstv_mode_by_vis(int vis_code)
{
    for (int i = 0; i < SSTV_NUM_MODES; i++) {
        if (kModes[i].vis_code == vis_code) return &kModes[i];
    }
    return 0;
}

double sstv_mode_image_us(const sstv_mode_t* m)
{
    if (!m) return 0.0;
    int frames = m->height / m->rows_per_frame;
    return m->starting_sync_us + (double)frames * m->line_us;
}

double sstv_mode_total_us(const sstv_mode_t* m)
{
    if (!m) return 0.0;
    return SSTV_HEADER_US + sstv_mode_image_us(m);
}
