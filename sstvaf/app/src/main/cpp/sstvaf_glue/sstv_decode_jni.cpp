// SSTV decoder JNI wrappers — binds the clean-room codec's handle-based push
// decoder (sstv_lib/sstv_decode.c) to Kotlin.
//
// Java side:
//   radio.ks3ckc.sstvaf.sstv.NativeSstvCodec (instance methods)
//
// Handle model (same pattern the retired FT8 decoder glue used): create
// returns an opaque sstv_decoder_t* packed into a jlong; every other entry
// point unpacks it. The Kotlin DecoderSession wrapper serializes access and
// guarantees destroy is called exactly once — the decoder itself is not
// thread-safe and these wrappers add no locking.

#include <jni.h>
#include <stdint.h>

#include "sstv.h"  // guards its own C linkage

static inline sstv_decoder_t* decoder_from_handle(jlong handle)
{
    return (sstv_decoder_t*)(intptr_t)handle;
}

// sstv_decoder_create(sample_rate) -> handle, 0 on failure (bad rate / OOM).
extern "C" JNIEXPORT jlong JNICALL
Java_radio_ks3ckc_sstvaf_sstv_NativeSstvCodec_nativeDecoderCreate(
        JNIEnv*, jobject, jint sampleRate)
{
    return (jlong)(intptr_t)sstv_decoder_create(sampleRate);
}

// sstv_decoder_push(dec, samples, n) — feed n mono float samples.
extern "C" JNIEXPORT void JNICALL
Java_radio_ks3ckc_sstvaf_sstv_NativeSstvCodec_nativeDecoderPush(
        JNIEnv* env, jobject, jlong handle, jfloatArray samples, jint n)
{
    sstv_decoder_t* dec = decoder_from_handle(handle);
    if (dec == nullptr || samples == nullptr || n <= 0) {
        return;
    }
    jsize len = env->GetArrayLength(samples);
    if (n > len) {
        n = len;
    }
    jfloat* buf = env->GetFloatArrayElements(samples, nullptr);
    if (buf == nullptr) {
        return;
    }
    sstv_decoder_push(dec, buf, n);
    env->ReleaseFloatArrayElements(samples, buf, JNI_ABORT);
}

// sstv_decoder_status(dec) -> SSTV_STATUS_* (IDLE when handle is null).
extern "C" JNIEXPORT jint JNICALL
Java_radio_ks3ckc_sstvaf_sstv_NativeSstvCodec_nativeDecoderStatus(
        JNIEnv*, jobject, jlong handle)
{
    sstv_decoder_t* dec = decoder_from_handle(handle);
    return dec != nullptr ? sstv_decoder_status(dec) : SSTV_STATUS_IDLE;
}

// sstv_decoder_mode(dec) -> mode id, or -1 before VIS lock.
extern "C" JNIEXPORT jint JNICALL
Java_radio_ks3ckc_sstvaf_sstv_NativeSstvCodec_nativeDecoderMode(
        JNIEnv*, jobject, jlong handle)
{
    sstv_decoder_t* dec = decoder_from_handle(handle);
    return dec != nullptr ? sstv_decoder_mode(dec) : -1;
}

// sstv_decoder_rows_ready(dec) -> decoded row count so far.
extern "C" JNIEXPORT jint JNICALL
Java_radio_ks3ckc_sstvaf_sstv_NativeSstvCodec_nativeDecoderRowsReady(
        JNIEnv*, jobject, jlong handle)
{
    sstv_decoder_t* dec = decoder_from_handle(handle);
    return dec != nullptr ? sstv_decoder_rows_ready(dec) : 0;
}

// sstv_decoder_read_rows(dec, first_row, n_rows, argb) — copy decoded rows
// (0xAARRGGBB, mode width) into argbOut. Returns rows copied or a negative
// SSTV_ERR_*.
extern "C" JNIEXPORT jint JNICALL
Java_radio_ks3ckc_sstvaf_sstv_NativeSstvCodec_nativeDecoderReadRows(
        JNIEnv* env, jobject, jlong handle, jint firstRow, jint nRows,
        jintArray argbOut)
{
    sstv_decoder_t* dec = decoder_from_handle(handle);
    if (dec == nullptr || argbOut == nullptr) {
        return SSTV_ERR_BAD_ARGS;
    }
    jint* out = env->GetIntArrayElements(argbOut, nullptr);
    if (out == nullptr) {
        return SSTV_ERR_NOMEM;
    }
    int copied = sstv_decoder_read_rows(dec, firstRow, nRows, (uint32_t*)out);
    env->ReleaseIntArrayElements(argbOut, out, copied > 0 ? 0 : JNI_ABORT);
    return copied;
}

// sstv_decoder_slant_ppm(dec) -> estimated sample-clock slant in ppm.
extern "C" JNIEXPORT jfloat JNICALL
Java_radio_ks3ckc_sstvaf_sstv_NativeSstvCodec_nativeDecoderSlantPpm(
        JNIEnv*, jobject, jlong handle)
{
    sstv_decoder_t* dec = decoder_from_handle(handle);
    return dec != nullptr ? sstv_decoder_slant_ppm(dec) : 0.0f;
}

// sstv_decoder_quality(dec) -> 0..1 decode quality estimate.
extern "C" JNIEXPORT jfloat JNICALL
Java_radio_ks3ckc_sstvaf_sstv_NativeSstvCodec_nativeDecoderQuality(
        JNIEnv*, jobject, jlong handle)
{
    sstv_decoder_t* dec = decoder_from_handle(handle);
    return dec != nullptr ? sstv_decoder_quality(dec) : 0.0f;
}

// sstv_decoder_reset(dec) — back to IDLE hunting; keeps the handle valid.
extern "C" JNIEXPORT void JNICALL
Java_radio_ks3ckc_sstvaf_sstv_NativeSstvCodec_nativeDecoderReset(
        JNIEnv*, jobject, jlong handle)
{
    sstv_decoder_t* dec = decoder_from_handle(handle);
    if (dec != nullptr) {
        sstv_decoder_reset(dec);
    }
}

// sstv_decoder_destroy(dec) — free the handle; it must not be used again.
extern "C" JNIEXPORT void JNICALL
Java_radio_ks3ckc_sstvaf_sstv_NativeSstvCodec_nativeDecoderDestroy(
        JNIEnv*, jobject, jlong handle)
{
    sstv_decoder_t* dec = decoder_from_handle(handle);
    if (dec != nullptr) {
        sstv_decoder_destroy(dec);
    }
}
