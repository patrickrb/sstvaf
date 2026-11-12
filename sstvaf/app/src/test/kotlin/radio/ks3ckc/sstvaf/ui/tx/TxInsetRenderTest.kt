package radio.ks3ckc.sstvaf.ui.tx

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import radio.ks3ckc.sstvaf.sstv.SstvMode
import java.io.File

/**
 * The inset on the air: its rectangle in composite pixels and the pixels that
 * end up in the bitmap the transmitter encodes.
 *
 * NATIVE graphics mode for the reason [TxFlattenTest] gives - the legacy
 * shadows no-op drawBitmap, so every assertion here would pass on a blank
 * render. Also covers the editor-side geometry (hit test, selection box, drag)
 * against the same rectangle, because the whole point of that geometry is that
 * it agrees with what is drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TxInsetRenderTest {

    private val mode = SstvMode.SCOTTIE_1
    private val frameAspect = mode.width.toFloat() / mode.height

    private val grey = Color.rgb(128, 128, 128)
    private val red = Color.rgb(220, 20, 20)

    private fun greySource(): Bitmap =
        Bitmap.createBitmap(640, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(grey) }

    /** A received picture: solid red, so it cannot be confused with the source. */
    private fun received(w: Int = 320, h: Int = 256): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(red) }

    private fun cornered(
        corner: InsetCorner = InsetCorner.BOTTOM_RIGHT,
        size: Float = INSET_SIZE_MEDIUM,
        aspect: Float = 320f / 256f,
    ): ImageInset {
        val (x, y) = insetCornerCentre(corner, size, aspect, frameAspect)
        return ImageInset("file:///x.png", aspect, x, y, size)
    }

    private fun render(comp: TxComposition, insetBitmap: Bitmap?): Bitmap =
        renderComposite(greySource(), comp, mode.width, mode.height, insetBitmap)

    // ----- rectangle ----------------------------------------------------------

    @Test
    fun `the bottom right inset's rectangle is where the percentages say`() {
        val rect = insetRectPx(cornered(), mode.width, mode.height)
        // 33% of 320 and of 256; centred at 80.5%, 67.5%.
        assertThat(rect.width()).isEqualTo(106)
        assertThat(rect.height()).isEqualTo(84)
        assertThat(rect.left).isEqualTo(205)
        assertThat(rect.top).isEqualTo(131)
    }

    @Test
    fun `the rectangle is always inside the bitmap, whatever the centre`() {
        for ((x, y) in listOf(-50f to -50f, 150f to 150f, Float.NaN to 50f)) {
            val rect = insetRectPx(
                ImageInset("file:///x.png", 1.25f, x, y, INSET_SIZE_LARGE),
                mode.width, mode.height,
            )
            assertThat(rect.left).isAtLeast(0)
            assertThat(rect.top).isAtLeast(0)
            assertThat(rect.right).isAtMost(mode.width)
            assertThat(rect.bottom).isAtMost(mode.height)
            assertThat(rect.width()).isGreaterThan(0)
            assertThat(rect.height()).isGreaterThan(0)
        }
    }

    @Test
    fun `a degenerate composite size yields an empty rectangle`() {
        assertThat(insetRectPx(cornered(), 0, 256).isEmpty).isTrue()
        assertThat(insetRectPx(cornered(), 320, -1).isEmpty).isTrue()
    }

    @Test
    fun `the border is at least a pixel and scales with the short side`() {
        assertThat(insetBorderPx(320, 256)).isEqualTo(2)
        assertThat(insetBorderPx(40, 40)).isEqualTo(1)
        assertThat(insetBorderPx(800, 616)).isEqualTo(5)
    }

    // ----- pixels -------------------------------------------------------------

    @Test
    fun `the received picture is burned into the composite`() {
        val inset = cornered()
        val out = render(TxComposition(mode = mode, inset = inset), received())
        val rect = insetRectPx(inset, mode.width, mode.height)
        assertThat(out.getPixel(rect.centerX(), rect.centerY())).isEqualTo(red)
    }

    @Test
    fun `the inset carries a white border on all four edges`() {
        val inset = cornered()
        val out = render(TxComposition(mode = mode, inset = inset), received())
        val r = insetRectPx(inset, mode.width, mode.height)
        val white = Color.WHITE
        assertThat(out.getPixel(r.left, r.centerY())).isEqualTo(white)
        assertThat(out.getPixel(r.right - 1, r.centerY())).isEqualTo(white)
        assertThat(out.getPixel(r.centerX(), r.top)).isEqualTo(white)
        assertThat(out.getPixel(r.centerX(), r.bottom - 1)).isEqualTo(white)
        // ...and stops: two pixels in, it is the picture again.
        assertThat(out.getPixel(r.left + 3, r.centerY())).isEqualTo(red)
    }

    @Test
    fun `nothing outside the rectangle is touched`() {
        val inset = cornered()
        val out = render(TxComposition(mode = mode, inset = inset), received())
        val r = insetRectPx(inset, mode.width, mode.height)
        assertThat(out.getPixel(r.left - 1, r.centerY())).isEqualTo(grey)
        assertThat(out.getPixel(r.centerX(), r.top - 1)).isEqualTo(grey)
        assertThat(out.getPixel(r.right, r.centerY())).isEqualTo(grey)
        assertThat(out.getPixel(r.centerX(), r.bottom)).isEqualTo(grey)
        assertThat(out.getPixel(20, 128)).isEqualTo(grey)
    }

    @Test
    fun `an inset whose picture is missing draws nothing`() {
        // The composition says there is an inset but the file is gone: the
        // transmitted picture must be the plain one, not a white-bordered hole.
        val plain = render(TxComposition(mode = mode), null)
        val missing = render(TxComposition(mode = mode, inset = cornered()), null)
        assertThat(missing.sameAs(plain)).isTrue()
    }

    @Test
    fun `a picture with no inset in the composition is not drawn either`() {
        val plain = render(TxComposition(mode = mode), null)
        val stray = render(TxComposition(mode = mode), received())
        assertThat(stray.sameAs(plain)).isTrue()
    }

    @Test
    fun `text is drawn over the inset, not under it`() {
        // A bar dragged across the inset: the operator's own text wins.
        val inset = cornered()
        val r = insetRectPx(inset, mode.width, mode.height)
        val yPercent = r.centerY() * 100f / mode.height
        val bar = TextOverlay(id = "t1", text = "K1AF", yPercent = yPercent, style = OverlayStyle.BAR)
        val out = render(TxComposition(mode = mode, inset = inset, overlays = listOf(bar)), received())
        // Inside the inset, on the bar's band, clear of the centred glyphs:
        // the 70% black band has darkened the red.
        val px = out.getPixel(r.right - 6, r.centerY())
        assertThat(Color.red(px)).isLessThan(Color.red(red) / 2)
    }

    @Test
    fun `the burned-in frame is drawn over the inset`() {
        // An inset flush in the corner, under a Bold frame: the frame's edge
        // is still the outermost thing in the picture.
        val inset = ImageInset("file:///x.png", 1.25f, 100f, 100f, INSET_SIZE_MEDIUM)
        val out = render(
            TxComposition(mode = mode, inset = inset, frame = ImageFrame.BOLD),
            received(),
        )
        assertThat(out.getPixel(mode.width - 1, mode.height - 1))
            .isEqualTo(frameColorArgb(ImageFrame.BOLD))
    }

    @Test
    fun `photo adjustments do not touch the inset`() {
        // Brightening your own photo must not wash out their picture.
        val inset = cornered()
        val out = render(
            TxComposition(
                mode = mode,
                inset = inset,
                adjustments = ImageAdjustments(brightness = 150),
            ),
            received(),
        )
        val r = insetRectPx(inset, mode.width, mode.height)
        assertThat(out.getPixel(r.centerX(), r.centerY())).isEqualTo(red)
    }

    @Test
    fun `a recycled inset bitmap is skipped rather than crashing the render`() {
        val gone = received().apply { recycle() }
        val out = render(TxComposition(mode = mode, inset = cornered()), gone)
        assertThat(out.sameAs(render(TxComposition(mode = mode), null))).isTrue()
    }

    @Test
    fun `the whole received picture is scaled in, not cropped`() {
        // Left half blue, right half red, wider than the frame's shape. A
        // cover-crop would lose the outer columns; a whole-picture scale keeps
        // both colours right up to the border.
        val wide = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val blue = Color.rgb(20, 20, 220)
        for (x in 0 until 400) for (y in 0 until 200) wide.setPixel(x, y, if (x < 200) blue else red)
        val inset = cornered(aspect = 2f)
        val out = render(TxComposition(mode = mode, inset = inset), wide)
        val r = insetRectPx(inset, mode.width, mode.height)
        assertThat(r.width().toFloat() / r.height()).isWithin(0.05f).of(2f)
        assertThat(out.getPixel(r.left + 4, r.centerY())).isEqualTo(blue)
        assertThat(out.getPixel(r.right - 5, r.centerY())).isEqualTo(red)
    }

    // ----- editor geometry agrees with the render ------------------------------

    @Test
    fun `the hit test covers exactly the drawn rectangle`() {
        val inset = cornered()
        val r = insetRectPx(inset, mode.width, mode.height)
        fun xp(px: Int) = px * 100f / mode.width
        fun yp(px: Int) = px * 100f / mode.height
        assertThat(insetHitTest(inset, frameAspect, xp(r.centerX()), yp(r.centerY()))).isTrue()
        assertThat(insetHitTest(inset, frameAspect, xp(r.left + 2), yp(r.top + 2))).isTrue()
        assertThat(insetHitTest(inset, frameAspect, xp(r.left - 3), yp(r.centerY()))).isFalse()
        assertThat(insetHitTest(inset, frameAspect, xp(r.centerX()), yp(r.bottom + 3))).isFalse()
        assertThat(insetHitTest(inset, frameAspect, 10f, 10f)).isFalse()
    }

    @Test
    fun `there is nothing to hit without an inset`() {
        assertThat(insetHitTest(null, frameAspect, 50f, 50f)).isFalse()
    }

    @Test
    fun `the selection outline is the drawn rectangle, scaled to the canvas`() {
        val inset = cornered()
        val r = insetRectPx(inset, mode.width, mode.height)
        // A canvas three times the composite, as on a phone.
        val box = insetSelectionBox(inset, frameAspect, mode.width * 3f, mode.height * 3f)
        assertThat(box.left).isWithin(3f).of(r.left * 3f)
        assertThat(box.top).isWithin(3f).of(r.top * 3f)
        assertThat(box.width).isWithin(3f).of(r.width() * 3f)
        assertThat(box.height).isWithin(3f).of(r.height() * 3f)
    }

    @Test
    fun `a drag keeps the grabbed point under the finger`() {
        // Grabbed 5% right of and 4% below centre; finger now at (40, 30).
        val (x, y) = insetDragCentre(40f, 30f, grabOffsetX = 5f, grabOffsetY = 4f)
        assertThat(x).isEqualTo(35f)
        assertThat(y).isEqualTo(26f)
    }

    // ----- source file --------------------------------------------------------

    @Test
    fun `only a file uri resolves to an inset file`() {
        assertThat(insetSourceFile("file:///data/user/0/app/files/sstv_images/a.png"))
            .isEqualTo(File("/data/user/0/app/files/sstv_images/a.png"))
        assertThat(insetSourceFile("content://media/external/images/12")).isNull()
        assertThat(insetSourceFile("https://example.com/a.png")).isNull()
        assertThat(insetSourceFile("")).isNull()
        assertThat(insetSourceFile("not a uri")).isNull()
    }

    @Test
    fun `a missing inset file loads as null rather than throwing`() {
        assertThat(loadInsetBitmap("file:///definitely/not/here.png")).isNull()
        assertThat(loadInsetBitmap("content://nope")).isNull()
    }
}
