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

    // ----- rxEtaSeconds -----------------------------------------------------

    @Test
    fun eta_atStart_isFullImageScanTime() {
        // Robot 36: 36.91 s total − 0.91 s header = 36.0 s of image; no rows in.
        assertThat(rxEtaSeconds(SstvMode.ROBOT_36, 0, 240)).isWithin(1e-9).of(36.0)
    }

    @Test
    fun eta_halfway_isHalfTheScanTime() {
        assertThat(rxEtaSeconds(SstvMode.ROBOT_36, 120, 240)).isWithin(1e-9).of(18.0)
    }

    @Test
    fun eta_lastRow_isNearlyZero() {
        assertThat(rxEtaSeconds(SstvMode.ROBOT_36, 239, 240)).isWithin(1e-9).of(36.0 / 240)
    }

    @Test
    fun eta_allRows_isZero() {
        assertThat(rxEtaSeconds(SstvMode.ROBOT_36, 240, 240)).isEqualTo(0.0)
    }

    @Test
    fun eta_overrun_clampsToZero() {
        // Defensive: engine publishing rowsReady > totalRows must not go negative.
        assertThat(rxEtaSeconds(SstvMode.ROBOT_36, 300, 240)).isEqualTo(0.0)
    }

    @Test
    fun eta_degenerateTotal_isZero() {
        assertThat(rxEtaSeconds(SstvMode.ROBOT_36, 10, 0)).isEqualTo(0.0)
        assertThat(rxEtaSeconds(SstvMode.ROBOT_36, 10, -1)).isEqualTo(0.0)
    }

    // ----- formatRxEta ------------------------------------------------------

    @Test
    fun formatEta_roundsToWholeSeconds() {
        assertThat(formatRxEta(12.4)).isEqualTo("~0:12")
        assertThat(formatRxEta(11.6)).isEqualTo("~0:12")
    }

    @Test
    fun formatEta_padsSecondsAndSplitsMinutes() {
        assertThat(formatRxEta(0.0)).isEqualTo("~0:00")
        assertThat(formatRxEta(9.0)).isEqualTo("~0:09")
        assertThat(formatRxEta(75.0)).isEqualTo("~1:15")
        assertThat(formatRxEta(600.0)).isEqualTo("~10:00")
    }

    @Test
    fun formatEta_negative_clampsToZero() {
        assertThat(formatRxEta(-5.0)).isEqualTo("~0:00")
    }

    // ----- rxEtaLabel -------------------------------------------------------

    @Test
    fun etaLabel_atStart_readsFullScanTime() {
        // Scottie 1: 110.54332 − 0.91 = 109.63332 s → rounds to 110 s → 1:50.
        assertThat(rxEtaLabel(SstvMode.SCOTTIE_1, 0, 256)).isEqualTo("~1:50")
    }

    @Test
    fun etaLabel_complete_isZero() {
        assertThat(rxEtaLabel(SstvMode.SCOTTIE_1, 256, 256)).isEqualTo("~0:00")
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

    // ----- rxSecondsRemaining -----------------------------------------------

    @Test
    fun eta_atStart_isNearlyFullImageTime() {
        // Scottie 1: 110.54332 s total − 0.91 s header ≈ 109.63 s of scan, which
        // rounds to 110 (roundToInt) at the start when all rows are still to come.
        assertThat(rxSecondsRemaining(0, 256, SstvMode.SCOTTIE_1.txDurationSeconds)).isEqualTo(110)
    }

    @Test
    fun eta_header_isSharedSourceOfTruth() {
        // The header the ETA subtracts is the same 0.91 s baked into every mode's
        // txDurationSeconds, sourced from SstvMode rather than duplicated here.
        assertThat(SstvMode.CALIBRATION_HEADER_SECONDS).isEqualTo(0.91)
        assertThat(SstvMode.ROBOT_36.txDurationSeconds - SstvMode.CALIBRATION_HEADER_SECONDS)
            .isWithin(1e-9).of(36.0)
    }

    @Test
    fun eta_halfway_isAboutHalfTheScanTime() {
        assertThat(rxSecondsRemaining(128, 256, SstvMode.SCOTTIE_1.txDurationSeconds)).isEqualTo(55)
    }

    @Test
    fun eta_complete_isZero() {
        assertThat(rxSecondsRemaining(256, 256, SstvMode.SCOTTIE_1.txDurationSeconds)).isEqualTo(0)
    }

    @Test
    fun secondsRemaining_overrun_clampsToZero() {
        // Defensive: engine publishing rowsReady > totalRows must not go negative.
        assertThat(rxSecondsRemaining(300, 256, SstvMode.SCOTTIE_1.txDurationSeconds)).isEqualTo(0)
    }

    @Test
    fun secondsRemaining_degenerateTotal_isZero() {
        assertThat(rxSecondsRemaining(10, 0, SstvMode.SCOTTIE_1.txDurationSeconds)).isEqualTo(0)
        assertThat(rxSecondsRemaining(10, -1, SstvMode.SCOTTIE_1.txDurationSeconds)).isEqualTo(0)
    }

    @Test
    fun eta_negativeDuration_clampsToZero() {
        // A malformed duration shorter than the header must never yield a negative ETA.
        assertThat(rxSecondsRemaining(0, 256, 0.5)).isEqualTo(0)
    }

    // ----- formatRxEta ------------------------------------------------------

    @Test
    fun formatEta_underOneMinute_padsSeconds() {
        assertThat(formatRxEta(55)).isEqualTo("0:55")
        assertThat(formatRxEta(5)).isEqualTo("0:05")
    }

    @Test
    fun formatEta_overOneMinute_splitsMinutesAndSeconds() {
        assertThat(formatRxEta(110)).isEqualTo("1:50")
        assertThat(formatRxEta(605)).isEqualTo("10:05")
    }

    @Test
    fun formatEta_zeroAndNegative_areZeroZero() {
        assertThat(formatRxEta(0)).isEqualTo("0:00")
        assertThat(formatRxEta(-9)).isEqualTo("0:00")
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
