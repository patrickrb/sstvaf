package radio.ks3ckc.sstvaf.sstv

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end loopback through the real libsstvaf.so: encode a Robot 36 test
 * pattern, push the waveform into a decoder session in recorder-sized chunks,
 * and check the image comes back. This is the on-device proof that the JNI
 * marshalling (mode ids, float buffers, ARGB rows, handle lifecycle) matches
 * the C API — the DSP itself is exercised far harder by the host tests
 * (cpp/sstvaf_glue/run_sstv_host_tests.*).
 *
 * Pixel tolerances are deliberately loose: Robot 36 is the lossiest mode
 * (4:2:0-style chroma, ~27 dB PSNR round-trip on the host).
 */
@RunWith(AndroidJUnit4::class)
class SstvCodecLoopbackTest {

    private val sampleRate = 12000

    /** Vertical RGB color bars, sampled away from the bar edges. */
    private fun colorBars(mode: SstvMode): IntArray {
        val third = mode.width / 3
        return IntArray(mode.width * mode.height) { i ->
            when ((i % mode.width) / third) {
                0 -> 0xFFFF0000.toInt() // red
                1 -> 0xFF00FF00.toInt() // green
                else -> 0xFF0000FF.toInt() // blue
            }
        }
    }

    @Test
    fun robot36EncodeDecodeLoopback() {
        val codec = NativeSstvCodec()
        val mode = SstvMode.ROBOT_36
        val pixels = colorBars(mode)

        val audio = codec.encode(pixels, mode.width, mode.height, mode, sampleRate)
        // 36.91 s at 12 kHz — pinned by the host golden tests too.
        assertThat(audio.size).isEqualTo(442920)

        codec.newDecoderSession(sampleRate).use { session ->
            assertThat(session.status()).isEqualTo(DecodeStatus.IDLE)
            assertThat(session.mode()).isNull()

            // Feed in ~200 ms chunks, the size the recorder tap delivers.
            val chunk = 2400
            var offset = 0
            while (offset < audio.size) {
                val n = minOf(chunk, audio.size - offset)
                session.push(audio.copyOfRange(offset, offset + n), n)
                offset += n
            }
            // A couple seconds of trailing silence lets the decoder finish the
            // last frame (same as the host round-trip suite; live RX gets this
            // for free because the recorder never stops delivering audio).
            val silence = FloatArray(chunk)
            repeat(16) { session.push(silence, silence.size) }

            assertThat(session.status()).isEqualTo(DecodeStatus.DONE)
            assertThat(session.mode()).isEqualTo(mode)
            assertThat(session.rowsReady()).isEqualTo(mode.height)
            assertThat(session.quality()).isGreaterThan(0.8f)
            // Encoder and decoder share one clock: slant ~0.
            assertThat(Math.abs(session.slantPpm())).isLessThan(50f)

            val decoded = IntArray(mode.width * mode.height)
            assertThat(session.readRows(0, mode.height, decoded)).isEqualTo(mode.height)

            // Rough pixel sanity in the middle of each bar, middle of frame.
            fun channelAt(x: Int, shift: Int): Int =
                (decoded[(mode.height / 2) * mode.width + x] shr shift) and 0xFF

            val redX = mode.width / 6 // center of bar 1
            val greenX = mode.width / 2 // center of bar 2
            val blueX = mode.width * 5 / 6 // center of bar 3

            // Red bar: strong R, weak B.
            assertThat(channelAt(redX, 16)).isGreaterThan(140)
            assertThat(channelAt(redX, 0)).isLessThan(110)
            // Green bar: strong G, weak R and B.
            assertThat(channelAt(greenX, 8)).isGreaterThan(140)
            assertThat(channelAt(greenX, 16)).isLessThan(110)
            assertThat(channelAt(greenX, 0)).isLessThan(110)
            // Blue bar: strong B, weak R.
            assertThat(channelAt(blueX, 0)).isGreaterThan(140)
            assertThat(channelAt(blueX, 16)).isLessThan(110)

            // Alpha is fully opaque everywhere.
            assertThat(decoded[0] ushr 24).isEqualTo(0xFF)

            // reset() returns the session to hunting.
            session.reset()
            assertThat(session.status()).isEqualTo(DecodeStatus.IDLE)
            assertThat(session.mode()).isNull()
        }
    }

    @Test
    fun encodeRejectsWrongDimensions() {
        val codec = NativeSstvCodec()
        val tooSmall = IntArray(10 * 10)
        try {
            codec.encode(tooSmall, 10, 10, SstvMode.ROBOT_36, sampleRate)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Robot 36 wants 320x240.
        }
    }
}
