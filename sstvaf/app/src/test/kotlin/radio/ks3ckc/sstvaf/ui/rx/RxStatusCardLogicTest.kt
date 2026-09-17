package radio.ks3ckc.sstvaf.ui.rx

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.sstvaf.gallery.ImageDirection
import radio.ks3ckc.sstvaf.gallery.SavedImage
import radio.ks3ckc.sstvaf.sstv.SstvMode
import radio.ks3ckc.sstvaf.sstv.SstvRxState

/**
 * Unit tests for the redesigned Receive screen's status card and canvas
 * decisions.
 *
 * Robolectric for the resource-id assertions; the rest is pure.
 */
@RunWith(RobolectricTestRunner::class)
class RxStatusCardLogicTest {

    private fun decoding(
        rows: Int = 128,
        total: Int = 256,
        mode: SstvMode = SstvMode.SCOTTIE_1,
        quality: Float = 0.87f,
        slant: Float = 12.4f,
    ) = SstvRxState.Decoding(mode, rows, total, quality, slant)

    private fun saved(mode: SstvMode = SstvMode.SCOTTIE_1, quality: Float = 0.91f) =
        SstvRxState.Complete(mode, quality, frameAvailable = true)

    // ----- status kind -------------------------------------------------------

    @Test
    fun `a heard leader still reads as listening`() {
        // The canvas has its own "signal detected" look, but the card must not
        // promise a picture before any rows have arrived.
        assertThat(rxStatusKind(SstvRxState.Idle)).isEqualTo(RxStatusKind.LISTENING)
        assertThat(rxStatusKind(SstvRxState.Leader)).isEqualTo(RxStatusKind.LISTENING)
    }

    @Test
    fun `decoding, saved and lost each get their own status`() {
        assertThat(rxStatusKind(decoding())).isEqualTo(RxStatusKind.DECODING)
        assertThat(rxStatusKind(saved())).isEqualTo(RxStatusKind.SAVED)
        assertThat(rxStatusKind(SstvRxState.Aborted(40, SstvMode.SCOTTIE_1)))
            .isEqualTo(RxStatusKind.LOST)
    }

    @Test
    fun `each status has its own label resource`() {
        val ids = listOf(
            SstvRxState.Idle,
            decoding(),
            saved(),
            SstvRxState.Aborted(0, null),
        ).map { rxStatusLabelRes(it) }
        assertThat(ids).containsNoDuplicates()
        assertThat(rxStatusLabelRes(SstvRxState.Idle)).isEqualTo(R.string.rx_status_listening)
        assertThat(rxStatusLabelRes(decoding())).isEqualTo(R.string.rx_status_decoding)
    }

    @Test
    fun `only the decoding label takes the mode name`() {
        // The decoding string has a placeholder and the others do not; passing
        // an argument to a string without one silently drops it.
        assertThat(rxStatusLabelTakesMode(decoding())).isTrue()
        assertThat(rxStatusLabelTakesMode(SstvRxState.Idle)).isFalse()
        assertThat(rxStatusLabelTakesMode(SstvRxState.Leader)).isFalse()
        assertThat(rxStatusLabelTakesMode(saved())).isFalse()
        assertThat(rxStatusLabelTakesMode(SstvRxState.Aborted(0, null))).isFalse()
    }

    // ----- right-hand readout ------------------------------------------------

    @Test
    fun `listening has no readout so the caller can say auto-detect`() {
        assertThat(rxStatusRightLabel(SstvRxState.Idle)).isNull()
        assertThat(rxStatusRightLabel(SstvRxState.Leader)).isNull()
    }

    @Test
    fun `decoding counts down the remaining picture`() {
        // Half of Scottie 1's 256 rows done: about half its scan time left.
        val label = rxStatusRightLabel(decoding(rows = 128, total = 256))
        assertThat(label).isEqualTo("0:55")
    }

    @Test
    fun `a finished decode shows the mode's total duration`() {
        assertThat(rxStatusRightLabel(saved())).isEqualTo("1:51")
        assertThat(rxStatusRightLabel(saved(SstvMode.ROBOT_36))).isEqualTo("0:37")
    }

    @Test
    fun `a lost signal has no readout`() {
        assertThat(rxStatusRightLabel(SstvRxState.Aborted(40, SstvMode.SCOTTIE_1))).isNull()
    }

    // ----- progress ----------------------------------------------------------

    @Test
    fun `progress is empty while listening`() {
        assertThat(rxProgressFraction(SstvRxState.Idle)).isEqualTo(0f)
        assertThat(rxProgressFraction(SstvRxState.Leader)).isEqualTo(0f)
    }

    @Test
    fun `progress tracks rows decoded`() {
        assertThat(rxProgressFraction(decoding(rows = 64, total = 256))).isEqualTo(0.25f)
        assertThat(rxProgressFraction(decoding(rows = 256, total = 256))).isEqualTo(1f)
    }

    @Test
    fun `progress clamps an overrun`() {
        assertThat(rxProgressFraction(decoding(rows = 300, total = 256))).isEqualTo(1f)
    }

    @Test
    fun `progress survives a degenerate total`() {
        assertThat(rxProgressFraction(decoding(rows = 10, total = 0))).isEqualTo(0f)
    }

    @Test
    fun `a saved picture is complete`() {
        assertThat(rxProgressFraction(saved())).isEqualTo(1f)
    }

    @Test
    fun `a lost signal holds how much of the picture arrived`() {
        // Not zero: the bar is the record of what made it, and snapping it back
        // would hide that three quarters of the picture was fine.
        assertThat(rxProgressFraction(SstvRxState.Aborted(192, SstvMode.SCOTTIE_1)))
            .isEqualTo(0.75f)
    }

    @Test
    fun `a lost signal with no mode has no progress`() {
        assertThat(rxProgressFraction(SstvRxState.Aborted(0, null))).isEqualTo(0f)
    }

    // ----- the three meta readouts -------------------------------------------

    @Test
    fun `rows label counts toward the mode's total`() {
        assertThat(rxRowsLabel(decoding(rows = 159, total = 256))).isEqualTo("159 / 256")
    }

    @Test
    fun `rows label is dashes before anything decodes`() {
        assertThat(rxRowsLabel(SstvRxState.Idle)).isEqualTo("— / —")
        assertThat(rxRowsLabel(SstvRxState.Leader)).isEqualTo("— / —")
    }

    @Test
    fun `a finished decode reads as all rows in`() {
        assertThat(rxRowsLabel(saved())).isEqualTo("256 / 256")
        assertThat(rxRowsLabel(saved(SstvMode.ROBOT_36))).isEqualTo("240 / 240")
    }

    @Test
    fun `a partial decode reports what arrived`() {
        assertThat(rxRowsLabel(SstvRxState.Aborted(159, SstvMode.SCOTTIE_1)))
            .isEqualTo("159 / 256")
        assertThat(rxRowsLabel(SstvRxState.Aborted(159, null))).isEqualTo("— / —")
    }

    @Test
    fun `quality is a whole percent while decoding and after`() {
        assertThat(rxQualityLabel(decoding(quality = 0.87f))).isEqualTo("87%")
        assertThat(rxQualityLabel(saved(quality = 0.91f))).isEqualTo("91%")
    }

    @Test
    fun `quality clamps out-of-range engine values`() {
        assertThat(rxQualityLabel(decoding(quality = 1.4f))).isEqualTo("100%")
        assertThat(rxQualityLabel(decoding(quality = -0.2f))).isEqualTo("0%")
    }

    @Test
    fun `quality is a dash when nothing has decoded`() {
        assertThat(rxQualityLabel(SstvRxState.Idle)).isEqualTo("—")
        assertThat(rxQualityLabel(SstvRxState.Aborted(10, SstvMode.SCOTTIE_1))).isEqualTo("—")
    }

    @Test
    fun `slant is signed ppm while decoding and blank otherwise`() {
        assertThat(rxSlantLabel(decoding(slant = 12.4f))).isEqualTo("+12 ppm")
        assertThat(rxSlantLabel(decoding(slant = -3.2f))).isEqualTo("-3 ppm")
        assertThat(rxSlantLabel(SstvRxState.Idle)).isEmpty()
        assertThat(rxSlantLabel(saved())).isEmpty()
    }

    // ----- canvas ------------------------------------------------------------

    @Test
    fun `the canvas shows no image while listening`() {
        // Otherwise a stale picture from the last decode sits there looking live.
        assertThat(rxShowsCanvasImage(SstvRxState.Idle)).isFalse()
        assertThat(rxShowsCanvasImage(SstvRxState.Leader)).isFalse()
    }

    @Test
    fun `the canvas shows the image while decoding and when saved`() {
        assertThat(rxShowsCanvasImage(decoding())).isTrue()
        assertThat(rxShowsCanvasImage(saved())).isTrue()
    }

    @Test
    fun `a partial image shows only when rows actually decoded`() {
        assertThat(rxShowsCanvasImage(SstvRxState.Aborted(40, SstvMode.SCOTTIE_1))).isTrue()
        assertThat(rxShowsCanvasImage(SstvRxState.Aborted(0, SstvMode.SCOTTIE_1))).isFalse()
        assertThat(rxShowsCanvasImage(SstvRxState.Aborted(40, null))).isFalse()
    }

    @Test
    fun `the reveal matches the decode progress`() {
        assertThat(rxRevealFraction(decoding(rows = 64, total = 256))).isEqualTo(0.25f)
        assertThat(rxRevealFraction(saved())).isEqualTo(1f)
        assertThat(rxRevealFraction(SstvRxState.Idle)).isEqualTo(0f)
    }

    @Test
    fun `a partial picture reveals only what arrived`() {
        assertThat(rxRevealFraction(SstvRxState.Aborted(64, SstvMode.SCOTTIE_1)))
            .isEqualTo(0.25f)
    }

    // ----- recent strip ------------------------------------------------------

    private fun savedImage(
        mode: String = "Scottie 1",
        utcMillis: Long = 0L,
    ) = SavedImage(
        id = 1,
        fileName = "x.png",
        direction = ImageDirection.RX,
        mode = mode,
        freqHz = 14_230_000L,
        utcMillis = utcMillis,
        width = 320,
        height = 256,
        complete = true,
        quality = 0.9f,
        notes = "",
    )

    @Test
    fun `recent caption is the short code and the local time`() {
        // 1970-01-01T14:02Z; the assertion is on the shape, since the clock is
        // local by design (see rxRecentCaption).
        val caption = rxRecentCaption(savedImage(utcMillis = 50_520_000L))
        assertThat(caption).startsWith("S1 · ")
        assertThat(caption).matches("S1 · \\d\\d:\\d\\d")
    }

    @Test
    fun `an unknown mode name passes through unchanged`() {
        // A hand-edited database row must not break the strip.
        assertThat(rxRecentCaption(savedImage(mode = "Weird 9"))).startsWith("Weird 9 · ")
    }

    @Test
    fun `the strip holds four thumbnails`() {
        // Four 84dp cells and their gaps is what crosses a compact phone
        // without the last being a sliver inviting a scroll the row lacks.
        assertThat(RX_RECENT_LIMIT).isEqualTo(4)
    }
}
