package radio.ks3ckc.sstvaf.ui.settings

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.connector.ConnectMode
import com.k1af.ft8af.database.ControlMode
import com.k1af.ft8af.rigs.CatConnectionState
import com.k1af.ft8af.spectrum.AudioInputLevel
import com.k1af.ft8af.wave.InputAudioLevel
import radio.ks3ckc.sstvaf.ui.components.inputLevelStatusText
import org.junit.Test

/**
 * The Radio & audio screen decisions: what the connection card reports, which
 * controls are offered, and how the input meter is drawn.
 *
 * All pure, so no runner. The rig-list tests that need the shipped asset live
 * in [RigOptionsTest], which does need Robolectric.
 */
class RadioAudioLogicTest {

    // ----- link state ---------------------------------------------------------

    @Test
    fun `cat connection states map to link states`() {
        assertThat(rigLinkState(ControlMode.CAT, CatConnectionState.CONNECTED))
            .isEqualTo(RigLinkState.CONNECTED)
        assertThat(rigLinkState(ControlMode.CAT, CatConnectionState.CONNECTING))
            .isEqualTo(RigLinkState.CONNECTING)
        assertThat(rigLinkState(ControlMode.CAT, CatConnectionState.DISCONNECTED))
            .isEqualTo(RigLinkState.DISCONNECTED)
        assertThat(rigLinkState(ControlMode.CAT, CatConnectionState.ERROR))
            .isEqualTo(RigLinkState.DISCONNECTED)
    }

    @Test
    fun `rts and dtr report the link the same way cat does`() {
        // All three are serial rig control; only VOX is different in kind.
        for (mode in listOf(ControlMode.CAT, ControlMode.RTS, ControlMode.DTR)) {
            assertThat(rigLinkState(mode, CatConnectionState.CONNECTED))
                .isEqualTo(RigLinkState.CONNECTED)
        }
    }

    @Test
    fun `vox outranks a stale cat error`() {
        // An operator who switched to VOX has no control link at all. Reporting
        // a leftover CAT error would report the absence of something they
        // turned off on purpose, and send them looking for a cable fault.
        assertThat(rigLinkState(ControlMode.VOX, CatConnectionState.ERROR))
            .isEqualTo(RigLinkState.VOX)
        assertThat(rigLinkState(ControlMode.VOX, CatConnectionState.CONNECTED))
            .isEqualTo(RigLinkState.VOX)
    }

    @Test
    fun `only a broken link gets the primary action`() {
        assertThat(rigLinkActionIsPrimary(RigLinkState.DISCONNECTED)).isTrue()
        assertThat(rigLinkActionIsPrimary(RigLinkState.CONNECTED)).isFalse()
        assertThat(rigLinkActionIsPrimary(RigLinkState.CONNECTING)).isFalse()
        assertThat(rigLinkActionIsPrimary(RigLinkState.VOX)).isFalse()
    }

    @Test
    fun `a connection in flight is not re-tappable`() {
        assertThat(rigLinkActionEnabled(RigLinkState.CONNECTING)).isFalse()
        assertThat(rigLinkActionEnabled(RigLinkState.DISCONNECTED)).isTrue()
        assertThat(rigLinkActionEnabled(RigLinkState.CONNECTED)).isTrue()
        assertThat(rigLinkActionEnabled(RigLinkState.VOX)).isTrue()
    }

    @Test
    fun `an unset rig model says so instead of blaming the link`() {
        // Index 0 of the shipped list is a blank placeholder whose display name
        // is "None", so the ordinary format would read "None not responding" on
        // a fresh install. That describes a fault where there is only an
        // unfinished setup step.
        assertThat(rigLinkTitleRes(RigLinkState.DISCONNECTED, hasRigModel = false))
            .isNotEqualTo(rigLinkTitleRes(RigLinkState.DISCONNECTED, hasRigModel = true))
        assertThat(rigLinkTitleRes(RigLinkState.CONNECTING, hasRigModel = false))
            .isEqualTo(rigLinkTitleRes(RigLinkState.DISCONNECTED, hasRigModel = false))
    }

    @Test
    fun `vox needs no rig model to make sense`() {
        // Under VOX the rig keys off audio, so there is nothing to select and
        // nothing missing to report.
        assertThat(rigLinkTitleRes(RigLinkState.VOX, hasRigModel = false))
            .isEqualTo(rigLinkTitleRes(RigLinkState.VOX, hasRigModel = true))
    }

    @Test
    fun `the blank first row does not count as a chosen rig`() {
        assertThat(hasRigModelSelected(0)).isFalse()
        assertThat(hasRigModelSelected(1)).isTrue()
        assertThat(hasRigModelSelected(42)).isTrue()
    }

    @Test
    fun `a negative stored model number is treated as unset`() {
        // getRigNameByIndex maps -1 to its own blank entry, so the card must
        // agree rather than reporting a rig that is not there.
        assertThat(hasRigModelSelected(-1)).isFalse()
    }

    @Test
    fun `every link state has a distinct title and action`() {
        val titles = RigLinkState.entries.map { rigLinkTitleRes(it, hasRigModel = true) }
        assertThat(titles).containsNoDuplicates()
        val actions = RigLinkState.entries.map { rigLinkActionRes(it) }
        assertThat(actions).containsNoDuplicates()
    }

    // ----- the detail line ----------------------------------------------------

    private fun detail(state: RigLinkState, isUsb: Boolean = true, baud: String = "19200") =
        rigLinkDetail(
            state = state,
            connectionLabel = "USB Cable",
            baudLabel = baud,
            isUsb = isUsb,
            dialLabel = "20m",
            voxDetail = "audio only",
            waitingDetail = "waiting",
            checkCableDetail = "check cable",
        )

    @Test
    fun `a connected usb link names its baud rate`() {
        assertThat(detail(RigLinkState.CONNECTED)).isEqualTo("USB Cable · 19200 · 20m")
    }

    @Test
    fun `a non-usb link does not claim a baud rate`() {
        // Bluetooth and network links have no serial line to clock, so printing
        // one would state something about the link that is simply not true.
        assertThat(detail(RigLinkState.CONNECTED, isUsb = false))
            .isEqualTo("USB Cable · 20m")
    }

    @Test
    fun `a blank baud rate is left out rather than shown as an empty segment`() {
        assertThat(detail(RigLinkState.CONNECTED, baud = "  "))
            .isEqualTo("USB Cable · 20m")
    }

    @Test
    fun `the other states say what they are doing`() {
        assertThat(detail(RigLinkState.VOX)).isEqualTo("audio only")
        assertThat(detail(RigLinkState.CONNECTING)).isEqualTo("USB Cable · waiting")
        assertThat(detail(RigLinkState.DISCONNECTED)).isEqualTo("USB Cable · check cable")
    }

    // ----- the control sets ---------------------------------------------------

    @Test
    fun `the connection options are the three real routes`() {
        assertThat(CONNECTION_MODES)
            .containsExactly(ConnectMode.USB_CABLE, ConnectMode.BLUE_TOOTH, ConnectMode.NETWORK)
            .inOrder()
    }

    @Test
    fun `the control options are the four ptt methods`() {
        assertThat(CONTROL_MODES)
            .containsExactly(ControlMode.CAT, ControlMode.RTS, ControlMode.DTR, ControlMode.VOX)
            .inOrder()
    }

    @Test
    fun `every connection and control option has its own label and hint`() {
        assertThat(CONNECTION_MODES.map { connectionModeLabelRes(it) }).containsNoDuplicates()
        assertThat(CONNECTION_MODES.map { connectionHintRes(it) }).containsNoDuplicates()
        assertThat(CONTROL_MODES.map { controlModeShortLabel(it) })
            .containsExactly("CAT", "RTS", "DTR", "VOX").inOrder()
        assertThat(CONTROL_MODES.map { controlHintRes(it) }).containsNoDuplicates()
    }

    // ----- baud ---------------------------------------------------------------

    @Test
    fun `baud is offered only on a usb serial link under rig control`() {
        assertThat(showsBaudRate(ConnectMode.USB_CABLE, ControlMode.CAT)).isTrue()
        assertThat(showsBaudRate(ConnectMode.USB_CABLE, ControlMode.RTS)).isTrue()
        assertThat(showsBaudRate(ConnectMode.USB_CABLE, ControlMode.DTR)).isTrue()
    }

    @Test
    fun `baud is hidden where there is no serial line to clock`() {
        // Offering it under VOX or on a network link implies changing it would
        // do something, which sends an operator chasing a fault down a dead end.
        assertThat(showsBaudRate(ConnectMode.USB_CABLE, ControlMode.VOX)).isFalse()
        assertThat(showsBaudRate(ConnectMode.NETWORK, ControlMode.CAT)).isFalse()
        assertThat(showsBaudRate(ConnectMode.BLUE_TOOTH, ControlMode.CAT)).isFalse()
    }

    @Test
    fun `the slow rates older rigs need are still offered`() {
        // 4800 is the default on older Kenwood and Yaesu radios. Dropping it to
        // tidy the layout would lock those rigs out of CAT control entirely.
        assertThat(BAUD_RATES).contains(4800)
        assertThat(BAUD_RATES).hasSize(9)
        assertThat(BAUD_RATES).isInOrder()
    }

    @Test
    fun `baud chips wrap instead of shrinking below a usable width`() {
        val rows = baudChipRows()
        assertThat(rows).hasSize(2)
        assertThat(rows.first()).hasSize(BAUD_CHIPS_PER_ROW)
        assertThat(rows.flatten()).containsExactlyElementsIn(BAUD_RATES).inOrder()
    }

    @Test
    fun `a degenerate row size falls back to one row rather than looping`() {
        assertThat(baudChipRows(perRow = 0)).hasSize(1)
        assertThat(baudChipRows(perRow = -3)).hasSize(1)
    }

    @Test
    fun `baud labels are short and distinct`() {
        assertThat(baudChipLabel(4800)).isEqualTo("4.8k")
        assertThat(baudChipLabel(19200)).isEqualTo("19.2k")
        assertThat(baudChipLabel(43000)).isEqualTo("43k")
        assertThat(baudChipLabel(115200)).isEqualTo("115.2k")
        assertThat(baudChipLabel(300)).isEqualTo("300")
        assertThat(BAUD_RATES.map { baudChipLabel(it) }).containsNoDuplicates()
    }

    // ----- ptt delay ----------------------------------------------------------

    @Test
    fun `ptt delay snaps to the step`() {
        // The value is a settling time tuned by trial, so it snaps; 137 ms
        // would suggest a precision the control does not have.
        assertThat(snapPttDelay(137)).isEqualTo(140)
        assertThat(snapPttDelay(124)).isEqualTo(120)
        assertThat(snapPttDelay(125)).isEqualTo(130)
        assertThat(snapPttDelay(100)).isEqualTo(100)
    }

    @Test
    fun `every value the old Advanced picker could set is still reachable`() {
        // That picker offered 0..190 in tens and this slider replaces it. A
        // coarser step here would silently round a delay an operator had
        // already tuned, the first time they touched the control.
        for (legacy in 0..190 step 10) {
            assertThat(snapPttDelay(legacy)).isEqualTo(legacy)
        }
    }

    @Test
    fun `the range extends past what the old picker allowed`() {
        // Some rigs take longer than 190 ms to switch over.
        assertThat(PTT_DELAY_MAX).isGreaterThan(190)
        assertThat(snapPttDelay(500)).isEqualTo(500)
    }

    @Test
    fun `ptt delay is clamped to the slider range`() {
        assertThat(snapPttDelay(-40)).isEqualTo(PTT_DELAY_MIN)
        assertThat(snapPttDelay(9000)).isEqualTo(PTT_DELAY_MAX)
    }

    @Test
    fun `every snapped value is reachable and on the step`() {
        for (raw in PTT_DELAY_MIN..PTT_DELAY_MAX) {
            val snapped = snapPttDelay(raw)
            assertThat(snapped % PTT_DELAY_STEP).isEqualTo(0)
            assertThat(snapped).isAtLeast(PTT_DELAY_MIN)
            assertThat(snapped).isAtMost(PTT_DELAY_MAX)
        }
    }

    // ----- the meter ----------------------------------------------------------

    @Test
    fun `the gradient spans the full track whatever the fill`() {
        // This is the point of the inverse scale: a quiet signal must not show
        // red at the end of its short bar, which would tell the operator to turn
        // the gain down at exactly the moment they need to turn it up.
        for (fill in listOf(0.1f, 0.25f, 0.5f, 0.78f, 1f)) {
            assertThat(fill * inputLevelGradientScale(fill)).isWithin(1e-5f).of(1f)
        }
    }

    @Test
    fun `an empty meter has nothing to scale`() {
        assertThat(inputLevelGradientScale(0f)).isEqualTo(1f)
        assertThat(inputLevelGradientScale(-1f)).isEqualTo(1f)
    }

    @Test
    fun `an over-full reading is clamped before scaling`() {
        assertThat(inputLevelGradientScale(4f)).isEqualTo(1f)
    }

    @Test
    fun `no reading and a silent reading are reported the same way`() {
        // Both mean check the connection before touching the gain.
        assertThat(inputLevelIsSilent(null)).isTrue()
        assertThat(inputLevelIsSilent(InputAudioLevel.Levels(0f, 0f))).isTrue()
    }

    @Test
    fun `real audio is not reported as silent`() {
        assertThat(inputLevelIsSilent(InputAudioLevel.Levels(0.3f, 0.1f))).isFalse()
    }

    @Test
    fun `only the actionable levels carry advice`() {
        // A healthy level needs no instruction; printing one anyway trains the
        // operator to ignore the line.
        assertThat(inputLevelAdviceRes(AudioInputLevel.Status.GOOD)).isNull()
        for (status in listOf(
            AudioInputLevel.Status.SILENT,
            AudioInputLevel.Status.LOW,
            AudioInputLevel.Status.HIGH,
            AudioInputLevel.Status.CLIPPING,
        )) {
            assertThat(inputLevelAdviceRes(status)).isNotNull()
        }
    }

    @Test
    fun `silence gets its own word, not the too-quiet one`() {
        // The shared classifier wording collapses the two. Beside a sentence
        // saying no audio is arriving, the word "Low" would read as a second,
        // different diagnosis of the same reading.
        assertThat(inputLevelWordRes(AudioInputLevel.Status.SILENT))
            .isNotEqualTo(inputLevelWordRes(AudioInputLevel.Status.LOW))
    }

    @Test
    fun `the other levels keep the shared wording`() {
        for (status in listOf(
            AudioInputLevel.Status.LOW,
            AudioInputLevel.Status.GOOD,
            AudioInputLevel.Status.HIGH,
            AudioInputLevel.Status.CLIPPING,
        )) {
            assertThat(inputLevelWordRes(status)).isEqualTo(inputLevelStatusText(status))
        }
    }

    @Test
    fun `every level has its own word`() {
        val words = AudioInputLevel.Status.entries.map { inputLevelWordRes(it) }
        assertThat(words).containsNoDuplicates()
    }

    @Test
    fun `each advice line is distinct`() {
        val lines = AudioInputLevel.Status.entries.mapNotNull { inputLevelAdviceRes(it) }
        assertThat(lines).containsNoDuplicates()
    }

    @Test
    fun `the meter tick sits exactly where the classifier turns hot`() {
        // The mark and the verdict come from one threshold, so the bar cannot
        // say "good" while the operator is past the line, or the reverse.
        val justUnder = AudioInputLevel.HOT_PEAK - 0.01f
        val justOver = AudioInputLevel.HOT_PEAK + 0.01f
        // RMS well inside the healthy window so the peak decides the verdict.
        assertThat(AudioInputLevel.fromPeakRms(justUnder, 0.2f).status)
            .isNotEqualTo(AudioInputLevel.Status.HIGH)
        assertThat(AudioInputLevel.fromPeakRms(justOver, 0.2f).status)
            .isEqualTo(AudioInputLevel.Status.HIGH)
    }

    @Test
    fun `the tick is inside the bar`() {
        assertThat(AudioInputLevel.HOT_PEAK).isGreaterThan(0f)
        assertThat(AudioInputLevel.HOT_PEAK).isLessThan(1f)
    }

    // ----- rig search ---------------------------------------------------------

    private val rigs = listOf(
        RigOption(1, "ICOM IC-705"),
        RigOption(2, "ICOM IC-7300"),
        RigOption(3, "YAESU FT-991A"),
        RigOption(4, "YAESU FT-891"),
        RigOption(5, "Xiegu G90"),
    )

    @Test
    fun `a blank query returns everything`() {
        assertThat(searchRigs(rigs, "")).isEqualTo(rigs)
        assertThat(searchRigs(rigs, "   ")).isEqualTo(rigs)
    }

    @Test
    fun `search ignores case`() {
        assertThat(searchRigs(rigs, "icom")).hasSize(2)
        assertThat(searchRigs(rigs, "ICOM")).hasSize(2)
    }

    @Test
    fun `every term must match so a second word narrows`() {
        // With a hundred rigs in the list, matching any term would widen the
        // result instead, which defeats the point of typing the second word.
        assertThat(searchRigs(rigs, "yaesu 991").map { it.name })
            .containsExactly("YAESU FT-991A")
    }

    @Test
    fun `search matches anywhere in the name`() {
        assertThat(searchRigs(rigs, "7300").map { it.index }).containsExactly(2)
    }

    @Test
    fun `a query matching nothing returns empty rather than everything`() {
        assertThat(searchRigs(rigs, "kenwood")).isEmpty()
    }

    @Test
    fun `extra whitespace between terms is ignored`() {
        assertThat(searchRigs(rigs, "  yaesu    891  ").map { it.index }).containsExactly(4)
    }

    // ----- the card action label ---------------------------------------------

    @Test
    fun `with no rig model the action offers to choose one`() {
        // The CAT command set, the CI-V address and the baud default all come
        // from the model, so there is nothing to connect to until it is set.
        // Offering "Connect" there sent the operator to a cable picker for a
        // rig the app had not been told the type of.
        assertThat(rigLinkActionRes(RigLinkState.DISCONNECTED, hasRigModel = false))
            .isEqualTo(rigLinkActionRes(RigLinkState.CONNECTED, hasRigModel = false))
        assertThat(rigLinkActionRes(RigLinkState.DISCONNECTED, hasRigModel = false))
            .isNotEqualTo(rigLinkActionRes(RigLinkState.DISCONNECTED, hasRigModel = true))
    }

    @Test
    fun `vox does not need a rig model to offer its test`() {
        // Under VOX the rig keys itself off audio, so the model is irrelevant.
        assertThat(rigLinkActionRes(RigLinkState.VOX, hasRigModel = false))
            .isEqualTo(rigLinkActionRes(RigLinkState.VOX, hasRigModel = true))
    }

    @Test
    fun `the vox test offers to stop while it is running`() {
        // It keys the rig, so there has to be a way to stop it that is not
        // waiting for the timeout.
        assertThat(rigLinkActionRes(RigLinkState.VOX, tuning = true))
            .isNotEqualTo(rigLinkActionRes(RigLinkState.VOX, tuning = false))
    }

    @Test
    fun `each connected state keeps its own label`() {
        assertThat(rigLinkActionRes(RigLinkState.CONNECTED))
            .isNotEqualTo(rigLinkActionRes(RigLinkState.DISCONNECTED))
        assertThat(rigLinkActionRes(RigLinkState.CONNECTING))
            .isNotEqualTo(rigLinkActionRes(RigLinkState.DISCONNECTED))
    }
}
