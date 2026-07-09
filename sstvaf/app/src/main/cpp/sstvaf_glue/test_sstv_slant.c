// test_sstv_slant.c — sample-clock slant tracking and sync freewheeling.
//
// Slant: encode at 12 kHz, linearly resample the audio by 1 ± ppm (a
// receiver whose ADC clock is off by that much), decode, and require:
//   - the mode still decodes fully (DONE, all rows),
//   - sstv_decoder_slant_ppm() reports the injected slant within ±10% for
//     the larger offsets (500/2000 ppm; 100 ppm is below the regression's
//     guaranteed resolution and only has to decode well),
//   - per-channel PSNR stays above a floor (set empirically, a few dB under
//     the clean-roundtrip result).
//
// Freewheel: blank five consecutive line syncs mid-image (overwrite with
// leader tone, which the sync search cannot lock to) — the regression must
// coast through on prediction and still deliver the full image.
//
// HOW TO RUN: sstvaf_glue/run_sstv_host_tests.ps1 / run_sstv_host_tests.sh.

#include "sstv.h"
#include "sstv_modes.h"

#include "sstv_test_util.h"

#define RATE 12000

// Decode buf and PSNR-score against img. Returns min per-channel PSNR;
// fills slant/status/rows.
static double decode_score(const float* buf, int n, const uint32_t* img,
                           const sstv_mode_t* m, float* slant_out,
                           int* status_out, int* rows_out)
{
    sstv_decoder_t* d = sstv_decoder_create(RATE);
    sstv_test_push_all(d, buf, n);
    float tail[4096] = { 0 };
    for (int i = 0; i < 8; i++) sstv_test_push_all(d, tail, 4096);

    if (slant_out) *slant_out = sstv_decoder_slant_ppm(d);
    if (status_out) *status_out = sstv_decoder_status(d);
    if (rows_out) *rows_out = sstv_decoder_rows_ready(d);

    double psnr = -1.0;
    uint32_t* out = (uint32_t*)calloc((size_t)m->width * m->height, 4);
    if (sstv_decoder_read_rows(d, 0, m->height, out) == m->height) {
        psnr = sstv_test_min_psnr(img, out, m->width * m->height);
    }
    free(out);
    sstv_decoder_destroy(d);
    return psnr;
}

static void slant_case(int mode_id, double ppm, int check_ppm, double floor_db)
{
    char label[160];
    const sstv_mode_t* m = sstv_mode_get(mode_id);
    uint32_t* img = sstv_testcard_alloc(m->width, m->height);
    int need = sstv_encode_num_samples(mode_id, RATE);
    float* buf = (float*)malloc((size_t)need * sizeof(float));
    sstv_encode(mode_id, img, m->width, m->height, RATE, 0.7f, buf, need);

    int cap = need + need / 200 + 16;
    float* rs = (float*)malloc((size_t)cap * sizeof(float));
    int rn = sstv_test_resample_ppm(buf, need, ppm, rs, cap);

    float slant;
    int status, rows;
    double psnr = decode_score(rs, rn, img, m, &slant, &status, &rows);

    snprintf(label, sizeof(label), "%s %+.0f ppm: full decode", m->name, ppm);
    check(status == SSTV_STATUS_DONE && rows == m->height, label);
    printf("  info: %s %+.0f ppm -> slant %.1f ppm, PSNR %.1f dB\n", m->name,
           ppm, slant, psnr);
    if (check_ppm) {
        snprintf(label, sizeof(label), "%s %+.0f ppm: slant within 10%%",
                 m->name, ppm);
        check(fabs(slant - ppm) <= 0.10 * fabs(ppm), label);
    }
    snprintf(label, sizeof(label), "%s %+.0f ppm: PSNR >= %.1f dB", m->name,
             ppm, floor_db);
    check(psnr >= floor_db, label);

    free(rs);
    free(buf);
    free(img);
}

int main(void)
{
    printf("sstv_slant tests:\n");
    char label[160];

    // Martin 2 across the specified offsets (fastest full image).
    slant_case(SSTV_MODE_MARTIN2, 100.0, 0, 27.0);
    slant_case(SSTV_MODE_MARTIN2, -500.0, 1, 27.0);
    slant_case(SSTV_MODE_MARTIN2, 500.0, 1, 27.0);
    slant_case(SSTV_MODE_MARTIN2, 2000.0, 1, 27.0);
    slant_case(SSTV_MODE_MARTIN2, -2000.0, 1, 27.0);

    // One mid-line-sync mode (Scottie) — its sync offset math is different.
    slant_case(SSTV_MODE_SCOTTIE1, 500.0, 1, 33.0);
    // And one two-rows-per-frame mode.
    slant_case(SSTV_MODE_PD50, -500.0, 1, 29.0);

    // Freewheel: blank the syncs of five consecutive Martin 1 lines with
    // leader tone; the tracker must coast through on the regression.
    {
        const sstv_mode_t* m = sstv_mode_get(SSTV_MODE_MARTIN1);
        uint32_t* img = sstv_testcard_alloc(m->width, m->height);
        int need = sstv_encode_num_samples(SSTV_MODE_MARTIN1, RATE);
        float* buf = (float*)malloc((size_t)need * sizeof(float));
        sstv_encode(SSTV_MODE_MARTIN1, img, m->width, m->height, RATE, 0.7f,
                    buf, need);

        for (int k = 100; k < 105; k++) {
            double t0 = 910000.0 + (double)k * m->line_us;  // sync start, µs
            int i0 = (int)(t0 * RATE / 1e6);
            int i1 = (int)((t0 + m->sync_us) * RATE / 1e6);
            for (int i = i0; i <= i1 && i < need; i++) {
                buf[i] = 0.7f * (float)sin(2.0 * M_PI * 1900.0 * i / RATE);
            }
        }

        float slant;
        int status, rows;
        double psnr = decode_score(buf, need, img, m, &slant, &status, &rows);
        check(status == SSTV_STATUS_DONE && rows == m->height,
              "freewheel: 5 blanked syncs still decode fully");
        printf("  info: freewheel PSNR %.1f dB, slant %.1f ppm\n", psnr, slant);
        snprintf(label, sizeof(label), "freewheel: PSNR >= 40 dB");
        check(psnr >= 40.0, label);

        free(buf);
        free(img);
    }

    if (g_failures) {
        printf("%d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("all sstv_slant tests passed\n");
    return 0;
}
