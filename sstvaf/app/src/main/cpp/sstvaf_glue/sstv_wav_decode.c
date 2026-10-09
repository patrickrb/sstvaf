// sstv_wav_decode.c — host CLI: decode an SSTV recording (WAV) with the
// clean-room decoder and report what happened.
//
//   sstv_wav_decode <in.wav> [--out img.ppm] [--trace track.csv] [--chunk N]
//                   [--gain G] [--rate-override HZ]
//
// Reads a PCM WAV (8/16/24/32-bit int or 32-bit float, any channel count —
// channels are averaged to mono), pushes it through sstv_decoder_push in
// live-sized chunks, prints every status transition with its timestamp and
// the final mode/rows/quality/slant, and optionally writes the decoded image
// as a binary PPM plus the demodulated frequency track as CSV.
//
// This is the debugging companion to test_sstv_recordings.c: point it at a
// real off-air recording to see where the state machine gives up.
//
// Build: see run_sstv_host_tests.ps1 / .sh (target "sstv_wav_decode").

#include "sstv.h"
#include "sstv_modes.h"
#include "sstv_demod.h"
#include "sstv_wav.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char* status_name(int s)
{
    switch (s) {
    case SSTV_STATUS_IDLE: return "IDLE";
    case SSTV_STATUS_LEADER: return "LEADER";
    case SSTV_STATUS_VIS: return "VIS";
    case SSTV_STATUS_IMAGE: return "IMAGE";
    case SSTV_STATUS_DONE: return "DONE";
    case SSTV_STATUS_ABORTED: return "ABORTED";
    default: return "?";
    }
}

static int write_ppm(const char* path, const uint32_t* argb, int w, int h)
{
    FILE* f = fopen(path, "wb");
    if (!f) return -1;
    fprintf(f, "P6\n%d %d\n255\n", w, h);
    for (int i = 0; i < w * h; i++) {
        unsigned char px[3] = { (unsigned char)(argb[i] >> 16),
                                (unsigned char)(argb[i] >> 8),
                                (unsigned char)argb[i] };
        fwrite(px, 1, 3, f);
    }
    fclose(f);
    return 0;
}

int main(int argc, char** argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s <in.wav> [--out img.ppm] [--trace track.csv]"
                        " [--chunk N] [--gain G] [--rate-override HZ]\n", argv[0]);
        return 2;
    }
    const char* in_path = argv[1];
    const char* out_path = 0;
    const char* trace_path = 0;
    int chunk = 2400;
    double gain = 1.0;
    int rate_override = 0;
    for (int i = 2; i < argc; i++) {
        if (!strcmp(argv[i], "--out") && i + 1 < argc) out_path = argv[++i];
        else if (!strcmp(argv[i], "--trace") && i + 1 < argc) trace_path = argv[++i];
        else if (!strcmp(argv[i], "--chunk") && i + 1 < argc) chunk = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--gain") && i + 1 < argc) gain = atof(argv[++i]);
        else if (!strcmp(argv[i], "--rate-override") && i + 1 < argc) rate_override = atoi(argv[++i]);
        else {
            fprintf(stderr, "unknown arg: %s\n", argv[i]);
            return 2;
        }
    }
    if (chunk < 1) chunk = 1;

    int rate = 0, n = 0;
    float* pcm = sstv_wav_read_mono(in_path, &rate, &n);
    if (!pcm) {
        fprintf(stderr, "cannot read %s\n", in_path);
        return 1;
    }
    if (rate_override > 0) rate = rate_override;
    if (gain != 1.0) {
        for (int i = 0; i < n; i++) pcm[i] = (float)(pcm[i] * gain);
    }
    double peak = 0.0, rms = 0.0;
    for (int i = 0; i < n; i++) {
        double v = fabs(pcm[i]);
        if (v > peak) peak = v;
        rms += (double)pcm[i] * pcm[i];
    }
    rms = sqrt(rms / (n > 0 ? n : 1));
    printf("%s: %d Hz, %d samples (%.2f s), peak %.3f, rms %.3f\n", in_path, rate, n,
           (double)n / rate, peak, rms);

    if (trace_path) {
        sstv_demod_t dm;
        if (sstv_demod_init(&dm, rate) == 0) {
            FILE* tf = fopen(trace_path, "w");
            if (tf) {
                fprintf(tf, "t_s,f_hz,mag\n");
                for (int i = 0; i < n; i++) {
                    double mag;
                    double f = sstv_demod_process(&dm, (double)pcm[i], &mag);
                    fprintf(tf, "%.6f,%.1f,%.5f\n", (double)i / rate, f, mag);
                }
                fclose(tf);
            }
            sstv_demod_free(&dm);
        }
    }

    sstv_decoder_t* d = sstv_decoder_create(rate);
    if (!d) {
        fprintf(stderr, "sstv_decoder_create(%d) failed\n", rate);
        free(pcm);
        return 1;
    }

    int last_status = sstv_decoder_status(d);
    int last_rows = 0;
    int images = 0;
    for (int off = 0; off < n; off += chunk) {
        int c = (n - off < chunk) ? (n - off) : chunk;
        sstv_decoder_push(d, pcm + off, c);
        int s = sstv_decoder_status(d);
        double t = (double)(off + c) / rate;
        if (s != last_status) {
            printf("  %8.3f s: %s -> %s", t, status_name(last_status), status_name(s));
            if (s == SSTV_STATUS_IMAGE) {
                const sstv_mode_t* m = sstv_mode_get(sstv_decoder_mode(d));
                printf("  mode=%s (vis %d, %dx%d)", m ? m->name : "?",
                       m ? m->vis_code : -1, m ? m->width : 0, m ? m->height : 0);
            }
            printf("\n");
            last_status = s;
        }
        int rows = sstv_decoder_rows_ready(d);
        if (rows != last_rows && (rows % 32 == 0 || rows < last_rows)) {
            printf("  %8.3f s: rows=%d quality=%.2f slant=%.0f ppm\n", t, rows,
                   sstv_decoder_quality(d), sstv_decoder_slant_ppm(d));
        }
        last_rows = rows;
        if (s == SSTV_STATUS_DONE || s == SSTV_STATUS_ABORTED) {
            const sstv_mode_t* m = sstv_mode_get(sstv_decoder_mode(d));
            printf("  %8.3f s: %s mode=%s rows=%d/%d quality=%.2f slant=%.0f ppm\n", t,
                   status_name(s), m ? m->name : "?", rows, m ? m->height : 0,
                   sstv_decoder_quality(d), sstv_decoder_slant_ppm(d));
            if (m && out_path && rows > 0) {
                uint32_t* img = (uint32_t*)calloc((size_t)m->width * m->height, 4);
                sstv_decoder_read_rows(d, 0, rows, img);
                char path[1024];
                if (images == 0) snprintf(path, sizeof(path), "%s", out_path);
                else snprintf(path, sizeof(path), "%s.%d.ppm", out_path, images);
                write_ppm(path, img, m->width, m->height);
                printf("  wrote %s\n", path);
                free(img);
            }
            images++;
            sstv_decoder_reset(d);
            last_status = sstv_decoder_status(d);
            last_rows = 0;
        }
    }
    int s = sstv_decoder_status(d);
    printf("end: status=%s mode=%d rows=%d images=%d\n", status_name(s),
           sstv_decoder_mode(d), sstv_decoder_rows_ready(d), images);
    // A clip that ends mid-image still has rows worth looking at.
    const sstv_mode_t* m = sstv_mode_get(sstv_decoder_mode(d));
    int rows = sstv_decoder_rows_ready(d);
    if (m && out_path && rows > 0 && s == SSTV_STATUS_IMAGE) {
        uint32_t* img = (uint32_t*)calloc((size_t)m->width * m->height, 4);
        sstv_decoder_read_rows(d, 0, rows, img);
        // Same numbering as the finished images so a partial after a
        // complete one does not overwrite it.
        char path[1024];
        if (images == 0) snprintf(path, sizeof(path), "%s", out_path);
        else snprintf(path, sizeof(path), "%s.%d.ppm", out_path, images);
        write_ppm(path, img, m->width, m->height);
        printf("  wrote %s (partial, %d rows)\n", path, rows);
        free(img);
        images++;
    }
    sstv_decoder_destroy(d);
    free(pcm);
    return images > 0 ? 0 : 3;
}
