package radio.ks3ckc.sstvaf.sstv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The 30 s input-level line the RX engine writes to debug.log. */
class InputLevelSummaryTest {

    private fun tone(amplitude: Float, n: Int) = FloatArray(n) { i ->
        if (i % 2 == 0) amplitude else -amplitude
    }

    @Test
    fun nothingUntilAFullWindowOfAudioHasArrived() {
        val summary = InputLevelSummary(sampleRate = 1000, windowMs = 1000)
        summary.add(tone(0.5f, 999), 999)
        assertThat(summary.summaryIfDue()).isNull()
        summary.add(tone(0.5f, 1), 1)
        assertThat(summary.summaryIfDue()).isEqualTo("peak -6.0 dBFS, rms -6.0 dBFS over 1 s")
    }

    @Test
    fun takingTheSummaryStartsAFreshWindow() {
        val summary = InputLevelSummary(sampleRate = 1000, windowMs = 1000)
        summary.add(tone(1.0f, 1000), 1000)
        assertThat(summary.summaryIfDue()).startsWith("peak 0.0 dBFS")
        assertThat(summary.summaryIfDue()).isNull()
        // The loud first window must not leak into the quiet second one.
        summary.add(tone(0.1f, 1000), 1000)
        assertThat(summary.summaryIfDue()).isEqualTo("peak -20.0 dBFS, rms -20.0 dBFS over 1 s")
    }

    @Test
    fun peakIsTheLargestMagnitudeAndRmsTheMeanPower() {
        val summary = InputLevelSummary(sampleRate = 4, windowMs = 1000)
        // One -1.0 sample among zeros: peak is full scale, rms is half (= -6 dB).
        summary.add(floatArrayOf(0f, -1f, 0f, 0f), 4)
        assertThat(summary.summaryIfDue()).isEqualTo("peak 0.0 dBFS, rms -6.0 dBFS over 1 s")
    }

    @Test
    fun digitalSilenceIsReportedAsSuch() {
        // All-zero capture means "no microphone", not "a weak signal" — the
        // line must say so instead of printing -inf numbers.
        val summary = InputLevelSummary(sampleRate = 100, windowMs = 1000)
        summary.add(FloatArray(100), 100)
        assertThat(summary.summaryIfDue()).isEqualTo("silent (all zero) over 1 s")
    }

    @Test
    fun onlyTheFirstLengthSamplesCount() {
        val summary = InputLevelSummary(sampleRate = 2, windowMs = 1000)
        summary.add(floatArrayOf(0.5f, 0.5f, 1f, 1f), 2)
        assertThat(summary.summaryIfDue()).isEqualTo("peak -6.0 dBFS, rms -6.0 dBFS over 1 s")
    }

    @Test
    fun formatHelpers() {
        assertThat(InputLevelSummary.dbfs(1.0)).isEqualTo("0.0 dBFS")
        assertThat(InputLevelSummary.dbfs(0.001)).isEqualTo("-60.0 dBFS")
        assertThat(InputLevelSummary.dbfs(0.0)).isEqualTo("-inf dBFS")
        assertThat(InputLevelSummary.format(0.25f, 0.1, 30.0))
            .isEqualTo("peak -12.0 dBFS, rms -20.0 dBFS over 30 s")
    }
}
