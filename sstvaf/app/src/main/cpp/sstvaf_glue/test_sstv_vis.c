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
//   - a truncated leader (rejected),
//   - the operator mode lock (sstv_decoder_set_forced_mode): garbled VIS
//     decodes as the locked mode, the lock overrides a readable VIS, the
//     header hunt itself is unchanged, the lock survives reset, and a full
//     encode->decode roundtrip with corrupted VIS cells recovers the image.
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
// decoder has samples to chew past the stop bit. With forced >= 0 the
// decoder gets that operator mode lock before any audio.
static int detect_locked(int code, double offset_hz, int parity_flip,
                         double leader_ms, double awgn_sigma, uint64_t seed,
                         int forced, int* status_out)
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
    if (forced >= 0) sstv_decoder_set_forced_mode(d, forced);
    sstv_test_push_all(d, buf, o.pos);
    int mode = sstv_decoder_mode(d);
    if (status_out) *status_out = sstv_decoder_status(d);
    sstv_decoder_destroy(d);
    free(buf);
    return mode;
}

static int detect(int code, double offset_hz, int parity_flip,
                  double leader_ms, double awgn_sigma, uint64_t seed,
                  int* status_out)
{
    return detect_locked(code, offset_hz, parity_flip, leader_ms, awgn_sigma,
                         seed, -1, status_out);
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

    // 2. Tuning offsets: ±30 Hz, ±80 Hz and ±180 Hz (an SSB dial that is
    //    off by most of a VIS tone spacing — common on off-air recordings).
    static const double kOffsets[] = { -180.0, -80.0, -30.0, 30.0, 80.0, 180.0 };
    for (int i = 0; i < 6; i++) {
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

    // 7. Operator mode lock: setter surface.
    {
        sstv_decoder_t* d = sstv_decoder_create(RATE);
        check(sstv_decoder_forced_mode(d) == -1, "lock: default is auto (-1)");
        check(sstv_decoder_set_forced_mode(d, 999) == SSTV_ERR_BAD_ARGS,
              "lock: unknown mode id rejected");
        check(sstv_decoder_set_forced_mode(0, SSTV_MODE_MARTIN2)
                  == SSTV_ERR_BAD_ARGS,
              "lock: null handle rejected");
        check(sstv_decoder_set_forced_mode(d, SSTV_MODE_MARTIN2) == 0,
              "lock: valid mode accepted");
        check(sstv_decoder_forced_mode(d) == SSTV_MODE_MARTIN2,
              "lock: getter round-trips");
        sstv_decoder_reset(d);
        check(sstv_decoder_forced_mode(d) == SSTV_MODE_MARTIN2,
              "lock: survives reset (operator setting, not decode state)");
        check(sstv_decoder_set_forced_mode(d, -1) == 0,
              "lock: -1 restores auto");
        sstv_decoder_destroy(d);
    }

    // 8. Lock behavior on synthesized headers.
    {
        int status;
        // A parity-corrupted VIS that auto rejects (case 4) decodes under
        // the lock.
        int mode = detect_locked(44, 0.0, 1, 300.0, 0.0, 0,
                                 SSTV_MODE_MARTIN2, &status);
        check(mode == SSTV_MODE_MARTIN2 && status == SSTV_STATUS_IMAGE,
              "lock: parity-corrupted VIS decodes as the locked mode");

        // An unknown-but-parity-valid code (case 5) decodes under the lock.
        mode = detect_locked(3, 0.0, 0, 300.0, 0.0, 0,
                             SSTV_MODE_SCOTTIE1, &status);
        check(mode == SSTV_MODE_SCOTTIE1 && status == SSTV_STATUS_IMAGE,
              "lock: unknown VIS code decodes as the locked mode");

        // The lock overrides even a clean, readable VIS: operator wins.
        mode = detect_locked(44, 0.0, 0, 300.0, 0.0, 0,
                             SSTV_MODE_MARTIN2, &status);
        check(mode == SSTV_MODE_MARTIN2,
              "lock: overrides a readable VIS (44 -> locked Martin 2)");

        // The header hunt itself is unchanged: a truncated leader still
        // detects nothing — the lock is not "decode anything".
        mode = detect_locked(44, 0.0, 0, 30.0, 0.0, 0,
                             SSTV_MODE_MARTIN2, &status);
        check(mode == -1 && status != SSTV_STATUS_IMAGE,
              "lock: truncated leader still rejected");
    }

    // 9. Full roundtrip with corrupted VIS cells: encode a real Martin 2
    //    transmission, overwrite the 8 data+parity cells (t0=610 ms, cells
    //    at 640..880 ms) with the 1900 Hz leader tone — every bit reads 0,
    //    code 0 is unassigned, so auto rejects — then decode with the lock
    //    and require the image itself to survive (the timing anchor at the
    //    stop-bit end must still hold).
    {
        const sstv_mode_t* m = sstv_mode_get(SSTV_MODE_MARTIN2);
        uint32_t* img = sstv_testcard_alloc(m->width, m->height);
        int need = sstv_encode_num_samples(SSTV_MODE_MARTIN2, RATE);
        float* buf = (float*)malloc((size_t)need * sizeof(float));
        sstv_encode(SSTV_MODE_MARTIN2, img, m->width, m->height, RATE, 0.7f,
                    buf, need);
        int c0 = (int)(0.640 * RATE), c1 = (int)(0.880 * RATE);
        for (int i = c0; i < c1 && i < need; i++) {
            buf[i] = 0.7f * (float)sin(2.0 * M_PI * 1900.0 * (double)i / RATE);
        }
        float tail[4096] = { 0 };

        // Auto: the corrupted header is rejected as a VIS, so the decoder
        // falls back to the header-less sync-train lock (test_sstv_robust
        // covers that path in depth) — the mode still comes out of the
        // line timing, flagged as a non-VIS lock.
        sstv_decoder_t* d = sstv_decoder_create(RATE);
        sstv_test_push_all(d, buf, need);
        for (int i = 0; i < 8; i++) sstv_test_push_all(d, tail, 4096);
        check(sstv_decoder_mode(d) == SSTV_MODE_MARTIN2 &&
                  sstv_decoder_vis_locked(d) == 0,
              "lock roundtrip: corrupted VIS on auto falls back to a sync lock");
        sstv_decoder_destroy(d);

        // Locked: full image, PSNR at the mode's roundtrip floor, and the
        // header itself (not the sync train) anchored the image.
        d = sstv_decoder_create(RATE);
        sstv_decoder_set_forced_mode(d, SSTV_MODE_MARTIN2);
        sstv_test_push_all(d, buf, need);
        for (int i = 0; i < 8; i++) sstv_test_push_all(d, tail, 4096);
        check(sstv_decoder_status(d) == SSTV_STATUS_DONE,
              "lock roundtrip: corrupted VIS reaches DONE under the lock");
        check(sstv_decoder_vis_locked(d) == 1,
              "lock roundtrip: the forced header counts as a VIS lock");
        check(sstv_decoder_rows_ready(d) == m->height,
              "lock roundtrip: all rows decoded");
        uint32_t* out = (uint32_t*)calloc((size_t)m->width * m->height,
                                          sizeof(uint32_t));
        sstv_decoder_read_rows(d, 0, m->height, out);
        double psnr = sstv_test_min_psnr(img, out, m->width * m->height);
        printf("  info: locked Martin 2 with corrupted VIS: min PSNR %.1f dB\n",
               psnr);
        check(psnr >= 30.0, "lock roundtrip: PSNR >= 30 dB");
        free(out);
        sstv_decoder_destroy(d);
        free(buf);
        free(img);
    }

    if (g_failures) {
        printf("%d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("all sstv_vis tests passed\n");
    return 0;
}
