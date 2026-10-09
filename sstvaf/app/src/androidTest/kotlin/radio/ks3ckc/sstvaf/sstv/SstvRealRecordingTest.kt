package radio.ks3ckc.sstvaf.sstv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real recorded SSTV audio through the production RX engine on the device:
 * the fixtures in `cpp/sstvaf_glue/fixtures` (bundled as androidTest assets)
 * are fed to [SstvSignalListener] + [NativeSstvCodec] in the recorder tap's
 * 200 ms buffers, exactly as the microphone path does, and the published
 * [SstvRxState] / [LastDecodedImage] are checked.
 *
 * The host suite (`test_sstv_recordings.c`) runs the same clips against the
 * C decoder directly; this test is the proof that nothing between the audio
 * tap and the saved frame (queueing, JNI marshalling, the terminal-state
 * snapshot + reset) loses them on a real device.
 */
@RunWith(AndroidJUnit4::class)
class SstvRealRecordingTest {

    private val logs = mutableListOf<String>()

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
    }

    @After
    fun nothing() = Unit

    /** 16-bit PCM mono WAV (the fixture format) -> float samples in [-1, 1). */
    private fun loadFixture(name: String): FloatArray {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open(name).use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "$name: not a RIFF file" }
        var pos = 12
        var rate = 0
        var channels = 0
        var bits = 0
        var data: FloatArray? = null
        while (pos + 8 <= bytes.size && data == null) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    channels = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                }
                "data" -> {
                    check(bits == 16 && channels == 1) { "$name: expected 16-bit mono" }
                    val n = minOf(len, bytes.size - body) / 2
                    data = FloatArray(n) { i -> bb.getShort(body + 2 * i) / 32768f }
                }
            }
            pos = body + len + (len and 1)
        }
        check(rate == SstvSignalListener.SAMPLE_RATE_HZ) { "$name: ${rate} Hz, want 12000" }
        return checkNotNull(data) { "$name: no data chunk" }
    }

    /**
     * Stream [audio] in recorder-sized buffers, stepping the (threadless)
     * engine after each one, and return the first terminal state seen (or
     * the last state when the clip ends mid-image).
     */
    private fun stream(listener: SstvSignalListener, audio: FloatArray): SstvRxState {
        val chunk = SstvSignalListener.SAMPLE_RATE_HZ * SstvSignalListener.MONITOR_BUFFER_MS / 1000
        var offset = 0
        while (offset < audio.size) {
            val n = minOf(chunk, audio.size - offset)
            listener.onAudioBuffer(audio.copyOfRange(offset, offset + n), n)
            listener.stepOnce(0)
            offset += n
            val s = listener.stateNow()
            if (s is SstvRxState.Complete || s is SstvRxState.Aborted) return s
        }
        return listener.stateNow()
    }

    @Test
    fun glitchedOffAirRobot36DecodesCompletely() {
        // Sender dropped ~15 ms of audio shortly into the image; before sync
        // re-acquisition this came out sheared with inverted chroma.
        val audio = loadFixture("robot36_glitch_14230_yt.wav")
        val listener = newListener()
        listener.startDirect()
        try {
            val end = stream(listener, audio)
            assertThat(end).isInstanceOf(SstvRxState.Complete::class.java)
            end as SstvRxState.Complete
            assertThat(end.mode).isEqualTo(SstvMode.ROBOT_36)
            assertThat(end.quality).isGreaterThan(0.9f)
            assertThat(end.frameAvailable).isTrue()

            val frame = checkNotNull(LastDecodedImage.frame)
            assertThat(frame.mode).isEqualTo(SstvMode.ROBOT_36)
            assertThat(frame.rowsDecoded).isEqualTo(240)
            assertThat(frame.complete).isTrue()
            assertThat(Math.abs(frame.slantPpm)).isLessThan(500f)
            assertThat(logs.any { it.contains("VIS lock") && it.contains("Robot 36") }).isTrue()
            assertThat(logs.any { it.contains("image complete") }).isTrue()
            assertThat(listener.droppedBufferCount()).isEqualTo(0)
        } finally {
            listener.stop()
        }
    }

    @Test
    fun quietRecordingAtSevenPercentLevelDecodes() {
        val audio = loadFixture("robot36_quiet_yt.wav")
        val listener = newListener()
        listener.startDirect()
        try {
            val end = stream(listener, audio)
            assertThat(end).isInstanceOf(SstvRxState.Complete::class.java)
            assertThat((end as SstvRxState.Complete).mode).isEqualTo(SstvMode.ROBOT_36)
            assertThat(checkNotNull(LastDecodedImage.frame).rowsDecoded).isEqualTo(240)
        } finally {
            listener.stop()
        }
    }

    @Test
    fun clipJoinedMidImageLocksFromTheSyncTrain() {
        // No VIS anywhere in this clip: the engine must still show a decode.
        val audio = loadFixture("scottie2_midimage_20m_yt.wav")
        val listener = newListener()
        listener.startDirect()
        try {
            val end = stream(listener, audio)
            assertThat(end).isInstanceOf(SstvRxState.Decoding::class.java)
            end as SstvRxState.Decoding
            assertThat(end.mode).isEqualTo(SstvMode.SCOTTIE_2)
            assertThat(end.rowsReady).isAtLeast(80)
            assertThat(logs.any { it.contains("sync lock (no VIS heard)") && it.contains("Scottie 2") })
                .isTrue()

            // The RX screen's incremental paint path reads rows live.
            val rows = IntArray(SstvMode.SCOTTIE_2.width * end.rowsReady)
            assertThat(listener.readNewRows(0, end.rowsReady, rows)).isEqualTo(end.rowsReady)
            assertThat(rows[0] ushr 24).isEqualTo(0xFF)
        } finally {
            listener.stop()
        }
    }
}
