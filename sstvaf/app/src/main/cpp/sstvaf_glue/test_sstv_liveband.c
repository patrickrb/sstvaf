// test_sstv_liveband.c — the SSTV decoder on a live band.
//
// A receiver parked on 14.230 MHz does not hear tidy transmissions with
// silence between them. It hears band noise brought up to full level by the
// AGC, people talking, carriers, and pictures whose header went out while
// someone else was still on the air. Every case here reproduces,
// synthetically and deterministically, something first seen in live off-air
// captures of that frequency (2026-11-15; the recordings themselves are in
// fixtures/, see its README and test_sstv_recordings.c):
//
//   - noise and speech are not pictures: minutes of either must not lock
//     (plain band noise crosses the sync threshold some forty times a
//     second in sync-length runs; speech does it more);
//   - a picture whose header was drowned must still decode from its FIRST
//     line and end DONE: the sync comb locks a couple of seconds in, then
//     walks back through the audio history to where the syncs begin;
//   - a station that stops mid-picture leaves AGC noise at signal level, not
//     silence: the decoder must not sit on the dead image to its nominal
//     end, deaf to the next transmission;
//   - a header read out of nothing (it happens on speech a few times an
//     hour) must be dropped within seconds, not painted as noise for the
//     length of an image;
//   - an image that was noise from top to bottom must not come out DONE;
//   - a picture that fades out for a few seconds and comes back is ONE
//     picture: none of the above may cut it in two.
//
// HOW TO RUN: sstvaf_glue/run_sstv_host_tests.ps1 / run_sstv_host_tests.sh.

#include "sstv.h"
#include "sstv_modes.h"

#include "sstv_test_util.h"

#define RATE 12000
#define CHUNK 2400       // 200 ms: the recorder tap's buffer size
#define SIG_AMP 0.7f     // encoder amplitude used throughout
// rms of a full-level tone: what a receiver's AGC brings band noise up to
// once the station it was holding down stops.
#define AGC_SIGMA (0.7 / 1.41421356)

// ---------------------------------------------------------------------------
// Signal builders
// ---------------------------------------------------------------------------
static float* encode_card(int mode_id, uint32_t** img_out, int* n_out)
{
    const sstv_mode_t* m = sstv_mode_get(mode_id);
    uint32_t* img = sstv_testcard_alloc(m->width, m->height);
    int need = sstv_encode_num_samples(mode_id, RATE);
    float* buf = (float*)malloc((size_t)need * sizeof(float));
    sstv_encode(mode_id, img, m->width, m->height, RATE, SIG_AMP, buf, need);
    *img_out = img;
    *n_out = need;
    return buf;
}

static int header_samples(void)
{
    return (int)(SSTV_HEADER_US * RATE / 1e6);
}

// Sample index of the start of line `line` (frame for PD), header included.
static int line_start_sample(const sstv_mode_t* m, int line)
{
    double us = SSTV_HEADER_US + m->starting_sync_us + (double)line * m->line_us;
    return (int)(us * RATE / 1e6);
}

// Speech-like QRM, added into out[0..n): voiced "syllables" — a fundamental
// gliding somewhere in 300..1300 Hz with a weaker upper partial, 40..250 ms
// long, 20..150 ms apart. To the SSTV discriminator this is what SSB speech
// is: stretches below the sync threshold of every length, at random.
static void add_speechlike(float* out, int n, double amp)
{
    double ph1 = 0.0, ph2 = 0.0;
    int i = 0;
    while (i < n) {
        int len = (int)((0.040 + 0.210 * sstv_test_rand01()) * RATE);
        int gap = (int)((0.020 + 0.130 * sstv_test_rand01()) * RATE);
        double f0 = 300.0 + 1000.0 * sstv_test_rand01();
        double f1 = f0 + (sstv_test_rand01() - 0.5) * 300.0;
        double k2 = 2.0 + sstv_test_rand01();
        for (int j = 0; j < len && i < n; j++, i++) {
            double t = (double)j / (double)len;
            double f = f0 + (f1 - f0) * t;
            double env = sin(M_PI * t);
            ph1 += 2.0 * M_PI * f / RATE;
            ph2 += 2.0 * M_PI * f * k2 / RATE;
            out[i] += (float)(amp * env * (sin(ph1) + 0.4 * sin(ph2)));
        }
        i += gap;
    }
}

// ---------------------------------------------------------------------------
// Decoder drivers
// ---------------------------------------------------------------------------
typedef struct {
    int images;            // terminal states seen (each followed by a reset)
    int first_status;      // of the first terminal state
    int first_mode, first_rows;
    double first_end_s;
    int last_status;       // of the last terminal state
    int last_mode, last_rows, last_vis;
    uint32_t* last_img;    // its image (width x height of last_mode), or NULL
    int locks;             // VIS_ACCEPTED + SYNC_LOCK events
    int ever_image;        // status was ever IMAGE
} run_t;

static void count_events(sstv_decoder_t* d, run_t* r)
{
    sstv_event_t ev[SSTV_EVENT_CAPACITY];
    int n = sstv_decoder_events(d, ev, SSTV_EVENT_CAPACITY);
    for (int i = 0; i < n; i++) {
        if (ev[i].type == SSTV_EVT_VIS_ACCEPTED || ev[i].type == SSTV_EVT_SYNC_LOCK) r->locks++;
    }
}

// Push buf the way the RX engine does: in recorder-sized chunks, polling
// after each, and resetting after every terminal state (which is what
// applies a pending lock).
static void run_engine(sstv_decoder_t* d, const float* buf, int n, run_t* r)
{
    for (int off = 0; off < n; off += CHUNK) {
        int c = (n - off < CHUNK) ? (n - off) : CHUNK;
        sstv_decoder_push(d, buf + off, c);
        count_events(d, r);
        int s = sstv_decoder_status(d);
        if (s == SSTV_STATUS_IMAGE) r->ever_image = 1;
        if (s == SSTV_STATUS_DONE || s == SSTV_STATUS_ABORTED) {
            if (r->images == 0) {
                r->first_status = s;
                r->first_mode = sstv_decoder_mode(d);
                r->first_rows = sstv_decoder_rows_ready(d);
                r->first_end_s = (double)(off + c) / RATE;
            }
            r->images++;
            r->last_status = s;
            r->last_mode = sstv_decoder_mode(d);
            r->last_rows = sstv_decoder_rows_ready(d);
            r->last_vis = sstv_decoder_vis_locked(d);
            free(r->last_img);
            r->last_img = 0;
            const sstv_mode_t* lm = sstv_mode_get(r->last_mode);
            if (lm) {
                // The engine snapshots the frame before the reset discards it.
                r->last_img = (uint32_t*)calloc((size_t)lm->width * lm->height, 4);
                sstv_decoder_read_rows(d, 0, r->last_rows, r->last_img);
            }
            sstv_decoder_reset(d);
            if (sstv_decoder_status(d) == SSTV_STATUS_IMAGE) r->ever_image = 1;
        }
    }
}

static void run_init(run_t* r)
{
    memset(r, 0, sizeof(*r));
    r->first_status = r->first_mode = -1;
    r->last_status = r->last_mode = -1;
    r->first_end_s = -1.0;
}

// Min per-channel PSNR of decoded rows [r0, r1) against source rows
// [r0 + off, r1 + off).
static double psnr_rows(const uint32_t* src, const uint32_t* dec, int w, int r0,
                        int r1, int off)
{
    return sstv_test_min_psnr(src + (size_t)(r0 + off) * w, dec + (size_t)r0 * w,
                              (r1 - r0) * w);
}

// Best PSNR over row offsets [0, max_off]: which source row the decoder's
// row 0 is.
static double psnr_best_offset(const uint32_t* src, const uint32_t* dec, int w,
                               int h, int r0, int r1, int max_off, int* off_out)
{
    double best = -1.0;
    for (int off = 0; off <= max_off; off++) {
        int r1c = (r1 + off > h) ? h - off : r1;
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

// ---------------------------------------------------------------------------
// 1. Band noise is not a picture.
// ---------------------------------------------------------------------------
static void test_noise_never_locks(void)
{
    int n = 240 * RATE;
    float* buf = (float*)calloc((size_t)n, sizeof(float));
    sstv_test_srand(0x51D5EED1ull);
    sstv_test_add_awgn(buf, n, 0.1);

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    run_t r;
    run_init(&r);
    run_engine(d, buf, n, &r);
    printf("  info: 4 min of band noise -> %d lock(s), %d image(s)\n", r.locks, r.images);
    check(r.locks == 0 && !r.ever_image && r.images == 0,
          "band noise, 4 minutes: no lock, no image");
    free(r.last_img);
    sstv_decoder_destroy(d);
    free(buf);
}

// ---------------------------------------------------------------------------
// 2. Speech is not a picture.
// ---------------------------------------------------------------------------
static void test_speech_never_locks(void)
{
    int n = 180 * RATE;
    float* buf = (float*)calloc((size_t)n, sizeof(float));
    sstv_test_srand(0xC0FFEE1234ull);
    add_speechlike(buf, n, 0.5);
    sstv_test_add_awgn(buf, n, 0.03);

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    run_t r;
    run_init(&r);
    run_engine(d, buf, n, &r);
    printf("  info: 3 min of speech-like QRM -> %d lock(s), %d image(s)\n", r.locks, r.images);
    check(r.locks == 0 && !r.ever_image && r.images == 0,
          "speech-like QRM, 3 minutes: no lock, no image");
    free(r.last_img);
    sstv_decoder_destroy(d);
    free(buf);
}

// ---------------------------------------------------------------------------
// 3. Header drowned: 3 s of speech, then a transmission whose calibration
//    header is speech too (someone still talking when the picture starts).
//    The picture itself is clean. It must lock from the sync comb, decode
//    from its first line (row offset 0), and end DONE with every row.
// ---------------------------------------------------------------------------
static void header_lost_case(int mode_id, double floor_db)
{
    char label[200];
    const sstv_mode_t* m = sstv_mode_get(mode_id);
    uint32_t* img;
    int n;
    float* tx = encode_card(mode_id, &img, &n);
    int lead = 3 * RATE, hdr = header_samples(), tail = 3 * RATE;
    int total = lead + n + tail;
    float* buf = (float*)calloc((size_t)total, sizeof(float));
    memcpy(buf + lead + hdr, tx + hdr, (size_t)(n - hdr) * sizeof(float));
    sstv_test_srand(0xBADC0DEull + (uint64_t)mode_id);
    add_speechlike(buf, lead + hdr, 0.5);
    sstv_test_add_awgn(buf, total, 0.01);

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    sstv_test_push_all(d, buf, total);
    int status = sstv_decoder_status(d), rows = sstv_decoder_rows_ready(d);
    int vis = sstv_decoder_vis_locked(d);
    snprintf(label, sizeof(label), "%s, header drowned: locks from the sync comb", m->name);
    check(sstv_decoder_mode(d) == mode_id && vis == 0, label);
    snprintf(label, sizeof(label), "%s, header drowned: DONE with every row", m->name);
    check(status == SSTV_STATUS_DONE && rows == m->height, label);
    if (sstv_decoder_mode(d) == mode_id && rows > 16) {
        uint32_t* out = read_image(d, m);
        int off = -1;
        int rpf = m->rows_per_frame;
        double p = psnr_best_offset(img, out, m->width, m->height, 2 * rpf, rows - 2 * rpf,
                                    8 * rpf, &off);
        printf("  info: %s header drowned -> rows %d, row offset %d, PSNR %.1f dB,"
               " quality %.2f, slant %.0f ppm\n", m->name, rows, off, p,
               sstv_decoder_quality(d), sstv_decoder_slant_ppm(d));
        snprintf(label, sizeof(label), "%s, header drowned: row 0 is the picture's first line",
                 m->name);
        check(off == 0, label);
        snprintf(label, sizeof(label), "%s, header drowned: PSNR >= %.0f dB", m->name, floor_db);
        check(p >= floor_db, label);
        free(out);
    }
    sstv_decoder_destroy(d);
    free(buf);
    free(tx);
    free(img);
}

// ---------------------------------------------------------------------------
// 4. A station stops 40 % into a Scottie 1 picture. The receiver's AGC
//    brings the band noise up to signal level, so the in-band energy never
//    collapses. Three seconds later another station sends Martin 1, its
//    header lost. The Scottie 1 image must end (ABORTED, rows kept) once the
//    Martin 1 syncs are established, and the Martin 1 picture must decode
//    from its first line to DONE.
// ---------------------------------------------------------------------------
static void test_dead_image_is_preempted(void)
{
    const sstv_mode_t* ma = sstv_mode_get(SSTV_MODE_SCOTTIE1);
    const sstv_mode_t* mb = sstv_mode_get(SSTV_MODE_MARTIN1);
    uint32_t *ia, *ib;
    int na, nb;
    float* a = encode_card(SSTV_MODE_SCOTTIE1, &ia, &na);
    float* b = encode_card(SSTV_MODE_MARTIN1, &ib, &nb);
    int stop_line = (int)(0.4 * ma->height);
    int cut = line_start_sample(ma, stop_line);
    int gap = 3 * RATE, hdr = header_samples(), tail = 3 * RATE;
    int b_at = cut + gap;
    int total = b_at + (nb - hdr) + tail;
    float* buf = (float*)calloc((size_t)total, sizeof(float));
    memcpy(buf, a, (size_t)cut * sizeof(float));
    sstv_test_srand(0xA6C0A6Cull);
    sstv_test_add_awgn(buf + cut, gap, AGC_SIGMA);
    memcpy(buf + b_at, b + hdr, (size_t)(nb - hdr) * sizeof(float));

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    run_t r;
    run_init(&r);
    run_engine(d, buf, total, &r);
    int status = r.last_status, rows = r.last_rows;
    printf("  info: dead image -> Scottie 1 ended status %d at %.1f s (stopped %.1f s,"
           " Martin 1 from %.1f s) with %d rows; then mode %d rows %d status %d\n",
           r.first_status, r.first_end_s, (double)cut / RATE, (double)b_at / RATE,
           r.first_rows, r.last_mode, rows, status);
    check(r.first_mode == SSTV_MODE_SCOTTIE1 && r.first_status == SSTV_STATUS_ABORTED,
          "dead image: the stopped Scottie 1 image ends ABORTED");
    check(r.first_rows >= stop_line - 4 && r.first_rows <= stop_line + 24,
          "dead image: its rows are kept");
    check(r.first_end_s >= 0.0 && r.first_end_s <= (double)b_at / RATE + 8.0,
          "dead image: it lets go within 8 s of the next picture starting");
    check(r.images == 2 && status == SSTV_STATUS_DONE && r.last_mode == SSTV_MODE_MARTIN1 &&
              rows == mb->height && r.last_vis == 0,
          "dead image: the Martin 1 picture decodes to DONE");
    if (r.last_mode == SSTV_MODE_MARTIN1 && rows == mb->height && r.last_img) {
        int off = -1;
        double p = psnr_best_offset(ib, r.last_img, mb->width, mb->height, 2, rows - 2, 8, &off);
        printf("  info: dead image -> Martin 1 row offset %d, PSNR %.1f dB\n", off, p);
        check(off == 0, "dead image: Martin 1 row 0 is its first line");
        check(p >= 30.0, "dead image: Martin 1 PSNR >= 30 dB");
    }
    free(r.last_img);
    sstv_decoder_destroy(d);
    free(buf);
    free(a);
    free(b);
    free(ia);
    free(ib);
}

// ---------------------------------------------------------------------------
// 5. A header with nothing behind it: a PD 120 calibration header, half a
//    second of picture, then AGC noise. (On the air the usual source is a
//    VIS read out of speech.) PD 120 runs 126 s; the lock must be dropped
//    within 12 s.
// ---------------------------------------------------------------------------
static void test_dead_header_is_dropped(void)
{
    uint32_t* img;
    int n;
    float* tx = encode_card(SSTV_MODE_PD120, &img, &n);
    int keep = header_samples() + RATE / 2;
    int total = keep + 60 * RATE;
    float* buf = (float*)calloc((size_t)total, sizeof(float));
    memcpy(buf, tx, (size_t)keep * sizeof(float));
    sstv_test_srand(0xDEADBEEFull);
    sstv_test_add_awgn(buf + keep, total - keep, AGC_SIGMA);

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    run_t r;
    run_init(&r);
    run_engine(d, buf, total, &r);
    printf("  info: dead header -> status %d at %.1f s with %d rows; %d image(s) in 60 s\n",
           r.first_status, r.first_end_s, r.first_rows, r.images);
    check(r.first_mode == SSTV_MODE_PD120 && r.first_status == SSTV_STATUS_ABORTED,
          "dead header: the PD 120 lock ends ABORTED");
    check(r.first_end_s >= 0.0 && r.first_end_s <= (double)keep / RATE + 12.0,
          "dead header: within 12 s, not after 126");
    check(r.images == 1 && sstv_decoder_status(d) != SSTV_STATUS_IMAGE,
          "dead header: nothing else locks on the noise that follows");
    free(r.last_img);
    sstv_decoder_destroy(d);
    free(buf);
    free(tx);
    free(img);
}

// ---------------------------------------------------------------------------
// 6. Noise from top to bottom is not a received picture: a Scottie 2
//    transmission caught with no header that stops ten lines after the
//    sync comb locks onto it, AGC noise to its nominal end. A comb lock is
//    not cut short (the silence could be a fade, see 7), so the image plays
//    out — but 246 rows of noise under ten of picture must not come out
//    DONE, which is what gets a frame presented and saved as received.
// ---------------------------------------------------------------------------
static void test_noise_image_is_not_done(void)
{
    const sstv_mode_t* m = sstv_mode_get(SSTV_MODE_SCOTTIE2);
    uint32_t* img;
    int n;
    float* tx = encode_card(SSTV_MODE_SCOTTIE2, &img, &n);
    int hdr = header_samples();
    int keep = line_start_sample(m, 10) - hdr;   // ten lines, no header
    int total = (n - hdr) + 6 * RATE;
    float* buf = (float*)calloc((size_t)total, sizeof(float));
    memcpy(buf, tx + hdr, (size_t)keep * sizeof(float));
    sstv_test_srand(0x0DDBA11ull);
    sstv_test_add_awgn(buf + keep, total - keep, AGC_SIGMA);

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    run_t r;
    run_init(&r);
    run_engine(d, buf, total, &r);
    printf("  info: noise image -> %d lock(s); status %d at %.1f s with %d rows"
           " (picture stopped at %.1f s, nominal end %.1f s)\n", r.locks, r.first_status,
           r.first_end_s, r.first_rows, (double)keep / RATE, (double)(n - hdr) / RATE);
    check(r.locks == 1 && r.first_mode == SSTV_MODE_SCOTTIE2,
          "noise image: the ten lines lock Scottie 2 from the sync comb");
    check(r.first_status == SSTV_STATUS_ABORTED,
          "noise image: 10 lines of picture + 246 of noise ends ABORTED, not DONE");
    check(r.first_end_s >= (double)(n - hdr) / RATE - 3.0,
          "noise image: it was not cut short (a comb lock rides out silence)");
    free(r.last_img);
    sstv_decoder_destroy(d);
    free(buf);
    free(tx);
    free(img);
}

// ---------------------------------------------------------------------------
// 7. A fade is not the end: a Martin 1 picture that drops into the noise for
//    six seconds (13 lines) and comes back is one picture. No preemption, no
//    probation, no re-anchor may split it; the rows after the fade must
//    still line up with the source.
// ---------------------------------------------------------------------------
static void test_fade_is_one_picture(void)
{
    const sstv_mode_t* m = sstv_mode_get(SSTV_MODE_MARTIN1);
    uint32_t* img;
    int n;
    float* tx = encode_card(SSTV_MODE_MARTIN1, &img, &n);
    int fade_line = 100, fade_lines = 13;
    int f0 = line_start_sample(m, fade_line);
    int f1 = line_start_sample(m, fade_line + fade_lines);
    int total = n + 3 * RATE;
    float* buf = (float*)calloc((size_t)total, sizeof(float));
    memcpy(buf, tx, (size_t)n * sizeof(float));
    memset(buf + f0, 0, (size_t)(f1 - f0) * sizeof(float));
    sstv_test_srand(0xFADEFADEull);
    sstv_test_add_awgn(buf + f0, f1 - f0, AGC_SIGMA);

    sstv_decoder_t* d = sstv_decoder_create(RATE);
    run_t r;
    run_init(&r);
    run_engine(d, buf, total, &r);
    printf("  info: fade -> %d image(s); first: status %d, %d rows\n", r.images,
           r.first_status, r.first_rows);
    check(r.images == 1 && r.first_status == SSTV_STATUS_DONE && r.first_rows == m->height,
          "fade: one picture, DONE with every row");
    free(r.last_img);
    sstv_decoder_destroy(d);

    // The engine's reset discards the image; decode again without it to
    // compare the rows.
    d = sstv_decoder_create(RATE);
    sstv_test_push_all(d, buf, total);
    if (sstv_decoder_status(d) == SSTV_STATUS_DONE) {
        uint32_t* out = read_image(d, m);
        double before = psnr_rows(img, out, m->width, 0, fade_line - 1, 0);
        double after = psnr_rows(img, out, m->width, fade_line + fade_lines + 2, m->height, 0);
        printf("  info: fade -> PSNR before %.1f dB, after %.1f dB\n", before, after);
        check(before >= 40.0, "fade: rows before it (PSNR >= 40 dB)");
        check(after >= 40.0, "fade: rows after it still on the grid (PSNR >= 40 dB)");
        free(out);
    }
    sstv_decoder_destroy(d);
    free(buf);
    free(tx);
    free(img);
}

// ---------------------------------------------------------------------------
int main(void)
{
    printf("sstv_liveband tests:\n");

    test_noise_never_locks();
    test_speech_never_locks();

    header_lost_case(SSTV_MODE_SCOTTIE2, 32.0);
    header_lost_case(SSTV_MODE_MARTIN1, 38.0);
    header_lost_case(SSTV_MODE_ROBOT36, 24.0);
    header_lost_case(SSTV_MODE_PD120, 28.0);

    test_dead_image_is_preempted();
    test_dead_header_is_dropped();
    test_noise_image_is_not_done();
    test_fade_is_one_picture();

    if (g_failures) {
        printf("%d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("all sstv_liveband tests passed\n");
    return 0;
}
