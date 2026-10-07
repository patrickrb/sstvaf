package radio.ks3ckc.sstvaf.wefax

/**
 * Decoder state machine position. Mirrors `WEFAX_STATUS_*` in
 * `cpp/sstv_lib/wefax.h`; [nativeValue] crosses the JNI boundary verbatim.
 */
enum class WefaxDecodeStatus(val nativeValue: Int) {
    /** No phasing seen yet — pre-lock content is ignored. */
    IDLE(0),

    /** Phasing line(s) seen; line phase locked in. */
    PHASING(1),

    /** Locked; emitting picture rows. */
    IMAGE(2),

    /** finish() called; the partial final line has been flushed. */
    DONE(3),
    ;

    companion object {
        fun fromNative(value: Int): WefaxDecodeStatus =
            entries.firstOrNull { it.nativeValue == value } ?: IDLE
    }
}

/**
 * A WEFAX (HF radiofax) line rate + IOC pairing the UI offers. LPM 120 /
 * IOC 576 is the near-universal marine/aviation standard; the others cover
 * the variants still on the air (see `cpp/sstv_lib/SOURCES.md`).
 */
enum class WefaxPreset(val lpm: Int, val ioc: Int) {
    LPM120_IOC576(120, 576),
    LPM120_IOC288(120, 288),
    LPM60_IOC576(60, 576),
    LPM90_IOC576(90, 576),
    LPM240_IOC576(240, 576),
    ;

    /** e.g. "120/576" — the notation fax schedules use. */
    val label: String get() = "$lpm/$ioc"

    companion object {
        val DEFAULT = LPM120_IOC576
    }
}

/**
 * The WEFAX codec surface the engine layer builds on. Implemented by
 * [NativeWefaxCodec] (JNI to cpp/sstv_lib/wefax.c) in the app and by
 * `FakeWefaxCodec` in JVM tests.
 */
interface WefaxCodec {

    /**
     * Open a push-model radiofax decoder for [sampleRate] Hz mono audio at
     * a known [lpm] line rate and [ioc].
     *
     * @throws IllegalStateException when the native decoder can't be created
     */
    fun newDecoderSession(sampleRate: Int, lpm: Int, ioc: Int): WefaxDecoderSession
}

/**
 * A handle-based push decoder for one fax transmission. Not thread-safe —
 * callers serialize access. [close] releases the native handle; every other
 * call after close is an error.
 */
interface WefaxDecoderSession : AutoCloseable {

    /** Feed the first [length] samples of [samples] to the decoder. */
    fun push(samples: FloatArray, length: Int)

    /** Flush the partial final line and mark the decode DONE. */
    fun finish()

    fun status(): WefaxDecodeStatus

    /** Pixels per scan line for the session's IOC. */
    fun width(): Int

    /** Number of picture rows decoded and readable so far. */
    fun rowsReady(): Int

    /**
     * Copy up to [nRows] decoded rows starting at [firstRow] into [out]
     * (8-bit gray, row-major, [width] per row). Returns rows actually
     * copied, or 0 when nothing is available.
     */
    fun readRows(firstRow: Int, nRows: Int, out: ByteArray): Int

    /** Back to IDLE hunting; the session stays usable. */
    fun reset()

    /** Release the native handle; the session is unusable afterwards. */
    override fun close()
}
