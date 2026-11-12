package radio.ks3ckc.sstvaf.ui.tx

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.sstvaf.gallery.ImageDirection
import radio.ks3ckc.sstvaf.gallery.SavedImage
import java.io.File

/**
 * The decisions behind the Inset tool and the reply flow: which pictures are
 * offered, how one is described, and how a gallery row becomes an inset.
 *
 * Robolectric only because [insetForReceived] builds a `file:` URI with
 * android.net.Uri; everything else here is plain Kotlin.
 */
@RunWith(RobolectricTestRunner::class)
class TxInsetLogicTest {

    private val scottieAspect = 320f / 256f

    // 2026-11-12 14:32:00 UTC.
    private val utc1432 = 1_794_493_920_000L

    private fun image(
        id: Long,
        direction: ImageDirection = ImageDirection.RX,
        mode: String = "Scottie 1",
        freqHz: Long = 14_230_000L,
        utcMillis: Long = utc1432,
        width: Int = 320,
        height: Int = 256,
    ) = SavedImage(id, "f$id.png", direction, mode, freqHz, utcMillis, width, height, true, 0.9f, "")

    // ----- picker contents ----------------------------------------------------

    @Test
    fun `only received pictures are offered`() {
        val all = listOf(
            image(1, ImageDirection.TX),
            image(2, ImageDirection.RX),
            image(3, ImageDirection.TX),
            image(4, ImageDirection.RX),
        )
        assertThat(recentReceived(all).map { it.id }).containsExactly(2L, 4L).inOrder()
    }

    @Test
    fun `the strip is capped and keeps the newest`() {
        val all = (1L..40L).map { image(it) }
        val offered = recentReceived(all)
        assertThat(offered).hasSize(INSET_PICKER_LIMIT)
        assertThat(offered.first().id).isEqualTo(1L)
        assertThat(recentReceived(all, limit = 3).map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `an empty gallery or a silly limit offers nothing`() {
        assertThat(recentReceived(emptyList())).isEmpty()
        assertThat(recentReceived(listOf(image(1)), limit = 0)).isEmpty()
        assertThat(recentReceived(listOf(image(1)), limit = -4)).isEmpty()
    }

    // ----- captions -----------------------------------------------------------

    @Test
    fun `the caption names mode, utc time and frequency`() {
        assertThat(insetCaption("Scottie 1", utc1432, 14_230_000L))
            .isEqualTo("Scottie 1 · 14:32 UTC · 14.230 MHz")
    }

    @Test
    fun `the caption drops a frequency the rig never reported`() {
        assertThat(insetCaption("Robot 36", utc1432, 0L)).isEqualTo("Robot 36 · 14:32 UTC")
    }

    @Test
    fun `the caption survives a blank mode`() {
        assertThat(insetCaption("  ", utc1432, 7_171_000L)).isEqualTo("14:32 UTC · 7.171 MHz")
    }

    @Test
    fun `the picker caption uses the mode's short code`() {
        assertThat(insetPickerCaption(image(1, mode = "Scottie 1"))).isEqualTo("S1 · 14:32")
        assertThat(insetPickerCaption(image(1, mode = "Robot 36"))).isEqualTo("R36 · 14:32")
    }

    @Test
    fun `the picker caption falls back to the stored name for an unknown mode`() {
        assertThat(insetPickerCaption(image(1, mode = "HamDRM A"))).isEqualTo("HamDRM A · 14:32")
        assertThat(insetPickerCaption(image(1, mode = ""))).isEqualTo("14:32")
    }

    // ----- gallery row to inset -----------------------------------------------

    @Test
    fun `the aspect comes from the row and tolerates a broken one`() {
        assertThat(insetAspectOf(image(1, width = 320, height = 240))).isWithin(1e-4f).of(320f / 240f)
        assertThat(insetAspectOf(image(1, width = 0, height = 256))).isWithin(1e-4f).of(4f / 3f)
        assertThat(insetAspectOf(image(1, width = 320, height = 0))).isWithin(1e-4f).of(4f / 3f)
    }

    @Test
    fun `a received picture becomes a default-placed inset pointing at its file`() {
        // absoluteFile: on a Windows dev box a rooted path gains a drive letter
        // on its way through Uri.fromFile.
        val file = File("/data/user/0/app/files/sstv_images/f7.png").absoluteFile
        val inset = insetForReceived(image(7, width = 320, height = 240), file, scottieAspect)

        assertThat(insetSourceFile(inset.sourceUri)).isEqualTo(file)
        assertThat(inset.aspect).isWithin(1e-4f).of(320f / 240f)
        assertThat(inset.widthFraction).isEqualTo(INSET_SIZE_MEDIUM)
        assertThat(inset.caption).isEqualTo("Scottie 1 · 14:32 UTC · 14.230 MHz")
        assertThat(matchingInsetCorner(inset, scottieAspect)).isEqualTo(DEFAULT_INSET_CORNER)
    }

    @Test
    fun `changing the picture keeps the corner and size the operator chose`() {
        val file = File("/x/f1.png")
        val first = insetForReceived(image(1), file, scottieAspect)
        val placed = first.copy(widthFraction = INSET_SIZE_LARGE).let {
            val (x, y) = insetCornerCentre(
                InsetCorner.TOP_LEFT, it.widthFraction, it.aspect, scottieAspect,
            )
            it.copy(xPercent = x, yPercent = y, corner = InsetCorner.TOP_LEFT)
        }

        // A differently shaped picture: same corner, same size, new geometry.
        val swapped = insetForReceived(
            image(2, mode = "Robot 36", width = 320, height = 240),
            File("/x/f2.png"),
            scottieAspect,
            keepPlacementOf = placed,
        )
        assertThat(insetSourceFile(swapped.sourceUri)).isEqualTo(File("/x/f2.png").absoluteFile)
        assertThat(swapped.widthFraction).isEqualTo(INSET_SIZE_LARGE)
        assertThat(swapped.caption).startsWith("Robot 36")
        assertThat(matchingInsetCorner(swapped, scottieAspect)).isEqualTo(InsetCorner.TOP_LEFT)
    }

    @Test
    fun `changing the picture keeps a dragged position, re-clamped to the new shape`() {
        val dragged = ImageInset("file:///x/f1.png", 1.25f, 50f, 20f, INSET_SIZE_MEDIUM)
        // A much taller picture: at 33% wide it is 82.5% of the frame's height,
        // so a centre at 20% no longer fits.
        val tall = insetForReceived(
            image(2, width = 200, height = 400),
            File("/x/f2.png"),
            scottieAspect,
            keepPlacementOf = dragged,
        )
        assertThat(tall.xPercent).isEqualTo(50f)
        val half = insetHeightPercent(tall.widthFraction, tall.aspect, scottieAspect) / 2f
        assertThat(tall.yPercent).isWithin(1e-3f).of(half)
        // Still unpinned: a fresh inset's default corner must not leak in.
        assertThat(tall.corner).isNull()
    }

    // ----- panel state --------------------------------------------------------

    @Test
    fun `the picker shows when nothing is pinned or a change was asked for`() {
        assertThat(insetPanelShowsPicker(hasInset = false, changeRequested = false)).isTrue()
        assertThat(insetPanelShowsPicker(hasInset = false, changeRequested = true)).isTrue()
        assertThat(insetPanelShowsPicker(hasInset = true, changeRequested = true)).isTrue()
        assertThat(insetPanelShowsPicker(hasInset = true, changeRequested = false)).isFalse()
    }

    @Test
    fun `the inset is selected only while its tool is open and no text is`() {
        assertThat(insetIsSelected(TxTool.INSET, hasInset = true, overlaySelected = false)).isTrue()
        assertThat(insetIsSelected(TxTool.INSET, hasInset = false, overlaySelected = false)).isFalse()
        assertThat(insetIsSelected(TxTool.INSET, hasInset = true, overlaySelected = true)).isFalse()
        for (tool in TxTool.entries.filter { it != TxTool.INSET }) {
            assertThat(insetIsSelected(tool, hasInset = true, overlaySelected = false)).isFalse()
        }
    }

    // ----- spoken names -------------------------------------------------------

    @Test
    fun `every corner has its own spoken name`() {
        val names = InsetCorner.entries.map { insetCornerDescriptionRes(it) }
        assertThat(names.toSet()).hasSize(InsetCorner.entries.size)
        assertThat(insetCornerDescriptionRes(InsetCorner.BOTTOM_RIGHT))
            .isEqualTo(R.string.tx_inset_corner_bottom_right)
    }

    @Test
    fun `the three sizes are labelled S, M and L`() {
        assertThat(insetSizeLabelRes(INSET_SIZE_SMALL)).isEqualTo(R.string.tx_size_small)
        assertThat(insetSizeLabelRes(INSET_SIZE_MEDIUM)).isEqualTo(R.string.tx_size_medium)
        assertThat(insetSizeLabelRes(INSET_SIZE_LARGE)).isEqualTo(R.string.tx_size_large)
    }

    @Test
    fun `the delete button names what it will remove`() {
        assertThat(deleteTargetDescriptionRes(overlaySelected = true))
            .isEqualTo(R.string.tx_overlay_delete_description)
        assertThat(deleteTargetDescriptionRes(overlaySelected = false))
            .isEqualTo(R.string.tx_inset_remove)
    }

    @Test
    fun `the inset tool sits on the rail between callsign and frame`() {
        val tools = TxTool.entries
        assertThat(tools.indexOf(TxTool.INSET)).isEqualTo(tools.indexOf(TxTool.CALLSIGN) + 1)
        assertThat(tools.indexOf(TxTool.FRAME)).isEqualTo(tools.indexOf(TxTool.INSET) + 1)
        assertThat(TxTool.INSET.labelRes).isEqualTo(R.string.tx_tool_inset)
    }

    // ----- reopen and delete decisions ----------------------------------------

    @Test
    fun `an edit list without an inset replays whenever its source loads`() {
        assertThat(editListIsReplayable(sourceLoaded = true, insetWanted = false, insetLoaded = false))
            .isTrue()
        assertThat(editListIsReplayable(sourceLoaded = false, insetWanted = false, insetLoaded = false))
            .isFalse()
    }

    @Test
    fun `an edit list with an inset replays only if the received picture is still there`() {
        assertThat(editListIsReplayable(sourceLoaded = true, insetWanted = true, insetLoaded = true))
            .isTrue()
        // Deleted from the gallery since: fall back to the flattened picture
        // rather than resend without the inset it plainly had.
        assertThat(editListIsReplayable(sourceLoaded = true, insetWanted = true, insetLoaded = false))
            .isFalse()
        assertThat(editListIsReplayable(sourceLoaded = false, insetWanted = true, insetLoaded = true))
            .isFalse()
    }

    @Test
    fun `delete removes the selected text before anything else`() {
        for (tool in TxTool.entries) {
            assertThat(deleteTarget(overlaySelected = true, tool = tool, hasInset = true))
                .isEqualTo(DeleteTarget.OVERLAY)
        }
    }

    @Test
    fun `delete removes the inset only while its tool is open`() {
        assertThat(deleteTarget(overlaySelected = false, tool = TxTool.INSET, hasInset = true))
            .isEqualTo(DeleteTarget.INSET)
        assertThat(deleteTarget(overlaySelected = false, tool = TxTool.INSET, hasInset = false))
            .isEqualTo(DeleteTarget.NONE)
        for (tool in TxTool.entries.filter { it != TxTool.INSET }) {
            assertThat(deleteTarget(overlaySelected = false, tool = tool, hasInset = true))
                .isEqualTo(DeleteTarget.NONE)
        }
    }
}
