package radio.ks3ckc.sstvaf.ui.tx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.sstvaf.sstv.TxOutcome
import radio.ks3ckc.sstvaf.sstv.TxResult

/**
 * The screen's side of the transmit outcome: how a result is reported, and when
 * the editor is locked.
 *
 * The behaviour these cover is what the review found missing — a failure shown
 * as a success, and controls left live over the sent scrim.
 */
class TxOutcomeLogicTest {

    // ----- wording ------------------------------------------------------------

    @Test
    fun `each outcome gets its own headline`() {
        // A single "Sent" line for all three is how a failed transmission came
        // to be reported as a success.
        val titles = TxOutcome.entries.map { txOutcomeTitleRes(it) }
        assertThat(titles).containsNoDuplicates()
    }

    @Test
    fun `each outcome gets its own detail line`() {
        val details = TxOutcome.entries.map { txOutcomeDetailRes(it) }
        assertThat(details).containsNoDuplicates()
    }

    @Test
    fun `a headline is never reused as a detail line`() {
        val titles = TxOutcome.entries.map { txOutcomeTitleRes(it) }.toSet()
        val details = TxOutcome.entries.map { txOutcomeDetailRes(it) }.toSet()
        assertThat(titles.intersect(details)).isEmpty()
    }

    // ----- the editor lockout -------------------------------------------------

    @Test
    fun `the editor is live only when idle`() {
        assertThat(editorControlsEnabled(transmitting = false, showingOutcome = false)).isTrue()
    }

    @Test
    fun `the editor is locked while the rig is keyed`() {
        // The audio buffer is already encoded and playing, so an edit could
        // only make the preview disagree with what the far end received.
        assertThat(editorControlsEnabled(transmitting = true, showingOutcome = false)).isFalse()
    }

    @Test
    fun `the editor is locked while an outcome is on screen`() {
        // With the controls live under the scrim, a second Transmit tap started
        // a real transmission with the success overlay still up.
        assertThat(editorControlsEnabled(transmitting = false, showingOutcome = true)).isFalse()
    }

    @Test
    fun `transmitting and showing an outcome at once is still locked`() {
        assertThat(editorControlsEnabled(transmitting = true, showingOutcome = true)).isFalse()
    }

    // ----- consuming a result once --------------------------------------------

    /**
     * Mirrors the screen's condition: show a result when the rig has stopped
     * and its sequence is newer than the one already shown.
     */
    private fun shouldShow(result: TxResult?, transmitting: Boolean, lastSeen: Long): Boolean =
        !transmitting && result != null && result.sequence > lastSeen

    @Test
    fun `a new result is shown once`() {
        val r = TxResult(TxOutcome.COMPLETED, 1L)
        assertThat(shouldShow(r, transmitting = false, lastSeen = 0L)).isTrue()
        // After consuming it, the same value must not raise the scrim again -
        // the LiveData keeps holding it.
        assertThat(shouldShow(r, transmitting = false, lastSeen = 1L)).isFalse()
    }

    @Test
    fun `a result that arrived during another tab is still shown`() {
        // The case the old screen-local edge detector could not see at all: the
        // screen was not composed when the transmission ended, so there was no
        // true-to-false transition for it to observe.
        val r = TxResult(TxOutcome.COMPLETED, 3L)
        assertThat(shouldShow(r, transmitting = false, lastSeen = 0L)).isTrue()
    }

    @Test
    fun `the next transmission raises its own confirmation`() {
        assertThat(shouldShow(TxResult(TxOutcome.CANCELLED, 2L), false, lastSeen = 1L)).isTrue()
    }

    @Test
    fun `nothing is shown while the rig is still keyed`() {
        assertThat(shouldShow(TxResult(TxOutcome.COMPLETED, 1L), true, lastSeen = 0L)).isFalse()
    }

    @Test
    fun `no result means nothing to show`() {
        assertThat(shouldShow(null, transmitting = false, lastSeen = 0L)).isFalse()
    }
}
