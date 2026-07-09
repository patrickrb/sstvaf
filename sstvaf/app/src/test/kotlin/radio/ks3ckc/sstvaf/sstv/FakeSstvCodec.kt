package radio.ks3ckc.sstvaf.sstv

/**
 * Deterministic [SstvCodec] for JVM tests: no native lib, fully scripted.
 *
 * Encoding returns a ramp waveform of [encodeSampleCount] samples and records
 * the call. Decoding is driven by [script]: each `push()` on the session
 * consumes the next [ScriptedState] (or keeps the current one when the script
 * is exhausted); `readRows` fills deterministic pixels via [pixelFor].
 */
class FakeSstvCodec : SstvCodec {

    // ----- encode ------------------------------------------------------

    data class EncodeCall(
        val width: Int,
        val height: Int,
        val mode: SstvMode,
        val sampleRate: Int,
    )

    var encodeSampleCount: Int = 12000
    val encodeCalls = mutableListOf<EncodeCall>()

    /** When set, [encode] throws it instead of returning audio. */
    var encodeFailure: RuntimeException? = null

    override fun encode(
        pixels: IntArray,
        width: Int,
        height: Int,
        mode: SstvMode,
        sampleRate: Int,
    ): FloatArray {
        encodeCalls += EncodeCall(width, height, mode, sampleRate)
        encodeFailure?.let { throw it }
        return FloatArray(encodeSampleCount) { i -> (i % 100) / 100f }
    }

    // ----- decode scripting ---------------------------------------------

    data class ScriptedState(
        val status: DecodeStatus,
        val mode: SstvMode? = null,
        val rowsReady: Int = 0,
        val quality: Float = 0f,
        val slantPpm: Float = 0f,
    )

    /** Consumed one entry per push(); empty = state holds. */
    val script = ArrayDeque<ScriptedState>()

    var sessionsCreated = 0
        private set
    var pushCount = 0
        private set

    /** First sample of every pushed buffer, in push order (drop-order checks). */
    val pushedFirstSamples = mutableListOf<Float>()
    var resetCount = 0
        private set
    var closedSessions = 0
        private set

    override fun newDecoderSession(sampleRate: Int): DecoderSession {
        sessionsCreated++
        return FakeSession()
    }

    /** Deterministic pixel pattern: opaque, row in G byte, column in B byte. */
    fun pixelFor(row: Int, x: Int): Int =
        (0xFF shl 24) or ((row and 0xFF) shl 8) or (x and 0xFF)

    private inner class FakeSession : DecoderSession {
        private var current = ScriptedState(DecodeStatus.IDLE)

        override fun push(samples: FloatArray, length: Int) {
            pushCount++
            if (length > 0) pushedFirstSamples += samples[0]
            script.removeFirstOrNull()?.let { current = it }
        }

        override fun status(): DecodeStatus = current.status

        override fun mode(): SstvMode? = current.mode

        override fun rowsReady(): Int = current.rowsReady

        override fun readRows(firstRow: Int, nRows: Int, out: IntArray): Int {
            val mode = current.mode ?: return 0
            val n = minOf(nRows, current.rowsReady - firstRow).coerceAtLeast(0)
            for (r in 0 until n) {
                for (x in 0 until mode.width) {
                    out[r * mode.width + x] = pixelFor(firstRow + r, x)
                }
            }
            return n
        }

        override fun slantPpm(): Float = current.slantPpm

        override fun quality(): Float = current.quality

        override fun reset() {
            resetCount++
            current = ScriptedState(DecodeStatus.IDLE)
        }

        override fun close() {
            closedSessions++
        }
    }
}
