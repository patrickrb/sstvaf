package radio.ks3ckc.sstvaf.wefax

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Drives [WefaxSignalListener] synchronously (startDirect + stepOnce, no
 * decode thread) against [FakeWefaxCodec]'s scripted decoder. Robolectric
 * only because the published state is LiveData.
 */
@RunWith(RobolectricTestRunner::class)
class WefaxSignalListenerTest {

    private val codec = FakeWefaxCodec()
    private val logs = mutableListOf<String>()

    private fun newListener() = WefaxSignalListener(
        codec,
        WefaxSignalListener.SAMPLE_RATE_HZ,
        8,
        { logs += it },
    )

    private fun WefaxSignalListener.feedAndStep() {
        feed(FloatArray(64))
        stepOnce(0)
    }

    @Test
    fun sessionOpensWithThePresetParameters() {
        val listener = newListener()
        listener.startDirect(WefaxPreset.LPM120_IOC288)

        assertThat(codec.sessionParams).containsExactly(
            FakeWefaxCodec.SessionParams(
                WefaxSignalListener.SAMPLE_RATE_HZ, 120, 288,
            ),
        )
        listener.stopReceiving()
    }

    @Test
    fun phasingPublishesListeningAndRowsPublishDecoding() {
        val listener = newListener()
        listener.startDirect(WefaxPreset.DEFAULT)

        codec.script.add(FakeWefaxCodec.ScriptedState(WefaxDecodeStatus.PHASING))
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(WefaxRxState.Listening(120, 576))

        codec.script.add(FakeWefaxCodec.ScriptedState(WefaxDecodeStatus.IMAGE, rowsReady = 40))
        listener.feedAndStep()
        assertThat(listener.stateNow())
            .isEqualTo(WefaxRxState.Decoding(120, 576, codec.width, 40))
        listener.stopReceiving()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun stopFinishesTheDecodeAndHandsOverTheStrip() {
        val listener = newListener()
        val finished = mutableListOf<WefaxSignalListener.FinishedImage>()
        listener.onImageFinished = { finished += it }
        listener.startDirect(WefaxPreset.DEFAULT)

        codec.script.add(
            FakeWefaxCodec.ScriptedState(
                WefaxDecodeStatus.IMAGE,
                rowsReady = WefaxSignalListener.MIN_SAVE_ROWS + 4,
            ),
        )
        listener.feedAndStep()
        listener.stopReceiving()

        assertThat(codec.finishCount).isEqualTo(1)
        assertThat(codec.closedSessions).isEqualTo(1)
        assertThat(finished).hasSize(1)
        val img = finished.single()
        assertThat(img.rows).isEqualTo(WefaxSignalListener.MIN_SAVE_ROWS + 4)
        assertThat(img.width).isEqualTo(codec.width)
        assertThat(img.lpm).isEqualTo(120)
        assertThat(img.ioc).isEqualTo(576)
        // The gray payload is the session's deterministic pattern.
        assertThat(img.gray[0]).isEqualTo(codec.grayFor(0, 0))
        assertThat(img.gray[codec.width + 1]).isEqualTo(codec.grayFor(1, 1))
        assertThat(listener.stateNow())
            .isEqualTo(WefaxRxState.Stopped(img.rows, codec.width))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun stopBelowMinRowsDiscardsTheStrip() {
        val listener = newListener()
        val finished = mutableListOf<WefaxSignalListener.FinishedImage>()
        listener.onImageFinished = { finished += it }
        listener.startDirect(WefaxPreset.DEFAULT)

        codec.script.add(
            FakeWefaxCodec.ScriptedState(
                WefaxDecodeStatus.IMAGE,
                rowsReady = WefaxSignalListener.MIN_SAVE_ROWS - 1,
            ),
        )
        listener.feedAndStep()
        listener.stopReceiving()

        assertThat(finished).isEmpty()
        assertThat(codec.finishCount).isEqualTo(1)
        assertThat(codec.closedSessions).isEqualTo(1)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun stopWhenIdleIsANoOp() {
        val listener = newListener()
        listener.stopReceiving()
        assertThat(codec.finishCount).isEqualTo(0)
        assertThat(listener.stateNow()).isEqualTo(WefaxRxState.Idle)
    }

    @Test
    fun audioIsIgnoredWhenNotReceiving() {
        val listener = newListener()
        listener.feed(FloatArray(64))
        assertThat(listener.queuedBufferCount()).isEqualTo(0)
    }

    @Test
    fun readNewRowsReadsFromTheLiveSession() {
        val listener = newListener()
        listener.startDirect(WefaxPreset.DEFAULT)
        codec.script.add(FakeWefaxCodec.ScriptedState(WefaxDecodeStatus.IMAGE, rowsReady = 3))
        listener.feedAndStep()

        val out = ByteArray(2 * codec.width)
        val copied = listener.readNewRows(1, 2, out)

        assertThat(copied).isEqualTo(2)
        assertThat(out[0]).isEqualTo(codec.grayFor(1, 0))
        listener.stopReceiving()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
