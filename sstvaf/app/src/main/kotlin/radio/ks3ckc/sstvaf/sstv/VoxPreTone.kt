package radio.ks3ckc.sstvaf.sstv

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Extra 1900 Hz leader tone prepended to an SSTV transmission when nothing
 * keys the rig explicitly (VOX control mode): the radio's own VOX or an
 * auto-PTT audio cable (e.g. the BTECH APRS-K1 / APRS-K1 PRO used with
 * HTs) only keys after it *hears* audio, so the attack time plus the
 * radio's TX ramp-up would otherwise eat the start of the real 300 ms
 * calibration leader — and a receiver that misses the leader/VIS decodes
 * nothing (see CLAUDE.md "Never clip the leading audio").
 *
 * The fix is strictly additive: the pre-tone is *prepended* sacrificial
 * leader at the same 1900 Hz the calibration header uses, so however much
 * of it the keying chain swallows, the untouched encoder output still
 * arrives intact. Samples are never dropped from the head.
 *
 * Everything here is deterministic and side-effect free so it can be unit
 * tested directly (no Android, no native lib), mirroring [CwId].
 */
object VoxPreTone {

    /** SSTV calibration-leader frequency (SSTV_FREQ_LEADER in sstv_lib). */
    const val TONE_HZ = 1900.0

    /** Matches the encoder output level so the joint has no amplitude step. */
    const val AMPLITUDE = NativeSstvCodec.ENCODE_AMPLITUDE

    /** Raised-cosine fade-in at the very start, in milliseconds — anti-click. */
    private const val RAMP_MS = 5.0

    /** Hard cap on the pre-tone length (settings picker upper bound). */
    const val MAX_MS = 1000

    /**
     * Default pre-tone length: one extra leader-length. Covers a hardware
     * auto-PTT cable's audio-detect attack plus a typical HT's TX ramp with
     * room to spare, while adding well under half a second of airtime.
     */
    const val DEFAULT_MS = 300

    /** The number of samples [samples] produces — for TX duration estimates. */
    fun sampleCount(preToneMs: Int, sampleRate: Int): Int {
        if (preToneMs <= 0 || sampleRate <= 0) return 0
        return (preToneMs.coerceAtMost(MAX_MS).toLong() * sampleRate / 1000L).toInt()
    }

    /** [sampleCount] expressed in seconds of airtime. */
    fun durationSeconds(preToneMs: Int, sampleRate: Int): Double {
        if (sampleRate <= 0) return 0.0
        return sampleCount(preToneMs, sampleRate).toDouble() / sampleRate
    }

    /**
     * A [preToneMs]-long 1900 Hz tone at [sampleRate]: raised-cosine fade-in
     * at the start (anti-click), full level thereafter. The phase is laid out
     * to reach exactly 0 at the buffer's end, so when the encoder's oscillator
     * starts its leader at zero phase the joint is phase-continuous; if it
     * doesn't, the residual step is a one-sample glitch mid-leader, which the
     * decoder's windowed leader detection ignores. Empty for a non-positive
     * length or sample rate.
     */
    fun samples(preToneMs: Int, sampleRate: Int): FloatArray {
        val n = sampleCount(preToneMs, sampleRate)
        if (n == 0) return FloatArray(0)
        val out = FloatArray(n)
        val step = 2.0 * PI * TONE_HZ / sampleRate
        val ramp = minOf((RAMP_MS * sampleRate / 1000.0).toInt(), n)
        for (i in 0 until n) {
            val env = if (ramp > 0 && i < ramp) 0.5 * (1.0 - cos(PI * i / ramp)) else 1.0
            // Phase counts down to 0 at index n, meeting the encoder's phase-0
            // leader start.
            out[i] = (AMPLITUDE * env * sin(step * (i - n))).toFloat()
        }
        return out
    }

    /**
     * Return [imageAudio] with a [preToneMs] pre-tone prepended, or unchanged
     * when the pre-tone is empty. The image samples themselves are never
     * modified or shifted relative to each other.
     */
    fun prependTo(imageAudio: FloatArray, preToneMs: Int, sampleRate: Int): FloatArray {
        val tone = samples(preToneMs, sampleRate)
        if (tone.isEmpty()) return imageAudio
        val out = FloatArray(tone.size + imageAudio.size)
        tone.copyInto(out, 0)
        imageAudio.copyInto(out, tone.size)
        return out
    }
}
