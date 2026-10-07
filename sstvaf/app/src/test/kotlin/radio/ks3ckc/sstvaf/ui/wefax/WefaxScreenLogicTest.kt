package radio.ks3ckc.sstvaf.ui.wefax

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import radio.ks3ckc.sstvaf.wefax.WefaxRxState

class WefaxScreenLogicTest {

    // ----- status / rows labels -----

    @Test
    fun statusLabelPerState() {
        assertThat(wefaxStatusLabelRes(WefaxRxState.Idle))
            .isEqualTo(R.string.wefax_status_idle)
        assertThat(wefaxStatusLabelRes(WefaxRxState.Listening(120, 576)))
            .isEqualTo(R.string.wefax_status_listening)
        assertThat(wefaxStatusLabelRes(WefaxRxState.Decoding(120, 576, 1809, 5)))
            .isEqualTo(R.string.wefax_status_receiving)
        assertThat(wefaxStatusLabelRes(WefaxRxState.Stopped(100, 1809)))
            .isEqualTo(R.string.wefax_status_stopped)
    }

    @Test
    fun rowsCountOnlyWhereMeaningful() {
        assertThat(wefaxRowsCount(WefaxRxState.Idle)).isNull()
        assertThat(wefaxRowsCount(WefaxRxState.Listening(120, 576))).isNull()
        assertThat(wefaxRowsCount(WefaxRxState.Decoding(120, 576, 1809, 42))).isEqualTo(42)
        assertThat(wefaxRowsCount(WefaxRxState.Stopped(100, 1809))).isEqualTo(100)
        // A zero-row stop (noise, discarded) shows no count.
        assertThat(wefaxRowsCount(WefaxRxState.Stopped(0, 0))).isNull()
    }

    @Test
    fun presetLockedWhileReceiving() {
        assertThat(wefaxPresetSelectable(receiving = false)).isTrue()
        assertThat(wefaxPresetSelectable(receiving = true)).isFalse()
    }

    @Test
    fun previewResetsOnNewSessionBoundaries() {
        // Idle (initial value) and Listening (published by every start,
        // before any Decoding) reset the preview bookkeeping...
        assertThat(wefaxPreviewResets(WefaxRxState.Idle)).isTrue()
        assertThat(wefaxPreviewResets(WefaxRxState.Listening(120, 576))).isTrue()
        // ...while Decoding accumulates and Stopped keeps the finished strip.
        assertThat(wefaxPreviewResets(WefaxRxState.Decoding(120, 576, 1809, 5))).isFalse()
        assertThat(wefaxPreviewResets(WefaxRxState.Stopped(100, 1809))).isFalse()
    }

    // ----- rolling preview -----

    private fun row(width: Int, value: Int) = IntArray(width) { value }

    @Test
    fun previewFillsBeforeItRolls() {
        val w = 4
        val buf = IntArray(w * 3)
        var pop = wefaxRollPreview(buf, w, 3, 0, row(w, 1), 1)
        pop = wefaxRollPreview(buf, w, 3, pop, row(w, 2), 1)
        assertThat(pop).isEqualTo(2)
        assertThat(buf[0]).isEqualTo(1)
        assertThat(buf[w]).isEqualTo(2)
    }

    @Test
    fun previewRollsUpAtCapacity() {
        val w = 4
        val buf = IntArray(w * 3)
        var pop = 0
        for (v in 1..4) pop = wefaxRollPreview(buf, w, 3, pop, row(w, v), 1)
        assertThat(pop).isEqualTo(3)
        // Rows 2..4 survive; row 1 rolled off the top.
        assertThat(buf[0]).isEqualTo(2)
        assertThat(buf[w]).isEqualTo(3)
        assertThat(buf[2 * w]).isEqualTo(4)
    }

    @Test
    fun burstLargerThanPreviewKeepsOnlyTheNewestRows() {
        val w = 2
        val buf = IntArray(w * 3)
        // 5 rows arrive at once, values 10..14 stacked in one array.
        val burst = IntArray(5 * w) { i -> 10 + i / w }
        val pop = wefaxRollPreview(buf, w, 3, 0, burst, 5)
        assertThat(pop).isEqualTo(3)
        assertThat(buf[0]).isEqualTo(12)
        assertThat(buf[w]).isEqualTo(13)
        assertThat(buf[2 * w]).isEqualTo(14)
    }

    @Test
    fun zeroNewRowsIsANoOp() {
        val w = 4
        val buf = IntArray(w * 3)
        assertThat(wefaxRollPreview(buf, w, 3, 2, IntArray(0), 0)).isEqualTo(2)
    }
}
