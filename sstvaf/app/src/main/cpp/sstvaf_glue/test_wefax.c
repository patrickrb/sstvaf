// test_wefax.c — WeFax / HF radiofax codec tests (clean-room wefax_lib).
//
// Covers, host-only (no device):
//   - the pure timing/mapping helpers (IOC->pixels, LPM->samples,
//     frequency<->gray, APT start/stop tone rates) against hand-computed
//     goldens,
//   - the line renderer (box-average of demod frequencies -> gray),
//   - line classification + phasing-pulse location, including a pulse that
//     wraps the free-run line boundary,
//   - a full encode -> FM-demod -> decode roundtrip: the decoder must lock to
//     the phasing signal, align the left margin, and reconstruct an
//     identical-per-row test pattern above a PSNR floor.
//
// The test pattern has NO vertical variation (every scan line is identical),
// so the roundtrip's PSNR isolates horizontal (phasing) alignment and demod
// fidelity from any sub-line vertical offset the demod group delay introduces.
//
// HOW TO RUN: sstvaf_glue/run_sstv_host_tests.ps1 / run_sstv_host_tests.sh.

#include "wefax.h"

#include "sstv_test_util.h"

// ---------------------------------------------------------------------------
// Grayscale test pattern: left half = 8 sharp gray bars, right half = a smooth
// 0..255 ramp. Identical on every row.
// ---------------------------------------------------------------------------
static void wefax_make_row(uint8_t* row, int w)
{
    int half = w / 2;
    for (int x = 0; x < w; x++) {
        if (x < half) {
            int i = (x * 8) / half;
            if (i > 7) i = 7;
            row[x] = (uint8_t)((i * 255) / 7);
        } else {
            int xx = x - half;
            int denom = (w - half - 1);
            if (denom < 1) denom = 1;
            row[x] = (uint8_t)((xx * 255) / denom);
        }
    }
}

static double gray_psnr(const uint8_t* a, const uint8_t* b, int n)
{
    double se = 0.0;
    for (int i = 0; i < n; i++) {
        double e = (double)a[i] - (double)b[i];
        se += e * e;
    }
    double mse = se / (double)n;
    if (mse <= 1e-12) return 99.0;
    return 10.0 * log10(255.0 * 255.0 / mse);
}

// ---------------------------------------------------------------------------
static void test_helpers(void)
{
    printf("wefax pure helpers:\n");

    check(wefax_pixels_per_line(576) == 1810, "IOC 576 -> 1810 px (576*pi)");
    check(wefax_pixels_per_line(288) == 905, "IOC 288 -> 905 px (288*pi)");
    check(wefax_pixels_per_line(0) == 0, "IOC 0 -> 0 px");

    check_near(wefax_samples_per_line(120, 12000), 6000.0, 1e-9,
               "120 lpm @12k -> 6000 samples/line");
    check_near(wefax_samples_per_line(60, 48000), 48000.0, 1e-9,
               "60 lpm @48k -> 48000 samples/line");
    check(wefax_samples_per_line(0, 12000) == 0.0, "bad lpm -> 0");

    check(wefax_freq_to_gray(1500.0) == 0, "1500 Hz -> gray 0 (black)");
    check(wefax_freq_to_gray(2300.0) == 255, "2300 Hz -> gray 255 (white)");
    check(wefax_freq_to_gray(1900.0) == 128, "1900 Hz -> gray 128 (mid)");
    check(wefax_freq_to_gray(1000.0) == 0, "below black clamps to 0");
    check(wefax_freq_to_gray(3000.0) == 255, "above white clamps to 255");

    check_near(wefax_gray_to_freq(0), 1500.0, 1e-9, "gray 0 -> 1500 Hz");
    check_near(wefax_gray_to_freq(255), 2300.0, 1e-9, "gray 255 -> 2300 Hz");
    check_near(wefax_gray_to_freq(128), 1500.0 + 128.0 * 800.0 / 255.0, 1e-9,
               "gray 128 -> mid frequency");

    check_near(wefax_start_tone_hz(576), 300.0, 1e-9, "IOC 576 start tone 300 Hz");
    check_near(wefax_start_tone_hz(288), 675.0, 1e-9, "IOC 288 start tone 675 Hz");
    check(wefax_start_tone_hz(999) < 0.0, "unknown IOC start tone < 0");
    check_near(wefax_stop_tone_hz(), 450.0, 1e-9, "stop tone 450 Hz");

    check(wefax_ioc_from_start_tone(300.0) == 576, "300 Hz -> IOC 576");
    check(wefax_ioc_from_start_tone(305.0) == 576, "305 Hz -> IOC 576 (tol)");
    check(wefax_ioc_from_start_tone(675.0) == 288, "675 Hz -> IOC 288");
    check(wefax_ioc_from_start_tone(500.0) == -1, "500 Hz -> no IOC");
}

// ---------------------------------------------------------------------------
static void test_render(void)
{
    printf("wefax line render:\n");
    enum { N = 3620, W = 1810 };
    double* f = (double*)malloc(sizeof(double) * N);
    uint8_t g[W];

    for (int i = 0; i < N; i++) f[i] = WEFAX_WHITE_HZ;
    wefax_render_line(f, N, W, g);
    check(g[0] == 255 && g[W / 2] == 255 && g[W - 1] == 255,
          "all-white frequencies -> gray 255");

    for (int i = 0; i < N; i++) f[i] = WEFAX_BLACK_HZ;
    wefax_render_line(f, N, W, g);
    check(g[0] == 0 && g[W - 1] == 0, "all-black frequencies -> gray 0");

    // Left third black, right third white: renders monotonic non-decreasing.
    for (int i = 0; i < N; i++)
        f[i] = (i < N / 2) ? WEFAX_BLACK_HZ : WEFAX_WHITE_HZ;
    wefax_render_line(f, N, W, g);
    check(g[0] == 0 && g[W - 1] == 255, "step frequency -> black..white gray");
    int nondec = 1;
    for (int x = 1; x < W; x++)
        if (g[x] < g[x - 1]) nondec = 0;
    check(nondec, "step render is monotonic non-decreasing");

    free(f);
}

// ---------------------------------------------------------------------------
static void test_classify(void)
{
    printf("wefax line classify:\n");
    enum { W = 1810 };
    uint8_t g[W];
    int pulse = -99;

    // Phasing line: white pulse at the very start (aligned case).
    int pw = (int)(W * WEFAX_PHASE_PULSE_FRAC);
    for (int x = 0; x < W; x++) g[x] = (x < pw) ? 255 : 0;
    check(wefax_classify_line(g, W, &pulse) == WEFAX_LINE_PHASING,
          "black line + leading white pulse -> PHASING");
    check(pulse == 0, "aligned pulse leading edge at column 0");

    // Phasing pulse shifted into the middle of the line.
    int k = 700;
    for (int x = 0; x < W; x++) g[x] = (x >= k && x < k + pw) ? 255 : 0;
    check(wefax_classify_line(g, W, &pulse) == WEFAX_LINE_PHASING,
          "shifted pulse -> PHASING");
    check(pulse == k, "shifted pulse leading edge located");

    // Pulse split across the free-run boundary: white at both ends.
    for (int x = 0; x < W; x++)
        g[x] = (x < 30 || x >= W - 30) ? 255 : 0;
    check(wefax_classify_line(g, W, &pulse) == WEFAX_LINE_PHASING,
          "wrap-split pulse -> PHASING");
    check(pulse == W - 30, "wrap-split pulse leading edge = start of tail run");

    // Picture line (the test pattern) must NOT look like phasing.
    wefax_make_row(g, W);
    check(wefax_classify_line(g, W, &pulse) == WEFAX_LINE_PICTURE,
          "test pattern -> PICTURE");
    check(pulse == -1, "picture line reports pulse_col -1");
}

// ---------------------------------------------------------------------------
static void test_roundtrip(void)
{
    printf("wefax encode->decode roundtrip:\n");
    const int lpm = 120, ioc = WEFAX_IOC_576, rate = 12000;
    const int H = 48, start_sec = 1, phasing = 6, stop_sec = 0;
    const int W = wefax_pixels_per_line(ioc);

    uint8_t* img = (uint8_t*)malloc((size_t)W * H);
    for (int y = 0; y < H; y++) wefax_make_row(img + (size_t)y * W, W);
    uint8_t* pattern = (uint8_t*)malloc((size_t)W);
    wefax_make_row(pattern, W);

    int need = wefax_encode_num_samples(lpm, ioc, H, rate, start_sec, phasing, stop_sec);
    check(need > 0, "encode_num_samples positive");
    float* buf = (float*)malloc((size_t)need * sizeof(float));
    int got = wefax_encode(lpm, ioc, img, W, H, rate, 0.7f, start_sec, phasing,
                           stop_sec, buf, need);
    check(got == need, "encode fills exactly num_samples");

    wefax_decoder_t* d = wefax_decoder_create(rate, lpm, ioc);
    check(d != NULL, "decoder created");
    check(wefax_decoder_width(d) == W, "decoder width == pixels per line");

    // Push in live-sized chunks.
    const int chunk = 2048;
    for (int off = 0; off < got; off += chunk) {
        int c = (got - off < chunk) ? (got - off) : chunk;
        wefax_decoder_push(d, buf + off, c);
    }

    check(wefax_decoder_status(d) == WEFAX_STATUS_IMAGE,
          "decoder locked to phasing (status IMAGE)");
    int ready = wefax_decoder_rows_ready(d);
    printf("  info: %d picture rows decoded (image height %d)\n", ready, H);
    check(ready >= H - 3, "decoded at least height-3 rows");
    check(ready <= H + 2, "no runaway spurious rows");

    // PSNR every decoded row against the (identical) source pattern.
    uint8_t* row = (uint8_t*)malloc((size_t)W);
    int good = 0;
    double best = 0.0;
    const double floor_db = 15.0;
    for (int r = 0; r < ready; r++) {
        int n = wefax_decoder_read_rows(d, r, 1, row);
        if (n != 1) continue;
        double p = gray_psnr(pattern, row, W);
        if (p > best) best = p;
        if (p >= floor_db) good++;
    }
    printf("  info: best row PSNR %.1f dB, %d/%d rows >= %.0f dB\n",
           best, good, ready, floor_db);
    check(best >= floor_db, "at least one row clears the PSNR floor");
    check(good >= H - 3, "nearly all rows clear the PSNR floor");

    wefax_decoder_finish(d);
    check(wefax_decoder_status(d) == WEFAX_STATUS_DONE, "finish -> DONE");

    free(row);
    wefax_decoder_destroy(d);
    free(buf);
    free(pattern);
    free(img);
}

// ---------------------------------------------------------------------------
// A live stream that ends mid-line must not drop its last row: finish() pads
// the partial line and runs the normal completion path.
static void test_finish_flushes_partial_line(void)
{
    printf("wefax finish flushes partial final line:\n");
    const int lpm = 120, ioc = WEFAX_IOC_576, rate = 12000;
    const int H = 48, start_sec = 1, phasing = 6, stop_sec = 0;
    const int W = wefax_pixels_per_line(ioc);

    uint8_t* img = (uint8_t*)malloc((size_t)W * H);
    for (int y = 0; y < H; y++) wefax_make_row(img + (size_t)y * W, W);

    int need = wefax_encode_num_samples(lpm, ioc, H, rate, start_sec, phasing, stop_sec);
    float* buf = (float*)malloc((size_t)need * sizeof(float));
    int got = wefax_encode(lpm, ioc, img, W, H, rate, 0.7f, start_sec, phasing,
                           stop_sec, buf, need);
    check(got == need, "encode fills exactly num_samples");

    wefax_decoder_t* d = wefax_decoder_create(rate, lpm, ioc);
    // Drop the final quarter-line of samples so the stream ends partway through
    // the last picture line (line is 6000 samples at 120 lpm @ 12 kHz), leaving
    // well over half a line buffered.
    const int trim = 1500;
    int pushed = got - trim;
    check(pushed > 0, "trimmed stream still non-empty");
    const int chunk = 2048;
    for (int off = 0; off < pushed; off += chunk) {
        int c = (pushed - off < chunk) ? (pushed - off) : chunk;
        wefax_decoder_push(d, buf + off, c);
    }
    check(wefax_decoder_status(d) == WEFAX_STATUS_IMAGE, "locked before finish");

    int before = wefax_decoder_rows_ready(d);
    wefax_decoder_finish(d);
    int after = wefax_decoder_rows_ready(d);
    check(wefax_decoder_status(d) == WEFAX_STATUS_DONE, "finish -> DONE");
    printf("  info: rows %d -> %d across finish()\n", before, after);
    check(after == before + 1, "finish flushes exactly the one partial line");

    wefax_decoder_destroy(d);
    free(buf);
    free(img);
}

// ---------------------------------------------------------------------------
static void test_api_surface(void)
{
    printf("wefax API surface:\n");

    check(wefax_decoder_create(12000, 0, 576) == NULL, "create rejects bad lpm");
    check(wefax_decoder_create(12000, 120, 0) == NULL, "create rejects bad ioc");

    check(wefax_encode_num_samples(120, 576, 0, 12000, 1, 6, 0) < 0,
          "encode_num_samples rejects zero height");
    check(wefax_encode_num_samples(120, 576, 10, 100, 1, 6, 0) < 0,
          "encode_num_samples rejects bad rate");

    wefax_decoder_t* d = wefax_decoder_create(12000, 120, 576);
    check(d != NULL, "decoder created for surface checks");
    uint8_t px[8];
    check(wefax_decoder_status(d) == WEFAX_STATUS_IDLE, "fresh decoder IDLE");
    check(wefax_decoder_rows_ready(d) == 0, "fresh decoder has no rows");
    check(wefax_decoder_read_rows(d, 0, 1, px) == 0, "read before any row -> 0");
    check(wefax_decoder_read_rows(d, -1, 1, px) == WEFAX_ERR_BAD_ARGS,
          "read_rows rejects negative first_row");

    // reset returns to IDLE.
    wefax_decoder_reset(d);
    check(wefax_decoder_status(d) == WEFAX_STATUS_IDLE, "reset -> IDLE");
    check(wefax_decoder_rows_ready(d) == 0, "reset clears rows");

    wefax_decoder_destroy(d);
    wefax_decoder_destroy(NULL);   // must be a no-op
    check(1, "destroy(NULL) is a no-op");
}

int main(void)
{
    printf("wefax tests:\n");
    test_helpers();
    test_render();
    test_classify();
    test_roundtrip();
    test_finish_flushes_partial_line();
    test_api_surface();

    if (g_failures) {
        printf("%d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("all wefax tests passed\n");
    return 0;
}
