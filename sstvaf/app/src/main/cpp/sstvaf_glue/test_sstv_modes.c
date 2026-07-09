// test_sstv_modes.c — pins the SSTV mode timing table against independent
// golden values. A typo in sstv_modes.c ships a silently broken mode; every
// number here was computed by hand from the published spec (see
// sstv_lib/SOURCES.md), NOT from the table under test.
//
// Checks per mode:
//   - the segment durations sum to the line/frame total,
//   - total image duration matches the golden value,
//   - VIS code + even-parity bit match the published assignments,
//   - sstv_encode_num_samples() is exact at 12000 and 48000 Hz,
//   - dimensions / color model / sync layout are as specified.
//
// HOW TO RUN: sstvaf_glue/run_sstv_host_tests.ps1 (Windows) or
// run_sstv_host_tests.sh (POSIX). Exit 0 == all pass.

#include "sstv.h"
#include "sstv_modes.h"
#include "sstv_vis.h"

#include "sstv_test_util.h"

typedef struct {
    int mode_id;
    const char* name;
    int vis;
    int parity;         // even-parity bit over the 7 data bits
    int w, h;
    int rows_per_frame;
    double line_us;     // one frame
    double start_sync_us;
    double image_us;    // start_sync + frames * line
} golden_t;

// All values hand-computed from the N7CXI Dayton-2000 tables.
static const golden_t kGolden[] = {
    { SSTV_MODE_ROBOT36,  "Robot 36",  8,  1, 320, 240, 1, 150000.0, 0.0,  36000000.0 },
    { SSTV_MODE_ROBOT72,  "Robot 72",  12, 0, 320, 240, 1, 300000.0, 0.0,  72000000.0 },
    { SSTV_MODE_MARTIN1,  "Martin 1",  44, 1, 320, 256, 1, 446446.0, 0.0, 114290176.0 },
    { SSTV_MODE_MARTIN2,  "Martin 2",  40, 0, 320, 256, 1, 226798.0, 0.0,  58060288.0 },
    { SSTV_MODE_SCOTTIE1, "Scottie 1", 60, 0, 320, 256, 1, 428220.0, 9000.0, 109633320.0 },
    { SSTV_MODE_SCOTTIE2, "Scottie 2", 56, 1, 320, 256, 1, 277692.0, 9000.0,  71098152.0 },
    { SSTV_MODE_PD50,     "PD 50",     93, 1, 320, 256, 2, 388160.0, 0.0,  49684480.0 },
    { SSTV_MODE_PD90,     "PD 90",     99, 0, 320, 256, 2, 703040.0, 0.0,  89989120.0 },
    { SSTV_MODE_PD120,    "PD 120",    95, 0, 640, 496, 2, 508480.0, 0.0, 126103040.0 },
    { SSTV_MODE_SCOTTIEDX,"Scottie DX",76, 1, 320, 256, 1,1050300.0, 9000.0, 268885800.0 },
    { SSTV_MODE_MARTIN3,  "Martin 3",  36, 0, 320, 128, 1, 446446.0, 0.0,  57145088.0 },
    { SSTV_MODE_MARTIN4,  "Martin 4",  32, 1, 320, 128, 1, 226798.0, 0.0,  29030144.0 },
    { SSTV_MODE_PD160,    "PD 160",    98, 1, 512, 400, 2, 804416.0, 0.0, 160883200.0 },
    { SSTV_MODE_PD180,    "PD 180",    96, 0, 640, 496, 2, 754240.0, 0.0, 187051520.0 },
    { SSTV_MODE_PD240,    "PD 240",    97, 1, 640, 496, 2,1000000.0, 0.0, 248000000.0 },
    { SSTV_MODE_PD290,    "PD 290",    94, 1, 800, 616, 2, 937280.0, 0.0, 288682240.0 },
};
#define N_GOLD ((int)(sizeof(kGolden) / sizeof(kGolden[0])))

// Independent sample-count formula: header is 910000 µs (300+10+300 ms +
// ten 30 ms VIS cells); n = ceil(total_us * fs / 1e6), computed in exact
// integer arithmetic (all timings are integer µs).
static long long expect_samples(const golden_t* g, int fs)
{
    long long total_us = 910000LL + (long long)llround(g->image_us);
    return (total_us * (long long)fs + 999999LL) / 1000000LL;
}

int main(void)
{
    printf("sstv_modes tests:\n");
    char label[160];

    check(SSTV_NUM_MODES == N_GOLD, "mode count is 16");

    for (int i = 0; i < N_GOLD; i++) {
        const golden_t* g = &kGolden[i];
        const sstv_mode_t* m = sstv_mode_get(g->mode_id);
        snprintf(label, sizeof(label), "%s: mode exists", g->name);
        check(m != 0, label);
        if (!m) continue;

        snprintf(label, sizeof(label), "%s: name matches", g->name);
        check(strcmp(m->name, g->name) == 0, label);

        snprintf(label, sizeof(label), "%s: VIS code %d", g->name, g->vis);
        check(m->vis_code == g->vis, label);

        snprintf(label, sizeof(label), "%s: VIS even-parity bit %d", g->name, g->parity);
        check(sstv_vis_parity_bit(g->vis) == g->parity, label);

        snprintf(label, sizeof(label), "%s: mode_by_vis roundtrip", g->name);
        check(sstv_mode_by_vis(g->vis) == m, label);

        snprintf(label, sizeof(label), "%s: %dx%d, %d rows/frame", g->name,
                 g->w, g->h, g->rows_per_frame);
        check(m->width == g->w && m->height == g->h &&
              m->rows_per_frame == g->rows_per_frame, label);

        // Segment durations must sum to the stored line total, and the
        // stored line total must equal the hand-computed golden.
        double sum = 0.0;
        for (int s = 0; s < m->n_segments; s++) sum += m->segments[s].dur_us;
        snprintf(label, sizeof(label), "%s: segments sum to line total", g->name);
        check(fabs(sum - m->line_us) < 1e-6, label);
        snprintf(label, sizeof(label), "%s: line total %.0f us", g->name, g->line_us);
        check(fabs(m->line_us - g->line_us) < 1e-6, label);

        snprintf(label, sizeof(label), "%s: starting sync %.0f us", g->name,
                 g->start_sync_us);
        check(fabs(m->starting_sync_us - g->start_sync_us) < 1e-6, label);

        snprintf(label, sizeof(label), "%s: image duration %.0f us", g->name,
                 g->image_us);
        check(fabs(sstv_mode_image_us(m) - g->image_us) < 1e-6, label);

        // Sync placement: Scottie's sync sits before the red scan; its
        // offset must match the segment list (sep+G+sep+B).
        double off = 0.0;
        int found_sync = 0;
        for (int s = 0; s < m->n_segments; s++) {
            const sstv_segment_t* seg = &m->segments[s];
            if (seg->kind == SSTV_SEG_TONE && seg->freq_hz == 1200.0 &&
                fabs(seg->dur_us - m->sync_us) < 1e-9) {
                found_sync = 1;
                break;
            }
            off += seg->dur_us;
        }
        snprintf(label, sizeof(label), "%s: sync segment found in line", g->name);
        check(found_sync, label);
        snprintf(label, sizeof(label), "%s: sync offset matches segment table",
                 g->name);
        check(fabs(off - m->sync_offset_us) < 1e-6, label);
        snprintf(label, sizeof(label), "%s: sync position enum consistent", g->name);
        check((m->sync_offset_us == 0.0) == (m->sync_pos == SSTV_SYNC_LINE_START),
              label);

        // Sample counts, both rates.
        long long e12 = expect_samples(g, 12000);
        long long e48 = expect_samples(g, 48000);
        snprintf(label, sizeof(label), "%s: num_samples @12k == %lld", g->name, e12);
        check(sstv_encode_num_samples(g->mode_id, 12000) == (int)e12, label);
        snprintf(label, sizeof(label), "%s: num_samples @48k == %lld", g->name, e48);
        check(sstv_encode_num_samples(g->mode_id, 48000) == (int)e48, label);
    }

    // VIS cell frequencies: spot-check the published cell layout.
    check(sstv_vis_cell_freq(44, 0) == 1200.0, "VIS cell 0 is the 1200 Hz start bit");
    check(sstv_vis_cell_freq(44, 9) == 1200.0, "VIS cell 9 is the 1200 Hz stop bit");
    // Martin 1 = 44 = 0b0101100 LSB first: 0,0,1,1,0,1,0.
    check(sstv_vis_cell_freq(44, 1) == 1300.0, "M1 data bit 0 = 0 (1300 Hz)");
    check(sstv_vis_cell_freq(44, 3) == 1100.0, "M1 data bit 2 = 1 (1100 Hz)");
    check(sstv_vis_cell_freq(44, 8) == 1100.0, "M1 parity bit = 1 (1100 Hz)");
    // PD90 = 99 = 0b1100011: bit0 = 1.
    check(sstv_vis_cell_freq(99, 1) == 1100.0, "PD90 data bit 0 = 1 (1100 Hz)");
    check(sstv_vis_cell_freq(99, 8) == 1300.0, "PD90 parity bit = 0 (1300 Hz)");

    // Bad-args surface.
    check(sstv_mode_get(-1) == 0, "mode_get(-1) is NULL");
    check(sstv_mode_get(SSTV_NUM_MODES) == 0, "mode_get(out of range) is NULL");
    check(sstv_mode_by_vis(0) == 0, "mode_by_vis(unknown) is NULL");
    check(sstv_encode_num_samples(-1, 12000) == SSTV_ERR_BAD_MODE,
          "num_samples rejects bad mode");
    check(sstv_encode_num_samples(SSTV_MODE_MARTIN1, 0) == SSTV_ERR_BAD_ARGS,
          "num_samples rejects bad rate");

    if (g_failures) {
        printf("%d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("all sstv_modes tests passed\n");
    return 0;
}
