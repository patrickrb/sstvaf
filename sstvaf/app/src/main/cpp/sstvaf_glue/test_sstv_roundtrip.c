// test_sstv_roundtrip.c — encode → decode roundtrip for every mode.
//
// Per mode at 12 kHz: encode the synthetic test card (color bars + RGB
// gradients), push the audio through a fresh decoder in live-sized chunks,
// then assert the mode was detected, every row came out, DONE was reached,
// and the per-channel PSNR clears the mode's floor. The floors were set
// empirically from a clean run minus ~2 dB of headroom: GBR modes carry the
// channels verbatim (high floors); Robot/PD lose chroma resolution to
// subsampling/averaging and studio-swing quantization (lower floors).
//
// Also covers: a truncated transmission followed by silence must ABORT and
// retain the partial image; a 48 kHz roundtrip pins the rate-independence
// of the timing math.
//
// HOW TO RUN: sstvaf_glue/run_sstv_host_tests.ps1 / run_sstv_host_tests.sh.

#include "sstv.h"
#include "sstv_modes.h"

#include "sstv_test_util.h"

typedef struct {
    int mode_id;
    double floor_db;   // min per-channel PSNR, clean roundtrip @12 kHz
} rt_case_t;

// Floors ~= measured clean result minus ~3 dB headroom. Measured on this
// codec at 12 kHz (min channel): M1 47.8, M2 33.1, S1 45.3, S2 35.5,
// R36 27.1, R72 30.7, PD50 36.2, PD90 48.5, PD120 33.6. Martin 2 is the
// fastest GBR line (229 µs pixels), so its clean floor sits below the ~35 dB
// the slower GBR modes clear; the Robot/PD floors reflect chroma
// subsampling/averaging plus studio-swing quantization.
static const rt_case_t kCases[] = {
    { SSTV_MODE_MARTIN1,  44.0 },
    { SSTV_MODE_MARTIN2,  30.0 },
    { SSTV_MODE_SCOTTIE1, 42.0 },
    { SSTV_MODE_SCOTTIE2, 32.0 },
    { SSTV_MODE_ROBOT36,  24.0 },
    { SSTV_MODE_ROBOT72,  27.0 },
    { SSTV_MODE_PD50,     33.0 },
    { SSTV_MODE_PD90,     45.0 },
    { SSTV_MODE_PD120,    30.0 },
    // Appended batch (issue #16). Floors set ~3 dB below the clean measured
    // PSNR (printed by the info lines); GBR modes carry channels verbatim,
    // PD modes lose chroma to averaging.
    { SSTV_MODE_SCOTTIEDX, 58.0 },  // clean min ~63 dB (1.08 ms/px, near-lossless)
    { SSTV_MODE_MARTIN3,   44.0 },  // clean min ~47.7 (M1 timing)
    { SSTV_MODE_MARTIN4,   30.0 },  // clean min ~32.9 (M2 timing)
    { SSTV_MODE_PD160,     39.0 },  // clean min ~43.4
    { SSTV_MODE_PD180,     36.0 },  // clean min ~40.3
    { SSTV_MODE_PD240,     39.0 },  // clean min ~43.0
    { SSTV_MODE_PD290,     36.0 },  // clean min ~40.2
};
#define N_CASES ((int)(sizeof(kCases) / sizeof(kCases[0])))

// Encode the test card at `rate`, decode it, and PSNR-score the result.
// Returns the min per-channel PSNR (or -1 on failure), and reports
// mode/rows/status through the check() labels.
static double roundtrip(int mode_id, int rate, double floor_db)
{
    char label[160];
    const sstv_mode_t* m = sstv_mode_get(mode_id);
    uint32_t* img = sstv_testcard_alloc(m->width, m->height);

    int need = sstv_encode_num_samples(mode_id, rate);
    float* buf = (float*)malloc((size_t)need * sizeof(float));
    int got = sstv_encode(mode_id, img, m->width, m->height, rate, 0.7f, buf, need);
    snprintf(label, sizeof(label), "%s @%d: encode succeeds", m->name, rate);
    check(got == need, label);

    sstv_decoder_t* d = sstv_decoder_create(rate);
    sstv_test_push_all(d, buf, got);
    // A little trailing silence lets the decoder finish the last frame.
    float tail[4096] = { 0 };
    for (int i = 0; i < 8; i++) sstv_test_push_all(d, tail, 4096);

    snprintf(label, sizeof(label), "%s @%d: mode detected", m->name, rate);
    check(sstv_decoder_mode(d) == mode_id, label);
    snprintf(label, sizeof(label), "%s @%d: status DONE", m->name, rate);
    check(sstv_decoder_status(d) == SSTV_STATUS_DONE, label);
    snprintf(label, sizeof(label), "%s @%d: all %d rows ready", m->name, rate,
             m->height);
    check(sstv_decoder_rows_ready(d) == m->height, label);
    snprintf(label, sizeof(label), "%s @%d: quality > 0.8", m->name, rate);
    check(sstv_decoder_quality(d) > 0.8f, label);
    snprintf(label, sizeof(label), "%s @%d: slant ~0 on a clean clock", m->name,
             rate);
    check(fabs(sstv_decoder_slant_ppm(d)) < 60.0f, label);

    double psnr = -1.0;
    uint32_t* out = (uint32_t*)calloc((size_t)m->width * m->height,
                                      sizeof(uint32_t));
    int rows = sstv_decoder_read_rows(d, 0, m->height, out);
    snprintf(label, sizeof(label), "%s @%d: read_rows returns %d", m->name, rate,
             m->height);
    check(rows == m->height, label);
    if (rows == m->height) {
        int npx = m->width * m->height;
        double pr = sstv_test_psnr(img, out, npx, 0);
        double pg = sstv_test_psnr(img, out, npx, 1);
        double pb = sstv_test_psnr(img, out, npx, 2);
        psnr = pr < pg ? pr : pg;
        if (pb < psnr) psnr = pb;
        printf("  info: %s @%d PSNR R=%.1f G=%.1f B=%.1f dB (floor %.1f)\n",
               m->name, rate, pr, pg, pb, floor_db);
        snprintf(label, sizeof(label), "%s @%d: PSNR >= %.1f dB", m->name, rate,
                 floor_db);
        check(psnr >= floor_db, label);
    }

    free(out);
    sstv_decoder_destroy(d);
    free(buf);
    free(img);
    return psnr;
}

int main(void)
{
    printf("sstv_roundtrip tests:\n");
    char label[160];

    for (int i = 0; i < N_CASES; i++) {
        roundtrip(kCases[i].mode_id, 12000, kCases[i].floor_db);
    }

    // Rate independence: one GBR mode at 48 kHz.
    roundtrip(SSTV_MODE_MARTIN2, 48000, 31.0);

    // Truncated transmission: stop 40% into the image, then silence. The
    // decoder must ABORT (sync gone AND energy collapsed) and keep the
    // partial image.
    {
        const sstv_mode_t* m = sstv_mode_get(SSTV_MODE_MARTIN2);
        uint32_t* img = sstv_testcard_alloc(m->width, m->height);
        int need = sstv_encode_num_samples(SSTV_MODE_MARTIN2, 12000);
        float* buf = (float*)malloc((size_t)need * sizeof(float));
        sstv_encode(SSTV_MODE_MARTIN2, img, m->width, m->height, 12000, 0.7f,
                    buf, need);

        int cut = (int)(need * 0.4);
        sstv_decoder_t* d = sstv_decoder_create(12000);
        sstv_test_push_all(d, buf, cut);
        check(sstv_decoder_status(d) == SSTV_STATUS_IMAGE,
              "truncation: decoding mid-image at the cut");
        int rows_at_cut = sstv_decoder_rows_ready(d);
        check(rows_at_cut > 50, "truncation: partial rows before the cut");

        float silence[4096] = { 0 };
        for (int i = 0; i < 40; i++) sstv_test_push_all(d, silence, 4096);
        check(sstv_decoder_status(d) == SSTV_STATUS_ABORTED,
              "truncation: silence aborts the decode");
        check(sstv_decoder_rows_ready(d) >= rows_at_cut,
              "truncation: partial image retained after abort");

        // A reset decoder hunts again.
        sstv_decoder_reset(d);
        check(sstv_decoder_status(d) == SSTV_STATUS_IDLE, "reset returns to IDLE");
        check(sstv_decoder_mode(d) == -1, "reset clears the mode");
        sstv_test_push_all(d, buf, need);
        float tail[4096] = { 0 };
        for (int i = 0; i < 8; i++) sstv_test_push_all(d, tail, 4096);
        check(sstv_decoder_status(d) == SSTV_STATUS_DONE,
              "reset decoder decodes a full transmission");

        sstv_decoder_destroy(d);
        free(buf);
        free(img);
    }

    // read_rows argument surface.
    {
        sstv_decoder_t* d = sstv_decoder_create(12000);
        uint32_t px[8];
        check(sstv_decoder_read_rows(d, 0, 1, px) == SSTV_ERR_BAD_MODE,
              "read_rows before VIS is an error");
        check(sstv_decoder_read_rows(d, -1, 1, px) == SSTV_ERR_BAD_ARGS,
              "read_rows rejects negative row");
        check(sstv_decoder_mode(d) == -1, "mode is -1 before VIS");
        check(sstv_decoder_rows_ready(d) == 0, "no rows before VIS");
        snprintf(label, sizeof(label), "decoder create rejects bad rate");
        check(sstv_decoder_create(100) == 0, label);
        sstv_decoder_destroy(d);
        sstv_decoder_destroy(0);  // must be a no-op
    }

    if (g_failures) {
        printf("%d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("all sstv_roundtrip tests passed\n");
    return 0;
}
