package radio.ks3ckc.sstvaf.sstv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The transmit outcome and the image window inside a transmission.
 *
 * Both exist because a UI cannot derive them: the transmitting flag drops from
 * the teardown `finally` whatever happened, and overall progress covers audio
 * that is not the picture. These are pure, so they are tested without a rig.
 */
class TxResultTest {

    // ----- the image window ---------------------------------------------------

    @Test
    fun `a bare image occupies the whole transmission`() {
        val w = TxImageWindow.of(preToneSamples = 0, imageSamples = 1000, totalSamples = 1000)
        assertThat(w.start).isEqualTo(0f)
        assertThat(w.end).isEqualTo(1f)
    }

    @Test
    fun `a leader pushes the image start back`() {
        // 200 samples of VOX pre-tone before 800 of image.
        val w = TxImageWindow.of(preToneSamples = 200, imageSamples = 800, totalSamples = 1000)
        assertThat(w.start).isWithin(1e-5f).of(0.2f)
        assertThat(w.end).isEqualTo(1f)
    }

    @Test
    fun `a cw tail pulls the image end in`() {
        val w = TxImageWindow.of(preToneSamples = 0, imageSamples = 800, totalSamples = 1000)
        assertThat(w.start).isEqualTo(0f)
        assertThat(w.end).isWithin(1e-5f).of(0.8f)
    }

    @Test
    fun `a leader and a tail both count`() {
        val w = TxImageWindow.of(preToneSamples = 100, imageSamples = 700, totalSamples = 1000)
        assertThat(w.start).isWithin(1e-5f).of(0.1f)
        assertThat(w.end).isWithin(1e-5f).of(0.8f)
    }

    @Test
    fun `degenerate buffers fall back to the whole transmission`() {
        // A zero-width or inverted window would make the scan line jump or
        // divide by zero, so these degrade rather than propagate.
        assertThat(TxImageWindow.of(0, 0, 1000)).isEqualTo(TxImageWindow.WHOLE)
        assertThat(TxImageWindow.of(0, 500, 0)).isEqualTo(TxImageWindow.WHOLE)
        assertThat(TxImageWindow.of(2000, 500, 1000)).isEqualTo(TxImageWindow.WHOLE)
    }

    @Test
    fun `a negative pre-tone count is treated as none`() {
        val w = TxImageWindow.of(preToneSamples = -50, imageSamples = 1000, totalSamples = 1000)
        assertThat(w.start).isEqualTo(0f)
    }

    // ----- mapping overall progress to scan progress --------------------------

    @Test
    fun `the scan line does not move during the leader`() {
        // This is the bug the window exists to fix: the line used to start
        // sweeping before a receiver had seen a single pixel.
        val w = TxImageWindow(0.2f, 1f)
        assertThat(imageScanProgress(0f, w)).isEqualTo(0f)
        assertThat(imageScanProgress(0.1f, w)).isEqualTo(0f)
        assertThat(imageScanProgress(0.2f, w)).isEqualTo(0f)
    }

    @Test
    fun `the scan line sweeps across the image`() {
        val w = TxImageWindow(0.2f, 1f)
        assertThat(imageScanProgress(0.6f, w)).isWithin(1e-5f).of(0.5f)
        assertThat(imageScanProgress(1f, w)).isEqualTo(1f)
    }

    @Test
    fun `the scan line holds at the bottom through the cw tail`() {
        // And the matching half: it used to reach the bottom only while the
        // station ID was being keyed.
        val w = TxImageWindow(0f, 0.8f)
        assertThat(imageScanProgress(0.8f, w)).isEqualTo(1f)
        assertThat(imageScanProgress(0.9f, w)).isEqualTo(1f)
        assertThat(imageScanProgress(1f, w)).isEqualTo(1f)
    }

    @Test
    fun `out-of-range progress is clamped`() {
        val w = TxImageWindow.WHOLE
        assertThat(imageScanProgress(-1f, w)).isEqualTo(0f)
        assertThat(imageScanProgress(4f, w)).isEqualTo(1f)
    }

    @Test
    fun `a zero-width window falls back to overall progress`() {
        // Cannot happen through `of`, but the mapping must not divide by zero
        // if a window is ever constructed directly.
        assertThat(imageScanProgress(0.4f, TxImageWindow(0.5f, 0.5f))).isWithin(1e-5f).of(0.4f)
    }

    // ----- the result ---------------------------------------------------------

    @Test
    fun `a result carries its outcome and sequence`() {
        val r = TxResult(TxOutcome.COMPLETED, 7L)
        assertThat(r.outcome).isEqualTo(TxOutcome.COMPLETED)
        assertThat(r.sequence).isEqualTo(7L)
    }

    @Test
    fun `the three outcomes are distinct`() {
        // The whole point: a failure must not be indistinguishable from a send.
        assertThat(TxOutcome.entries).hasSize(3)
        assertThat(TxOutcome.entries.toSet()).hasSize(3)
    }
}
