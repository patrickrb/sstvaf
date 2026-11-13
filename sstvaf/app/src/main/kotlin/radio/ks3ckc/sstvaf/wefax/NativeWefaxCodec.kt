package radio.ks3ckc.sstvaf.wefax

import android.util.Log

/**
 * The real [WefaxCodec]: JNI bridge to the clean-room radiofax codec in
 * `cpp/sstv_lib/wefax.c`, glued in `cpp/sstvaf_glue/wefax_jni.cpp`.
 *
 * The native symbols are bound to this exact class
 * (`Java_radio_ks3ckc_sstvaf_wefax_NativeWefaxCodec_*`), so neither the
 * class name nor the `external` method names may change (see
 * proguard-rules.pro, guarded by R8KeepRulesTest).
 *
 * Library load follows the NativeSstvCodec pattern: a JVM unit test has no
 * libsstvaf.so, so the loader failure is caught and remembered — the native
 * methods then throw [UnsatisfiedLinkError] only if actually invoked.
 */
class NativeWefaxCodec : WefaxCodec {

    override fun newDecoderSession(sampleRate: Int, lpm: Int, ioc: Int): WefaxDecoderSession {
        val handle = nativeDecoderCreate(sampleRate, lpm, ioc)
        check(handle != 0L) { "wefax_decoder_create($sampleRate, $lpm, $ioc) failed" }
        return NativeSession(handle)
    }

    /**
     * Wraps one native decoder handle. All methods synchronize on the
     * session so the not-thread-safe native decoder never sees concurrent
     * calls, and [close] is idempotent (handle zeroed under the same lock).
     */
    private inner class NativeSession(private var handle: Long) : WefaxDecoderSession {

        private fun requireHandle(): Long {
            val h = handle
            check(h != 0L) { "wefax decoder session is closed" }
            return h
        }

        @Synchronized
        override fun push(samples: FloatArray, length: Int) {
            nativeDecoderPush(requireHandle(), samples, length.coerceAtMost(samples.size))
        }

        @Synchronized
        override fun finish() {
            nativeDecoderFinish(requireHandle())
        }

        @Synchronized
        override fun status(): WefaxDecodeStatus =
            WefaxDecodeStatus.fromNative(nativeDecoderStatus(requireHandle()))

        @Synchronized
        override fun width(): Int = nativeDecoderWidth(requireHandle())

        @Synchronized
        override fun rowsReady(): Int = nativeDecoderRowsReady(requireHandle())

        @Synchronized
        override fun readRows(firstRow: Int, nRows: Int, out: ByteArray): Int {
            val copied = nativeDecoderReadRows(requireHandle(), firstRow, nRows, out)
            return if (copied > 0) copied else 0
        }

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

    // ----- JNI surface (cpp/sstvaf_glue/wefax_jni.cpp) -----

    private external fun nativeDecoderCreate(sampleRate: Int, lpm: Int, ioc: Int): Long

    private external fun nativeDecoderPush(handle: Long, samples: FloatArray, n: Int)

    private external fun nativeDecoderFinish(handle: Long)

    private external fun nativeDecoderStatus(handle: Long): Int

    private external fun nativeDecoderWidth(handle: Long): Int

    private external fun nativeDecoderRowsReady(handle: Long): Int

    private external fun nativeDecoderReadRows(
        handle: Long,
        firstRow: Int,
        nRows: Int,
        grayOut: ByteArray,
    ): Int

    private external fun nativeDecoderReset(handle: Long)

    private external fun nativeDecoderDestroy(handle: Long)

    companion object {
        private const val TAG = "NativeWefaxCodec"

        init {
            try {
                System.loadLibrary("sstvaf")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Failed to load libsstvaf: ${e.message}")
            }
        }
    }
}
