package radio.ks3ckc.sstvaf.sstv.digital

/**
 * Rate bridge between the digital codec's waveform and the app's audio
 * paths.
 *
 * The COFDM waveform is defined in the sample domain; its physical
 * bandwidth is fixed by the playback rate. At [DIGITAL_WAVEFORM_RATE_HZ]
 * (6 kHz) the data carriers (FFT bins 16..120 of 256) land on
 * 375..2812 Hz — inside a normal SSB transmit passband, matching the
 * ~2.5 kHz HamDRM channel the design follows. Any other rate pushes the
 * upper carriers past the rig's filter, so every path converts:
 *
 *   TX: encode at 6 kHz -> [upsampleForDeviceRate] to the device audio rate.
 *   RX: the 12 kHz listener tap -> [decimate2to1] -> 6 kHz decode.
 *
 * Both directions share one sharp anti-alias filter: zero-stuffed
 * doubling stages on TX, low-pass-then-drop on RX — flat across the
 * carriers, linear phase, and cheap.
 */

/** The rate the digital waveform is defined at (see file KDoc). */
const val DIGITAL_WAVEFORM_RATE_HZ = 6000

/**
 * Linear-interpolation resample of [wave] from [fromRate] to [toRate].
 * Identity when the rates match. Intended for upsampling (TX); for
 * downsampling use a proper decimator instead.
 */
internal fun upsampleLinear(wave: FloatArray, fromRate: Int, toRate: Int): FloatArray {
    require(fromRate > 0 && toRate > 0) { "bad rates $fromRate -> $toRate" }
    if (fromRate == toRate || wave.isEmpty()) return wave.copyOf()
    val outLen = (wave.size.toLong() * toRate / fromRate).toInt()
    val step = fromRate.toDouble() / toRate
    return FloatArray(outLen) { i ->
        val x = i * step
        val i0 = x.toInt().coerceAtMost(wave.size - 1)
        val i1 = (i0 + 1).coerceAtMost(wave.size - 1)
        val fr = (x - i0).toFloat()
        wave[i0] * (1f - fr) + wave[i1] * fr
    }
}

/**
 * Symmetric 255-tap Blackman-windowed-sinc low-pass shared by
 * [Decimator2to1] and [upsample2to1], designed at the 12 kHz side of the
 * 2:1 step. The length is forced by the FAST mode: its top carrier sits at
 * 2812 Hz while everything above 3000 Hz must be gone before decimation
 * (its alias would land back among the carriers), leaving only a 188 Hz
 * transition band — a short filter's early rolloff there costs
 * Reed-Solomon blocks even in a noiseless loopback. Cutoff is centred in
 * that gap (0.2422 fs); 255 taps put the passband edge within a fraction
 * of a dB and the alias band below the FEC's notice, at ~3 M multiplies
 * per second of streamed audio — noise on a phone core.
 */
private val ANTI_ALIAS = run {
    val n = 255
    val mid = n / 2
    val fc = 0.2422 // of the 12 kHz-side sample rate
    val taps = DoubleArray(n) { i ->
        val k = i - mid
        val sinc =
            if (k == 0) 2.0 * fc
            else kotlin.math.sin(2.0 * Math.PI * fc * k) / (Math.PI * k)
        val x = Math.PI * k / mid
        val window = 0.42 + 0.5 * kotlin.math.cos(x) + 0.08 * kotlin.math.cos(2.0 * x)
        sinc * window
    }
    val sum = taps.sum()
    DoubleArray(n) { taps[it] / sum }
}

/**
 * Whole-buffer 2x upsample: zero-stuff then [ANTI_ALIAS] at the output rate
 * (gain 2 restores the amplitude the stuffing halves). The workhorse of
 * [upsampleForDeviceRate]; unlike linear interpolation its passband is flat
 * across the OFDM carriers.
 */
internal fun upsample2to1(wave: FloatArray): FloatArray {
    val stuffed = DoubleArray(wave.size * 2)
    for (i in wave.indices) stuffed[2 * i] = wave[i].toDouble()
    // Full convolution, tail included: truncating at 2n would clip the
    // filter's flush of the final samples — for an OFDM frame that is the
    // last symbol, and a clipped last symbol costs Reed-Solomon blocks
    // (same lesson as CLAUDE.md's "never clip the leading audio", at the
    // other end).
    val out = FloatArray(stuffed.size + ANTI_ALIAS.size - 1)
    for (i in out.indices) {
        var acc = 0.0
        for (t in ANTI_ALIAS.indices) {
            val j = i - t
            if (j >= 0 && j < stuffed.size) acc += ANTI_ALIAS[t] * stuffed[j]
        }
        out[i] = (2.0 * acc).toFloat()
    }
    return out
}

/**
 * Resample the 6 kHz digital waveform to [deviceRate]: half-band doubling
 * stages to the first power-of-two rate at or above the target, then a
 * linear tail for any residual ratio (e.g. 48 kHz is three exact stages;
 * 44.1 kHz doubles to 48 kHz and linearly resamples down — at that point
 * the signal sits far below Nyquist, where linear interpolation is clean).
 */
internal fun upsampleForDeviceRate(wave: FloatArray, deviceRate: Int): FloatArray {
    require(deviceRate >= DIGITAL_WAVEFORM_RATE_HZ) { "device rate $deviceRate too low" }
    var out = wave
    var rate = DIGITAL_WAVEFORM_RATE_HZ
    while (rate < deviceRate) {
        out = upsample2to1(out)
        rate *= 2
    }
    return if (rate == deviceRate) out else upsampleLinear(out, rate, deviceRate)
}

/**
 * Stateful 2:1 decimator (12 kHz -> 6 kHz) for the streaming RX path:
 * low-pass at ~3 kHz then keep every other sample. Carries the filter
 * history across [push] calls so chunk boundaries are seamless.
 */
internal class Decimator2to1 {
    private val history = FloatArray(ANTI_ALIAS.size - 1)
    private var phase = 0

    /** Decimate [samples] (first [length]), returning the 6 kHz output. */
    fun push(samples: FloatArray, length: Int): FloatArray {
        val len = length.coerceAtMost(samples.size)
        if (len <= 0) return FloatArray(0)
        val hist = history.size
        val work = FloatArray(hist + len)
        System.arraycopy(history, 0, work, 0, hist)
        System.arraycopy(samples, 0, work, hist, len)

        val out = ArrayList<Float>(len / 2 + 1)
        // Output sample for every even-phase input position.
        for (i in 0 until len) {
            if (phase == 0) {
                var acc = 0.0
                for (t in ANTI_ALIAS.indices) {
                    acc += ANTI_ALIAS[t] * work[hist + i - t]
                }
                out.add(acc.toFloat())
            }
            phase = (phase + 1) and 1
        }
        // Preserve the last hist input samples for the next chunk.
        System.arraycopy(work, len, history, 0, hist)
        return out.toFloatArray()
    }

    fun reset() {
        history.fill(0f)
        phase = 0
    }
}

/**
 * Encode [image] for a device audio path running at [deviceRate]: the
 * 6 kHz codec waveform, upsampled. This is what the transmitter plays.
 */
internal fun encodeDigitalForRate(
    image: DigitalSstvCodec.Image,
    mode: DigitalSstvMode,
    deviceRate: Int,
): FloatArray =
    upsampleForDeviceRate(DigitalSstvCodec(mode).encode(image), deviceRate)

/**
 * Estimated on-air seconds for a [payloadBytes]-byte image in [mode],
 * derived from the exact frame layout (preamble + header + payload
 * segments at the modem's bits/symbol) — used for the TX duration label
 * without paying for a full encode.
 */
internal fun digitalDurationSeconds(payloadBytes: Int, mode: DigitalSstvMode): Double {
    val image = DigitalSstvCodec.Image(
        DigitalSstvCodec.Format.JPEG, 0, 0, ByteArray(payloadBytes.coerceAtLeast(0)),
    )
    // The container/codec own the layout; one dry-run encode of a zeroed
    // payload is exact and still cheap (all-zero QPSK symbols, ~100 ms of
    // work for the largest payloads the UI offers).
    val samples = DigitalSstvCodec(mode).encode(image).size
    return samples.toDouble() / DIGITAL_WAVEFORM_RATE_HZ
}
