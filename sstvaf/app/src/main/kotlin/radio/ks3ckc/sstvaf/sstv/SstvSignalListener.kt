package radio.ks3ckc.sstvaf.sstv

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.wave.HamRecorder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The continuous SSTV receive engine.
 *
 * Audio path: [attachToRecorder] registers a persistent (looping) tap on the
 * [HamRecorder] fan-out — the same mechanism SpectrumListener uses for the
 * waterfall — which delivers 12 kHz mono float buffers on the capture thread.
 * [onAudioBuffer] copies each buffer (the recorder reuses its array) into a
 * bounded queue; ONE decode thread drains the queue into a native
 * [DecoderSession] and publishes [rxState]. On overflow the OLDEST buffer is
 * dropped (hunting cares about fresh audio) and the drop is logged.
 *
 * On DONE/ABORTED the finished (or partial) frame is snapshotted into
 * [LastDecodedImage] BEFORE the decoder is reset back to hunting, and the
 * terminal [SstvRxState.Complete]/[SstvRxState.Aborted] stays published until
 * the next transmission starts (the post-reset IDLE is deliberately not
 * republished over it).
 */
class SstvSignalListener @JvmOverloads constructor(
    private val codec: SstvCodec,
    private val sampleRate: Int = SAMPLE_RATE_HZ,
    queueCapacity: Int = QUEUE_CAPACITY,
    private val log: (String) -> Unit = { GeneralVariables.fileLog(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val dialFrequency: () -> Long = { GeneralVariables.band },
) {

    private val mutableRxState = MutableLiveData<SstvRxState>(SstvRxState.Idle)

    /** Live receive state for the (future, PR 6) RX UI. */
    val rxState: LiveData<SstvRxState> get() = mutableRxState

    @Volatile
    private var currentState: SstvRxState = SstvRxState.Idle

    private val queue = ArrayBlockingQueue<FloatArray>(queueCapacity)
    private val enabled = AtomicBoolean(true)
    private val running = AtomicBoolean(false)
    private val dropped = AtomicLong(0)

    /** Serializes every DecoderSession call (decode thread vs [readNewRows]). */
    private val sessionLock = Any()
    private var session: DecoderSession? = null

    private var decodeThread: Thread? = null

    /** The registered recorder tap; kept so [detachFromRecorder] can remove it. */
    private var tapRecorder: HamRecorder? = null
    private var tapMonitor: HamRecorder.VoiceDataMonitor? = null

    /** Set after a DONE/ABORTED so the post-reset IDLE doesn't clobber it. */
    private var holdTerminalState = false
    private var visLockLogged = false

    /** Start the decode thread; safe to call once at app start. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        decodeThread = Thread({ decodeLoop() }, "SstvDecode").apply {
            isDaemon = true
            start()
        }
    }

    /** Stop the decode thread; the thread closes the session on its way out. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        detachFromRecorder()
        decodeThread?.interrupt()
        decodeThread = null
        queue.clear()
        // Direct-driven (test) mode has no thread to do the teardown.
        synchronized(sessionLock) {
            if (decodeThreadless) {
                session?.close()
                session = null
                decodeThreadless = false
            }
        }
    }

    /**
     * Register the persistent audio tap: a looping voice-data monitor that
     * forwards every [MONITOR_BUFFER_MS] chunk into the decode queue.
     * Idempotent — re-attaching first removes the previous tap, so two
     * monitors can never double-feed the queue.
     */
    fun attachToRecorder(recorder: HamRecorder) {
        if (!recorder.isRunning) {
            log("SSTV RX: recorder not running; audio tap NOT attached")
            return
        }
        detachFromRecorder()
        val monitor = recorder.getVoiceData(MONITOR_BUFFER_MS, false) { data ->
            onAudioBuffer(data, data.size)
        }
        if (monitor == null) {
            // Recorder stopped between the check above and registration.
            log("SSTV RX: recorder stopped during attach; audio tap NOT attached")
            return
        }
        tapRecorder = recorder
        tapMonitor = monitor
    }

    /** Remove the audio tap from the recorder fan-out; no-op when detached. */
    fun detachFromRecorder() {
        val monitor = tapMonitor ?: return
        tapRecorder?.deleteVoiceDataMonitor(monitor)
        tapMonitor = null
        tapRecorder = null
    }

    /**
     * Feed one capture buffer (called on the recorder thread). Copies the
     * data (the recorder reuses its buffer) and enqueues it; on overflow the
     * oldest queued buffer is dropped and the drop is (rate-limited) logged.
     */
    fun onAudioBuffer(data: FloatArray, length: Int) {
        if (!enabled.get() || !running.get()) return
        val len = length.coerceAtMost(data.size)
        if (len <= 0) return
        val copy = data.copyOf(len)
        if (!queue.offer(copy)) {
            queue.poll() // drop-oldest: fresh audio wins while hunting
            val total = dropped.incrementAndGet()
            if (total == 1L || total % DROP_LOG_EVERY == 0L) {
                log("SSTV RX: audio queue overflow, dropped=$total buffers (decode thread lagging)")
            }
            queue.offer(copy)
        }
    }

    /** Enable/disable RX processing (default ON). Disabling clears the queue. */
    fun setEnabled(on: Boolean) {
        if (enabled.getAndSet(on) != on) {
            log("SSTV RX: listener ${if (on) "enabled" else "disabled"}")
        }
        if (!on) queue.clear()
    }

    fun isEnabled(): Boolean = enabled.get()

    /**
     * Read decoded rows straight from the live session (for the future RX
     * screen's incremental image paint). Returns rows copied, 0 when no
     * session/mode is active.
     */
    fun readNewRows(firstRow: Int, nRows: Int, out: IntArray): Int =
        synchronized(sessionLock) { session?.readRows(firstRow, nRows, out) ?: 0 }

    // ------------------------------------------------------------------
    // Decode thread
    // ------------------------------------------------------------------

    private fun decodeLoop() {
        val s = try {
            codec.newDecoderSession(sampleRate)
        } catch (t: Throwable) {
            // No native lib (JVM tests / unsupported ABI): shut down cleanly
            // rather than crash-looping — the app runs fine without RX.
            log(
                "SSTV RX: decoder unavailable" +
                    " (${t.javaClass.simpleName}: ${t.message}); listener stopped",
            )
            running.set(false)
            return
        }
        synchronized(sessionLock) { session = s }
        log("SSTV RX: hunting (rate=$sampleRate Hz)")
        try {
            while (running.get()) {
                try {
                    stepOnce(POLL_WAIT_MS)
                } catch (ie: InterruptedException) {
                    // stop() interrupts; the loop condition decides.
                } catch (t: Throwable) {
                    log("SSTV RX: decode step failed: $t")
                }
            }
        } finally {
            synchronized(sessionLock) {
                session = null
            }
            s.close()
        }
    }

    /**
     * One decode-loop iteration: wait up to [waitMs] for audio, drain
     * everything queued into the session, then poll and publish status.
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
            publishFromSession(s)
        }
    }

    private fun publishFromSession(s: DecoderSession) {
        when (s.status()) {
            DecodeStatus.IDLE ->
                if (!holdTerminalState) setState(SstvRxState.Idle)

            DecodeStatus.LEADER, DecodeStatus.VIS -> {
                holdTerminalState = false
                setState(SstvRxState.Leader)
            }

            DecodeStatus.IMAGE -> {
                holdTerminalState = false
                val mode = s.mode() ?: return
                if (!visLockLogged) {
                    visLockLogged = true
                    log(
                        "SSTV RX: VIS lock — mode=${mode.displayName}" +
                            " (vis=${mode.visCode}, ${mode.width}x${mode.height})",
                    )
                }
                setState(
                    SstvRxState.Decoding(
                        mode, s.rowsReady(), mode.totalRows, s.quality(), s.slantPpm(),
                    ),
                )
            }

            DecodeStatus.DONE -> finishDecode(s, complete = true)
            DecodeStatus.ABORTED -> finishDecode(s, complete = false)
        }
    }

    /**
     * Terminal handling: snapshot the frame into [LastDecodedImage], publish
     * Complete/Aborted, THEN reset the decoder back to hunting. Reset last —
     * every read (rows, quality, slant, mode) must happen before it.
     */
    private fun finishDecode(s: DecoderSession, complete: Boolean) {
        val mode = s.mode()
        val rows = s.rowsReady()
        val quality = s.quality()
        val slant = s.slantPpm()

        var frameCaptured = false
        if (mode != null && rows > 0) {
            val pixels = IntArray(mode.width * mode.height)
            val copied = s.readRows(0, rows, pixels)
            if (copied > 0) {
                LastDecodedImage.frame = LastDecodedImage.Frame(
                    pixels = pixels,
                    width = mode.width,
                    height = mode.height,
                    mode = mode,
                    rowsDecoded = copied,
                    quality = quality,
                    slantPpm = slant,
                    utcMillis = clock(),
                    dialFrequencyHz = dialFrequency(),
                    complete = complete,
                )
                frameCaptured = true
            }
        }

        if (complete && mode != null) {
            log(
                "SSTV RX: image complete — mode=${mode.displayName}" +
                    " rows=$rows quality=$quality slant=${slant}ppm",
            )
            setState(SstvRxState.Complete(mode, quality, frameCaptured))
        } else {
            log("SSTV RX: decode aborted — mode=${mode?.displayName} partialRows=$rows")
            setState(SstvRxState.Aborted(rows, mode))
        }

        s.reset()
        holdTerminalState = true
        visLockLogged = false
    }

    private fun setState(next: SstvRxState) {
        if (next == currentState) return
        currentState = next
        mutableRxState.postValue(next)
    }

    // ------------------------------------------------------------------
    // Test hooks (JVM tests drive the loop synchronously — no decode thread)
    // ------------------------------------------------------------------

    private var decodeThreadless = false

    /** Test-only start: create the session on the caller thread, no thread. */
    internal fun startDirect() {
        check(running.compareAndSet(false, true)) { "already running" }
        decodeThreadless = true
        synchronized(sessionLock) { session = codec.newDecoderSession(sampleRate) }
    }

    internal fun stateNow(): SstvRxState = currentState

    internal fun droppedBufferCount(): Long = dropped.get()

    internal fun queuedBufferCount(): Int = queue.size

    companion object {
        /** The recorder chain is fixed 12 kHz mono float (HamRecorder). */
        const val SAMPLE_RATE_HZ = 12000

        /** Looping voice-monitor chunk size, ms (~2400 samples at 12 kHz). */
        internal const val MONITOR_BUFFER_MS = 200

        /** ~13 s of audio headroom before drops start. */
        internal const val QUEUE_CAPACITY = 64

        /** Decode-thread wait per iteration → status polled ~4-5x/sec. */
        internal const val POLL_WAIT_MS = 250L

        /** Rate limit for the overflow log line. */
        internal const val DROP_LOG_EVERY = 50L
    }
}
