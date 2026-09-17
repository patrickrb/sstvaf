package radio.ks3ckc.sstvaf.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The Settings landing: the category row set and the rig-status line on the
 * operator card.
 */
class SettingsLandingLogicTest {

    @Test
    fun `every category is reachable from the landing`() {
        // The enum is what the landing iterates, so a category added to it
        // without a row would be unreachable — there is no other way in.
        assertThat(SettingsCategory.entries).hasSize(7)
        assertThat(SettingsCategory.entries.first()).isEqualTo(SettingsCategory.RADIO_AUDIO)
    }

    @Test
    fun `radio and audio comes first`() {
        // It holds the settings an operator opens Settings to fix.
        assertThat(SettingsCategory.entries.indexOf(SettingsCategory.RADIO_AUDIO)).isEqualTo(0)
    }

    @Test
    fun `every category has its own label`() {
        val labels = SettingsCategory.entries.map { categoryLabelRes(it) }
        assertThat(labels).containsNoDuplicates()
        assertThat(labels).hasSize(SettingsCategory.entries.size)
    }

    @Test
    fun `every category has its own description`() {
        // The description is what tells an operator which screen holds the
        // setting they want, so a shared one would defeat the row.
        val descriptions = SettingsCategory.entries.map { categoryDescriptionRes(it) }
        assertThat(descriptions).containsNoDuplicates()
        assertThat(descriptions).hasSize(SettingsCategory.entries.size)
    }

    @Test
    fun `a label is never reused as a description`() {
        val labels = SettingsCategory.entries.map { categoryLabelRes(it) }.toSet()
        val descriptions = SettingsCategory.entries.map { categoryDescriptionRes(it) }.toSet()
        assertThat(labels.intersect(descriptions)).isEmpty()
    }

    // ----- the rig status line ------------------------------------------------

    @Test
    fun `a connected rig is named along with how it is controlled`() {
        assertThat(
            rigStatusLine(
                connected = true,
                rigName = "IC-705",
                controlLabel = "CAT",
                connectedFormat = "%1\$s linked over %2\$s",
                idleFormat = "No rig linked · %1\$s",
            ),
        ).isEqualTo("IC-705 linked over CAT")
    }

    @Test
    fun `an idle station still says how it would key`() {
        // With no rig linked the control mode is the one fact that explains
        // whether transmitting will work at all, so it stays on the line.
        assertThat(
            rigStatusLine(
                connected = false,
                rigName = "IC-705",
                controlLabel = "VOX",
                connectedFormat = "%1\$s linked over %2\$s",
                idleFormat = "No rig linked · %1\$s",
            ),
        ).isEqualTo("No rig linked · VOX")
    }

    @Test
    fun `the idle line does not leak the remembered rig name`() {
        // The stored model is still set when nothing is connected; printing it
        // beside "no rig linked" reads as a contradiction.
        val line = rigStatusLine(
            connected = false,
            rigName = "IC-705",
            controlLabel = "CAT",
            connectedFormat = "%1\$s linked over %2\$s",
            idleFormat = "No rig linked · %1\$s",
        )
        assertThat(line).doesNotContain("IC-705")
    }

    // ----- appearance ---------------------------------------------------------

    @Test
    fun `appearance is a category of its own`() {
        // Theme and language used to be two sections inside Advanced, so an
        // operator looking for them had to guess. The landing iterates this
        // enum, so being absent from it meant being unreachable as a row.
        assertThat(SettingsCategory.entries).contains(SettingsCategory.APPEARANCE)
    }

    @Test
    fun `appearance sits before advanced`() {
        // It is the more commonly wanted of the two, and Advanced should stay
        // the last stop before diagnostics.
        val order = SettingsCategory.entries
        assertThat(order.indexOf(SettingsCategory.APPEARANCE))
            .isLessThan(order.indexOf(SettingsCategory.ADVANCED))
    }

    // ----- the tune method value ----------------------------------------------

    @Test
    fun `each tune method has its own name`() {
        val names = (0..2).map { tuneMethodNameRes(it) }
        assertThat(names).containsNoDuplicates()
    }

    @Test
    fun `an out-of-range tune method reads as automatic`() {
        // Matches what the Transmission screen itself shows for one, rather
        // than leaving the row blank or crashing on a corrupt config value.
        assertThat(tuneMethodNameRes(99)).isEqualTo(tuneMethodNameRes(0))
        assertThat(tuneMethodNameRes(-1)).isEqualTo(tuneMethodNameRes(0))
    }
}
