package radio.ks3ckc.sstvaf.ui.tx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.sstvaf.sstv.SstvMode

/**
 * [TxComposition] model layer: default seeding from the operator callsign,
 * zoom/pan clamping (including NaN degradation), and the overlay add /
 * replace / remove transformations with their out-of-range no-op and
 * immutability guarantees. Pure Kotlin — no Android types, no runner.
 */
class TxCompositionTest {

    // -- defaultTxComposition -------------------------------------------------

    @Test
    fun `default composition uppercases and trims the callsign into a top bar`() {
        val comp = defaultTxComposition("  ks3ckc ", SstvMode.SCOTTIE_1)

        assertThat(comp.mode).isEqualTo(SstvMode.SCOTTIE_1)
        assertThat(comp.overlays).hasSize(2)
        val top = comp.overlays[0]
        assertThat(top.text).isEqualTo("KS3CKC")
        assertThat(top.position).isEqualTo(OverlayPosition.TOP_BAR)
        assertThat(top.style).isEqualTo(OverlayStyle.BAR)
        assertThat(top.sizeFraction).isEqualTo(OVERLAY_SIZE_LARGE)
        assertThat(top.colorArgb).isEqualTo(OVERLAY_COLOR_WHITE)
    }

    @Test
    fun `default composition seeds a CQ bottom bar from the callsign`() {
        val comp = defaultTxComposition("ks3ckc", SstvMode.ROBOT_36)

        val bottom = comp.overlays[1]
        assertThat(bottom.text).isEqualTo("CQ SSTV de KS3CKC")
        assertThat(bottom.position).isEqualTo(OverlayPosition.BOTTOM_BAR)
        assertThat(bottom.style).isEqualTo(OverlayStyle.BAR)
        assertThat(bottom.sizeFraction).isEqualTo(OVERLAY_SIZE_MEDIUM)
    }

    @Test
    fun `blank callsign seeds no overlays`() {
        assertThat(defaultTxComposition("", SstvMode.SCOTTIE_1).overlays).isEmpty()
        assertThat(defaultTxComposition("   ", SstvMode.SCOTTIE_1).overlays).isEmpty()
    }

    @Test
    fun `blank callsign seeds no overlays even when a grid is set`() {
        // A grid without a callsign bar makes no sense on air, so it stays empty.
        assertThat(defaultTxComposition("", SstvMode.SCOTTIE_1, "FN31").overlays).isEmpty()
    }

    @Test
    fun `default composition appends the grid to the CQ bottom bar`() {
        val comp = defaultTxComposition("ks3ckc", SstvMode.ROBOT_36, "  en35 ")

        // Top bar stays callsign-only; the CQ line carries the location.
        assertThat(comp.overlays[0].text).isEqualTo("KS3CKC")
        assertThat(comp.overlays[1].text).isEqualTo("CQ SSTV de KS3CKC EN35")
    }

    @Test
    fun `blank grid leaves the CQ bottom bar unchanged`() {
        assertThat(defaultTxComposition("KS3CKC", SstvMode.ROBOT_36, "   ").overlays[1].text)
            .isEqualTo("CQ SSTV de KS3CKC")
    }

    // -- cqBarText ------------------------------------------------------------

    @Test
    fun `cqBarText appends an uppercased trimmed grid when present`() {
        assertThat(cqBarText("KS3CKC", "en35ll")).isEqualTo("CQ SSTV de KS3CKC EN35LL")
        assertThat(cqBarText("KS3CKC", "  FN31  ")).isEqualTo("CQ SSTV de KS3CKC FN31")
    }

    @Test
    fun `cqBarText omits a blank grid`() {
        assertThat(cqBarText("KS3CKC", "")).isEqualTo("CQ SSTV de KS3CKC")
        assertThat(cqBarText("KS3CKC", "   ")).isEqualTo("CQ SSTV de KS3CKC")
    }

    @Test
    fun `default composition starts unzoomed and centered with no photo`() {
        val comp = defaultTxComposition("KS3CKC", SstvMode.PD_120)
        assertThat(comp.sourceUri).isNull()
        assertThat(comp.zoom).isEqualTo(1f)
        assertThat(comp.panX).isEqualTo(0f)
        assertThat(comp.panY).isEqualTo(0f)
    }

    // -- clampZoom / clampPan -------------------------------------------------

    @Test
    fun `clampZoom bounds to 1 through 4`() {
        assertThat(clampZoom(2.5f)).isEqualTo(2.5f)
        assertThat(clampZoom(1f)).isEqualTo(TX_MIN_ZOOM)
        assertThat(clampZoom(4f)).isEqualTo(TX_MAX_ZOOM)
        assertThat(clampZoom(0.25f)).isEqualTo(TX_MIN_ZOOM)
        assertThat(clampZoom(-3f)).isEqualTo(TX_MIN_ZOOM)
        assertThat(clampZoom(99f)).isEqualTo(TX_MAX_ZOOM)
    }

    @Test
    fun `clampZoom degrades NaN to no zoom`() {
        assertThat(clampZoom(Float.NaN)).isEqualTo(TX_MIN_ZOOM)
    }

    @Test
    fun `clampPan bounds to minus 1 through 1`() {
        assertThat(clampPan(0.5f)).isEqualTo(0.5f)
        assertThat(clampPan(-1f)).isEqualTo(-1f)
        assertThat(clampPan(1f)).isEqualTo(1f)
        assertThat(clampPan(-7f)).isEqualTo(-1f)
        assertThat(clampPan(7f)).isEqualTo(1f)
    }

    @Test
    fun `clampPan degrades NaN to centered`() {
        assertThat(clampPan(Float.NaN)).isEqualTo(0f)
    }

    @Test
    fun `withClampedView clamps all three axes at once`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withClampedView(zoom = 9f, panX = -5f, panY = Float.NaN)
        assertThat(comp.zoom).isEqualTo(TX_MAX_ZOOM)
        assertThat(comp.panX).isEqualTo(-1f)
        assertThat(comp.panY).isEqualTo(0f)
    }

    // -- overlay transformations ----------------------------------------------

    private val base = defaultTxComposition("KS3CKC", SstvMode.SCOTTIE_1)
    private val extra = TextOverlay(text = "FN20", position = OverlayPosition.BOTTOM_RIGHT)

    @Test
    fun `withOverlayAdded appends and leaves the original untouched`() {
        val added = base.withOverlayAdded(extra)

        assertThat(added.overlays).hasSize(3)
        assertThat(added.overlays.last()).isEqualTo(extra)
        // Immutability: the original composition is unchanged.
        assertThat(base.overlays).hasSize(2)
    }

    @Test
    fun `withOverlayReplaced swaps only the indexed overlay`() {
        val replacement = TextOverlay(text = "73 DE KS3CKC", position = OverlayPosition.BOTTOM_BAR)
        val replaced = base.withOverlayReplaced(1, replacement)

        assertThat(replaced.overlays[0]).isEqualTo(base.overlays[0])
        assertThat(replaced.overlays[1]).isEqualTo(replacement)
        assertThat(base.overlays[1].text).isEqualTo("CQ SSTV de KS3CKC")
    }

    @Test
    fun `withOverlayReplaced out of range is a no-op`() {
        assertThat(base.withOverlayReplaced(-1, extra)).isEqualTo(base)
        assertThat(base.withOverlayReplaced(2, extra)).isEqualTo(base)
        assertThat(base.withOverlayReplaced(99, extra)).isEqualTo(base)
    }

    @Test
    fun `withOverlayRemoved drops only the indexed overlay`() {
        val removed = base.withOverlayRemoved(0)

        assertThat(removed.overlays).hasSize(1)
        assertThat(removed.overlays[0].text).isEqualTo("CQ SSTV de KS3CKC")
        assertThat(base.overlays).hasSize(2)
    }

    @Test
    fun `withOverlayRemoved out of range is a no-op`() {
        assertThat(base.withOverlayRemoved(-1)).isEqualTo(base)
        assertThat(base.withOverlayRemoved(2)).isEqualTo(base)
        val empty = TxComposition(mode = SstvMode.SCOTTIE_1)
        assertThat(empty.withOverlayRemoved(0)).isEqualTo(empty)
    }

    // -- presets ----------------------------------------------------------------

    @Test
    fun `size presets ascend small medium large`() {
        assertThat(OVERLAY_SIZE_SMALL).isLessThan(OVERLAY_SIZE_MEDIUM)
        assertThat(OVERLAY_SIZE_MEDIUM).isLessThan(OVERLAY_SIZE_LARGE)
    }

    @Test
    fun `swatch row offers the five distinct preset colors`() {
        assertThat(OVERLAY_COLOR_SWATCHES).containsExactly(
            OVERLAY_COLOR_WHITE,
            OVERLAY_COLOR_BLACK,
            OVERLAY_COLOR_CYAN,
            OVERLAY_COLOR_AMBER,
            OVERLAY_COLOR_GREEN,
        ).inOrder()
        assertThat(OVERLAY_COLOR_SWATCHES.toSet()).hasSize(OVERLAY_COLOR_SWATCHES.size)
    }
}
