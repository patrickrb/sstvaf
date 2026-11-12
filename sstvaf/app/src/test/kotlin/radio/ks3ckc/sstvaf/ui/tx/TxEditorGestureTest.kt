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
import radio.ks3ckc.sstvaf.sstv.TxOutcome
import radio.ks3ckc.sstvaf.sstv.TxImageWindow

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
        transmitting: Boolean = false,
        outcome: TxOutcome? = null,
        onTool: (TxTool) -> Unit = {},
    ) {
        var composition by remember { mutableStateOf(initial) }
        var selected by remember { mutableStateOf<String?>(null) }
        // The tool is state, as on the real screen: touching the inset
        // switches to its tool from inside the gesture.
        var activeTool by remember { mutableStateOf(tool) }
        onComposition(composition)
        onTool(activeTool)

        Box(modifier = Modifier.size(300.dp)) {
            TxEditorCanvas(
                modifier = Modifier.testTag(CANVAS_TAG),
                preview = blankPreview(),
                composition = composition,
                tool = activeTool,
                selectedOverlayId = selected,
                onInsetTouched = {
                    selected = null
                    activeTool = TxTool.INSET
                },
                onInsetMovedTo = { x, y ->
                    composition = composition.withInsetMoved(x, y)
                },
                editable = !transmitting && outcome == null,
                transmitting = transmitting,
                transmitProgress = 0f,
                outcome = outcome,
                imageWindow = TxImageWindow.WHOLE,
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
                onEditAgain = {},
                onNewPicture = {},
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

    // ----- lockout while keyed or showing an outcome --------------------------

    @Test
    fun `the canvas takes no edits while the rig is keyed`() {
        // The audio buffer is encoded and playing by then, so a stroke could
        // not reach the air and would only make the preview disagree with what
        // the far end received.
        var latest: TxComposition? = null
        rule.setContent {
            Harness(
                tool = TxTool.DRAW,
                initial = TxComposition(mode = mode),
                onComposition = { latest = it },
                transmitting = true,
            )
        }

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(center)
            moveBy(Offset(20f, 0f))
            up()
        }
        rule.waitForIdle()

        assertThat(latest!!.paths).isEmpty()
    }

    @Test
    fun `the canvas takes no edits while an outcome is on screen`() {
        var latest: TxComposition? = null
        rule.setContent {
            Harness(
                tool = TxTool.DRAW,
                initial = TxComposition(mode = mode),
                onComposition = { latest = it },
                outcome = TxOutcome.COMPLETED,
            )
        }

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(center)
            moveBy(Offset(20f, 0f))
            up()
        }
        rule.waitForIdle()

        assertThat(latest!!.paths).isEmpty()
    }

    // ----- inset dragging -----------------------------------------------------

    /** A Scottie-shaped inset on the bottom-right preset: centre (80.5%, 67.5%). */
    private fun corneredInset(): ImageInset = newImageInset(
        sourceUri = "file:///x/rx.png",
        aspect = mode.width.toFloat() / mode.height,
        caption = "",
        frameAspect = mode.width.toFloat() / mode.height,
    )

    @Test
    fun `dragging the inset moves it by the distance the finger travelled`() {
        var latest: TxComposition? = null
        rule.setContent {
            Harness(
                tool = TxTool.INSET,
                initial = TxComposition(mode = mode, inset = corneredInset()),
                onComposition = { latest = it },
            )
        }

        // Grabbed off-centre on purpose. Snapping the centre to the finger -
        // what a text overlay does - would add that offset to the move.
        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(Offset(width * 0.86f, height * 0.72f))
            moveBy(Offset(-width * 0.10f, 0f))
            moveBy(Offset(-width * 0.10f, -height * 0.10f))
            up()
        }
        rule.waitForIdle()

        val moved = latest!!.inset!!
        assertThat(moved.xPercent).isWithin(1.5f).of(80.5f - 20f)
        assertThat(moved.yPercent).isWithin(1.5f).of(67.5f - 10f)
    }

    @Test
    fun `grabbing the inset from another tool switches tool and still drags`() {
        // The touch that selects the inset is the same touch that moves it.
        // While the gesture loop was keyed on the tool, switching tool here
        // restarted it and the drag died after its first event.
        var latest: TxComposition? = null
        var tool: TxTool? = null
        rule.setContent {
            Harness(
                tool = TxTool.TEXT,
                initial = TxComposition(mode = mode, zoom = 2f, inset = corneredInset()),
                onComposition = { latest = it },
                onTool = { tool = it },
            )
        }

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(Offset(width * 0.80f, height * 0.67f))
            moveBy(Offset(-width * 0.10f, 0f))
            moveBy(Offset(-width * 0.10f, 0f))
            moveBy(Offset(-width * 0.10f, 0f))
            up()
        }
        rule.waitForIdle()

        assertThat(tool).isEqualTo(TxTool.INSET)
        assertThat(latest!!.inset!!.xPercent).isWithin(1.5f).of(80.5f - 30f)
        // ...and the crop underneath did not pan along with it.
        assertThat(latest!!.panX).isEqualTo(0f)
        assertThat(latest!!.panY).isEqualTo(0f)
    }

    @Test
    fun `a drag cannot push any of the inset off the picture`() {
        var latest: TxComposition? = null
        rule.setContent {
            Harness(
                tool = TxTool.INSET,
                initial = TxComposition(mode = mode, inset = corneredInset()),
                onComposition = { latest = it },
            )
        }

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(Offset(width * 0.80f, height * 0.67f))
            moveBy(Offset(width * 0.15f, height * 0.25f))
            moveBy(Offset(width * 0.15f, height * 0.25f))
            up()
        }
        rule.waitForIdle()

        // Parked against the bottom-right of the frame: centre = 100 - half.
        val parked = latest!!.inset!!
        assertThat(parked.xPercent).isWithin(0.01f).of(100f - 16.5f)
        assertThat(parked.yPercent).isWithin(0.01f).of(100f - 16.5f)
    }

    @Test
    fun `text lying over the inset is what a touch there grabs`() {
        // The text is drawn on top, so it is what the operator is reaching for.
        val overlay = TextOverlay(
            id = "t1",
            text = "K1AF",
            xPercent = 80.5f,
            yPercent = 67.5f,
            style = OverlayStyle.OUTLINE,
        )
        val inset = corneredInset()
        var latest: TxComposition? = null
        rule.setContent {
            Harness(
                tool = TxTool.TEXT,
                initial = TxComposition(mode = mode, inset = inset, overlays = listOf(overlay)),
                onComposition = { latest = it },
            )
        }

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(Offset(width * 0.805f, height * 0.675f))
            moveBy(Offset(0f, -height * 0.10f))
            moveBy(Offset(0f, -height * 0.10f))
            up()
        }
        rule.waitForIdle()

        assertThat(latest!!.overlays.single().yPercent).isLessThan(55f)
        assertThat(latest!!.inset).isEqualTo(inset)
    }

    @Test
    fun `the inset cannot be moved while the rig is keyed`() {
        val inset = corneredInset()
        var latest: TxComposition? = null
        rule.setContent {
            Harness(
                tool = TxTool.INSET,
                initial = TxComposition(mode = mode, inset = inset),
                onComposition = { latest = it },
                transmitting = true,
            )
        }

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(Offset(width * 0.80f, height * 0.67f))
            moveBy(Offset(-width * 0.20f, 0f))
            up()
        }
        rule.waitForIdle()

        assertThat(latest!!.inset).isEqualTo(inset)
    }

    @Test
    fun `a crop pan that starts on the inset pans the picture, not the inset`() {
        // The inset can cover two fifths of the frame. If it took every touch
        // that began on it, Crop could not pan from that part of the picture.
        val inset = corneredInset()
        var latest: TxComposition? = null
        var tool: TxTool? = null
        rule.setContent {
            Harness(
                tool = TxTool.CROP,
                initial = TxComposition(mode = mode, zoom = 2f, inset = inset),
                onComposition = { latest = it },
                onTool = { tool = it },
            )
        }

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(Offset(width * 0.80f, height * 0.67f))
            moveBy(Offset(-width * 0.10f, 0f))
            moveBy(Offset(-width * 0.10f, 0f))
            up()
        }
        rule.waitForIdle()

        assertThat(tool).isEqualTo(TxTool.CROP)
        assertThat(latest!!.inset).isEqualTo(inset)
        assertThat(latest!!.panX).isNotEqualTo(0f)
    }

    @Test
    fun `a stroke can start on the inset while drawing`() {
        val inset = corneredInset()
        var latest: TxComposition? = null
        rule.setContent {
            Harness(
                tool = TxTool.DRAW,
                initial = TxComposition(mode = mode, inset = inset),
                onComposition = { latest = it },
            )
        }

        rule.onNodeWithTag(CANVAS_TAG).performTouchInput {
            down(Offset(width * 0.80f, height * 0.67f))
            moveBy(Offset(-width * 0.10f, 0f))
            moveBy(Offset(-width * 0.10f, 0f))
            up()
        }
        rule.waitForIdle()

        assertThat(latest!!.inset).isEqualTo(inset)
        assertThat(latest!!.paths.single().points.size).isAtLeast(3)
    }
}
