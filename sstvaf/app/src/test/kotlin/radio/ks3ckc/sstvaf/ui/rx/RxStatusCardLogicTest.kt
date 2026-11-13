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
import java.time.ZoneId
import java.time.ZonedDateTime

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

    /** Receive on, and the store confirmed the write — the ordinary good case. */
    private fun kind(state: SstvRxState, enabled: Boolean = true, save: RxSaveState = RxSaveState.SAVED) =
        rxStatusKind(state, enabled, save)

    // ----- status kind -------------------------------------------------------

    @Test
    fun `a heard leader still reads as listening`() {
        // The canvas has its own "signal detected" look, but the card must not
        // promise a picture before any rows have arrived.
        assertThat(kind(SstvRxState.Idle)).isEqualTo(RxStatusKind.LISTENING)
        assertThat(kind(SstvRxState.Leader)).isEqualTo(RxStatusKind.LISTENING)
    }

    @Test
    fun `decoding, saved and lost each get their own status`() {
        assertThat(kind(decoding())).isEqualTo(RxStatusKind.DECODING)
        assertThat(kind(saved())).isEqualTo(RxStatusKind.SAVED)
        assertThat(kind(SstvRxState.Aborted(40, SstvMode.SCOTTIE_1)))
            .isEqualTo(RxStatusKind.LOST)
    }

    // ----- the receive switch ------------------------------------------------

    @Test
    fun `a switched-off receiver does not claim to be listening`() {
        // setEnabled(false) publishes no new rxState, so the engine sits on
        // whatever it last said. Without this the card read "Listening" with a
        // pulsing dot while the canvas said receive was off.
        assertThat(kind(SstvRxState.Idle, enabled = false)).isEqualTo(RxStatusKind.OFF)
        assertThat(kind(SstvRxState.Leader, enabled = false)).isEqualTo(RxStatusKind.OFF)
        assertThat(kind(decoding(), enabled = false)).isEqualTo(RxStatusKind.OFF)
        assertThat(kind(saved(), enabled = false)).isEqualTo(RxStatusKind.OFF)
        assertThat(kind(SstvRxState.Aborted(40, SstvMode.SCOTTIE_1), enabled = false))
            .isEqualTo(RxStatusKind.OFF)
    }

    @Test
    fun `only an idle-looking or finished receiver pulses`() {
        assertThat(rxStatusPulses(RxStatusKind.OFF)).isFalse()
        assertThat(rxStatusPulses(RxStatusKind.LOST)).isFalse()
        assertThat(rxStatusPulses(RxStatusKind.LISTENING)).isTrue()
        assertThat(rxStatusPulses(RxStatusKind.DECODING)).isTrue()
    }

    @Test
    fun `a switched-off receiver has no right-hand readout`() {
        // "auto-detect" advertises a decoder that is not running.
        assertThat(rxStatusRightLabel(SstvRxState.Idle, receiveEnabled = false)).isNull()
        assertThat(rxStatusRightLabel(decoding(), receiveEnabled = false)).isNull()
        assertThat(rxStatusRightLabel(saved(), receiveEnabled = false)).isNull()
    }

    // ----- completion is not persistence -------------------------------------

    @Test
    fun `a finished decode is not called saved until the store confirms it`() {
        assertThat(kind(saved(), save = RxSaveState.NONE)).isEqualTo(RxStatusKind.COMPLETE)
        assertThat(kind(saved(), save = RxSaveState.PENDING)).isEqualTo(RxStatusKind.COMPLETE)
        assertThat(kind(saved(), save = RxSaveState.SAVED)).isEqualTo(RxStatusKind.SAVED)
    }

    @Test
    fun `a failed save is reported, not dressed up as success`() {
        assertThat(kind(saved(), save = RxSaveState.FAILED)).isEqualTo(RxStatusKind.SAVE_FAILED)
    }

    @Test
    fun `the save state only matters once a decode has completed`() {
        // A failure left over from the previous picture must not colour the
        // status of the decode now in progress.
        assertThat(kind(decoding(), save = RxSaveState.FAILED)).isEqualTo(RxStatusKind.DECODING)
        assertThat(kind(SstvRxState.Idle, save = RxSaveState.FAILED))
            .isEqualTo(RxStatusKind.LISTENING)
    }

    @Test
    fun `the saved badge waits for a confirmed write`() {
        assertThat(rxShowsSavedBadge(saved(), RxSaveState.SAVED)).isTrue()
        assertThat(rxShowsSavedBadge(saved(), RxSaveState.PENDING)).isFalse()
        assertThat(rxShowsSavedBadge(saved(), RxSaveState.FAILED)).isFalse()
        assertThat(rxShowsSavedBadge(saved(), RxSaveState.NONE)).isFalse()
        // And never on a state that is not a completed decode at all.
        assertThat(rxShowsSavedBadge(decoding(), RxSaveState.SAVED)).isFalse()
        assertThat(rxShowsSavedBadge(SstvRxState.Idle, RxSaveState.SAVED)).isFalse()
    }

    @Test
    fun `each status has its own label resource`() {
        val ids = RxStatusKind.entries.map { rxStatusLabelRes(it) }
        assertThat(ids).containsNoDuplicates()
        assertThat(rxStatusLabelRes(RxStatusKind.LISTENING)).isEqualTo(R.string.rx_status_listening)
        assertThat(rxStatusLabelRes(RxStatusKind.DECODING)).isEqualTo(R.string.rx_status_decoding)
        assertThat(rxStatusLabelRes(RxStatusKind.OFF)).isEqualTo(R.string.rx_status_off)
        assertThat(rxStatusLabelRes(RxStatusKind.COMPLETE)).isEqualTo(R.string.rx_status_complete)
        assertThat(rxStatusLabelRes(RxStatusKind.SAVED)).isEqualTo(R.string.rx_status_saved)
        assertThat(rxStatusLabelRes(RxStatusKind.SAVE_FAILED))
            .isEqualTo(R.string.rx_status_save_failed)
    }

    @Test
    fun `only the decoding label takes the mode name`() {
        // The decoding string has a placeholder and the others do not; passing
        // an argument to a string without one silently drops it.
        assertThat(rxStatusLabelTakesMode(RxStatusKind.DECODING)).isTrue()
        for (other in RxStatusKind.entries.filter { it != RxStatusKind.DECODING }) {
            assertThat(rxStatusLabelTakesMode(other)).isFalse()
        }
    }

    // ----- right-hand readout ------------------------------------------------

    @Test
    fun `listening has no readout so the caller can say auto-detect`() {
        assertThat(rxStatusRightLabel(SstvRxState.Idle, receiveEnabled = true)).isNull()
        assertThat(rxStatusRightLabel(SstvRxState.Leader, receiveEnabled = true)).isNull()
    }

    @Test
    fun `decoding counts down the remaining picture`() {
        // Half of Scottie 1's 256 rows done: about half its scan time left.
        val label = rxStatusRightLabel(decoding(rows = 128, total = 256), receiveEnabled = true)
        assertThat(label).isEqualTo("0:55")
    }

    @Test
    fun `a finished decode shows the mode's total duration`() {
        assertThat(rxStatusRightLabel(saved(), receiveEnabled = true)).isEqualTo("1:51")
        assertThat(rxStatusRightLabel(saved(SstvMode.ROBOT_36), receiveEnabled = true))
            .isEqualTo("0:37")
    }

    @Test
    fun `a lost signal has no readout`() {
        assertThat(
            rxStatusRightLabel(SstvRxState.Aborted(40, SstvMode.SCOTTIE_1), receiveEnabled = true),
        ).isNull()
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
    fun `a completion with no frame shows nothing rather than the last picture`() {
        // frameAvailable = false means the row read failed and nothing was
        // snapshotted, so LastDecodedImage.frame still holds the PREVIOUS
        // decode — which would be presented as the picture that just finished.
        assertThat(
            rxShowsCanvasImage(
                SstvRxState.Complete(SstvMode.SCOTTIE_1, 0.9f, frameAvailable = false),
            ),
        ).isFalse()
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

    // ----- "received today" --------------------------------------------------

    private fun image(id: Long, utcMillis: Long, direction: ImageDirection = ImageDirection.RX) =
        SavedImage(
            id = id,
            fileName = "img$id.png",
            direction = direction,
            mode = "Scottie 1",
            freqHz = 14_230_000L,
            utcMillis = utcMillis,
            width = 320,
            height = 256,
            complete = true,
            quality = 0.9f,
            notes = "",
        )

    /** Fixed zone so the day boundaries in these tests are not the runner's. */
    private val zone: ZoneId = ZoneId.of("America/Chicago")

    /** 2026-09-17 14:00 local. */
    private val now = ZonedDateTime.of(2026, 9, 17, 14, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun localMillis(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `the strip keeps only images received today`() {
        // The heading says RECEIVED TODAY. Filtering by direction alone meant
        // it showed whatever the last four RX images were, however old.
        val images = listOf(
            image(1, localMillis(2026, 9, 17, 9)),
            image(2, localMillis(2026, 9, 16, 23, 59)),
            image(3, localMillis(2025, 9, 17, 9)),
            image(4, localMillis(2026, 9, 17, 13)),
        )
        assertThat(rxImagesReceivedToday(images, now, zone).map { it.id })
            .containsExactly(4L, 1L).inOrder()
    }

    @Test
    fun `the strip keeps received images only`() {
        val images = listOf(
            image(1, localMillis(2026, 9, 17, 9), ImageDirection.TX),
            image(2, localMillis(2026, 9, 17, 10), ImageDirection.RX),
        )
        assertThat(rxImagesReceivedToday(images, now, zone).map { it.id }).containsExactly(2L)
    }

    @Test
    fun `the strip counts today by the operator's local day, not UTC`() {
        // 2026-09-17 23:30 in Chicago is already the 18th in UTC; it is still
        // today for the operator looking at the screen.
        val lateTonight = localMillis(2026, 9, 17, 23, 30)
        val atLateTonight = lateTonight
        assertThat(rxImagesReceivedToday(listOf(image(1, lateTonight)), atLateTonight, zone))
            .hasSize(1)
    }

    @Test
    fun `the strip drops yesterday once the local day rolls over`() {
        val yesterdayEvening = localMillis(2026, 9, 16, 22)
        val justAfterMidnight = localMillis(2026, 9, 17, 0, 5)
        assertThat(
            rxImagesReceivedToday(listOf(image(1, yesterdayEvening)), justAfterMidnight, zone),
        ).isEmpty()
    }

    @Test
    fun `the strip shows the newest first and caps the row`() {
        val images = (1..8).map { image(it.toLong(), localMillis(2026, 9, 17, it)) }
        val shown = rxImagesReceivedToday(images, now, zone)
        assertThat(shown).hasSize(RX_RECENT_LIMIT)
        assertThat(shown.map { it.id }).containsExactly(8L, 7L, 6L, 5L).inOrder()
    }

    @Test
    fun `the next local day is always in the future`() {
        // Used as a coroutine delay: zero would spin.
        val midnight = localMillis(2026, 9, 17, 0, 0)
        assertThat(rxMillisUntilNextLocalDay(midnight, zone)).isEqualTo(24L * 60 * 60 * 1000)
        assertThat(rxMillisUntilNextLocalDay(now, zone)).isEqualTo(10L * 60 * 60 * 1000)
        assertThat(rxMillisUntilNextLocalDay(localMillis(2026, 9, 17, 23, 59), zone))
            .isGreaterThan(0L)
    }

    // ----- canvas height cap -------------------------------------------------

    @Test
    fun `the canvas leaves room for the rest of the screen`() {
        // A full-width 4-3 canvas on a 360dp-wide phone wants 270dp. In
        // landscape the screen is only ~360dp tall, and an uncapped canvas
        // pushed the status card and the recent strip off the bottom.
        assertThat(rxCanvasMaxHeightDp(360)).isLessThan(270)
        assertThat(rxCanvasMaxHeightDp(800)).isAtLeast(400)
    }

    @Test
    fun `a very short window still gets a usable canvas`() {
        assertThat(rxCanvasMaxHeightDp(120)).isEqualTo(140)
        assertThat(rxCanvasMaxHeightDp(0)).isEqualTo(140)
    }
}
