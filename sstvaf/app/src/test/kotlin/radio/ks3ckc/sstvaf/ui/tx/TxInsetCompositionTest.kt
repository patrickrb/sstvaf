package radio.ks3ckc.sstvaf.ui.tx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.sstvaf.sstv.SstvMode

/**
 * The inset's model: its size and position in frame percentages, the corner
 * presets, and the transformations the Inset tool applies.
 *
 * Pure maths, no runner. What is being protected is mostly *shape*: an inset's
 * width and height are percentages of two different lengths, so every one of
 * these has a version that is right on a 320x256 frame and wrong on a 320x128
 * one, and the 320x256 frame is the one everybody tests on.
 */
class TxInsetCompositionTest {

    private val scottie = SstvMode.SCOTTIE_1 // 320 x 256
    private val martin4 = SstvMode.MARTIN_4 // 320 x 128
    private val scottieAspect = 320f / 256f
    private val martin4Aspect = 320f / 128f

    private fun inset(
        x: Float = 50f,
        y: Float = 50f,
        size: Float = INSET_SIZE_MEDIUM,
        aspect: Float = 320f / 256f,
    ) = ImageInset(
        sourceUri = "file:///data/sstv_images/a.png",
        aspect = aspect,
        xPercent = x,
        yPercent = y,
        widthFraction = size,
        caption = "Scottie 1 · 14:32 UTC · 14.230 MHz",
    )

    // ----- clamps -------------------------------------------------------------

    @Test
    fun `a nonsense aspect degrades to four by three`() {
        for (bad in listOf(0f, -2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThat(clampInsetAspect(bad)).isWithin(1e-4f).of(4f / 3f)
        }
    }

    @Test
    fun `an extreme aspect is pulled back to a readable sliver`() {
        assertThat(clampInsetAspect(100f)).isEqualTo(INSET_MAX_ASPECT)
        assertThat(clampInsetAspect(0.01f)).isEqualTo(INSET_MIN_ASPECT)
        assertThat(clampInsetAspect(1.25f)).isEqualTo(1.25f)
    }

    @Test
    fun `an inset size is held to the range the tool offers`() {
        assertThat(clampInsetSize(0.01f)).isEqualTo(INSET_SIZE_SMALL)
        assertThat(clampInsetSize(5f)).isEqualTo(INSET_SIZE_LARGE)
        assertThat(clampInsetSize(Float.NaN)).isEqualTo(INSET_SIZE_MEDIUM)
        assertThat(clampInsetSize(Double.NaN)).isEqualTo(INSET_SIZE_MEDIUM)
        assertThat(clampInsetSize(Double.POSITIVE_INFINITY)).isEqualTo(INSET_SIZE_MEDIUM)
        assertThat(clampInsetSize(0.4)).isEqualTo(INSET_SIZE_LARGE)
    }

    // ----- size ---------------------------------------------------------------

    @Test
    fun `an inset shaped like its frame takes the same share of both sides`() {
        assertThat(insetWidthPercent(INSET_SIZE_MEDIUM)).isWithin(1e-3f).of(33f)
        assertThat(insetHeightPercent(INSET_SIZE_MEDIUM, scottieAspect, scottieAspect))
            .isWithin(1e-3f).of(33f)
    }

    @Test
    fun `height follows the received picture's shape, not the frame's`() {
        // A Robot 36 picture (320x240) pinned to a Scottie frame (320x256) is
        // shorter than a Scottie picture at the same width - it is not cropped
        // or stretched to the frame's shape.
        val robot = insetHeightPercent(INSET_SIZE_MEDIUM, 320f / 240f, scottieAspect)
        assertThat(robot).isWithin(0.01f).of(33f * 1.25f / (320f / 240f))
        assertThat(robot).isLessThan(33f)
    }

    @Test
    fun `on a wide frame the same inset takes a far larger share of the height`() {
        // Martin 4 is 320x128: a quarter-width Scottie picture is half its height.
        assertThat(insetHeightPercent(INSET_SIZE_SMALL, scottieAspect, martin4Aspect))
            .isWithin(0.01f).of(50f)
    }

    @Test
    fun `an inset can never be taller than the frame`() {
        // A tall picture, large, on the widest frame: uncapped this is 400%.
        assertThat(insetHeightPercent(INSET_SIZE_LARGE, 0.25f, martin4Aspect)).isEqualTo(100f)
    }

    @Test
    fun `a broken frame aspect does not poison the height`() {
        val h = insetHeightPercent(INSET_SIZE_MEDIUM, scottieAspect, Float.NaN)
        assertThat(h.isNaN()).isFalse()
        assertThat(h).isGreaterThan(0f)
    }

    // ----- centre clamp -------------------------------------------------------

    @Test
    fun `the centre is clamped by half the inset, so none of it leaves the frame`() {
        assertThat(clampInsetCentre(0f, 33f)).isWithin(1e-3f).of(16.5f)
        assertThat(clampInsetCentre(100f, 33f)).isWithin(1e-3f).of(83.5f)
        assertThat(clampInsetCentre(40f, 33f)).isEqualTo(40f)
        assertThat(clampInsetCentre(Float.NaN, 33f)).isEqualTo(50f)
    }

    @Test
    fun `a full-height inset has exactly one legal centre`() {
        assertThat(clampInsetCentre(10f, 100f)).isEqualTo(50f)
        assertThat(clampInsetCentre(90f, 100f)).isEqualTo(50f)
    }

    // ----- corner presets -----------------------------------------------------

    @Test
    fun `the bottom right preset sits inside the margin and above the bottom bar`() {
        val (x, y) = insetCornerCentre(
            InsetCorner.BOTTOM_RIGHT, INSET_SIZE_MEDIUM, scottieAspect, scottieAspect,
        )
        assertThat(x).isWithin(1e-3f).of(100f - INSET_SIDE_MARGIN_PERCENT - 16.5f)
        assertThat(y).isWithin(1e-3f).of(INSET_SAFE_BOTTOM_PERCENT - 16.5f)
    }

    @Test
    fun `no preset puts the inset over the top or bottom text bar`() {
        // The default composition's bars: a Large top bar and a Medium bottom
        // one. A preset must leave both wholly uncovered, at every size.
        val topBarEnds = TOP_BAR_Y_PERCENT + OVERLAY_SIZE_LARGE * 100f * BAR_HEIGHT_FACTOR / 2f
        val bottomBarStarts =
            BOTTOM_BAR_Y_PERCENT - OVERLAY_SIZE_MEDIUM * 100f * BAR_HEIGHT_FACTOR / 2f
        for (corner in InsetCorner.entries) {
            for (size in INSET_SIZES) {
                val (_, y) = insetCornerCentre(corner, size, scottieAspect, scottieAspect)
                val half = insetHeightPercent(size, scottieAspect, scottieAspect) / 2f
                assertThat(y - half).isAtLeast(topBarEnds)
                assertThat(y + half).isAtMost(bottomBarStarts)
            }
        }
    }

    @Test
    fun `left and right presets mirror each other`() {
        val (leftX, leftY) = insetCornerCentre(
            InsetCorner.TOP_LEFT, INSET_SIZE_SMALL, scottieAspect, scottieAspect,
        )
        val (rightX, rightY) = insetCornerCentre(
            InsetCorner.TOP_RIGHT, INSET_SIZE_SMALL, scottieAspect, scottieAspect,
        )
        assertThat(leftX + rightX).isWithin(1e-3f).of(100f)
        assertThat(leftY).isEqualTo(rightY)
    }

    @Test
    fun `an inset too tall for the safe band is centred on it`() {
        // Large, on Martin 4: 80% tall against a 66% band.
        val (_, top) = insetCornerCentre(
            InsetCorner.TOP_LEFT, INSET_SIZE_LARGE, scottieAspect, martin4Aspect,
        )
        val (_, bottom) = insetCornerCentre(
            InsetCorner.BOTTOM_LEFT, INSET_SIZE_LARGE, scottieAspect, martin4Aspect,
        )
        assertThat(top).isWithin(1e-3f).of(51f)
        assertThat(bottom).isWithin(1e-3f).of(51f)
    }

    @Test
    fun `an inset on a preset reports that preset, and a dragged one reports none`() {
        for (corner in InsetCorner.entries) {
            val (x, y) = insetCornerCentre(corner, INSET_SIZE_MEDIUM, scottieAspect, scottieAspect)
            assertThat(matchingInsetCorner(inset(x, y), scottieAspect)).isEqualTo(corner)
            // A pixel's worth of nudge still counts.
            assertThat(matchingInsetCorner(inset(x + 0.5f, y - 0.5f), scottieAspect))
                .isEqualTo(corner)
        }
        assertThat(matchingInsetCorner(inset(50f, 50f), scottieAspect)).isNull()
    }

    // ----- factory ------------------------------------------------------------

    @Test
    fun `a new inset lands bottom right at medium size`() {
        val made = newImageInset("file:///x.png", 320f / 240f, "cap", scottieAspect)
        assertThat(made.widthFraction).isEqualTo(INSET_SIZE_MEDIUM)
        assertThat(made.caption).isEqualTo("cap")
        assertThat(made.sourceUri).isEqualTo("file:///x.png")
        assertThat(matchingInsetCorner(made, scottieAspect)).isEqualTo(InsetCorner.BOTTOM_RIGHT)
    }

    @Test
    fun `a new inset sanitises a broken aspect before placing itself`() {
        val made = newImageInset("file:///x.png", Float.NaN, "", scottieAspect)
        assertThat(made.aspect).isWithin(1e-4f).of(4f / 3f)
        assertThat(made.xPercent.isNaN()).isFalse()
        assertThat(made.yPercent.isNaN()).isFalse()
    }

    // ----- transformations ----------------------------------------------------

    @Test
    fun `pinning replaces any inset already there`() {
        val first = inset(x = 20f)
        val second = inset(x = 70f)
        val comp = TxComposition(mode = scottie).withInset(first).withInset(second)
        assertThat(comp.inset).isEqualTo(second)
    }

    @Test
    fun `removing an inset that is not there changes nothing`() {
        val comp = TxComposition(mode = scottie)
        assertThat(comp.withInsetRemoved()).isSameInstanceAs(comp)
        assertThat(comp.withInset(inset()).withInsetRemoved().inset).isNull()
    }

    @Test
    fun `the inset transformations are no-ops without an inset`() {
        val comp = TxComposition(mode = scottie)
        assertThat(comp.withInsetMoved(10f, 10f)).isSameInstanceAs(comp)
        assertThat(comp.withInsetCorner(InsetCorner.TOP_LEFT)).isSameInstanceAs(comp)
        assertThat(comp.withInsetSized(INSET_SIZE_LARGE)).isSameInstanceAs(comp)
    }

    @Test
    fun `a move is clamped so the whole inset stays on the picture`() {
        val comp = TxComposition(mode = scottie).withInset(inset())
        val flung = comp.withInsetMoved(200f, -50f).inset!!
        assertThat(flung.xPercent).isWithin(1e-3f).of(83.5f)
        assertThat(flung.yPercent).isWithin(1e-3f).of(16.5f)
        // Unlike a corner preset, a drag may go over the text bars.
        assertThat(flung.yPercent).isLessThan(INSET_SAFE_TOP_PERCENT + 16.5f)
    }

    @Test
    fun `a corner preset moves the inset without resizing it`() {
        val comp = TxComposition(mode = scottie)
            .withInset(inset(size = INSET_SIZE_LARGE))
            .withInsetCorner(InsetCorner.TOP_LEFT)
        assertThat(comp.inset!!.widthFraction).isEqualTo(INSET_SIZE_LARGE)
        assertThat(matchingInsetCorner(comp.inset!!, scottieAspect)).isEqualTo(InsetCorner.TOP_LEFT)
    }

    @Test
    fun `resizing a cornered inset keeps it in its corner`() {
        val cornered = TxComposition(mode = scottie)
            .withInset(inset(size = INSET_SIZE_SMALL))
            .withInsetCorner(InsetCorner.BOTTOM_LEFT)
        val grown = cornered.withInsetSized(INSET_SIZE_LARGE)
        assertThat(grown.inset!!.widthFraction).isEqualTo(INSET_SIZE_LARGE)
        // Had the centre been kept, the larger inset would have spilled past
        // the margin and over the bottom bar.
        assertThat(matchingInsetCorner(grown.inset!!, scottieAspect))
            .isEqualTo(InsetCorner.BOTTOM_LEFT)
    }

    @Test
    fun `resizing a dragged inset keeps its centre where it fits`() {
        val dragged = TxComposition(mode = scottie).withInset(inset(x = 50f, y = 50f))
        val grown = dragged.withInsetSized(INSET_SIZE_LARGE).inset!!
        assertThat(grown.xPercent).isEqualTo(50f)
        assertThat(grown.yPercent).isEqualTo(50f)
    }

    @Test
    fun `resizing a dragged inset near an edge nudges it back inside`() {
        val edge = TxComposition(mode = scottie)
            .withInset(inset(size = INSET_SIZE_SMALL))
            .withInsetMoved(100f, 50f) // parks at 87.5
        val grown = edge.withInsetSized(INSET_SIZE_LARGE).inset!!
        assertThat(grown.xPercent).isWithin(1e-3f).of(80f)
    }

    @Test
    fun `a nonsense size is clamped rather than stored`() {
        val comp = TxComposition(mode = scottie).withInset(inset()).withInsetSized(9f)
        assertThat(comp.inset!!.widthFraction).isEqualTo(INSET_SIZE_LARGE)
    }

    // ----- mode change --------------------------------------------------------

    @Test
    fun `changing mode without an inset is just a mode change`() {
        val comp = TxComposition(mode = scottie, zoom = 2f).withModeChanged(martin4)
        assertThat(comp.mode).isEqualTo(martin4)
        assertThat(comp.zoom).isEqualTo(2f)
        assertThat(comp.inset).isNull()
    }

    @Test
    fun `a cornered inset stays cornered when the frame changes shape`() {
        val comp = TxComposition(mode = scottie)
            .withInset(inset(size = INSET_SIZE_SMALL))
            .withInsetCorner(InsetCorner.BOTTOM_RIGHT)
            .withModeChanged(martin4)
        assertThat(comp.mode).isEqualTo(martin4)
        assertThat(matchingInsetCorner(comp.inset!!, martin4Aspect))
            .isEqualTo(InsetCorner.BOTTOM_RIGHT)
    }

    @Test
    fun `a dragged inset is pulled back inside a wider frame`() {
        // Dragged to the top edge of a Scottie frame a small inset parks at
        // 12.5% (half its 25% height). On Martin 4 the same inset is 50% of
        // the frame's height, so its centre has to come down to 25%.
        val comp = TxComposition(mode = scottie)
            .withInset(inset(size = INSET_SIZE_SMALL))
            .withInsetMoved(50f, 0f)
        assertThat(comp.inset!!.yPercent).isWithin(1e-3f).of(12.5f)
        val wide = comp.withModeChanged(martin4).inset!!
        assertThat(wide.yPercent).isWithin(1e-3f).of(25f)
        assertThat(wide.xPercent).isEqualTo(50f)
    }

    @Test
    fun `the frame aspect comes from the mode`() {
        assertThat(TxComposition(mode = scottie).frameAspect()).isWithin(1e-4f).of(1.25f)
        assertThat(TxComposition(mode = martin4).frameAspect()).isWithin(1e-4f).of(2.5f)
    }
}
