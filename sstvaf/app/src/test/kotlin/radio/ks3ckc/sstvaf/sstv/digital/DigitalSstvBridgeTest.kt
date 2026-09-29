package radio.ks3ckc.sstvaf.sstv.digital

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DigitalSstvBridgeTest {

    // ----- upsampleLinear -----

    @Test
    fun `identity when rates match`() {
        val wave = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertThat(upsampleLinear(wave, 6000, 6000)).isEqualTo(wave)
    }

    @Test
    fun `output length scales with the rate ratio`() {
        val wave = FloatArray(6000)
        assertThat(upsampleLinear(wave, 6000, 48000)).hasLength(48000)
        assertThat(upsampleLinear(wave, 6000, 12000)).hasLength(12000)
    }

    @Test
    fun `an in-band sine survives 8x upsampling`() {
        // 1 kHz at 6 kHz -> 48 kHz; compare against the ideal sine.
        val n = 6000
        val src = FloatArray(n) { i -> sin(2.0 * PI * 1000.0 * i / 6000.0).toFloat() }
        val up = upsampleLinear(src, 6000, 48000)
        var maxErr = 0.0
        // Skip the tail where the source clamp flattens interpolation.
        for (i in 0 until up.size - 8) {
            val ideal = sin(2.0 * PI * 1000.0 * i / 48000.0)
            maxErr = maxOf(maxErr, abs(up[i] - ideal))
        }
        // Linear interpolation of a 1 kHz tone at 6 kHz sampling bends the
        // peaks by up to ~sin^2(pi f/fs) ~ 0.25; the SSB filter and the QPSK
        // slicer both shrug at that. This guards gross errors (dropped
        // samples, wrong ratio), not interpolation error.
        assertThat(maxErr).isLessThan(0.3)
    }

    // ----- Decimator2to1 -----

    @Test
    fun `passband tone survives decimation`() {
        val n = 12000
        val src = FloatArray(n) { i -> sin(2.0 * PI * 1000.0 * i / 12000.0).toFloat() }
        val out = Decimator2to1().push(src, n)
        assertThat(out.size).isEqualTo(n / 2)
        // Steady-state amplitude stays near 1 (skip the filter warm-up).
        var peak = 0f
        for (i in 100 until out.size) peak = maxOf(peak, abs(out[i]))
        assertThat(peak).isGreaterThan(0.9f)
        assertThat(peak).isLessThan(1.1f)
    }

    @Test
    fun `chunked decimation equals whole-buffer decimation`() {
        val n = 4096
        val rng = java.util.Random(7)
        val src = FloatArray(n) { rng.nextFloat() * 2f - 1f }
        val whole = Decimator2to1().push(src, n)

        val chunked = Decimator2to1()
        val out = ArrayList<Float>()
        var pos = 0
        for (size in intArrayOf(1, 7, 250, 1024, 999, n)) {
            val take = size.coerceAtMost(n - pos)
            if (take <= 0) break
            out.addAll(chunked.push(src.copyOfRange(pos, pos + take), take).toList())
            pos += take
        }
        assertThat(out.toFloatArray()).isEqualTo(whole)
    }

    @Test
    fun `reset clears history`() {
        val d = Decimator2to1()
        d.push(FloatArray(101) { 1f }, 101)
        d.reset()
        // After reset the first outputs match a fresh decimator's.
        val fresh = Decimator2to1().push(FloatArray(32) { 0.5f }, 32)
        assertThat(d.push(FloatArray(32) { 0.5f }, 32)).isEqualTo(fresh)
    }

    // ----- duration + encode-for-rate -----

    @Test
    fun `encode for device rate scales the sample count`() {
        val image = DigitalSstvCodec.Image(DigitalSstvCodec.Format.JPEG, 8, 8, ByteArray(64))
        val at6k = DigitalSstvCodec(DigitalSstvMode.STANDARD).encode(image)
        val at48k = encodeDigitalForRate(image, DigitalSstvMode.STANDARD, 48000)
        // x8 plus each stage's small filter-flush tail, never a truncation.
        assertThat(at48k.size).isAtLeast(at6k.size * 8)
        assertThat(at48k.size).isLessThan(at6k.size * 8 + 4000)
    }

    @Test
    fun `duration grows with payload and robustness`() {
        val small = digitalDurationSeconds(1024, DigitalSstvMode.STANDARD)
        val large = digitalDurationSeconds(8192, DigitalSstvMode.STANDARD)
        assertThat(large).isGreaterThan(small)

        val fast = digitalDurationSeconds(4096, DigitalSstvMode.FAST)
        val robust = digitalDurationSeconds(4096, DigitalSstvMode.ROBUST)
        assertThat(robust).isGreaterThan(fast)
    }

    @Test
    fun `analytic sample count matches a real encode exactly`() {
        // The duration label leans entirely on this equality — a layout
        // change that breaks it silently skews every estimate.
        for (mode in DigitalSstvMode.entries) {
            for (payload in intArrayOf(0, 1, 128, 129, 4096, 12 * 1024)) {
                val image = DigitalSstvCodec.Image(
                    DigitalSstvCodec.Format.JPEG, 8, 8, ByteArray(payload),
                )
                val codec = DigitalSstvCodec(mode)
                assertThat(codec.encodedSampleCount(payload))
                    .isEqualTo(codec.encode(image).size)
            }
        }
    }
}
