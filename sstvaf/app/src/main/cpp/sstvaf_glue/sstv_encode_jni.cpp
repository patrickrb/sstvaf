// SSTV encoder JNI wrappers — binds the clean-room codec's whole-buffer
// encoder (sstv_lib/sstv_encode.c) to Kotlin.
//
// Java side:
//   radio.ks3ckc.sstvaf.sstv.NativeSstvCodec (instance methods)
//
// Both entry points are thin: argument marshalling only, all validation and
// synthesis lives in the C codec (which rejects bad modes/dims/capacity with
// the SSTV_ERR_* negative codes that the Kotlin wrapper turns into
// exceptions). Array access uses Get/ReleaseFloatArrayElements /
// Get/ReleaseIntArrayElements, consistent with the existing glue
// (ft8_fft_jni.cpp).

#include <jni.h>
#include <stdint.h>

#include "sstv.h"  // guards its own C linkage

// int sstv_encode_num_samples(mode_id, sample_rate) — exact float sample
// count of a full transmission, or a negative SSTV_ERR_*.
extern "C" JNIEXPORT jint JNICALL
Java_radio_ks3ckc_sstvaf_sstv_NativeSstvCodec_nativeEncodeNumSamples(
        JNIEnv*, jobject, jint modeId, jint sampleRate)
{
    return sstv_encode_num_samples(modeId, sampleRate);
}

// int sstv_encode(mode_id, argb, w, h, rate, amplitude, out, capacity) —
// synthesize the whole transmission into out[]. Returns samples written or a
// negative SSTV_ERR_*. argb is 0xAARRGGBB row-major (Android's ARGB_8888
// pixel int layout), exactly the mode's native dimensions.
extern "C" JNIEXPORT jint JNICALL
Java_radio_ks3ckc_sstvaf_sstv_NativeSstvCodec_nativeEncode(
        JNIEnv* env, jobject, jint modeId, jintArray argb, jint width,
        jint height, jint sampleRate, jfloat amplitude, jfloatArray out)
{
    if (argb == nullptr || out == nullptr) {
        return SSTV_ERR_BAD_ARGS;
    }
    if (width <= 0 || height <= 0
            || env->GetArrayLength(argb) < (jsize)((int64_t)width * height)) {
        return SSTV_ERR_BAD_ARGS;
    }

    jsize capacity = env->GetArrayLength(out);

    jint* pixels = env->GetIntArrayElements(argb, nullptr);
    if (pixels == nullptr) {
        return SSTV_ERR_NOMEM;
    }
    jfloat* samples = env->GetFloatArrayElements(out, nullptr);
    if (samples == nullptr) {
        env->ReleaseIntArrayElements(argb, pixels, JNI_ABORT);
        return SSTV_ERR_NOMEM;
    }

    int written = sstv_encode(modeId, (const uint32_t*)pixels, width, height,
                              sampleRate, amplitude, samples, (int)capacity);

    // Commit the output only on success; the input is never written.
    env->ReleaseFloatArrayElements(out, samples, written > 0 ? 0 : JNI_ABORT);
    env->ReleaseIntArrayElements(argb, pixels, JNI_ABORT);
    return written;
}
