package radio.ks3ckc.sstvaf.ui.components

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.database.ControlMode
import com.k1af.ft8af.R
import com.k1af.ft8af.rigs.CatConnectionState
import org.junit.Test
import radio.ks3ckc.sstvaf.theme.StatusBad
import radio.ks3ckc.sstvaf.theme.StatusConfirmed
import radio.ks3ckc.sstvaf.theme.StatusWarn
import radio.ks3ckc.sstvaf.theme.TextMuted

/**
 * Unit tests for the app header's pure logic: the dial chip's label and the
 * colour of its CAT status dot. No Android runtime needed — the theme colours
 * are plain values and the control modes are int constants.
 */
class AppHeaderTest {

    // ----- chip label --------------------------------------------------------

    @Test
    fun chipLabel_isFrequencyThenBand() {
        assertThat(frequencyChipLabel(14_230_000L, "20m")).isEqualTo("14.230 · 20m")
    }

    @Test
    fun chipLabel_keepsKhzResolution() {
        // Three decimals: SSTV calling frequencies are specified to the kHz and
        // an operator recognises "7.171" at a glance.
        assertThat(frequencyChipLabel(7_171_000L, "40m")).isEqualTo("7.171 · 40m")
        assertThat(frequencyChipLabel(3_845_000L, "80m")).isEqualTo("3.845 · 80m")
        assertThat(frequencyChipLabel(28_680_000L, "10m")).isEqualTo("28.680 · 10m")
    }

    @Test
    fun chipLabel_roundsToNearestKhz() {
        // A dial reported with Hz precision (CAT readback) must not spill extra
        // digits into a chip sized for eight characters.
        assertThat(frequencyChipLabel(14_230_400L, "20m")).isEqualTo("14.230 · 20m")
        assertThat(frequencyChipLabel(14_230_600L, "20m")).isEqualTo("14.231 · 20m")
    }

    @Test
    fun chipLabel_dropsSeparatorWhenBandUnknown() {
        // Out-of-band dial: no band name, so no dangling separator.
        assertThat(frequencyChipLabel(14_230_000L, "")).isEqualTo("14.230")
        assertThat(frequencyChipLabel(14_230_000L, "   ")).isEqualTo("14.230")
    }

    @Test
    fun chipLabel_hasNoUnitSuffix() {
        // The chip shares a 360dp-wide header with a 22sp title and two buttons;
        // "MHz" is implied by the format and was dropped on purpose.
        assertThat(frequencyChipLabel(14_230_000L, "20m")).doesNotContain("MHz")
    }

    // ----- CAT status dot ----------------------------------------------------

    @Test
    fun dot_isGreenWhenConnected() {
        assertThat(headerCatDotColor(ControlMode.CAT, CatConnectionState.CONNECTED))
            .isEqualTo(StatusConfirmed)
    }

    @Test
    fun dot_isAmberWhileConnecting() {
        assertThat(headerCatDotColor(ControlMode.CAT, CatConnectionState.CONNECTING))
            .isEqualTo(StatusWarn)
    }

    @Test
    fun dot_isRedOnError() {
        assertThat(headerCatDotColor(ControlMode.CAT, CatConnectionState.ERROR))
            .isEqualTo(StatusBad)
    }

    @Test
    fun dot_isMutedWhenDisconnected() {
        assertThat(headerCatDotColor(ControlMode.CAT, CatConnectionState.DISCONNECTED))
            .isEqualTo(TextMuted)
    }

    @Test
    fun dot_isMutedForVoxInEveryState() {
        // VOX is audio-only: there is no control link, so no state of one can be
        // reported. A red dot here would be flagging the absence of something
        // the operator switched off deliberately.
        for (state in CatConnectionState.entries) {
            assertThat(headerCatDotColor(ControlMode.VOX, state)).isEqualTo(TextMuted)
        }
    }

    @Test
    fun dot_reportsStateForEveryNonVoxControlMode() {
        for (mode in listOf(ControlMode.CAT, ControlMode.RTS, ControlMode.DTR)) {
            assertThat(headerCatDotColor(mode, CatConnectionState.CONNECTED))
                .isEqualTo(StatusConfirmed)
        }
    }

    // ----- CAT state description ---------------------------------------------

    @Test
    fun catStateDescription_namesEveryConnectionState() {
        assertThat(catStateDescriptionRes(ControlMode.CAT, CatConnectionState.CONNECTED))
            .isEqualTo(R.string.cat_state_connected)
        assertThat(catStateDescriptionRes(ControlMode.CAT, CatConnectionState.CONNECTING))
            .isEqualTo(R.string.cat_state_connecting)
        assertThat(catStateDescriptionRes(ControlMode.CAT, CatConnectionState.DISCONNECTED))
            .isEqualTo(R.string.cat_state_disconnected)
        assertThat(catStateDescriptionRes(ControlMode.CAT, CatConnectionState.ERROR))
            .isEqualTo(R.string.cat_state_error)
    }

    @Test
    fun catStateDescription_distinguishesEveryState() {
        // The point of the description: four states that the dot renders as
        // colour alone must be four different strings, or TalkBack cannot tell
        // them apart.
        val all = CatConnectionState.values().map {
            catStateDescriptionRes(ControlMode.CAT, it)
        }
        assertThat(all).containsNoDuplicates()
    }

    @Test
    fun catStateDescription_reportsVoxAsHavingNoLink() {
        // Matches the muted dot: VOX has no control link to be disconnected
        // from, so "not connected" would report the absence of something the
        // operator switched off on purpose.
        for (state in CatConnectionState.values()) {
            assertThat(catStateDescriptionRes(ControlMode.VOX, state))
                .isEqualTo(R.string.cat_state_vox)
        }
    }

    @Test
    fun catStateDescription_coversTheSameModesAsTheDot() {
        // RTS and DTR key over the same serial link as CAT, so they report the
        // connection state rather than falling into the VOX case.
        for (mode in listOf(ControlMode.CAT, ControlMode.RTS, ControlMode.DTR)) {
            assertThat(catStateDescriptionRes(mode, CatConnectionState.CONNECTED))
                .isEqualTo(R.string.cat_state_connected)
        }
    }
}
