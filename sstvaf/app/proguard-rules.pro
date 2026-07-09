# ProGuard / R8 rules for the release build.
#
# Release is built with minifyEnabled true (shrink + obfuscate). The default
# proguard-android-optimize.txt already covers the common cases we rely on:
#   * classes that declare `native` methods keep their name + the native method
#     names (so the libsstvaf.so JNI symbol names Java_com_k1af_ft8af_* /
#     Java_radio_ks3ckc_sstvaf_* still resolve) — covers FT8Resample,
#     SpectrumFragment, UsbAudioNative, NativeSstvCodec.
#   * enum values()/valueOf() and Parcelable CREATOR fields.
# Everything below is the project-specific surface R8 cannot infer on its own:
# names the native layer (or reflection) looks up by string.

# Keep source/line metadata so the uploaded mapping.txt can deobfuscate crash
# stack traces back to real file + line numbers in Play Console, then hide the
# original source file name (it becomes "SourceFile" in the obfuscated build).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- JNI: callback methods invoked by name via GetMethodID(...) ---
# usb_audio_capture.cpp calls back into the AudioInputCallback by method name +
# signature. Keep the interface methods so implementers keep the same names.
-keepclassmembers interface com.k1af.ft8af.wave.UsbAudioNative$AudioInputCallback {
    void onAudioData(float[], int);
    void onCaptureStopped(int);
}

# --- JNI: the SSTV codec bridge ---
# The default rules already keep native-method-declaring classes, but the SSTV
# engine is silently unusable (RX never locks, TX throws) if these symbols are
# ever renamed or stripped, so pin them explicitly. Guarded by R8KeepRulesTest.
-keepclasseswithmembers class radio.ks3ckc.sstvaf.sstv.NativeSstvCodec {
    native <methods>;
}

# --- Reflection: USB serial driver discovery ---
# UsbSerialProber instantiates each driver via getConstructor(UsbDevice.class)
# .newInstance(...), and ProbeTable invokes the static getSupportedDevices()
# via getMethod("getSupportedDevices"). Both are reached only reflectively, so
# R8 would otherwise drop them as unused.
-keepclassmembers class * implements com.k1af.ft8af.serialport.UsbSerialDriver {
    public <init>(android.hardware.usb.UsbDevice);
    public static java.util.Map getSupportedDevices();
}

# --- Plain JARs without consumer rules: silence missing optional references ---
# commons-net / MPAndroidChart are used directly (no reflection on our types)
# but reference optional APIs R8 can't resolve; -dontwarn keeps the build from
# failing on those phantom references.
-dontwarn org.apache.commons.net.**
-dontwarn com.github.mikephil.charting.**
