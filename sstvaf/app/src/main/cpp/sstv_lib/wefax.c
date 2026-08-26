// wefax.c — clean-room WeFax / HF radiofax codec. See wefax.h and SOURCES.md.
//
// The encoder reuses the SSTV absolute-time oscillator (sstv_osc): a WeFax
// pixel maps to exactly the same 1500 + v*(800/255) Hz the SSTV scan uses, so
// sstv_osc_run_scan renders a gray line directly and the fractional
// samples-per-line never round-drift. The decoder reuses the SSTV FM
// discriminator (sstv_demod) — same 1500..2300 Hz band — and adds only the
// WeFax-specific line slicing, phasing lock, and classification.

#include "wefax.h"
#include "sstv_osc.h"
#include "sstv_demod.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// Gray thresholds for line classification. Generous around the 0 (black) /
// 255 (white) extremes so discriminator ringing at pixel edges is tolerated.
#define WEFAX_GRAY_WHITE_T 180
#define WEFAX_GRAY_BLACK_T 100

// ---------------------------------------------------------------------------
// Pure timing / mapping helpers
// ---------------------------------------------------------------------------

int wefax_pixels_per_line(int ioc)
{
    if (ioc <= 0) return 0;
    return (int)lround((double)ioc * M_PI);
}

double wefax_samples_per_line(int lpm, int sample_rate)
{
    if (lpm <= 0 || sample_rate <= 0) return 0.0;
    return (double)sample_rate * 60.0 / (double)lpm;
}

int wefax_freq_to_gray(double hz)
{
    double v = (hz - WEFAX_BLACK_HZ) / WEFAX_SHIFT_HZ * 255.0;
    long g = lround(v);
    if (g < 0) g = 0;
    if (g > 255) g = 255;
    return (int)g;
}

double wefax_gray_to_freq(int gray)
{
    if (gray < 0) gray = 0;
    if (gray > 255) gray = 255;
    return WEFAX_BLACK_HZ + (double)gray * (WEFAX_SHIFT_HZ / 255.0);
}

double wefax_start_tone_hz(int ioc)
{
    if (ioc == WEFAX_IOC_576) return 300.0;
    if (ioc == WEFAX_IOC_288) return 675.0;
    return -1.0;
}

double wefax_stop_tone_hz(void)
{
    return WEFAX_STOP_HZ;
}

int wefax_ioc_from_start_tone(double hz)
{
    if (fabs(hz - 300.0) <= 40.0) return WEFAX_IOC_576;
    if (fabs(hz - 675.0) <= 40.0) return WEFAX_IOC_288;
    return -1;
}

// ---------------------------------------------------------------------------
// Pure line geometry
// ---------------------------------------------------------------------------

void wefax_render_line(const double* freq_hz, int n, int width, uint8_t* out)
{
    if (n <= 0 || width <= 0) return;
    for (int c = 0; c < width; c++) {
        int a0 = (int)(((long long)c * n) / width);
        int a1 = (int)(((long long)(c + 1) * n) / width);
        if (a1 <= a0) a1 = a0 + 1;
        if (a1 > n) a1 = n;
        double sum = 0.0;
        for (int i = a0; i < a1; i++) sum += freq_hz[i];
        out[c] = (uint8_t)wefax_freq_to_gray(sum / (double)(a1 - a0));
    }
}

// Leading-edge column of the longest run of white pixels, treating the line
// as circular so a pulse split across the free-run boundary is counted once.
// Returns -1 (and *out_len = 0) when there is no white pixel.
static int longest_white_run(const uint8_t* g, int w, int* out_len)
{
    int anchor = -1;
    for (int i = 0; i < w; i++) {
        if (g[i] < WEFAX_GRAY_WHITE_T) { anchor = i; break; }
    }
    if (anchor < 0) {              // all white
        if (out_len) *out_len = w;
        return 0;
    }
    // Scan starting from a known black pixel, so no white run wraps the seam.
    int best_start = -1, best_len = 0, cur_start = -1, cur_len = 0;
    for (int k = 0; k < w; k++) {
        int i = (anchor + k) % w;
        if (g[i] >= WEFAX_GRAY_WHITE_T) {
            if (cur_len == 0) cur_start = i;
            cur_len++;
            if (cur_len > best_len) { best_len = cur_len; best_start = cur_start; }
        } else {
            cur_len = 0;
        }
    }
    if (out_len) *out_len = best_len;
    return best_start;
}

wefax_line_class_t wefax_classify_line(const uint8_t* gray, int width,
                                       int* pulse_col)
{
    int white = 0, black = 0;
    for (int i = 0; i < width; i++) {
        if (gray[i] >= WEFAX_GRAY_WHITE_T) white++;
        else if (gray[i] <= WEFAX_GRAY_BLACK_T) black++;
    }
    double wf = (double)white / (double)width;
    double bf = (double)black / (double)width;

    // Phasing: predominantly black with a small white pulse.
    if (bf >= 0.75 && wf >= 0.005 && wf <= 0.30) {
        int len = 0;
        int start = longest_white_run(gray, width, &len);
        if (pulse_col) *pulse_col = (start < 0) ? 0 : start;
        return WEFAX_LINE_PHASING;
    }
    if (pulse_col) *pulse_col = -1;
    return WEFAX_LINE_PICTURE;
}

// ---------------------------------------------------------------------------
// Encoder
// ---------------------------------------------------------------------------

static double wefax_total_us(int lpm, int height, int start_sec,
                             int phasing_lines, int stop_sec)
{
    double line_us = 60.0e6 / (double)lpm;
    return (double)start_sec * 1.0e6
         + (double)phasing_lines * line_us
         + (double)height * line_us
         + (double)stop_sec * 1.0e6;
}

int wefax_encode_num_samples(int lpm, int ioc, int height, int sample_rate,
                             int start_sec, int phasing_lines, int stop_sec)
{
    if (lpm <= 0 || ioc <= 0 || height <= 0) return WEFAX_ERR_BAD_ARGS;
    if (sample_rate < 8000 || sample_rate > 192000) return WEFAX_ERR_BAD_ARGS;
    if (start_sec < 0 || phasing_lines < 0 || stop_sec < 0) return WEFAX_ERR_BAD_ARGS;

    double total_us = wefax_total_us(lpm, height, start_sec, phasing_lines, stop_sec);
    double x = total_us * (double)sample_rate / 1.0e6;
    // The oscillator emits sample `pos` while pos*1e6/fs < total_us, i.e. the
    // count is ceil(x) (or exactly x when x is integral); the -1e-6 keeps a
    // float that landed just above an integer from over-counting.
    long n = (long)ceil(x - 1e-6);
    return (int)n;
}

// Emit an APT keyed tone (black/white alternation at key_hz) until end_us.
static int emit_keyed(sstv_osc_t* o, double key_hz, double end_us)
{
    double cyc_us = 1.0e6 / key_hz;
    while (sstv_osc_time_us(o) < end_us) {
        double ph = fmod(sstv_osc_time_us(o), cyc_us);
        double f = (ph < cyc_us * 0.5) ? WEFAX_WHITE_HZ : WEFAX_BLACK_HZ;
        int rc = sstv_osc_emit(o, f);
        if (rc) return rc;
    }
    return 0;
}

int wefax_encode(int lpm, int ioc, const uint8_t* gray, int width, int height,
                 int sample_rate, float amplitude, int start_sec,
                 int phasing_lines, int stop_sec, float* out, int out_capacity)
{
    if (!gray || !out) return WEFAX_ERR_BAD_ARGS;
    if (width != wefax_pixels_per_line(ioc)) return WEFAX_ERR_BAD_ARGS;

    int need = wefax_encode_num_samples(lpm, ioc, height, sample_rate,
                                        start_sec, phasing_lines, stop_sec);
    if (need < 0) return need;
    if (out_capacity < need) return WEFAX_ERR_CAPACITY;

    // Phasing line: white pulse at the left margin, black for the remainder.
    uint8_t* phase_row = (uint8_t*)malloc((size_t)width);
    if (!phase_row) return WEFAX_ERR_NOMEM;
    int pulse_px = (int)lround((double)width * WEFAX_PHASE_PULSE_FRAC);
    for (int x = 0; x < width; x++) phase_row[x] = (x < pulse_px) ? 255 : 0;

    sstv_osc_t osc;
    sstv_osc_init(&osc, sample_rate, amplitude, out, out_capacity);

    double line_us = 60.0e6 / (double)lpm;
    double t_end = 0.0;
    int rc = 0;

    if (start_sec > 0) {
        double key = wefax_start_tone_hz(ioc);
        if (key <= 0.0) key = 300.0;   // fall back to the IOC-576 rate
        t_end += (double)start_sec * 1.0e6;
        rc |= emit_keyed(&osc, key, t_end);
    }

    for (int i = 0; i < phasing_lines && rc == 0; i++) {
        double seg_start = t_end;
        t_end += line_us;
        rc |= sstv_osc_run_scan(&osc, phase_row, width, seg_start, t_end);
    }

    for (int y = 0; y < height && rc == 0; y++) {
        double seg_start = t_end;
        t_end += line_us;
        rc |= sstv_osc_run_scan(&osc, gray + (size_t)y * width, width,
                                seg_start, t_end);
    }

    if (stop_sec > 0 && rc == 0) {
        t_end += (double)stop_sec * 1.0e6;
        rc |= emit_keyed(&osc, WEFAX_STOP_HZ, t_end);
    }

    free(phase_row);
    if (rc) return rc;
    return osc.pos;
}

// ---------------------------------------------------------------------------
// Decoder
// ---------------------------------------------------------------------------

// Lock onto the line phase after this many phasing lines have been seen (1 is
// enough; the pulse detector is robust to a boundary-split pulse).
#define WEFAX_LOCK_LINES 1

struct wefax_decoder {
    int sample_rate, lpm, ioc, width;
    double spl;              // samples per line (double)
    sstv_demod_t demod;

    double* linebuf;         // freq (Hz) samples of the current line
    int linecap;
    int linelen;
    int line_samples;        // target length of the current line
    double carry;            // Bresenham fractional-line accumulator

    long sample_index;       // total demod outputs seen
    int resync_skip;         // samples to drop for one-time phase realignment

    int status;
    int seen_phasing;
    int locked;

    uint8_t* rowtmp;         // width-byte scratch for the current line
    uint8_t* rows;           // width * row_cap gray bytes
    int row_cap, n_rows;
};

static void start_new_line(wefax_decoder_t* d)
{
    double v = d->spl + d->carry;
    int ls = (int)v;
    if (ls < 1) ls = 1;
    d->carry = v - (double)ls;
    d->line_samples = ls;
    d->linelen = 0;
}

wefax_decoder_t* wefax_decoder_create(int sample_rate, int lpm, int ioc)
{
    if (lpm <= 0 || ioc <= 0) return NULL;
    int width = wefax_pixels_per_line(ioc);
    if (width <= 0) return NULL;
    double spl = wefax_samples_per_line(lpm, sample_rate);
    if (spl < 2.0) return NULL;

    wefax_decoder_t* d = (wefax_decoder_t*)calloc(1, sizeof(*d));
    if (!d) return NULL;
    d->sample_rate = sample_rate;
    d->lpm = lpm;
    d->ioc = ioc;
    d->width = width;
    d->spl = spl;
    d->status = WEFAX_STATUS_IDLE;

    if (sstv_demod_init(&d->demod, sample_rate) != 0) {
        free(d);
        return NULL;
    }

    d->linecap = (int)ceil(spl) + 4;
    d->linebuf = (double*)malloc(sizeof(double) * (size_t)d->linecap);
    d->rowtmp = (uint8_t*)malloc((size_t)width);
    d->row_cap = 64;
    d->rows = (uint8_t*)malloc((size_t)d->row_cap * (size_t)width);
    if (!d->linebuf || !d->rowtmp || !d->rows) {
        wefax_decoder_destroy(d);
        return NULL;
    }
    start_new_line(d);
    return d;
}

static void emit_row(wefax_decoder_t* d, const uint8_t* gray)
{
    if (d->n_rows >= d->row_cap) {
        int nc = d->row_cap * 2;
        uint8_t* nr = (uint8_t*)realloc(d->rows, (size_t)nc * (size_t)d->width);
        if (!nr) return;   // out of memory: drop the row rather than crash
        d->rows = nr;
        d->row_cap = nc;
    }
    memcpy(d->rows + (size_t)d->n_rows * d->width, gray, (size_t)d->width);
    d->n_rows++;
}

static void complete_line(wefax_decoder_t* d)
{
    uint8_t* gray = d->rowtmp;
    int w = d->width;
    wefax_render_line(d->linebuf, d->line_samples, w, gray);

    int pulse_col = -1;
    wefax_line_class_t cls = wefax_classify_line(gray, w, &pulse_col);

    if (!d->locked) {
        if (cls == WEFAX_LINE_PHASING) {
            d->seen_phasing++;
            if (d->status == WEFAX_STATUS_IDLE) d->status = WEFAX_STATUS_PHASING;
            if (d->seen_phasing >= WEFAX_LOCK_LINES) {
                // Realign so the pulse leading edge becomes column 0: drop the
                // in-line sample offset of the pulse once, then resume slicing.
                long off = (long)((long long)pulse_col * d->line_samples / w);
                if (off < 0) off = 0;
                if (off >= d->line_samples) off = 0;
                d->locked = 1;
                d->status = WEFAX_STATUS_IMAGE;
                d->resync_skip = (int)off;
                if (d->resync_skip == 0) start_new_line(d);
                return;   // start_new_line handled by the resync path
            }
        }
        // Pre-lock picture / start-tone lines are ignored.
        start_new_line(d);
        return;
    }

    // Locked: emit picture rows; skip trailing phasing lines.
    if (cls == WEFAX_LINE_PICTURE) emit_row(d, gray);
    start_new_line(d);
}

static void process_freq(wefax_decoder_t* d, double f)
{
    if (d->status == WEFAX_STATUS_DONE) return;

    if (d->resync_skip > 0) {
        d->resync_skip--;
        if (d->resync_skip == 0) start_new_line(d);
        d->sample_index++;
        return;
    }

    if (d->linelen < d->linecap) d->linebuf[d->linelen++] = f;
    d->sample_index++;

    if (d->linelen >= d->line_samples) complete_line(d);
}

void wefax_decoder_push(wefax_decoder_t* d, const float* samples, int n)
{
    if (!d || !samples || n <= 0) return;
    if (d->status == WEFAX_STATUS_DONE) return;
    for (int i = 0; i < n; i++) {
        double f = sstv_demod_process(&d->demod, (double)samples[i], NULL);
        process_freq(d, f);
    }
}

void wefax_decoder_finish(wefax_decoder_t* d)
{
    if (!d) return;
    // Flush a partial final line so a stream that ends mid-line (common with
    // live audio capture) doesn't drop its last row. Only once locked and only
    // when most of the line is present; pad the tail by repeating the last
    // sample so the renderer sees a full line_samples-length buffer, then run
    // the normal completion path. line_samples <= ceil(spl) < linecap, so the
    // pad never overruns linebuf.
    if (d->status != WEFAX_STATUS_DONE && d->locked && d->resync_skip == 0 &&
        d->linelen > 0 && d->linelen < d->line_samples &&
        d->linelen >= d->line_samples / 2 && d->line_samples <= d->linecap) {
        double last = d->linebuf[d->linelen - 1];
        while (d->linelen < d->line_samples) d->linebuf[d->linelen++] = last;
        complete_line(d);
    }
    d->status = WEFAX_STATUS_DONE;
}

int wefax_decoder_status(const wefax_decoder_t* d)
{
    return d ? d->status : WEFAX_STATUS_IDLE;
}

int wefax_decoder_width(const wefax_decoder_t* d)
{
    return d ? d->width : 0;
}

int wefax_decoder_rows_ready(const wefax_decoder_t* d)
{
    return d ? d->n_rows : 0;
}

int wefax_decoder_read_rows(const wefax_decoder_t* d, int first_row,
                            int n_rows, uint8_t* gray_out)
{
    if (!d || !gray_out || first_row < 0 || n_rows < 0) return WEFAX_ERR_BAD_ARGS;
    int avail = d->n_rows - first_row;
    if (avail <= 0) return 0;
    int cnt = n_rows < avail ? n_rows : avail;
    memcpy(gray_out, d->rows + (size_t)first_row * d->width,
           (size_t)cnt * (size_t)d->width);
    return cnt;
}

void wefax_decoder_reset(wefax_decoder_t* d)
{
    if (!d) return;
    sstv_demod_free(&d->demod);
    if (sstv_demod_init(&d->demod, d->sample_rate) != 0) {
        // Re-init failed (e.g. OOM). sstv_demod_init zeroed the demod on the
        // way out, so leave the decoder in a terminal state — push() bails on
        // DONE before touching the demod, and destroy() frees NULLs safely.
        d->status = WEFAX_STATUS_DONE;
        return;
    }
    d->linelen = 0;
    d->carry = 0.0;
    d->sample_index = 0;
    d->resync_skip = 0;
    d->status = WEFAX_STATUS_IDLE;
    d->seen_phasing = 0;
    d->locked = 0;
    d->n_rows = 0;
    start_new_line(d);
}

void wefax_decoder_destroy(wefax_decoder_t* d)
{
    if (!d) return;
    sstv_demod_free(&d->demod);
    free(d->linebuf);
    free(d->rowtmp);
    free(d->rows);
    free(d);
}
