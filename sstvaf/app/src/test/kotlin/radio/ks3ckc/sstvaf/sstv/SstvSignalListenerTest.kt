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
    fun logsSyncLockWhenTheDecoderLockedWithoutAVis() {
        val listener = newListener()
        listener.startDirect()

        codec.script += FakeSstvCodec.ScriptedState(
            DecodeStatus.IMAGE, SstvMode.SCOTTIE_2, rowsReady = 4, quality = 0.9f,
            visLocked = false,
        )
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(
            SstvRxState.Decoding(SstvMode.SCOTTIE_2, 4, 256, 0.9f, 0f),
        )
        val lockLine = logs.single { it.contains("lock") }
        assertThat(lockLine).contains("sync lock (no VIS heard)")
        assertThat(lockLine).contains("Scottie 2")
        assertThat(lockLine).doesNotContain("VIS lock")

        // Logged once per image, not once per poll.
        codec.script += FakeSstvCodec.ScriptedState(
            DecodeStatus.IMAGE, SstvMode.SCOTTIE_2, rowsReady = 40, quality = 0.9f,
            visLocked = false,
        )
        listener.feedAndStep()
        assertThat(logs.count { it.contains("lock") }).isEqualTo(1)
    }

    @Test
    fun abortedThenImmediateImageAfterResetPublishesDecoding() {
        // VIS preemption: the native decoder ends the stale image ABORTED and
        // is back in IMAGE for the new transmission right after reset(). The
        // engine must publish the new Decoding state without waiting for an
        // Idle in between.
        val listener = newListener()
        listener.startDirect()
        codec.script += FakeSstvCodec.ScriptedState(
            DecodeStatus.ABORTED, SstvMode.ROBOT_36, rowsReady = 66, quality = 0.4f,
        )
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(SstvRxState.Aborted(66, SstvMode.ROBOT_36))
        assertThat(codec.resetCount).isEqualTo(1)

        codec.script += FakeSstvCodec.ScriptedState(
            DecodeStatus.IMAGE, SstvMode.MARTIN_2, rowsReady = 0, quality = 0f,
        )
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(
            SstvRxState.Decoding(SstvMode.MARTIN_2, 0, 256, 0f, 0f),
        )
        assertThat(logs.count { it.contains("VIS lock") && it.contains("Martin 2") }).isEqualTo(1)
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

    @Test
    fun digitalTransmissionOnTheTapPublishesADigitalResult() {
        val listener = newListener(queueCapacity = 16)
        listener.startDirect()

        val payload = ByteArray(500) { (it * 7).toByte() }
        val image = radio.ks3ckc.sstvaf.sstv.digital.DigitalSstvCodec.Image(
            radio.ks3ckc.sstvaf.sstv.digital.DigitalSstvCodec.Format.JPEG, 160, 120, payload,
        )
        val onAir = radio.ks3ckc.sstvaf.sstv.digital.upsampleForDeviceRate(
            radio.ks3ckc.sstvaf.sstv.digital.DigitalSstvCodec(
                radio.ks3ckc.sstvaf.sstv.digital.DigitalSstvMode.STANDARD,
            ).encode(image),
            12000,
        )
        val audio = FloatArray(6000) + onAir + FloatArray(18000) // lead + tail silence

        var pos = 0
        while (pos < audio.size) {
            val take = 2400.coerceAtMost(audio.size - pos)
            listener.onAudioBuffer(audio.copyOfRange(pos, pos + take), take)
            listener.stepOnce(0)
            pos += take
        }
        shadowOf(Looper.getMainLooper()).idle()

        val result = listener.digitalResult.value
        assertThat(result).isNotNull()
        assertThat(result!!.payload).isEqualTo(payload)
        assertThat(result.mode)
            .isEqualTo(radio.ks3ckc.sstvaf.sstv.digital.DigitalSstvMode.STANDARD)
        listener.stop()
    }

    @Test
    fun modeLockReachesTheLiveSessionImmediately() {
        val listener = newListener()
        listener.startDirect()

        listener.setModeLock(SstvMode.MARTIN_2)

        assertThat(codec.forcedMode).isEqualTo(SstvMode.MARTIN_2)
        assertThat(listener.modeLock()).isEqualTo(SstvMode.MARTIN_2)
        assertThat(logs.any { it.contains("mode lock Martin 2") }).isTrue()
        listener.stop()
    }

    @Test
    fun digitalFramesAlsoArriveOnTheDirectCallback() {
        // The direct callback is the durable per-frame handoff (postValue
        // coalesces); it must fire for the same frame the LiveData carries.
        val listener = newListener(queueCapacity = 16)
        val delivered = mutableListOf<radio.ks3ckc.sstvaf.sstv.digital.DigitalRxImage>()
        listener.onDigitalImage = { delivered += it }
        listener.startDirect()

        val payload = ByteArray(300) { (it * 3).toByte() }
        val image = radio.ks3ckc.sstvaf.sstv.digital.DigitalSstvCodec.Image(
            radio.ks3ckc.sstvaf.sstv.digital.DigitalSstvCodec.Format.JPEG, 80, 60, payload,
        )
        val onAir = radio.ks3ckc.sstvaf.sstv.digital.upsampleForDeviceRate(
            radio.ks3ckc.sstvaf.sstv.digital.DigitalSstvCodec(
                radio.ks3ckc.sstvaf.sstv.digital.DigitalSstvMode.FAST,
            ).encode(image),
            12000,
        )
        val audio = FloatArray(6000) + onAir + FloatArray(18000)
        var pos = 0
        while (pos < audio.size) {
            val take = 2400.coerceAtMost(audio.size - pos)
            listener.onAudioBuffer(audio.copyOfRange(pos, pos + take), take)
            listener.stepOnce(0)
            pos += take
        }

        assertThat(delivered).hasSize(1)
        assertThat(delivered.single().payload).isEqualTo(payload)
        listener.stop()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun modeLockSetBeforeStartIsAppliedToTheNewSession() {
        val listener = newListener()
        listener.setModeLock(SstvMode.SCOTTIE_1)
        assertThat(codec.setForcedModeCalls).isEqualTo(0) // no session yet

        listener.startDirect()

        assertThat(codec.forcedMode).isEqualTo(SstvMode.SCOTTIE_1)
        assertThat(codec.setForcedModeCalls).isEqualTo(1)
        listener.stop()
    }

    @Test
    fun clearingTheModeLockRestoresAutoOnTheSession() {
        val listener = newListener()
        listener.startDirect()
        listener.setModeLock(SstvMode.MARTIN_2)

        listener.setModeLock(null)

        assertThat(codec.forcedMode).isNull()
        assertThat(listener.modeLock()).isNull()
        assertThat(logs.any { it.contains("mode lock off (auto VIS)") }).isTrue()
        listener.stop()
    }

    @Test
    fun settingTheSameModeLockTwiceIsANoOp() {
        val listener = newListener()
        listener.startDirect()
        listener.setModeLock(SstvMode.MARTIN_2)
        val callsAfterFirst = codec.setForcedModeCalls

        listener.setModeLock(SstvMode.MARTIN_2)

        assertThat(codec.setForcedModeCalls).isEqualTo(callsAfterFirst)
        listener.stop()
    }

    // ----- header-hunt diagnostics ------------------------------------------

    private fun rejected(reason: VisRejectReason, code: Int, atMs: Long = 1000L, offsetHz: Int = 0) =
        DecodeEvent(DecodeEventType.VIS_REJECTED, reason.nativeValue, code, atMs, offsetHz)

    @Test
    fun unknownVisCodePublishesUnsupportedModeAndHoldsItWhileHunting() {
        val listener = newListener()
        listener.startDirect()

        // The decoder read a clean header for a mode this app has no decoder
        // for (Wraase SC2-180 is VIS 55) and went straight back to IDLE.
        codec.events += rejected(VisRejectReason.UNKNOWN_MODE, code = 55, offsetHz = -12)
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(SstvRxState.UnsupportedMode(55))
        val line = logs.single { it.contains("unsupported mode") }
        assertThat(line).contains("VIS code 55")
        assertThat(line).contains("offset -12 Hz")

        // Still IDLE on later polls: the verdict stays up, like Complete does.
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(SstvRxState.UnsupportedMode(55))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(listener.rxState.value).isEqualTo(SstvRxState.UnsupportedMode(55))
    }

    @Test
    fun unsupportedModeSurvivesLeaderCandidatesUntilALockReplacesIt() {
        val listener = newListener()
        listener.startDirect()

        // The rejection and a fresh leader candidate land in the SAME step:
        // the hunt resumed straight into the unsupported transmission's
        // picture, whose mid-gray content satisfies the leader gate.
        codec.events += rejected(VisRejectReason.UNKNOWN_MODE, code = 55)
        codec.script += FakeSstvCodec.ScriptedState(DecodeStatus.LEADER)
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(SstvRxState.UnsupportedMode(55))

        // Candidate times out (IDLE), another appears (LEADER), VIS cells are
        // being sampled (VIS): still the verdict.
        codec.script += FakeSstvCodec.ScriptedState(DecodeStatus.IDLE)
        listener.feedAndStep()
        codec.script += FakeSstvCodec.ScriptedState(DecodeStatus.LEADER)
        listener.feedAndStep()
        codec.script += FakeSstvCodec.ScriptedState(DecodeStatus.VIS)
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(SstvRxState.UnsupportedMode(55))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(listener.rxState.value).isEqualTo(SstvRxState.UnsupportedMode(55))

        // A confirmed lock replaces it.
        codec.script += FakeSstvCodec.ScriptedState(
            DecodeStatus.IMAGE, SstvMode.MARTIN_1, rowsReady = 2, quality = 1f,
        )
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(
            SstvRxState.Decoding(SstvMode.MARTIN_1, 2, 256, 1f, 0f),
        )

        // And once that image is over, leader candidates publish normally again.
        codec.script += FakeSstvCodec.ScriptedState(
            DecodeStatus.DONE, SstvMode.MARTIN_1, rowsReady = 256, quality = 1f,
        )
        listener.feedAndStep()
        codec.script += FakeSstvCodec.ScriptedState(DecodeStatus.LEADER)
        listener.feedAndStep()
        assertThat(listener.stateNow()).isEqualTo(SstvRxState.Leader)
    }

    @Test
    fun rejectedHeadersAreLoggedWithReasonAndCodeButChangeNoState() {
        val listener = newListener()
        listener.startDirect()

        codec.events += rejected(VisRejectReason.PARITY, code = 44, offsetHz = 30)
        codec.events += rejected(VisRejectReason.STOP_BIT, code = -1, offsetHz = 0)
        listener.feedAndStep()

        assertThat(listener.stateNow()).isEqualTo(SstvRxState.Idle)
        val lines = logs.filter { it.contains("header rejected") }
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).contains("parity")
        assertThat(lines[0]).contains("vis=44")
        assertThat(lines[0]).contains("offset +30 Hz")
        assertThat(lines[1]).contains("stop bit")
        assertThat(lines[1]).contains("vis=?")
    }

    @Test
    fun leaderTimeoutsCollapseToOneLinePerIntervalOfAudio() {
        val listener = newListener()
        listener.startDirect()
        fun timeout(atMs: Long, offsetHz: Int = 0) =
            DecodeEvent(DecodeEventType.LEADER_TIMEOUT, 0, -1, atMs, offsetHz)

        // Three timeouts inside one interval: one line, then silence.
        codec.events += timeout(2_000L, offsetHz = 15)
        codec.events += timeout(4_000L)
        codec.events += timeout(6_000L)
        listener.feedAndStep()
        var lines = logs.filter { it.contains("no VIS start bit") }
        assertThat(lines).hasSize(1)
        assertThat(lines[0]).contains("offset +15 Hz")
        assertThat(lines[0]).doesNotContain("(x")

        // Past the interval the next one is logged, with the suppressed count.
        codec.events += timeout(2_000L + SstvSignalListener.LEADER_NOTE_INTERVAL_MS)
        listener.feedAndStep()
        lines = logs.filter { it.contains("no VIS start bit") }
        assertThat(lines).hasSize(2)
        assertThat(lines[1]).contains("(x3)")
        assertThat(listener.stateNow()).isEqualTo(SstvRxState.Idle)
    }

    @Test
    fun lockLineCarriesTheHeaderTuningOffset() {
        val listener = newListener()
        listener.startDirect()

        codec.events += DecodeEvent(
            DecodeEventType.VIS_ACCEPTED, SstvMode.MARTIN_1.modeId, 44, 1_200L, -37,
        )
        codec.script += FakeSstvCodec.ScriptedState(
            DecodeStatus.IMAGE, SstvMode.MARTIN_1, rowsReady = 1, quality = 1f,
        )
        listener.feedAndStep()

        val lock = logs.single { it.contains("VIS lock") }
        assertThat(lock).contains("Martin 1")
        assertThat(lock).contains("offset -37 Hz")
        // Accepted headers produce no line of their own: the lock line is it.
        assertThat(logs.none { it.contains("header rejected") || it.contains("accepted") }).isTrue()
    }

    @Test
    fun inputLevelIsLoggedOncePerThirtySecondsOfAudio() {
        val listener = newListener(queueCapacity = 256)
        listener.startDirect()
        val chunk = FloatArray(2400) { 0.5f } // 200 ms at 12 kHz, -6 dBFS square

        // 29.8 s: nothing yet.
        repeat(149) { listener.onAudioBuffer(chunk, chunk.size) }
        listener.stepOnce(0)
        assertThat(logs.none { it.contains("input level") }).isTrue()

        // The 150th buffer completes the window.
        listener.onAudioBuffer(chunk, chunk.size)
        listener.stepOnce(0)
        val lines = logs.filter { it.contains("input level") }
        assertThat(lines).hasSize(1)
        assertThat(lines[0]).isEqualTo("SSTV RX: input level — peak -6.0 dBFS, rms -6.0 dBFS over 30 s")

        // A silent next window says so explicitly.
        repeat(150) { listener.onAudioBuffer(FloatArray(2400), 2400) }
        listener.stepOnce(0)
        assertThat(logs.last { it.contains("input level") })
            .isEqualTo("SSTV RX: input level — silent (all zero) over 30 s")
        listener.stop()
    }
}
