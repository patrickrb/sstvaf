package radio.ks3ckc.sstvaf.ui.tx

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.sstvaf.sstv.SstvMode

/**
 * Unit tests for the mode sheet's grouping, duration tone and labels.
 *
 * The grouping rule matters more than it looks: it is what decides where each
 * of sixteen modes shows up, and it replaced a hand-written list. These tests
 * pin the nine modes the design names to the groups the design puts them in, so
 * a future tweak to a threshold has to consciously re-answer "does Scottie 1
 * still read as standard, and is PD 120 still high-resolution?".
 *
 * Robolectric only for the resource-id assertions; the grouping itself is pure.
 */
@RunWith(RobolectricTestRunner::class)
class ModeSheetLogicTest {

    // ----- the design's nine land where the design puts them ------------------

    @Test
    fun `fast group is the sub-minute modes the design lists`() {
        assertThat(modeSpeedGroup(SstvMode.ROBOT_36)).isEqualTo(ModeSpeedGroup.FAST)
        assertThat(modeSpeedGroup(SstvMode.PD_50)).isEqualTo(ModeSpeedGroup.FAST)
        assertThat(modeSpeedGroup(SstvMode.MARTIN_2)).isEqualTo(ModeSpeedGroup.FAST)
    }

    @Test
    fun `standard group is the minute-or-two modes the design lists`() {
        assertThat(modeSpeedGroup(SstvMode.SCOTTIE_2)).isEqualTo(ModeSpeedGroup.STANDARD)
        assertThat(modeSpeedGroup(SstvMode.ROBOT_72)).isEqualTo(ModeSpeedGroup.STANDARD)
        assertThat(modeSpeedGroup(SstvMode.PD_90)).isEqualTo(ModeSpeedGroup.STANDARD)
        assertThat(modeSpeedGroup(SstvMode.SCOTTIE_1)).isEqualTo(ModeSpeedGroup.STANDARD)
        assertThat(modeSpeedGroup(SstvMode.MARTIN_1)).isEqualTo(ModeSpeedGroup.STANDARD)
    }

    @Test
    fun `pd 120 is high resolution`() {
        assertThat(modeSpeedGroup(SstvMode.PD_120)).isEqualTo(ModeSpeedGroup.HIGH_RESOLUTION)
    }

    // ----- the rule, not just the nine ---------------------------------------

    @Test
    fun `resolution outranks duration`() {
        // PD 120 is 127 s, which would be "standard-ish" by time alone, but it
        // is 640 wide: the detail-for-airtime trade is the point of the group.
        assertThat(SstvMode.PD_120.txDurationSeconds).isGreaterThan(100.0)
        assertThat(modeSpeedGroup(SstvMode.PD_120)).isEqualTo(ModeSpeedGroup.HIGH_RESOLUTION)
        // PD 160 is 512 wide — also high resolution despite a different duration.
        assertThat(modeSpeedGroup(SstvMode.PD_160)).isEqualTo(ModeSpeedGroup.HIGH_RESOLUTION)
    }

    @Test
    fun `every 320-wide mode is grouped by duration alone`() {
        for (mode in SstvMode.entries.filter { it.width == 320 }) {
            val expected = if (mode.txDurationSeconds < FAST_MODE_MAX_SECONDS) {
                ModeSpeedGroup.FAST
            } else {
                ModeSpeedGroup.STANDARD
            }
            assertThat(modeSpeedGroup(mode)).isEqualTo(expected)
        }
    }

    @Test
    fun `the appended modes all find a home`() {
        // The seven modes added in issue #16 must each land somewhere — the
        // whole reason the sheet groups by rule rather than a curated list.
        for (mode in listOf(
            SstvMode.SCOTTIE_DX, SstvMode.MARTIN_3, SstvMode.MARTIN_4,
            SstvMode.PD_160, SstvMode.PD_180, SstvMode.PD_240, SstvMode.PD_290,
        )) {
            assertThat(modeSpeedGroup(mode)).isNotNull()
        }
    }

    // ----- the grouped list --------------------------------------------------

    @Test
    fun `groups contain every mode exactly once`() {
        val grouped = modeGroups().flatMap { it.modes }
        assertThat(grouped).containsExactlyElementsIn(SstvMode.entries)
    }

    @Test
    fun `groups are ordered fast then standard then high resolution`() {
        assertThat(modeGroups().map { it.group })
            .containsExactly(
                ModeSpeedGroup.FAST,
                ModeSpeedGroup.STANDARD,
                ModeSpeedGroup.HIGH_RESOLUTION,
            )
            .inOrder()
    }

    @Test
    fun `modes are shortest-first inside each group`() {
        for (group in modeGroups()) {
            val durations = group.modes.map { it.txDurationSeconds }
            assertThat(durations).isInOrder()
        }
    }

    @Test
    fun `empty groups are omitted`() {
        // A filtered list with only fast modes yields one group, not three
        // headers with nothing under two of them.
        val groups = modeGroups(listOf(SstvMode.ROBOT_36, SstvMode.MARTIN_4))
        assertThat(groups).hasSize(1)
        assertThat(groups.single().group).isEqualTo(ModeSpeedGroup.FAST)
    }

    @Test
    fun `an empty mode list yields no groups`() {
        assertThat(modeGroups(emptyList())).isEmpty()
    }

    // ----- duration tone -----------------------------------------------------

    @Test
    fun `sub-minute durations read as quick`() {
        assertThat(modeDurationTone(SstvMode.ROBOT_36)).isEqualTo(ModeDurationTone.QUICK)
        assertThat(modeDurationTone(SstvMode.MARTIN_2)).isEqualTo(ModeDurationTone.QUICK)
        assertThat(modeDurationTone(SstvMode.MARTIN_4)).isEqualTo(ModeDurationTone.QUICK)
    }

    @Test
    fun `the middle band reads as normal`() {
        assertThat(modeDurationTone(SstvMode.SCOTTIE_2)).isEqualTo(ModeDurationTone.NORMAL)
        assertThat(modeDurationTone(SstvMode.PD_90)).isEqualTo(ModeDurationTone.NORMAL)
    }

    @Test
    fun `long transmissions are flagged`() {
        // Over 100 s the frequency is tied up long enough to warn about.
        assertThat(modeDurationTone(SstvMode.SCOTTIE_1)).isEqualTo(ModeDurationTone.LONG)
        assertThat(modeDurationTone(SstvMode.MARTIN_1)).isEqualTo(ModeDurationTone.LONG)
        assertThat(modeDurationTone(SstvMode.PD_120)).isEqualTo(ModeDurationTone.LONG)
        assertThat(modeDurationTone(SstvMode.SCOTTIE_DX)).isEqualTo(ModeDurationTone.LONG)
    }

    @Test
    fun `tone is independent of the group`() {
        // A high-resolution mode still gets its duration flagged; the group
        // says what you gain, the tone says what it costs.
        assertThat(modeSpeedGroup(SstvMode.PD_120)).isEqualTo(ModeSpeedGroup.HIGH_RESOLUTION)
        assertThat(modeDurationTone(SstvMode.PD_120)).isEqualTo(ModeDurationTone.LONG)
    }

    // ----- labels ------------------------------------------------------------

    @Test
    fun `duration label is m colon ss`() {
        assertThat(modeDurationLabel(SstvMode.ROBOT_36)).isEqualTo("0:37")
        assertThat(modeDurationLabel(SstvMode.SCOTTIE_1)).isEqualTo("1:51")
        assertThat(modeDurationLabel(SstvMode.PD_120)).isEqualTo("2:07")
    }

    @Test
    fun `sub label is the dimensions alone for most modes`() {
        assertThat(modeSubLabel(SstvMode.ROBOT_36, null)).isEqualTo("320 × 240")
        assertThat(modeSubLabel(SstvMode.PD_120, null)).isEqualTo("640 × 496")
    }

    @Test
    fun `sub label appends a note when the mode has one`() {
        assertThat(modeSubLabel(SstvMode.SCOTTIE_1, "most common on 20m"))
            .isEqualTo("320 × 256 · most common on 20m")
    }

    @Test
    fun `a blank note does not leave a trailing separator`() {
        assertThat(modeSubLabel(SstvMode.SCOTTIE_1, "")).isEqualTo("320 × 256")
        assertThat(modeSubLabel(SstvMode.SCOTTIE_1, "   ")).isEqualTo("320 × 256")
    }

    @Test
    fun `only scottie 1 carries a note`() {
        assertThat(modeNoteRes(SstvMode.SCOTTIE_1)).isEqualTo(R.string.mode_note_most_common)
        for (mode in SstvMode.entries.filter { it != SstvMode.SCOTTIE_1 }) {
            assertThat(modeNoteRes(mode)).isNull()
        }
    }

    @Test
    fun `each speed group has its own header resource`() {
        val ids = ModeSpeedGroup.entries.map { modeSpeedGroupLabelRes(it) }
        assertThat(ids).containsNoDuplicates()
        assertThat(modeSpeedGroupLabelRes(ModeSpeedGroup.FAST))
            .isEqualTo(R.string.mode_group_fast)
    }
}
