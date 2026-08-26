// sstvaf_cli.c — implementation of the SSTVAF desktop command-line tool.
// See sstvaf_cli.h for the surface and rationale.

#include "sstvaf_cli.h"

#include <limits.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sstv.h"
#include "sstv_modes.h"

// ---------------------------------------------------------------------------
// Little-endian byte helpers (WAV is little-endian; write bytes explicitly so
// the tool is correct regardless of host endianness).
// ---------------------------------------------------------------------------
static void put_u16le(unsigned char* p, unsigned v)
{
    p[0] = (unsigned char)(v & 0xFF);
    p[1] = (unsigned char)((v >> 8) & 0xFF);
}

static void put_u32le(unsigned char* p, unsigned long v)
{
    p[0] = (unsigned char)(v & 0xFF);
    p[1] = (unsigned char)((v >> 8) & 0xFF);
    p[2] = (unsigned char)((v >> 16) & 0xFF);
    p[3] = (unsigned char)((v >> 24) & 0xFF);
}

static unsigned get_u16le(const unsigned char* p)
{
    return (unsigned)p[0] | ((unsigned)p[1] << 8);
}

static unsigned long get_u32le(const unsigned char* p)
{
    return (unsigned long)p[0] | ((unsigned long)p[1] << 8) |
           ((unsigned long)p[2] << 16) | ((unsigned long)p[3] << 24);
}

// ---------------------------------------------------------------------------
// Mode name resolution.
// ---------------------------------------------------------------------------

// Lowercase and drop spaces so display names and compact aliases both match.
static void normalize_mode(const char* in, char* out, size_t cap)
{
    size_t j = 0;
    for (size_t i = 0; in[i] && j + 1 < cap; i++) {
        char c = in[i];
        if (c == ' ' || c == '\t' || c == '_' || c == '-') continue;
        if (c >= 'A' && c <= 'Z') c = (char)(c - 'A' + 'a');
        out[j++] = c;
    }
    out[j] = '\0';
}

int sstvaf_cli_mode_by_name(const char* name)
{
    if (!name || !*name) return -1;
    char want[64];
    normalize_mode(name, want, sizeof(want));
    for (int id = 0; id < SSTV_NUM_MODES; id++) {
        const sstv_mode_t* m = sstv_mode_get(id);
        if (!m) continue;
        char have[64];
        normalize_mode(m->name, have, sizeof(have));
        if (strcmp(have, want) == 0) return id;
    }
    return -1;
}

// ---------------------------------------------------------------------------
// WAV serialize / parse.
// ---------------------------------------------------------------------------

unsigned char* sstvaf_cli_wav_serialize(const float* samples, int n,
                                        int sample_rate, size_t* len_out)
{
    if (!samples || n < 0 || sample_rate <= 0 || !len_out) return NULL;

    const unsigned channels = 1, bits = 16;
    const unsigned bytes_per_sample = (bits / 8) * channels;
    // Guard the size math in size_t: n frames * bytes_per_sample must fit, and
    // the RIFF/data 32-bit length fields (36 + data_bytes, data_bytes) must be
    // representable. On a 32-bit host this is where a large n would wrap.
    if ((size_t)n > (SIZE_MAX - 44) / bytes_per_sample) return NULL;
    size_t data_bytes = (size_t)n * bytes_per_sample;
    if (data_bytes > 0xFFFFFFFFul - 36) return NULL;  // RIFF length is u32
    size_t total = 44 + data_bytes;
    unsigned char* buf = (unsigned char*)malloc(total ? total : 1);
    if (!buf) return NULL;

    unsigned long byte_rate = (unsigned long)sample_rate * channels * (bits / 8);
    unsigned block_align = channels * (bits / 8);

    memcpy(buf, "RIFF", 4);
    put_u32le(buf + 4, 36 + data_bytes);
    memcpy(buf + 8, "WAVE", 4);
    memcpy(buf + 12, "fmt ", 4);
    put_u32le(buf + 16, 16);            // PCM fmt chunk size
    put_u16le(buf + 20, 1);             // PCM
    put_u16le(buf + 22, channels);
    put_u32le(buf + 24, (unsigned long)sample_rate);
    put_u32le(buf + 28, byte_rate);
    put_u16le(buf + 32, block_align);
    put_u16le(buf + 34, bits);
    memcpy(buf + 36, "data", 4);
    put_u32le(buf + 40, data_bytes);

    for (int i = 0; i < n; i++) {
        double v = samples[i];
        if (v > 1.0) v = 1.0;
        if (v < -1.0) v = -1.0;
        int s = (int)lrint(v * 32767.0);
        if (s > 32767) s = 32767;
        if (s < -32768) s = -32768;
        put_u16le(buf + 44 + (size_t)i * 2, (unsigned)(s & 0xFFFF));
    }

    *len_out = total;
    return buf;
}

// Walk the RIFF chunk list to find `data` and the format's channel count and
// sample rate. Returns 0 on success.
static int find_wav_chunks(const unsigned char* d, size_t len,
                           unsigned* channels, int* rate, unsigned* bits,
                           size_t* data_off, size_t* data_len)
{
    if (len < 12 || memcmp(d, "RIFF", 4) != 0 || memcmp(d + 8, "WAVE", 4) != 0)
        return -1;
    int have_fmt = 0, have_data = 0;
    size_t off = 12;
    while (off + 8 <= len) {
        const unsigned char* id = d + off;
        unsigned long csize = get_u32le(d + off + 4);
        size_t body = off + 8;
        if (body > len) break;
        size_t avail = len - body;
        if (csize > avail) csize = (unsigned long)avail;  // tolerate a short tail
        if (memcmp(id, "fmt ", 4) == 0 && csize >= 16) {
            unsigned fmt = get_u16le(d + body);
            *channels = get_u16le(d + body + 2);
            *rate = (int)get_u32le(d + body + 4);
            *bits = get_u16le(d + body + 14);
            if (fmt != 1) return -1;  // PCM only
            have_fmt = 1;
        } else if (memcmp(id, "data", 4) == 0) {
            *data_off = body;
            *data_len = csize;
            have_data = 1;
        }
        off = body + csize + (csize & 1);  // chunks are word-aligned
    }
    return (have_fmt && have_data) ? 0 : -1;
}

float* sstvaf_cli_wav_parse(const unsigned char* data, size_t len,
                            int* n_out, int* rate_out)
{
    if (!data || !n_out || !rate_out) return NULL;
    unsigned channels = 0, bits = 0;
    int rate = 0;
    size_t data_off = 0, data_len = 0;
    if (find_wav_chunks(data, len, &channels, &rate, &bits, &data_off, &data_len))
        return NULL;
    if (bits != 16 || channels < 1 || rate <= 0) return NULL;

    size_t frame_bytes = (size_t)channels * 2;
    size_t frame_count = frame_bytes ? (data_len / frame_bytes) : 0;
    // n_out is an int; refuse inputs whose frame count can't be represented
    // rather than truncating to a negative/huge value.
    if (frame_count > (size_t)INT_MAX) return NULL;
    int frames = (int)frame_count;
    float* out = (float*)malloc((size_t)(frames ? frames : 1) * sizeof(float));
    if (!out) return NULL;

    for (int i = 0; i < frames; i++) {
        long acc = 0;
        const unsigned char* p = data + data_off + (size_t)i * frame_bytes;
        for (unsigned c = 0; c < channels; c++) {
            int s = (int)(short)get_u16le(p + c * 2);  // sign-extend int16
            acc += s;
        }
        out[i] = (float)((double)acc / channels / 32768.0);
    }
    *n_out = frames;
    *rate_out = rate;
    return out;
}

// ---------------------------------------------------------------------------
// PPM serialize / parse.
// ---------------------------------------------------------------------------

unsigned char* sstvaf_cli_ppm_serialize(const uint32_t* argb, int w, int h,
                                        size_t* len_out)
{
    if (!argb || w <= 0 || h <= 0 || !len_out) return NULL;
    char header[64];
    int hn = snprintf(header, sizeof(header), "P6\n%d %d\n255\n", w, h);
    // Treat truncation (hn >= sizeof(header)) as an error so the buffer stays
    // well-formed.
    if (hn <= 0 || (size_t)hn >= sizeof(header)) return NULL;
    // Guard w*h*3 against size_t overflow before allocating.
    if ((size_t)w > SIZE_MAX / (size_t)h ||
        (size_t)w * (size_t)h > (SIZE_MAX - (size_t)hn) / 3)
        return NULL;
    size_t body = (size_t)w * h * 3;
    size_t total = (size_t)hn + body;
    unsigned char* buf = (unsigned char*)malloc(total);
    if (!buf) return NULL;
    memcpy(buf, header, (size_t)hn);
    unsigned char* p = buf + hn;
    size_t npx = (size_t)w * h;
    for (size_t i = 0; i < npx; i++) {
        uint32_t v = argb[i];
        *p++ = (unsigned char)((v >> 16) & 0xFF);  // R
        *p++ = (unsigned char)((v >> 8) & 0xFF);   // G
        *p++ = (unsigned char)(v & 0xFF);          // B
    }
    *len_out = total;
    return buf;
}

// Skip whitespace and '#'-to-end-of-line comments; return the new offset.
static size_t ppm_skip_ws(const unsigned char* d, size_t len, size_t off)
{
    for (;;) {
        while (off < len && (d[off] == ' ' || d[off] == '\t' ||
                             d[off] == '\n' || d[off] == '\r'))
            off++;
        if (off < len && d[off] == '#') {
            while (off < len && d[off] != '\n') off++;
            continue;
        }
        break;
    }
    return off;
}

// Read a non-negative decimal integer, advancing *off. Returns -1 if none.
static long ppm_read_int(const unsigned char* d, size_t len, size_t* off)
{
    size_t o = ppm_skip_ws(d, len, *off);
    if (o >= len || d[o] < '0' || d[o] > '9') return -1;
    long v = 0;
    while (o < len && d[o] >= '0' && d[o] <= '9') {
        v = v * 10 + (d[o] - '0');
        o++;
    }
    *off = o;
    return v;
}

uint32_t* sstvaf_cli_ppm_parse(const unsigned char* data, size_t len,
                               int* w_out, int* h_out)
{
    if (!data || len < 2 || data[0] != 'P' || data[1] != '6') return NULL;
    size_t off = 2;
    long w = ppm_read_int(data, len, &off);
    long h = ppm_read_int(data, len, &off);
    long maxv = ppm_read_int(data, len, &off);
    if (w <= 0 || h <= 0 || maxv != 255) return NULL;
    // Exactly one whitespace byte separates the header from binary data.
    // Verify it actually is whitespace so a malformed header doesn't cause the
    // first pixel byte to be consumed as the separator.
    if (off >= len) return NULL;
    unsigned char sep = data[off];
    if (sep != ' ' && sep != '\t' && sep != '\n' && sep != '\r') return NULL;
    off++;
    // Compute the pixel count once, guarding w*h and the *3 / *4 scalings
    // against size_t overflow before any allocation.
    if ((size_t)w > SIZE_MAX / (size_t)h) return NULL;
    size_t npx = (size_t)w * (size_t)h;
    if (npx > SIZE_MAX / 4) return NULL;  // covers *3 (body) and *4 (uint32_t)
    size_t need = npx * 3;
    if (len - off < need) return NULL;

    uint32_t* img = (uint32_t*)malloc(npx * sizeof(uint32_t));
    if (!img) return NULL;
    const unsigned char* p = data + off;
    for (size_t i = 0; i < npx; i++) {
        uint32_t r = p[i * 3 + 0], g = p[i * 3 + 1], b = p[i * 3 + 2];
        img[i] = 0xFF000000u | (r << 16) | (g << 8) | b;
    }
    *w_out = (int)w;
    *h_out = (int)h;
    return img;
}

// ---------------------------------------------------------------------------
// Whole-file helpers.
// ---------------------------------------------------------------------------

unsigned char* sstvaf_cli_read_file(const char* path, size_t* len_out)
{
    if (!path || !len_out) return NULL;
    FILE* f = fopen(path, "rb");
    if (!f) return NULL;
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return NULL; }
    long sz = ftell(f);
    if (sz < 0) { fclose(f); return NULL; }
    rewind(f);
    unsigned char* buf = (unsigned char*)malloc((size_t)(sz ? sz : 1));
    if (!buf) { fclose(f); return NULL; }
    size_t got = fread(buf, 1, (size_t)sz, f);
    fclose(f);
    if (got != (size_t)sz) { free(buf); return NULL; }
    *len_out = got;
    return buf;
}

int sstvaf_cli_write_file(const char* path, const unsigned char* data, size_t len)
{
    if (!path || (!data && len)) return -1;
    FILE* f = fopen(path, "wb");
    if (!f) return -1;
    size_t put = fwrite(data, 1, len, f);
    int rc = (fclose(f) == 0 && put == len) ? 0 : -1;
    return rc;
}

// ---------------------------------------------------------------------------
// Sub-commands.
// ---------------------------------------------------------------------------

static void print_usage(FILE* f)
{
    fprintf(f,
        "SSTVAF desktop CLI — encode/decode SSTV using the SSTVAF codec.\n\n"
        "Usage:\n"
        "  sstvaf encode <mode> <in.ppm> <out.wav> [--rate HZ] [--amp A]\n"
        "  sstvaf decode <in.wav> <out.ppm>\n"
        "  sstvaf list\n"
        "  sstvaf help\n\n"
        "Modes (name or compact alias, e.g. \"Scottie 1\" or scottie1):\n");
    for (int id = 0; id < SSTV_NUM_MODES; id++) {
        const sstv_mode_t* m = sstv_mode_get(id);
        if (m) fprintf(f, "  %-10s %dx%d (VIS %d)\n", m->name, m->width,
                       m->height, m->vis_code);
    }
    fprintf(f,
        "\nImages are binary PPM (P6); audio is 16-bit mono PCM WAV.\n"
        "Default rate %d Hz, amplitude %.2f.\n",
        SSTVAF_CLI_DEFAULT_RATE, (double)SSTVAF_CLI_DEFAULT_AMP);
}

static int cmd_list(void)
{
    for (int id = 0; id < SSTV_NUM_MODES; id++) {
        const sstv_mode_t* m = sstv_mode_get(id);
        if (m)
            printf("%d\t%s\t%dx%d\tVIS %d\n", m->id, m->name, m->width,
                   m->height, m->vis_code);
    }
    return 0;
}

static int cmd_encode(int argc, char** argv)
{
    if (argc < 5) {
        fprintf(stderr, "encode: need <mode> <in.ppm> <out.wav>\n");
        return 2;
    }
    const char* mode_name = argv[2];
    const char* in_path = argv[3];
    const char* out_path = argv[4];
    int rate = SSTVAF_CLI_DEFAULT_RATE;
    float amp = SSTVAF_CLI_DEFAULT_AMP;

    for (int i = 5; i < argc; i++) {
        if (strcmp(argv[i], "--rate") == 0 && i + 1 < argc) {
            rate = atoi(argv[++i]);
        } else if (strcmp(argv[i], "--amp") == 0 && i + 1 < argc) {
            amp = (float)atof(argv[++i]);
        } else {
            fprintf(stderr, "encode: unknown option %s\n", argv[i]);
            return 2;
        }
    }
    if (rate <= 0) {
        fprintf(stderr, "encode: invalid --rate\n");
        return 2;
    }

    int mode = sstvaf_cli_mode_by_name(mode_name);
    if (mode < 0) {
        fprintf(stderr, "encode: unknown mode '%s' (try `sstvaf list`)\n",
                mode_name);
        return 2;
    }
    const sstv_mode_t* m = sstv_mode_get(mode);

    size_t plen = 0;
    unsigned char* praw = sstvaf_cli_read_file(in_path, &plen);
    if (!praw) {
        fprintf(stderr, "encode: cannot read '%s'\n", in_path);
        return 1;
    }
    int w = 0, h = 0;
    uint32_t* img = sstvaf_cli_ppm_parse(praw, plen, &w, &h);
    free(praw);
    if (!img) {
        fprintf(stderr, "encode: '%s' is not a binary PPM (P6, maxval 255)\n",
                in_path);
        return 1;
    }
    if (w != m->width || h != m->height) {
        fprintf(stderr,
                "encode: %s needs a %dx%d image, got %dx%d\n",
                m->name, m->width, m->height, w, h);
        free(img);
        return 1;
    }

    int need = sstv_encode_num_samples(mode, rate);
    if (need <= 0) {
        fprintf(stderr, "encode: codec rejected mode/rate\n");
        free(img);
        return 1;
    }
    float* buf = (float*)malloc((size_t)need * sizeof(float));
    if (!buf) { free(img); return 1; }
    int got = sstv_encode(mode, img, w, h, rate, amp, buf, need);
    free(img);
    if (got != need) {
        fprintf(stderr, "encode: codec error %d\n", got);
        free(buf);
        return 1;
    }

    size_t wlen = 0;
    unsigned char* wav = sstvaf_cli_wav_serialize(buf, got, rate, &wlen);
    free(buf);
    if (!wav) { fprintf(stderr, "encode: out of memory\n"); return 1; }
    int rc = sstvaf_cli_write_file(out_path, wav, wlen);
    free(wav);
    if (rc != 0) {
        fprintf(stderr, "encode: cannot write '%s'\n", out_path);
        return 1;
    }
    printf("encoded %s: %d samples @ %d Hz -> %s\n", m->name, got, rate,
           out_path);
    return 0;
}

static int cmd_decode(int argc, char** argv)
{
    if (argc < 4) {
        fprintf(stderr, "decode: need <in.wav> <out.ppm>\n");
        return 2;
    }
    const char* in_path = argv[2];
    const char* out_path = argv[3];

    size_t wlen = 0;
    unsigned char* wav = sstvaf_cli_read_file(in_path, &wlen);
    if (!wav) {
        fprintf(stderr, "decode: cannot read '%s'\n", in_path);
        return 1;
    }
    int n = 0, rate = 0;
    float* samples = sstvaf_cli_wav_parse(wav, wlen, &n, &rate);
    free(wav);
    if (!samples) {
        fprintf(stderr, "decode: '%s' is not a 16-bit PCM WAV\n", in_path);
        return 1;
    }

    sstv_decoder_t* d = sstv_decoder_create(rate);
    if (!d) {
        fprintf(stderr, "decode: unsupported sample rate %d\n", rate);
        free(samples);
        return 1;
    }
    // Push in live-sized chunks, then a little trailing silence so the decoder
    // can finish the final frame.
    const int chunk = 2048;
    for (int off = 0; off < n; off += chunk) {
        int c = (n - off < chunk) ? (n - off) : chunk;
        sstv_decoder_push(d, samples + off, c);
    }
    free(samples);
    float tail[2048] = { 0 };
    for (int i = 0; i < 16; i++) sstv_decoder_push(d, tail, 2048);

    int mode = sstv_decoder_mode(d);
    if (mode < 0) {
        fprintf(stderr, "decode: no SSTV image found in '%s'\n", in_path);
        sstv_decoder_destroy(d);
        return 1;
    }
    const sstv_mode_t* m = sstv_mode_get(mode);
    int rows_ready = sstv_decoder_rows_ready(d);

    uint32_t* img = (uint32_t*)calloc((size_t)m->width * m->height,
                                      sizeof(uint32_t));
    if (!img) { sstv_decoder_destroy(d); return 1; }
    // Any un-decoded tail rows stay black (calloc zeroed); force full opacity.
    for (size_t i = 0; i < (size_t)m->width * m->height; i++)
        img[i] = 0xFF000000u;
    int rows = sstv_decoder_read_rows(d, 0, m->height, img);
    float quality = sstv_decoder_quality(d);
    int status = sstv_decoder_status(d);
    sstv_decoder_destroy(d);
    (void)rows_ready;

    size_t plen = 0;
    unsigned char* ppm = sstvaf_cli_ppm_serialize(img, m->width, m->height, &plen);
    free(img);
    if (!ppm) { fprintf(stderr, "decode: out of memory\n"); return 1; }
    int rc = sstvaf_cli_write_file(out_path, ppm, plen);
    free(ppm);
    if (rc != 0) {
        fprintf(stderr, "decode: cannot write '%s'\n", out_path);
        return 1;
    }
    printf("decoded %s: %d/%d rows%s, quality %.2f -> %s\n", m->name,
           rows < 0 ? 0 : rows, m->height,
           status == SSTV_STATUS_DONE ? " (complete)" : " (partial)",
           (double)quality, out_path);
    return 0;
}

int sstvaf_cli_run(int argc, char** argv)
{
    if (argc < 2) {
        print_usage(stderr);
        return 2;
    }
    const char* cmd = argv[1];
    if (strcmp(cmd, "encode") == 0) return cmd_encode(argc, argv);
    if (strcmp(cmd, "decode") == 0) return cmd_decode(argc, argv);
    if (strcmp(cmd, "list") == 0) return cmd_list();
    if (strcmp(cmd, "help") == 0 || strcmp(cmd, "--help") == 0 ||
        strcmp(cmd, "-h") == 0) {
        print_usage(stdout);
        return 0;
    }
    fprintf(stderr, "unknown command '%s'\n\n", cmd);
    print_usage(stderr);
    return 2;
}
