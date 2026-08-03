package radio.ks3ckc.sstvaf.ui.tx

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.sstvaf.sstv.SstvMode

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
        val edited = state.composition!!.withOverlayAdded(TextOverlay(text = "73"))
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
            .withOverlayAdded(TextOverlay(text = "73"))
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
}
