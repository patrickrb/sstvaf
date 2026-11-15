package radio.ks3ckc.sstvaf.sstv

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.sstvaf.sstv.TxMuteGate.Edge

/** 12 kHz capture in the recorder's 200 ms (2400-sample) chunks. */
class TxMuteGateTest {

    private val chunk = 2400

    @Test
    fun passesAudioWhileTheAppIsNotTransmitting() {
        val gate = TxMuteGate(12000)
        repeat(10) {
            assertThat(gate.onBuffer(txActive = false, samples = chunk)).isEqualTo(Edge.NONE)
            assertThat(gate.muted).isFalse()
        }
    }

    @Test
    fun mutesFromTheFirstBufferOfATransmissionAndReportsTheEdgeOnce() {
        val gate = TxMuteGate(12000)
        gate.onBuffer(txActive = false, samples = chunk)

        assertThat(gate.onBuffer(txActive = true, samples = chunk)).isEqualTo(Edge.MUTED)
        assertThat(gate.muted).isTrue()
        repeat(50) {
            assertThat(gate.onBuffer(txActive = true, samples = chunk)).isEqualTo(Edge.NONE)
            assertThat(gate.muted).isTrue()
        }
    }

    @Test
    fun staysMutedForOneSecondOfAudioAfterTheTransmissionEnds() {
        val gate = TxMuteGate(12000)
        gate.onBuffer(txActive = true, samples = chunk)

        // 1000 ms tail = five 200 ms buffers still muted...
        repeat(5) {
            assertThat(gate.onBuffer(txActive = false, samples = chunk)).isEqualTo(Edge.NONE)
            assertThat(gate.muted).isTrue()
        }
        // ...and the sixth is heard.
        assertThat(gate.onBuffer(txActive = false, samples = chunk)).isEqualTo(Edge.UNMUTED)
        assertThat(gate.muted).isFalse()
        assertThat(gate.onBuffer(txActive = false, samples = chunk)).isEqualTo(Edge.NONE)
        assertThat(gate.muted).isFalse()
    }

    @Test
    fun aBufferTheTailOnlyPartlyCoversIsMutedWhole() {
        val gate = TxMuteGate(12000, tailMs = 300) // 3600 samples
        gate.onBuffer(txActive = true, samples = chunk)

        gate.onBuffer(txActive = false, samples = chunk) // 1200 left
        assertThat(gate.muted).isTrue()
        gate.onBuffer(txActive = false, samples = chunk) // tail ends inside this one
        assertThat(gate.muted).isTrue()
        assertThat(gate.onBuffer(txActive = false, samples = chunk)).isEqualTo(Edge.UNMUTED)
    }

    @Test
    fun aSecondTransmissionDuringTheTailRestartsIt() {
        val gate = TxMuteGate(12000)
        gate.onBuffer(txActive = true, samples = chunk)
        repeat(4) { gate.onBuffer(txActive = false, samples = chunk) }

        // Keyed again before the tail ran out: no unmute edge in between.
        assertThat(gate.onBuffer(txActive = true, samples = chunk)).isEqualTo(Edge.NONE)
        repeat(5) {
            gate.onBuffer(txActive = false, samples = chunk)
            assertThat(gate.muted).isTrue()
        }
        assertThat(gate.onBuffer(txActive = false, samples = chunk)).isEqualTo(Edge.UNMUTED)
    }

    @Test
    fun theTailIsCountedInAudioNotInBuffers() {
        val gate = TxMuteGate(12000)
        gate.onBuffer(txActive = true, samples = chunk)

        // A backlog delivered as one 1 s buffer uses the whole tail at once.
        gate.onBuffer(txActive = false, samples = 12000)
        assertThat(gate.muted).isTrue()
        assertThat(gate.onBuffer(txActive = false, samples = chunk)).isEqualTo(Edge.UNMUTED)
    }

    @Test
    fun aZeroTailUnmutesOnTheFirstBufferAfterTheTransmission() {
        val gate = TxMuteGate(12000, tailMs = 0)
        gate.onBuffer(txActive = true, samples = chunk)
        assertThat(gate.onBuffer(txActive = false, samples = chunk)).isEqualTo(Edge.UNMUTED)
        assertThat(gate.muted).isFalse()
    }
}
