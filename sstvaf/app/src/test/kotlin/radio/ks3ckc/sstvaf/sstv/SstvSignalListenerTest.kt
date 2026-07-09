package radio.ks3ckc.sstvaf.sstv

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.wave.HamRecorder
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Drives [SstvSignalListener] synchronously (startDirect + stepOnce, no
 * decode thread) against [FakeSstvCodec]'s scripted decoder. Robolectric only
 * because the published state is LiveData.
 */
@RunWith(RobolectricTestRunner::class)
class SstvSignalListenerTest {

    private val codec = FakeSstvCodec()
    private val logs = mutableListOf<String>()
    private var nowMs = 1_720_000_000_000L

    private fun newListener(queueCapacity: Int = 8) = SstvSignalListener(
        codec,
        SstvSignalListener.SAMPLE_RATE_HZ,
        queueCapacity,
        { logs += it },
        { nowMs },
        { 14_230_000L },
    )

    private fun buffer(firstSample: Float = 0f) =
        FloatArray(64) { if (it == 0) firstSample else 0f }

    private fun SstvSignalListener.feedAndStep(firstSample: Float = 0f) {
        onAudioBuffer(buffer(firstSample), 64)
        stepOnce(0)
    }

    @Before
    fun clearHolder() {
        LastDecodedImage.frame = null
    }

    @After
    fun drainMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun startsIdleAndStaysIdleWithoutSignal() {
        val listener = newListener()
        listener.startDirect()
        listener.feedAndStep() // script empty -> decoder stays IDLE
        assertThat(listener.stateNow()).isEqualTo(SstvRxState.Idle)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(listener.rxState.value).isEqualTo(SstvRxState.Idle)
    }

    @Test
    fun transitionsIdleToLeaderToDecodingToComplete() {
        val listener = newListener()
        listener.startDirect()

        codec.script += FakeSstvCodec.ScriptedState(DecodeStatus.LEADER)
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(SstvRxState.Leader)

        codec.script += FakeSstvCodec.ScriptedState(
            DecodeStatus.IMAGE, SstvMode.ROBOT_36, rowsReady = 10, quality = 0.5f,
        )
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(
            SstvRxState.Decoding(SstvMode.ROBOT_36, 10, 240, 0.5f, 0f),
        )
        assertThat(logs.any { it.contains("VIS lock") && it.contains("Robot 36") }).isTrue()

        codec.script += FakeSstvCodec.ScriptedState(
            DecodeStatus.DONE, SstvMode.ROBOT_36, rowsReady = 240,
            quality = 0.9f, slantPpm = 12.5f,
        )
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(
            SstvRxState.Complete(SstvMode.ROBOT_36, 0.9f, frameAvailable = true),
        )
        assertThat(logs.any { it.contains("image complete") }).isTrue()

        // LiveData observers see the terminal state too.
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(listener.rxState.value)
            .isEqualTo(SstvRxState.Complete(SstvMode.ROBOT_36, 0.9f, frameAvailable = true))
    }

    @Test
    fun completeSnapshotsFrameIntoLastDecodedImageBeforeReset() {
        val listener = newListener()
        listener.startDirect()
        codec.script += FakeSstvCodec.ScriptedState(
            DecodeStatus.DONE, SstvMode.ROBOT_36, rowsReady = 240,
            quality = 0.9f, slantPpm = -3f,
        )
        listener.feedAndStep()

        val frame = LastDecodedImage.frame
        assertThat(frame).isNotNull()
        frame!!
        assertThat(frame.mode).isEqualTo(SstvMode.ROBOT_36)
        assertThat(frame.width).isEqualTo(320)
        assertThat(frame.height).isEqualTo(240)
        assertThat(frame.rowsDecoded).isEqualTo(240)
        assertThat(frame.complete).isTrue()
        assertThat(frame.quality).isEqualTo(0.9f)
        assertThat(frame.slantPpm).isEqualTo(-3f)
        assertThat(frame.utcMillis).isEqualTo(nowMs)
        assertThat(frame.dialFrequencyHz).isEqualTo(14_230_000L)
        assertThat(frame.pixels).hasLength(320 * 240)
        assertThat(frame.pixels[0]).isEqualTo(codec.pixelFor(0, 0))
        assertThat(frame.pixels[5 * 320 + 7]).isEqualTo(codec.pixelFor(5, 7))
        // The decoder was reset back to hunting after the snapshot.
        assertThat(codec.resetCount).isEqualTo(1)
    }

    @Test
    fun autoResetHoldsCompleteUntilNextSignal() {
        val listener = newListener()
        listener.startDirect()
        codec.script += FakeSstvCodec.ScriptedState(
            DecodeStatus.DONE, SstvMode.MARTIN_1, rowsReady = 256, quality = 0.8f,
        )
        listener.feedAndStep()
        val terminal = listener.stateNow()
        assertThat(terminal).isInstanceOf(SstvRxState.Complete::class.java)

        // Post-reset the session reports IDLE — the terminal state must hold.
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(terminal)

        // The next leader flips it back into live states.
        codec.script += FakeSstvCodec.ScriptedState(DecodeStatus.LEADER)
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(SstvRxState.Leader)
    }

    @Test
    fun abortedPublishesPartialAndSnapshotsPartialFrame() {
        val listener = newListener()
        listener.startDirect()
        codec.script += FakeSstvCodec.ScriptedState(
            DecodeStatus.ABORTED, SstvMode.SCOTTIE_1, rowsReady = 42, quality = 0.3f,
        )
        listener.feedAndStep()

        assertThat(listener.stateNow()).isEqualTo(SstvRxState.Aborted(42, SstvMode.SCOTTIE_1))
        val frame = LastDecodedImage.frame
        assertThat(frame).isNotNull()
        assertThat(frame!!.complete).isFalse()
        assertThat(frame.rowsDecoded).isEqualTo(42)
        // Full-size buffer with the undecoded remainder untouched (zeros).
        assertThat(frame.pixels).hasLength(320 * 256)
        assertThat(frame.pixels[41 * 320]).isEqualTo(codec.pixelFor(41, 0))
        assertThat(frame.pixels[100 * 320]).isEqualTo(0)
        assertThat(codec.resetCount).isEqualTo(1)
    }

    @Test
    fun boundedQueueDropsOldestAndLogs() {
        val listener = newListener(queueCapacity = 2)
        listener.startDirect()
        listener.onAudioBuffer(buffer(1f), 64)
        listener.onAudioBuffer(buffer(2f), 64)
        listener.onAudioBuffer(buffer(3f), 64) // overflow: drops buffer 1

        assertThat(listener.droppedBufferCount()).isEqualTo(1)
        assertThat(listener.queuedBufferCount()).isEqualTo(2)
        assertThat(logs.any { it.contains("overflow") }).isTrue()

        listener.stepOnce(0)
        assertThat(codec.pushedFirstSamples).containsExactly(2f, 3f).inOrder()
    }

    @Test
    fun setEnabledFalseStopsPushingUntilReEnabled() {
        val listener = newListener()
        listener.startDirect()

        listener.setEnabled(false)
        listener.onAudioBuffer(buffer(), 64)
        assertThat(listener.queuedBufferCount()).isEqualTo(0)
        listener.stepOnce(0)
        assertThat(codec.pushCount).isEqualTo(0)

        listener.setEnabled(true)
        listener.feedAndStep()
        assertThat(codec.pushCount).isEqualTo(1)
    }

    @Test
    fun disablingClearsAlreadyQueuedAudio() {
        val listener = newListener()
        listener.startDirect()
        listener.onAudioBuffer(buffer(), 64)
        assertThat(listener.queuedBufferCount()).isEqualTo(1)
        listener.setEnabled(false)
        assertThat(listener.queuedBufferCount()).isEqualTo(0)
    }

    @Test
    fun readNewRowsPassesThroughToLiveSession() {
        val listener = newListener()
        listener.startDirect()
        codec.script += FakeSstvCodec.ScriptedState(
            DecodeStatus.IMAGE, SstvMode.ROBOT_36, rowsReady = 3,
        )
        listener.feedAndStep()

        val out = IntArray(320 * 3)
        assertThat(listener.readNewRows(0, 3, out)).isEqualTo(3)
        assertThat(out[0]).isEqualTo(codec.pixelFor(0, 0))
        assertThat(out[2 * 320 + 9]).isEqualTo(codec.pixelFor(2, 9))
    }

    @Test
    fun ignoresAudioBeforeStart() {
        val listener = newListener()
        listener.onAudioBuffer(buffer(), 64)
        assertThat(listener.queuedBufferCount()).isEqualTo(0)
    }

    @Test
    fun stopClosesDirectSession() {
        val listener = newListener()
        listener.startDirect()
        listener.stop()
        assertThat(codec.closedSessions).isEqualTo(1)
        val out = IntArray(10)
        assertThat(listener.readNewRows(0, 1, out)).isEqualTo(0)
    }

    // -- recorder tap lifecycle --

    /** A HamRecorder whose running flag is forced on, without real audio. */
    private fun runningRecorder(): HamRecorder {
        val recorder = HamRecorder(null)
        HamRecorder::class.java.getDeclaredField("isRunning").apply {
            isAccessible = true
        }.setBoolean(recorder, true)
        return recorder
    }

    @Test
    fun attachIsIdempotentAndStopDetachesTheTap() {
        val recorder = runningRecorder()
        val listener = newListener()
        listener.startDirect()

        listener.attachToRecorder(recorder)
        assertThat(recorder.voiceDataMonitors).hasSize(1)

        // Re-attaching replaces the tap instead of stacking a second one
        // (two taps would double-feed audio into the decode queue).
        listener.attachToRecorder(recorder)
        assertThat(recorder.voiceDataMonitors).hasSize(1)

        // Stop unregisters, so a stopped listener costs the recorder nothing.
        listener.stop()
        assertThat(recorder.voiceDataMonitors).isEmpty()
    }

    @Test
    fun attachWithoutRunningRecorderRegistersNothing() {
        val recorder = HamRecorder(null) // isRunning stays false
        val listener = newListener()
        listener.startDirect()

        listener.attachToRecorder(recorder)

        assertThat(recorder.voiceDataMonitors).isEmpty()
        assertThat(logs.any { it.contains("NOT attached") }).isTrue()
        listener.stop()
    }
}
