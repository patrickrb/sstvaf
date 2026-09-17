package radio.ks3ckc.sstvaf.ui.tx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.sstvaf.sstv.SstvMode

/**
 * Round-trip and robustness tests for the persisted edit list.
 *
 * This blob goes into a database row and is read back by a build that may be
 * months newer, so the parse path matters more than the serialize path: every
 * way it can be wrong has to degrade to null, because the caller's fallback
 * (open the flattened picture as a plain source) is always available and
 * always better than a crash while the gallery is listing.
 *
 * Robolectric for org.json, which is an Android platform class.
 */
@RunWith(RobolectricTestRunner::class)
class TxEditListTest {

    private fun rich() = TxComposition(
        sourceUri = "content://media/external/images/1234",
        mode = SstvMode.PD_120,
        zoom = 1.8f,
        panX = -0.4f,
        panY = 0.25f,
        overlays = listOf(
            TextOverlay(
                id = CALLSIGN_OVERLAY_ID,
                text = "K1AF",
                xPercent = 12f,
                yPercent = 91f,
                colorArgb = OVERLAY_COLOR_AMBER,
                sizeFraction = OVERLAY_SIZE_LARGE,
                style = OverlayStyle.OUTLINE,
            ),
            TextOverlay(
                id = "t1",
                text = "CQ SSTV FN42",
                xPercent = 50f,
                yPercent = 8f,
                colorArgb = OVERLAY_COLOR_CYAN,
                sizeFraction = OVERLAY_SIZE_SMALL,
                style = OverlayStyle.BAR,
            ),
        ),
        paths = listOf(
            DrawPath(OVERLAY_COLOR_WHITE, STROKE_THICK, listOf(PathPoint(10f, 20f), PathPoint(30f, 40f))),
            DrawPath(OVERLAY_COLOR_GREEN, STROKE_THIN, listOf(PathPoint(70f, 80f))),
        ),
        adjustments = ImageAdjustments(brightness = 120, contrast = 90, saturation = 135),
        frame = ImageFrame.AMBER,
    )

    // ----- round trip ---------------------------------------------------------

    @Test
    fun `a full composition survives a round trip`() {
        val original = rich()
        val restored = parseEditList(TxEditList.serialize(original))
        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `an empty composition survives a round trip`() {
        val original = TxComposition(mode = SstvMode.SCOTTIE_1)
        val restored = parseEditList(TxEditList.serialize(original))
        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `the mode is stored by name so native ids can move`() {
        // The ids are pinned to the C table's order and cross JNI; a name is
        // the stable thing to put in a database row.
        val json = TxEditList.serialize(TxComposition(mode = SstvMode.PD_290))
        assertThat(json).contains("PD_290")
        assertThat(parseEditList(json)?.mode).isEqualTo(SstvMode.PD_290)
    }

    @Test
    fun `overlay order is preserved`() {
        // Order is draw order, which decides what sits on top.
        val restored = parseEditList(TxEditList.serialize(rich()))
        assertThat(restored?.overlays?.map { it.id })
            .containsExactly(CALLSIGN_OVERLAY_ID, "t1")
            .inOrder()
    }

    @Test
    fun `a single-point stroke survives`() {
        // A tapped dot is a real mark; the flat point array must not lose it.
        val restored = parseEditList(TxEditList.serialize(rich()))
        assertThat(restored?.paths?.last()?.points).hasSize(1)
    }

    // ----- robustness ---------------------------------------------------------

    @Test
    fun `blank and whitespace parse to null`() {
        assertThat(parseEditList("")).isNull()
        assertThat(parseEditList("   ")).isNull()
    }

    @Test
    fun `malformed json parses to null`() {
        assertThat(parseEditList("not json at all")).isNull()
        assertThat(parseEditList("{")).isNull()
        assertThat(parseEditList("[1,2,3]")).isNull()
    }

    @Test
    fun `a future version is refused`() {
        // A newer build may have written a shape this one cannot read; better
        // to fall back than to reconstruct half of it.
        val json = """{"v":${TxEditList.VERSION + 1},"mode":"SCOTTIE_1"}"""
        assertThat(parseEditList(json)).isNull()
    }

    @Test
    fun `the current version is accepted`() {
        val json = """{"v":${TxEditList.VERSION},"mode":"SCOTTIE_1"}"""
        assertThat(parseEditList(json)?.mode).isEqualTo(SstvMode.SCOTTIE_1)
    }

    @Test
    fun `an unknown mode is refused`() {
        // A mode this build does not have means the composition cannot be
        // rebuilt at the right pixel size, which is the whole point.
        assertThat(parseEditList("""{"v":1,"mode":"SCOTTIE_9000"}""")).isNull()
        assertThat(parseEditList("""{"v":1}""")).isNull()
    }

    @Test
    fun `missing optional fields fall back to neutral`() {
        val restored = parseEditList("""{"v":1,"mode":"SCOTTIE_1"}""")
        assertThat(restored).isNotNull()
        assertThat(restored!!.zoom).isEqualTo(1f)
        assertThat(restored.panX).isEqualTo(0f)
        assertThat(restored.adjustments.isNeutral()).isTrue()
        assertThat(restored.frame).isEqualTo(ImageFrame.NONE)
        assertThat(restored.overlays).isEmpty()
        assertThat(restored.paths).isEmpty()
        assertThat(restored.sourceUri).isNull()
    }

    @Test
    fun `an overlay with no id rejects the whole edit list`() {
        // Ids are how overlays are edited and deleted, so one without an id is
        // unreachable on the canvas. Skipping it returned a composition that
        // looked complete but was quietly missing a piece of text that had been
        // transmitted; the flattened-image fallback at least shows the operator
        // what actually went out.
        val json = """{"v":1,"mode":"SCOTTIE_1","overlays":[{"text":"orphan"}]}"""
        assertThat(parseEditList(json)).isNull()
    }

    @Test
    fun `a non-object overlay entry rejects the edit list`() {
        val json = """{"v":1,"mode":"SCOTTIE_1","overlays":["not an object"]}"""
        assertThat(parseEditList(json)).isNull()
    }

    @Test
    fun `one bad overlay among good ones still rejects the list`() {
        // Partial reconstruction is the failure mode being removed: the good
        // overlays surviving is what made the loss invisible.
        val json = """
            {"v":1,"mode":"SCOTTIE_1","overlays":[
              {"id":"t1","text":"KEPT"},
              {"text":"no id"}
            ]}
        """.trimIndent()
        assertThat(parseEditList(json)).isNull()
    }

    @Test
    fun `an unknown overlay style falls back to bar`() {
        val json =
            """{"v":1,"mode":"SCOTTIE_1","overlays":[{"id":"t1","text":"X","style":"NEON"}]}"""
        assertThat(parseEditList(json)?.overlays?.single()?.style).isEqualTo(OverlayStyle.BAR)
    }

    @Test
    fun `out-of-range values are clamped rather than rejected`() {
        val json = """
            {"v":1,"mode":"SCOTTIE_1","zoom":99,"panX":-9,"b":900,
             "overlays":[{"id":"t1","text":"X","x":-50,"y":400}]}
        """.trimIndent()
        val restored = parseEditList(json)
        assertThat(restored).isNotNull()
        assertThat(restored!!.zoom).isEqualTo(TX_MAX_ZOOM)
        assertThat(restored.panX).isEqualTo(-1f)
        assertThat(restored.adjustments.brightness).isEqualTo(ADJUSTMENT_MAX)
        assertThat(restored.overlays.single().xPercent).isEqualTo(0f)
        assertThat(restored.overlays.single().yPercent).isEqualTo(100f)
    }

    @Test
    fun `a path with an odd point count drops the stray value`() {
        // Pairing a lone coordinate with a default would plant a point
        // somewhere the operator never drew.
        val json =
            """{"v":1,"mode":"SCOTTIE_1","paths":[{"color":-1,"w":0.01,"pts":[10,20,30]}]}"""
        assertThat(parseEditList(json)?.paths?.single()?.points).hasSize(1)
    }

    @Test
    fun `a path with no usable points is dropped`() {
        val json = """{"v":1,"mode":"SCOTTIE_1","paths":[{"color":-1,"w":0.01,"pts":[]}]}"""
        assertThat(parseEditList(json)?.paths).isEmpty()
    }

    @Test
    fun `a path with no point array is dropped`() {
        val json = """{"v":1,"mode":"SCOTTIE_1","paths":[{"color":-1,"w":0.01}]}"""
        assertThat(parseEditList(json)?.paths).isEmpty()
    }

    // ----- the restorable predicate -------------------------------------------

    @Test
    fun `restorable reports what parse can actually deliver`() {
        assertThat(hasRestorableEdits(TxEditList.serialize(rich()))).isTrue()
        assertThat(hasRestorableEdits("")).isFalse()
        assertThat(hasRestorableEdits("garbage")).isFalse()
        // A pre-v21 row: the column defaults to the empty string.
        assertThat(hasRestorableEdits("")).isFalse()
    }

    // ----- generated card sources ---------------------------------------------

    @Test
    fun `a card source round-trips through the edit list`() {
        // The commonest reopen path: a one-tap card has no file behind it, so
        // its source is a scheme the restore recognises and regenerates.
        for (kind in TxCardKind.entries) {
            val original = TxComposition(
                sourceUri = cardSourceUri(kind),
                mode = SstvMode.SCOTTIE_1,
                overlays = gridCardOverlays("K1AF", "FN42"),
            )
            val restored = parseEditList(TxEditList.serialize(original))
            assertThat(restored?.sourceUri).isEqualTo(cardSourceUri(kind))
            assertThat(cardKindFromUri(restored?.sourceUri)).isEqualTo(kind)
        }
    }

    @Test
    fun `an ordinary photo uri is not mistaken for a card`() {
        assertThat(cardKindFromUri("content://media/external/images/9")).isNull()
        assertThat(cardKindFromUri(null)).isNull()
        assertThat(cardKindFromUri("")).isNull()
    }

    @Test
    fun `an unknown card name degrades to the flat fallback`() {
        // A future card kind read by an older build must not crash the restore.
        assertThat(cardKindFromUri(CARD_URI_SCHEME + ":POSTCARD")).isNull()
    }
}
