// sstv_test_util.h — shared helpers for the sstv_lib host tests.
//
// Follows the ft8af_glue test style: each test_*.c has its own main(), a
// check() helper, and exits nonzero on any failure. This header is included
// exactly once per test binary, so it defines the counter itself.

#ifndef SSTVAF_GLUE_SSTV_TEST_UTIL_H
#define SSTVAF_GLUE_SSTV_TEST_UTIL_H

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static int g_failures = 0;

static void check(int cond, const char* label)
{
    if (cond) {
        printf("  ok:   %s\n", label);
    } else {
        printf("  FAIL: %s\n", label);
        ++g_failures;
    }
}

static void check_near(double got, double want, double tol, const char* label)
{
    if (fabs(got - want) <= tol) {
        printf("  ok:   %s (got %.3f, want %.3f)\n", label, got, want);
    } else {
        printf("  FAIL: %s (got %.3f, want %.3f, tol %.3f)\n", label, got, want, tol);
        ++g_failures;
    }
}

// ---------------------------------------------------------------------------
// Deterministic test card: 8 color bars on the top half, RGB gradients on
// the bottom half. Same card everywhere (golden vectors depend on it).
// ---------------------------------------------------------------------------
static uint32_t sstv_testcard_pixel(int x, int y, int w, int h)
{
    if (y < h / 2) {
        static const uint32_t bars[8] = {
            0xFFFFFFFFu, 0xFFFFFF00u, 0xFF00FFFFu, 0xFF00FF00u,
            0xFFFF00FFu, 0xFFFF0000u, 0xFF0000FFu, 0xFF000000u,
        };
        int i = (x * 8) / w;
        if (i > 7) i = 7;
        return bars[i];
    }
    uint8_t r = (uint8_t)((x * 255) / (w - 1));
    uint8_t g = (uint8_t)(255 - r);
    uint8_t b = (uint8_t)((y * 255) / (h - 1));
    return 0xFF000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | b;
}

static uint32_t* sstv_testcard_alloc(int w, int h)
{
    uint32_t* img = (uint32_t*)malloc((size_t)w * h * sizeof(uint32_t));
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) img[(size_t)y * w + x] = sstv_testcard_pixel(x, y, w, h);
    return img;
}

// ---------------------------------------------------------------------------
// Per-channel PSNR (dB). channel: 0=R, 1=G, 2=B. Returns 99.0 for exact.
// ---------------------------------------------------------------------------
static double sstv_test_psnr(const uint32_t* a, const uint32_t* b, int npx,
                             int channel)
{
    int shift = (channel == 0) ? 16 : (channel == 1) ? 8 : 0;
    double se = 0.0;
    for (int i = 0; i < npx; i++) {
        int va = (int)((a[i] >> shift) & 0xFF);
        int vb = (int)((b[i] >> shift) & 0xFF);
        double e = (double)(va - vb);
        se += e * e;
    }
    double mse = se / (double)npx;
    if (mse <= 1e-12) return 99.0;
    return 10.0 * log10(255.0 * 255.0 / mse);
}

static double sstv_test_min_psnr(const uint32_t* a, const uint32_t* b, int npx)
{
    double p = 1e9;
    for (int c = 0; c < 3; c++) {
        double v = sstv_test_psnr(a, b, npx, c);
        if (v < p) p = v;
    }
    return p;
}

// ---------------------------------------------------------------------------
// Deterministic RNG (xorshift) + Box-Muller gaussian, for AWGN tests.
// ---------------------------------------------------------------------------
static uint64_t g_rng_state = 0x243F6A8885A308D3ull;

static void sstv_test_srand(uint64_t seed)
{
    g_rng_state = seed ? seed : 1;
}

static double sstv_test_rand01(void)
{
    uint64_t x = g_rng_state;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    g_rng_state = x;
    return (double)(x >> 11) / 9007199254740992.0;  // [0,1)
}

static double sstv_test_gauss(void)
{
    double u1 = sstv_test_rand01(), u2 = sstv_test_rand01();
    if (u1 < 1e-300) u1 = 1e-300;
    return sqrt(-2.0 * log(u1)) * cos(2.0 * M_PI * u2);
}

static void sstv_test_add_awgn(float* buf, int n, double sigma)
{
    for (int i = 0; i < n; i++) buf[i] += (float)(sigma * sstv_test_gauss());
}

// ---------------------------------------------------------------------------
// Simple test-side tone generator (independent of sstv_osc, so tests can
// synthesize headers with tuning offsets, corrupted parity, etc.).
// ---------------------------------------------------------------------------
typedef struct {
    double phase;
    int sr;
    float* out;
    int pos, cap;
} sstv_test_osc_t;

static void tosc_init(sstv_test_osc_t* o, int sr, float* out, int cap)
{
    o->phase = 0.0;
    o->sr = sr;
    o->out = out;
    o->pos = 0;
    o->cap = cap;
}

static void tosc_tone_ms(sstv_test_osc_t* o, double freq, double ms, double amp)
{
    int ns = (int)lround(ms * 1e-3 * o->sr);
    for (int i = 0; i < ns && o->pos < o->cap; i++) {
        o->out[o->pos++] = (float)(amp * sin(o->phase));
        o->phase += 2.0 * M_PI * freq / o->sr;
        if (o->phase > 2.0 * M_PI) o->phase -= 2.0 * M_PI;
    }
}

// Synthesize a full calibration header + VIS for `code`, all frequencies
// shifted by offset_hz. parity_flip corrupts the parity bit; leader_ms
// overrides the 300 ms leaders (for the truncated-leader test).
static void sstv_test_gen_header(sstv_test_osc_t* o, int code, double offset_hz,
                                 int parity_flip, double leader_ms, double amp)
{
    tosc_tone_ms(o, 1900 + offset_hz, leader_ms, amp);
    tosc_tone_ms(o, 1200 + offset_hz, 10, amp);
    tosc_tone_ms(o, 1900 + offset_hz, leader_ms, amp);
    tosc_tone_ms(o, 1200 + offset_hz, 30, amp);  // start bit
    int ones = 0;
    for (int i = 0; i < 7; i++) {
        int bit = (code >> i) & 1;
        ones += bit;
        tosc_tone_ms(o, (bit ? 1100 : 1300) + offset_hz, 30, amp);
    }
    int parity = (ones & 1) ^ (parity_flip ? 1 : 0);
    tosc_tone_ms(o, (parity ? 1100 : 1300) + offset_hz, 30, amp);
    tosc_tone_ms(o, 1200 + offset_hz, 30, amp);  // stop bit
}

// ---------------------------------------------------------------------------
// Linear resampler for slant simulation. ppm > 0 stretches the signal
// (receiver clock slower than nominal => lines arrive slower). Returns the
// output length.
// ---------------------------------------------------------------------------
static int sstv_test_resample_ppm(const float* in, int n, double ppm,
                                  float* out, int cap)
{
    double stretch = 1.0 + ppm * 1e-6;
    int m = 0;
    for (;; m++) {
        double u = (double)m / stretch;
        int i = (int)u;
        if (i >= n - 1 || m >= cap) break;
        double fr = u - (double)i;
        out[m] = (float)((1.0 - fr) * in[i] + fr * in[i + 1]);
    }
    return m;
}

// ---------------------------------------------------------------------------
// Push a buffer through a decoder in chunks (as a live audio path would).
// ---------------------------------------------------------------------------
struct sstv_decoder;
void sstv_decoder_push(struct sstv_decoder* d, const float* samples, int n);

static void sstv_test_push_all(struct sstv_decoder* d, const float* buf, int n)
{
    const int chunk = 2048;
    for (int off = 0; off < n; off += chunk) {
        int c = (n - off < chunk) ? (n - off) : chunk;
        sstv_decoder_push(d, buf + off, c);
    }
}

// ---------------------------------------------------------------------------
// FNV-1a 64 over a byte buffer (for golden checksums).
// ---------------------------------------------------------------------------
static uint64_t sstv_test_fnv1a64(const void* data, size_t len)
{
    const uint8_t* p = (const uint8_t*)data;
    uint64_t h = 0xCBF29CE484222325ull;
    for (size_t i = 0; i < len; i++) {
        h ^= p[i];
        h *= 0x100000001B3ull;
    }
    return h;
}

// int16 quantization used by the golden checksums.
static void sstv_test_quantize_i16(const float* in, int n, int16_t* out)
{
    for (int i = 0; i < n; i++) {
        double v = in[i];
        if (v > 1.0) v = 1.0;
        if (v < -1.0) v = -1.0;
        out[i] = (int16_t)lrint(v * 32767.0);
    }
}

// ---------------------------------------------------------------------------
// Instantaneous-frequency estimate of a (locally constant) tone: count whole
// cycles between the first and last interpolated upward zero crossing.
// Sub-Hz accurate for a clean sine. Window [n0, n1) must sit fully inside a
// constant-frequency stretch.
// ---------------------------------------------------------------------------
static double sstv_test_measure_freq(const float* buf, int n0, int n1, int sr)
{
    double first = -1.0, last = -1.0;
    int cycles = 0;
    for (int i = n0; i < n1 - 1; i++) {
        if (buf[i] <= 0.0f && buf[i + 1] > 0.0f) {
            double fr = (double)(-buf[i]) / (double)(buf[i + 1] - buf[i]);
            double t = (double)i + fr;
            if (first < 0.0) {
                first = t;
            } else {
                last = t;
                cycles++;
            }
        }
    }
    if (cycles < 1 || last <= first) return 0.0;
    return (double)cycles * (double)sr / (last - first);
}

#endif // SSTVAF_GLUE_SSTV_TEST_UTIL_H
