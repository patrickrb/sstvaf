package radio.ks3ckc.sstvaf.sstv

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Exercises [SstvTransmitter] with a fake codec/keyer/player and a
 * synchronous worker runner, so the whole keyDown → play → keyUp sequence
 * runs deterministically on the test thread. Robolectric only for LiveData.
 */
@RunWith(RobolectricTestRunner::class)
class SstvTransmitterTest {

    private val codec = FakeSstvCodec()
    private val events = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    private val keyer = object : SstvTransmitter.Keyer {
        override fun keyDown() {
            events += "keyDown"
        }

        override fun keyUp() {
            events += "keyUp"
        }
    }

    private class FakePlayer(
        private val events: MutableList<String>,
        var playResult: Boolean = true,
        var onPlay: (() -> Unit)? = null,
    ) : SstvTransmitter.Player {
        var cancelCalls = 0

        override fun play(buffer: FloatArray, sampleRate: Int): Boolean {
            events += "play(samples=${buffer.size}, rate=$sampleRate)"
            onPlay?.invoke()
            return playResult
        }

        override fun cancel() {
            cancelCalls++
            events += "cancel"
        }
    }

    private fun newTransmitter(
        player: FakePlayer,
        tuneActive: Boolean = false,
        cwId: CwIdSettings = CwIdSettings(enabled = false, text = "", wpm = 20),
        // Defaults model an explicitly keyed rig (CAT/RTS/DTR), where the VOX
        // pre-tone never applies; VOX-path tests override both.
        controlsPtt: Boolean = true,
        voxPreToneMs: Int = 0,
        settleDelayMsSource: () -> Long = { 0L }, // no PTT settle sleep in tests
    ) = SstvTransmitter(
        codec,
        keyer,
        player,
        { tuneActive },
        { 12000 },
        settleDelayMsSource,
        { logs += it },
        { 42L },
        { body -> body.run() }, // synchronous worker
        { cwId },
        { controlsPtt },
        { voxPreToneMs },
    )

    private val pixels = IntArray(SstvMode.ROBOT_36.width * SstvMode.ROBOT_36.height)

    private fun transmitRobot36(tx: SstvTransmitter): Boolean =
        tx.transmit(pixels, SstvMode.ROBOT_36.width, SstvMode.ROBOT_36.height, SstvMode.ROBOT_36)

    @Test
    fun ordersKeyDownPlayKeyUp() {
        codec.encodeSampleCount = 24000
        val player = FakePlayer(events)
        val tx = newTransmitter(player)

        assertThat(transmitRobot36(tx)).isTrue()

        assertThat(events)
            .containsExactly("keyDown", "play(samples=24000, rate=12000)", "keyUp")
            .inOrder()
        assertThat(codec.encodeCalls).containsExactly(
            FakeSstvCodec.EncodeCall(320, 240, SstvMode.ROBOT_36, 12000),
        )
        assertThat(tx.isTransmittingNow()).isFalse()
        assertThat(logs.any { it.contains("SSTV TX: start") }).isTrue()
        assertThat(logs.any { it.contains("SSTV TX: end") && it.contains("completed=true") })
            .isTrue()
    }

    private fun playSampleCounts(): List<Int> =
        events.filter { it.startsWith("play(") }
            .map { Regex("samples=(\\d+)").find(it)!!.groupValues[1].toInt() }

    @Test
    fun cwIdTailPlaysAsSeparateBufferAfterImage() {
        codec.encodeSampleCount = 24000
        val player = FakePlayer(events)
        val tx = newTransmitter(
            player,
            cwId = CwIdSettings(enabled = true, text = "K1ABC", wpm = 20),
        )

        assertThat(transmitRobot36(tx)).isTrue()

        // Two plays: the untouched image, then a separate non-empty CW-ID buffer.
        val plays = playSampleCounts()
        assertThat(plays).hasSize(2)
        assertThat(plays[0]).isEqualTo(24000)
        assertThat(plays[1]).isGreaterThan(0)
        assertThat(events.first()).isEqualTo("keyDown")
        assertThat(events.last()).isEqualTo("keyUp")
        assertThat(logs.any { it.contains("cwId=K1ABC") && it.contains("wpm=20") }).isTrue()
        assertThat(logs.any { it == "SSTV TX: sending CW ID" }).isTrue()
    }

    @Test
    fun cwIdDisabledPlaysImageOnly() {
        codec.encodeSampleCount = 24000
        val player = FakePlayer(events)
        val tx = newTransmitter(player) // CW ID off by default

        transmitRobot36(tx)

        assertThat(playSampleCounts()).containsExactly(24000)
        assertThat(logs.none { it.contains("cwId=") }).isTrue()
        assertThat(logs.none { it.contains("sending CW ID") }).isTrue()
    }

    @Test
    fun userCancelStillSendsCwId() {
        lateinit var tx: SstvTransmitter
        var firstPlay = true
        val player = FakePlayer(events, playResult = false)
        // Operator hits Stop while the image is playing; the ID must still go out.
        player.onPlay = { if (firstPlay) { firstPlay = false; tx.cancel() } }
        tx = newTransmitter(player, cwId = CwIdSettings(enabled = true, text = "K1ABC", wpm = 20))

        transmitRobot36(tx)

        // Image play, then a second play for the CW ID, then key-up.
        assertThat(playSampleCounts()).hasSize(2)
        assertThat(events.last()).isEqualTo("keyUp")
        assertThat(logs.any { it == "SSTV TX: sending CW ID after cancel" }).isTrue()
    }

    @Test
    fun cancelDuringCwIdTailDoesNotReportFullProgress() {
        lateinit var tx: SstvTransmitter
        var playCount = 0
        val player = FakePlayer(events, playResult = true)
        // The image plays to completion, but the operator hits Stop while the
        // CW ID is keying, so the tail play returns false. Completion (and thus
        // final progress) must reflect the tail, not just the image.
        player.onPlay = {
            playCount++
            if (playCount == 2) {
                player.playResult = false
                tx.cancel()
            }
        }
        tx = newTransmitter(player, cwId = CwIdSettings(enabled = true, text = "K1ABC", wpm = 20))

        transmitRobot36(tx)

        assertThat(playSampleCounts()).hasSize(2)
        assertThat(events.last()).isEqualTo("keyUp")
        shadowOf(Looper.getMainLooper()).idle()
        // Image finished but the ID was cut short — not a fully completed TX.
        assertThat(tx.txProgress.value).isEqualTo(0f)
        assertThat(logs.any { it.contains("SSTV TX: end") && it.contains("completed=false") })
            .isTrue()
    }

    @Test
    fun safetyHaltSuppressesCwId() {
        lateinit var tx: SstvTransmitter
        var firstPlay = true
        val player = FakePlayer(events, playResult = false)
        // An SWR/ALC halt must drop RF now — no follow-up CW keying.
        player.onPlay = { if (firstPlay) { firstPlay = false; tx.cancel(sendCwId = false) } }
        tx = newTransmitter(player, cwId = CwIdSettings(enabled = true, text = "K1ABC", wpm = 20))

        transmitRobot36(tx)

        // Only the image played; the ID was suppressed.
        assertThat(playSampleCounts()).hasSize(1)
        assertThat(events.last()).isEqualTo("keyUp")
        assertThat(logs.none { it.contains("sending CW ID") }).isTrue()
    }

    @Test
    fun progressReachesOneOnCompletion() {
        val player = FakePlayer(events)
        val tx = newTransmitter(player)
        transmitRobot36(tx)

        shadowOf(Looper.getMainLooper()).idle()
        assertThat(tx.txProgress.value).isEqualTo(1f)
        assertThat(tx.isTransmitting.value).isFalse()
    }

    @Test
    fun cancelMidPlayStillUnkeys() {
        lateinit var tx: SstvTransmitter
        val player = FakePlayer(events, playResult = false)
        player.onPlay = { tx.cancel() } // operator hits stop while audio is playing
        tx = newTransmitter(player)

        transmitRobot36(tx)

        assertThat(player.cancelCalls).isEqualTo(1)
        assertThat(events.first()).isEqualTo("keyDown")
        assertThat(events.last()).isEqualTo("keyUp")
        assertThat(tx.isTransmittingNow()).isFalse()
        shadowOf(Looper.getMainLooper()).idle()
        // A cancelled TX never reports full progress.
        assertThat(tx.txProgress.value).isEqualTo(0f)
    }

    @Test
    fun rejectsConcurrentTransmit() {
        lateinit var tx: SstvTransmitter
        var nestedResult: Boolean? = null
        val player = FakePlayer(events)
        player.onPlay = { nestedResult = transmitRobot36(tx) } // TX while TX active
        tx = newTransmitter(player)

        assertThat(transmitRobot36(tx)).isTrue()

        assertThat(nestedResult).isFalse()
        // Exactly one keying cycle happened.
        assertThat(events.count { it == "keyDown" }).isEqualTo(1)
        assertThat(events.count { it == "keyUp" }).isEqualTo(1)
        assertThat(logs.any { it.contains("already transmitting") }).isTrue()
    }

    @Test
    fun rejectsTransmitWhileTuneActive() {
        val player = FakePlayer(events)
        val tx = newTransmitter(player, tuneActive = true)

        assertThat(transmitRobot36(tx)).isFalse()

        assertThat(events).isEmpty()
        assertThat(codec.encodeCalls).isEmpty()
        assertThat(logs.any { it.contains("tune carrier active") }).isTrue()
    }

    @Test
    fun encodeFailureNeverKeysTheRig() {
        codec.encodeFailure = IllegalStateException("bad image")
        val player = FakePlayer(events)
        val tx = newTransmitter(player)

        transmitRobot36(tx)

        assertThat(events).isEmpty() // no keyDown, no play, no keyUp needed
        assertThat(tx.isTransmittingNow()).isFalse()
        assertThat(logs.any { it.contains("SSTV TX: failed") }).isTrue()
    }

    @Test
    fun playFailureStillUnkeysAndClearsState() {
        val player = FakePlayer(events, playResult = false)
        val tx = newTransmitter(player)

        transmitRobot36(tx)

        assertThat(events.last()).isEqualTo("keyUp")
        assertThat(tx.isTransmittingNow()).isFalse()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(tx.txProgress.value).isEqualTo(0f)
    }

    @Test
    fun cancelDuringEncodeNeverKeysTheRig() {
        lateinit var tx: SstvTransmitter
        val player = FakePlayer(events)
        // The sink resets its own cancelled flag on play() entry, so a cancel
        // landing while the waveform is still being generated must be caught
        // by the transmitter's flag before keying.
        val cancellingCodec = object : SstvCodec {
            override fun encode(
                pixels: IntArray,
                width: Int,
                height: Int,
                mode: SstvMode,
                sampleRate: Int,
            ): FloatArray {
                tx.cancel()
                return codec.encode(pixels, width, height, mode, sampleRate)
            }

            override fun newDecoderSession(sampleRate: Int) = codec.newDecoderSession(sampleRate)
        }
        tx = SstvTransmitter(
            cancellingCodec,
            keyer,
            player,
            { false },
            { 12000 },
            { 0L },
            { logs += it },
            { 42L },
            { body -> body.run() },
            keyerControlsPttSource = { true },
        )

        transmitRobot36(tx)

        // The player's cancel bookkeeping fires, but the rig is never keyed
        // and nothing plays.
        assertThat(events.filter { it != "cancel" }).isEmpty()
        assertThat(logs.any { it.contains("cancelled before keying") }).isTrue()
        assertThat(tx.isTransmittingNow()).isFalse()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(tx.txProgress.value).isEqualTo(0f)
    }

    @Test
    fun cancelDuringPttSettleSkipsPlayButUnkeys() {
        lateinit var tx: SstvTransmitter
        val player = FakePlayer(events)
        // controlsPtt=true: the settle source is only consulted when the keyer
        // actually commands PTT (VOX skips the settle entirely).
        tx = newTransmitter(
            player,
            controlsPtt = true,
            settleDelayMsSource = { tx.cancel(); 0L }, // cancel lands while PTT is settling
        )

        transmitRobot36(tx)

        assertThat(events).containsExactly("keyDown", "cancel", "keyUp").inOrder()
        assertThat(logs.any { it.contains("cancelled during PTT settle") }).isTrue()
        assertThat(tx.isTransmittingNow()).isFalse()
    }

    // ------------------------------------------------------------------
    // VOX pre-tone: with nothing keying the rig (VOX / auto-PTT cable),
    // sacrificial 1900 Hz leader is prepended and the PTT settle is skipped.

    @Test
    fun voxPrependsPreToneWhenNothingKeysPtt() {
        codec.encodeSampleCount = 24000
        val player = FakePlayer(events)
        val tx = newTransmitter(player, controlsPtt = false, voxPreToneMs = 300)

        assertThat(transmitRobot36(tx)).isTrue()

        // 300 ms at 12 kHz = 3600 extra samples ahead of the image, in the
        // SAME buffer — a separate play could open a gap that drops the key.
        assertThat(playSampleCounts()).containsExactly(24000 + 3600)
        assertThat(logs.any { it.contains("voxPreToneMs=300") }).isTrue()
    }

    @Test
    fun rigKeyedTransmissionNeverGetsPreTone() {
        codec.encodeSampleCount = 24000
        val player = FakePlayer(events)
        // CAT/RTS/DTR: PTT is commanded before audio, so no pre-tone even
        // with a configured length.
        val tx = newTransmitter(player, controlsPtt = true, voxPreToneMs = 300)

        assertThat(transmitRobot36(tx)).isTrue()

        assertThat(playSampleCounts()).containsExactly(24000)
        assertThat(logs.none { it.contains("voxPreToneMs") }).isTrue()
    }

    @Test
    fun voxPreToneZeroDisablesThePreTone() {
        codec.encodeSampleCount = 24000
        val player = FakePlayer(events)
        val tx = newTransmitter(player, controlsPtt = false, voxPreToneMs = 0)

        assertThat(transmitRobot36(tx)).isTrue()

        assertThat(playSampleCounts()).containsExactly(24000)
        assertThat(logs.none { it.contains("voxPreToneMs") }).isTrue()
    }

    @Test
    fun voxSkipsThePttSettleDelay() {
        codec.encodeSampleCount = 24000
        val player = FakePlayer(events)
        var settleReads = 0
        // Nothing was keyed, so sleeping the settle delay would only push the
        // audio (and the pre-tone) later — the source must not be consulted.
        val tx = newTransmitter(
            player,
            controlsPtt = false,
            voxPreToneMs = 300,
            settleDelayMsSource = { settleReads++; 100L },
        )

        assertThat(transmitRobot36(tx)).isTrue()

        assertThat(settleReads).isEqualTo(0)
        assertThat(events.first()).isEqualTo("keyDown")
        assertThat(events.last()).isEqualTo("keyUp")
    }

    @Test
    fun rigKeyedTransmissionStillReadsSettleDelay() {
        codec.encodeSampleCount = 24000
        val player = FakePlayer(events)
        var settleReads = 0
        val tx = newTransmitter(
            player,
            controlsPtt = true,
            settleDelayMsSource = { settleReads++; 0L },
        )

        assertThat(transmitRobot36(tx)).isTrue()

        assertThat(settleReads).isEqualTo(1)
    }

    @Test
    fun voxPreToneAndCwIdComposeAroundTheImage() {
        codec.encodeSampleCount = 24000
        val player = FakePlayer(events)
        val tx = newTransmitter(
            player,
            cwId = CwIdSettings(enabled = true, text = "K1ABC", wpm = 20),
            controlsPtt = false,
            voxPreToneMs = 300,
        )

        assertThat(transmitRobot36(tx)).isTrue()

        // Pre-tone fused ahead of the image; CW ID still its own tail buffer.
        val plays = playSampleCounts()
        assertThat(plays).hasSize(2)
        assertThat(plays[0]).isEqualTo(24000 + 3600)
        assertThat(plays[1]).isGreaterThan(0)
    }

    @Test
    fun cancelWithoutActiveTransmissionIsANoOp() {
        val player = FakePlayer(events)
        val tx = newTransmitter(player)
        tx.cancel()
        assertThat(player.cancelCalls).isEqualTo(0)
    }

    @Test
    fun progressFractionIsClampedAndMonotonicInputs() {
        assertThat(SstvTransmitter.txProgressFraction(0, 1000)).isEqualTo(0f)
        assertThat(SstvTransmitter.txProgressFraction(500, 1000)).isEqualTo(0.5f)
        assertThat(SstvTransmitter.txProgressFraction(999, 1000)).isWithin(1e-4f).of(0.99f)
        // The ticker never reports full completion — only a finished play does.
        assertThat(SstvTransmitter.txProgressFraction(2000, 1000))
            .isEqualTo(SstvTransmitter.MAX_TICKER_PROGRESS)
        assertThat(SstvTransmitter.txProgressFraction(100, 0)).isEqualTo(0f)
        assertThat(SstvTransmitter.txProgressFraction(-5, 1000)).isEqualTo(0f)
    }
}
