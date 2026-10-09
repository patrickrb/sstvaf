// test_sstv_robust.c — real-signal robustness of the SSTV decoder.
//
// Every case here reproduces, synthetically and deterministically, a failure
// first seen on an off-air recording (see fixtures/README.md and
// test_sstv_recordings.c for the recordings themselves):
//
//   - sync re-acquisition: audio cut out of / silence inserted into the
//     middle of an image shifts every later sync by a constant offset; the
//     tracker must re-anchor instead of freewheeling to a sheared picture;
//   - header-less lock: a transmission joined mid-image (no VIS at all)
//     must lock from its sync train alone, in GBR, Robot (separator-keyed
//     chroma) and PD (two rows per frame) modes, and under a forced mode;
//   - large clock slant: ±0.8 % sound-card errors (far beyond the ±2000 ppm
//     the slant suite covers) must still decode;
//   - VIS preemption: a new calibration header under a running image ends
//     it and the following reset decodes the new transmission from line 0,
//     while plain image content (a mid-gray picture, whose 1900 Hz looks
//     like a leader) must never trigger it;
//   - a terminal-state decoder keeps buffering audio until reset.
//
// HOW TO RUN: sstvaf_glue/run_sstv_host_tests.ps1 / run_sstv_host_tests.sh.

#include "sstv.h"
#include "sstv_modes.h"

#include "sstv_test_util.h"

#define RATE 12000
#define CHUNK 2048

typedef struct {
    int status, mode, rows, vis_locked;
    float quality, slant;
} result_t;

// Push buf (chunked, as live audio arrives) plus ~2.7 s of trailing silence.
static void run_decoder(sstv_decoder_t* d, const float* buf, int n)
{
    sstv_test_push_all(d, buf, n);
    float tail[4096] = { 0 };
    for (int i = 0; i < 8; i++) sstv_test_push_all(d, tail, 4096);
}

static void read_result(const sstv_decoder_t* d, result_t* r)
{
    r->status = sstv_decoder_status(d);
    r->mode = sstv_decoder_mode(d);
    r->rows = sstv_decoder_rows_ready(d);
    r->vis_locked = sstv_decoder_vis_locked(d);
    r->quality = sstv_decoder_quality(d);
    r->slant = sstv_decoder_slant_ppm(d);
}

static float* encode_card(int mode_id, uint32_t** img_out, int* n_out)
{
    const sstv_mode_t* m = sstv_mode_get(mode_id);
    uint32_t* img = sstv_testcard_alloc(m->width, m->height);
    int need = sstv_encode_num_samples(mode_id, RATE);
    float* buf = (float*)malloc((size_t)need * sizeof(float));
    sstv_encode(mode_id, img, m->width, m->height, RATE, 0.7f, buf, need);
    *img_out = img;
    *n_out = need;
    return buf;
}

// Min per-channel PSNR of decoded rows [r0, r1) against source rows
// [r0 + off, r1 + off).
static double psnr_rows(const uint32_t* src, const uint32_t* dec, int w, int r0,
                        int r1, int off)
{
    return sstv_test_min_psnr(src + (size_t)(r0 + off) * w, dec + (size_t)r0 * w,
                              (r1 - r0) * w);
}

// Best PSNR over row offsets [0, max_off] — a header-less lock starts its
// row count at whichever line it first saw.
static double psnr_best_offset(const uint32_t* src, const uint32_t* dec, int w,
                               int h, int r0, int r1, int max_off, int* off_out)
{
    double best = -1.0;
    for (int off = 0; off <= max_off; off++) {
        int r1c = (r1 + off > h) ? h - off : r1;  // clamp to the source height
        if (r1c - r0 < 8) break;
        double p = psnr_rows(src, dec, w, r0, r1c, off);
        if (p > best) {
            best = p;
            if (off_out) *off_out = off;
        }
    }
    return best;
}

static uint32_t* read_image(const sstv_decoder_t* d, const sstv_mode_t* m)
{
    uint32_t* out = (uint32_t*)calloc((size_t)m->width * m->height, 4);
    sstv_decoder_read_rows(d, 0, m->height, out);
    return out;
}

// Sample index of the start of line `line` (frame for PD), header included.
static int line_start_sample(const sstv_mode_t* m, int line)
{
    double us = SSTV_HEADER_US + m->starting_sync_us + (double)line * m->line_us;
    return (int)(us * RATE / 1e6);
}

// ---------------------------------------------------------------------------
// 1. Sync re-acquisition: cut 15 ms out of a Martin 1 image at line 40.
// ---------------------------------------------------------------------------
static void test_phase_jump_cut(void)
{
    const sstv_mode_t* m = sstv_mode_get(SSTV_MODE_MARTIN1);
    uint32_t* img;
    int n;
    float* buf = encode_card(SSTV_MODE_MARTIN1, &img, &n);

    int cut_at = line_start_sample(m, 40) + (int)(0.2 * m->line_us * RATE / 1e6);
    int cut_len = (int)(0.015 * RATE);
    memmove(buf + cut_at, buf + cut_at + cut_len,
            (size_t)(n - cut_at - cut_len) * sizeof(float));
    n -= cut_len;

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    run_decoder(d, buf, n);
    result_t r;
    read_result(d, &r);
    check(r.status == SSTV_STATUS_DONE && r.rows == m->height,
          "cut 15 ms: still a full Martin 1 decode");
    uint32_t* out = read_image(d, m);
    double before = psnr_rows(img, out, m->width, 0, 40, 0);
    double after = psnr_rows(img, out, m->width, 48, m->height, 0);
    printf("  info: cut 15 ms -> quality %.2f slant %.0f ppm, PSNR before %.1f dB,"
           " after re-anchor %.1f dB\n", r.quality, r.slant, before, after);
    check(after >= 40.0, "cut 15 ms: rows after the jump re-anchored (PSNR >= 40 dB)");
    check(r.quality >= 0.9, "cut 15 ms: sync hit rate recovered (quality >= 0.9)");
    check(fabs(r.slant) < 300.0, "cut 15 ms: slope history survived the jump");
    free(out);
    sstv_decoder_destroy(d);
    free(buf);
    free(img);
}

// ---------------------------------------------------------------------------
// 2. Sync re-acquisition: 25 ms of silence inserted into Robot 36 at line 100
//    (sender glitch); Robot 36 also exercises the pair-aligned re-decode.
// ---------------------------------------------------------------------------
static void test_phase_jump_insert(void)
{
    const sstv_mode_t* m = sstv_mode_get(SSTV_MODE_ROBOT36);
    uint32_t* img;
    int n;
    float* buf = encode_card(SSTV_MODE_ROBOT36, &img, &n);

    int at = line_start_sample(m, 100) + (int)(0.5 * m->line_us * RATE / 1e6);
    int ins = (int)(0.025 * RATE);
    float* buf2 = (float*)calloc((size_t)(n + ins), sizeof(float));
    memcpy(buf2, buf, (size_t)at * sizeof(float));
    memcpy(buf2 + at + ins, buf + at, (size_t)(n - at) * sizeof(float));

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    run_decoder(d, buf2, n + ins);
    result_t r;
    read_result(d, &r);
    check(r.status == SSTV_STATUS_DONE && r.rows == m->height,
          "insert 25 ms: still a full Robot 36 decode");
    uint32_t* out = read_image(d, m);
    double after = psnr_rows(img, out, m->width, 110, m->height, 0);
    printf("  info: insert 25 ms -> quality %.2f, PSNR after re-anchor %.1f dB\n",
           r.quality, after);
    check(after >= 23.0, "insert 25 ms: rows after the jump re-anchored (PSNR >= 23 dB)");
    check(r.quality >= 0.9, "insert 25 ms: sync hit rate recovered");
    free(out);
    sstv_decoder_destroy(d);
    free(buf2);
    free(buf);
    free(img);
}

// ---------------------------------------------------------------------------
// 3. Header-less lock: join a transmission `skip_lines` lines (plus a
//    fraction) after its header. Expects the sync-train lock in `mode_id`,
//    nearly every remaining row, and the rows lining up with the source
//    (row offset <= skip_lines + 2 frames).
// ---------------------------------------------------------------------------
static void headerless_case(int mode_id, double skip_lines, int forced,
                            double floor_db, int min_rows_lost)
{
    char label[200];
    const sstv_mode_t* m = sstv_mode_get(mode_id);
    uint32_t* img;
    int n;
    float* buf = encode_card(mode_id, &img, &n);
    int start = (int)((SSTV_HEADER_US + m->starting_sync_us + skip_lines * m->line_us)
                      * RATE / 1e6);

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    if (forced >= 0) sstv_decoder_set_forced_mode(d, forced);
    run_decoder(d, buf + start, n - start);
    result_t r;
    read_result(d, &r);

    snprintf(label, sizeof(label), "%s joined at line %.1f: sync-train lock on the mode",
             m->name, skip_lines);
    check(r.mode == mode_id && r.vis_locked == 0, label);
    snprintf(label, sizeof(label), "%s header-less: ends ABORTED/DONE with the rows heard",
             m->name);
    int lost = m->height - r.rows;
    check((r.status == SSTV_STATUS_ABORTED || r.status == SSTV_STATUS_DONE) &&
              lost >= 0 && lost <= min_rows_lost,
          label);
    if (r.mode == mode_id && r.rows > 8) {
        uint32_t* out = read_image(d, m);
        int off = -1;
        int rpf = m->rows_per_frame;
        double p = psnr_best_offset(img, out, m->width, m->height, 2 * rpf,
                                    r.rows - 2 * rpf, (int)skip_lines * rpf + 4 * rpf, &off);
        printf("  info: %s header-less -> rows %d, row offset %d, PSNR %.1f dB, "
               "quality %.2f, slant %.0f ppm\n", m->name, r.rows, off, p, r.quality, r.slant);
        snprintf(label, sizeof(label), "%s header-less: rows match the source (PSNR >= %.0f dB)",
                 m->name, floor_db);
        check(p >= floor_db, label);
        free(out);
    }
    sstv_decoder_destroy(d);
    free(buf);
    free(img);
}

// Forced mode that does NOT match the sync train: must not lock.
static void test_headerless_forced_mismatch(void)
{
    const sstv_mode_t* m = sstv_mode_get(SSTV_MODE_MARTIN2);
    uint32_t* img;
    int n;
    float* buf = encode_card(SSTV_MODE_MARTIN2, &img, &n);
    int start = (int)((SSTV_HEADER_US + 3.5 * m->line_us) * RATE / 1e6);
    sstv_decoder_t* d = sstv_decoder_create(RATE);
    sstv_decoder_set_forced_mode(d, SSTV_MODE_SCOTTIE1);
    run_decoder(d, buf + start, n - start);
    check(sstv_decoder_mode(d) == -1,
          "header-less with a mismatching forced mode: no lock");
    sstv_decoder_destroy(d);
    free(buf);
    free(img);
}

// ---------------------------------------------------------------------------
// 4. Large clock slant (±8000 ppm): the young-fit window must catch the
//    second sync even when a line is 0.8 % longer than nominal.
// ---------------------------------------------------------------------------
static void slant_case(int mode_id, double ppm, double floor_db)
{
    char label[200];
    const sstv_mode_t* m = sstv_mode_get(mode_id);
    uint32_t* img;
    int n;
    float* buf = encode_card(mode_id, &img, &n);
    int cap = n + n / 50 + 16;
    float* rs = (float*)malloc((size_t)cap * sizeof(float));
    int rn = sstv_test_resample_ppm(buf, n, ppm, rs, cap);

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    run_decoder(d, rs, rn);
    result_t r;
    read_result(d, &r);
    snprintf(label, sizeof(label), "%s %+.0f ppm: full decode", m->name, ppm);
    check(r.status == SSTV_STATUS_DONE && r.rows == m->height, label);
    double p = -1.0;
    if (r.rows == m->height) {
        uint32_t* out = read_image(d, m);
        p = sstv_test_min_psnr(img, out, m->width * m->height);
        free(out);
    }
    printf("  info: %s %+.0f ppm -> slant %.0f ppm, PSNR %.1f dB\n", m->name, ppm,
           r.slant, p);
    snprintf(label, sizeof(label), "%s %+.0f ppm: slant within 10%%", m->name, ppm);
    check(fabs(r.slant - ppm) <= 0.10 * fabs(ppm), label);
    snprintf(label, sizeof(label), "%s %+.0f ppm: PSNR >= %.0f dB", m->name, ppm, floor_db);
    check(p >= floor_db, label);
    sstv_decoder_destroy(d);
    free(rs);
    free(buf);
    free(img);
}

// ---------------------------------------------------------------------------
// 5. VIS preemption: Robot 36 cut off after 60 lines, immediately followed by
//    a complete Martin 2 transmission.
// ---------------------------------------------------------------------------
static void test_vis_preemption(void)
{
    const sstv_mode_t* ma = sstv_mode_get(SSTV_MODE_ROBOT36);
    const sstv_mode_t* mb = sstv_mode_get(SSTV_MODE_MARTIN2);
    uint32_t *ia, *ib;
    int na, nb;
    float* a = encode_card(SSTV_MODE_ROBOT36, &ia, &na);
    float* b = encode_card(SSTV_MODE_MARTIN2, &ib, &nb);
    int cut = line_start_sample(ma, 60);
    int n = cut + nb;
    float* buf = (float*)malloc((size_t)n * sizeof(float));
    memcpy(buf, a, (size_t)cut * sizeof(float));
    memcpy(buf + cut, b, (size_t)nb * sizeof(float));

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    int aborted_at = -1, rows_a = 0, mode_a = -1;
    int image_after_reset = 0, mode_after_reset = -1, vis_after_reset = -1;
    int rows_after_reset = -1;
    for (int off = 0; off < n; off += CHUNK) {
        int c = (n - off < CHUNK) ? (n - off) : CHUNK;
        sstv_decoder_push(d, buf + off, c);
        if (aborted_at < 0 && sstv_decoder_status(d) == SSTV_STATUS_ABORTED) {
            aborted_at = off + c;
            rows_a = sstv_decoder_rows_ready(d);
            mode_a = sstv_decoder_mode(d);
            // The engine reads the partial frame, then resets — the reset
            // applies the pending header lock.
            sstv_decoder_reset(d);
            image_after_reset = sstv_decoder_status(d) == SSTV_STATUS_IMAGE;
            mode_after_reset = sstv_decoder_mode(d);
            vis_after_reset = sstv_decoder_vis_locked(d);
            rows_after_reset = sstv_decoder_rows_ready(d);
        }
    }
    float tail[4096] = { 0 };
    for (int i = 0; i < 8; i++) sstv_test_push_all(d, tail, 4096);

    double t_abort = aborted_at >= 0 ? (double)aborted_at / RATE : -1.0;
    printf("  info: preemption -> aborted at %.2f s (cut at %.2f s) with %d Robot 36 rows\n",
           t_abort, (double)cut / RATE, rows_a);
    check(aborted_at >= 0 && mode_a == SSTV_MODE_ROBOT36,
          "preemption: the running Robot 36 image ends ABORTED");
    check(rows_a >= 56 && rows_a <= 80, "preemption: partial Robot 36 rows retained");
    // Header is 0.91 s; the abort must land within ~1.2 s of the cut.
    check(aborted_at >= cut && aborted_at <= cut + (int)(1.3 * RATE),
          "preemption: fires as soon as the new header is decoded");
    check(image_after_reset && mode_after_reset == SSTV_MODE_MARTIN2 &&
              vis_after_reset == 1 && rows_after_reset == 0,
          "preemption: reset enters IMAGE for Martin 2 off the new VIS");

    result_t r;
    read_result(d, &r);
    check(r.status == SSTV_STATUS_DONE && r.mode == SSTV_MODE_MARTIN2 && r.rows == mb->height,
          "preemption: the Martin 2 image decodes fully");
    if (r.rows == mb->height) {
        uint32_t* out = read_image(d, mb);
        double p = sstv_test_min_psnr(ib, out, mb->width * mb->height);
        printf("  info: preemption -> Martin 2 PSNR %.1f dB from line 0\n", p);
        check(p >= 29.0, "preemption: Martin 2 PSNR >= 29 dB (nothing lost at the start)");
        free(out);
    }
    sstv_decoder_destroy(d);
    free(buf);
    free(a);
    free(b);
    free(ia);
    free(ib);
}

// ---------------------------------------------------------------------------
// 6. No false preemption: a uniform mid-gray Robot 36 picture is 1900 Hz for
//    88 ms of every line (a leader look-alike) with a 1200 Hz sync pulse
//    (a start-bit look-alike); the strict shadow profile must not bite.
// ---------------------------------------------------------------------------
static void test_no_false_preemption(void)
{
    const sstv_mode_t* m = sstv_mode_get(SSTV_MODE_ROBOT36);
    uint32_t* img = (uint32_t*)malloc((size_t)m->width * m->height * 4);
    for (int i = 0; i < m->width * m->height; i++) img[i] = 0xFF808080u;
    int need = sstv_encode_num_samples(SSTV_MODE_ROBOT36, RATE);
    float* buf = (float*)malloc((size_t)need * sizeof(float));
    sstv_encode(SSTV_MODE_ROBOT36, img, m->width, m->height, RATE, 0.7f, buf, need);

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    run_decoder(d, buf, need);
    result_t r;
    read_result(d, &r);
    check(r.status == SSTV_STATUS_DONE && r.rows == m->height && r.vis_locked == 1,
          "gray picture: decodes to DONE without a false preemption");
    sstv_decoder_destroy(d);
    free(buf);
    free(img);
}

// ---------------------------------------------------------------------------
// 7. Terminal state keeps buffering: a DONE decoder that is handed the next
//    transmission's header inside the same buffers, then reset, must still
//    lock that header (its audio is in the history, not lost).
// ---------------------------------------------------------------------------
static void test_terminal_keeps_history(void)
{
    const sstv_mode_t* m = sstv_mode_get(SSTV_MODE_MARTIN2);
    uint32_t* img;
    int n;
    float* buf = encode_card(SSTV_MODE_MARTIN2, &img, &n);
    // Two back-to-back transmissions.
    float* two = (float*)malloc((size_t)(2 * n) * sizeof(float));
    memcpy(two, buf, (size_t)n * sizeof(float));
    memcpy(two + n, buf, (size_t)n * sizeof(float));

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    // First image, plus 1.5 s of the second (its whole header) while DONE.
    int first = n + (int)(1.5 * RATE);
    sstv_test_push_all(d, two, first);
    check(sstv_decoder_status(d) == SSTV_STATUS_DONE, "terminal: first image DONE");
    sstv_decoder_reset(d);
    // Nothing of the second header was re-pushed; a reset decoder that kept
    // its history cannot recover the VIS (it already went by while DONE),
    // but the sync train is still there and must lock the mode.
    run_decoder(d, two + first, 2 * n - first);
    result_t r;
    read_result(d, &r);
    printf("  info: terminal history -> status %d mode %d rows %d vis %d\n", r.status,
           r.mode, r.rows, r.vis_locked);
    check(r.mode == SSTV_MODE_MARTIN2 && r.rows >= m->height - 12,
          "terminal: the second transmission still decodes after reset");
    sstv_decoder_destroy(d);
    free(two);
    free(buf);
    free(img);
}

int main(void)
{
    printf("sstv_robust tests:\n");

    test_phase_jump_cut();
    test_phase_jump_insert();

    headerless_case(SSTV_MODE_SCOTTIE1, 3.5, -1, 38.0, 12);
    headerless_case(SSTV_MODE_ROBOT36, 2.25, -1, 22.0, 12);
    headerless_case(SSTV_MODE_PD120, 1.5, -1, 27.0, 24);
    headerless_case(SSTV_MODE_MARTIN2, 3.5, SSTV_MODE_MARTIN2, 27.0, 12);
    test_headerless_forced_mismatch();

    slant_case(SSTV_MODE_ROBOT36, 8000.0, 22.0);
    slant_case(SSTV_MODE_PD90, -8000.0, 40.0);
    slant_case(SSTV_MODE_SCOTTIEDX, 5000.0, 50.0);

    test_vis_preemption();
    test_no_false_preemption();
    test_terminal_keeps_history();

    if (g_failures) {
        printf("%d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("all sstv_robust tests passed\n");
    return 0;
}
