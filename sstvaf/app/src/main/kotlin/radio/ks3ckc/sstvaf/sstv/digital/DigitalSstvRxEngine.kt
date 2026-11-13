package radio.ks3ckc.sstvaf.sstv.digital

/**
 * A completed digital-SSTV reception: the reassembled compressed payload
 * plus the frame metadata a renderer needs. Public (unlike the codec
 * internals) because it crosses the signal listener's API to the save
 * controller and any UI observer.
 */
class DigitalRxImage(
    val mode: DigitalSstvMode,
    val format: Int,
    val width: Int,
    val height: Int,
    val payload: ByteArray,
)

/**
 * Streaming digital-SSTV receive engine: feed it the 12 kHz listener audio
 * and it hunts for a COFDM preamble, captures the frame, and hands back the
 * reassembled image payload.
 *
 * Pipeline per [push]: decimate to the codec's 6 kHz design rate
 * ([Decimator2to1]) into a ring buffer. While HUNTING, the newest window is
 * cross-correlated against each robustness mode's preamble
 * ([OfdmModem.findFrameStartScored]); a normalized score above
 * [DETECT_THRESHOLD] starts a CAPTURE anchored just before the hit. While
 * CAPTURING, a decode of the captured span is attempted every
 * [ATTEMPT_INTERVAL_SAMPLES] new samples — [DigitalSstvCodec.decode] returns
 * null until the whole frame is in the buffer, so retrying on a growing span
 * is the streaming adaptation of the codec's whole-buffer model. A frame
 * whose blocks all survive FEC yields a [DigitalRxImage]; a capture that produces
 * nothing within [MAX_CAPTURE_SAMPLES] returns to hunting (the CRC-gated
 * decode is the real false-positive filter — a noise trigger only costs a
 * capture window).
 *
 * Single-transmission only: a frame decoded with missing blocks is logged
 * and dropped — the library's ARQ/retransmission machinery
 * ([DigitalSstvReceiver.retransmitRequest]) needs an over-the-air back
 * channel and operator consent, which is future work.
 *
 * Not thread-safe; the signal listener drives it from its decode thread.
 */
internal class DigitalSstvRxEngine(
    private val log: (String) -> Unit,
) {

    private val decimator = Decimator2to1()
    private val ring = FloatArray(RING_SAMPLES)
    private var writePos = 0L // absolute 6 kHz sample count

    private val modems = DigitalSstvMode.entries.associateWith { OfdmModem(it.ofdm) }

    // CAPTURE state; capturing while captureMode != null.
    private var captureMode: DigitalSstvMode? = null
    private var captureStart = 0L
    private var nextAttemptAt = 0L
    private var lastDetectAt = 0L
    private var lastMissingLogged = -1

    /**
     * Feed the first [length] samples of 12 kHz [samples]. Returns a
     * completed image when this chunk finished a frame, else null.
     */
    fun push(samples: FloatArray, length: Int): DigitalRxImage? {
        val chunk = decimator.push(samples, length)
        if (chunk.isEmpty()) return null
        append(chunk)
        return if (captureMode == null) {
            hunt()
            null
        } else {
            attemptDecode()
        }
    }

    fun reset() {
        decimator.reset()
        writePos = 0
        captureMode = null
        nextAttemptAt = 0
        lastDetectAt = 0
    }

    /** Test hook: whether a capture is in progress. */
    internal fun isCapturing(): Boolean = captureMode != null

    private fun append(chunk: FloatArray) {
        for (v in chunk) {
            ring[(writePos % RING_SAMPLES).toInt()] = v
            writePos++
        }
    }

    private fun hunt() {
        // Search once per DETECT_STEP of new audio, over a window that
        // overlaps the previous search by a symbol so a preamble straddling
        // the boundary is never missed.
        if (writePos - lastDetectAt < DETECT_STEP) return
        lastDetectAt = writePos
        val windowLen = DETECT_WINDOW.coerceAtMost(writePos.toInt())
        val from = writePos - windowLen
        val window = DoubleArray(windowLen) { i -> ring[((from + i) % RING_SAMPLES).toInt()].toDouble() }

        // Every mode is scored and the best wins: the three preambles share
        // the same symbol body and differ only in cyclic-prefix length, so a
        // FAST transmission also scores ~0.97 against the STANDARD template —
        // first-hit-wins would misattribute the mode. The true mode's exact
        // template always scores highest.
        var bestMode: DigitalSstvMode? = null
        var bestOffset = 0
        var bestScore = 0.0
        for ((mode, modem) in modems) {
            val (offset, score) = modem.findFrameStartScored(window, windowLen - modem.symbolLength)
            if (score >= DETECT_THRESHOLD && score > bestScore) {
                bestMode = mode
                bestOffset = offset
                bestScore = score
            }
        }
        val mode = bestMode ?: return
        captureMode = mode
        captureStart = (from + bestOffset - CAPTURE_MARGIN).coerceAtLeast(0L)
        nextAttemptAt = writePos + ATTEMPT_INTERVAL_SAMPLES
        lastMissingLogged = -1
        log(
            "SSTV RX digital: preamble hit — mode=${mode.displayName}" +
                " score=${(bestScore * 100).toInt()}%",
        )
    }

    private fun attemptDecode(): DigitalRxImage? {
        val mode = captureMode ?: return null
        if (writePos - captureStart > MAX_CAPTURE_SAMPLES) {
            log("SSTV RX digital: capture timed out — back to hunting")
            captureMode = null
            return null
        }
        if (writePos < nextAttemptAt) return null
        nextAttemptAt = writePos + ATTEMPT_INTERVAL_SAMPLES

        val len = (writePos - captureStart).toInt()
        if (len <= 0 || len > RING_SAMPLES) {
            captureMode = null
            return null
        }
        val audio = FloatArray(len) { i -> ring[((captureStart + i) % RING_SAMPLES).toInt()] }
        val decoded = DigitalSstvCodec(mode).decode(audio, searchLimit = SEARCH_LIMIT) ?: return null

        val receiver = DigitalSstvReceiver()
        receiver.accept(decoded)
        val payload = receiver.payload()
        if (payload != null) {
            captureMode = null
            log(
                "SSTV RX digital: image complete — mode=${mode.displayName}" +
                    " ${decoded.meta.width}x${decoded.meta.height}" +
                    " payload=${payload.size}B corrupt=${decoded.corruptCount}",
            )
            return DigitalRxImage(
                mode, decoded.meta.format, decoded.meta.width, decoded.meta.height, payload,
            )
        }
        // Blocks missing. The header decodes long before the payload finishes
        // arriving, so "missing" usually just means "still on the air" — keep
        // capturing and try again with more audio; the 90 s cap is the only
        // give-up. (A genuinely lossy frame would need the library's ARQ
        // machinery, which has no over-the-air back channel yet.)
        if (decoded.missing.size != lastMissingLogged) {
            lastMissingLogged = decoded.missing.size
            log(
                "SSTV RX digital: ${decoded.missing.size} block(s) still missing" +
                    " — capture continues",
            )
        }
        return null
    }

    companion object {
        /** Ring capacity: the longest capture plus hunting slack. */
        private const val RING_SECONDS = 100
        internal const val RING_SAMPLES = RING_SECONDS * DIGITAL_WAVEFORM_RATE_HZ

        /** Hunt window / cadence (6 kHz samples). */
        internal const val DETECT_WINDOW = 4096
        internal const val DETECT_STEP = 2048

        /**
         * Normalized preamble-correlation floor. The clean loopback chain
         * (linear upsample + half-band decimate) scores ~0.9; noise scores
         * near 0. Set well below the clean score so fading still triggers,
         * and let the frame CRCs reject anything that wasn't a real frame.
         */
        internal const val DETECT_THRESHOLD = 0.35

        /** Samples kept before the detected preamble (timing slack). */
        internal const val CAPTURE_MARGIN = 512L

        /** Search span handed to the codec's own frame-start recovery. */
        internal const val SEARCH_LIMIT = 2048

        /** Decode retry cadence: once per second of new audio. */
        internal const val ATTEMPT_INTERVAL_SAMPLES = DIGITAL_WAVEFORM_RATE_HZ.toLong()

        /** A capture that yields nothing for 90 s was a false trigger. */
        internal const val MAX_CAPTURE_SAMPLES = 90L * DIGITAL_WAVEFORM_RATE_HZ
    }
}
