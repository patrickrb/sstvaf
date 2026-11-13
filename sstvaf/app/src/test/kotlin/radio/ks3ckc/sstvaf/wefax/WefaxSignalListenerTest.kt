package radio.ks3ckc.sstvaf.wefax

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.wave.HamRecorder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun overflowLogIsRateLimited() {
        // Capacity 2: the 3rd feed onward is a drop; the drop log must fire
        // only on the first drop and every DROP_LOG_EVERY-th after that.
        val listener = WefaxSignalListener(
            codec,
            WefaxSignalListener.SAMPLE_RATE_HZ,
            2,
            { logs += it },
        )
        listener.startDirect(WefaxPreset.DEFAULT)

        val drops = WefaxSignalListener.DROP_LOG_EVERY.toInt() + 2
        repeat(2 + drops) { listener.feed(FloatArray(16)) }

        assertThat(listener.queuedBufferCount()).isEqualTo(2)
        val overflowLogs = logs.filter { it.contains("overflow") }
        // Drop #1 and drop #50 log; drops 2..49, 51, 52 stay silent.
        assertThat(overflowLogs).hasSize(2)
        assertThat(overflowLogs[0]).contains("dropped=1 ")
        assertThat(overflowLogs[1])
            .contains("dropped=${WefaxSignalListener.DROP_LOG_EVERY} ")
        listener.stopReceiving()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // -- async stop (threaded path) --

    /** A HamRecorder whose running flag is forced on, without real audio. */
    private fun runningRecorder(): HamRecorder {
        val recorder = HamRecorder(null)
        HamRecorder::class.java.getDeclaredField("isRunning").apply {
            isAccessible = true
        }.setBoolean(recorder, true)
        return recorder
    }

    private fun awaitState(
        listener: WefaxSignalListener,
        predicate: (WefaxRxState) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(listener.stateNow())) return
            Thread.sleep(10)
        }
        throw AssertionError("state never reached; last=${listener.stateNow()}")
    }

    @Test
    fun threadedStopFinalizesOffTheCallerAndPublishesStopped() {
        val listener = newListener()
        val finishedLatch = CountDownLatch(1)
        var finishedThread: String? = null
        var image: WefaxSignalListener.FinishedImage? = null
        listener.onImageFinished = { img ->
            finishedThread = Thread.currentThread().name
            image = img
            finishedLatch.countDown()
        }

        codec.script.add(
            FakeWefaxCodec.ScriptedState(
                WefaxDecodeStatus.IMAGE,
                rowsReady = WefaxSignalListener.MIN_SAVE_ROWS + 2,
            ),
        )
        listener.startReceiving(runningRecorder(), WefaxPreset.DEFAULT)
        assertThat(listener.isReceiving()).isTrue()
        listener.feed(FloatArray(64))

        listener.stopReceiving()
        assertThat(listener.isReceiving()).isFalse()

        assertThat(finishedLatch.await(5, TimeUnit.SECONDS)).isTrue()
        // The heavy finalization ran on the dedicated stop thread, not the
        // caller (main) thread.
        assertThat(finishedThread).isEqualTo("WefaxStop")
        assertThat(image!!.rows).isEqualTo(WefaxSignalListener.MIN_SAVE_ROWS + 2)
        awaitState(listener) { it is WefaxRxState.Stopped }
        assertThat(listener.stateNow())
            .isEqualTo(
                WefaxRxState.Stopped(WefaxSignalListener.MIN_SAVE_ROWS + 2, codec.width),
            )
        assertThat(codec.finishCount).isEqualTo(1)
        assertThat(codec.closedSessions).isEqualTo(1)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun startIsRejectedWhileAStopIsStillFinalizing() {
        val listener = newListener()
        val inFinish = CountDownLatch(1)
        val gate = CountDownLatch(1)
        codec.onFinish = {
            inFinish.countDown()
            gate.await(5, TimeUnit.SECONDS)
        }

        listener.startReceiving(runningRecorder(), WefaxPreset.DEFAULT)
        listener.stopReceiving()
        assertThat(inFinish.await(5, TimeUnit.SECONDS)).isTrue()

        // A second stop while finalizing is a no-op...
        listener.stopReceiving()
        // ...and a start is rejected (with a log) until finalization ends.
        listener.startReceiving(runningRecorder(), WefaxPreset.DEFAULT)
        assertThat(listener.isReceiving()).isFalse()
        assertThat(codec.sessionsCreated).isEqualTo(1)
        assertThat(logs.any { it.contains("still finalizing") }).isTrue()

        gate.countDown()
        awaitState(listener) { it is WefaxRxState.Stopped }
        assertThat(codec.finishCount).isEqualTo(1)

        // Once the finalization completed, a fresh start works again.
        codec.onFinish = null
        listener.startReceiving(runningRecorder(), WefaxPreset.DEFAULT)
        assertThat(listener.isReceiving()).isTrue()
        assertThat(codec.sessionsCreated).isEqualTo(2)
        listener.stopReceiving()
        awaitState(listener) { !listener.isStopping() }
        shadowOf(Looper.getMainLooper()).idle()
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
