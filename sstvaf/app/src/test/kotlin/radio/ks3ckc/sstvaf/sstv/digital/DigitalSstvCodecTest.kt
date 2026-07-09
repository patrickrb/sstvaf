package radio.ks3ckc.sstvaf.sstv.digital

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random

/** End-to-end digital-SSTV codec: modulate -> demodulate, and the block-ARQ recovery loop. */
class DigitalSstvCodecTest {

    private fun image(n: Int, seed: Int = 0, format: Int = DigitalSstvCodec.Format.JPEG): DigitalSstvCodec.Image {
        val rnd = Random(seed.toLong())
        return DigitalSstvCodec.Image(format, 320, 256, ByteArray(n) { rnd.nextInt(256).toByte() })
    }

    private fun withLeadSilence(audio: FloatArray, lead: Int): FloatArray {
        val out = FloatArray(lead + audio.size)
        System.arraycopy(audio, 0, out, lead, audio.size)
        return out
    }

    @Test
    fun `clean round trip recovers every block and the payload`() {
        val codec = DigitalSstvCodec(DigitalSstvMode.STANDARD)
        val img = image(500, seed = 1)
        val audio = withLeadSilence(codec.encode(img), 240)

        val decoded = codec.decode(audio)!!
        assertThat(decoded.missing).isEmpty()
        assertThat(decoded.meta.format).isEqualTo(DigitalSstvCodec.Format.JPEG)
        assertThat(decoded.meta.width).isEqualTo(320)

        val rx = DigitalSstvReceiver()
        rx.accept(decoded)
        assertThat(rx.isComplete()).isTrue()
        assertThat(rx.retransmitRequest()).isNull()
        assertThat(rx.payload()).isEqualTo(img.payload)
    }

    @Test
    fun `encoded waveform stays within headroom`() {
        val audio = DigitalSstvCodec().encode(image(200, seed = 4))
        var peak = 0f
        for (v in audio) { if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v) }
        assertThat(peak).isAtMost(DigitalSstvCodec.TARGET_PEAK.toFloat() + 1e-4f)
        assertThat(peak).isGreaterThan(0.5f)
    }

    @Test
    fun `receiver accumulates blocks across partial retransmissions`() {
        val codec = DigitalSstvCodec()
        val img = image(600, seed = 2) // 5 blocks
        val rx = DigitalSstvReceiver()

        // First "transmission" only carried the even blocks (as if the odds faded).
        rx.accept(codec.decode(codec.encodeRetransmission(img, listOf(0, 2, 4)))!!)
        assertThat(rx.isComplete()).isFalse()
        val request = DigitalSstvContainer.decodeRetransmitRequest(rx.retransmitRequest()!!)
        assertThat(request).isEqualTo(listOf(1, 3))

        // Sender answers the BSR with exactly the requested blocks.
        rx.accept(codec.decode(codec.encodeRetransmission(img, request))!!)
        assertThat(rx.isComplete()).isTrue()
        assertThat(rx.payload()).isEqualTo(img.payload)
    }

    @Test
    fun `burst that destroys the payload keeps the header and drives a full retransmit`() {
        val codec = DigitalSstvCodec()
        val img = image(400, seed = 7)
        val audio = codec.encode(img).copyOf()

        // Wipe the body region (after preamble + header symbols) with noise;
        // the header is heavily coded and survives, so decode still yields meta.
        val rnd = Random(11)
        val bodyStart = audio.size / 2
        for (i in bodyStart until audio.size) audio[i] = (audio[i] + 2.0 * rnd.nextGaussian()).toFloat()

        val decoded = codec.decode(audio, searchLimit = 64)!!
        assertThat(decoded.meta.blockCount).isEqualTo(img.payload.size.let { (it + 127) / 128 })
        assertThat(decoded.missing).isNotEmpty()

        val rx = DigitalSstvReceiver()
        rx.accept(decoded)
        val request = DigitalSstvContainer.decodeRetransmitRequest(rx.retransmitRequest()!!)
        rx.accept(codec.decode(codec.encodeRetransmission(img, request))!!)
        assertThat(rx.isComplete()).isTrue()
        assertThat(rx.payload()).isEqualTo(img.payload)
    }

    @Test
    fun `single-block and tiny payloads round trip`() {
        val codec = DigitalSstvCodec()
        for (n in listOf(1, 5, 128)) {
            val img = image(n, seed = n)
            val rx = DigitalSstvReceiver()
            rx.accept(codec.decode(codec.encode(img))!!)
            assertThat(rx.payload()).isEqualTo(img.payload)
        }
    }

    @Test
    fun `all robustness modes carry an image end to end`() {
        for (mode in DigitalSstvMode.entries) {
            val codec = DigitalSstvCodec(mode)
            val img = image(300, seed = 42)
            val rx = DigitalSstvReceiver()
            rx.accept(codec.decode(codec.encode(img))!!)
            assertThat(rx.payload()).isEqualTo(img.payload)
        }
    }

    @Test
    fun `pure noise never reassembles into a valid image`() {
        val rnd = Random(5)
        val noise = FloatArray(6000) { (0.1 * rnd.nextGaussian()).toFloat() }
        // The header magic + FEC almost always reject noise outright (null); in
        // the astronomically unlikely event a "header" is accepted, no blocks
        // can reassemble into a CRC-valid payload.
        val decoded = DigitalSstvCodec().decode(noise)
        if (decoded != null) {
            val rx = DigitalSstvReceiver()
            rx.accept(decoded)
            assertThat(rx.payload()).isNull()
        }
    }

    @Test
    fun `new image resets receiver accumulation`() {
        val codec = DigitalSstvCodec()
        val rx = DigitalSstvReceiver()
        rx.accept(codec.decode(codec.encodeRetransmission(image(600, seed = 2), listOf(0, 1)))!!)
        assertThat(rx.isComplete()).isFalse()
        // A different image (different CRC) arrives; stale blocks are dropped.
        val other = image(140, seed = 99)
        rx.accept(codec.decode(codec.encode(other))!!)
        assertThat(rx.isComplete()).isTrue()
        assertThat(rx.payload()).isEqualTo(other.payload)
    }
}
