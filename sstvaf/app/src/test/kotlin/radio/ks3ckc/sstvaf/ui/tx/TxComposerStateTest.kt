package radio.ks3ckc.sstvaf.ui.tx

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.sstvaf.sstv.SstvMode
import radio.ks3ckc.sstvaf.gallery.SavedImage
import radio.ks3ckc.sstvaf.gallery.ImageDirection

/**
 * [TxComposerState] owns the TX composer's photo/crop/overlay state across
 * tab switches: seeding/re-seeding rules, bitmap ownership (recycle on
 * replace/clear only), and the explicit user clear. Robolectric only for
 * [Bitmap]; the state logic itself is plain Kotlin.
 */
@RunWith(RobolectricTestRunner::class)
class TxComposerStateTest {

    private fun bitmap(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    private fun default(callsign: String = "K1ABC") =
        defaultTxComposition(callsign, SstvMode.SCOTTIE_1)

    @Test
    fun refreshDefaultsSeedsWhenUninitialized() {
        val state = TxComposerState()
        val seeded = state.refreshDefaults { default() }
        assertThat(state.composition).isEqualTo(seeded)
        assertThat(seeded).isEqualTo(default())
    }

    @Test
    fun refreshDefaultsReSeedsWhileUntouched() {
        // First visit before a callsign exists; the operator sets one in
        // Settings and comes back — the default overlays must pick it up.
        val state = TxComposerState()
        state.refreshDefaults { default(callsign = "") }
        val second = state.refreshDefaults { default(callsign = "K1ABC") }
        assertThat(second).isEqualTo(default(callsign = "K1ABC"))
        assertThat(state.composition).isEqualTo(second)
    }

    @Test
    fun refreshDefaultsKeepsUserEditsWithoutAPhoto() {
        // Overlay setup alone (no photo yet) is user work — a tab switch must
        // not re-seed over it.
        val state = TxComposerState()
        state.refreshDefaults { default() }
        val edited = state.composition!!.withOverlayStamped(TextOverlay(id = "t1", text = "73"))
        state.composition = edited
        assertThat(state.refreshDefaults { default() }).isEqualTo(edited)
        assertThat(state.composition).isEqualTo(edited)
    }

    @Test
    fun refreshDefaultsKeepsThePickedPhotoAndItsCrop() {
        val state = TxComposerState()
        state.refreshDefaults { default() }
        state.setImage(bitmap(), "content://photo/1")
        state.composition = state.composition!!.copy(zoom = 2f, panX = 0.5f)
        val kept = state.refreshDefaults { default() }
        assertThat(kept.sourceUri).isEqualTo("content://photo/1")
        assertThat(kept.zoom).isEqualTo(2f)
        assertThat(state.sourceBitmap).isNotNull()
    }

    @Test
    fun setImageInstallsPhotoAndResetsCrop() {
        val state = TxComposerState()
        state.refreshDefaults { default() }
        state.composition = state.composition!!.copy(zoom = 3f, panX = 1f, panY = -1f)
        val photo = bitmap()
        state.setImage(photo, "content://photo/1")
        assertThat(state.sourceBitmap).isSameInstanceAs(photo)
        val c = state.composition!!
        assertThat(c.sourceUri).isEqualTo("content://photo/1")
        assertThat(c.zoom).isEqualTo(1f)
        assertThat(c.panX).isEqualTo(0f)
        assertThat(c.panY).isEqualTo(0f)
    }

    @Test
    fun setImageRecyclesOnlyTheReplacedPhoto() {
        val state = TxComposerState()
        state.refreshDefaults { default() }
        val first = bitmap()
        val second = bitmap()
        state.setImage(first, "content://photo/1")
        state.setImage(second, "content://photo/2")
        assertThat(first.isRecycled).isTrue()
        assertThat(second.isRecycled).isFalse()
        assertThat(state.sourceBitmap).isSameInstanceAs(second)
    }

    @Test
    fun clearImageDropsPhotoButKeepsModeAndOverlays() {
        val state = TxComposerState()
        state.refreshDefaults { default() }
        val edited = state.composition!!
            .copy(mode = SstvMode.ROBOT_36)
            .withOverlayStamped(TextOverlay(id = "t1", text = "73"))
        state.composition = edited
        val photo = bitmap()
        state.setImage(photo, "content://photo/1")

        state.clearImage()

        assertThat(state.sourceBitmap).isNull()
        assertThat(photo.isRecycled).isTrue()
        val c = state.composition!!
        assertThat(c.sourceUri).isNull()
        assertThat(c.zoom).isEqualTo(1f)
        assertThat(c.mode).isEqualTo(SstvMode.ROBOT_36)
        assertThat(c.overlays).isEqualTo(edited.overlays)
    }

    @Test
    fun clearImageWithoutAPhotoIsANoOp() {
        val state = TxComposerState()
        val seeded = state.refreshDefaults { default() }
        state.clearImage()
        assertThat(state.composition).isEqualTo(seeded)
        assertThat(state.sourceBitmap).isNull()
    }

    @Test
    fun photoOnlyThenClearIsPristineAndPicksUpRefreshedDefaults() {
        // Copilot review (PR #73) flagged this path as a bug; it is pinned
        // here as INTENDED. Picking a photo and clearing it without any other
        // edit returns the composition to the pristine seeded state (default
        // mode/overlays, no photo), so the next visit refreshes defaults —
        // e.g. a callsign set in the meantime seeds its overlays. Nothing the
        // user did is lost: the photo stays cleared either way, and any
        // mode/overlay edit makes the state structurally distinct and thus
        // protected (see clearedCompositionCountsAsTouchedNotReSeeded).
        val state = TxComposerState()
        state.refreshDefaults { default(callsign = "") }
        state.setImage(bitmap(), "content://photo/1")
        state.clearImage()
        val after = state.refreshDefaults { default(callsign = "K1ABC") }
        assertThat(after).isEqualTo(default(callsign = "K1ABC"))
        assertThat(state.sourceBitmap).isNull()
    }

    @Test
    fun clearedCompositionCountsAsTouchedNotReSeeded() {
        // After an explicit clear the operator's mode/overlay choices remain;
        // the next tab visit must not blow them away with fresh defaults.
        val state = TxComposerState()
        state.refreshDefaults { default() }
        state.composition = state.composition!!.copy(mode = SstvMode.ROBOT_36)
        state.setImage(bitmap(), "content://photo/1")
        state.clearImage()
        val after = state.refreshDefaults { default() }
        assertThat(after.mode).isEqualTo(SstvMode.ROBOT_36)
    }

    // ----- the reopen request ------------------------------------------------

    private fun entry(id: Long, fileName: String = "tx.png") = SavedImage(
        id = id,
        fileName = fileName,
        direction = ImageDirection.TX,
        mode = SstvMode.SCOTTIE_1.displayName,
        freqHz = 14_230_000L,
        utcMillis = 1_700_000_000_000L,
        width = SstvMode.SCOTTIE_1.width,
        height = SstvMode.SCOTTIE_1.height,
        complete = true,
        quality = 1f,
        notes = "",
        edits = "",
    )

    @Test
    fun `no request is pending to begin with`() {
        assertThat(TxComposerState().pendingReopen).isNull()
    }

    @Test
    fun `a requested entry is readable before it is consumed`() {
        // The screen reads it to key its effect, and must be able to do that
        // without taking it: consuming first was what let a recomposition
        // cancel the load with the request already gone.
        val state = TxComposerState()
        state.requestReopen(entry(1))
        assertThat(state.pendingReopen?.id).isEqualTo(1L)
        assertThat(state.pendingReopen?.id).isEqualTo(1L)
    }

    @Test
    fun `consuming returns the entry once and clears it`() {
        val state = TxComposerState()
        state.requestReopen(entry(7))
        assertThat(state.consumeReopenRequest()?.id).isEqualTo(7L)
        assertThat(state.pendingReopen).isNull()
        assertThat(state.consumeReopenRequest()).isNull()
    }

    @Test
    fun `consuming nothing is harmless`() {
        assertThat(TxComposerState().consumeReopenRequest()).isNull()
    }

    @Test
    fun `a later request replaces an unconsumed one`() {
        // Two taps in the gallery before the first load lands: the second is
        // what the operator wants, so it wins rather than queueing.
        val state = TxComposerState()
        state.requestReopen(entry(1))
        state.requestReopen(entry(2))
        assertThat(state.consumeReopenRequest()?.id).isEqualTo(2L)
        assertThat(state.pendingReopen).isNull()
    }

    @Test
    fun `a request can be made again after being consumed`() {
        // Reopening the same row twice has to work; the id alone keys the
        // screen's effect, so the state must not dedupe it away.
        val state = TxComposerState()
        state.requestReopen(entry(5))
        state.consumeReopenRequest()
        state.requestReopen(entry(5))
        assertThat(state.consumeReopenRequest()?.id).isEqualTo(5L)
    }

    // ----- inset --------------------------------------------------------------

    private fun inset(uri: String = "file:///x/rx_1.png") =
        ImageInset(uri, 1.25f, 80.5f, 67.5f, INSET_SIZE_MEDIUM, "Scottie 1 · 14:32 UTC")

    @Test
    fun setInsetPinsThePictureAndItsDescription() {
        val state = TxComposerState()
        state.refreshDefaults { default() }
        val bmp = bitmap()
        state.setInset(bmp, inset())
        assertThat(state.insetBitmap).isSameInstanceAs(bmp)
        assertThat(state.composition!!.inset).isEqualTo(inset())
    }

    @Test
    fun setInsetRecyclesThePictureItReplaces() {
        val state = TxComposerState()
        state.refreshDefaults { default() }
        val first = bitmap()
        val second = bitmap()
        state.setInset(first, inset("file:///x/a.png"))
        state.setInset(second, inset("file:///x/b.png"))
        assertThat(first.isRecycled).isTrue()
        assertThat(second.isRecycled).isFalse()
        assertThat(state.composition!!.inset!!.sourceUri).isEqualTo("file:///x/b.png")
    }

    @Test
    fun setInsetWithTheSameBitmapDoesNotRecycleIt() {
        // Moving the inset re-pins the same picture; it must stay drawable.
        val state = TxComposerState()
        state.refreshDefaults { default() }
        val bmp = bitmap()
        state.setInset(bmp, inset())
        state.setInset(bmp, inset().copy(xPercent = 20f))
        assertThat(bmp.isRecycled).isFalse()
    }

    @Test
    fun clearInsetDropsAndRecyclesIt() {
        val state = TxComposerState()
        state.refreshDefaults { default() }
        val bmp = bitmap()
        state.setInset(bmp, inset())
        state.clearInset()
        assertThat(state.insetBitmap).isNull()
        assertThat(state.composition!!.inset).isNull()
        assertThat(bmp.isRecycled).isTrue()
    }

    @Test
    fun clearInsetWithNothingPinnedIsHarmless() {
        val state = TxComposerState()
        state.refreshDefaults { default() }
        val before = state.composition
        state.clearInset()
        assertThat(state.composition).isSameInstanceAs(before)
    }

    @Test
    fun clearingThePhotoKeepsAPendingReply() {
        // "New" on the canvas goes back to the four ways to start - the reply
        // banner must still be there, with its picture.
        val state = TxComposerState()
        state.refreshDefaults { default() }
        val insetBmp = bitmap()
        state.setInset(insetBmp, inset())
        state.setImage(bitmap(), "content://photo/1")
        state.clearImage()
        assertThat(state.sourceBitmap).isNull()
        assertThat(state.composition!!.inset).isEqualTo(inset())
        assertThat(state.insetBitmap).isSameInstanceAs(insetBmp)
        assertThat(insetBmp.isRecycled).isFalse()
    }

    @Test
    fun pickingAPhotoKeepsTheInset() {
        val state = TxComposerState()
        state.refreshDefaults { default() }
        state.setInset(bitmap(), inset())
        state.setImage(bitmap(), "content://photo/1")
        assertThat(state.composition!!.inset).isEqualTo(inset())
    }

    @Test
    fun aPendingReplyIsNotReSeededAway() {
        // No photo yet, but an inset is pinned: that is operator intent, and a
        // tab switch must not reset the composition to defaults over it.
        val state = TxComposerState()
        state.refreshDefaults { default() }
        state.setInset(bitmap(), inset())
        val kept = state.refreshDefaults { default(callsign = "W9XYZ") }
        assertThat(kept.inset).isEqualTo(inset())
        assertThat(kept.overlays).isEqualTo(default().overlays)
    }

    @Test
    fun restoreInsetBitmapLeavesThePlacementAlone() {
        // A reopened edit list already says where the inset goes.
        val state = TxComposerState()
        state.refreshDefaults { default() }
        val placed = inset().copy(xPercent = 19.5f, yPercent = 34.5f)
        state.composition = state.composition!!.withInset(placed)
        val old = bitmap()
        val fresh = bitmap()
        state.restoreInsetBitmap(old)
        state.restoreInsetBitmap(fresh)
        assertThat(state.insetBitmap).isSameInstanceAs(fresh)
        assertThat(old.isRecycled).isTrue()
        assertThat(state.composition!!.inset).isEqualTo(placed)
    }

    @Test
    fun aReplyRequestIsHeldUntilConsumed() {
        val state = TxComposerState()
        val entry = SavedImage(
            7, "rx_7.png", ImageDirection.RX, "Scottie 1", 14_230_000L, 0L, 320, 256, true, 1f, "",
        )
        assertThat(state.pendingReply).isNull()
        state.requestReply(entry)
        assertThat(state.pendingReply).isEqualTo(entry)
        assertThat(state.consumeReplyRequest()).isEqualTo(entry)
        assertThat(state.pendingReply).isNull()
        assertThat(state.consumeReplyRequest()).isNull()
    }
}
