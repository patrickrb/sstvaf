// sstv_color.h — BT.601 studio-swing color conversion for the Robot/PD
// modes. Forward matrix from the published spec (see SOURCES.md); the
// inverse is the exact numeric inverse of that matrix, clamped to [0,255].

#ifndef SSTV_LIB_SSTV_COLOR_H
#define SSTV_LIB_SSTV_COLOR_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// R,G,B in [0,255] -> Y (16..235-ish), Cr = "R-Y", Cb = "B-Y" (both around
// 128). Unclamped doubles; the encoder clamps when quantizing to a scan.
void sstv_rgb_to_ycc(double r, double g, double b,
                     double* y, double* cr, double* cb);

// Exact clamped inverse of sstv_rgb_to_ycc.
void sstv_ycc_to_rgb(double y, double cr, double cb,
                     uint8_t* r, uint8_t* g, uint8_t* b);

#ifdef __cplusplus
}
#endif

#endif // SSTV_LIB_SSTV_COLOR_H
