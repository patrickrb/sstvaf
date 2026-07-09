package radio.ks3ckc.sstvaf.ui.rx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.sstvaf.sstv.SstvMode
import radio.ks3ckc.sstvaf.sstv.SstvRxState

/** Pure render-decision and formatting logic behind [RxScreen]. */
class RxScreenLogicTest {

    // ----- rxRenderKind ---------------------------------------------------

    @Test
    fun idle_rendersListening() {
        assertThat(rxRenderKind(SstvRxState.Idle)).isEqualTo(RxRenderKind.LISTENING)
    }

    @Test
    fun leader_rendersSignalDetected() {
        assertThat(rxRenderKind(SstvRxState.Leader)).isEqualTo(RxRenderKind.SIGNAL_DETECTED)
    }

    @Test
    fun decoding_rendersDecoding() {
        val state = SstvRxState.Decoding(SstvMode.SCOTTIE_1, 10, 256, 0.9f, 12f)
        assertThat(rxRenderKind(state)).isEqualTo(RxRenderKind.DECODING)
    }

    @Test
    fun complete_rendersComplete() {
        val state = SstvRxState.Complete(SstvMode.ROBOT_36, 0.8f, true)
        assertThat(rxRenderKind(state)).isEqualTo(RxRenderKind.COMPLETE)
    }

    @Test
    fun aborted_rendersAborted() {
        val state = SstvRxState.Aborted(42, SstvMode.MARTIN_1)
        assertThat(rxRenderKind(state)).isEqualTo(RxRenderKind.ABORTED)
    }

    // ----- rxProgressPercent ----------------------------------------------

    @Test
    fun progress_zeroRows_isZero() {
        assertThat(rxProgressPercent(0, 256)).isEqualTo(0)
    }

    @Test
    fun progress_halfway_truncatesToWholePercent() {
        assertThat(rxProgressPercent(128, 256)).isEqualTo(50)
        assertThat(rxProgressPercent(127, 256)).isEqualTo(49)
    }

    @Test
    fun progress_allRows_is100() {
        assertThat(rxProgressPercent(256, 256)).isEqualTo(100)
    }

    @Test
    fun progress_overrun_clampsTo100() {
        // Defensive: engine publishing rowsReady > totalRows must not overflow the bar.
        assertThat(rxProgressPercent(300, 256)).isEqualTo(100)
    }

    @Test
    fun progress_degenerateTotal_isZero() {
        assertThat(rxProgressPercent(10, 0)).isEqualTo(0)
        assertThat(rxProgressPercent(10, -1)).isEqualTo(0)
    }

    // ----- rxQualityFraction ------------------------------------------------

    @Test
    fun quality_clampsIntoUnitRange() {
        assertThat(rxQualityFraction(0.5f)).isEqualTo(0.5f)
        assertThat(rxQualityFraction(-0.2f)).isEqualTo(0f)
        assertThat(rxQualityFraction(1.7f)).isEqualTo(1f)
    }

    // ----- formatSlantPpm ---------------------------------------------------

    @Test
    fun slant_positive_getsPlusSign() {
        assertThat(formatSlantPpm(12.4f)).isEqualTo("+12")
    }

    @Test
    fun slant_negative_keepsMinusSign() {
        assertThat(formatSlantPpm(-3.2f)).isEqualTo("-3")
    }

    @Test
    fun slant_zero_hasNoSign() {
        assertThat(formatSlantPpm(0f)).isEqualTo("0")
    }

    @Test
    fun slant_roundsToNearestWholePpm() {
        assertThat(formatSlantPpm(0.6f)).isEqualTo("+1")
        assertThat(formatSlantPpm(-0.6f)).isEqualTo("-1")
        assertThat(formatSlantPpm(0.4f)).isEqualTo("0")
    }

    // ----- formatDialFrequency ----------------------------------------------

    @Test
    fun frequency_sstvCallingFrequency() {
        assertThat(formatDialFrequency(14_230_000L)).isEqualTo("14.230 MHz")
    }

    @Test
    fun frequency_subKilohertzTruncatesToThreeDecimals() {
        assertThat(formatDialFrequency(7_171_500L)).isEqualTo("7.172 MHz")
    }

    // ----- showsPartialImage --------------------------------------------------

    @Test
    fun aborted_withRowsAndMode_showsPartial() {
        assertThat(showsPartialImage(SstvRxState.Aborted(10, SstvMode.SCOTTIE_1))).isTrue()
    }

    @Test
    fun aborted_withNoRows_showsNoPartial() {
        assertThat(showsPartialImage(SstvRxState.Aborted(0, SstvMode.SCOTTIE_1))).isFalse()
    }

    @Test
    fun aborted_withNoMode_showsNoPartial() {
        assertThat(showsPartialImage(SstvRxState.Aborted(10, null))).isFalse()
    }
}
