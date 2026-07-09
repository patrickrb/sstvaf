// sstv_vis.h — VIS (Vertical Interval Signaling) code helpers.
//
// VIS format (see SOURCES.md): 1200 Hz 30 ms start bit, 7 data bits LSB
// first (30 ms each, 1 = 1100 Hz, 0 = 1300 Hz), one even-parity bit, and a
// 1200 Hz 30 ms stop bit. Ten 30 ms cells in total.

#ifndef SSTV_LIB_SSTV_VIS_H
#define SSTV_LIB_SSTV_VIS_H

#ifdef __cplusplus
extern "C" {
#endif

// Even-parity bit for a 7-bit code: 1 if the code has an odd number of set
// bits (so data+parity together always carry an even count of ones).
int sstv_vis_parity_bit(int vis_code);

// Frequency (Hz) of VIS cell `cell` (0 = start bit, 1..7 = data bits LSB
// first, 8 = parity bit, 9 = stop bit) for the given 7-bit code.
// Returns 0.0 for an out-of-range cell.
double sstv_vis_cell_freq(int vis_code, int cell);

#ifdef __cplusplus
}
#endif

#endif // SSTV_LIB_SSTV_VIS_H
