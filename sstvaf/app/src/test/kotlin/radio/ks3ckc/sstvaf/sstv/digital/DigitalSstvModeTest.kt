package radio.ks3ckc.sstvaf.sstv.digital

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pins the digital-SSTV robustness-mode table. */
class DigitalSstvModeTest {

    @Test
    fun `mode table is pinned`() {
        assertThat(DigitalSstvMode.entries.map { it.shortCode })
            .containsExactly("DGR", "DGS", "DGF").inOrder()
        assertThat(DigitalSstvMode.STANDARD.displayName).isEqualTo("Digital Standard")
        assertThat(DigitalSstvMode.ROBUST.interleaveRows).isEqualTo(32)
        assertThat(DigitalSstvMode.STANDARD.interleaveRows).isEqualTo(16)
        assertThat(DigitalSstvMode.FAST.interleaveRows).isEqualTo(8)
    }

    @Test
    fun `robustness ordering - deeper guard and interleave on robust modes`() {
        assertThat(DigitalSstvMode.ROBUST.ofdm.ncp).isGreaterThan(DigitalSstvMode.STANDARD.ofdm.ncp)
        assertThat(DigitalSstvMode.STANDARD.ofdm.ncp).isGreaterThan(DigitalSstvMode.FAST.ofdm.ncp)
        assertThat(DigitalSstvMode.ROBUST.interleaveRows).isGreaterThan(DigitalSstvMode.FAST.interleaveRows)
    }

    @Test
    fun `short codes are unique`() {
        assertThat(DigitalSstvMode.entries.map { it.shortCode }.toSet())
            .hasSize(DigitalSstvMode.entries.size)
    }

    @Test
    fun `fromShortCode round trips and rejects unknown`() {
        for (mode in DigitalSstvMode.entries) {
            assertThat(DigitalSstvMode.fromShortCode(mode.shortCode)).isEqualTo(mode)
        }
        assertThat(DigitalSstvMode.fromShortCode("XXX")).isNull()
    }

    @Test
    fun `all mode param sets are structurally valid`() {
        // The OfdmParams init blocks enforce the invariants; constructing a
        // modem per mode proves they hold and produces usable carriers.
        for (mode in DigitalSstvMode.entries) {
            val modem = OfdmModem(mode.ofdm)
            assertThat(modem.dataCarrierCount).isGreaterThan(0)
        }
    }
}
