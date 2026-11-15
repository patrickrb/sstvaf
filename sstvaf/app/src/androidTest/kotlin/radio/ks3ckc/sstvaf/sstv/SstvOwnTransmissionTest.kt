package radio.ks3ckc.sstvaf.sstv

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's own transmission must not come out of its receiver (issue #153).
 *
 * The waveform here is the one the transmitter plays (the real encoder,
 * through JNI), fed to the production RX engine in the recorder tap's 200 ms
 * buffers. That is what the microphone hands the receiver when the phone's
 * speaker is driving the radio, or what a USB rig returns as monitor audio.
 * Without the transmit check the decoder draws the picture and the app saves
 * it as a received one; the last test below is that control.
 */
@RunWith(AndroidJUnit4::class)
class SstvOwnTransmissionTest {

    private val logs = mutableListOf<String>()
    private val mode = SstvMode.ROBOT_36
    private val chunk = SstvSignalListener.SAMPLE_RATE_HZ * SstvSignalListener.MONITOR_BUFFER_MS / 1000

    @Volatile
    private var transmitting = false

    private fun newListener() = SstvSignalListener(
        NativeSstvCodec(),
        SstvSignalListener.SAMPLE_RATE_HZ,
        SstvSignalListener.QUEUE_CAPACITY,
        { logs += it },
        { 1_720_000_000_000L },
        { 14_230_000L },
    )

    @Before
    fun clearHolder() {
        LastDecodedImage.frame = null
        logs.clear()
        transmitting = false
    }

    /** What the app would put on the air: colour bars in Robot 36. */
    private fun outgoingPicture(): FloatArray {
        val third = mode.width / 3
        val pixels = IntArray(mode.width * mode.height) { i ->
            when ((i % mode.width) / third) {
                0 -> 0xFFFF0000.toInt()
                1 -> 0xFF00FF00.toInt()
                else -> 0xFF0000FF.toInt()
            }
        }
        return NativeSstvCodec().encode(
            pixels, mode.width, mode.height, mode, SstvSignalListener.SAMPLE_RATE_HZ,
        )
    }

    /**
     * Stream [audio] from sample [from] up to [to] in recorder-sized buffers,
     * stepping the (threadless) engine after each, and return every distinct
     * state the receiver published on the way.
     */
    private fun stream(
        listener: SstvSignalListener,
        audio: FloatArray,
        from: Int = 0,
        to: Int = audio.size,
    ): List<SstvRxState> {
        val seen = mutableListOf<SstvRxState>()
        var offset = from
        while (offset < to) {
            val n = minOf(chunk, to - offset)
            listener.onAudioBuffer(audio.copyOfRange(offset, offset + n), n)
            listener.stepOnce(0)
            offset += n
            val s = listener.stateNow()
            if (seen.lastOrNull() != s) seen += s
        }
        return seen
    }

    private fun quiet(seconds: Int) = FloatArray(seconds * SstvSignalListener.SAMPLE_RATE_HZ)

    @Test
    fun thePictureTheAppIsSendingIsNotDecoded() {
        val audio = outgoingPicture()
        val listener = newListener()
        listener.setTxActiveCheck { transmitting }
        listener.startDirect()
        try {
            // On the air for the whole picture: the receiver hears nothing.
            transmitting = true
            val during = stream(listener, audio)
            assertThat(during).containsExactly(SstvRxState.Idle)
            assertThat(LastDecodedImage.frame).isNull()
            assertThat(logs.none { it.contains("lock") }).isTrue()
            assertThat(logs).contains("SSTV RX: muted while the app transmits")

            // Back on receive. After the mute's tail the same engine decodes
            // the next picture on the frequency, header and all.
            transmitting = false
            stream(listener, quiet(2))
            assertThat(logs).contains("SSTV RX: listening again")

            val after = stream(listener, audio) + stream(listener, quiet(3))
            val end = after.last { it is SstvRxState.Complete || it is SstvRxState.Aborted }
            assertThat(end).isInstanceOf(SstvRxState.Complete::class.java)
            assertThat((end as SstvRxState.Complete).mode).isEqualTo(mode)
            val frame = checkNotNull(LastDecodedImage.frame)
            assertThat(frame.rowsDecoded).isEqualTo(mode.height)
            assertThat(frame.complete).isTrue()
            assertThat(listener.droppedBufferCount()).isEqualTo(0)
        } finally {
            listener.stop()
        }
    }

    @Test
    fun aPictureComingInWhenTheAppKeysUpEndsAsAPartial() {
        val audio = outgoingPicture()
        val cut = 15 * SstvSignalListener.SAMPLE_RATE_HZ
        val listener = newListener()
        listener.setTxActiveCheck { transmitting }
        listener.startDirect()
        try {
            // Another station's picture, 15 s in...
            val before = stream(listener, audio, to = cut)
            assertThat(before.last()).isInstanceOf(SstvRxState.Decoding::class.java)

            // ...when the operator transmits. The rest of that picture is on
            // the capture (as the app's own audio would be) but must not be
            // drawn: the image ends as a partial and nothing completes.
            transmitting = true
            val during = stream(listener, audio, from = cut) + stream(listener, quiet(3))
            assertThat(during.none { it is SstvRxState.Complete }).isTrue()
            val end = during.last()
            assertThat(end).isInstanceOf(SstvRxState.Aborted::class.java)
            // Stopped within a couple of seconds of key-down, not at the end
            // of the picture's 36 s.
            val rows = (end as SstvRxState.Aborted).partialRows
            assertThat(rows).isGreaterThan(60)
            assertThat(rows).isLessThan(120)
            assertThat(checkNotNull(LastDecodedImage.frame).complete).isFalse()
        } finally {
            listener.stop()
        }
    }

    @Test
    fun withoutTheTransmitCheckTheSamePictureIsDecoded() {
        // The control, and the bug as reported: nothing told the receiver the
        // app was transmitting, so its own picture came back as a received one.
        val audio = outgoingPicture()
        val listener = newListener()
        listener.startDirect()
        try {
            val seen = stream(listener, audio) + stream(listener, quiet(3))
            assertThat(seen.any { it is SstvRxState.Complete }).isTrue()
            assertThat(checkNotNull(LastDecodedImage.frame).complete).isTrue()
        } finally {
            listener.stop()
        }
    }
}
