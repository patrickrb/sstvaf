package radio.ks3ckc.sstvaf.sstv

/**
 * Decoder state machine position. Mirrors `SSTV_STATUS_*` in
 * `cpp/sstv_lib/sstv.h`; [nativeValue] crosses the JNI boundary verbatim
 * (pinned by [SstvModeTest]).
 */
enum class DecodeStatus(val nativeValue: Int) {
    /** Hunting for the 1900 Hz leader. */
    IDLE(0),

    /** Leader seen; hunting for the VIS start bit. */
    LEADER(1),

    /** Start bit seen; sampling the VIS cells. */
    VIS(2),

    /** VIS accepted; decoding image lines. */
    IMAGE(3),

    /** Full image decoded. */
    DONE(4),

    /** Signal lost mid-image (partial image retained). */
    ABORTED(5),
    ;

    companion object {
        fun fromNative(value: Int): DecodeStatus =
            entries.firstOrNull { it.nativeValue == value } ?: IDLE
    }
}

/**
 * The SSTV codec surface the engine layer builds on. Implemented by
 * [NativeSstvCodec] (JNI to cpp/sstv_lib) in the app and by `FakeSstvCodec`
 * in JVM tests.
 */
interface SstvCodec {

    /**
     * Encode a full SSTV transmission (calibration header + VIS + image) of
     * [pixels] (0xAARRGGBB row-major, exactly [width] x [height], which must
     * match the mode's native dimensions) into a mono float waveform at
     * [sampleRate] Hz.
     *
     * @throws IllegalArgumentException on dimension mismatch
     * @throws IllegalStateException when the native encoder reports an error
     */
    fun encode(
        pixels: IntArray,
        width: Int,
        height: Int,
        mode: SstvMode,
        sampleRate: Int,
    ): FloatArray

    /** Open a new push-model decoder session for [sampleRate] Hz mono audio. */
    fun newDecoderSession(sampleRate: Int): DecoderSession
}

/**
 * A handle-based push decoder: feed arbitrary-size float sample blocks with
 * [push], poll [status]/[rowsReady], and copy decoded rows out with
 * [readRows]. Not thread-safe — callers serialize access (the signal
 * listener guards it with one lock). [close] releases the native handle;
 * every other call after close is an error.
 */
interface DecoderSession : AutoCloseable {

    /** Feed the first [length] samples of [samples] to the decoder. */
    fun push(samples: FloatArray, length: Int)

    fun status(): DecodeStatus

    /** Detected mode, or null before VIS lock. */
    fun mode(): SstvMode?

    /** Number of image rows decoded and readable so far. */
    fun rowsReady(): Int

    /**
     * Copy up to [nRows] decoded rows starting at [firstRow] into [out]
     * (0xAARRGGBB row-major, mode width). Returns rows actually copied
     * (limited by [rowsReady]), or 0 when no mode is locked yet.
     */
    fun readRows(firstRow: Int, nRows: Int, out: IntArray): Int

    /** Estimated sample-clock slant in ppm (0 until enough syncs tracked). */
    fun slantPpm(): Float

    /** 0..1 blend of sync-hit-rate and in-band coherence. */
    fun quality(): Float

    /** Back to IDLE hunting; the session stays usable. */
    fun reset()

    /** Release the native handle; the session is unusable afterwards. */
    override fun close()
}
