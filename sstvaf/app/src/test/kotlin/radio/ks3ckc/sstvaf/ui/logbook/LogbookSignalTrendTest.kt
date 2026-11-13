package radio.ks3ckc.sstvaf.ui.logbook

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.log.QSLCallsignRecord
import org.junit.Test
import com.k1af.ft8af.R

/**
 * Unit tests for the logbook's signal-trend chart logic — the real-data
 * replacement for the old fabricated "SNR" sparkline. The chart now plots the
 * strength digit of the received RST/RSV report persisted on each QSO row
 * ([QSLCallsignRecord.rstReceived], ADIF rst_rcvd) and shows an honest
 * placeholder when there aren't enough real reports to draw a line.
 *
 * QSLCallsignRecord is a plain POJO, so these run on the bare JVM with no
 * Robolectric.
 */
class LogbookSignalTrendTest {

    private fun record(
        callsign: String,
        date: String,
        time: String = "120000",
        rstReceived: String? = null,
    ): QSLCallsignRecord {
        val r = QSLCallsignRecord()
        r.setCallsign(callsign)
        r.lastTime = date
        r.timeOn = time
        r.rstReceived = rstReceived
        return r
    }

    // -----------------------------------------------------------------------
    // rsvStrength: which stored reports carry a real strength digit
    // -----------------------------------------------------------------------

    @Test
    fun rsvStrength_readsStrengthDigitFromSstvRsv() {
        // SSTV RSV "595": readability 5, strength 9, video 5.
        assertThat(rsvStrength("595")).isEqualTo(9)
        assertThat(rsvStrength("575")).isEqualTo(7)
        assertThat(rsvStrength("111")).isEqualTo(1)
    }

    @Test
    fun rsvStrength_readsStrengthDigitFromClassicRst() {
        assertThat(rsvStrength("599")).isEqualTo(9)
        assertThat(rsvStrength("59")).isEqualTo(9)
        assertThat(rsvStrength("33")).isEqualTo(3)
    }

    @Test
    fun rsvStrength_trimsWhitespace() {
        assertThat(rsvStrength(" 595 ")).isEqualTo(9)
    }

    @Test
    fun rsvStrength_rejectsEmptyAndNull() {
        assertThat(rsvStrength(null)).isNull()
        assertThat(rsvStrength("")).isNull()
        assertThat(rsvStrength("   ")).isNull()
    }

    @Test
    fun rsvStrength_rejectsFt8StyleDbSnrs() {
        // Signed dB values live on a different scale and must not be mixed
        // onto the 1..9 strength axis.
        assertThat(rsvStrength("-15")).isNull()
        assertThat(rsvStrength("+05")).isNull()
        assertThat(rsvStrength("+00")).isNull()
    }

    @Test
    fun rsvStrength_rejectsNoReportSentinels() {
        assertThat(rsvStrength("-100")).isNull()
        assertThat(rsvStrength("-120")).isNull()
    }

    @Test
    fun rsvStrength_rejectsMalformedShapes() {
        assertThat(rsvStrength("5")).isNull()      // single digit
        assertThat(rsvStrength("5995")).isNull()   // too long
        assertThat(rsvStrength("5x5")).isNull()    // non-digit
        assertThat(rsvStrength("505")).isNull()    // strength 0 does not exist in RST
    }

    // -----------------------------------------------------------------------
    // signalStrengthPoints: real values, chronological, capped, gaps skipped
    // -----------------------------------------------------------------------

    @Test
    fun points_areChronologicalOldestToNewest() {
        val points = signalStrengthPoints(
            listOf(
                record("NEW", "20240615", rstReceived = "595"),
                record("OLD", "20230101", rstReceived = "535"),
                record("MID", "20231231", rstReceived = "575"),
            ),
        )
        assertThat(points).containsExactly(3f, 7f, 9f).inOrder()
    }

    @Test
    fun points_skipRecordsWithoutParsableReport() {
        val points = signalStrengthPoints(
            listOf(
                record("A", "20240101", rstReceived = "595"),
                record("B", "20240102", rstReceived = null),
                record("C", "20240103", rstReceived = "-15"),
                record("D", "20240104", rstReceived = "555"),
            ),
        )
        assertThat(points).containsExactly(9f, 5f).inOrder()
    }

    @Test
    fun points_emptyWhenNoRecordCarriesAReport() {
        val points = signalStrengthPoints(
            listOf(
                record("A", "20240101", rstReceived = null),
                record("B", "20240102", rstReceived = "-100"),
            ),
        )
        assertThat(points).isEmpty()
    }

    @Test
    fun points_capAtMaxPointsKeepingTheMostRecent() {
        // 40 QSOs, strength cycling 1..9; only the newest maxPoints survive,
        // still oldest-to-newest.
        val records = (0 until 40).map { i ->
            record("C$i", "202401%02d".format(i % 28 + 1), "%02d0000".format(i % 24),
                rstReceived = "5${(i % 9) + 1}5")
        }
        val points = signalStrengthPoints(records, maxPoints = 5)
        assertThat(points).hasSize(5)
        // The 5 newest by date+time, reversed to chronological order.
        val expected = sortQsosByDateTimeDesc(records)
            .take(5)
            .reversed()
            .map { (rsvStrength(it.rstReceived))!!.toFloat() }
        assertThat(points).isEqualTo(expected)
    }

    @Test
    fun points_neverFabricated_valuesComeOnlyFromStoredReports() {
        // The old chart synthesized -15f + (index % 20) * 1.2f from the row
        // index; identical reports must now yield an identical flat series.
        val records = (0 until 10).map { i ->
            record("C$i", "2024010${i % 9 + 1}", rstReceived = "595")
        }
        val points = signalStrengthPoints(records)
        assertThat(points.distinct()).containsExactly(9f)
    }

    // -----------------------------------------------------------------------
    // signalTrendPlaceholderRes: honest empty states
    // -----------------------------------------------------------------------

    @Test
    fun placeholder_noQsosAtAll() {
        assertThat(signalTrendPlaceholderRes(hasRecords = false, pointCount = 0))
            .isEqualTo(R.string.log_no_qsos_yet)
    }

    @Test
    fun placeholder_recordsButTooFewRealReports() {
        assertThat(signalTrendPlaceholderRes(hasRecords = true, pointCount = 0))
            .isEqualTo(R.string.log_signal_trend_no_data)
        assertThat(signalTrendPlaceholderRes(hasRecords = true, pointCount = 1))
            .isEqualTo(R.string.log_signal_trend_no_data)
    }

    @Test
    fun placeholder_noneWhenEnoughPointsToDrawALine() {
        assertThat(
            signalTrendPlaceholderRes(hasRecords = true, pointCount = SPARKLINE_MIN_POINTS),
        ).isNull()
        assertThat(signalTrendPlaceholderRes(hasRecords = true, pointCount = 30)).isNull()
    }

    // -----------------------------------------------------------------------
    // strengthYFraction: fixed 1..9 axis geometry
    // -----------------------------------------------------------------------

    @Test
    fun yFraction_usesFixedStrengthAxis() {
        assertThat(strengthYFraction(9f)).isEqualTo(0f)     // S9 at the top
        assertThat(strengthYFraction(1f)).isEqualTo(1f)     // S1 at the bottom
        assertThat(strengthYFraction(5f)).isEqualTo(0.5f)   // S5 mid-chart
    }

    @Test
    fun yFraction_clampsOutOfRangeValues() {
        assertThat(strengthYFraction(0f)).isEqualTo(1f)
        assertThat(strengthYFraction(12f)).isEqualTo(0f)
    }
}
