package radio.ks3ckc.sstvaf.ui.tx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.sstvaf.sstv.SstvMode

/**
 * Unit tests for the transmit flow's readouts: the confirm sheet's frequency
 * line and the elapsed/remaining labels the amber panel shows while keyed.
 *
 * These are the numbers an operator reads to decide whether to commit a
 * frequency and to know how much longer the rig is keyed, so they are worth
 * pinning independently of the composables that draw them.
 */
class TxTransmitPanelLogicTest {

    // ----- confirm sheet frequency line ---------------------------------------

    @Test
    fun `the confirm line gives the dial in full with its band`() {
        // Unlike the header chip, which drops the unit for space: this is the
        // sheet where a frequency is about to be tied up.
        assertThat(confirmFrequencyLine(14_230_000L, "20m")).isEqualTo("14.230 MHz · 20m")
    }

    @Test
    fun `the confirm line keeps kHz resolution`() {
        assertThat(confirmFrequencyLine(7_171_000L, "40m")).isEqualTo("7.171 MHz · 40m")
        assertThat(confirmFrequencyLine(28_680_000L, "10m")).isEqualTo("28.680 MHz · 10m")
    }

    @Test
    fun `an unknown band drops the separator`() {
        assertThat(confirmFrequencyLine(14_230_000L, "")).isEqualTo("14.230 MHz")
        assertThat(confirmFrequencyLine(14_230_000L, "  ")).isEqualTo("14.230 MHz")
    }

    // ----- elapsed / remaining while keyed ------------------------------------

    @Test
    fun `elapsed counts up toward the total airtime`() {
        val total = SstvMode.SCOTTIE_1.txDurationSeconds
        assertThat(txElapsedLabel(0f, total)).isEqualTo("0:00 / 1:51")
        assertThat(txElapsedLabel(1f, total)).isEqualTo("1:51 / 1:51")
    }

    @Test
    fun `elapsed is clamped to the total`() {
        // The transmitter's ticker is time-based, so a slow frame can report
        // past 1.0; the readout must not claim more airtime than the mode has.
        val total = SstvMode.ROBOT_36.txDurationSeconds
        assertThat(txElapsedLabel(1.4f, total)).isEqualTo("0:37 / 0:37")
        assertThat(txElapsedLabel(-0.2f, total)).isEqualTo("0:00 / 0:37")
    }

    @Test
    fun `elapsed includes the cw id tail in the total`() {
        // The panel reports how long the rig is keyed, which is the image plus
        // any CW station ID — not the mode's image time.
        val withId = totalTxDurationSeconds(SstvMode.ROBOT_36, cwTailSeconds = 5.0)
        assertThat(txElapsedLabel(0f, withId)).isEqualTo("0:00 / 0:42")
    }

    @Test
    fun `remaining counts down to zero`() {
        val total = SstvMode.SCOTTIE_1.txDurationSeconds
        assertThat(txRemainingLabel(0f, total)).isEqualTo("1:51")
        assertThat(txRemainingLabel(1f, total)).isEqualTo("0:00")
    }

    @Test
    fun `remaining never goes negative`() {
        assertThat(txRemainingLabel(1.5f, SstvMode.ROBOT_36.txDurationSeconds)).isEqualTo("0:00")
    }
}
