package radio.ks3ckc.sstvaf.sstv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 12 kHz capture in the recorder's 200 ms (2400-sample) chunks. */
class TxMuteGateTest {

    private val chunk = 2400

    @Test
    fun passesAudioWhileTheAppIsNotTransmitting() {
        val gate = TxMuteGate(12000)
        repeat(10) { assertThat(gate.mutes(txActive = false, samples = chunk)).isFalse() }
    }

    @Test
    fun mutesFromTheFirstBufferOfATransmission() {
        val gate = TxMuteGate(12000)
        assertThat(gate.mutes(txActive = false, samples = chunk)).isFalse()
        repeat(50) { assertThat(gate.mutes(txActive = true, samples = chunk)).isTrue() }
    }

    @Test
    fun staysMutedForOneSecondOfAudioAfterTheTransmissionEnds() {
        val gate = TxMuteGate(12000)
        gate.mutes(txActive = true, samples = chunk)

        // 1000 ms tail = five 200 ms buffers still muted...
        repeat(5) { assertThat(gate.mutes(txActive = false, samples = chunk)).isTrue() }
        // ...and the sixth is heard.
        repeat(3) { assertThat(gate.mutes(txActive = false, samples = chunk)).isFalse() }
    }

    @Test
    fun aBufferTheTailOnlyPartlyCoversIsMutedWhole() {
        val gate = TxMuteGate(12000, tailMs = 300) // 3600 samples
        gate.mutes(txActive = true, samples = chunk)

        assertThat(gate.mutes(txActive = false, samples = chunk)).isTrue() // 1200 left
        assertThat(gate.mutes(txActive = false, samples = chunk)).isTrue() // tail ends inside it
        assertThat(gate.mutes(txActive = false, samples = chunk)).isFalse()
    }

    @Test
    fun aSecondTransmissionDuringTheTailRestartsIt() {
        val gate = TxMuteGate(12000)
        gate.mutes(txActive = true, samples = chunk)
        repeat(4) { assertThat(gate.mutes(txActive = false, samples = chunk)).isTrue() }

        // Keyed again before the tail ran out: muted throughout, full tail after.
        assertThat(gate.mutes(txActive = true, samples = chunk)).isTrue()
        repeat(5) { assertThat(gate.mutes(txActive = false, samples = chunk)).isTrue() }
        assertThat(gate.mutes(txActive = false, samples = chunk)).isFalse()
    }

    @Test
    fun theTailIsCountedInAudioNotInBuffers() {
        val gate = TxMuteGate(12000)
        gate.mutes(txActive = true, samples = chunk)

        // A backlog delivered as one 1 s buffer uses the whole tail at once.
        assertThat(gate.mutes(txActive = false, samples = 12000)).isTrue()
        assertThat(gate.mutes(txActive = false, samples = chunk)).isFalse()
    }

    @Test
    fun aZeroTailUnmutesOnTheFirstBufferAfterTheTransmission() {
        val gate = TxMuteGate(12000, tailMs = 0)
        gate.mutes(txActive = true, samples = chunk)
        assertThat(gate.mutes(txActive = false, samples = chunk)).isFalse()
    }
}
