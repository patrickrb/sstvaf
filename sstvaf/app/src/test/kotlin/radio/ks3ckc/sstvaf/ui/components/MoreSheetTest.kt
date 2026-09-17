package radio.ks3ckc.sstvaf.ui.components

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.database.ControlMode
import org.junit.Test

/**
 * Unit tests for the overflow sheet's summary lines — the sub-labels that let
 * an operator answer "is the rig talking to me?" and "am I set up?" from the
 * sheet itself, without opening either screen.
 */
class MoreSheetTest {

    // ----- radio row ---------------------------------------------------------

    @Test
    fun radioSummary_readsRigThenWiringThenKeying() {
        assertThat(radioSummaryLine("IC-705", "USB Cable", "CAT"))
            .isEqualTo("IC-705 · USB Cable · CAT")
    }

    @Test
    fun radioSummary_dropsBlankSegments() {
        // A fresh install has no rig selected yet: show the shorter honest
        // string rather than a row of separators around nothing.
        assertThat(radioSummaryLine("", "USB Cable", "VOX")).isEqualTo("USB Cable · VOX")
        assertThat(radioSummaryLine("IC-705", "", "CAT")).isEqualTo("IC-705 · CAT")
        assertThat(radioSummaryLine("", "", "")).isEmpty()
    }

    @Test
    fun radioSummary_trimsSegments() {
        assertThat(radioSummaryLine("  IC-705 ", " USB Cable", "CAT "))
            .isEqualTo("IC-705 · USB Cable · CAT")
    }

    // ----- operator row ------------------------------------------------------

    @Test
    fun operatorSummary_isCallsignThenGrid() {
        assertThat(operatorSummaryLine("k1af", "fn42", "NO CALL")).isEqualTo("K1AF · FN42")
    }

    @Test
    fun operatorSummary_fallsBackWhenCallsignUnset() {
        // The callsign is the one field that must be set before transmitting, so
        // its absence has to be visible from the sheet.
        assertThat(operatorSummaryLine("", "FN42", "NO CALL")).isEqualTo("NO CALL · FN42")
        assertThat(operatorSummaryLine("   ", "FN42", "NO CALL")).isEqualTo("NO CALL · FN42")
    }

    @Test
    fun operatorSummary_dropsSeparatorWhenGridUnset() {
        assertThat(operatorSummaryLine("K1AF", "", "NO CALL")).isEqualTo("K1AF")
    }

    @Test
    fun operatorSummary_handlesNeitherFieldSet() {
        assertThat(operatorSummaryLine("", "", "NO CALL")).isEqualTo("NO CALL")
    }

    // ----- control-mode label ------------------------------------------------

    @Test
    fun controlLabel_coversEveryMode() {
        assertThat(controlModeLabel(ControlMode.CAT)).isEqualTo("CAT")
        assertThat(controlModeLabel(ControlMode.RTS)).isEqualTo("RTS")
        assertThat(controlModeLabel(ControlMode.DTR)).isEqualTo("DTR")
        assertThat(controlModeLabel(ControlMode.VOX)).isEqualTo("VOX")
    }

    @Test
    fun controlLabel_treatsAnythingElseAsVox() {
        // An unknown persisted value (hand-edited config, a future mode) must
        // read as the no-control case rather than showing a blank.
        assertThat(controlModeLabel(-1)).isEqualTo("VOX")
        assertThat(controlModeLabel(99)).isEqualTo("VOX")
    }

    // ----- connection state segment ------------------------------------------

    @Test
    fun radioSummary_endsWithTheLiveConnectionState() {
        // Without this segment the row reads identically whether the rig is
        // connected, connecting, disconnected or errored — it describes the
        // configuration, not whether it is working.
        assertThat(radioSummaryLine("IC-705", "USB Cable", "CAT", "connected"))
            .isEqualTo("IC-705 · USB Cable · CAT · connected")
        assertThat(radioSummaryLine("IC-705", "USB Cable", "CAT", "connection error"))
            .isEqualTo("IC-705 · USB Cable · CAT · connection error")
    }

    @Test
    fun radioSummary_distinguishesStatesThatShareAConfiguration() {
        val connected = radioSummaryLine("IC-705", "USB Cable", "CAT", "connected")
        val disconnected = radioSummaryLine("IC-705", "USB Cable", "CAT", "not connected")
        assertThat(connected).isNotEqualTo(disconnected)
    }

    @Test
    fun radioSummary_dropsABlankConnectionState() {
        assertThat(radioSummaryLine("IC-705", "USB Cable", "CAT", "   "))
            .isEqualTo("IC-705 · USB Cable · CAT")
    }
}
