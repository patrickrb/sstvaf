package radio.ks3ckc.sstvaf.ui.logbook

import com.k1af.ft8af.log.QSLCallsignRecord
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [filterQsosByQuery] / [qsoMatchesQuery], the pure text search
 * extracted from RecentTab so an operator can find a station in a long logbook.
 *
 * The match is case-insensitive and spans callsign, grid, band+frequency, and
 * DXCC. QSLCallsignRecord is a plain POJO, so these run on the bare JVM with no
 * Robolectric.
 */
class LogbookSearchTest {

    private fun record(
        callsign: String,
        grid: String = "",
        band: String = "",
        dxcc: String = "",
    ): QSLCallsignRecord {
        val r = QSLCallsignRecord()
        r.setCallsign(callsign)
        r.setGrid(grid)
        r.setBand(band)
        r.dxccStr = dxcc
        return r
    }

    private fun calls(records: List<QSLCallsignRecord>): List<String?> =
        records.map { it.callsign }

    @Test
    fun blankQueryReturnsEverythingUnchanged() {
        val records = listOf(record("K1ABC"), record("W2XYZ"))
        // Same instance back — the no-search path must not allocate/copy.
        assertThat(filterQsosByQuery(records, "")).isSameInstanceAs(records)
        assertThat(filterQsosByQuery(records, "   ")).isSameInstanceAs(records)
    }

    @Test
    fun matchesPartialCallsignCaseInsensitively() {
        val records = listOf(record("K1ABC"), record("W2XYZ"), record("K1DEF"))
        assertThat(calls(filterQsosByQuery(records, "k1")))
            .containsExactly("K1ABC", "K1DEF").inOrder()
    }

    @Test
    fun matchesGrid() {
        val records = listOf(
            record("K1ABC", grid = "FN31"),
            record("W2XYZ", grid = "EM12"),
        )
        assertThat(calls(filterQsosByQuery(records, "fn31"))).containsExactly("K1ABC")
    }

    @Test
    fun matchesBandMeter() {
        val records = listOf(
            record("K1ABC", band = "20M(14.230 MHz)"),
            record("W2XYZ", band = "40M(7.171 MHz)"),
        )
        assertThat(calls(filterQsosByQuery(records, "20m"))).containsExactly("K1ABC")
    }

    @Test
    fun matchesFrequencyInsidePackedBandColumn() {
        // The band column packs the frequency, so searching a dial freq works.
        val records = listOf(
            record("K1ABC", band = "20M(14.230 MHz)"),
            record("W2XYZ", band = "40M(7.171 MHz)"),
        )
        assertThat(calls(filterQsosByQuery(records, "7.171"))).containsExactly("W2XYZ")
    }

    @Test
    fun matchesDxccCountry() {
        val records = listOf(
            record("K1ABC", dxcc = "United States"),
            record("G0XYZ", dxcc = "England"),
        )
        assertThat(calls(filterQsosByQuery(records, "england"))).containsExactly("G0XYZ")
    }

    @Test
    fun queryIsTrimmedBeforeMatching() {
        val records = listOf(record("K1ABC"), record("W2XYZ"))
        assertThat(calls(filterQsosByQuery(records, "  k1abc  "))).containsExactly("K1ABC")
    }

    @Test
    fun noMatchReturnsEmpty() {
        val records = listOf(record("K1ABC"), record("W2XYZ"))
        assertThat(filterQsosByQuery(records, "ZZZ")).isEmpty()
    }

    @Test
    fun needleDoesNotMatchAcrossFieldBoundaries() {
        // "ABCFN31" would only exist if callsign+grid were concatenated; the
        // per-field test must not create such a phantom match.
        val records = listOf(record("K1ABC", grid = "FN31"))
        assertThat(filterQsosByQuery(records, "ABCFN31")).isEmpty()
        // Each field on its own still matches.
        assertThat(filterQsosByQuery(records, "ABC")).hasSize(1)
        assertThat(filterQsosByQuery(records, "FN31")).hasSize(1)
    }

    @Test
    fun nullFieldsDoNotCrash() {
        // A freshly constructed record has null grid/band until set; a match on
        // callsign must still succeed and a non-match must not throw.
        val r = QSLCallsignRecord()
        r.setCallsign("K1ABC")
        val records = listOf(r)
        assertThat(calls(filterQsosByQuery(records, "k1"))).containsExactly("K1ABC")
        assertThat(filterQsosByQuery(records, "fn31")).isEmpty()
    }

    @Test
    fun emptyNeedleMatchesEveryRecordThroughHelper() {
        assertThat(qsoMatchesQuery(record("K1ABC"), "")).isTrue()
    }
}
