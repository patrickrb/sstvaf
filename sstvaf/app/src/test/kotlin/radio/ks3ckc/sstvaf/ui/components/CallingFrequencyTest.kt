package radio.ks3ckc.sstvaf.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the Frequency sheet's calling-frequency table and its lookup
 * into the app's band list.
 *
 * The table is pinned to the five dials SSTV activity actually lives on. Each
 * one must resolve to a real `bands.txt` entry, because a row that resolves to
 * nothing would silently tune the operator nowhere.
 */
class CallingFrequencyTest {

    /** The band-list frequencies shipped in `assets/bands.txt`, in file order. */
    private val bandsTxtFreqs = listOf(
        1_890_000L,   // 160m
        3_845_000L,   // 80m  *
        3_730_000L,   // 80m
        7_171_000L,   // 40m  *
        7_165_000L,   // 40m
        14_230_000L,  // 20m  *
        14_233_000L,  // 20m
        14_236_000L,  // 20m
        21_340_000L,  // 15m  *
        28_680_000L,  // 10m  *
        28_700_000L,  // 10m
        50_680_000L,  // 6m
    )

    // ----- the table ---------------------------------------------------------

    @Test
    fun table_isTheFiveSstvCallingFrequencies() {
        assertThat(SSTV_CALLING_FREQUENCIES.map { it.band })
            .containsExactly("80m", "40m", "20m", "15m", "10m")
            .inOrder()
    }

    @Test
    fun table_carriesTheConventionalDials() {
        val byBand = SSTV_CALLING_FREQUENCIES.associate { it.band to it.freqHz }
        assertThat(byBand["80m"]).isEqualTo(3_845_000L)
        assertThat(byBand["40m"]).isEqualTo(7_171_000L)
        assertThat(byBand["20m"]).isEqualTo(14_230_000L)
        assertThat(byBand["15m"]).isEqualTo(21_340_000L)
        assertThat(byBand["10m"]).isEqualTo(28_680_000L)
    }

    @Test
    fun table_isOrderedLowBandToHigh() {
        val freqs = SSTV_CALLING_FREQUENCIES.map { it.freqHz }
        assertThat(freqs).isInOrder()
    }

    @Test
    fun table_givesEveryRowItsOwnNote() {
        val notes = SSTV_CALLING_FREQUENCIES.map { it.noteRes }
        assertThat(notes).containsNoDuplicates()
    }

    // ----- band-list lookup --------------------------------------------------

    @Test
    fun lookup_resolvesEveryCallingFrequency() {
        // Every dial in the table is a marked primary entry in bands.txt, so no
        // row ever has to fall back to appending a synthetic band.
        for (row in SSTV_CALLING_FREQUENCIES) {
            assertThat(callingFrequencyBandIndex(row.freqHz, bandsTxtFreqs))
                .isAtLeast(0)
        }
    }

    @Test
    fun lookup_findsTheExactEntry() {
        assertThat(callingFrequencyBandIndex(14_230_000L, bandsTxtFreqs)).isEqualTo(5)
        assertThat(callingFrequencyBandIndex(3_845_000L, bandsTxtFreqs)).isEqualTo(1)
    }

    @Test
    fun lookup_prefersTheFirstMatch() {
        // Duplicate dials (a hand-edited bands.txt) resolve to the first, so the
        // selection is deterministic.
        assertThat(callingFrequencyBandIndex(7_000_000L, listOf(7_000_000L, 7_000_000L)))
            .isEqualTo(0)
    }

    @Test
    fun lookup_reportsMissWithoutGrowingTheList() {
        // -1, not an appended entry: this runs on every recomposition to decide
        // which row is highlighted, so it must never mutate the band list.
        val freqs = bandsTxtFreqs.toList()
        assertThat(callingFrequencyBandIndex(18_100_000L, freqs)).isEqualTo(-1)
        assertThat(freqs).hasSize(bandsTxtFreqs.size)
    }

    @Test
    fun lookup_handlesAnEmptyBandList() {
        assertThat(callingFrequencyBandIndex(14_230_000L, emptyList())).isEqualTo(-1)
    }

    // ----- selected row ------------------------------------------------------

    @Test
    fun selection_matchesTheDialledFrequency() {
        val twenty = SSTV_CALLING_FREQUENCIES.first { it.band == "20m" }
        assertThat(isCallingFrequencySelected(twenty, 14_230_000L)).isTrue()
        assertThat(isCallingFrequencySelected(twenty, 14_233_000L)).isFalse()
    }

    @Test
    fun selection_isExclusive() {
        // Sitting on one calling frequency highlights exactly one row.
        val selected = SSTV_CALLING_FREQUENCIES.filter {
            isCallingFrequencySelected(it, 14_230_000L)
        }
        assertThat(selected).hasSize(1)
    }

    @Test
    fun selection_highlightsNothingOffTheCallingFrequencies() {
        // Tuned somewhere else entirely (or to a 20m alternate): no row claims it.
        val selected = SSTV_CALLING_FREQUENCIES.filter {
            isCallingFrequencySelected(it, 14_236_000L)
        }
        assertThat(selected).isEmpty()
    }
}
