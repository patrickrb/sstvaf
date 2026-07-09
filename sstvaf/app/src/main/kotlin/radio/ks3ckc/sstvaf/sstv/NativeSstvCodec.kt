package radio.ks3ckc.sstvaf.sstv

import android.util.Log

/**
 * The real [SstvCodec]: JNI bridge to the clean-room codec in
 * `cpp/sstv_lib`, glued in `cpp/sstvaf_glue/sstv_encode_jni.cpp` and
 * `sstv_decode_jni.cpp`.
 *
 * The native symbols are bound to this exact class
 * (`Java_radio_ks3ckc_sstvaf_sstv_NativeSstvCodec_*`), so neither the class
 * name nor the `external` method names may change (see proguard-rules.pro,
 * guarded by R8KeepRulesTest).
 *
 * Library load follows the UsbAudioNative pattern: a JVM unit test has no
 * libsstvaf.so, so the loader failure is caught and remembered — the native
 * methods then throw [UnsatisfiedLinkError] only if actually invoked (the
 * engine's decode thread catches that and shuts itself down cleanly).
 */
class NativeSstvCodec : SstvCodec {

    override fun encode(
        pixels: IntArray,
        width: Int,
        height: Int,
        mode: SstvMode,
        sampleRate: Int,
    ): FloatArray {
        require(width == mode.width && height == mode.height) {
            "${mode.displayName} needs ${mode.width}x${mode.height}, got ${width}x$height"
        }
        require(pixels.size >= width * height) {
            "pixel buffer too small: ${pixels.size} < ${width * height}"
        }
        val numSamples = nativeEncodeNumSamples(mode.modeId, sampleRate)
        check(numSamples > 0) {
            "sstv_encode_num_samples(${mode.modeId}, $sampleRate) failed: $numSamples"
        }
        val out = FloatArray(numSamples)
        val written = nativeEncode(
            mode.modeId, pixels, width, height, sampleRate, ENCODE_AMPLITUDE, out,
        )
        check(written == numSamples) { "sstv_encode failed: $written" }
        return out
    }

    override fun newDecoderSession(sampleRate: Int): DecoderSession {
        val handle = nativeDecoderCreate(sampleRate)
        check(handle != 0L) { "sstv_decoder_create($sampleRate) failed" }
        return NativeDecoderSession(handle)
    }

    /**
     * Wraps one native decoder handle. All methods synchronize on the session
     * so the not-thread-safe native decoder never sees concurrent calls, and
     * [close] is idempotent (handle zeroed under the same lock).
     */
    private inner class NativeDecoderSession(private var handle: Long) : DecoderSession {

        private fun requireHandle(): Long {
            val h = handle
            check(h != 0L) { "decoder session is closed" }
            return h
        }

        @Synchronized
        override fun push(samples: FloatArray, length: Int) {
            nativeDecoderPush(requireHandle(), samples, length.coerceAtMost(samples.size))
        }

        @Synchronized
        override fun status(): DecodeStatus =
            DecodeStatus.fromNative(nativeDecoderStatus(requireHandle()))

        @Synchronized
        override fun mode(): SstvMode? = SstvMode.fromModeId(nativeDecoderMode(requireHandle()))

        @Synchronized
        override fun rowsReady(): Int = nativeDecoderRowsReady(requireHandle())

        @Synchronized
        override fun readRows(firstRow: Int, nRows: Int, out: IntArray): Int {
            val copied = nativeDecoderReadRows(requireHandle(), firstRow, nRows, out)
            // Negative return = no mode locked yet / bad range; the engine
            // treats both as "nothing to read".
            return if (copied > 0) copied else 0
        }

        @Synchronized
        override fun slantPpm(): Float = nativeDecoderSlantPpm(requireHandle())

        @Synchronized
        override fun quality(): Float = nativeDecoderQuality(requireHandle())

        @Synchronized
        override fun reset() {
            nativeDecoderReset(requireHandle())
        }

        @Synchronized
        override fun close() {
            val h = handle
            if (h != 0L) {
                handle = 0L
                nativeDecoderDestroy(h)
            }
        }
    }

    // ----- JNI surface (cpp/sstvaf_glue/sstv_encode_jni.cpp, sstv_decode_jni.cpp) -----

    private external fun nativeEncodeNumSamples(modeId: Int, sampleRate: Int): Int

    @Suppress("LongParameterList")
    private external fun nativeEncode(
        modeId: Int,
        argb: IntArray,
        width: Int,
        height: Int,
        sampleRate: Int,
        amplitude: Float,
        out: FloatArray,
    ): Int

    private external fun nativeDecoderCreate(sampleRate: Int): Long

    private external fun nativeDecoderPush(handle: Long, samples: FloatArray, n: Int)

    private external fun nativeDecoderStatus(handle: Long): Int

    private external fun nativeDecoderMode(handle: Long): Int

    private external fun nativeDecoderRowsReady(handle: Long): Int

    private external fun nativeDecoderReadRows(
        handle: Long,
        firstRow: Int,
        nRows: Int,
        argbOut: IntArray,
    ): Int

    private external fun nativeDecoderSlantPpm(handle: Long): Float

    private external fun nativeDecoderQuality(handle: Long): Float

    private external fun nativeDecoderReset(handle: Long)

    private external fun nativeDecoderDestroy(handle: Long)

    companion object {
        private const val TAG = "NativeSstvCodec"

        /**
         * Encoder output amplitude. Slightly below full scale so the
         * downstream float→int16 conversion (TransmitAudioSink) never sits on
         * the clip boundary; the TX volume is applied later in the sink.
         */
        const val ENCODE_AMPLITUDE = 0.95f

        init {
            try {
                System.loadLibrary("sstvaf")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Failed to load libsstvaf: ${e.message}")
            }
        }
    }
}
