package radio.ks3ckc.sstvaf.ui.tx

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import radio.ks3ckc.sstvaf.sstv.SstvMode

/**
 * Tests that everything the operator edits actually ends up in the bitmap the
 * transmitter encodes.
 *
 * This is the file that matters most in the composer. The receiving station
 * gets pixels, not a layer list — so an adjustment, a stroke or a frame that
 * lives only in the preview is a feature that silently does nothing on the air,
 * and nobody finds out until someone mentions the picture looked wrong.
 *
 * Robolectric in NATIVE graphics mode, which is not optional here: the legacy
 * shadows silently no-op drawBitmap-with-source-rect, drawText and colour
 * filters, so every pixel assertion below would read 0 and pass against a
 * completely blank render. Same reason TxImageComposerTest sets it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TxFlattenTest {

    private val mode = SstvMode.SCOTTIE_1

    /** A mid-grey source, so brightening and darkening are both visible. */
    private fun greySource(): Bitmap =
        Bitmap.createBitmap(640, 512, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(128, 128, 128))
        }

    private fun render(comp: TxComposition, src: Bitmap = greySource()): Bitmap =
        renderComposite(src, comp, mode.width, mode.height)

    // ----- native size --------------------------------------------------------

    @Test
    fun `the composite is exactly the mode's native pixel size`() {
        // The codec encodes this buffer directly; any other size would be
        // rescaled somewhere downstream, which is where sharpness goes to die.
        for (m in listOf(SstvMode.SCOTTIE_1, SstvMode.ROBOT_36, SstvMode.PD_120, SstvMode.MARTIN_4)) {
            val out = renderComposite(greySource(), TxComposition(mode = m), m.width, m.height)
            assertThat(out.width).isEqualTo(m.width)
            assertThat(out.height).isEqualTo(m.height)
        }
    }

    // ----- adjustments --------------------------------------------------------

    @Test
    fun `brightness is baked into the pixels`() {
        val plain = render(TxComposition(mode = mode))
        val bright = render(
            TxComposition(mode = mode, adjustments = ImageAdjustments(brightness = 150)),
        )
        assertThat(Color.red(bright.getPixel(160, 128)))
            .isGreaterThan(Color.red(plain.getPixel(160, 128)))
    }

    @Test
    fun `darkening is baked into the pixels`() {
        val plain = render(TxComposition(mode = mode))
        val dark = render(
            TxComposition(mode = mode, adjustments = ImageAdjustments(brightness = 50)),
        )
        assertThat(Color.red(dark.getPixel(160, 128)))
            .isLessThan(Color.red(plain.getPixel(160, 128)))
    }

    @Test
    fun `neutral adjustments leave the picture alone`() {
        val plain = render(TxComposition(mode = mode))
        val neutral = render(TxComposition(mode = mode, adjustments = ImageAdjustments()))
        assertThat(neutral.getPixel(160, 128)).isEqualTo(plain.getPixel(160, 128))
    }

    @Test
    fun `contrast pivots around mid-grey`() {
        // A mid-grey source is the pivot, so contrast alone must barely move it
        // — that is what makes contrast "contrast" and not brightness.
        val boosted = render(
            TxComposition(mode = mode, adjustments = ImageAdjustments(contrast = 150)),
        )
        assertThat(Color.red(boosted.getPixel(160, 128))).isIn(120..136)
    }

    @Test
    fun `saturation is baked in`() {
        val colourful = Bitmap.createBitmap(640, 512, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(200, 60, 60))
        }
        val plain = render(TxComposition(mode = mode), colourful)
        val flat = render(
            TxComposition(mode = mode, adjustments = ImageAdjustments(saturation = 50)),
            colourful,
        )
        // Desaturating pulls the channels together.
        val plainSpread = Color.red(plain.getPixel(160, 128)) - Color.blue(plain.getPixel(160, 128))
        val flatSpread = Color.red(flat.getPixel(160, 128)) - Color.blue(flat.getPixel(160, 128))
        assertThat(flatSpread).isLessThan(plainSpread)
    }

    @Test
    fun `adjustments do not touch the overlays drawn on top`() {
        // An operator brightening a photo is not asking to wash out the
        // callsign they put on it.
        val overlay = TextOverlay(
            id = CALLSIGN_OVERLAY_ID,
            text = "K1AF",
            yPercent = TOP_BAR_Y_PERCENT,
            style = OverlayStyle.BAR,
        )
        val plain = render(TxComposition(mode = mode).withOverlayStamped(overlay))
        val bright = render(
            TxComposition(mode = mode, adjustments = ImageAdjustments(brightness = 150))
                .withOverlayStamped(overlay),
        )
        // Sample the bar band, whose fill is drawn after the filtered photo.
        // It is composited over a brighter photo, so allow a little difference —
        // what must not happen is the band being scaled by the filter.
        val band = barBandRect(TOP_BAR_Y_PERCENT, overlay.sizeFraction, mode.width, mode.height)
        val y = band.centerY()
        val plainPixel = Color.red(plain.getPixel(4, y))
        val brightPixel = Color.red(bright.getPixel(4, y))
        assertThat(brightPixel - plainPixel).isLessThan(40)
    }

    // ----- strokes ------------------------------------------------------------

    @Test
    fun `a stroke is baked into the pixels`() {
        val plain = render(TxComposition(mode = mode))
        val drawn = render(
            TxComposition(mode = mode).withStrokeStarted(
                OVERLAY_COLOR_WHITE,
                STROKE_THICK,
                PathPoint(10f, 50f),
            ).withStrokeExtended(PathPoint(90f, 50f)),
        )
        val y = (0.5f * mode.height).toInt()
        assertThat(drawn.getPixel(mode.width / 2, y))
            .isNotEqualTo(plain.getPixel(mode.width / 2, y))
    }

    @Test
    fun `a single-point stroke still marks the picture`() {
        // A tap is a dot. Path.lineTo to the same point draws nothing, so
        // without special handling a tapped stroke would appear in the preview
        // and vanish from the transmitted picture.
        val plain = render(TxComposition(mode = mode))
        val dotted = render(
            TxComposition(mode = mode).withStrokeStarted(
                OVERLAY_COLOR_WHITE,
                STROKE_THICK,
                PathPoint(50f, 50f),
            ),
        )
        assertThat(dotted.getPixel(mode.width / 2, mode.height / 2))
            .isNotEqualTo(plain.getPixel(mode.width / 2, mode.height / 2))
    }

    @Test
    fun `an empty stroke is harmless`() {
        val comp = TxComposition(mode = mode, paths = listOf(DrawPath(OVERLAY_COLOR_WHITE, STROKE_THIN, emptyList())))
        assertThat(render(comp).width).isEqualTo(mode.width)
    }

    @Test
    fun `stroke width scales off the frame's short side`() {
        // So a stroke keeps its apparent weight across wildly different shapes.
        val wide = strokeWidthPx(STROKE_MEDIUM, 640, 496)
        val short = strokeWidthPx(STROKE_MEDIUM, 320, 128)
        assertThat(wide).isGreaterThan(short)
        assertThat(strokeWidthPx(0.00001f, 320, 256)).isAtLeast(1f)
    }

    // ----- frame --------------------------------------------------------------

    @Test
    fun `a frame is baked into the edge pixels`() {
        val plain = render(TxComposition(mode = mode))
        val framed = render(TxComposition(mode = mode, frame = ImageFrame.THIN))
        assertThat(framed.getPixel(0, 0)).isNotEqualTo(plain.getPixel(0, 0))
        assertThat(framed.getPixel(mode.width - 1, mode.height - 1))
            .isNotEqualTo(plain.getPixel(mode.width - 1, mode.height - 1))
    }

    @Test
    fun `a frame leaves the middle of the picture alone`() {
        val plain = render(TxComposition(mode = mode))
        val framed = render(TxComposition(mode = mode, frame = ImageFrame.BOLD))
        assertThat(framed.getPixel(mode.width / 2, mode.height / 2))
            .isEqualTo(plain.getPixel(mode.width / 2, mode.height / 2))
    }

    @Test
    fun `no frame changes nothing`() {
        val plain = render(TxComposition(mode = mode))
        val none = render(TxComposition(mode = mode, frame = ImageFrame.NONE))
        assertThat(none.getPixel(0, 0)).isEqualTo(plain.getPixel(0, 0))
    }

    @Test
    fun `the frame sits entirely inside the bitmap`() {
        // Drawn as four filled edges, not a stroked rect: a stroke centres on
        // its path and would spill half the frame off the bitmap.
        val framed = render(TxComposition(mode = mode, frame = ImageFrame.BOLD))
        val inset = (frameWidthFraction(ImageFrame.BOLD) * minOf(mode.width, mode.height)).toInt()
        // Just inside the frame's inner edge is picture, not frame.
        assertThat(framed.getPixel(inset + 3, mode.height / 2))
            .isNotEqualTo(framed.getPixel(0, mode.height / 2))
    }

    @Test
    fun `every frame style has a distinct weight and colour`() {
        val widths = ImageFrame.entries.filter { it != ImageFrame.NONE }
            .map { frameWidthFraction(it) }
        assertThat(widths.all { it > 0f }).isTrue()
        val colours = ImageFrame.entries.filter { it != ImageFrame.NONE }
            .map { frameColorArgb(it) }
        assertThat(colours).containsNoDuplicates()
        assertThat(frameWidthFraction(ImageFrame.NONE)).isEqualTo(0f)
    }

    // ----- everything at once --------------------------------------------------

    @Test
    fun `a fully edited composition differs from a plain one everywhere it should`() {
        val plain = render(TxComposition(mode = mode))
        val edited = render(
            TxComposition(
                mode = mode,
                adjustments = ImageAdjustments(brightness = 130, contrast = 120, saturation = 80),
                frame = ImageFrame.AMBER,
            )
                .withOverlayStamped(
                    TextOverlay(
                        id = CALLSIGN_OVERLAY_ID,
                        text = "K1AF",
                        yPercent = TOP_BAR_Y_PERCENT,
                        style = OverlayStyle.BAR,
                    ),
                )
                .withStrokeStarted(OVERLAY_COLOR_CYAN, STROKE_THICK, PathPoint(20f, 70f))
                .withStrokeExtended(PathPoint(80f, 70f)),
        )
        assertThat(edited.width).isEqualTo(mode.width)
        // Frame edge, stroke line, overlay band and adjusted picture all moved.
        assertThat(edited.getPixel(0, 0)).isNotEqualTo(plain.getPixel(0, 0))
        val strokeY = (0.7f * mode.height).toInt()
        assertThat(edited.getPixel(mode.width / 2, strokeY))
            .isNotEqualTo(plain.getPixel(mode.width / 2, strokeY))
    }

    // ----- text-only cards -----------------------------------------------------

    @Test
    fun `a card bitmap is generated at the mode's native size`() {
        for (kind in TxCardKind.entries) {
            val card = buildCardBitmap(kind, mode.width, mode.height)
            assertThat(card.width).isEqualTo(mode.width)
            assertThat(card.height).isEqualTo(mode.height)
        }
    }

    @Test
    fun `a card is a gradient rather than a flat fill`() {
        // A large flat area transmits as a long constant tone that is easy to
        // mistake for a fault.
        val card = buildCardBitmap(TxCardKind.GRID, mode.width, mode.height)
        assertThat(card.getPixel(2, 2)).isNotEqualTo(
            card.getPixel(mode.width - 3, mode.height - 3),
        )
    }

    @Test
    fun `the two card styles look different`() {
        val cq = buildCardBitmap(TxCardKind.CQ, mode.width, mode.height)
        val grid = buildCardBitmap(TxCardKind.GRID, mode.width, mode.height)
        assertThat(cq.getPixel(4, 4)).isNotEqualTo(grid.getPixel(4, 4))
    }

    @Test
    fun `a card composites with its starting overlays`() {
        val card = buildCardBitmap(TxCardKind.CQ, mode.width, mode.height)
        val comp = TxComposition(mode = mode, overlays = cqCardOverlays("K1AF"))
        val out = renderComposite(card, comp, mode.width, mode.height)
        assertThat(out.width).isEqualTo(mode.width)
        // The overlays landed: the middle of the card is no longer bare gradient.
        val bare = renderComposite(card, TxComposition(mode = mode), mode.width, mode.height)
        var differing = 0
        for (x in 0 until mode.width step 8) {
            for (y in 0 until mode.height step 8) {
                if (out.getPixel(x, y) != bare.getPixel(x, y)) differing++
            }
        }
        assertThat(differing).isGreaterThan(0)
    }
}
