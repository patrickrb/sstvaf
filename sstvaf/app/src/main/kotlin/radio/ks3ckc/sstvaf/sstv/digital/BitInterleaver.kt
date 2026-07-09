package radio.ks3ckc.sstvaf.sstv.digital

import kotlin.math.ceil

/**
 * Block (matrix) bit interleaver. Written row-by-row, read column-by-column,
 * so a contiguous burst of channel errors (a fade knocking out several
 * adjacent OFDM symbols) is spread across many Reed-Solomon codewords instead
 * of overwhelming one — the classic COFDM time-interleave that lets the FEC
 * do its job.
 *
 * [rows] is the interleave depth. [interleave] pads the last column with
 * zeros; [deinterleave] is told the original length so it drops that padding.
 * Bits are `0`/`1` in an `IntArray`.
 */
internal class BitInterleaver(private val rows: Int) {
    init {
        require(rows >= 1) { "rows must be >= 1: $rows" }
    }

    fun interleave(bits: IntArray): IntArray {
        if (bits.isEmpty()) return IntArray(0)
        val cols = ceil(bits.size.toDouble() / rows).toInt()
        val out = IntArray(rows * cols)
        var idx = 0
        for (c in 0 until cols) {
            for (r in 0 until rows) {
                val src = r * cols + c
                out[idx++] = if (src < bits.size) bits[src] else 0
            }
        }
        return out
    }

    /** Inverse of [interleave]; [originalLength] is the pre-padding bit count. */
    fun deinterleave(bits: IntArray, originalLength: Int): IntArray {
        if (originalLength == 0) return IntArray(0)
        val cols = ceil(originalLength.toDouble() / rows).toInt()
        val grid = IntArray(rows * cols)
        var idx = 0
        for (c in 0 until cols) {
            for (r in 0 until rows) {
                if (idx < bits.size) grid[r * cols + c] = bits[idx]
                idx++
            }
        }
        return grid.copyOfRange(0, originalLength)
    }
}
