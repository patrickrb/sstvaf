package radio.ks3ckc.sstvaf.ui.tx

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import radio.ks3ckc.sstvaf.sstv.SstvMode
import kotlin.math.abs

/**
 * Gesture regression tests for the composer canvas.
 *
 * [TxEditorGeometryTest] covers the pure hit-testing and coordinate maths, and
 * passed throughout while the canvas was badly broken — because the bug was not
 * in the geometry but in the *state the gesture loop read*. The canvas gesture
 * coroutine outlives the composition that launched it, so a callback closing
 * over a captured composition recomputed every event from the same starting
 * value: a whole pan gesture collapsed to its final event, and a freehand
 * stroke to a single dot. Keying the loop on the overlay list had a matching
 * failure, restarting the coroutine on the first move of a drag and cancelling
 * the drag underway.
 *
 * None of that is visible to a test that calls the helpers directly, which is
 * the point of this file: it drives real multi-event pointer input through the
 * composable and asserts on the state that comes out.
 *
 * The harness wires the callbacks exactly as `TxComposeScreen` does, reading
 * live state on every event, so a regression to capturing state fails here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TxEditorGestureTest {

    /** The canvas is driven directly: the root box is larger than the frame. */
    private val CANVAS_TAG = "editorCanvas"

    @get:Rule
    val rule = createComposeRule()

    private val mode = SstvMode.SCOTTIE_1

    /**
     * Mounts the canvas over a fixed 300dp box with the screen's own callbacks.
     * Returns nothing; the caller reads [state] after driving input.
     */
    @Composable
    private fun Harness(
        tool: TxTool,
        initial: TxComposition,
        onComposition: (TxComposition) -> Unit,
    ) {
        var composition by remember { mutableStateOf(initial) }
        var selected by remember { mutableStateOf<String?>(null) }
        onComposition(composition)

        Box(modifier = Modifier.size(300.dp)) {
            TxEditorCanvas(
                modifier = Modifier.testTag(CANVAS_TAG),
                preview = blankPreview(),
                composition = composition,
                tool = tool,
                selectedOverlayId = selected,
                editable = true,
                onPanBy = { dx, dy ->
                    composition = composition.copy(
                        panX = panStep(composition.panX, dx, composition.zoom),
                        panY = panStep(composition.panY, dy, composition.zoom),
                    )
                },
                onZoomBy = { scale ->
                    composition = composition.withClampedView(zoom = composition.zoom * scale)
                },
                onOverlayTouched = { selected = it },
                onOverlayMovedTo = { id, x, y ->
                    composition = composition.withOverlayMoved(id, x, y)
                },
                onDeselect = { selected = null },
                onStrokeStart = { x, y ->
                    composition = composition.withStrokeStarted(
                        OVERLAY_COLOR_WHITE, STROKE_MEDIUM, PathPoint(x, y),
                    )
                },
                onStrokeExtend = { x, y ->
                    composition = composition.withStrokeExtended(PathPoint(x, y))
                },
                onDeleteSelected = {},
                onChangePhoto = {},
                onClearImage = {},
            )
        }
    }

    private fun blankPreview(): Bitmap =
        Bitmap.createBitmap(mode.width, mode.height, Bitmap.Config.ARGB_8888)

    // ----- freehand drawing ---------------------------------------------------

    @Test
    fun `a freehand stroke keeps every point, not just the first`() {
        var latest: TxComposition? = null
        rule.setContent {
            Harness(
                tool = TxTool.DRAW,
                initial = TxComposition(mode = mode),
                onComposition = { latest = it },
            )
        }

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(center)
            moveBy(Offset(20f, 0f))
            moveBy(Offset(20f, 0f))
            moveBy(Offset(20f, 20f))
            up()
        }
        rule.waitForIdle()

        val path = latest?.paths?.singleOrNull()
        assertThat(path).isNotNull()
        // The regression this guards: with a stale composition the extension
        // was a no-op and every gesture flattened to one dot.
        assertThat(path!!.points.size).isAtLeast(3)
    }

    @Test
    fun `a stroke records distinct positions rather than repeating one`() {
        var latest: TxComposition? = null
        rule.setContent {
            Harness(
                tool = TxTool.DRAW,
                initial = TxComposition(mode = mode),
                onComposition = { latest = it },
            )
        }

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(center)
            moveBy(Offset(30f, 0f))
            moveBy(Offset(30f, 0f))
            up()
        }
        rule.waitForIdle()

        val xs = latest?.paths?.single()?.points?.map { it.xPercent }?.distinct()
        assertThat(xs!!.size).isAtLeast(2)
    }

    // ----- panning ------------------------------------------------------------

    @Test
    fun `pan accumulates across events instead of keeping only the last`() {
        var latest: TxComposition? = null
        // Zoomed in, or the clamp pins the pan to zero: at 1x there is no
        // slack to pan into, which is correct behaviour and would mask this.
        rule.setContent {
            Harness(
                tool = TxTool.CROP,
                initial = TxComposition(mode = mode).withClampedView(zoom = 2f),
                onComposition = { latest = it },
            )
        }

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-12f, 0f))
            up()
        }
        rule.waitForIdle()
        val afterOne = latest!!.panX

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-12f, 0f))
            moveBy(Offset(-12f, 0f))
            moveBy(Offset(-12f, 0f))
            up()
        }
        rule.waitForIdle()
        val afterFour = latest!!.panX

        // Four equal steps must move the crop further than one. With the stale
        // capture every step recomputed from the same starting pan, so a long
        // drag moved exactly as far as a single event. The sign is whatever
        // panStep's inversion produces; the distance is the point.
        assertThat(abs(afterOne)).isGreaterThan(0f)
        assertThat(abs(afterFour)).isGreaterThan(abs(afterOne))
    }

    // ----- overlay dragging ---------------------------------------------------

    @Test
    fun `an overlay drag survives past its first move event`() {
        val overlay = TextOverlay(
            id = "t1",
            text = "K1AF",
            xPercent = 50f,
            yPercent = 50f,
            colorArgb = OVERLAY_COLOR_WHITE,
            sizeFraction = OVERLAY_SIZE_MEDIUM,
            style = OverlayStyle.OUTLINE,
        )
        var latest: TxComposition? = null
        rule.setContent {
            Harness(
                tool = TxTool.TEXT,
                initial = TxComposition(mode = mode, overlays = listOf(overlay)),
                onComposition = { latest = it },
            )
        }

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, -30f))
            moveBy(Offset(0f, -30f))
            up()
        }
        rule.waitForIdle()

        // Moving the overlay changes composition.overlays. While that list was
        // a pointerInput key, the change restarted the gesture coroutine and
        // the drag stopped after one event, stranding the overlay near where it
        // started.
        val moved = latest?.overlays?.single()
        assertThat(moved!!.yPercent).isLessThan(45f)
    }

    @Test
    fun `dragging an overlay does not pan the picture`() {
        val overlay = TextOverlay(
            id = "t1",
            text = "K1AF",
            xPercent = 50f,
            yPercent = 50f,
            colorArgb = OVERLAY_COLOR_WHITE,
            sizeFraction = OVERLAY_SIZE_MEDIUM,
            style = OverlayStyle.OUTLINE,
        )
        var latest: TxComposition? = null
        rule.setContent {
            Harness(
                tool = TxTool.CROP,
                initial = TxComposition(mode = mode, overlays = listOf(overlay))
                    .withClampedView(zoom = 2f),
                onComposition = { latest = it },
            )
        }

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-20f, 0f))
            up()
        }
        rule.waitForIdle()

        // An overlay under the finger wins over the tool, so the crop must not
        // move as well - otherwise one drag does two things.
        assertThat(latest!!.panX).isEqualTo(0f)
    }

    // ----- deselection --------------------------------------------------------

    @Test
    fun `a tap on empty canvas with a text tool does not start a stroke`() {
        var latest: TxComposition? = null
        rule.setContent {
            Harness(
                tool = TxTool.TEXT,
                initial = TxComposition(mode = mode),
                onComposition = { latest = it },
            )
        }

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()

        assertThat(latest!!.paths).isEmpty()
    }
}
