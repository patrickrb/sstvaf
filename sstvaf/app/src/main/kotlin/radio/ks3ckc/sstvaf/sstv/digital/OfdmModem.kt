package radio.ks3ckc.sstvaf.sstv.digital

import kotlin.math.sqrt

/**
 * Coherent COFDM (DRM-derived) modem for the digital-SSTV waveform, operating
 * on a real audio passband.
 *
 * Each OFDM symbol is a real signal built by loading QPSK data and known
 * pilots onto a contiguous band of subcarriers, forcing Hermitian symmetry so
 * the inverse FFT is purely real, and prepending a cyclic prefix (guard
 * interval) that absorbs channel delay spread. The receiver FFTs the symbol
 * body, estimates the complex channel gain from the scattered pilots, linearly
 * interpolates it across the data carriers (zero-forcing equalization), and
 * hard-decides QPSK. Pilot equalization also absorbs the constant amplitude
 * and per-symbol phase introduced by the real-passband framing, so a
 * clean-channel round trip recovers every bit exactly.
 *
 * A known preamble symbol precedes the payload; [findFrameStart] cross-
 * correlates against it to recover symbol timing from a buffer with arbitrary
 * leading silence.
 *
 * This is original signal-processing code (see SOURCES note in the PR); the
 * subcarrier/pilot/guard structure follows the published DRM/HamDRM COFDM
 * design that EasyPal/DigTRX use, adapted to a real audio channel.
 */
internal class OfdmModem(val params: OfdmParams = OfdmParams()) {

    private val carriers: IntArray = (params.kLo..params.kHi).toList().toIntArray()
    private val pilotBins: IntArray
    private val dataBins: IntArray
    private val pilotSign: IntArray
    private val preambleWave: DoubleArray

    init {
        val pilots = ArrayList<Int>()
        val data = ArrayList<Int>()
        for ((i, k) in carriers.withIndex()) {
            if (i % params.pilotEvery == 0) pilots.add(k) else data.add(k)
        }
        pilotBins = pilots.toIntArray()
        dataBins = data.toIntArray()
        require(dataBins.isNotEmpty()) { "no data carriers for these params" }
        pilotSign = prbsSigns(pilotBins.size, PILOT_LFSR_SEED)
        preambleWave = modulateSymbol(preambleBits())
    }

    /** Total samples in one symbol (cyclic prefix + FFT body). */
    val symbolLength: Int get() = params.nfft + params.ncp

    /** Number of QPSK data carriers, i.e. [bitsPerSymbol] / 2. */
    val dataCarrierCount: Int get() = dataBins.size

    /** Payload bits carried by one OFDM symbol. */
    val bitsPerSymbol: Int get() = dataBins.size * 2

    /** The known preamble symbol waveform (length [symbolLength]). */
    fun preamble(): DoubleArray = preambleWave.copyOf()

    /**
     * Modulate exactly [bitsPerSymbol] payload bits into one real OFDM symbol.
     * Fewer bits than a full symbol should be zero-padded by the caller.
     */
    fun modulateSymbol(bits: IntArray): DoubleArray {
        require(bits.size == bitsPerSymbol) { "expected $bitsPerSymbol bits, got ${bits.size}" }
        val re = DoubleArray(params.nfft)
        val im = DoubleArray(params.nfft)
        for (i in pilotBins.indices) {
            val v = pilotValue(i)
            re[pilotBins[i]] = v.first
            im[pilotBins[i]] = v.second
        }
        for (i in dataBins.indices) {
            val (r, im2) = qpskMap(bits[2 * i], bits[2 * i + 1])
            re[dataBins[i]] = r
            im[dataBins[i]] = im2
        }
        // Hermitian symmetry -> real IFFT output.
        for (k in carriers) {
            re[params.nfft - k] = re[k]
            im[params.nfft - k] = -im[k]
        }
        Fft.ifft(re, im)
        val out = DoubleArray(symbolLength)
        // Cyclic prefix: copy the last ncp body samples in front.
        for (n in 0 until params.ncp) out[n] = re[params.nfft - params.ncp + n]
        for (n in 0 until params.nfft) out[params.ncp + n] = re[n]
        return out
    }

    /**
     * Demodulate one OFDM symbol (the [symbolLength] samples of [audio] at
     * [offset]) back to [bitsPerSymbol] hard bits.
     */
    fun demodulateSymbol(audio: DoubleArray, offset: Int): IntArray {
        val re = DoubleArray(params.nfft)
        val im = DoubleArray(params.nfft)
        for (n in 0 until params.nfft) re[n] = audio[offset + params.ncp + n]
        Fft.fft(re, im)

        // Channel gain at each pilot (zero-forcing).
        val gainR = DoubleArray(params.nfft)
        val gainI = DoubleArray(params.nfft)
        for (i in pilotBins.indices) {
            val k = pilotBins[i]
            val (pr, pi) = pilotValue(i)
            // spec[k] / pilot = spec[k] * conj(pilot) / |pilot|^2 ; |pilot|^2 = 1.
            gainR[k] = re[k] * pr + im[k] * pi
            gainI[k] = im[k] * pr - re[k] * pi
        }

        val bits = IntArray(bitsPerSymbol)
        for (i in dataBins.indices) {
            val k = dataBins[i]
            val (gr, gi) = interpolatedGain(k, gainR, gainI)
            // spec[k] / gain
            val denom = gr * gr + gi * gi
            val eqR: Double
            val eqI: Double
            if (denom > 1e-12) {
                eqR = (re[k] * gr + im[k] * gi) / denom
                eqI = (im[k] * gr - re[k] * gi) / denom
            } else {
                eqR = re[k]
                eqI = im[k]
            }
            bits[2 * i] = if (eqR > 0) 0 else 1
            bits[2 * i + 1] = if (eqI > 0) 0 else 1
        }
        return bits
    }

    /**
     * Find the sample offset where the preamble symbol begins, searching the
     * first [searchLimit] offsets by normalized cross-correlation. Returns the
     * best offset, or 0 when the buffer is too short to search.
     */
    fun findFrameStart(audio: DoubleArray, searchLimit: Int): Int {
        val limit = minOf(searchLimit, audio.size - symbolLength)
        if (limit < 0) return 0
        var best = 0
        var bestScore = -1.0
        for (off in 0..limit) {
            var dot = 0.0
            var energy = 0.0
            for (n in 0 until symbolLength) {
                val a = audio[off + n]
                dot += a * preambleWave[n]
                energy += a * a
            }
            val score = if (dot <= 0) 0.0 else dot * dot / (energy + 1e-12)
            if (score > bestScore) {
                bestScore = score
                best = off
            }
        }
        return best
    }

    // --- helpers ---

    private fun interpolatedGain(k: Int, gainR: DoubleArray, gainI: DoubleArray): Pair<Double, Double> {
        // Interpolate the channel gain between the two pilots that enclose k.
        // pilotBins is sorted ascending (built from the ascending carrier list),
        // so find the enclosing pilots with binarySearch instead of two
        // allocating filter scans — this runs per data carrier per symbol.
        val idx = pilotBins.binarySearch(k)
        if (idx >= 0) return gainR[k] to gainI[k] // k is itself a pilot
        val ins = -idx - 1 // pilots below k; also the index of the first pilot above k
        val lo = pilotBins[(ins - 1).coerceAtLeast(0)]
        val hi = pilotBins[ins.coerceAtMost(pilotBins.size - 1)]
        if (lo == hi) return gainR[lo] to gainI[lo]
        val frac = (k - lo).toDouble() / (hi - lo).toDouble()
        val gr = gainR[lo] * (1 - frac) + gainR[hi] * frac
        val gi = gainI[lo] * (1 - frac) + gainI[hi] * frac
        return gr to gi
    }

    private fun pilotValue(i: Int): Pair<Double, Double> {
        val s = pilotSign[i] * PILOT_MAG
        return s to s
    }

    private fun preambleBits(): IntArray {
        // Deterministic QPSK reference sequence (fixed LFSR), independent of
        // the pilot pattern, so timing correlation has a sharp peak.
        val bits = IntArray(bitsPerSymbol)
        var lfsr = PREAMBLE_LFSR_SEED
        for (i in bits.indices) {
            val bit = lfsr and 1
            bits[i] = bit
            lfsr = if (bit != 0) (lfsr ushr 1) xor 0xB400 else lfsr ushr 1
        }
        return bits
    }

    companion object {
        private const val PILOT_MAG = 0.70710678 // unit magnitude split over I/Q
        private const val PILOT_LFSR_SEED = 0xACE1
        private const val PREAMBLE_LFSR_SEED = 0x1234

        private fun qpskMap(b0: Int, b1: Int): Pair<Double, Double> {
            val inv = 1.0 / sqrt(2.0)
            return (1 - 2 * b0) * inv to (1 - 2 * b1) * inv
        }

        /** Length-[n] +1/-1 sign sequence from a 16-bit LFSR (whitens pilots). */
        private fun prbsSigns(n: Int, seed: Int): IntArray {
            val out = IntArray(n)
            var lfsr = seed
            for (i in 0 until n) {
                val bit = lfsr and 1
                out[i] = if (bit != 0) 1 else -1
                lfsr = if (bit != 0) (lfsr ushr 1) xor 0xB400 else lfsr ushr 1
            }
            return out
        }
    }
}

/**
 * OFDM subcarrier / guard-interval geometry. Defaults place ~48 QPSK carriers
 * (plus scattered pilots) in roughly the 500-3500 Hz band at an 8 kHz audio
 * rate, with a 1/8 cyclic prefix — a robust HF-friendly configuration.
 */
internal data class OfdmParams(
    val nfft: Int = 256,
    val ncp: Int = 32,
    val kLo: Int = 16,
    val kHi: Int = 112,
    val pilotEvery: Int = 8,
) {
    init {
        require(nfft > 0 && nfft and (nfft - 1) == 0) { "nfft must be a power of two" }
        require(ncp in 0 until nfft) { "ncp out of range" }
        require(kLo in 1 until nfft / 2) { "kLo out of range" }
        require(kHi in kLo until nfft / 2) { "kHi out of range" }
        require(pilotEvery >= 2) { "pilotEvery must be >= 2" }
    }
}
