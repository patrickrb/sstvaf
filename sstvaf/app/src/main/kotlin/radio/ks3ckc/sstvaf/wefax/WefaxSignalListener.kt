package radio.ks3ckc.sstvaf.wefax

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.wave.HamRecorder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Live WEFAX receive state for the fax screen. */
sealed class WefaxRxState {
    /** Not receiving (never started, or stopped). */
    object Idle : WefaxRxState()

    /** Receiving; no phasing lock yet. */
    data class Listening(val lpm: Int, val ioc: Int) : WefaxRxState()

    /** Phase locked; [rowsReady] picture rows decoded so far at [width] px. */
    data class Decoding(val lpm: Int, val ioc: Int, val width: Int, val rowsReady: Int) :
        WefaxRxState()

    /** Stopped by the operator; [rows] rows were decoded in the last run. */
    data class Stopped(val rows: Int, val width: Int) : WefaxRxState()
}

/**
 * The on-demand WEFAX receive engine — the radiofax sibling of
 * `SstvSignalListener`, sharing its audio-path design: a looping tap on the
 * [HamRecorder] fan-out delivers 12 kHz mono float buffers on the capture
 * thread into a bounded queue; one decode thread drains the queue into a
 * native [WefaxDecoderSession] and publishes [rxState].
 *
 * Unlike SSTV RX (always hunting), a fax decode is operator-driven: a
 * transmission is a continuous line stream with no length header, so
 * [startReceiving] opens a session with the operator's LPM/IOC and
 * [stopReceiving] finishes the decode (flushing the partial final line) and
 * hands the finished strip to [onImageFinished] before returning to idle.
 */
class WefaxSignalListener @JvmOverloads constructor(
    private val codec: WefaxCodec,
    private val sampleRate: Int = SAMPLE_RATE_HZ,
    queueCapacity: Int = QUEUE_CAPACITY,
    private val log: (String) -> Unit = { GeneralVariables.fileLog(it) },
) {

    /** One finished (operator-stopped) fax strip. */
    class FinishedImage(
        val gray: ByteArray,
        val width: Int,
        val rows: Int,
        val lpm: Int,
        val ioc: Int,
    )

    private val mutableRxState = MutableLiveData<WefaxRxState>(WefaxRxState.Idle)
    val rxState: LiveData<WefaxRxState> get() = mutableRxState

    @Volatile
    private var currentState: WefaxRxState = WefaxRxState.Idle

    /** Invoked (on the caller of [stopReceiving]) with each finished strip. */
    @Volatile
    var onImageFinished: ((FinishedImage) -> Unit)? = null

    private val queue = ArrayBlockingQueue<FloatArray>(queueCapacity)
    private val running = AtomicBoolean(false)

    /** Serializes every session call (decode thread vs stop vs readNewRows). */
    private val sessionLock = Any()
    private var session: WefaxDecoderSession? = null
    private var lpm = 0
    private var ioc = 0

    private var decodeThread: Thread? = null
    private var tapRecorder: HamRecorder? = null
    private var tapMonitor: HamRecorder.VoiceDataMonitor? = null

    fun isReceiving(): Boolean = running.get()

    /**
     * Open a decoder session for [preset] and start draining audio from
     * [recorder]. No-op (with a log line) when already receiving or the
     * recorder isn't running.
     */
    fun startReceiving(recorder: HamRecorder, preset: WefaxPreset) {
        if (!running.compareAndSet(false, true)) {
            log("WEFAX RX: already receiving")
            return
        }
        if (!recorder.isRunning) {
            log("WEFAX RX: recorder not running; not started")
            running.set(false)
            return
        }
        val s = try {
            codec.newDecoderSession(sampleRate, preset.lpm, preset.ioc)
        } catch (t: Throwable) {
            // No native lib (JVM tests / unsupported ABI): stay idle rather
            // than crash — the rest of the app runs fine without fax RX.
            log(
                "WEFAX RX: decoder unavailable" +
                    " (${t.javaClass.simpleName}: ${t.message}); not started",
            )
            running.set(false)
            return
        }
        synchronized(sessionLock) {
            session = s
            lpm = preset.lpm
            ioc = preset.ioc
        }
        queue.clear()
        val monitor = recorder.getVoiceData(MONITOR_BUFFER_MS, false) { data ->
            onAudioBuffer(data, data.size)
        }
        if (monitor == null) {
            log("WEFAX RX: recorder stopped during attach; not started")
            synchronized(sessionLock) {
                session = null
            }
            s.close()
            running.set(false)
            return
        }
        tapRecorder = recorder
        tapMonitor = monitor
        decodeThread = Thread({ decodeLoop() }, "WefaxDecode").apply {
            isDaemon = true
            start()
        }
        log("WEFAX RX: receiving (rate=$sampleRate Hz, lpm=${preset.lpm}, ioc=${preset.ioc})")
        setState(WefaxRxState.Listening(preset.lpm, preset.ioc))
    }

    /**
     * Stop receiving: detach the tap, drain what's queued, finish() the
     * decode (flushing the partial final line), hand the strip to
     * [onImageFinished] when at least [MIN_SAVE_ROWS] rows decoded, and
     * return to [WefaxRxState.Stopped].
     */
    fun stopReceiving() {
        if (!running.compareAndSet(true, false)) return
        detachTap()
        decodeThread?.interrupt()
        decodeThread?.join(THREAD_JOIN_MS)
        decodeThread = null

        var finished: FinishedImage? = null
        var rows = 0
        var width = 0
        synchronized(sessionLock) {
            val s = session ?: return@synchronized
            // Everything still queued belongs to this transmission.
            var buf = queue.poll()
            while (buf != null) {
                s.push(buf, buf.size)
                buf = queue.poll()
            }
            s.finish()
            rows = s.rowsReady()
            width = s.width()
            if (rows >= MIN_SAVE_ROWS && width > 0) {
                val gray = ByteArray(rows * width)
                val copied = s.readRows(0, rows, gray)
                if (copied > 0) {
                    finished = FinishedImage(gray, width, copied, lpm, ioc)
                }
            }
            session = null
            s.close()
        }
        queue.clear()
        log("WEFAX RX: stopped — rows=$rows width=$width saved=${finished != null}")
        finished?.let { img -> onImageFinished?.invoke(img) }
        setState(WefaxRxState.Stopped(rows, width))
    }

    /**
     * Read decoded rows straight from the live session (for the fax screen's
     * incremental strip paint). Returns rows copied, 0 when idle.
     */
    fun readNewRows(firstRow: Int, nRows: Int, out: ByteArray): Int =
        synchronized(sessionLock) { session?.readRows(firstRow, nRows, out) ?: 0 }

    private fun onAudioBuffer(data: FloatArray, length: Int) {
        if (!running.get()) return
        val len = length.coerceAtMost(data.size)
        if (len <= 0) return
        val copy = data.copyOf(len)
        if (!queue.offer(copy)) {
            // A fax line is context for the next one — drop the NEWEST buffer
            // so the locked line phase isn't shifted by a hole mid-strip.
            log("WEFAX RX: audio queue overflow, dropping newest buffer")
        }
    }

    private fun decodeLoop() {
        while (running.get()) {
            try {
                stepOnce(POLL_WAIT_MS)
            } catch (ie: InterruptedException) {
                // stopReceiving() interrupts; the loop condition decides.
            } catch (t: Throwable) {
                log("WEFAX RX: decode step failed: $t")
            }
        }
    }

    /**
     * One decode-loop iteration: wait up to [waitMs] for audio, drain
     * everything queued into the session, then publish status.
     * Package-internal so tests can drive the loop synchronously.
     */
    internal fun stepOnce(waitMs: Long) {
        val first =
            if (waitMs > 0) queue.poll(waitMs, TimeUnit.MILLISECONDS) else queue.poll()
        synchronized(sessionLock) {
            val s = session ?: return
            var buf = first
            while (buf != null) {
                s.push(buf, buf.size)
                buf = queue.poll()
            }
            when (s.status()) {
                WefaxDecodeStatus.IDLE, WefaxDecodeStatus.PHASING ->
                    setState(WefaxRxState.Listening(lpm, ioc))

                WefaxDecodeStatus.IMAGE ->
                    setState(WefaxRxState.Decoding(lpm, ioc, s.width(), s.rowsReady()))

                WefaxDecodeStatus.DONE -> Unit // stopReceiving() owns the finish
            }
        }
    }

    // ------------------------------------------------------------------
    // Test hooks (JVM tests drive the loop synchronously — no decode thread)
    // ------------------------------------------------------------------

    /** Test-only start: open the session on the caller thread, no thread/tap. */
    internal fun startDirect(preset: WefaxPreset) {
        check(running.compareAndSet(false, true)) { "already receiving" }
        synchronized(sessionLock) {
            session = codec.newDecoderSession(sampleRate, preset.lpm, preset.ioc)
            lpm = preset.lpm
            ioc = preset.ioc
        }
    }

    internal fun feed(data: FloatArray) {
        onAudioBuffer(data, data.size)
    }

    internal fun stateNow(): WefaxRxState = currentState

    internal fun queuedBufferCount(): Int = queue.size

    private fun detachTap() {
        val monitor = tapMonitor ?: return
        tapRecorder?.deleteVoiceDataMonitor(monitor)
        tapMonitor = null
        tapRecorder = null
    }

    private fun setState(next: WefaxRxState) {
        if (next == currentState) return
        currentState = next
        mutableRxState.postValue(next)
    }

    companion object {
        /** The recorder chain is fixed 12 kHz mono float (HamRecorder). */
        const val SAMPLE_RATE_HZ = 12000

        /** Looping voice-monitor chunk size, ms (matches SSTV RX). */
        internal const val MONITOR_BUFFER_MS = 200

        /** ~13 s of audio headroom before drops start. */
        internal const val QUEUE_CAPACITY = 64

        /** Decode-thread wait per iteration → status polled ~4-5x/sec. */
        internal const val POLL_WAIT_MS = 250L

        /** Below this many decoded rows a stop discards the strip as noise. */
        internal const val MIN_SAVE_ROWS = 16

        /** Bound on waiting for the decode thread during stop. */
        internal const val THREAD_JOIN_MS = 2000L
    }
}
