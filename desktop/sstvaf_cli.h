// sstvaf_cli.h — public surface of the SSTVAF desktop command-line tool.
//
// A thin, dependency-free front end over the clean-room SSTV codec in
// cpp/sstv_lib. It exists so the codec can be used on a plain desktop PC
// (notably a 32-bit Windows box, cross-compiled with the i686 MinGW
// toolchain — see desktop/README.md) without Android:
//
//   sstvaf encode <mode> <in.ppm> <out.wav> [--rate N] [--amp A]
//   sstvaf decode <in.wav> <out.ppm>
//   sstvaf list
//
// Image I/O uses binary PPM (P6); audio I/O uses 16-bit PCM mono WAV. Both
// formats are trivial and self-contained so the tool needs no third-party
// libraries — important for the frozen 32-bit Windows target.
//
// The reusable, side-effect-light helpers are declared here (rather than kept
// static in the .c) so the host test suite (test_sstvaf_cli.c) can exercise
// them directly: Compose/DrawScope has no analogue in C, but the same "keep
// main() a thin wrapper, test the extracted logic" rule from CLAUDE.md applies.

#ifndef SSTVAF_DESKTOP_CLI_H
#define SSTVAF_DESKTOP_CLI_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Default synthesis parameters, shared by the CLI and its tests.
#define SSTVAF_CLI_DEFAULT_RATE  12000
#define SSTVAF_CLI_DEFAULT_AMP   0.7f

// Resolve a user-typed mode string to an SSTV_MODE_* id, or -1 if unknown.
// Case-insensitive and space-insensitive, so "Scottie 1", "scottie1" and
// "SCOTTIE1" all resolve to SSTV_MODE_SCOTTIE1.
int sstvaf_cli_mode_by_name(const char* name);

// ---------------------------------------------------------------------------
// WAV (16-bit PCM, mono) — in-memory serialize / parse.
// ---------------------------------------------------------------------------

// Serialize `n` float samples (clamped to [-1,1] and quantized to int16) at
// `sample_rate` into a freshly malloc'd RIFF/WAVE byte buffer. On success
// returns the buffer and writes its length to *len_out; the caller frees it.
// Returns NULL on bad args or allocation failure.
unsigned char* sstvaf_cli_wav_serialize(const float* samples, int n,
                                        int sample_rate, size_t* len_out);

// Parse a 16-bit PCM WAV. On success returns a malloc'd float buffer of the
// (mono-mixed) samples, writes the count to *n_out and the rate to *rate_out,
// and the caller frees it. Multi-channel files are down-mixed by averaging.
// Returns NULL if the bytes are not a 16-bit PCM WAV.
float* sstvaf_cli_wav_parse(const unsigned char* data, size_t len,
                            int* n_out, int* rate_out);

// ---------------------------------------------------------------------------
// PPM (binary P6) — in-memory serialize / parse. Pixels are 0xAARRGGBB.
// ---------------------------------------------------------------------------

// Serialize a w*h ARGB image as binary PPM (alpha is dropped). Returns a
// malloc'd buffer + *len_out, or NULL on bad args / allocation failure.
unsigned char* sstvaf_cli_ppm_serialize(const uint32_t* argb, int w, int h,
                                        size_t* len_out);

// Parse a binary (P6) PPM with maxval 255, tolerating '#' comment lines.
// Returns a malloc'd ARGB buffer (alpha forced to 0xFF) + dimensions, or NULL
// on a malformed / unsupported header.
uint32_t* sstvaf_cli_ppm_parse(const unsigned char* data, size_t len,
                               int* w_out, int* h_out);

// ---------------------------------------------------------------------------
// Whole-file helpers (thin wrappers over the serialize/parse pair above).
// ---------------------------------------------------------------------------

// Read an entire file into a malloc'd buffer; *len_out gets its size. NULL on
// error. The caller frees the buffer.
unsigned char* sstvaf_cli_read_file(const char* path, size_t* len_out);

// Write `len` bytes to `path`. Returns 0 on success, -1 on error.
int sstvaf_cli_write_file(const char* path, const unsigned char* data, size_t len);

// ---------------------------------------------------------------------------
// Entry point. Returns a process exit code (0 = success). main() just forwards
// argc/argv here.
// ---------------------------------------------------------------------------
int sstvaf_cli_run(int argc, char** argv);

#ifdef __cplusplus
}
#endif

#endif  // SSTVAF_DESKTOP_CLI_H
