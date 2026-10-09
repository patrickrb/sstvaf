// sstv_wav.h — minimal RIFF/WAVE reader for the host-side SSTV tools and
// the real-recording regression test. Header-only, C11, no dependencies.
//
// Handles PCM (8/16/24/32-bit integer) and IEEE float (32/64-bit) data in
// any channel count; channels are averaged to mono. Unknown chunks are
// skipped. Returns NULL on any parse error.

#ifndef SSTVAF_GLUE_SSTV_WAV_H
#define SSTVAF_GLUE_SSTV_WAV_H

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint32_t sstv_wav_le32(const unsigned char* p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
           ((uint32_t)p[3] << 24);
}

static uint16_t sstv_wav_le16(const unsigned char* p)
{
    return (uint16_t)(p[0] | (p[1] << 8));
}

// Read a WAV file into a freshly malloc'd mono float buffer in [-1, 1].
// *rate_out / *n_out receive the sample rate and mono sample count.
static float* sstv_wav_read_mono(const char* path, int* rate_out, int* n_out)
{
    FILE* f = fopen(path, "rb");
    if (!f) return 0;
    unsigned char hdr[12];
    if (fread(hdr, 1, 12, f) != 12 || memcmp(hdr, "RIFF", 4) != 0 ||
        memcmp(hdr + 8, "WAVE", 4) != 0) {
        fclose(f);
        return 0;
    }
    int fmt_tag = 0, channels = 0, rate = 0, bits = 0;
    unsigned char* data = 0;
    uint32_t data_len = 0;
    for (;;) {
        unsigned char ch[8];
        if (fread(ch, 1, 8, f) != 8) break;
        uint32_t len = sstv_wav_le32(ch + 4);
        if (!memcmp(ch, "fmt ", 4)) {
            unsigned char fmt[40];
            uint32_t take = len < sizeof(fmt) ? len : (uint32_t)sizeof(fmt);
            if (fread(fmt, 1, take, f) != take) break;
            if (len > take) fseek(f, (long)(len - take), SEEK_CUR);
            fmt_tag = sstv_wav_le16(fmt);
            channels = sstv_wav_le16(fmt + 2);
            rate = (int)sstv_wav_le32(fmt + 4);
            bits = sstv_wav_le16(fmt + 14);
            // WAVE_FORMAT_EXTENSIBLE: the real tag is the first two bytes of
            // the SubFormat GUID.
            if (fmt_tag == 0xFFFE && take >= 26) fmt_tag = sstv_wav_le16(fmt + 24);
        } else if (!memcmp(ch, "data", 4)) {
            // Streams (ffmpeg pipes) may write 0 / 0xFFFFFFFF: read to EOF.
            long pos = ftell(f);
            fseek(f, 0, SEEK_END);
            long end = ftell(f);
            fseek(f, pos, SEEK_SET);
            uint32_t avail = (uint32_t)(end - pos);
            if (len == 0 || len == 0xFFFFFFFFu || len > avail) len = avail;
            data = (unsigned char*)malloc(len ? len : 1);
            if (!data || fread(data, 1, len, f) != len) {
                free(data);
                data = 0;
                break;
            }
            data_len = len;
            break;
        } else {
            fseek(f, (long)(len + (len & 1)), SEEK_CUR);
        }
    }
    fclose(f);
    if (!data || channels <= 0 || rate <= 0 || bits <= 0) {
        free(data);
        return 0;
    }
    int bps = bits / 8;
    if (bps <= 0) {
        free(data);
        return 0;
    }
    uint32_t frames = data_len / (uint32_t)(bps * channels);
    float* out = (float*)malloc(sizeof(float) * (frames ? frames : 1));
    if (!out) {
        free(data);
        return 0;
    }
    const unsigned char* p = data;
    for (uint32_t i = 0; i < frames; i++) {
        double acc = 0.0;
        for (int c = 0; c < channels; c++, p += bps) {
            double v = 0.0;
            if (fmt_tag == 3) {  // IEEE float
                if (bps == 4) {
                    float fv;
                    memcpy(&fv, p, 4);
                    v = fv;
                } else if (bps == 8) {
                    double dv;
                    memcpy(&dv, p, 8);
                    v = dv;
                }
            } else {  // PCM
                if (bps == 1) v = ((double)p[0] - 128.0) / 128.0;
                else if (bps == 2) v = (double)(int16_t)sstv_wav_le16(p) / 32768.0;
                else if (bps == 3) {
                    int32_t s = (int32_t)(((uint32_t)p[0] << 8) | ((uint32_t)p[1] << 16) |
                                          ((uint32_t)p[2] << 24));
                    v = (double)s / 2147483648.0;
                } else if (bps == 4) v = (double)(int32_t)sstv_wav_le32(p) / 2147483648.0;
            }
            acc += v;
        }
        out[i] = (float)(acc / channels);
    }
    free(data);
    *rate_out = rate;
    *n_out = (int)frames;
    return out;
}

#endif // SSTVAF_GLUE_SSTV_WAV_H
