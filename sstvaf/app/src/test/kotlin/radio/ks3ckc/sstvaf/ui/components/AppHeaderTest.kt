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
 * Unit tests for the header file's pure logic: the colour of the CAT status
 * dot and its text counterpart (both consumed by [BandBar] and the Frequency
 * sheet). No Android runtime needed — the theme colours are plain values and
 * the control modes are int constants. The dial label moved to the band bar;
 * see BandBarTest.
 */
class AppHeaderTest {

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
