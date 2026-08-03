package radio.ks3ckc.sstvaf.sstv

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Test

/**
 * [VoxPreTone] is pure math (no Android, no native lib), so it is pinned
 * directly: sample-count arithmetic, tone frequency, amplitude/ramp shape,
 * the phase-0 landing at the joint, and the strictly-additive prepend.
 */
class VoxPreToneTest {

    private val rate = 12000

    @Test
    fun sampleCountMatchesMillisecondArithmetic() {
        assertThat(VoxPreTone.sampleCount(300, rate)).isEqualTo(3600)
        assertThat(VoxPreTone.sampleCount(1000, rate)).isEqualTo(12000)
        assertThat(VoxPreTone.sampleCount(1, 48000)).isEqualTo(48)
    }

    @Test
    fun sampleCountIsZeroForDisabledOrDegenerateInput() {
        assertThat(VoxPreTone.sampleCount(0, rate)).isEqualTo(0)
        assertThat(VoxPreTone.sampleCount(-100, rate)).isEqualTo(0)
        assertThat(VoxPreTone.sampleCount(300, 0)).isEqualTo(0)
        assertThat(VoxPreTone.sampleCount(300, -1)).isEqualTo(0)
    }

    @Test
    fun sampleCountClampsToMaxLength() {
        assertThat(VoxPreTone.sampleCount(5000, rate))
            .isEqualTo(VoxPreTone.sampleCount(VoxPreTone.MAX_MS, rate))
    }

    @Test
    fun durationSecondsMatchesSampleCount() {
        assertThat(VoxPreTone.durationSeconds(300, rate)).isWithin(1e-9).of(0.3)
        assertThat(VoxPreTone.durationSeconds(0, rate)).isEqualTo(0.0)
        assertThat(VoxPreTone.durationSeconds(300, 0)).isEqualTo(0.0)
    }

    @Test
    fun samplesLengthMatchesSampleCountAndZeroIsEmpty() {
        assertThat(VoxPreTone.samples(300, rate)).hasLength(3600)
        assertThat(VoxPreTone.samples(0, rate)).isEmpty()
        assertThat(VoxPreTone.samples(300, 0)).isEmpty()
    }

    @Test
    fun toneStartsSilentRampsInAndStaysWithinAmplitude() {
        val tone = VoxPreTone.samples(300, rate)
        // Raised-cosine fade-in: dead silent at the very first sample (abs
        // because the zero-envelope product can round to -0.0f).
        assertThat(abs(tone[0])).isEqualTo(0f)
        // Never exceeds the encoder-matched level...
        assertThat(tone.map { abs(it) }.max()).isAtMost(VoxPreTone.AMPLITUDE + 1e-4f)
        // ...but reaches full level once the 5 ms ramp is done.
        val afterRamp = tone.drop(rate / 100) // skip the first 10 ms
        assertThat(afterRamp.map { abs(it) }.max())
            .isAtLeast(VoxPreTone.AMPLITUDE - 1e-3f)
    }

    @Test
    fun toneIs1900Hz() {
        val tone = VoxPreTone.samples(300, rate)
        var crossings = 0
        for (i in 1 until tone.size) {
            if ((tone[i - 1] < 0f) != (tone[i] < 0f)) crossings++
        }
        // A pure f Hz tone has 2f zero crossings per second; 300 ms of
        // 1900 Hz gives 1140. The fade-in can add/remove a couple.
        assertThat(crossings).isAtLeast(1130)
        assertThat(crossings).isAtMost(1150)
    }

    @Test
    fun toneEndsApproachingZeroPhaseForAContinuousJoint() {
        val tone = VoxPreTone.samples(300, rate)
        // The phase layout counts down to exactly 0 one sample past the end,
        // so the last sample must equal full-level sin(-step): the tone meets
        // an encoder leader that starts at zero phase without a jump.
        val step = 2.0 * PI * VoxPreTone.TONE_HZ / rate
        val expected = (VoxPreTone.AMPLITUDE * sin(-step)).toFloat()
        assertThat(tone.last()).isWithin(1e-4f).of(expected)
    }

    @Test
    fun prependToIsStrictlyAdditive() {
        val image = floatArrayOf(0.1f, -0.2f, 0.3f)
        val out = VoxPreTone.prependTo(image, 300, rate)
        val toneLen = VoxPreTone.sampleCount(300, rate)
        assertThat(out).hasLength(toneLen + image.size)
        // The image samples are byte-identical at the tail — never trimmed,
        // scaled, or shifted relative to each other.
        assertThat(out.copyOfRange(toneLen, out.size).toList())
            .isEqualTo(image.toList())
    }

    @Test
    fun prependToReturnsImageUnchangedWhenDisabled() {
        val image = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertThat(VoxPreTone.prependTo(image, 0, rate)).isSameInstanceAs(image)
    }
}
