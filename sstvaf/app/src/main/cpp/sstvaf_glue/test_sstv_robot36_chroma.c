// test_sstv_robot36_chroma.c — Robot 36 chroma-order robustness.
//
// Robot 36 sends one chroma component per line, alternating R-Y and B-Y.
// The published convention starts with R-Y (1500 Hz separator) on the first
// line, but real encoders exist that start with B-Y (2300 Hz separator).
// The decoder must key the pairing off the MEASURED separator tone, not an
// assumed line parity — this test encodes both orders (via
// SSTV_ENCODE_SWAP_ROBOT36_PARITY) and requires correct colors from each.
//
// HOW TO RUN: sstvaf_glue/run_sstv_host_tests.ps1 / run_sstv_host_tests.sh.

#include "sstv.h"
#include "sstv_modes.h"

#include "sstv_test_util.h"

#define RATE 12000

// Mean channel value over a rectangle. channel: 0=R 1=G 2=B.
static double region_mean(const uint32_t* img, int w, int x0, int x1, int y0,
                          int y1, int channel)
{
    int shift = (channel == 0) ? 16 : (channel == 1) ? 8 : 0;
    double sum = 0.0;
    int cnt = 0;
    for (int y = y0; y < y1; y++) {
        for (int x = x0; x < x1; x++) {
            sum += (double)((img[(size_t)y * w + x] >> shift) & 0xFF);
            cnt++;
        }
    }
    return sum / cnt;
}

static double decode_all(const float* buf, int n, const sstv_mode_t* m,
                         uint32_t* out, int* status_out)
{
    sstv_decoder_t* d = sstv_decoder_create(RATE);
    sstv_test_push_all(d, buf, n);
    float tail[4096] = { 0 };
    for (int i = 0; i < 8; i++) sstv_test_push_all(d, tail, 4096);
    if (status_out) *status_out = sstv_decoder_status(d);
    int rows = sstv_decoder_read_rows(d, 0, m->height, out);
    sstv_decoder_destroy(d);
    return rows;
}

int main(void)
{
    printf("sstv_robot36_chroma tests:\n");
    char label[160];

    const sstv_mode_t* m = sstv_mode_get(SSTV_MODE_ROBOT36);
    int w = m->width, h = m->height, npx = w * h;
    uint32_t* img = sstv_testcard_alloc(w, h);
    int need = sstv_encode_num_samples(SSTV_MODE_ROBOT36, RATE);

    float* norm = (float*)malloc((size_t)need * sizeof(float));
    float* swap = (float*)malloc((size_t)need * sizeof(float));
    check(sstv_encode(SSTV_MODE_ROBOT36, img, w, h, RATE, 0.7f, norm, need)
              == need,
          "normal-parity encode succeeds");
    check(sstv_encode_ex(SSTV_MODE_ROBOT36, img, w, h, RATE, 0.7f,
                         SSTV_ENCODE_SWAP_ROBOT36_PARITY, swap, need) == need,
          "swapped-parity encode succeeds");

    // The two encodings must actually differ (the flag is not a no-op).
    int differs = 0;
    for (int i = 0; i < need; i++) {
        if (norm[i] != swap[i]) {
            differs = 1;
            break;
        }
    }
    check(differs, "swapped encode differs from normal encode");

    uint32_t* out_n = (uint32_t*)calloc((size_t)npx, 4);
    uint32_t* out_s = (uint32_t*)calloc((size_t)npx, 4);
    int st_n, st_s;
    check(decode_all(norm, need, m, out_n, &st_n) == h &&
              st_n == SSTV_STATUS_DONE,
          "normal-parity decodes fully");
    check(decode_all(swap, need, m, out_s, &st_s) == h &&
              st_s == SSTV_STATUS_DONE,
          "swapped-parity decodes fully");

    double pn = sstv_test_min_psnr(img, out_n, npx);
    double ps = sstv_test_min_psnr(img, out_s, npx);
    printf("  info: PSNR normal=%.1f dB swapped=%.1f dB\n", pn, ps);
    check(pn >= 24.0, "normal-parity PSNR >= 24 dB");
    check(ps >= 24.0, "swapped-parity PSNR >= 24 dB");
    check(fabs(pn - ps) <= 3.0, "swapped decode within 3 dB of normal");

    // Color sanity on the swapped decode: if the decoder assumed parity
    // instead of reading the separator tone, R-Y and B-Y would swap and the
    // red bar (bar 5, x in [200,240)) would come out blue-ish and vice
    // versa. Check dominance in the bar centers, away from edges.
    struct {
        int x0, x1;
        int hi_ch, lo_ch;  // channel expected high vs low
        const char* what;
    } cases[] = {
        { 204, 236, 0, 2, "red bar stays red (R >> B)" },
        { 244, 276, 2, 0, "blue bar stays blue (B >> R)" },
        { 44, 76, 0, 2, "yellow bar keeps R over B" },
        { 84, 116, 2, 0, "cyan bar keeps B over R" },
    };
    for (int i = 0; i < 4; i++) {
        double hi = region_mean(out_s, w, cases[i].x0, cases[i].x1, 8, h / 2 - 8,
                                cases[i].hi_ch);
        double lo = region_mean(out_s, w, cases[i].x0, cases[i].x1, 8, h / 2 - 8,
                                cases[i].lo_ch);
        snprintf(label, sizeof(label), "swapped: %s (%.0f vs %.0f)",
                 cases[i].what, hi, lo);
        check(hi > lo + 100.0, label);
    }

    free(out_n);
    free(out_s);
    free(norm);
    free(swap);
    free(img);

    if (g_failures) {
        printf("%d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("all sstv_robot36_chroma tests passed\n");
    return 0;
}
