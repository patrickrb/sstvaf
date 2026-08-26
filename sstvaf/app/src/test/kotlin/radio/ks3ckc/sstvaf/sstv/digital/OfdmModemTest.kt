package radio.ks3ckc.sstvaf.sstv.digital

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random
import kotlin.random.asKotlinRandom

/** COFDM modem: exact clean round trip, timing sync, and noise/multipath tolerance. */
class OfdmModemTest {

    private val modem = OfdmModem()

    @Test
    fun `symbol geometry`() {
        assertThat(modem.symbolLength).isEqualTo(256 + 32)
        assertThat(modem.bitsPerSymbol).isEqualTo(modem.dataCarrierCount * 2)
        assertThat(modem.preamble().size).isEqualTo(modem.symbolLength)
    }

    @Test
    fun `clean channel round trip is bit-exact`() {
        val rnd = Random(1).asKotlinRandom()
        repeat(20) {
            val bits = IntArray(modem.bitsPerSymbol) { rnd.nextInt(2) }
            val sym = modem.modulateSymbol(bits)
            val out = modem.demodulateSymbol(sym, 0)
            assertThat(out).isEqualTo(bits)
        }
    }

    @Test
    fun `modulated symbol is a real audio waveform in range`() {
        val bits = IntArray(modem.bitsPerSymbol) { it and 1 }
        val sym = modem.modulateSymbol(bits)
        assertThat(sym.all { it.isFinite() && kotlin.math.abs(it) < 1.0 }).isTrue()
    }

    @Test
    fun `timing sync locates the preamble after leading silence`() {
        val rnd = Random(2)
        val bits = IntArray(modem.bitsPerSymbol) { rnd.nextInt(2) }
        val frame = modem.preamble() + modem.modulateSymbol(bits)
        val lead = 137
        val audio = DoubleArray(lead + frame.size) { i ->
            (if (i >= lead) frame[i - lead] else 0.0) + 0.002 * rnd.nextGaussian()
        }
        val start = modem.findFrameStart(audio, 512)
        assertThat(start).isEqualTo(lead)
        // Demodulating the payload symbol at the found offset recovers the bits.
        val out = modem.demodulateSymbol(audio, start + modem.symbolLength)
        assertThat(out).isEqualTo(bits)
    }

    @Test
    fun `tolerates mild noise and multipath`() {
        val jr = Random(3)
        val rnd = jr.asKotlinRandom()
        val bits = IntArray(modem.bitsPerSymbol) { rnd.nextInt(2) }
        val clean = modem.modulateSymbol(bits)
        // 2-tap multipath within the cyclic prefix + light AWGN.
        val gain = 0.7
        val delayed = DoubleArray(clean.size)
        for (i in clean.indices) {
            delayed[i] = gain * clean[i] + if (i >= 1) 0.25 * gain * clean[i - 1] else 0.0
            delayed[i] += 0.01 * jr.nextGaussian()
        }
        val out = modem.demodulateSymbol(delayed, 0)
        val errors = out.indices.count { out[it] != bits[it] }
        // Pilot equalization removes the channel; light noise leaves few errors.
        assertThat(errors).isLessThan(modem.bitsPerSymbol / 10)
    }

    @Test
    fun `all robustness-mode param sets round trip cleanly`() {
        for (mode in DigitalSstvMode.entries) {
            val m = OfdmModem(mode.ofdm)
            val bits = IntArray(m.bitsPerSymbol) { (it * 5 + 1) and 1 }
            assertThat(m.demodulateSymbol(m.modulateSymbol(bits), 0)).isEqualTo(bits)
        }
    }
}
