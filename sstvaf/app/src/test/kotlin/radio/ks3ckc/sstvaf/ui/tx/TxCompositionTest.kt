package radio.ks3ckc.sstvaf.ui.tx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.sstvaf.sstv.SstvMode
import java.util.Locale

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
        assertThat(top.id).isEqualTo(CALLSIGN_OVERLAY_ID)
        assertThat(top.yPercent).isEqualTo(TOP_BAR_Y_PERCENT)
        assertThat(top.style).isEqualTo(OverlayStyle.BAR)
        assertThat(top.sizeFraction).isEqualTo(OVERLAY_SIZE_LARGE)
        assertThat(top.colorArgb).isEqualTo(OVERLAY_COLOR_WHITE)
    }

    @Test
    fun `default composition seeds a CQ bottom bar from the callsign`() {
        val comp = defaultTxComposition("ks3ckc", SstvMode.ROBOT_36)

        val bottom = comp.overlays[1]
        assertThat(bottom.text).isEqualTo("CQ SSTV de KS3CKC")
        assertThat(bottom.yPercent).isEqualTo(BOTTOM_BAR_Y_PERCENT)
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
    fun `cqBarText grid uppercasing is locale-stable under a Turkish locale`() {
        // Under the Turkish locale a default uppercase() turns 'i' into the
        // dotted 'İ'; grid subsquares are ASCII, so Locale.ROOT must keep them
        // plain (e.g. "fn31ip" → "FN31IP", not "FN31İP"). The call is passed
        // already-uppercased, as cqBarText's contract expects.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertThat(cqBarText("KI1U", "fn31ip")).isEqualTo("CQ SSTV de KI1U FN31IP")
            // And the callsign path through defaultTxComposition stays ASCII too.
            val comp = defaultTxComposition("ki1u", SstvMode.SCOTTIE_1)
            assertThat(comp.overlays.any { it.text.contains("KI1U") }).isTrue()
        } finally {
            Locale.setDefault(previous)
        }
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


    @Test
    fun `withOverlayStamped appends a new id and leaves the original untouched`() {
        val base = TxComposition(mode = SstvMode.SCOTTIE_1)
        val added = base.withOverlayStamped(TextOverlay(id = "t1", text = "HELLO"))
        assertThat(base.overlays).isEmpty()
        assertThat(added.overlays).hasSize(1)
        assertThat(added.overlays.single().text).isEqualTo("HELLO")
    }

    @Test
    fun `withOverlayStamped replaces a matching id in place`() {
        // The callsign stamp must move, not pile up: six taps on six presets
        // should leave one stamp, not six.
        var comp = TxComposition(mode = SstvMode.SCOTTIE_1)
        for (preset in CallsignPreset.entries) {
            comp = comp.withOverlayStamped(
                TextOverlay(
                    id = CALLSIGN_OVERLAY_ID,
                    text = "K1AF",
                    xPercent = preset.xPercent,
                    yPercent = preset.yPercent,
                    style = preset.style,
                ),
            )
        }
        assertThat(comp.overlays).hasSize(1)
        assertThat(comp.overlays.single().yPercent)
            .isEqualTo(CallsignPreset.BOTTOM_RIGHT.yPercent)
    }

    @Test
    fun `withOverlayStamped keeps the replaced overlay's draw order`() {
        // Draw order decides what sits on top; editing text must not reshuffle it.
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withOverlayStamped(TextOverlay(id = "a", text = "A"))
            .withOverlayStamped(TextOverlay(id = "b", text = "B"))
            .withOverlayStamped(TextOverlay(id = "a", text = "A2"))
        assertThat(comp.overlays.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(comp.overlays.first().text).isEqualTo("A2")
    }

    @Test
    fun `withOverlayStamped clamps a position outside the frame`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1).withOverlayStamped(
            TextOverlay(id = "t1", text = "X", xPercent = -30f, yPercent = 180f),
        )
        assertThat(comp.overlays.single().xPercent).isEqualTo(0f)
        assertThat(comp.overlays.single().yPercent).isEqualTo(100f)
    }

    @Test
    fun `withOverlayRemoved drops only the named overlay`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withOverlayStamped(TextOverlay(id = "a", text = "A"))
            .withOverlayStamped(TextOverlay(id = "b", text = "B"))
            .withOverlayRemoved("a")
        assertThat(comp.overlays.map { it.id }).containsExactly("b")
    }

    @Test
    fun `withOverlayRemoved with an unknown id is a no-op`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withOverlayStamped(TextOverlay(id = "a", text = "A"))
        assertThat(comp.withOverlayRemoved("zzz")).isEqualTo(comp)
    }

    @Test
    fun `withOverlayMoved locks a bar to the horizontal centre`() {
        // A bar spans the frame, so sideways movement would be invisible.
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withOverlayStamped(TextOverlay(id = "a", text = "A", style = OverlayStyle.BAR))
            .withOverlayMoved("a", xPercent = 12f, yPercent = 70f)
        assertThat(comp.overlays.single().xPercent).isEqualTo(50f)
        assertThat(comp.overlays.single().yPercent).isEqualTo(70f)
    }

    @Test
    fun `withOverlayMoved lets an outline overlay move freely`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withOverlayStamped(TextOverlay(id = "a", text = "A", style = OverlayStyle.OUTLINE))
            .withOverlayMoved("a", xPercent = 12f, yPercent = 70f)
        assertThat(comp.overlays.single().xPercent).isEqualTo(12f)
        assertThat(comp.overlays.single().yPercent).isEqualTo(70f)
    }

    @Test
    fun `withOverlayMoved clamps to the frame`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withOverlayStamped(TextOverlay(id = "a", text = "A", style = OverlayStyle.OUTLINE))
            .withOverlayMoved("a", xPercent = 400f, yPercent = -40f)
        assertThat(comp.overlays.single().xPercent).isEqualTo(100f)
        assertThat(comp.overlays.single().yPercent).isEqualTo(0f)
    }

    @Test
    fun `withOverlayPatched edits only the named overlay`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withOverlayStamped(TextOverlay(id = "a", text = "A"))
            .withOverlayStamped(TextOverlay(id = "b", text = "B"))
            .withOverlayPatched("a") { it.copy(text = "EDITED") }
        assertThat(comp.overlays.first().text).isEqualTo("EDITED")
        assertThat(comp.overlays.last().text).isEqualTo("B")
    }

    @Test
    fun `nextOverlayId never reuses a live id after a deletion`() {
        // Counting from the list size would hand out "t2" again here, and the
        // new overlay would silently replace the surviving one.
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withOverlayStamped(TextOverlay(id = "t1", text = "one"))
            .withOverlayStamped(TextOverlay(id = "t2", text = "two"))
            .withOverlayRemoved("t1")
        assertThat(comp.overlays).hasSize(1)
        assertThat(comp.nextOverlayId()).isEqualTo("t3")
    }

    @Test
    fun `nextOverlayId starts at one on an empty composition`() {
        assertThat(TxComposition(mode = SstvMode.SCOTTIE_1).nextOverlayId()).isEqualTo("t1")
    }

    @Test
    fun `nextOverlayId ignores the callsign stamp`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withOverlayStamped(TextOverlay(id = CALLSIGN_OVERLAY_ID, text = "K1AF"))
        assertThat(comp.nextOverlayId()).isEqualTo("t1")
    }

    // ----- draw paths --------------------------------------------------------

    @Test
    fun `a stroke starts and extends`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withStrokeStarted(OVERLAY_COLOR_WHITE, STROKE_MEDIUM, PathPoint(10f, 10f))
            .withStrokeExtended(PathPoint(20f, 20f))
        assertThat(comp.paths).hasSize(1)
        assertThat(comp.paths.single().points).hasSize(2)
    }

    @Test
    fun `extending with no stroke in progress is a no-op`() {
        // A stray move event must not create a path with no origin.
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
        assertThat(comp.withStrokeExtended(PathPoint(10f, 10f))).isEqualTo(comp)
    }

    @Test
    fun `undo drops the most recent stroke only`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withStrokeStarted(OVERLAY_COLOR_WHITE, STROKE_THIN, PathPoint(1f, 1f))
            .withStrokeStarted(OVERLAY_COLOR_CYAN, STROKE_THICK, PathPoint(2f, 2f))
            .withStrokeUndone()
        assertThat(comp.paths).hasSize(1)
        assertThat(comp.paths.single().colorArgb).isEqualTo(OVERLAY_COLOR_WHITE)
    }

    @Test
    fun `undo and clear on an empty list are no-ops`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
        assertThat(comp.withStrokeUndone()).isEqualTo(comp)
        assertThat(comp.withStrokesCleared()).isEqualTo(comp)
    }

    @Test
    fun `clear drops every stroke`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withStrokeStarted(OVERLAY_COLOR_WHITE, STROKE_THIN, PathPoint(1f, 1f))
            .withStrokeStarted(OVERLAY_COLOR_CYAN, STROKE_THICK, PathPoint(2f, 2f))
            .withStrokesCleared()
        assertThat(comp.paths).isEmpty()
    }

    @Test
    fun `stroke widths ascend`() {
        assertThat(STROKE_WIDTHS).isInOrder()
        assertThat(STROKE_WIDTHS).hasSize(3)
    }

    // ----- adjustments -------------------------------------------------------

    @Test
    fun `a fresh composition is neutral`() {
        assertThat(TxComposition(mode = SstvMode.SCOTTIE_1).adjustments.isNeutral()).isTrue()
    }

    @Test
    fun `adjustments clamp to the slider range`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withAdjustments(ImageAdjustments(brightness = 500, contrast = -20, saturation = 120))
        assertThat(comp.adjustments.brightness).isEqualTo(ADJUSTMENT_MAX)
        assertThat(comp.adjustments.contrast).isEqualTo(ADJUSTMENT_MIN)
        assertThat(comp.adjustments.saturation).isEqualTo(120)
    }

    @Test
    fun `reset returns every channel to neutral`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1)
            .withAdjustments(ImageAdjustments(brightness = 130, contrast = 80, saturation = 140))
            .withAdjustmentsReset()
        assertThat(comp.adjustments.isNeutral()).isTrue()
    }

    @Test
    fun `the offset label is signed relative to neutral`() {
        assertThat(adjustmentOffsetLabel(112)).isEqualTo("+12")
        assertThat(adjustmentOffsetLabel(92)).isEqualTo("-8")
        assertThat(adjustmentOffsetLabel(ADJUSTMENT_NEUTRAL)).isEqualTo("0")
    }

    @Test
    fun `the offset label clamps before formatting`() {
        assertThat(adjustmentOffsetLabel(900)).isEqualTo("+50")
        assertThat(adjustmentOffsetLabel(-900)).isEqualTo("-50")
    }

    // ----- frame -------------------------------------------------------------

    @Test
    fun `a fresh composition has no frame`() {
        assertThat(TxComposition(mode = SstvMode.SCOTTIE_1).frame).isEqualTo(ImageFrame.NONE)
    }

    @Test
    fun `the frame is replaced wholesale`() {
        val comp = TxComposition(mode = SstvMode.SCOTTIE_1).withFrame(ImageFrame.AMBER)
        assertThat(comp.frame).isEqualTo(ImageFrame.AMBER)
        assertThat(comp.withFrame(ImageFrame.NONE).frame).isEqualTo(ImageFrame.NONE)
    }

    // ----- callsign presets --------------------------------------------------

    @Test
    fun `centre presets are bars and corner presets are outlines`() {
        for (preset in CallsignPreset.entries) {
            val expected =
                if (preset.xPercent == 50f) OverlayStyle.BAR else OverlayStyle.OUTLINE
            assertThat(preset.style).isEqualTo(expected)
        }
    }

    @Test
    fun `presets cover both edges`() {
        assertThat(CallsignPreset.entries.count { it.yPercent < 50f }).isEqualTo(3)
        assertThat(CallsignPreset.entries.count { it.yPercent > 50f }).isEqualTo(3)
    }

    @Test
    fun `a stamp on a preset reports that preset`() {
        val overlay = TextOverlay(
            id = CALLSIGN_OVERLAY_ID,
            text = "K1AF",
            xPercent = CallsignPreset.TOP_RIGHT.xPercent,
            yPercent = CallsignPreset.TOP_RIGHT.yPercent,
        )
        assertThat(matchingCallsignPreset(overlay)).isEqualTo(CallsignPreset.TOP_RIGHT)
    }

    @Test
    fun `a nudged stamp still reports its preset`() {
        // Dragging by a pixel should not make the preset cell go dark.
        val overlay = TextOverlay(
            id = CALLSIGN_OVERLAY_ID,
            text = "K1AF",
            xPercent = CallsignPreset.TOP_RIGHT.xPercent + 1f,
            yPercent = CallsignPreset.TOP_RIGHT.yPercent - 1f,
        )
        assertThat(matchingCallsignPreset(overlay)).isEqualTo(CallsignPreset.TOP_RIGHT)
    }

    @Test
    fun `a dragged stamp reports no preset`() {
        val overlay = TextOverlay(
            id = CALLSIGN_OVERLAY_ID,
            text = "K1AF",
            xPercent = 44f,
            yPercent = 55f,
        )
        assertThat(matchingCallsignPreset(overlay)).isNull()
    }

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
