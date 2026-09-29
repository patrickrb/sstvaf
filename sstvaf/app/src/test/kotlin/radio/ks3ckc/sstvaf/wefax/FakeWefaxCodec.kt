package radio.ks3ckc.sstvaf.wefax

/**
 * Deterministic [WefaxCodec] for JVM tests: no native lib, fully scripted.
 *
 * Decoding is driven by [script]: each `push()` on the session consumes the
 * next [ScriptedState] (or keeps the current one when the script is
 * exhausted); `readRows` fills the deterministic [grayFor] pattern.
 */
class FakeWefaxCodec : WefaxCodec {

    data class ScriptedState(
        val status: WefaxDecodeStatus,
        val rowsReady: Int = 0,
    )

    data class SessionParams(val sampleRate: Int, val lpm: Int, val ioc: Int)

    /** Consumed one entry per push(); empty = state holds. */
    val script = ArrayDeque<ScriptedState>()

    /** Width every session reports (the fake ignores the IOC). */
    var width: Int = 8

    /** When set, [newDecoderSession] throws it (no-native-lib path). */
    var createFailure: RuntimeException? = null

    var sessionsCreated = 0
        private set
    val sessionParams = mutableListOf<SessionParams>()
    var pushCount = 0
        private set
    var finishCount = 0
        private set
    var closedSessions = 0
        private set

    override fun newDecoderSession(sampleRate: Int, lpm: Int, ioc: Int): WefaxDecoderSession {
        createFailure?.let { throw it }
        sessionsCreated++
        sessionParams += SessionParams(sampleRate, lpm, ioc)
        return FakeSession()
    }

    /** Deterministic gray pattern: row + column, wrapped to a byte. */
    fun grayFor(row: Int, x: Int): Byte = ((row + x) and 0xFF).toByte()

    private inner class FakeSession : WefaxDecoderSession {
        private var current = ScriptedState(WefaxDecodeStatus.IDLE)
        private var done = false

        override fun push(samples: FloatArray, length: Int) {
            pushCount++
            script.removeFirstOrNull()?.let { current = it }
        }

        override fun finish() {
            finishCount++
            done = true
        }

        override fun status(): WefaxDecodeStatus =
            if (done) WefaxDecodeStatus.DONE else current.status

        override fun width(): Int = width

        override fun rowsReady(): Int = current.rowsReady

        override fun readRows(firstRow: Int, nRows: Int, out: ByteArray): Int {
            val n = minOf(nRows, current.rowsReady - firstRow).coerceAtLeast(0)
            for (r in 0 until n) {
                for (x in 0 until width) {
                    out[r * width + x] = grayFor(firstRow + r, x)
                }
            }
            return n
        }

        override fun reset() {
            current = ScriptedState(WefaxDecodeStatus.IDLE)
            done = false
        }

        override fun close() {
            closedSessions++
        }
    }
}
