package radio.ks3ckc.sstvaf.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the Frequency sheet's favourites: what the operator may type
 * for a dial, how the list is edited, and how it survives the config row.
 *
 * Pure logic, no Robolectric: nothing here touches Android types.
 */
class FavoriteFrequenciesTest {

    // ----- parsing what the operator types ------------------------------------

    @Test
    fun input_mhzWithDecimalPoint() {
        assertThat(parseFrequencyInputHz("27.700")).isEqualTo(27_700_000L)
        assertThat(parseFrequencyInputHz("14.23")).isEqualTo(14_230_000L)
        assertThat(parseFrequencyInputHz("3.845")).isEqualTo(3_845_000L)
    }

    @Test
    fun input_mhzWithDecimalComma() {
        // Half of Europe types a comma; it means the same dial.
        assertThat(parseFrequencyInputHz("27,700")).isEqualTo(27_700_000L)
    }

    @Test
    fun input_bareNumberBelowAThousandIsMhz() {
        assertThat(parseFrequencyInputHz("28")).isEqualTo(28_000_000L)
        assertThat(parseFrequencyInputHz("145.5")).isEqualTo(145_500_000L)
    }

    @Test
    fun input_bareNumberInTheThousandsIsKhz() {
        // Nobody saving "27700" means 27.7 kHz.
        assertThat(parseFrequencyInputHz("27700")).isEqualTo(27_700_000L)
        assertThat(parseFrequencyInputHz("14230")).isEqualTo(14_230_000L)
    }

    @Test
    fun input_bareNumberInTheMillionsIsHz() {
        assertThat(parseFrequencyInputHz("27700000")).isEqualTo(27_700_000L)
    }

    @Test
    fun input_decimalSeparatorAlwaysMeansMhz() {
        // "1296.5" is 23cm, not 1.2965 MHz: the magnitude rule only applies to
        // bare integers, where kHz is the far more common thing to type.
        assertThat(parseFrequencyInputHz("1296.5")).isEqualTo(1_296_500_000L)
        assertThat(parseFrequencyInputHz("1296.0")).isEqualTo(1_296_000_000L)
        assertThat(parseFrequencyInputHz("1296,0")).isEqualTo(1_296_000_000L)
        assertThat(parseFrequencyInputHz("2400.100")).isEqualTo(2_400_100_000L)
        // and a bare "1296" is still read as kHz, with the dialog's band echo
        // showing the operator what that resolves to.
        assertThat(parseFrequencyInputHz("1296")).isEqualTo(1_296_000L)
        assertThat(parseFrequencyInputHz("1296 MHz")).isEqualTo(1_296_000_000L)
    }

    @Test
    fun input_explicitUnitWins() {
        assertThat(parseFrequencyInputHz("27700 kHz")).isEqualTo(27_700_000L)
        assertThat(parseFrequencyInputHz("27.7MHz")).isEqualTo(27_700_000L)
        assertThat(parseFrequencyInputHz("27700000 Hz")).isEqualTo(27_700_000L)
        // "27.7 kHz" is a real request for 27.7 kHz, which is below the floor.
        assertThat(parseFrequencyInputHz("27.7 kHz")).isNull()
    }

    @Test
    fun input_toleratesSurroundingWhitespaceAndCase() {
        assertThat(parseFrequencyInputHz("  27.700 MHZ ")).isEqualTo(27_700_000L)
    }

    @Test
    fun input_roundsSubHertzToTheNearestHertz() {
        assertThat(parseFrequencyInputHz("14.2300004")).isEqualTo(14_230_000L)
    }

    @Test
    fun input_rejectsGarbage() {
        assertThat(parseFrequencyInputHz(null)).isNull()
        assertThat(parseFrequencyInputHz("")).isNull()
        assertThat(parseFrequencyInputHz("   ")).isNull()
        assertThat(parseFrequencyInputHz("abc")).isNull()
        assertThat(parseFrequencyInputHz("27.7.0")).isNull()
        assertThat(parseFrequencyInputHz("27,700.5")).isNull()
        assertThat(parseFrequencyInputHz("-14.230")).isNull()
        assertThat(parseFrequencyInputHz("0")).isNull()
        assertThat(parseFrequencyInputHz("MHz")).isNull()
    }

    @Test
    fun input_rejectsDialsNoRigHas() {
        // A slipped digit must not be saved as a frequency.
        assertThat(parseFrequencyInputHz("27700000000")).isNull()
        assertThat(parseFrequencyInputHz("0.05")).isNull()
        // The floor and ceiling themselves are allowed.
        assertThat(parseFrequencyInputHz("0.1")).isEqualTo(FAVORITE_MIN_HZ)
        assertThat(parseFrequencyInputHz("3000 MHz")).isEqualTo(FAVORITE_MAX_HZ)
        assertThat(parseFrequencyInputHz("3000.001 MHz")).isNull()
    }

    @Test
    fun inputText_prefillsTheCurrentDialInMhz() {
        assertThat(favoriteInputText(27_700_000L)).isEqualTo("27.700")
        assertThat(parseFrequencyInputHz(favoriteInputText(14_233_000L))).isEqualTo(14_233_000L)
    }

    @Test
    fun inputText_keepsSubKilohertzPrecision() {
        // A VFO-tuned rig reports 7 171 250 Hz over CAT; pre-filling "7.171"
        // would save (and later retune to) a dial 250 Hz away from where the
        // operator is, and that row would never highlight.
        assertThat(favoriteInputText(7_171_250L)).isEqualTo("7.17125")
        assertThat(favoriteInputText(14_230_001L)).isEqualTo("14.230001")
        for (hz in listOf(7_171_250L, 14_230_001L, 145_500_010L, 100_001L)) {
            assertThat(parseFrequencyInputHz(favoriteInputText(hz))).isEqualTo(hz)
        }
    }

    // ----- dialog state ---------------------------------------------------------

    @Test
    fun dialogState_blankFieldIsNotAnError() {
        val st = favoriteInputState("", emptyList())
        assertThat(st.freqHz).isNull()
        assertThat(st.showError).isFalse()
        assertThat(st.existing).isNull()
    }

    @Test
    fun dialogState_garbageIsAnError() {
        val st = favoriteInputState("abc", emptyList())
        assertThat(st.freqHz).isNull()
        assertThat(st.showError).isTrue()
    }

    @Test
    fun dialogState_findsTheFavouriteAlreadyOnThatDial() {
        val saved = FavoriteFrequency(27_555_000L, "11m group")
        val st = favoriteInputState("27,555", listOf(saved))
        assertThat(st.freqHz).isEqualTo(27_555_000L)
        assertThat(st.showError).isFalse()
        assertThat(st.existing).isEqualTo(saved)
        assertThat(favoriteInputState("27.556", listOf(saved)).existing).isNull()
    }

    @Test
    fun label_blankOnUpdateKeepsTheExistingName() {
        val saved = FavoriteFrequency(27_555_000L, "11m group")
        assertThat(resolveFavoriteLabel("", saved)).isEqualTo("11m group")
        assertThat(resolveFavoriteLabel("   ", saved)).isEqualTo("11m group")
        assertThat(resolveFavoriteLabel("new name", saved)).isEqualTo("new name")
        assertThat(resolveFavoriteLabel("", null)).isEmpty()
    }

    // ----- band-list lookup for a tap ---------------------------------------------

    @Test
    fun bandIndex_usesTheExactEntryWithoutAppending() {
        var appended = false
        val idx = bandIndexForDial(27_700_000L, listOf(14_230_000L, 27_700_000L)) { appended = true; -1 }
        assertThat(idx).isEqualTo(1)
        assertThat(appended).isFalse()
    }

    @Test
    fun bandIndex_appendsOnlyWhenNoEntryExists() {
        var appendedHz: Long? = null
        val idx = bandIndexForDial(27_555_000L, listOf(14_230_000L, 27_700_000L)) { hz ->
            appendedHz = hz
            2
        }
        assertThat(idx).isEqualTo(2)
        assertThat(appendedHz).isEqualTo(27_555_000L)
    }

    // ----- labels -------------------------------------------------------------

    @Test
    fun label_isTrimmedAndFlattened() {
        assertThat(sanitizeFavoriteLabel("  11m group \n")).isEqualTo("11m group")
        assertThat(sanitizeFavoriteLabel("line\r\nbreak")).isEqualTo("line  break")
        assertThat(sanitizeFavoriteLabel(null)).isEmpty()
    }

    @Test
    fun label_cannotContainTheEntrySeparator() {
        // "|" splits entries on disk; a label carrying one would corrupt the row.
        val label = sanitizeFavoriteLabel("a|b")
        assertThat(label).doesNotContain("|")
        assertThat(parseFavoriteFrequencies(serializeFavoriteFrequencies(listOf(FavoriteFrequency(27_700_000L, "a|b")))))
            .hasSize(1)
    }

    @Test
    fun label_isCutToTheMaximumLength() {
        val long = "x".repeat(FAVORITE_LABEL_MAX_LENGTH + 10)
        assertThat(sanitizeFavoriteLabel(long)).hasLength(FAVORITE_LABEL_MAX_LENGTH)
    }

    // ----- list edits ---------------------------------------------------------

    @Test
    fun add_keepsTheListInDialOrder() {
        var list = emptyList<FavoriteFrequency>()
        list = addFavoriteFrequency(list, FavoriteFrequency(27_700_000L, "11m"))
        list = addFavoriteFrequency(list, FavoriteFrequency(3_730_000L, "80m alt"))
        list = addFavoriteFrequency(list, FavoriteFrequency(14_233_000L))
        assertThat(list.map { it.freqHz }).containsExactly(3_730_000L, 14_233_000L, 27_700_000L).inOrder()
    }

    @Test
    fun add_replacesAnExistingDialInsteadOfDuplicating() {
        // Two rows on one frequency would both highlight when it is dialled in.
        val list = addFavoriteFrequency(
            listOf(FavoriteFrequency(27_700_000L, "old")),
            FavoriteFrequency(27_700_000L, "new"),
        )
        assertThat(list).containsExactly(FavoriteFrequency(27_700_000L, "new"))
    }

    @Test
    fun add_sanitizesTheLabel() {
        val list = addFavoriteFrequency(emptyList(), FavoriteFrequency(27_700_000L, "  net|night  "))
        assertThat(list.single().label).isEqualTo("net night")
    }

    @Test
    fun remove_dropsOnlyThatDial() {
        val list = listOf(FavoriteFrequency(3_730_000L), FavoriteFrequency(27_700_000L))
        assertThat(removeFavoriteFrequency(list, 27_700_000L)).containsExactly(FavoriteFrequency(3_730_000L))
        assertThat(removeFavoriteFrequency(list, 99L)).isEqualTo(list)
    }

    // ----- the config row -----------------------------------------------------

    @Test
    fun serialize_roundTrips() {
        val list = listOf(
            FavoriteFrequency(3_730_000L, ""),
            FavoriteFrequency(14_233_000L, "20m alt"),
            FavoriteFrequency(27_700_000L, "11m: Tuesday net"),
        )
        val text = serializeFavoriteFrequencies(list)
        assertThat(text).isEqualTo("3730000:|14233000:20m alt|27700000:11m: Tuesday net")
        assertThat(parseFavoriteFrequencies(text)).isEqualTo(list)
    }

    @Test
    fun parse_emptyRowIsNoFavourites() {
        assertThat(parseFavoriteFrequencies(null)).isEmpty()
        assertThat(parseFavoriteFrequencies("")).isEmpty()
        assertThat(parseFavoriteFrequencies("   ")).isEmpty()
        assertThat(serializeFavoriteFrequencies(emptyList())).isEmpty()
    }

    @Test
    fun parse_skipsMalformedEntriesAndKeepsTheRest() {
        // A hand-edited or partly corrupted backup loses the bad row, not the list.
        val parsed = parseFavoriteFrequencies("garbage|:no dial|27700000:11m|12abc:x|99:too low|")
        assertThat(parsed).containsExactly(FavoriteFrequency(27_700_000L, "11m"))
    }

    @Test
    fun parse_acceptsAnEntryWithNoLabelField() {
        assertThat(parseFavoriteFrequencies("27700000")).containsExactly(FavoriteFrequency(27_700_000L, ""))
    }

    @Test
    fun parse_dedupesAndSortsWhateverIsOnDisk() {
        val parsed = parseFavoriteFrequencies("27700000:a|3730000:b|27700000:c")
        assertThat(parsed.map { it.freqHz }).containsExactly(3_730_000L, 27_700_000L).inOrder()
        assertThat(parsed.last().label).isEqualTo("c")
    }
}
