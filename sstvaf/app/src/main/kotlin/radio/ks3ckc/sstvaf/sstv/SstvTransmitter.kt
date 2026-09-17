package radio.ks3ckc.sstvaf.sstv

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.transmit.PttController
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSTV image transmitter: encode → key PTT → play → unkey, on a worker
 * thread, composing the PR-3 extracted transmit plumbing the same way
 * TuneOperator does (PttController keyDown/keyUp around a blocking
 * TransmitAudioSink play, un-key in a finally — a stuck carrier is never
 * acceptable).
 *
 * The controller/sink are injected behind the minimal [Keyer]/[Player]
 * interfaces so JVM tests can fake them; MainViewModel adapts the real
 * PttController + TransmitAudioSink.
 *
 * The sink has no playback-progress callback, so [txProgress] is computed
 * from elapsed time vs the waveform duration by a small ticker thread, and
 * snapped to 1.0 when playback completes.
 */
class SstvTransmitter @JvmOverloads constructor(
    private val codec: SstvCodec,
    private val keyer: Keyer,
    private val player: Player,
    private val tuneActive: TuneActiveCheck = TuneActiveCheck { false },
    private val sampleRateSource: () -> Int = { GeneralVariables.audioSampleRate },
    private val settleDelayMsSource: () -> Long = { GeneralVariables.pttDelay.toLong() },
    private val log: (String) -> Unit = { GeneralVariables.fileLog(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Runs the TX worker; tests substitute a synchronous runner. */
    private val workerRunner: (Runnable) -> Unit = { body ->
        Thread(body, "SstvTransmit").start()
    },
    /** Optional CW station-ID tail settings (issue #14). */
    private val cwIdSource: () -> CwIdSettings = {
        CwIdSettings(
            enabled = GeneralVariables.cwIdEnabled,
            text = GeneralVariables.myCallsign,
            wpm = GeneralVariables.cwIdWpm,
        )
    },
    /**
     * Whether the keyer actually commands PTT (CAT/RTS/DTR). When it does
     * not (VOX — the audio itself keys the rig, either via the radio's VOX
     * or an auto-PTT audio cable), the PTT settle sleep is pointless and a
     * [VoxPreTone] is prepended instead so the keying chain's attack time
     * eats sacrificial leader, not the calibration header.
     */
    private val keyerControlsPttSource: () -> Boolean = {
        PttController.controlsPtt(GeneralVariables.controlMode)
    },
    /** VOX pre-tone length in milliseconds (0 disables). */
    private val voxPreToneMsSource: () -> Int = { GeneralVariables.voxPreToneMs },
) {

    /** Rig keying surface (MainViewModel adapts PttController). */
    interface Keyer {
        fun keyDown()

        fun keyUp()
    }

    /** Audio playback surface (MainViewModel adapts TransmitAudioSink). */
    interface Player {
        /** Blocking whole-buffer play. True when it played to completion. */
        fun play(buffer: FloatArray, sampleRate: Int): Boolean

        /** Abort an in-progress play from any thread (unblocks [play]). */
        fun cancel()
    }

    /** Whether the Tune carrier currently owns the rig. */
    fun interface TuneActiveCheck {
        fun isActive(): Boolean
    }

    private val transmitting = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /**
     * When a cancel must also skip the CW station-ID tail — an SWR/ALC safety
     * halt has to stop keying the rig immediately, so it must not follow a
     * cancelled image with a fresh CW transmission. A user Stop leaves this
     * false so the operator still identifies.
     */
    private val suppressCwId = AtomicBoolean(false)

    private val mutableIsTransmitting = MutableLiveData(false)
    val isTransmitting: LiveData<Boolean> get() = mutableIsTransmitting

    private val mutableTxProgress = MutableLiveData(0f)
    val txProgress: LiveData<Float> get() = mutableTxProgress

    private val mutableLastResult = MutableLiveData<TxResult?>(null)

    /**
     * The outcome of the most recent transmission, retained until the next one
     * starts.
     *
     * [isTransmitting] going false is not enough for a UI to report on: it
     * fires from the teardown `finally` whether the image played to the end,
     * the operator hit Stop, or encoding threw. A screen watching only for the
     * false edge showed a green "Sent" after a failure, and missed the end of a
     * transmission entirely if the operator was on another tab when it
     * finished, because the screen was not composed to see the edge.
     *
     * This is durable instead: the value stays put, carries what actually
     * happened, and is stamped with a sequence number so a consumer can tell a
     * result it has already acted on from a new one.
     */
    val lastResult: LiveData<TxResult?> get() = mutableLastResult

    private val mutableImageWindow = MutableLiveData(TxImageWindow.WHOLE)

    /**
     * Where the image sits inside the whole transmission, as fractions of the
     * total duration.
     *
     * [txProgress] covers everything that goes on the air, which includes the
     * VOX pre-tone leader and the CW station-ID tail. A scan-line indicator
     * driven straight off it starts moving before any image pixels are sent and
     * only reaches the bottom during the CW tail. This window is what maps one
     * onto the other.
     */
    val imageWindow: LiveData<TxImageWindow> get() = mutableImageWindow

    private val resultSequence = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Start transmitting [pixels] ([width] x [height], 0xAARRGGBB) in [mode].
     * Returns false without keying when a transmission is already running or
     * the Tune carrier is active.
     */
    fun transmit(pixels: IntArray, width: Int, height: Int, mode: SstvMode): Boolean {
        if (tuneActive.isActive()) {
            log("SSTV TX: rejected — tune carrier active")
            return false
        }
        if (!transmitting.compareAndSet(false, true)) {
            log("SSTV TX: rejected — already transmitting")
            return false
        }
        cancelled.set(false)
        suppressCwId.set(false)
        mutableIsTransmitting.postValue(true)
        mutableTxProgress.postValue(0f)
        // Clear the previous outcome so a new transmission cannot be reported
        // with the last one's result while it is still in flight.
        mutableLastResult.postValue(null)
        mutableImageWindow.postValue(TxImageWindow.WHOLE)
        workerRunner(Runnable { runTransmission(pixels, width, height, mode) })
        return true
    }

    /**
     * Abort an in-flight transmission; the worker's finally un-keys PTT. When
     * [sendCwId] is true (the default — a user Stop), the CW station-ID tail
     * still goes out so the operator identifies; a safety halt passes false to
     * drop RF immediately without any further keying.
     */
    @JvmOverloads
    fun cancel(sendCwId: Boolean = true) {
        if (!transmitting.get()) return
        if (!sendCwId) suppressCwId.set(true)
        cancelled.set(true)
        player.cancel()
    }

    fun isTransmittingNow(): Boolean = transmitting.get()

    // ------------------------------------------------------------------

    private fun runTransmission(pixels: IntArray, width: Int, height: Int, mode: SstvMode) {
        var keyed = false
        var completed = false
        val startedAt = clock()
        try {
            val sampleRate = sampleRateSource()
            // Encode BEFORE keying: a bad image/mode must never key the rig.
            val encoded = codec.encode(pixels, width, height, mode, sampleRate)
            // Read once so a mid-TX settings change can't split behavior
            // between the pre-tone and the settle sleep below.
            val controlsPtt = keyerControlsPttSource()
            // With no explicit PTT (VOX / auto-PTT cable) the audio itself
            // keys the rig, so prepend sacrificial 1900 Hz leader for the
            // keying chain's attack time to consume. Strictly additive — the
            // encoder output is never trimmed (CLAUDE.md "Never clip the
            // leading audio").
            val preToneMs = if (controlsPtt) 0 else voxPreToneMsSource()
            val imageAudio = VoxPreTone.prependTo(encoded, preToneMs, sampleRate)
            // Optional CW station-ID tail (issue #14) kept as its own buffer so
            // it can still be keyed after a user-cancelled image (the operator
            // must identify). The image itself is untouched, so the leading
            // SSTV calibration/VIS the receiver needs is never disturbed.
            val cwId = cwIdSource()
            val cwTail = CwId.tail(cwId, sampleRate)
            val durationMs = (imageAudio.size + cwTail.size) * 1000L / sampleRate
            // The image occupies the middle of the buffer: the VOX leader is
            // prepended and the CW ID appended, and neither is part of the
            // picture a receiver is drawing.
            mutableImageWindow.postValue(
                TxImageWindow.of(
                    preToneSamples = imageAudio.size - encoded.size,
                    imageSamples = encoded.size,
                    totalSamples = imageAudio.size + cwTail.size,
                ),
            )
            log(
                "SSTV TX: start — mode=${mode.displayName} ${width}x$height" +
                    " samples=${imageAudio.size} rate=$sampleRate durationMs=$durationMs" +
                    (if (imageAudio.size > encoded.size) " voxPreToneMs=$preToneMs" else "") +
                    if (cwTail.isNotEmpty()) " cwId=${cwId.text} wpm=${cwId.wpm}" else "",
            )

            // A cancel that lands during encode would otherwise be lost: the
            // sink clears its own cancelled flag on play() entry, so the
            // transmitter must gate keying/playback on its own flag.
            if (cancelled.get()) {
                log("SSTV TX: cancelled before keying")
                return
            }

            keyer.keyDown()
            keyed = true
            // The settle delay exists to let a commanded rig switch over after
            // the PTT command; with nothing keyed (VOX) it would only push the
            // pre-tone/leader later for no benefit.
            if (controlsPtt) {
                val settleMs = settleDelayMsSource()
                if (settleMs > 0) Thread.sleep(settleMs)
            }

            val ticker = startProgressTicker(durationMs)
            try {
                if (cancelled.get()) {
                    log("SSTV TX: cancelled during PTT settle")
                } else {
                    completed = player.play(imageAudio, sampleRate)
                }
                // Identify even on a user Stop: a cancelled image is still an
                // on-air transmission. A safety halt sets suppressCwId, which
                // drops the rig immediately with no further keying.
                if (cwTail.isNotEmpty() && !suppressCwId.get()) {
                    log(
                        if (completed) "SSTV TX: sending CW ID"
                        else "SSTV TX: sending CW ID after cancel",
                    )
                    // The CW ID is part of the transmission duration, so the
                    // TX is only "complete" when the tail plays to the end
                    // too. A cancel landing during the ID (return false)
                    // must not still report 1.0 progress.
                    val cwCompleted = player.play(cwTail, sampleRate)
                    completed = completed && cwCompleted
                }
            } finally {
                // Stop-and-join so a late ticker post can never overwrite the
                // final progress value published below.
                ticker.stop()
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            log("SSTV TX: interrupted")
        } catch (t: Throwable) {
            log("SSTV TX: failed — ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            // Single point of teardown: whatever happened above, PTT drops.
            if (keyed) {
                try {
                    keyer.keyUp()
                } catch (t: Throwable) {
                    log("SSTV TX: key-up failed — $t")
                }
            }
            mutableTxProgress.postValue(if (completed) 1f else 0f)
            // Cancelled and failed are different things to tell an operator:
            // one they did on purpose, the other means the picture never went
            // out and they should look at the log. Published before the
            // transmitting flag drops so a consumer reacting to that flag
            // already has the result.
            mutableLastResult.postValue(
                TxResult(
                    outcome = when {
                        completed -> TxOutcome.COMPLETED
                        cancelled.get() -> TxOutcome.CANCELLED
                        else -> TxOutcome.FAILED
                    },
                    sequence = resultSequence.incrementAndGet(),
                ),
            )
            transmitting.set(false)
            mutableIsTransmitting.postValue(false)
            log(
                "SSTV TX: end — completed=$completed cancelled=${cancelled.get()}" +
                    " elapsedMs=${clock() - startedAt}",
            )
        }
    }

    /** Elapsed-time progress publisher. */
    private inner class ProgressTicker(durationMs: Long) {
        private val keepRunning = AtomicBoolean(true)
        private val startedAt = clock()
        private val thread = Thread(
            {
                while (keepRunning.get()) {
                    mutableTxProgress.postValue(
                        txProgressFraction(clock() - startedAt, durationMs),
                    )
                    try {
                        Thread.sleep(PROGRESS_TICK_MS)
                    } catch (ie: InterruptedException) {
                        return@Thread
                    }
                }
            },
            "SstvTxProgress",
        ).apply {
            isDaemon = true
            start()
        }

        /**
         * Deterministic stop: no posts can land after this returns. The join
         * is unbounded on purpose — the loop body cannot block (interruptible
         * sleep + non-blocking postValue), so the join is bounded in practice
         * by a single tick, and a timed join would silently void the
         * no-post-after-return guarantee this class is documented to give.
         */
        fun stop() {
            keepRunning.set(false)
            thread.interrupt()
            var interrupted = false
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (ie: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun startProgressTicker(durationMs: Long): ProgressTicker =
        ProgressTicker(durationMs)

    companion object {
        internal const val PROGRESS_TICK_MS = 200L

        /**
         * Elapsed/duration as a 0..1 fraction, capped just below 1 — only a
         * completed play posts exactly 1.0. Pure; unit-tested directly.
         */
        @JvmStatic
        internal fun txProgressFraction(elapsedMs: Long, durationMs: Long): Float {
            if (durationMs <= 0) return 0f
            val fraction = elapsedMs.toFloat() / durationMs.toFloat()
            return fraction.coerceIn(0f, MAX_TICKER_PROGRESS)
        }

        internal const val MAX_TICKER_PROGRESS = 0.99f
    }
}
