package radio.ks3ckc.sstvaf.ui.logbook

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.sstvaf.sstv.SstvMode

/**
 * Manual SSTV QSO entry logic: field validation, MHz parsing, and the
 * QSLRecord the sheet saves. Robolectric because validation reaches
 * [com.k1af.ft8af.maidenhead.MaidenheadGrid] and record construction reaches
 * android.util.Log via QSLRecord's collaborators.
 */
@RunWith(RobolectricTestRunner::class)
class ManualQsoLogicTest {

    private fun input(
        callsign: String = "TEST1",
        grid: String = "",
        rsvSent: String = DEFAULT_RSV,
        rsvReceived: String = DEFAULT_RSV,
        freqMhz: String = "14.230",
        mode: SstvMode = SstvMode.SCOTTIE_1,
        comment: String = "",
    ) = ManualQsoInput(callsign, grid, rsvSent, rsvReceived, freqMhz, mode, comment)

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    @Test
    fun `default form with a callsign is valid`() {
        assertThat(validateManualQso(input())).isEmpty()
    }

    @Test
    fun `blank callsign fails`() {
        assertThat(validateManualQso(input(callsign = "   ")))
            .containsExactly(ManualQsoField.CALLSIGN)
    }

    @Test
    fun `empty grid is allowed but junk grid fails`() {
        assertThat(validateManualQso(input(grid = ""))).isEmpty()
        assertThat(validateManualQso(input(grid = "EM28"))).isEmpty()
        assertThat(validateManualQso(input(grid = "EM28ox"))).isEmpty()
        assertThat(validateManualQso(input(grid = "not-a-grid")))
            .containsExactly(ManualQsoField.GRID)
        assertThat(validateManualQso(input(grid = "12AB")))
            .containsExactly(ManualQsoField.GRID)
    }

    @Test
    fun `rsv must be three digits in rst ranges`() {
        assertThat(isValidRsv("595")).isTrue()
        assertThat(isValidRsv("111")).isTrue()
        assertThat(isValidRsv(" 595 ")).isTrue() // trimmed
        assertThat(isValidRsv("59")).isFalse() // too short
        assertThat(isValidRsv("5955")).isFalse() // too long
        assertThat(isValidRsv("695")).isFalse() // readability > 5
        assertThat(isValidRsv("505")).isFalse() // zero strength
        assertThat(isValidRsv("590")).isFalse() // zero video
        assertThat(isValidRsv("")).isFalse()
        assertThat(isValidRsv("abc")).isFalse()
    }

    @Test
    fun `bad rsv and bad frequency are reported per field`() {
        val errors = validateManualQso(
            input(rsvSent = "999", rsvReceived = "x", freqMhz = "zero"),
        )
        assertThat(errors).containsExactly(
            ManualQsoField.RSV_SENT,
            ManualQsoField.RSV_RECEIVED,
            ManualQsoField.FREQUENCY,
        )
    }

    // -----------------------------------------------------------------------
    // MHz parsing / formatting
    // -----------------------------------------------------------------------

    @Test
    fun `parseMhzToHz handles typical dials`() {
        assertThat(parseMhzToHz("14.230")).isEqualTo(14_230_000L)
        assertThat(parseMhzToHz("7.171")).isEqualTo(7_171_000L)
        assertThat(parseMhzToHz(" 28.680 ")).isEqualTo(28_680_000L)
        assertThat(parseMhzToHz("14")).isEqualTo(14_000_000L)
    }

    @Test
    fun `parseMhzToHz rejects junk and out-of-range values`() {
        assertThat(parseMhzToHz("")).isNull()
        assertThat(parseMhzToHz("abc")).isNull()
        assertThat(parseMhzToHz("0")).isNull()
        assertThat(parseMhzToHz("-14.230")).isNull()
        assertThat(parseMhzToHz("999999")).isNull()
    }

    @Test
    fun `defaultFreqMhzText renders whole-kHz dials with three decimals`() {
        assertThat(defaultFreqMhzText(14_230_000L)).isEqualTo("14.230")
        assertThat(defaultFreqMhzText(7_171_000L)).isEqualTo("7.171")
        // Sub-kHz dial keeps full resolution.
        assertThat(defaultFreqMhzText(14_230_500L)).isEqualTo("14.230500")
    }

    @Test
    fun `prefill text round-trips through the parser`() {
        val hz = 14_230_000L
        assertThat(parseMhzToHz(defaultFreqMhzText(hz))).isEqualTo(hz)
    }

    // -----------------------------------------------------------------------
    // Record construction
    // -----------------------------------------------------------------------

    @Test
    fun `record carries SSTV mode with submode and normalized callsign`() {
        val record = buildManualQsoRecord(
            input = input(callsign = " test1 ", grid = "em29", mode = SstvMode.ROBOT_36),
            nowMillis = 0L, // 1970-01-01 00:00:00 UTC — timezone-stable
            myCallsign = "KS3CKC",
            myGrid = "EM28",
        )

        assertThat(record.toCallsign).isEqualTo("TEST1")
        assertThat(record.toMaidenGrid).isEqualTo("em29")
        assertThat(record.mode).isEqualTo("SSTV")
        assertThat(record.submode).isEqualTo("Robot 36")
        assertThat(record.bandFreq).isEqualTo(14_230_000L)
        assertThat(record.bandLength).isEqualTo("20m")
        assertThat(record.sendReport).isEqualTo(595)
        assertThat(record.receivedReport).isEqualTo(595)
        assertThat(record.qso_date).isEqualTo("19700101")
        assertThat(record.time_on).isEqualTo("000000")
        assertThat(record.myCallsign).isEqualTo("KS3CKC")
        assertThat(record.myMaidenGrid).isEqualTo("EM28")
    }

    @Test
    fun `blank comment keeps the constructor default, typed comment wins`() {
        val defaulted = buildManualQsoRecord(input(), 0L, "KS3CKC", "")
        assertThat(defaulted.comment).isEqualTo("QSO by SSTVAF")

        val typed = buildManualQsoRecord(input(comment = " First SSTV contact "), 0L, "KS3CKC", "")
        assertThat(typed.comment).isEqualTo("First SSTV contact")
    }

    @Test
    fun `every sstv mode maps its display name into submode`() {
        for (mode in SstvMode.entries) {
            val record = buildManualQsoRecord(input(mode = mode), 0L, "KS3CKC", "")
            assertThat(record.submode).isEqualTo(mode.displayName)
        }
    }
}
