// test_sstvaf_cli.c — host tests for the SSTVAF desktop CLI helpers.
//
// Covers the new code paths introduced by the desktop tool: mode-name
// resolution, the WAV and PPM (de)serializers, and a full CLI round trip
// (encode -> decode) both in memory and through the argv dispatcher and real
// files. Follows the sstvaf_glue test style (own main(), check(), nonzero
// exit on failure). Build/run via desktop/run_cli_host_tests.sh.

#include "sstv.h"
#include "sstv_modes.h"
#include "sstvaf_cli.h"

#include "sstv_test_util.h"  // check(), sstv_testcard_alloc(), sstv_test_psnr()

// --- mode name resolution --------------------------------------------------
static void test_mode_names(void)
{
    printf("mode name resolution:\n");
    check(sstvaf_cli_mode_by_name("Scottie 1") == SSTV_MODE_SCOTTIE1,
          "display name 'Scottie 1'");
    check(sstvaf_cli_mode_by_name("scottie1") == SSTV_MODE_SCOTTIE1,
          "compact alias 'scottie1'");
    check(sstvaf_cli_mode_by_name("SCOTTIE1") == SSTV_MODE_SCOTTIE1,
          "uppercase 'SCOTTIE1'");
    check(sstvaf_cli_mode_by_name("robot 36") == SSTV_MODE_ROBOT36,
          "'robot 36'");
    check(sstvaf_cli_mode_by_name("PD120") == SSTV_MODE_PD120, "'PD120'");
    check(sstvaf_cli_mode_by_name("martin-2") == SSTV_MODE_MARTIN2,
          "hyphenated 'martin-2'");
    check(sstvaf_cli_mode_by_name("nope") == -1, "unknown name -> -1");
    check(sstvaf_cli_mode_by_name("") == -1, "empty name -> -1");
    check(sstvaf_cli_mode_by_name(0) == -1, "NULL name -> -1");
    // Every table mode must resolve from its own display name.
    for (int id = 0; id < SSTV_NUM_MODES; id++) {
        const sstv_mode_t* m = sstv_mode_get(id);
        char label[80];
        snprintf(label, sizeof(label), "roundtrip name '%s'", m->name);
        check(sstvaf_cli_mode_by_name(m->name) == id, label);
    }
}

// --- WAV serialize/parse ---------------------------------------------------
static void test_wav(void)
{
    printf("WAV serialize/parse:\n");
    enum { N = 4096 };
    float* in = (float*)malloc(N * sizeof(float));
    for (int i = 0; i < N; i++) in[i] = sinf(2.0f * 3.14159265f * i / 32.0f);

    size_t len = 0;
    unsigned char* wav = sstvaf_cli_wav_serialize(in, N, 12000, &len);
    check(wav != 0, "serialize succeeds");
    if (!wav) { free(in); return; }  // bail before dereferencing on failure
    check(len == 44 + (size_t)N * 2, "serialized length = 44 + 2*N");
    check(memcmp(wav, "RIFF", 4) == 0 && memcmp(wav + 8, "WAVE", 4) == 0,
          "RIFF/WAVE magic present");

    int n = 0, rate = 0;
    float* out = sstvaf_cli_wav_parse(wav, len, &n, &rate);
    check(out != 0, "parse succeeds");
    check(n == N, "sample count preserved");
    check(rate == 12000, "sample rate preserved");
    double maxerr = 0.0;
    for (int i = 0; i < N && out; i++) {
        double e = fabs((double)out[i] - (double)in[i]);
        if (e > maxerr) maxerr = e;
    }
    check(maxerr < 1.0 / 256.0, "int16 round trip within a quantum");

    // Garbage is rejected, not crashed on.
    const unsigned char junk[16] = { 'n', 'o', 't', 'w', 'a', 'v' };
    int jn = 0, jr = 0;
    check(sstvaf_cli_wav_parse(junk, sizeof(junk), &jn, &jr) == 0,
          "non-WAV bytes rejected");

    free(in);
    free(wav);
    free(out);
}

// --- PPM serialize/parse ---------------------------------------------------
static void test_ppm(void)
{
    printf("PPM serialize/parse:\n");
    const int w = 4, h = 3;
    uint32_t img[12];
    for (int i = 0; i < w * h; i++)
        img[i] = 0xFF000000u | ((uint32_t)(i * 17) << 16) |
                 ((uint32_t)(i * 5) << 8) | (uint32_t)(i * 3);

    size_t len = 0;
    unsigned char* ppm = sstvaf_cli_ppm_serialize(img, w, h, &len);
    check(ppm != 0, "serialize succeeds");
    check(ppm && ppm[0] == 'P' && ppm[1] == '6', "P6 magic present");

    int pw = 0, ph = 0;
    uint32_t* back = sstvaf_cli_ppm_parse(ppm, len, &pw, &ph);
    check(back != 0, "parse succeeds");
    check(pw == w && ph == h, "dimensions preserved");
    int ok = 1;
    for (int i = 0; back && i < w * h; i++)
        if ((back[i] & 0x00FFFFFFu) != (img[i] & 0x00FFFFFFu)) ok = 0;
    check(ok, "RGB values preserved");
    check(back && (back[0] & 0xFF000000u) == 0xFF000000u, "alpha forced opaque");

    // A comment line in the header must be tolerated.
    const char* commented = "P6\n# made by test\n2 1\n255\n";
    size_t clen = strlen(commented) + 6;
    unsigned char* cbuf = (unsigned char*)malloc(clen);
    memcpy(cbuf, commented, strlen(commented));
    unsigned char px[6] = { 10, 20, 30, 40, 50, 60 };
    memcpy(cbuf + strlen(commented), px, 6);
    int cw = 0, ch = 0;
    uint32_t* cimg = sstvaf_cli_ppm_parse(cbuf, clen, &cw, &ch);
    check(cimg != 0 && cw == 2 && ch == 1, "comment line tolerated");
    check(cimg && (cimg[0] & 0xFFFFFF) == 0x0A141Eu, "first pixel after comment");

    // Malformed inputs are rejected.
    int bw = 0, bh = 0;
    check(sstvaf_cli_ppm_parse((const unsigned char*)"P3\n1 1\n255\n\0\0\0", 13,
                               &bw, &bh) == 0, "ASCII P3 rejected");
    check(sstvaf_cli_ppm_parse(ppm, len - 3, &bw, &bh) == 0,
          "truncated pixel data rejected");

    free(ppm);
    free(back);
    free(cbuf);
    free(cimg);
}

// --- in-memory encode -> WAV -> decode round trip --------------------------
static void test_wav_codec_roundtrip(void)
{
    printf("encode->WAV->decode round trip:\n");
    const int mode = SSTV_MODE_MARTIN1, rate = 12000;
    const sstv_mode_t* m = sstv_mode_get(mode);
    uint32_t* img = sstv_testcard_alloc(m->width, m->height);

    int need = sstv_encode_num_samples(mode, rate);
    float* buf = (float*)malloc((size_t)need * sizeof(float));
    int got = sstv_encode(mode, img, m->width, m->height, rate, 0.7f, buf, need);
    check(got == need, "encode produced full buffer");

    size_t wlen = 0;
    unsigned char* wav = sstvaf_cli_wav_serialize(buf, got, rate, &wlen);
    int n = 0, prate = 0;
    float* samples = sstvaf_cli_wav_parse(wav, wlen, &n, &prate);
    check(n == got && prate == rate, "WAV preserves samples & rate");

    sstv_decoder_t* d = sstv_decoder_create(prate);
    const int chunk = 2048;
    for (int off = 0; off < n; off += chunk) {
        int c = (n - off < chunk) ? (n - off) : chunk;
        sstv_decoder_push(d, samples + off, c);
    }
    float tail[2048] = { 0 };
    for (int i = 0; i < 16; i++) sstv_decoder_push(d, tail, 2048);

    check(sstv_decoder_mode(d) == mode, "mode detected after WAV round trip");
    check(sstv_decoder_status(d) == SSTV_STATUS_DONE, "decode reached DONE");

    uint32_t* out = (uint32_t*)calloc((size_t)m->width * m->height,
                                      sizeof(uint32_t));
    int rows = sstv_decoder_read_rows(d, 0, m->height, out);
    check(rows == m->height, "all rows read back");
    double p = sstv_test_min_psnr(img, out, m->width * m->height);
    printf("  info: WAV-path min PSNR = %.1f dB\n", p);
    check(p >= 20.0, "PSNR clears 20 dB through the int16 WAV path");

    sstv_decoder_destroy(d);
    free(img);
    free(buf);
    free(wav);
    free(samples);
    free(out);
}

// --- full CLI dispatch through argv + real files ---------------------------
static void test_cli_run_files(void)
{
    printf("CLI run() through files:\n");
    const char* in_ppm = "cli_test_in.ppm";
    const char* out_wav = "cli_test_out.wav";
    const char* out_ppm = "cli_test_out.ppm";
    const int mode = SSTV_MODE_MARTIN2;
    const sstv_mode_t* m = sstv_mode_get(mode);

    uint32_t* img = sstv_testcard_alloc(m->width, m->height);
    size_t plen = 0;
    unsigned char* ppm = sstvaf_cli_ppm_serialize(img, m->width, m->height, &plen);
    check(sstvaf_cli_write_file(in_ppm, ppm, plen) == 0, "wrote input PPM");

    char* enc_argv[] = { "sstvaf", "encode", "martin2",
                         (char*)in_ppm, (char*)out_wav };
    int rc = sstvaf_cli_run(5, enc_argv);
    check(rc == 0, "encode command returns 0");

    size_t wlen = 0;
    unsigned char* wav = sstvaf_cli_read_file(out_wav, &wlen);
    int n = 0, rate = 0;
    float* s = wav ? sstvaf_cli_wav_parse(wav, wlen, &n, &rate) : 0;
    check(s != 0 && n == sstv_encode_num_samples(mode, rate),
          "encoded WAV parses with the expected sample count");

    char* dec_argv[] = { "sstvaf", "decode", (char*)out_wav, (char*)out_ppm };
    rc = sstvaf_cli_run(4, dec_argv);
    check(rc == 0, "decode command returns 0");

    size_t dlen = 0;
    unsigned char* draw = sstvaf_cli_read_file(out_ppm, &dlen);
    int dw = 0, dh = 0;
    uint32_t* dimg = draw ? sstvaf_cli_ppm_parse(draw, dlen, &dw, &dh) : 0;
    check(dimg != 0 && dw == m->width && dh == m->height,
          "decoded PPM has the mode's dimensions");
    if (dimg) {
        double p = sstv_test_min_psnr(img, dimg, m->width * m->height);
        printf("  info: file-path min PSNR = %.1f dB\n", p);
        check(p >= 20.0, "file round-trip image clears 20 dB");
    }

    // Bad-usage exit codes.
    char* bad_mode[] = { "sstvaf", "encode", "bogus",
                         (char*)in_ppm, (char*)out_wav };
    check(sstvaf_cli_run(5, bad_mode) == 2, "unknown mode -> exit 2");
    char* no_cmd[] = { "sstvaf" };
    check(sstvaf_cli_run(1, no_cmd) == 2, "no command -> exit 2");
    char* help[] = { "sstvaf", "help" };
    check(sstvaf_cli_run(2, help) == 0, "help -> exit 0");
    char* list[] = { "sstvaf", "list" };
    check(sstvaf_cli_run(2, list) == 0, "list -> exit 0");

    remove(in_ppm);
    remove(out_wav);
    remove(out_ppm);
    free(img);
    free(ppm);
    free(wav);
    free(s);
    free(draw);
    free(dimg);
}

int main(void)
{
    printf("sstvaf_cli tests:\n");
    test_mode_names();
    test_wav();
    test_ppm();
    test_wav_codec_roundtrip();
    test_cli_run_files();

    if (g_failures) {
        printf("%d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("all sstvaf_cli tests passed\n");
    return 0;
}
