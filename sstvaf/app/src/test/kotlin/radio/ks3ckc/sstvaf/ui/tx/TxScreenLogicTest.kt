package radio.ks3ckc.sstvaf.ui.tx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.sstvaf.sstv.SstvMode
import kotlin.math.roundToInt

/**
 * [TxScreenLogic]: transmit gating precedence, label/duration formatting
 * goldens, last-used-mode fallback, and the pinch/drag gesture math.
 * Robolectric (not pure JVM) because [applyPanZoomGesture] goes through
 * `computeCoverCrop`, which returns an android.graphics.Rect — under the
 * stubbed android.jar its methods would silently return 0
 * (returnDefaultValues) and fake the geometry.
 */
@RunWith(RobolectricTestRunner::class)
class TxScreenLogicTest {

    // -- transmitGate ----------------------------------------------------------

    @Test
    fun `transmitting outranks everything`() {
        assertThat(transmitGate(hasImage = true, isTransmitting = true, tuneActive = true))
            .isEqualTo(TxGate.TRANSMITTING)
        assertThat(transmitGate(hasImage = false, isTransmitting = true, tuneActive = false))
            .isEqualTo(TxGate.TRANSMITTING)
    }

    @Test
    fun `tune outranks a missing image`() {
        assertThat(transmitGate(hasImage = false, isTransmitting = false, tuneActive = true))
            .isEqualTo(TxGate.TUNE_ACTIVE)
        assertThat(transmitGate(hasImage = true, isTransmitting = false, tuneActive = true))
            .isEqualTo(TxGate.TUNE_ACTIVE)
    }

    @Test
    fun `missing image blocks transmit when idle`() {
        assertThat(transmitGate(hasImage = false, isTransmitting = false, tuneActive = false))
            .isEqualTo(TxGate.NO_IMAGE)
    }

    @Test
    fun `image present and idle is ready`() {
        assertThat(transmitGate(hasImage = true, isTransmitting = false, tuneActive = false))
            .isEqualTo(TxGate.READY)
    }

    // -- previewMaxHeightDp ------------------------------------------------------

    @Test
    fun `portrait returns null so the frame fills the width`() {
        assertThat(previewMaxHeightDp(screenWidthDp = 393, screenHeightDp = 851, aspect = 1.25f))
            .isNull()
    }

    @Test
    fun `square returns null`() {
        assertThat(previewMaxHeightDp(screenWidthDp = 600, screenHeightDp = 600, aspect = 1.25f))
            .isNull()
    }

    @Test
    fun `landscape caps at half the screen height when width allows`() {
        // 851x393 landscape: half-height 196.5 < width bound 851/1.25=680.8.
        assertThat(previewMaxHeightDp(screenWidthDp = 851, screenHeightDp = 393, aspect = 1.25f))
            .isWithin(0.01f).of(196.5f)
    }

    @Test
    fun `landscape width bound wins for a very wide frame on a short screen`() {
        // aspect 4.0: width bound 400/4=100 < half-height 150 → width wins.
        assertThat(previewMaxHeightDp(screenWidthDp = 400, screenHeightDp = 300, aspect = 4.0f))
            .isWithin(0.01f).of(100f)
    }

    @Test
    fun `non-positive aspect returns null`() {
        assertThat(previewMaxHeightDp(screenWidthDp = 851, screenHeightDp = 393, aspect = 0f))
            .isNull()
    }

    // -- labels ------------------------------------------------------------------

    @Test
    fun `mode resolution label is the mode's pixel dimensions`() {
        assertThat(modeResolutionLabel(SstvMode.SCOTTIE_1)).isEqualTo("320×256")
        assertThat(modeResolutionLabel(SstvMode.ROBOT_36)).isEqualTo("320×240")
        assertThat(modeResolutionLabel(SstvMode.MARTIN_4)).isEqualTo("320×128")
        assertThat(modeResolutionLabel(SstvMode.PD_290)).isEqualTo("800×616")
    }

    @Test
    fun `mode chip label carries resolution and duration`() {
        assertThat(modeChipLabel(SstvMode.SCOTTIE_1)).isEqualTo("Scottie 1 · 320×256 · 111 s")
        assertThat(modeChipLabel(SstvMode.ROBOT_36)).isEqualTo("Robot 36 · 320×240 · 37 s")
        assertThat(modeChipLabel(SstvMode.PD_120)).isEqualTo("PD 120 · 640×496 · 127 s")
    }

    @Test
    fun `confirm sheet duration line golden`() {
        assertThat(confirmDurationLine(SstvMode.ROBOT_36))
            .isEqualTo("Robot 36 — 320×240 — 37 seconds")
        assertThat(confirmDurationLine(SstvMode.SCOTTIE_1))
            .isEqualTo("Scottie 1 — 320×256 — 111 seconds")
    }

    @Test
    fun `total tx duration adds the cw id tail`() {
        assertThat(totalTxDurationSeconds(SstvMode.ROBOT_36, 0.0)).isWithin(1e-9).of(36.91)
        assertThat(totalTxDurationSeconds(SstvMode.ROBOT_36, 5.0)).isWithin(1e-9).of(41.91)
    }

    @Test
    fun `total tx duration ignores a negative tail`() {
        assertThat(totalTxDurationSeconds(SstvMode.ROBOT_36, -3.0)).isWithin(1e-9).of(36.91)
    }

    @Test
    fun `total tx duration adds the vox pre-tone`() {
        assertThat(totalTxDurationSeconds(SstvMode.ROBOT_36, 0.0, 0.3))
            .isWithin(1e-9).of(37.21)
        assertThat(totalTxDurationSeconds(SstvMode.ROBOT_36, 5.0, 0.3))
            .isWithin(1e-9).of(42.21)
    }

    @Test
    fun `total tx duration ignores a negative pre-tone`() {
        assertThat(totalTxDurationSeconds(SstvMode.ROBOT_36, 0.0, -0.3))
            .isWithin(1e-9).of(36.91)
    }

    @Test
    fun `confirm line folds in the pre-tone without a cw flag`() {
        // 36.91 s image + 0.8 s pre-tone = 37.71 → 38 s; sub-second leader is
        // not called out as a separate segment.
        assertThat(confirmDurationLine(SstvMode.ROBOT_36, 0.0, 0.8))
            .isEqualTo("Robot 36 — 320×240 — 38 seconds")
    }

    @Test
    fun `confirm line with no cw id is unchanged`() {
        assertThat(confirmDurationLine(SstvMode.ROBOT_36, 0.0))
            .isEqualTo("Robot 36 — 320×240 — 37 seconds")
    }

    @Test
    fun `confirm line folds in and flags the cw id tail`() {
        // 36.91 s image + 5.2 s CW = 42.11 → 42 s, flagged as including the ID.
        assertThat(confirmDurationLine(SstvMode.ROBOT_36, 5.2))
            .isEqualTo("Robot 36 — 320×240 — 42 seconds (incl. CW ID)")
    }

    // -- txAirtimeClass ----------------------------------------------------------

    @Test
    fun `airtime class buckets representative modes`() {
        // Martin 4 ≈ 30 s, Robot 36 ≈ 37 s → QUICK
        assertThat(txAirtimeClass(SstvMode.MARTIN_4)).isEqualTo(TxAirtimeClass.QUICK)
        assertThat(txAirtimeClass(SstvMode.ROBOT_36)).isEqualTo(TxAirtimeClass.QUICK)
        // Robot 72 ≈ 73 s, Scottie 1 ≈ 111 s → MODERATE
        assertThat(txAirtimeClass(SstvMode.ROBOT_72)).isEqualTo(TxAirtimeClass.MODERATE)
        assertThat(txAirtimeClass(SstvMode.SCOTTIE_1)).isEqualTo(TxAirtimeClass.MODERATE)
        // PD 120 ≈ 127 s, PD 180 ≈ 188 s → LONG
        assertThat(txAirtimeClass(SstvMode.PD_120)).isEqualTo(TxAirtimeClass.LONG)
        assertThat(txAirtimeClass(SstvMode.PD_180)).isEqualTo(TxAirtimeClass.LONG)
        // Scottie DX ≈ 270 s, PD 290 ≈ 290 s → VERY_LONG
        assertThat(txAirtimeClass(SstvMode.SCOTTIE_DX)).isEqualTo(TxAirtimeClass.VERY_LONG)
        assertThat(txAirtimeClass(SstvMode.PD_290)).isEqualTo(TxAirtimeClass.VERY_LONG)
    }

    @Test
    fun `airtime class thresholds are inclusive lower bounds`() {
        // PD 50 ≈ 50.59 s sits just under the 60 s QUICK/MODERATE line.
        assertThat(txAirtimeClass(SstvMode.PD_50)).isEqualTo(TxAirtimeClass.QUICK)
        // A CW tail that pushes PD 50 past 60 s bumps it to MODERATE.
        assertThat(txAirtimeClass(SstvMode.PD_50, cwTailSeconds = 12.0))
            .isEqualTo(TxAirtimeClass.MODERATE)
    }

    @Test
    fun `airtime class folds in the cw id tail`() {
        // PD 120 ≈ 127 s is LONG; a long CW tail can carry it into VERY_LONG.
        assertThat(txAirtimeClass(SstvMode.PD_120)).isEqualTo(TxAirtimeClass.LONG)
        assertThat(txAirtimeClass(SstvMode.PD_120, cwTailSeconds = 200.0))
            .isEqualTo(TxAirtimeClass.VERY_LONG)
        // A negative tail is treated as 0 (see totalTxDurationSeconds), so the
        // class never drops below the bare-mode class.
        assertThat(txAirtimeClass(SstvMode.ROBOT_36, cwTailSeconds = -50.0))
            .isEqualTo(TxAirtimeClass.QUICK)
    }

    @Test
    fun `every mode maps to a class and all four classes are reachable`() {
        val classes = SstvMode.entries.map { txAirtimeClass(it) }.toSet()
        assertThat(classes).isEqualTo(TxAirtimeClass.entries.toSet())
    }

    @Test
    fun `formatMinSec goldens`() {
        assertThat(formatMinSec(0)).isEqualTo("0:00")
        assertThat(formatMinSec(7)).isEqualTo("0:07")
        assertThat(formatMinSec(60)).isEqualTo("1:00")
        assertThat(formatMinSec(111)).isEqualTo("1:51")
        assertThat(formatMinSec(600)).isEqualTo("10:00")
    }

    @Test
    fun `formatMinSec clamps negatives to zero`() {
        assertThat(formatMinSec(-5)).isEqualTo("0:00")
    }

    @Test
    fun `elapsed label tracks the progress fraction`() {
        assertThat(txElapsedLabel(0f, 110.54332)).isEqualTo("0:00 / 1:51")
        assertThat(txElapsedLabel(1f, 110.54332)).isEqualTo("1:51 / 1:51")
        assertThat(txElapsedLabel(0.5f, 36.91)).isEqualTo("0:19 / 0:37")
    }

    @Test
    fun `elapsed label clamps progress outside 0 to 1`() {
        assertThat(txElapsedLabel(-0.5f, 36.91)).isEqualTo("0:00 / 0:37")
        assertThat(txElapsedLabel(1.5f, 36.91)).isEqualTo("0:37 / 0:37")
    }

    // -- txRemaining -------------------------------------------------------------

    @Test
    fun `remaining seconds is total minus elapsed`() {
        // Scottie 1 rounds to 111 s total.
        assertThat(txRemainingSeconds(0f, 110.54332)).isEqualTo(111)
        assertThat(txRemainingSeconds(1f, 110.54332)).isEqualTo(0)
        // Robot 36 rounds to 37 s; halfway elapses 19 (round 18.5) → 18 left.
        assertThat(txRemainingSeconds(0.5f, 36.91)).isEqualTo(18)
    }

    @Test
    fun `remaining seconds stays in bounds for out-of-range progress`() {
        assertThat(txRemainingSeconds(-0.5f, 36.91)).isEqualTo(37)
        assertThat(txRemainingSeconds(1.5f, 36.91)).isEqualTo(0)
    }

    @Test
    fun `remaining seconds is zero for a non-positive duration`() {
        assertThat(txRemainingSeconds(0f, 0.0)).isEqualTo(0)
        assertThat(txRemainingSeconds(0f, -5.0)).isEqualTo(0)
    }

    @Test
    fun `remaining stays within zero and the rounded total for every mode`() {
        // The countdown must never disagree with the elapsed/total line by
        // going negative or exceeding the whole-second total shown there.
        for (mode in SstvMode.entries) {
            val total = mode.txDurationSeconds.roundToInt()
            for (p in listOf(0f, 0.1f, 0.37f, 0.5f, 0.8f, 1f)) {
                assertThat(txRemainingSeconds(p, mode.txDurationSeconds)).isIn(0..total)
            }
        }
    }

    @Test
    fun `remaining shrinks monotonically as progress advances`() {
        val total = SstvMode.SCOTTIE_1.txDurationSeconds
        var previous = txRemainingSeconds(0f, total)
        for (p in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 1f)) {
            val current = txRemainingSeconds(p, total)
            assertThat(current).isAtMost(previous)
            previous = current
        }
    }

    @Test
    fun `remaining label is a bare m ss countdown`() {
        assertThat(txRemainingLabel(0f, 110.54332)).isEqualTo("1:51")
        assertThat(txRemainingLabel(1f, 110.54332)).isEqualTo("0:00")
        assertThat(txRemainingLabel(0.5f, 36.91)).isEqualTo("0:18")
    }

    // -- initialTxMode ---------------------------------------------------------------

    @Test
    fun `initial mode falls back to Scottie 1 on null, blank, or garbage`() {
        assertThat(initialTxMode(null)).isEqualTo(SstvMode.SCOTTIE_1)
        assertThat(initialTxMode("")).isEqualTo(SstvMode.SCOTTIE_1)
        assertThat(initialTxMode("garbage")).isEqualTo(SstvMode.SCOTTIE_1)
        assertThat(initialTxMode("robot_36")).isEqualTo(SstvMode.SCOTTIE_1) // case-sensitive
    }

    @Test
    fun `every persisted mode name round-trips`() {
        for (mode in SstvMode.entries) {
            assertThat(initialTxMode(mode.name)).isEqualTo(mode)
        }
    }

    // -- applyPanZoomGesture ------------------------------------------------------------

    /** 1024x512 source into Scottie 1 (320x256): zoom-1 crop is 640x512. */
    private val comp = TxComposition(mode = SstvMode.SCOTTIE_1)

    private fun gesture(
        c: TxComposition = comp,
        srcW: Int = 1024,
        srcH: Int = 512,
        previewW: Float = 320f,
        previewH: Float = 256f,
        dx: Float = 0f,
        dy: Float = 0f,
        zoomFactor: Float = 1f,
    ) = applyPanZoomGesture(c, srcW, srcH, previewW, previewH, dx, dy, zoomFactor)

    @Test
    fun `pinch multiplies the zoom`() {
        assertThat(gesture(comp.copy(zoom = 2f), zoomFactor = 1.5f).zoom).isEqualTo(3f)
    }

    @Test
    fun `pinch clamps the zoom to 1 through 4`() {
        assertThat(gesture(comp.copy(zoom = 2f), zoomFactor = 100f).zoom).isEqualTo(4f)
        assertThat(gesture(comp.copy(zoom = 2f), zoomFactor = 0.01f).zoom).isEqualTo(1f)
    }

    @Test
    fun `dragging right decreases panX`() {
        // Crop 640 wide, overflowX = 384: dx=+50 px -> panX -= 50*(640/320)/(384/2).
        val out = gesture(dx = 50f)
        assertThat(out.panX).isWithin(1e-4f).of(-50f * 2f / 192f)
        assertThat(out.panX).isLessThan(0f)
    }

    @Test
    fun `dragging down decreases panY when the axis has overflow`() {
        // Zoomed 2x: crop 320x256, overflowY = 256.
        val out = gesture(comp.copy(zoom = 2f), dy = 30f)
        assertThat(out.panY).isWithin(1e-4f).of(-30f * 1f / 128f)
    }

    @Test
    fun `huge drags clamp the pan to the edge`() {
        assertThat(gesture(dx = 100_000f).panX).isEqualTo(-1f)
        assertThat(gesture(dx = -100_000f).panX).isEqualTo(1f)
    }

    @Test
    fun `zero-overflow axis is left untouched`() {
        // At zoom 1 the 1024x512 crop is full-height: overflowY = 0.
        val out = gesture(comp.copy(panY = 0.7f), dy = 40f)
        assertThat(out.panY).isEqualTo(0.7f)
    }

    @Test
    fun `NaN pan degrades to centered even on a zero-overflow axis`() {
        // overflowY = 0 for the full-height crop, so the old code would have
        // carried a NaN panY through every future copy; it must degrade to 0.
        val out = gesture(comp.copy(panX = Float.NaN, panY = Float.NaN))
        assertThat(out.panX).isEqualTo(0f)
        assertThat(out.panY).isEqualTo(0f)
    }

    @Test
    fun `pan conversion uses the crop at the new zoom`() {
        // Pinch to 2x while dragging: crop is 320 wide, overflowX = 704.
        val out = gesture(dx = 35.2f, zoomFactor = 2f)
        assertThat(out.zoom).isEqualTo(2f)
        assertThat(out.panX).isWithin(1e-4f).of(-35.2f * 1f / 352f)
    }

    @Test
    fun `degenerate sizes are a no-op`() {
        val moved = comp.copy(zoom = 2f, panX = 0.5f, panY = -0.5f)
        assertThat(gesture(moved, srcW = 0, dx = 50f, zoomFactor = 2f)).isEqualTo(moved)
        assertThat(gesture(moved, srcH = -1)).isEqualTo(moved)
        assertThat(gesture(moved, previewW = 0f)).isEqualTo(moved)
        assertThat(gesture(moved, previewH = 0f)).isEqualTo(moved)
    }
}
