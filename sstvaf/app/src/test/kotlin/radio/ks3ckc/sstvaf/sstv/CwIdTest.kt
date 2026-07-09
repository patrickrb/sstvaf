package radio.ks3ckc.sstvaf.sstv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure unit tests for the CW station-ID generator (issue #14). No Android /
 * native lib, so no Robolectric runner is needed.
 */
class CwIdTest {

    // ----- dit timing (PARIS) -----

    @Test
    fun ditMsUsesParisTiming() {
        assertThat(CwId.ditMs(20)).isWithin(1e-9).of(60.0) // 1200 / 20
        assertThat(CwId.ditMs(12)).isWithin(1e-9).of(100.0)
        // Guards against divide-by-zero / negative WPM.
        assertThat(CwId.ditMs(0)).isWithin(1e-9).of(1200.0)
    }

    // ----- keying timeline -----

    @Test
    fun singleLetterKeysItsElements() {
        // 'A' is dot-dash: on(1), intra-gap(1), on(3).
        assertThat(CwId.keyingUnits("A")).containsExactly(
            CwId.Segment(keyed = true, units = 1),
            CwId.Segment(keyed = false, units = 1),
            CwId.Segment(keyed = true, units = 3),
        ).inOrder()
    }

    @Test
    fun letterGapIsThreeUnits() {
        // "EE" → dot, inter-char gap(3), dot.
        assertThat(CwId.keyingUnits("EE")).containsExactly(
            CwId.Segment(keyed = true, units = 1),
            CwId.Segment(keyed = false, units = 3),
            CwId.Segment(keyed = true, units = 1),
        ).inOrder()
    }

    @Test
    fun wordGapIsSevenUnits() {
        // "E E" → dot, word gap(7), dot.
        val gap = CwId.keyingUnits("E E").single { !it.keyed }
        assertThat(gap.units).isEqualTo(7)
    }

    @Test
    fun leadingAndTrailingSpacesProduceNoEdgeGaps() {
        assertThat(CwId.keyingUnits("  E  ")).containsExactly(
            CwId.Segment(keyed = true, units = 1),
        )
    }

    @Test
    fun unknownCharactersAreSkipped() {
        // '@' has no Morse code; the result is just the two dots of "EE".
        assertThat(CwId.keyingUnits("E@E").count { it.keyed }).isEqualTo(2)
    }

    @Test
    fun blankOrUncodeableInputIsEmpty() {
        assertThat(CwId.keyingUnits("")).isEmpty()
        assertThat(CwId.keyingUnits("   ")).isEmpty()
        assertThat(CwId.keyingUnits("@#")).isEmpty()
    }

    @Test
    fun lowercaseIsKeyedSameAsUppercase() {
        assertThat(CwId.keyingUnits("cq")).isEqualTo(CwId.keyingUnits("CQ"))
    }

    @Test
    fun caseFoldingIsLocaleIndependent() {
        // International Morse is never localized: in a Turkish locale the JVM
        // default upper-cases 'i' to 'İ' (dotted capital I), which is not in
        // the ASCII Morse table. keyingUnits() must key 'i' regardless.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
            assertThat(CwId.keyingUnits("wi5")).isEqualTo(CwId.keyingUnits("WI5"))
            // 'I' is di-dit: two keyed elements survive the fold.
            assertThat(CwId.keyingUnits("i").count { it.keyed }).isEqualTo(2)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    // ----- audio synthesis -----

    @Test
    fun encodeLengthMatchesTimeline() {
        // 'E' at 20 WPM, 8000 Hz: one 1-unit dot = 60 ms = 480 samples.
        val samples = CwId.encode("E", sampleRate = 8000, wpm = 20)
        assertThat(samples.size).isEqualTo(480)
        assertThat(samples.any { it != 0f }).isTrue()
    }

    @Test
    fun encodeReturnsEmptyForBlank() {
        assertThat(CwId.encode("", sampleRate = 8000, wpm = 20)).isEmpty()
    }

    @Test
    fun encodeStaysWithinAmplitude() {
        val samples = CwId.encode("K1ABC/P", sampleRate = 8000, wpm = 20)
        assertThat(samples.isNotEmpty()).isTrue()
        assertThat(samples.all { kotlin.math.abs(it) <= CwId.AMPLITUDE + 1e-4f }).isTrue()
    }

    @Test
    fun encodeClampsSpeedToMaxWpm() {
        // Anything above MAX_WPM keys at MAX_WPM, so 25 and 20 are identical.
        val fast = CwId.encode("K", sampleRate = 8000, wpm = 25)
        val capped = CwId.encode("K", sampleRate = 8000, wpm = CwId.MAX_WPM)
        assertThat(fast.size).isEqualTo(capped.size)
    }

    // ----- appendTo -----

    @Test
    fun appendToDisabledReturnsSameBuffer() {
        val image = FloatArray(1000) { 0.1f }
        val out = CwId.appendTo(image, CwIdSettings(enabled = false, text = "K1ABC", wpm = 20), 8000)
        assertThat(out).isSameInstanceAs(image)
    }

    @Test
    fun appendToBlankCallReturnsSameBuffer() {
        val image = FloatArray(1000) { 0.1f }
        val out = CwId.appendTo(image, CwIdSettings(enabled = true, text = "   ", wpm = 20), 8000)
        assertThat(out).isSameInstanceAs(image)
    }

    @Test
    fun appendToAddsGapThenTone() {
        val image = FloatArray(1000) { 0.1f }
        val out = CwId.appendTo(image, CwIdSettings(enabled = true, text = "E", wpm = 20), 8000)
        val gap = CwId.LEAD_GAP_MS * 8000 / 1000 // 5600
        val cw = CwId.encode("E", 8000, 20)
        assertThat(out.size).isEqualTo(image.size + gap + cw.size)
        // The image audio is preserved verbatim at the head (leading audio rule).
        assertThat(out.copyOfRange(0, image.size)).isEqualTo(image)
        // The separating gap is silence.
        assertThat(out.copyOfRange(image.size, image.size + gap).all { it == 0f }).isTrue()
        // The tone follows the gap.
        assertThat(out.copyOfRange(image.size + gap, out.size).any { it != 0f }).isTrue()
    }
}
