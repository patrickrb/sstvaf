// test_sstv_golden.c — golden-vector regression test for the SSTV encoder.
//
// Encodes the fixed test card (sstv_test_util.h) for every mode at 12 kHz
// and asserts:
//   (A) exact buffer length,
//   (B) a frozen FNV-1a-64 checksum over the int16-quantized samples —
//       any change to timing, tone mapping, VIS layout or color math
//       changes the checksum,
//   (C) instantaneous-frequency spot checks at analytically known times
//       (leader, VIS cells, first sync, first porch, a known pixel run) —
//       these prove the frozen checksums describe genuine SSTV signals,
//       not just whatever the encoder emitted the day they were frozen.
//
// HOW TO REGENERATE the checksums (only when the encoder output changes
// intentionally): run with --emit (run_sstv_host_tests.ps1 -Regen) and paste
// the printed block over kGoldenChecksums. Never hand-edit an entry.

#include "sstv.h"
#include "sstv_modes.h"
#include "sstv_vis.h"

#include "sstv_test_util.h"

#define RATE 12000

// FNV-1a 64 checksums of the int16-quantized 12 kHz test-card encodes.
// Regenerate with --emit; do not hand-edit.
static const struct {
    int mode_id;
    const char* name;
    uint64_t checksum;
    int n_samples;
} kGoldenChecksums[SSTV_NUM_MODES] = {
    { SSTV_MODE_ROBOT36,  "Robot 36",  0x0b552cb2067bfee3ull,  442920 },
    { SSTV_MODE_ROBOT72,  "Robot 72",  0xc00a84b29707f06full,  874920 },
    { SSTV_MODE_MARTIN1,  "Martin 1",  0x0ea05a74544489e4ull, 1382403 },
    { SSTV_MODE_MARTIN2,  "Martin 2",  0xb18ff0b99cf4710dull,  707644 },
    { SSTV_MODE_SCOTTIE1, "Scottie 1", 0x9df2a269862e56d0ull, 1326520 },
    { SSTV_MODE_SCOTTIE2, "Scottie 2", 0x1deee9484923f1a7ull,  864098 },
    { SSTV_MODE_PD50,     "PD 50",     0xcc7d745baf6c7a5dull,  607134 },
    { SSTV_MODE_PD90,     "PD 90",     0x10cd021bcd945bb7ull, 1090790 },
    { SSTV_MODE_PD120,    "PD 120",    0xa5d7ccbda46e5316ull, 1524157 },
    { SSTV_MODE_SCOTTIEDX, "Scottie DX", 0xe8ff205b37af4212ull, 3237550 },
    { SSTV_MODE_MARTIN3,   "Martin 3",   0xe91c427cf235a5e0ull,  696662 },
    { SSTV_MODE_MARTIN4,   "Martin 4",   0x02de19123e25c86bull,  359282 },
    { SSTV_MODE_PD160,     "PD 160",     0x38db67d7bf30aea9ull, 1941519 },
    { SSTV_MODE_PD180,     "PD 180",     0x7635d2b786cdb648ull, 2255539 },
    { SSTV_MODE_PD240,     "PD 240",     0x25289d532e3e29e7ull, 2986920 },
    { SSTV_MODE_PD290,     "PD 290",     0x8848b28005d4d72full, 3475107 },
};

// Measure the tone frequency around absolute time t_us (window fully inside
// a constant-frequency stretch: [t_us, t_us + win_us)).
static double freq_at(const float* buf, int n, double t_us, double win_us)
{
    int n0 = (int)(t_us * RATE / 1e6) + 1;
    int n1 = (int)((t_us + win_us) * RATE / 1e6) - 1;
    if (n0 < 0) n0 = 0;
    if (n1 > n) n1 = n;
    return sstv_test_measure_freq(buf, n0, n1, RATE);
}

int main(int argc, char** argv)
{
    int emit = (argc > 1 && strcmp(argv[1], "--emit") == 0);
    char label[160];
    printf("sstv_golden tests:\n");

    if (emit) {
        printf("// FNV-1a 64 checksums of the int16-quantized 12 kHz test-card"
               " encodes.\n// Regenerate with --emit; do not hand-edit.\n");
    }

    for (int i = 0; i < SSTV_NUM_MODES; i++) {
        int mode_id = kGoldenChecksums[i].mode_id;
        const sstv_mode_t* m = sstv_mode_get(mode_id);
        uint32_t* img = sstv_testcard_alloc(m->width, m->height);

        int need = sstv_encode_num_samples(mode_id, RATE);
        float* buf = (float*)malloc((size_t)need * sizeof(float));
        int got = sstv_encode(mode_id, img, m->width, m->height, RATE, 0.8f,
                              buf, need);

        if (emit) {
            int16_t* q = (int16_t*)malloc((size_t)need * sizeof(int16_t));
            sstv_test_quantize_i16(buf, got > 0 ? got : 0, q);
            uint64_t h = sstv_test_fnv1a64(q, (size_t)(got > 0 ? got : 0) * 2);
            printf("    { SSTV_MODE_%s, \"%s\", 0x%016llxull, %d },\n",
                   mode_id == SSTV_MODE_ROBOT36 ? "ROBOT36" :
                   mode_id == SSTV_MODE_ROBOT72 ? "ROBOT72" :
                   mode_id == SSTV_MODE_MARTIN1 ? "MARTIN1" :
                   mode_id == SSTV_MODE_MARTIN2 ? "MARTIN2" :
                   mode_id == SSTV_MODE_SCOTTIE1 ? "SCOTTIE1" :
                   mode_id == SSTV_MODE_SCOTTIE2 ? "SCOTTIE2" :
                   mode_id == SSTV_MODE_PD50 ? "PD50" :
                   mode_id == SSTV_MODE_PD90 ? "PD90" :
                   mode_id == SSTV_MODE_PD120 ? "PD120" :
                   mode_id == SSTV_MODE_SCOTTIEDX ? "SCOTTIEDX" :
                   mode_id == SSTV_MODE_MARTIN3 ? "MARTIN3" :
                   mode_id == SSTV_MODE_MARTIN4 ? "MARTIN4" :
                   mode_id == SSTV_MODE_PD160 ? "PD160" :
                   mode_id == SSTV_MODE_PD180 ? "PD180" :
                   mode_id == SSTV_MODE_PD240 ? "PD240" : "PD290",
                   m->name, (unsigned long long)h, got);
            free(q);
            free(buf);
            free(img);
            continue;
        }

        // (A) exact length.
        snprintf(label, sizeof(label), "%s: encode returns %d samples", m->name,
                 kGoldenChecksums[i].n_samples);
        check(got == kGoldenChecksums[i].n_samples, label);
        snprintf(label, sizeof(label), "%s: length matches num_samples", m->name);
        check(got == need, label);

        // (B) frozen checksum over int16-quantized samples.
        int16_t* q = (int16_t*)malloc((size_t)got * sizeof(int16_t));
        sstv_test_quantize_i16(buf, got, q);
        uint64_t h = sstv_test_fnv1a64(q, (size_t)got * 2);
        snprintf(label, sizeof(label), "%s: golden checksum", m->name);
        check(h == kGoldenChecksums[i].checksum, label);
        free(q);

        // (C) instantaneous-frequency spot checks at analytic times.
        // Header layout: leader [0,300ms], break, leader [310,610ms],
        // VIS cells of 30 ms from 610 ms; image from 910 ms.
        snprintf(label, sizeof(label), "%s: leader 1 is 1900 Hz", m->name);
        check_near(freq_at(buf, got, 150000.0, 5000.0), 1900.0, 4.0, label);
        snprintf(label, sizeof(label), "%s: leader 2 is 1900 Hz", m->name);
        check_near(freq_at(buf, got, 450000.0, 5000.0), 1900.0, 4.0, label);
        snprintf(label, sizeof(label), "%s: VIS start bit is 1200 Hz", m->name);
        check_near(freq_at(buf, got, 620000.0, 5000.0), 1200.0, 4.0, label);

        // First data bit (cell 1, centered 655 ms): 1100/1300 by VIS LSB.
        double f_bit0 = sstv_vis_cell_freq(m->vis_code, 1);
        snprintf(label, sizeof(label), "%s: VIS data bit 0 is %.0f Hz", m->name,
                 f_bit0);
        check_near(freq_at(buf, got, 650000.0, 5000.0), f_bit0, 4.0, label);
        snprintf(label, sizeof(label), "%s: VIS stop bit is 1200 Hz", m->name);
        check_near(freq_at(buf, got, 890000.0, 5000.0), 1200.0, 4.0, label);

        // First line sync + following porch, timed from the mode table
        // goldens (910 ms + starting sync + sync offset).
        double img0 = 910000.0 + m->starting_sync_us;
        double sync_t = img0 + m->sync_offset_us;
        double win = m->sync_us * 0.5;
        snprintf(label, sizeof(label), "%s: first line sync is 1200 Hz", m->name);
        check_near(freq_at(buf, got, sync_t + m->sync_us * 0.25, win), 1200.0,
                   6.0, label);

        if (m->sync_pos == SSTV_SYNC_BEFORE_RED) {
            // Scottie (incl. DX): one-off 9 ms starting sync right after VIS.
            snprintf(label, sizeof(label), "%s: 9 ms starting sync after VIS",
                     m->name);
            check_near(freq_at(buf, got, 910000.0 + 2000.0, 5000.0), 1200.0,
                       6.0, label);
        } else {
            // Line-start modes with a porch long enough to measure.
            double porch_us = m->segments[1].dur_us;
            if (porch_us >= 2000.0) {
                snprintf(label, sizeof(label), "%s: porch after sync is %.0f Hz",
                         m->name, m->segments[1].freq_hz);
                check_near(freq_at(buf, got, sync_t + m->sync_us + porch_us * 0.2,
                                   porch_us * 0.6),
                           m->segments[1].freq_hz, 8.0, label);
            }
        }

        if (m->color == SSTV_COLOR_GBR) {
            // Gradient rows: the test card's blue channel depends only on y,
            // so a gradient row's whole B scan is one constant tone. This
            // pins the G,B,R scan ordering AND the line period: get either
            // wrong and the measured tone is a moving pixel ramp instead.
            int row = m->height > 200 ? 200 : m->height / 2;
            double boff = 0.0;
            const sstv_segment_t* bscan = 0;
            for (int s = 0; s < m->n_segments; s++) {
                if (m->segments[s].kind == SSTV_SEG_SCAN &&
                    m->segments[s].component == SSTV_COMP_B) {
                    bscan = &m->segments[s];
                    break;
                }
                boff += m->segments[s].dur_us;
            }
            double bval = (double)((row * 255) / (m->height - 1));
            double t_b = img0 + (double)row * m->line_us + boff
                         + 0.1 * bscan->dur_us;
            snprintf(label, sizeof(label), "%s: row 200 B scan is a constant tone",
                     m->name);
            check_near(freq_at(buf, got, t_b, 0.5 * bscan->dur_us),
                       1500.0 + bval * (800.0 / 255.0), 4.0, label);
        }

        // A known pixel run: row 0 of the test card starts with a white bar
        // (v=255 on every component of every color model: R=G=B=255 gives
        // Y=235... so compute the expected freq from the first scan segment's
        // component of a white pixel instead).
        // White: R=G=B=255 -> G/B/R scans at 2300 Hz; Y = 16+235-ish.
        double off = 0.0;
        const sstv_segment_t* scan = 0;
        for (int s = 0; s < m->n_segments; s++) {
            if (m->segments[s].kind == SSTV_SEG_SCAN) {
                scan = &m->segments[s];
                break;
            }
            off += m->segments[s].dur_us;
        }
        double px_us = scan->dur_us / m->width;
        double t_px = img0 + off + 4.0 * px_us;  // inside the white bar
        double expect;
        if (m->color == SSTV_COLOR_GBR) {
            expect = 2300.0;  // white -> component 255
        } else {
            // Y of white = 16 + (65.738+129.057+25.064)*255/256 = 235.0 -> round
            expect = 1500.0 + 235.0 * (800.0 / 255.0);
        }
        snprintf(label, sizeof(label), "%s: white-bar pixel scan freq", m->name);
        check_near(freq_at(buf, got, t_px, 20.0 * px_us), expect, 6.0, label);

        free(buf);
        free(img);
    }

    if (!emit) {
        // Encoder argument checking.
        const sstv_mode_t* m1 = sstv_mode_get(SSTV_MODE_MARTIN1);
        uint32_t* img = sstv_testcard_alloc(m1->width, m1->height);
        float dummy[16];
        check(sstv_encode(SSTV_MODE_MARTIN1, img, 100, 100, RATE, 0.8f, dummy, 16)
                  == SSTV_ERR_BAD_ARGS,
              "encode rejects wrong dimensions");
        check(sstv_encode(SSTV_MODE_MARTIN1, img, m1->width, m1->height, RATE,
                          0.8f, dummy, 16) == SSTV_ERR_CAPACITY,
              "encode rejects short buffer");
        check(sstv_encode(99, img, 320, 256, RATE, 0.8f, dummy, 16)
                  == SSTV_ERR_BAD_MODE,
              "encode rejects bad mode");
        free(img);
    }

    if (g_failures) {
        printf("%d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf(emit ? "golden emit complete\n" : "all sstv_golden tests passed\n");
    return 0;
}
