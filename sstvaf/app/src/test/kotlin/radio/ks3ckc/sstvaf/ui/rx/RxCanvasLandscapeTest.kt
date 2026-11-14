package radio.ks3ckc.sstvaf.ui.rx

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Issue #145: on a phone turned to landscape the Receive canvas painted over
 * the MODE / RX row above it, so the mode-lock chip and the receive switch
 * "disappeared" (a Discord tester on android-dev.326).
 *
 * The canvas was `fillMaxWidth().aspectRatio(4:3)` inside a slot capped at
 * 56% of the screen height. On a 914 x 411dp landscape phone the full width
 * wants a ~630dp-tall canvas, the cap allows 230dp, `aspectRatio` cannot
 * satisfy both and falls back to ignoring the cap, and the oversized box is
 * centred on the slot - spilling ~200dp up over the controls (and down over
 * the status card). The canvas must instead shrink to fit the cap, keeping
 * its aspect, and stay inside its slot.
 *
 * Compose reports an overrunning child's bounds clamped to its slot, so a
 * "canvas top >= controls bottom" assertion passes even on the broken code.
 * The tell that does survive is the shape: on origin/dev the surface measures
 * 778 x 230dp (cap height, full width - not 4:3), which is what the
 * fits-the-cap test fails on there.
 */
@RunWith(AndroidJUnit4::class)
// Wide enough that the test widths below are not clamped by the window.
@Config(sdk = [34], qualifiers = "w1200dp-h2400dp")
class RxCanvasLandscapeTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setCanvas(width: Dp, screenHeightDp: Int, aspect: Float = 4f / 3f) {
        val cap = rxCanvasMaxHeightDp(screenHeightDp).dp
        rule.setContent {
            Box(Modifier.size(width = width, height = 2000.dp), contentAlignment = Alignment.TopStart) {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.testTag("controls").fillMaxWidth().height(40.dp))
                    RxCanvas(
                        image = null,
                        aspect = aspect,
                        revealFraction = 0f,
                        showsImage = false,
                        listening = true,
                        receiveEnabled = true,
                        frequencyLabel = "14.230 MHz · 20m",
                        completedModeName = null,
                        showsSavedBadge = false,
                        heightCap = cap,
                    )
                    Box(Modifier.testTag("below").fillMaxWidth().height(40.dp))
                }
            }
        }
    }

    @Test
    fun `landscape phone - the canvas fits the height cap and keeps 4 by 3`() {
        setCanvas(width = 778.dp, screenHeightDp = 411)
        val canvas = rule.onNodeWithTag(RX_CANVAS_SURFACE_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val cap = rxCanvasMaxHeightDp(411).toFloat()
        assertThat(canvas.height.value).isWithin(0.5f).of(cap)
        assertThat(canvas.width.value).isWithin(1f).of(cap * 4f / 3f)
    }

    @Test
    fun `portrait phone - the canvas still fills the width`() {
        // 380dp content width, 915dp tall: 4:3 of the width (285dp) is under
        // the cap (512dp), so nothing about portrait changes.
        setCanvas(width = 380.dp, screenHeightDp = 915)
        val canvas = rule.onNodeWithTag(RX_CANVAS_SURFACE_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertThat(canvas.width.value).isWithin(0.5f).of(380f)
        assertThat(canvas.height.value).isWithin(1f).of(380f * 3f / 4f)
    }

    @Test
    fun `tall mode in landscape - a 1 to 1 canvas also fits the cap`() {
        // Robot 36 and friends are 4:3, but PD/Pasokon variants with other
        // aspects go through the same path; the fit must not assume 4:3.
        setCanvas(width = 778.dp, screenHeightDp = 411, aspect = 1f)
        val canvas = rule.onNodeWithTag(RX_CANVAS_SURFACE_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val controls = rule.onNodeWithTag("controls").getUnclippedBoundsInRoot()
        assertThat(canvas.height.value).isAtMost(rxCanvasMaxHeightDp(411) + 0.5f)
        assertThat(canvas.top.value).isAtLeast(controls.bottom.value - 0.5f)
    }

    @Test
    fun `fit - full width when 4 by 3 is under the cap`() {
        val (w, h) = rxCanvasFitSizeDp(maxWidthDp = 380f, maxHeightDp = 512f, aspect = 4f / 3f)
        assertThat(w).isWithin(0.01f).of(380f)
        assertThat(h).isWithin(0.01f).of(285f)
    }

    @Test
    fun `fit - cap height and narrowed when full width would overrun`() {
        val (w, h) = rxCanvasFitSizeDp(maxWidthDp = 778f, maxHeightDp = 230f, aspect = 4f / 3f)
        assertThat(h).isWithin(0.01f).of(230f)
        assertThat(w).isWithin(0.01f).of(230f * 4f / 3f)
    }

    @Test
    fun `fit - never taller than the cap or wider than the slot`() {
        for (width in listOf(200f, 380f, 600f, 778f, 1200f)) {
            for (cap in listOf(140f, 230f, 512f)) {
                for (aspect in listOf(0.5f, 1f, 4f / 3f, 2f)) {
                    val (w, h) = rxCanvasFitSizeDp(width, cap, aspect)
                    assertThat(h).isAtMost(cap + 0.01f)
                    assertThat(w).isAtMost(width + 0.01f)
                    assertThat(w / h).isWithin(0.001f).of(aspect)
                }
            }
        }
    }

    @Test
    fun `fit - a bad aspect falls back to 4 by 3 rather than NaN`() {
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val (w, h) = rxCanvasFitSizeDp(maxWidthDp = 400f, maxHeightDp = 600f, aspect = bad)
            assertThat(w).isWithin(0.01f).of(400f)
            assertThat(h).isWithin(0.01f).of(300f)
        }
    }
}
