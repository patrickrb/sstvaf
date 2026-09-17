package radio.ks3ckc.sstvaf.ui.components

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.database.OperationBand
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the Frequency sheet's calling-frequency table and its lookup
 * into the app's band list.
 *
 * The table is pinned to the five dials SSTV activity actually lives on. Each
 * one must resolve to a real `bands.txt` entry, because a row that resolves to
 * nothing would silently tune the operator nowhere.
 */
@RunWith(RobolectricTestRunner::class)
class CallingFrequencyTest {

    /**
     * The real `assets/bands.txt`, read through the production loader.
     *
     * Not a hardcoded copy of the asset. The invariant being tested is that
     * every row in the sheet resolves to a shipped band entry, and a duplicated
     * list cannot fail when the asset is the thing that drifts: drop 21.340 from
     * `bands.txt` and a copy-based test still passes while the 15m row tunes the
     * operator nowhere. Robolectric serves the app's real assets here because
     * `unitTests.includeAndroidResources` is on.
     */
    private fun bandsTxtFreqs(): List<Long> {
        OperationBand(ApplicationProvider.getApplicationContext())
        return OperationBand.bandList.map { it.band }
    }

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
    fun lookup_resolvesEveryCallingFrequencyInTheShippedAsset() {
        // Every dial in the table is a marked primary entry in bands.txt, so no
        // row ever has to fall back to appending a synthetic band.
        val freqs = bandsTxtFreqs()
        assertThat(freqs).isNotEmpty()
        for (row in SSTV_CALLING_FREQUENCIES) {
            assertThat(callingFrequencyBandIndex(row.freqHz, freqs)).isAtLeast(0)
        }
    }

    @Test
    fun lookup_findsTheExactEntry() {
        val freqs = bandsTxtFreqs()
        assertThat(freqs[callingFrequencyBandIndex(14_230_000L, freqs)]).isEqualTo(14_230_000L)
        assertThat(freqs[callingFrequencyBandIndex(3_845_000L, freqs)]).isEqualTo(3_845_000L)
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
        val freqs = bandsTxtFreqs()
        val size = freqs.size
        assertThat(callingFrequencyBandIndex(18_100_000L, freqs)).isEqualTo(-1)
        assertThat(freqs).hasSize(size)
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

    // ----- excluded bands ----------------------------------------------------

    @Test
    fun visibleRows_dropTheOperatorsExcludedBands() {
        val visible = visibleCallingFrequencies(SSTV_CALLING_FREQUENCIES, setOf("80m", "10m"))
        assertThat(visible.map { it.band }).containsExactly("40m", "20m", "15m").inOrder()
    }

    @Test
    fun visibleRows_areUnchangedWithNoExclusions() {
        assertThat(visibleCallingFrequencies(SSTV_CALLING_FREQUENCIES, emptySet()))
            .isEqualTo(SSTV_CALLING_FREQUENCIES)
    }

    @Test
    fun visibleRows_ignoreExclusionsForBandsNotInTheTable() {
        // 160m/6m/2m are in bands.txt but never in this sheet; excluding them
        // must not disturb the five rows.
        assertThat(visibleCallingFrequencies(SSTV_CALLING_FREQUENCIES, setOf("160m", "6m", "2m")))
            .isEqualTo(SSTV_CALLING_FREQUENCIES)
    }

    @Test
    fun visibleRows_fallBackToEveryRowWhenAllAreExcluded() {
        // An empty sheet would be a dead end: the chip's only purpose is
        // retuning, and there would be no row left to retune with.
        val all = SSTV_CALLING_FREQUENCIES.map { it.band }.toSet()
        assertThat(visibleCallingFrequencies(SSTV_CALLING_FREQUENCIES, all))
            .isEqualTo(SSTV_CALLING_FREQUENCIES)
    }

    @Test
    fun visibleRows_matchTheSettingsExclusionPredicate() {
        // Same source of truth as Radio & audio's band picker.
        GeneralVariables.excludedBands = java.util.HashSet(listOf("20m"))
        try {
            val visible = visibleCallingFrequencies(
                SSTV_CALLING_FREQUENCIES, GeneralVariables.excludedBands,
            )
            assertThat(visible.map { it.band }).doesNotContain("20m")
            assertThat(GeneralVariables.isBandExcluded("20m")).isTrue()
        } finally {
            GeneralVariables.excludedBands = java.util.HashSet()
        }
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
