package radio.ks3ckc.sstvaf.ui.tx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the composer canvas's geometry: touch-to-percentage
 * conversion, overlay hit-testing, the selection box, and the crop pan step.
 *
 * This is where dragging is either right or wrong, and none of it needs a
 * display to check.
 */
class TxEditorGeometryTest {

    // ----- touch to frame percent --------------------------------------------

    @Test
    fun `a touch maps to its percentage of the canvas`() {
        assertThat(toFramePercent(0f, 0f, 400f, 320f)).isEqualTo(0f to 0f)
        assertThat(toFramePercent(200f, 160f, 400f, 320f)).isEqualTo(50f to 50f)
        assertThat(toFramePercent(400f, 320f, 400f, 320f)).isEqualTo(100f to 100f)
    }

    @Test
    fun `a touch outside the canvas clamps to the edge`() {
        // A drag that leaves the canvas parks the overlay at the edge rather
        // than flinging it somewhere unreachable.
        assertThat(toFramePercent(-80f, 500f, 400f, 320f)).isEqualTo(0f to 100f)
    }

    @Test
    fun `a degenerate canvas yields the centre`() {
        // Before layout the canvas has no size; centring is the safe answer.
        assertThat(toFramePercent(10f, 10f, 0f, 0f)).isEqualTo(50f to 50f)
    }

    // ----- hit testing --------------------------------------------------------

    private fun bar(id: String, y: Float, size: Float = OVERLAY_SIZE_MEDIUM) =
        TextOverlay(id = id, text = "K1AF", xPercent = 50f, yPercent = y, sizeFraction = size, style = OverlayStyle.BAR)

    private fun outline(id: String, x: Float, y: Float, text: String = "K1AF") =
        TextOverlay(id = id, text = text, xPercent = x, yPercent = y, style = OverlayStyle.OUTLINE)

    @Test
    fun `a bar is grabbed anywhere along its width`() {
        val overlays = listOf(bar("cs", 8f))
        assertThat(overlayHitTest(overlays, 2f, 8f)?.id).isEqualTo("cs")
        assertThat(overlayHitTest(overlays, 98f, 8f)?.id).isEqualTo("cs")
    }

    @Test
    fun `a touch away from a bar vertically misses it`() {
        val overlays = listOf(bar("cs", 8f))
        assertThat(overlayHitTest(overlays, 50f, 60f)).isNull()
    }

    @Test
    fun `an outline overlay is only grabbed near its text`() {
        val overlays = listOf(outline("t1", 20f, 50f))
        assertThat(overlayHitTest(overlays, 20f, 50f)?.id).isEqualTo("t1")
        assertThat(overlayHitTest(overlays, 90f, 50f)).isNull()
    }

    @Test
    fun `a larger overlay has a larger target`() {
        val small = listOf(bar("s", 50f, OVERLAY_SIZE_SMALL))
        val large = listOf(bar("l", 50f, OVERLAY_SIZE_LARGE))
        val offset = 50f + overlayTouchHalfHeightPercent(OVERLAY_SIZE_SMALL) + 1f
        // Just outside the small overlay's target...
        assertThat(overlayHitTest(small, 50f, offset)).isNull()
        // ...and still inside the large one's.
        assertThat(overlayHitTest(large, 50f, offset)?.id).isEqualTo("l")
    }

    @Test
    fun `a small overlay still has a usable target`() {
        // Without a floor, a Small overlay on a tall mode is a few pixels high.
        assertThat(overlayTouchHalfHeightPercent(0.001f)).isEqualTo(MIN_TOUCH_HALF_PERCENT)
    }

    @Test
    fun `the topmost overlay wins where two overlap`() {
        // Later overlays draw over earlier ones, so a tap must grab what the
        // operator can actually see.
        val overlays = listOf(bar("under", 50f), bar("over", 50f))
        assertThat(overlayHitTest(overlays, 50f, 50f)?.id).isEqualTo("over")
    }

    @Test
    fun `an empty overlay list is a miss`() {
        assertThat(overlayHitTest(emptyList(), 50f, 50f)).isNull()
    }

    @Test
    fun `a longer outline overlay has a wider target`() {
        val short = outline("a", 50f, 50f, text = "K")
        val long = outline("b", 50f, 50f, text = "CQ SSTV DE K1AF")
        assertThat(overlayTouchHalfWidthPercent(long))
            .isGreaterThan(overlayTouchHalfWidthPercent(short))
    }

    @Test
    fun `an outline target never exceeds the frame`() {
        val huge = outline("a", 50f, 50f, text = "X".repeat(200))
        assertThat(overlayTouchHalfWidthPercent(huge)).isAtMost(50f)
    }

    // ----- selection box ------------------------------------------------------

    @Test
    fun `the selection box matches the touch target`() {
        // What the operator sees selected must be what they can grab.
        val overlay = bar("cs", 50f)
        val box = overlaySelectionBox(overlay, widthPx = 400f, heightPx = 320f)
        val expectedHalfHeight = overlayTouchHalfHeightPercent(overlay.sizeFraction) / 100f * 320f
        assertThat(box.height).isWithin(0.01f).of(expectedHalfHeight * 2f)
        assertThat(box.width).isWithin(0.01f).of(400f)
    }

    @Test
    fun `the selection box is clipped to the canvas`() {
        val overlay = bar("cs", 0f)
        val box = overlaySelectionBox(overlay, widthPx = 400f, heightPx = 320f)
        assertThat(box.top).isEqualTo(0f)
        assertThat(box.left).isAtLeast(0f)
        assertThat(box.height).isAtMost(320f)
    }

    @Test
    fun `the selection box never has a negative size`() {
        val box = overlaySelectionBox(bar("cs", 50f), widthPx = 0f, heightPx = 0f)
        assertThat(box.width).isAtLeast(0f)
        assertThat(box.height).isAtLeast(0f)
    }

    // ----- crop pan -----------------------------------------------------------

    @Test
    fun `panning does nothing at 1x`() {
        // At 1x the crop fills the frame: there is no overflow to move across.
        assertThat(panStep(currentPan = 0f, dxFraction = 0.5f, zoom = 1f)).isEqualTo(0f)
    }

    @Test
    fun `the picture follows the finger`() {
        // Dragging right reveals more of the left of the photo, so the pan
        // value moves the opposite way to the finger.
        val panned = panStep(currentPan = 0f, dxFraction = 0.2f, zoom = 2f)
        assertThat(panned).isLessThan(0f)
        val other = panStep(currentPan = 0f, dxFraction = -0.2f, zoom = 2f)
        assertThat(other).isGreaterThan(0f)
    }

    @Test
    fun `panning stays inside its bounds`() {
        assertThat(panStep(currentPan = 0.9f, dxFraction = -5f, zoom = 2f)).isEqualTo(1f)
        assertThat(panStep(currentPan = -0.9f, dxFraction = 5f, zoom = 2f)).isEqualTo(-1f)
    }

    @Test
    fun `a deeper zoom moves the crop less for the same drag`() {
        // Otherwise panning becomes uncontrollable the further in you go.
        val shallow = kotlin.math.abs(panStep(0f, 0.1f, zoom = 1.2f))
        val deep = kotlin.math.abs(panStep(0f, 0.1f, zoom = 4f))
        assertThat(deep).isLessThan(shallow)
    }

    @Test
    fun `a NaN zoom degrades to no panning`() {
        assertThat(panStep(currentPan = 0.3f, dxFraction = 0.5f, zoom = Float.NaN))
            .isEqualTo(0.3f)
    }

    // ----- zoom slider mapping ------------------------------------------------

    @Test
    fun `the zoom slider maps to and from composition zoom`() {
        assertThat(zoomToSlider(1f)).isEqualTo(100)
        assertThat(zoomToSlider(2.5f)).isEqualTo(250)
        assertThat(sliderToZoom(100)).isEqualTo(1f)
        assertThat(sliderToZoom(250)).isEqualTo(2.5f)
    }

    @Test
    fun `a pinch past the slider's top still shows as its maximum`() {
        // The slider stops at 2.5x but a pinch can go to 4x; the thumb parks at
        // the end rather than reporting a value off its own scale.
        assertThat(zoomToSlider(4f)).isEqualTo(ZOOM_SLIDER_MAX)
    }

    @Test
    fun `the slider cannot request a zoom outside its range`() {
        assertThat(sliderToZoom(-50)).isEqualTo(1f)
        assertThat(sliderToZoom(9000)).isEqualTo(ZOOM_SLIDER_MAX / 100f)
    }

    @Test
    fun `fill frame zooms in rather than resetting`() {
        assertThat(FILL_FRAME_ZOOM).isGreaterThan(1f)
        assertThat(FILL_FRAME_ZOOM).isAtMost(ZOOM_SLIDER_MAX / 100f)
    }

    // ----- editor draft -------------------------------------------------------

    @Test
    fun `selecting an overlay pulls its settings into the draft`() {
        val overlay = TextOverlay(
            id = "t1",
            text = "HELLO",
            colorArgb = OVERLAY_COLOR_AMBER,
            sizeFraction = OVERLAY_SIZE_LARGE,
            style = OverlayStyle.OUTLINE,
        )
        val draft = TxEditorDraft().matching(overlay)
        assertThat(draft.text).isEqualTo("HELLO")
        assertThat(draft.colorArgb).isEqualTo(OVERLAY_COLOR_AMBER)
        assertThat(draft.sizeFraction).isEqualTo(OVERLAY_SIZE_LARGE)
        assertThat(draft.style).isEqualTo(OverlayStyle.OUTLINE)
    }

    @Test
    fun `clearing the draft keeps the brush settings`() {
        // The text is committed; the colour and size are still what the
        // operator wants for the next overlay.
        val draft = TxEditorDraft(text = "HELLO", colorArgb = OVERLAY_COLOR_CYAN).cleared()
        assertThat(draft.text).isEmpty()
        assertThat(draft.colorArgb).isEqualTo(OVERLAY_COLOR_CYAN)
    }
}
