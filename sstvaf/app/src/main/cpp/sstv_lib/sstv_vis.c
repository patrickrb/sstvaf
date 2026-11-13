// sstv_vis.c — VIS code helpers. See sstv_vis.h.

#include "sstv_vis.h"
#include "sstv_modes.h"

int sstv_vis_parity_bit(int vis_code)
{
    int ones = 0;
    for (int i = 0; i < 7; i++) ones += (vis_code >> i) & 1;
    return ones & 1;
}

double sstv_vis_cell_freq(int vis_code, int cell)
{
    if (cell == 0 || cell == 9) return SSTV_FREQ_SYNC;   // start / stop
    if (cell >= 1 && cell <= 7) {
        int bit = (vis_code >> (cell - 1)) & 1;          // LSB first
        return bit ? SSTV_FREQ_VIS_ONE : SSTV_FREQ_VIS_ZERO;
    }
    if (cell == 8) {
        return sstv_vis_parity_bit(vis_code) ? SSTV_FREQ_VIS_ONE
                                             : SSTV_FREQ_VIS_ZERO;
    }
    return 0.0;
}
