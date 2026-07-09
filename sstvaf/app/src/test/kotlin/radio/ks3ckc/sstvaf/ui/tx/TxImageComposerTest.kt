package radio.ks3ckc.sstvaf.ui.tx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import radio.ks3ckc.sstvaf.sstv.SstvMode
import java.io.File

/**
 * [TxImageComposer]: cover-crop geometry, the ARGB_8888 composite renderer
 * (frame size, BAR band and OUTLINE halo pixels), decode downsampling math,
 * EXIF rotation mapping, and source decoding. Robolectric because everything
 * here touches android.graphics — and NATIVE graphics mode explicitly,
 * because the legacy shadows silently no-op the src-rect drawBitmap overload
 * and drawText (they'd pass a bogus all-transparent render straight through
 * to the pixel assertions).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TxImageComposerTest {

    // -- computeCoverCrop: aspect-fill base ------------------------------------

    @Test
    fun `wide source into taller frame crops the sides, full height`() {
        // 1024x512 (2.0) into 320x256 (1.25): crop 640x512, centered.
        val crop = computeCoverCrop(1024, 512, 320, 256, 1f, 0f, 0f)
        assertThat(crop).isEqualTo(Rect(192, 0, 832, 512))
    }

    @Test
    fun `tall source into wider frame crops top and bottom, full width`() {
        // 512x1024 (0.5) into 320x256 (1.25): crop 512x410, centered.
        val crop = computeCoverCrop(512, 1024, 320, 256, 1f, 0f, 0f)
        assertThat(crop).isEqualTo(Rect(0, 307, 512, 717))
    }

    @Test
    fun `matching aspect at zoom 1 uses the whole source`() {
        val crop = computeCoverCrop(640, 512, 320, 256, 1f, 0f, 0f)
        assertThat(crop).isEqualTo(Rect(0, 0, 640, 512))
    }

    // -- computeCoverCrop: zoom -------------------------------------------------

    @Test
    fun `zoom 2 halves the visible window and stays centered`() {
        val crop = computeCoverCrop(1024, 512, 320, 256, 2f, 0f, 0f)
        assertThat(crop).isEqualTo(Rect(352, 128, 672, 384))
    }

    @Test
    fun `zoom above 4 clamps to 4`() {
        val at4 = computeCoverCrop(1024, 512, 320, 256, 4f, 0f, 0f)
        val at99 = computeCoverCrop(1024, 512, 320, 256, 99f, 0f, 0f)
        assertThat(at99).isEqualTo(at4)
        assertThat(at4.width()).isEqualTo(160)
        assertThat(at4.height()).isEqualTo(128)
    }

    @Test
    fun `zoom below 1 and NaN clamp to 1`() {
        val at1 = computeCoverCrop(1024, 512, 320, 256, 1f, 0f, 0f)
        assertThat(computeCoverCrop(1024, 512, 320, 256, 0.25f, 0f, 0f)).isEqualTo(at1)
        assertThat(computeCoverCrop(1024, 512, 320, 256, Float.NaN, 0f, 0f)).isEqualTo(at1)
    }

    // -- computeCoverCrop: pan ----------------------------------------------------

    @Test
    fun `pan minus 1 is flush with the left and top edges`() {
        val crop = computeCoverCrop(1024, 512, 320, 256, 2f, -1f, -1f)
        assertThat(crop).isEqualTo(Rect(0, 0, 320, 256))
    }

    @Test
    fun `pan plus 1 is flush with the right and bottom edges`() {
        val crop = computeCoverCrop(1024, 512, 320, 256, 2f, 1f, 1f)
        assertThat(crop).isEqualTo(Rect(704, 256, 1024, 512))
    }

    @Test
    fun `pan beyond the legal range clamps and stays inside the source`() {
        for (panX in listOf(-99f, -1f, -0.3f, 0f, 0.7f, 1f, 99f, Float.NaN)) {
            for (zoom in listOf(1f, 1.7f, 3f, 4f)) {
                val crop = computeCoverCrop(1024, 512, 320, 256, zoom, panX, panX)
                assertThat(crop.left).isAtLeast(0)
                assertThat(crop.top).isAtLeast(0)
                assertThat(crop.right).isAtMost(1024)
                assertThat(crop.bottom).isAtMost(512)
                assertThat(crop.width()).isAtLeast(1)
                assertThat(crop.height()).isAtLeast(1)
            }
        }
    }

    @Test
    fun `degenerate source or destination sizes yield an empty rect`() {
        assertThat(computeCoverCrop(0, 512, 320, 256, 1f, 0f, 0f).isEmpty).isTrue()
        assertThat(computeCoverCrop(1024, 0, 320, 256, 1f, 0f, 0f).isEmpty).isTrue()
        assertThat(computeCoverCrop(1024, 512, 0, 256, 1f, 0f, 0f).isEmpty).isTrue()
        assertThat(computeCoverCrop(1024, 512, 320, -1, 1f, 0f, 0f).isEmpty).isTrue()
        assertThat(computeCoverCrop(-5, -5, -5, -5, 1f, 0f, 0f).isEmpty).isTrue()
    }

    // -- renderComposite: frame size ------------------------------------------

    private fun solidSource(color: Int = Color.RED): Bitmap =
        Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun `composite is exactly the mode frame size for representative modes`() {
        val src = solidSource()
        for (mode in listOf(SstvMode.SCOTTIE_1, SstvMode.ROBOT_36, SstvMode.PD_120)) {
            val comp = TxComposition(mode = mode)
            val out = renderComposite(src, comp, mode.width, mode.height)
            assertThat(out.width).isEqualTo(mode.width)
            assertThat(out.height).isEqualTo(mode.height)
            assertThat(out.config).isEqualTo(Bitmap.Config.ARGB_8888)
            // Cover-crop of a solid source fills the frame with that color.
            assertThat(out.getPixel(0, 0)).isEqualTo(Color.RED)
            assertThat(out.getPixel(mode.width - 1, mode.height - 1)).isEqualTo(Color.RED)
        }
    }

    @Test
    fun `renderComposite rejects a degenerate frame`() {
        val src = solidSource()
        try {
            renderComposite(src, TxComposition(mode = SstvMode.SCOTTIE_1), 0, 256)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected).hasMessageThat().contains("bad composite size")
        }
    }

    // -- renderComposite: BAR overlay -------------------------------------------

    @Test
    fun `top bar overlay darkens pixels inside the band and leaves the far corner alone`() {
        val src = solidSource()
        val mode = SstvMode.SCOTTIE_1
        val plain = renderComposite(src, TxComposition(mode = mode), mode.width, mode.height)
        val withBar = renderComposite(
            src,
            TxComposition(mode = mode).withOverlayAdded(
                TextOverlay(
                    text = "KS3CKC",
                    position = OverlayPosition.TOP_BAR,
                    style = OverlayStyle.BAR,
                ),
            ),
            mode.width,
            mode.height,
        )

        // Inside the band (band height = 0.07 * 256 * 1.6 ≈ 29 px): the 70 %
        // black fill must change the pixel vs the overlay-free render.
        val inBand = withBar.getPixel(4, 4)
        assertThat(inBand).isNotEqualTo(plain.getPixel(4, 4))
        // Far corner (bottom-right) is outside the band: identical renders.
        val corner = mode.width - 2 to mode.height - 2
        assertThat(withBar.getPixel(corner.first, corner.second))
            .isEqualTo(plain.getPixel(corner.first, corner.second))
    }

    @Test
    fun `blank-text overlay renders nothing`() {
        val src = solidSource()
        val mode = SstvMode.ROBOT_36
        val plain = renderComposite(src, TxComposition(mode = mode), mode.width, mode.height)
        val blank = renderComposite(
            src,
            TxComposition(mode = mode).withOverlayAdded(
                TextOverlay(text = "   ", position = OverlayPosition.TOP_BAR),
            ),
            mode.width,
            mode.height,
        )
        assertThat(blank.sameAs(plain)).isTrue()
    }

    // -- renderComposite: OUTLINE overlay ----------------------------------------

    @Test
    fun `outline overlay draws a contrasting halo, not just the fill color`() {
        // Solid navy source; white text with (per contrastColorFor) a black halo.
        val src = solidSource(Color.rgb(0x22, 0x44, 0xAA))
        val mode = SstvMode.SCOTTIE_1
        val out = renderComposite(
            src,
            TxComposition(mode = mode).withOverlayAdded(
                TextOverlay(
                    text = "KS3CKC",
                    colorArgb = OVERLAY_COLOR_WHITE,
                    sizeFraction = OVERLAY_SIZE_LARGE,
                    position = OverlayPosition.CENTER,
                    style = OverlayStyle.OUTLINE,
                ),
            ),
            mode.width,
            mode.height,
        )

        var sawHalo = false
        var sawFill = false
        for (y in 0 until mode.height) {
            for (x in 0 until mode.width) {
                val p = out.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (r < 40 && g < 40 && b < 40) sawHalo = true
                if (r > 215 && g > 215 && b > 215) sawFill = true
            }
        }
        assertThat(sawHalo).isTrue() // black halo pixels exist
        assertThat(sawFill).isTrue() // white text pixels exist
        // Corners stay pure source color (no band behind an OUTLINE overlay).
        assertThat(out.getPixel(1, 1)).isEqualTo(Color.rgb(0x22, 0x44, 0xAA))
    }

    // -- barBandRect / contrastColorFor / outlineOffsets ---------------------------

    @Test
    fun `barBandRect spans the full width at top or bottom and is null elsewhere`() {
        // 0.07 * 256 * 1.6 = 28.672 -> 29 px band.
        assertThat(barBandRect(OverlayPosition.TOP_BAR, 0.07f, 320, 256))
            .isEqualTo(Rect(0, 0, 320, 29))
        assertThat(barBandRect(OverlayPosition.BOTTOM_BAR, 0.07f, 320, 256))
            .isEqualTo(Rect(0, 227, 320, 256))
        assertThat(barBandRect(OverlayPosition.CENTER, 0.07f, 320, 256)).isNull()
        assertThat(barBandRect(OverlayPosition.TOP_LEFT, 0.07f, 320, 256)).isNull()
        assertThat(barBandRect(OverlayPosition.BOTTOM_RIGHT, 0.07f, 320, 256)).isNull()
    }

    @Test
    fun `barBandRect clamps an oversize band to the frame height`() {
        assertThat(barBandRect(OverlayPosition.TOP_BAR, 5f, 320, 256))
            .isEqualTo(Rect(0, 0, 320, 256))
    }

    @Test
    fun `contrastColorFor picks black behind light text and white behind dark text`() {
        assertThat(contrastColorFor(OVERLAY_COLOR_WHITE)).isEqualTo(OVERLAY_COLOR_BLACK)
        assertThat(contrastColorFor(OVERLAY_COLOR_BLACK)).isEqualTo(OVERLAY_COLOR_WHITE)
        assertThat(contrastColorFor(0xFF000080.toInt())).isEqualTo(OVERLAY_COLOR_WHITE) // navy
        assertThat(contrastColorFor(OVERLAY_COLOR_CYAN)).isEqualTo(OVERLAY_COLOR_BLACK)
        assertThat(contrastColorFor(OVERLAY_COLOR_AMBER)).isEqualTo(OVERLAY_COLOR_BLACK)
    }

    @Test
    fun `outlineOffsets are the four diagonals scaled to text size with a 1px floor`() {
        assertThat(outlineOffsets(24f)).containsExactly(
            -2f to -2f, 2f to -2f, -2f to 2f, 2f to 2f,
        ).inOrder()
        assertThat(outlineOffsets(6f)).containsExactly(
            -1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f,
        ).inOrder()
    }

    // -- computeInSampleSize ------------------------------------------------------

    @Test
    fun `inSampleSize keeps the decode at or above the requested size`() {
        assertThat(computeInSampleSize(4000, 3000, 1280, 1024)).isEqualTo(2)
        assertThat(computeInSampleSize(8000, 6000, 1280, 1024)).isEqualTo(4)
        assertThat(computeInSampleSize(2560, 2048, 1280, 1024)).isEqualTo(2)
        assertThat(computeInSampleSize(1000, 800, 1280, 1024)).isEqualTo(1)
        assertThat(computeInSampleSize(1280, 1024, 1280, 1024)).isEqualTo(1)
    }

    @Test
    fun `inSampleSize is always a power of two`() {
        for (dim in listOf(1500, 3000, 5000, 12000, 20000)) {
            val s = computeInSampleSize(dim, dim, 1280, 1024)
            assertThat(Integer.bitCount(s)).isEqualTo(1)
        }
    }

    @Test
    fun `inSampleSize degenerate inputs fall back to 1`() {
        assertThat(computeInSampleSize(0, 3000, 1280, 1024)).isEqualTo(1)
        assertThat(computeInSampleSize(4000, -1, 1280, 1024)).isEqualTo(1)
        assertThat(computeInSampleSize(4000, 3000, 0, 1024)).isEqualTo(1)
        assertThat(computeInSampleSize(4000, 3000, 1280, 0)).isEqualTo(1)
    }

    // -- exifRotationDegrees --------------------------------------------------------

    @Test
    fun `exif orientation maps to clockwise rotation degrees`() {
        assertThat(exifRotationDegrees(ExifInterface.ORIENTATION_ROTATE_90)).isEqualTo(90)
        assertThat(exifRotationDegrees(ExifInterface.ORIENTATION_TRANSPOSE)).isEqualTo(90)
        assertThat(exifRotationDegrees(ExifInterface.ORIENTATION_ROTATE_180)).isEqualTo(180)
        assertThat(exifRotationDegrees(ExifInterface.ORIENTATION_FLIP_VERTICAL)).isEqualTo(180)
        assertThat(exifRotationDegrees(ExifInterface.ORIENTATION_ROTATE_270)).isEqualTo(270)
        assertThat(exifRotationDegrees(ExifInterface.ORIENTATION_TRANSVERSE)).isEqualTo(270)
        assertThat(exifRotationDegrees(ExifInterface.ORIENTATION_NORMAL)).isEqualTo(0)
        assertThat(exifRotationDegrees(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)).isEqualTo(0)
        assertThat(exifRotationDegrees(ExifInterface.ORIENTATION_UNDEFINED)).isEqualTo(0)
    }

    // -- loadSourceBitmap -------------------------------------------------------------

    @Test
    fun `loadSourceBitmap decodes a real png from a file uri`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "tx-src.png")
        val src = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.GREEN) }
        file.outputStream().use { src.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val loaded = loadSourceBitmap(context.contentResolver, Uri.fromFile(file), 320, 256)

        assertThat(loaded).isNotNull()
        assertThat(loaded!!.width).isEqualTo(64) // smaller than the target: no downsampling
        assertThat(loaded.height).isEqualTo(48)
        assertThat(loaded.getPixel(10, 10)).isEqualTo(Color.GREEN)
        file.delete()
    }

    @Test
    fun `loadSourceBitmap returns null for an unopenable uri`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val missing = Uri.fromFile(File(context.cacheDir, "does-not-exist.png"))
        assertThat(loadSourceBitmap(context.contentResolver, missing, 320, 256)).isNull()
    }
}
