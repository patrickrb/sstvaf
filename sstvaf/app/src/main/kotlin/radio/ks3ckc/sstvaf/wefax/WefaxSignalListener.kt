package radio.ks3ckc.sstvaf.wefax

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.wave.HamRecorder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
 * hands the finished strip to [onImageFinished]. The stop finalization
 * (decode of the buffered tail + full-strip copy) is unbounded native work,
 * so it runs on a short-lived background thread — [stopReceiving] itself
 * returns immediately and [WefaxRxState.Stopped] is published when the
 * strip is done.
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

    /** Invoked (on the stop-finalizer thread) with each finished strip. */
    @Volatile
    var onImageFinished: ((FinishedImage) -> Unit)? = null

    private val queue = ArrayBlockingQueue<FloatArray>(queueCapacity)
    private val running = AtomicBoolean(false)

    /** True while a stop's background finalization is still in flight. */
    private val stopping = AtomicBoolean(false)

    /** Buffers dropped on queue overflow (rate-limits the overflow log). */
    private val dropped = AtomicLong(0)

    /** Test mode ([startDirect]): no decode thread, stop finalizes inline. */
    @Volatile
    private var threadless = false

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
        if (stopping.get()) {
            log("WEFAX RX: previous stop still finalizing; not started")
            return
        }
        if (!running.compareAndSet(false, true)) {
            log("WEFAX RX: already receiving")
            return
        }
        threadless = false
        dropped.set(0)
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
     * Stop receiving: detach the tap immediately, then finalize on a
     * background thread — drain what's queued, finish() the decode (flushing
     * the partial final line), hand the strip to [onImageFinished] when at
     * least [MIN_SAVE_ROWS] rows decoded, and publish
     * [WefaxRxState.Stopped]. Runs off the caller because the finalization
     * is unbounded native work (tail decode + full-strip copy) and this is
     * invoked from the Stop button on the main thread — doing it inline
     * risked an ANR and stalled the preview's [readNewRows] on
     * [sessionLock]. No-op when not receiving or when a previous stop is
     * still finalizing.
     */
    fun stopReceiving() {
        if (!stopping.compareAndSet(false, true)) return
        if (!running.compareAndSet(true, false)) {
            stopping.set(false)
            return
        }
        detachTap()
        if (threadless) {
            // Test mode: no decode thread exists, so finalize synchronously
            // on the caller — keeps JVM tests deterministic.
            finalizeStop()
        } else {
            Thread({ finalizeStop() }, "WefaxStop").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * The heavy tail of a stop. Ordering matters for thread safety: the
     * decode thread is joined (bounded) first, then the session is captured
     * and nulled *under* [sessionLock] together with the queued buffers —
     * only after that exclusive hand-off does the native work (tail push,
     * finish, full-strip read) run *outside* the lock, so nothing else can
     * reach the session and [readNewRows] never blocks on the finalization.
     */
    private fun finalizeStop() {
        try {
            decodeThread?.let { t ->
                t.interrupt()
                t.join(THREAD_JOIN_MS)
            }
            decodeThread = null

            var s: WefaxDecoderSession? = null
            var sessionLpm = 0
            var sessionIoc = 0
            val pending = ArrayList<FloatArray>()
            synchronized(sessionLock) {
                s = session
                session = null
                sessionLpm = lpm
                sessionIoc = ioc
                // Everything still queued belongs to this transmission.
                var buf = queue.poll()
                while (buf != null) {
                    pending += buf
                    buf = queue.poll()
                }
            }

            var finished: FinishedImage? = null
            var rows = 0
            var width = 0
            val sess = s
            if (sess != null) {
                for (buf in pending) {
                    sess.push(buf, buf.size)
                }
                sess.finish()
                rows = sess.rowsReady()
                width = sess.width()
                if (rows >= MIN_SAVE_ROWS && width > 0) {
                    val gray = ByteArray(rows * width)
                    val copied = sess.readRows(0, rows, gray)
                    if (copied > 0) {
                        finished = FinishedImage(gray, width, copied, sessionLpm, sessionIoc)
                    }
                }
                sess.close()
            }
            queue.clear()
            log("WEFAX RX: stopped — rows=$rows width=$width saved=${finished != null}")
            finished?.let { img -> onImageFinished?.invoke(img) }
            setState(WefaxRxState.Stopped(rows, width))
        } finally {
            stopping.set(false)
        }
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
            // Rate-limited (like SSTV RX): this runs on the shared capture
            // fan-out thread and fileLog() is a synchronous file append.
            val total = dropped.incrementAndGet()
            if (total == 1L || total % DROP_LOG_EVERY == 0L) {
                log("WEFAX RX: audio queue overflow, dropped=$total buffers (decode thread lagging)")
            }
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
        threadless = true
        dropped.set(0)
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

    internal fun isStopping(): Boolean = stopping.get()

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

        /** Rate limit for the overflow log line (matches SSTV RX). */
        internal const val DROP_LOG_EVERY = 50L
    }
}
