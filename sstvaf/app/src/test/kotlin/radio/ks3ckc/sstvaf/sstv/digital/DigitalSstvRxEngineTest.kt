package radio.ks3ckc.sstvaf.sstv.digital

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Drives [DigitalSstvRxEngine] the way the signal listener does: 12 kHz
 * audio in ~200 ms chunks. The transmissions are REAL — encoded by the
 * codec at its 6 kHz design rate and upsampled through the same bridge the
 * transmitter uses, so the whole TX->air->RX rate chain is what's under
 * test.
 */
class DigitalSstvRxEngineTest {

    private val logs = mutableListOf<String>()
    private val engine = DigitalSstvRxEngine { logs += it }

    /** ~200 ms of 12 kHz audio per push, like the recorder tap. */
    private fun feed(audio: FloatArray): DigitalRxImage? {
        var result: DigitalRxImage? = null
        var pos = 0
        while (pos < audio.size) {
            val take = CHUNK.coerceAtMost(audio.size - pos)
            engine.push(audio.copyOfRange(pos, pos + take), take)?.let { result = it }
            pos += take
        }
        return result
    }

    private fun silence(seconds: Double) = FloatArray((seconds * 12000).toInt())

    private fun onAir(image: DigitalSstvCodec.Image, mode: DigitalSstvMode): FloatArray =
        upsampleForDeviceRate(DigitalSstvCodec(mode).encode(image), 12000)

    private fun testImage(bytes: Int, seed: Int = 3): DigitalSstvCodec.Image {
        val rng = java.util.Random(seed.toLong())
        val payload = ByteArray(bytes).also { rng.nextBytes(it) }
        return DigitalSstvCodec.Image(DigitalSstvCodec.Format.JPEG, 320, 256, payload)
    }

    @Test
    fun `clean loopback recovers the payload`() {
        val image = testImage(700)
        val result = feed(
            silence(0.5) + onAir(image, DigitalSstvMode.STANDARD) + silence(1.5),
        )

        assertThat(result).isNotNull()
        assertThat(result!!.mode).isEqualTo(DigitalSstvMode.STANDARD)
        assertThat(result.payload).isEqualTo(image.payload)
        assertThat(result.width).isEqualTo(320)
        assertThat(result.height).isEqualTo(256)
        assertThat(result.format).isEqualTo(DigitalSstvCodec.Format.JPEG)
        assertThat(engine.isCapturing()).isFalse()
    }

    @Test
    fun `each robustness mode is detected as itself`() {
        for (mode in DigitalSstvMode.entries) {
            val engine = DigitalSstvRxEngine { }
            val image = testImage(400, seed = mode.ordinal)
            var result: DigitalRxImage? = null
            val audio = silence(0.5) + onAir(image, mode) + silence(1.5)
            var pos = 0
            while (pos < audio.size) {
                val take = CHUNK.coerceAtMost(audio.size - pos)
                engine.push(audio.copyOfRange(pos, pos + take), take)?.let { result = it }
                pos += take
            }
            assertThat(result?.mode).isEqualTo(mode)
            assertThat(result?.payload).isEqualTo(image.payload)
        }
    }

    @Test
    fun `noise alone neither captures nor decodes`() {
        val rng = java.util.Random(99)
        val noise = FloatArray(12000 * 10) { (rng.nextFloat() * 2f - 1f) * 0.5f }
        val result = feed(noise)
        assertThat(result).isNull()
        assertThat(engine.isCapturing()).isFalse()
        assertThat(logs.none { it.contains("image complete") }).isTrue()
    }

    @Test
    fun `a preamble mid-stream still triggers a capture`() {
        val image = testImage(400)
        feed(silence(3.0))
        val result = feed(onAir(image, DigitalSstvMode.FAST) + silence(1.5))
        assertThat(result).isNotNull()
        assertThat(result!!.payload).isEqualTo(image.payload)
    }

    @Test
    fun `reset returns to hunting`() {
        val image = testImage(400)
        feed(silence(0.5) + onAir(image, DigitalSstvMode.STANDARD) + silence(1.5))
        engine.reset()
        assertThat(engine.isCapturing()).isFalse()
        // And it decodes again after the reset.
        val second = feed(
            silence(0.5) + onAir(image, DigitalSstvMode.STANDARD) + silence(1.5),
        )
        assertThat(second?.payload).isEqualTo(image.payload)
    }

    companion object {
        private const val CHUNK = 2400
    }
}
