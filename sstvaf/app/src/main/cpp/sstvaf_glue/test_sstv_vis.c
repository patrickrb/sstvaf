// test_sstv_vis.c — VIS detection tests for the SSTV decoder.
//
// The headers are synthesized by the test's own tone generator (NOT the
// library encoder), so a systematic encoder/decoder timing bug can't cancel
// out. Covers:
//   - clean detection of all 16 known VIS codes,
//   - tuning offsets of ±30 Hz and ±80 Hz (constant-offset calibration),
//   - additive white gaussian noise,
//   - a parity-corrupted header (rejected),
//   - an unknown-but-parity-valid code (rejected),
//   - a truncated leader (rejected).
//
// HOW TO RUN: sstvaf_glue/run_sstv_host_tests.ps1 / run_sstv_host_tests.sh.

#include "sstv.h"
#include "sstv_modes.h"

#include "sstv_test_util.h"

#define RATE 12000

// Header (910 ms) + trailing tone budget.
#define BUF_SAMPLES (RATE * 3)

// Synthesize a header for `code` and push it through a fresh decoder.
// Returns the decoder's detected mode id (or -1). A short stretch of the
// mode's post-VIS tone (any mid-range tone) follows the header so the
// decoder has samples to chew past the stop bit.
static int detect(int code, double offset_hz, int parity_flip,
                  double leader_ms, double awgn_sigma, uint64_t seed,
                  int* status_out)
{
    float* buf = (float*)calloc(BUF_SAMPLES, sizeof(float));
    sstv_test_osc_t o;
    tosc_init(&o, RATE, buf, BUF_SAMPLES);
    tosc_tone_ms(&o, 700, 50, 0.0);  // brief silence before the leader
    sstv_test_gen_header(&o, code, offset_hz, parity_flip, leader_ms, 0.7);
    tosc_tone_ms(&o, 1500 + offset_hz, 400, 0.7);  // post-VIS filler tone

    if (awgn_sigma > 0.0) {
        sstv_test_srand(seed);
        sstv_test_add_awgn(buf, o.pos, awgn_sigma);
    }

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    sstv_test_push_all(d, buf, o.pos);
    int mode = sstv_decoder_mode(d);
    if (status_out) *status_out = sstv_decoder_status(d);
    sstv_decoder_destroy(d);
    free(buf);
    return mode;
}

int main(void)
{
    char label[160];
    printf("sstv_vis tests:\n");

    static const struct { int code; int mode; const char* name; } kCodes[] = {
        { 8,  SSTV_MODE_ROBOT36,  "Robot 36" },
        { 12, SSTV_MODE_ROBOT72,  "Robot 72" },
        { 44, SSTV_MODE_MARTIN1,  "Martin 1" },
        { 40, SSTV_MODE_MARTIN2,  "Martin 2" },
        { 60, SSTV_MODE_SCOTTIE1, "Scottie 1" },
        { 56, SSTV_MODE_SCOTTIE2, "Scottie 2" },
        { 93, SSTV_MODE_PD50,     "PD 50" },
        { 99, SSTV_MODE_PD90,     "PD 90" },
        { 95, SSTV_MODE_PD120,    "PD 120" },
        { 76, SSTV_MODE_SCOTTIEDX, "Scottie DX" },
        { 36, SSTV_MODE_MARTIN3,  "Martin 3" },
        { 32, SSTV_MODE_MARTIN4,  "Martin 4" },
        { 98, SSTV_MODE_PD160,    "PD 160" },
        { 96, SSTV_MODE_PD180,    "PD 180" },
        { 97, SSTV_MODE_PD240,    "PD 240" },
        { 94, SSTV_MODE_PD290,    "PD 290" },
    };

    // 1. Clean headers, all known codes.
    for (int i = 0; i < (int)(sizeof(kCodes) / sizeof(kCodes[0])); i++) {
        int status;
        int mode = detect(kCodes[i].code, 0.0, 0, 300.0, 0.0, 0, &status);
        snprintf(label, sizeof(label), "clean VIS %d -> %s", kCodes[i].code,
                 kCodes[i].name);
        check(mode == kCodes[i].mode && status == SSTV_STATUS_IMAGE, label);
    }

    // 2. Tuning offsets: ±30 Hz and ±80 Hz.
    static const double kOffsets[] = { -80.0, -30.0, 30.0, 80.0 };
    for (int i = 0; i < 4; i++) {
        int mode = detect(44, kOffsets[i], 0, 300.0, 0.0, 0, 0);
        snprintf(label, sizeof(label), "Martin 1 detected at %+.0f Hz offset",
                 kOffsets[i]);
        check(mode == SSTV_MODE_MARTIN1, label);
    }

    // 3. AWGN on a clean header (sigma = 0.25 on a 0.7 amplitude carrier,
    //    ~9 dB SNR in the full Nyquist band). Two codes, two seeds.
    check(detect(60, 0.0, 0, 300.0, 0.25, 0x1234, 0) == SSTV_MODE_SCOTTIE1,
          "Scottie 1 detected in AWGN (seed 1)");
    check(detect(8, 0.0, 0, 300.0, 0.25, 0x9876, 0) == SSTV_MODE_ROBOT36,
          "Robot 36 detected in AWGN (seed 2)");
    check(detect(95, 20.0, 0, 300.0, 0.20, 0x55AA, 0) == SSTV_MODE_PD120,
          "PD 120 detected in AWGN with +20 Hz offset");

    // 4. Parity-corrupted header: rejected (no mode, back to hunting).
    {
        int status;
        int mode = detect(44, 0.0, 1, 300.0, 0.0, 0, &status);
        check(mode == -1, "parity-corrupted VIS rejected");
        check(status != SSTV_STATUS_IMAGE && status != SSTV_STATUS_DONE,
              "parity-corrupted VIS does not enter IMAGE");
    }

    // 5. Unknown code with valid parity: rejected. 0x03 (two bits set, even
    //    parity already) is not an assigned mode.
    check(detect(3, 0.0, 0, 300.0, 0.0, 0, 0) == -1,
          "unknown (parity-valid) VIS code rejected");

    // 6. Truncated leader: 30 ms leaders can never fill 80% of the 100 ms
    //    detection window (30+10+30 ms of header only spans 70 ms of 1900 Hz
    //    across the break), so the decoder must stay hunting and detect
    //    nothing. (50 ms leaders are deliberately NOT rejected: two of them
    //    straddling the break give ~90 ms of leader in the window, which a
    //    robust detector accepts.)
    {
        int status;
        int mode = detect(44, 0.0, 0, 30.0, 0.0, 0, &status);
        check(mode == -1, "truncated (30 ms) leader rejected");
        check(status == SSTV_STATUS_IDLE || status == SSTV_STATUS_LEADER,
              "truncated leader leaves decoder hunting");
    }

    if (g_failures) {
        printf("%d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("all sstv_vis tests passed\n");
    return 0;
}
