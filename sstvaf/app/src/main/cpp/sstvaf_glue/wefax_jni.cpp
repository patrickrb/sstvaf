// WEFAX decoder JNI wrappers — binds the clean-room radiofax codec
// (sstv_lib/wefax.c) to Kotlin.
//
// Java side:
//   radio.ks3ckc.sstvaf.wefax.NativeWefaxCodec (instance methods)
//
// Same handle model as sstv_decode_jni.cpp: create returns an opaque
// wefax_decoder_t* packed into a jlong; every other entry point unpacks it.
// The Kotlin session wrapper serializes access and guarantees destroy is
// called exactly once — the decoder is not thread-safe and these wrappers
// add no locking.

#include <jni.h>
#include <stdint.h>

#include "wefax.h"  // guards its own C linkage

static inline wefax_decoder_t* wefax_from_handle(jlong handle)
{
    return (wefax_decoder_t*)(intptr_t)handle;
}

// wefax_decoder_create(rate, lpm, ioc) -> handle, 0 on failure.
extern "C" JNIEXPORT jlong JNICALL
Java_radio_ks3ckc_sstvaf_wefax_NativeWefaxCodec_nativeDecoderCreate(
        JNIEnv*, jobject, jint sampleRate, jint lpm, jint ioc)
{
    return (jlong)(intptr_t)wefax_decoder_create(sampleRate, lpm, ioc);
}

// wefax_decoder_push(dec, samples, n) — feed n mono float samples.
extern "C" JNIEXPORT void JNICALL
Java_radio_ks3ckc_sstvaf_wefax_NativeWefaxCodec_nativeDecoderPush(
        JNIEnv* env, jobject, jlong handle, jfloatArray samples, jint n)
{
    wefax_decoder_t* dec = wefax_from_handle(handle);
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
    wefax_decoder_push(dec, buf, n);
    env->ReleaseFloatArrayElements(samples, buf, JNI_ABORT);
}

// wefax_decoder_finish(dec) — flush the partial final line, status -> DONE.
extern "C" JNIEXPORT void JNICALL
Java_radio_ks3ckc_sstvaf_wefax_NativeWefaxCodec_nativeDecoderFinish(
        JNIEnv*, jobject, jlong handle)
{
    wefax_decoder_t* dec = wefax_from_handle(handle);
    if (dec != nullptr) {
        wefax_decoder_finish(dec);
    }
}

// wefax_decoder_status(dec) -> WEFAX_STATUS_* (IDLE when handle is null).
extern "C" JNIEXPORT jint JNICALL
Java_radio_ks3ckc_sstvaf_wefax_NativeWefaxCodec_nativeDecoderStatus(
        JNIEnv*, jobject, jlong handle)
{
    wefax_decoder_t* dec = wefax_from_handle(handle);
    return dec != nullptr ? wefax_decoder_status(dec) : WEFAX_STATUS_IDLE;
}

// wefax_decoder_width(dec) -> pixels per line for the decoder's IOC.
extern "C" JNIEXPORT jint JNICALL
Java_radio_ks3ckc_sstvaf_wefax_NativeWefaxCodec_nativeDecoderWidth(
        JNIEnv*, jobject, jlong handle)
{
    wefax_decoder_t* dec = wefax_from_handle(handle);
    return dec != nullptr ? wefax_decoder_width(dec) : 0;
}

// wefax_decoder_rows_ready(dec) -> decoded row count so far.
extern "C" JNIEXPORT jint JNICALL
Java_radio_ks3ckc_sstvaf_wefax_NativeWefaxCodec_nativeDecoderRowsReady(
        JNIEnv*, jobject, jlong handle)
{
    wefax_decoder_t* dec = wefax_from_handle(handle);
    return dec != nullptr ? wefax_decoder_rows_ready(dec) : 0;
}

// wefax_decoder_read_rows(dec, first_row, n_rows, gray) — copy decoded gray
// rows (one byte per pixel, decoder width). Returns rows copied or a
// negative WEFAX_ERR_*.
extern "C" JNIEXPORT jint JNICALL
Java_radio_ks3ckc_sstvaf_wefax_NativeWefaxCodec_nativeDecoderReadRows(
        JNIEnv* env, jobject, jlong handle, jint firstRow, jint nRows,
        jbyteArray grayOut)
{
    wefax_decoder_t* dec = wefax_from_handle(handle);
    if (dec == nullptr || grayOut == nullptr) {
        return WEFAX_ERR_BAD_ARGS;
    }
    // Defense in depth: wefax_decoder_read_rows bounds the copy only by the
    // decoder's own row count and trusts the destination to hold
    // n_rows * width bytes. A caller sizing grayOut from a stale width (or
    // row count) would otherwise let the memcpy run past the end of the
    // Java array — a native out-of-bounds heap write — so clamp nRows to
    // what grayOut can actually hold.
    int width = wefax_decoder_width(dec);
    if (width <= 0) {
        return WEFAX_ERR_BAD_ARGS;
    }
    jsize maxRows = env->GetArrayLength(grayOut) / width;
    if (nRows > maxRows) {
        nRows = (jint)maxRows;
    }
    jbyte* out = env->GetByteArrayElements(grayOut, nullptr);
    if (out == nullptr) {
        return WEFAX_ERR_NOMEM;
    }
    int copied = wefax_decoder_read_rows(dec, firstRow, nRows, (uint8_t*)out);
    env->ReleaseByteArrayElements(grayOut, out, copied > 0 ? 0 : JNI_ABORT);
    return copied;
}

// wefax_decoder_reset(dec) — back to IDLE hunting; keeps the handle valid.
extern "C" JNIEXPORT void JNICALL
Java_radio_ks3ckc_sstvaf_wefax_NativeWefaxCodec_nativeDecoderReset(
        JNIEnv*, jobject, jlong handle)
{
    wefax_decoder_t* dec = wefax_from_handle(handle);
    if (dec != nullptr) {
        wefax_decoder_reset(dec);
    }
}

// wefax_decoder_destroy(dec) — free the handle; it must not be used again.
extern "C" JNIEXPORT void JNICALL
Java_radio_ks3ckc_sstvaf_wefax_NativeWefaxCodec_nativeDecoderDestroy(
        JNIEnv*, jobject, jlong handle)
{
    wefax_decoder_t* dec = wefax_from_handle(handle);
    if (dec != nullptr) {
        wefax_decoder_destroy(dec);
    }
}
