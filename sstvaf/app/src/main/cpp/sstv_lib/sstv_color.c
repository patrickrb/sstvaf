// sstv_color.c — BT.601 studio-swing conversion. See sstv_color.h.

#include "sstv_color.h"

#include <math.h>

// Forward matrix (times 1/256), offsets (16, 128, 128):
//   Y  =  16 + ( 65.738 R + 129.057 G +  25.064 B) / 256
//   Cr = 128 + (112.439 R -  94.154 G -  18.285 B) / 256
//   Cb = 128 + (-37.945 R -  74.494 G + 112.439 B) / 256
static const double kFwd[3][3] = {
    {  65.738, 129.057,  25.064 },
    { 112.439, -94.154, -18.285 },
    { -37.945, -74.494, 112.439 },
};

// Exact inverse of kFwd/256, computed once via Cramer's rule so the decoder
// undoes the encoder's matrix to double precision (no published rounded
// constants).
static double g_inv[3][3];
static int g_inv_ready = 0;

static void init_inverse(void)
{
    double a[3][3];
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++) a[i][j] = kFwd[i][j] / 256.0;

    double det =
        a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
        a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
        a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);

    g_inv[0][0] =  (a[1][1] * a[2][2] - a[1][2] * a[2][1]) / det;
    g_inv[0][1] = -(a[0][1] * a[2][2] - a[0][2] * a[2][1]) / det;
    g_inv[0][2] =  (a[0][1] * a[1][2] - a[0][2] * a[1][1]) / det;
    g_inv[1][0] = -(a[1][0] * a[2][2] - a[1][2] * a[2][0]) / det;
    g_inv[1][1] =  (a[0][0] * a[2][2] - a[0][2] * a[2][0]) / det;
    g_inv[1][2] = -(a[0][0] * a[1][2] - a[0][2] * a[1][0]) / det;
    g_inv[2][0] =  (a[1][0] * a[2][1] - a[1][1] * a[2][0]) / det;
    g_inv[2][1] = -(a[0][0] * a[2][1] - a[0][1] * a[2][0]) / det;
    g_inv[2][2] =  (a[0][0] * a[1][1] - a[0][1] * a[1][0]) / det;

    g_inv_ready = 1;
}

void sstv_rgb_to_ycc(double r, double g, double b,
                     double* y, double* cr, double* cb)
{
    *y  =  16.0 + (kFwd[0][0] * r + kFwd[0][1] * g + kFwd[0][2] * b) / 256.0;
    *cr = 128.0 + (kFwd[1][0] * r + kFwd[1][1] * g + kFwd[1][2] * b) / 256.0;
    *cb = 128.0 + (kFwd[2][0] * r + kFwd[2][1] * g + kFwd[2][2] * b) / 256.0;
}

static uint8_t clamp_u8(double v)
{
    if (v < 0.0) return 0;
    if (v > 255.0) return 255;
    return (uint8_t)lrint(v);
}

void sstv_ycc_to_rgb(double y, double cr, double cb,
                     uint8_t* r, uint8_t* g, uint8_t* b)
{
    if (!g_inv_ready) init_inverse();
    double dy = y - 16.0, dcr = cr - 128.0, dcb = cb - 128.0;
    *r = clamp_u8(g_inv[0][0] * dy + g_inv[0][1] * dcr + g_inv[0][2] * dcb);
    *g = clamp_u8(g_inv[1][0] * dy + g_inv[1][1] * dcr + g_inv[1][2] * dcb);
    *b = clamp_u8(g_inv[2][0] * dy + g_inv[2][1] * dcr + g_inv[2][2] * dcb);
}
