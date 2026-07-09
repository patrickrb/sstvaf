package radio.ks3ckc.sstvaf.sstv.digital

import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 Cooley-Tukey FFT over split real/imaginary
 * `DoubleArray`s, used by the OFDM modem ([OfdmModem]). Sizes must be powers
 * of two. [ifft] is the forward transform on the conjugate, scaled by `1/n`,
 * so `ifft(fft(x)) == x` to floating-point precision.
 */
internal object Fft {

    fun fft(re: DoubleArray, im: DoubleArray) = transform(re, im, inverse = false)

    fun ifft(re: DoubleArray, im: DoubleArray) {
        transform(re, im, inverse = true)
        val n = re.size
        val scale = 1.0 / n
        for (i in 0 until n) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two: $n" }
        require(im.size == n) { "re/im length mismatch" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2.0 else -2.0) * Math.PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var curR = 1.0
                var curI = 0.0
                for (k in 0 until len / 2) {
                    val aR = re[i + k]
                    val aI = im[i + k]
                    val bR = re[i + k + len / 2]
                    val bI = im[i + k + len / 2]
                    val tR = bR * curR - bI * curI
                    val tI = bR * curI + bI * curR
                    re[i + k] = aR + tR
                    im[i + k] = aI + tI
                    re[i + k + len / 2] = aR - tR
                    im[i + k + len / 2] = aI - tI
                    val nR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
