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

    /**
     * Operator mode lock: with a non-null [mode] the decoder still hunts for
     * the calibration header, but ignores the VIS payload (the part QRM
     * garbles first) and decodes as [mode]; null restores automatic VIS
     * selection. The lock survives [reset] — it is an operator setting, not
     * decode state.
     */
    fun setForcedMode(mode: SstvMode?)

    /** Estimated sample-clock slant in ppm (0 until enough syncs tracked). */
    fun slantPpm(): Float

    /** 0..1 blend of sync-hit-rate and in-band coherence. */
    fun quality(): Float

    /**
     * How the current image was locked: true when it was timed off a decoded
     * VIS header, false when the decoder locked onto the line-sync train
     * alone (transmission joined mid-image, or an unreadable header — rows
     * then start at the top of the frame). False before any lock.
     */
    fun visLocked(): Boolean

    /**
     * Back to hunting; the session stays usable. When a new calibration
     * header preempted the image that just ended, the decoder is already in
     * IMAGE for the new transmission afterwards — poll [status] again rather
     * than assuming IDLE.
     */
    fun reset()

    /** Release the native handle; the session is unusable afterwards. */
    override fun close()
}
