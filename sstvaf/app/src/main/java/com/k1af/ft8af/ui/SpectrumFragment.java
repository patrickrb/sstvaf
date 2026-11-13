package com.k1af.ft8af.ui;

import android.util.Log;

/**
 * Native FFT bridge for the waterfall display.
 *
 * <p>Historically this was the legacy View-UI spectrum Fragment; the Compose
 * waterfall ({@code WaterfallScreen.FFTBridge}) calls through an instance of
 * this class because the JNI symbols in {@code ft8af_glue/ft8_fft_jni.cpp} are
 * bound to this exact class name
 * ({@code Java_com_k1af_ft8af_ui_SpectrumFragment_*}). The class name and the
 * native method names must therefore not change (see also proguard-rules.pro:
 * classes declaring native methods keep their names).
 */
public class SpectrumFragment {
    private static final String TAG = "SpectrumFragment";

    static {
        try {
            System.loadLibrary("sstvaf");
        } catch (UnsatisfiedLinkError e) {
            // JVM unit tests have no libsstvaf.so; the native methods throw if
            // actually invoked without it.
            Log.w(TAG, "sstvaf native library not loaded: " + e.getMessage());
        }
    }

    /** FFT with noise-reduction weighting (int16 samples). */
    public native void getFFTData(int[] data, int[] fftData);

    /** FFT with noise-reduction weighting (float samples). */
    public native void getFFTDataFloat(float[] data, int[] fftData);

    /** Raw FFT (int16 samples). */
    public native void getFFTDataRaw(int[] data, int[] fftData);

    /** Raw FFT (float samples). */
    public native void getFFTDataRawFloat(float[] data, int[] fftData);
}
