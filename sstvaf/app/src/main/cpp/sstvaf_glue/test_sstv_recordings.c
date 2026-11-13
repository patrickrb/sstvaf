// test_sstv_recordings.c — the decoder against real off-air / on-line SSTV
// recordings (fixtures/*.wav, 12 kHz mono — the RX chain's native format).
//
// Synthetic round trips cannot catch what real audio does: sender glitches
// that shift every sync, clips that start mid-image, hot and whisper-quiet
// levels, slow-scan clock drift. Each fixture here once failed (or guards a
// property that was never tested against real audio). The assertions are
// structural, not pixel-golden — mode, lock type, rows, sync-tracking
// quality, slant, and an image-coherence score (mean correlation between
// adjacent rows' luma: a correctly timed picture scores > 0.65, a sheared
// or mis-phased one < 0.35) — so legitimate DSP tuning does not break them.
//
// Usage: test_sstv_recordings [fixtures-dir]   (default: ./fixtures)
// HOW TO RUN: sstvaf_glue/run_sstv_host_tests.ps1 / run_sstv_host_tests.sh.

#include "sstv.h"
#include "sstv_modes.h"
#include "sstv_wav.h"

#include "sstv_test_util.h"

#define CHUNK 2400  // 200 ms at 12 kHz: the recorder tap's buffer size

typedef struct {
    const char* file;
    int mode;
    int expect_done;        // 1: full image (DONE); 0: partial (clip ends mid-image)
    int expect_vis;         // expected sstv_decoder_vis_locked()
    int min_rows;
    double max_lock_s;      // mode must be locked by this time
    double min_quality;
    double max_abs_slant;   // ppm
    double min_coherence;
} fixture_t;

static const fixture_t kFixtures[] = {
    // Clean Robot 36 test card (YouTube "Slow-scan television (SSTV) - Robot
    // 36 Color"): the baseline.
    { "robot36_colorbars_yt.wav", SSTV_MODE_ROBOT36, 1, 1, 240, 3.0, 0.9, 500.0, 0.65 },
    // Peak 7 % of full scale: level must not matter.
    { "robot36_quiet_yt.wav", SSTV_MODE_ROBOT36, 1, 1, 240, 3.0, 0.9, 500.0, 0.65 },
    // ISS SpaceCam picture, mild clock slant.
    { "robot36_iss_yt.wav", SSTV_MODE_ROBOT36, 1, 1, 240, 3.0, 0.9, 500.0, 0.65 },
    // Peak 96 % with a limiter: near-clipping level.
    { "robot36_hot_yt.wav", SSTV_MODE_ROBOT36, 1, 1, 240, 3.0, 0.9, 500.0, 0.60 },
    // The sender dropped ~15 ms of audio 0.4 s into the image; every later
    // sync is off-grid. Before sync re-acquisition this decoded 3 syncs,
    // freewheeled 237 lines and produced a sheared, mis-coloured frame with
    // quality 0.51.
    { "robot36_glitch_14230_yt.wav", SSTV_MODE_ROBOT36, 1, 1, 240, 3.0, 0.9, 500.0, 0.65 },
    // Weak, fading 20 m Scottie 2 signal recorded from mid-image: no VIS in
    // the clip at all. Must lock from the sync train within a few lines.
    { "scottie2_midimage_20m_yt.wav", SSTV_MODE_SCOTTIE2, 0, 0, 80, 4.0, 0.0, 2000.0, 0.40 },
};
#define N_FIXTURES ((int)(sizeof(kFixtures) / sizeof(kFixtures[0])))

static double luma(uint32_t px)
{
    return 0.299 * ((px >> 16) & 0xFF) + 0.587 * ((px >> 8) & 0xFF) + 0.114 * (px & 0xFF);
}

// Mean normalized correlation between adjacent rows' luma over `rows` rows.
static double coherence(const uint32_t* img, int w, int rows)
{
    if (rows < 2) return 0.0;
    double acc = 0.0;
    int cnt = 0;
    for (int r = 1; r < rows; r++) {
        const uint32_t* a = img + (size_t)(r - 1) * w;
        const uint32_t* b = img + (size_t)r * w;
        double ma = 0.0, mb = 0.0;
        for (int x = 0; x < w; x++) {
            ma += luma(a[x]);
            mb += luma(b[x]);
        }
        ma /= w;
        mb /= w;
        double num = 0.0, da = 0.0, db = 0.0;
        for (int x = 0; x < w; x++) {
            double ya = luma(a[x]) - ma, yb = luma(b[x]) - mb;
            num += ya * yb;
            da += ya * ya;
            db += yb * yb;
        }
        double den = sqrt(da * db);
        acc += (den > 1e-6) ? num / den : 1.0;
        cnt++;
    }
    return acc / cnt;
}

static void run_fixture(const char* dir, const fixture_t* fx)
{
    char path[1024], label[256];
    snprintf(path, sizeof(path), "%s/%s", dir, fx->file);
    int rate = 0, n = 0;
    float* pcm = sstv_wav_read_mono(path, &rate, &n);
    snprintf(label, sizeof(label), "%s: fixture readable (12 kHz)", fx->file);
    check(pcm != 0 && rate == 12000, label);
    if (!pcm) return;

    sstv_decoder_t* d = sstv_decoder_create(rate);
    double lock_s = -1.0;
    int final_status = -1;
    for (int off = 0; off < n; off += CHUNK) {
        int c = (n - off < CHUNK) ? (n - off) : CHUNK;
        sstv_decoder_push(d, pcm + off, c);
        int s = sstv_decoder_status(d);
        if (lock_s < 0.0 && s == SSTV_STATUS_IMAGE) lock_s = (double)(off + c) / rate;
        if (s == SSTV_STATUS_DONE || s == SSTV_STATUS_ABORTED) {
            final_status = s;
            break;
        }
    }
    if (final_status < 0) final_status = sstv_decoder_status(d);

    const sstv_mode_t* m = sstv_mode_get(sstv_decoder_mode(d));
    int rows = sstv_decoder_rows_ready(d);
    double q = sstv_decoder_quality(d), slant = sstv_decoder_slant_ppm(d);
    int vis = sstv_decoder_vis_locked(d);
    double coh = -1.0;
    if (m && rows > 1) {
        uint32_t* img = (uint32_t*)calloc((size_t)m->width * m->height, 4);
        sstv_decoder_read_rows(d, 0, rows, img);
        coh = coherence(img, m->width, rows);
        free(img);
    }
    printf("  info: %s -> %s, lock %.1f s, vis %d, rows %d, quality %.2f, slant %.0f ppm,"
           " coherence %.2f, status %d\n", fx->file, m ? m->name : "(none)", lock_s, vis,
           rows, q, slant, coh, final_status);

    snprintf(label, sizeof(label), "%s: mode %s", fx->file, sstv_mode_get(fx->mode)->name);
    check(m && m->id == fx->mode, label);
    snprintf(label, sizeof(label), "%s: locked within %.1f s", fx->file, fx->max_lock_s);
    check(lock_s >= 0.0 && lock_s <= fx->max_lock_s, label);
    snprintf(label, sizeof(label), "%s: lock type (vis=%d)", fx->file, fx->expect_vis);
    check(vis == fx->expect_vis, label);
    if (fx->expect_done) {
        snprintf(label, sizeof(label), "%s: DONE with every row", fx->file);
        check(final_status == SSTV_STATUS_DONE && m && rows == m->height, label);
    } else {
        snprintf(label, sizeof(label), "%s: still decoding at clip end, >= %d rows", fx->file,
                 fx->min_rows);
        check(final_status == SSTV_STATUS_IMAGE && rows >= fx->min_rows, label);
    }
    snprintf(label, sizeof(label), "%s: quality >= %.2f", fx->file, fx->min_quality);
    check(q >= fx->min_quality, label);
    snprintf(label, sizeof(label), "%s: |slant| <= %.0f ppm", fx->file, fx->max_abs_slant);
    check(fabs(slant) <= fx->max_abs_slant, label);
    snprintf(label, sizeof(label), "%s: image coherence >= %.2f", fx->file, fx->min_coherence);
    check(coh >= fx->min_coherence, label);

    sstv_decoder_destroy(d);
    free(pcm);
}

int main(int argc, char** argv)
{
    const char* dir = (argc > 1) ? argv[1] : "fixtures";
    printf("sstv_recordings tests (%s):\n", dir);
    for (int i = 0; i < N_FIXTURES; i++) run_fixture(dir, &kFixtures[i]);
    if (g_failures) {
        printf("%d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("all sstv_recordings tests passed\n");
    return 0;
}
