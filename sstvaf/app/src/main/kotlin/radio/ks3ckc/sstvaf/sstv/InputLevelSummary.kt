package radio.ks3ckc.sstvaf.sstv

import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Peak / RMS of the audio the RX engine actually received, summarised once
 * per [windowMs] of audio into one debug-log line.
 *
 * Exists because of a field report whose log had every decode of the
 * operator's own transmissions and not one line about the off-air signal
 * they could hear: nothing recorded whether audio was reaching the decoder
 * at all. Measured in audio time (samples / rate), not wall-clock, so the
 * cadence is deterministic in tests and the line never fires while the tap
 * is detached or receive is switched off.
 */
internal class InputLevelSummary(
    private val sampleRate: Int,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private var peak = 0f
    private var sumSquares = 0.0
    private var samples = 0L

    /** Fold the first [length] samples of [buffer] into the current window. */
    fun add(buffer: FloatArray, length: Int) {
        val n = length.coerceAtMost(buffer.size)
        var p = peak
        var ss = 0.0
        for (i in 0 until n) {
            val v = buffer[i]
            val a = if (v < 0f) -v else v
            if (a > p) p = a
            ss += v.toDouble() * v
        }
        peak = p
        sumSquares += ss
        samples += n
    }

    /**
     * The summary for the window just completed, or null until [windowMs] of
     * audio has accumulated. Taking it starts the next window.
     */
    fun summaryIfDue(): String? {
        if (samples * 1000L < windowMs * sampleRate) return null
        val rms = sqrt(sumSquares / samples)
        val seconds = samples.toDouble() / sampleRate
        val line = format(peak, rms, seconds)
        peak = 0f
        sumSquares = 0.0
        samples = 0L
        return line
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 30_000L

        /** Below this the capture is delivering digital silence, not quiet audio. */
        private const val SILENCE = 1e-6

        /**
         * `peak -6.0 dBFS, rms -24.3 dBFS over 30 s`, or `silent (all zero)
         * over 30 s` when the capture delivered nothing at all — the one
         * reading that says "the microphone is not connected" rather than
         * "the signal is weak".
         */
        fun format(peak: Float, rms: Double, seconds: Double): String {
            val secs = String.format(Locale.US, "%.0f s", seconds)
            if (peak < SILENCE) return "silent (all zero) over $secs"
            return "peak ${dbfs(peak.toDouble())}, rms ${dbfs(rms)} over $secs"
        }

        fun dbfs(linear: Double): String =
            if (linear < SILENCE) "-inf dBFS"
            else String.format(Locale.US, "%.1f dBFS", 20.0 * log10(linear))
    }
}
