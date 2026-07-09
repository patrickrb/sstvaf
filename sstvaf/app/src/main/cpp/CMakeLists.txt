# SSTVAF native module.
#
# Builds a single libsstvaf.so containing:
#   - The clean-room SSTV codec (sstv_lib: encoder + push-model decoder)
#     and its JNI glue (sstvaf_glue/sstv_encode_jni.cpp, sstv_decode_jni.cpp)
#   - USB Audio Class capture/playback (libusb-backed)
#   - Waterfall FFT display (kissfft + fft_display)
#   - Audio resampler JNI (rational resampler / FIR decimator headers)
#
# The Java-side System.loadLibrary("sstvaf") call sites (UsbAudioNative,
# FT8Resample, SpectrumFragment, NativeSstvCodec) were renamed together with
# this library; JNI symbol names are derived from the Java class names and are
# unaffected by the library name.
#
# Why libusb: Android's UsbRequest API silently fails to drive
# isochronous transfers on some kernels (notably automotive Android
# variants), throwing IllegalStateException on first queue. libusb
# uses USBDEVFS ioctls directly and is the most battle-tested option
# for USB audio on Android.

cmake_minimum_required(VERSION 3.18.1)

project(sstvaf_native)

# ---------------------------------------------------------------------------
# libusb 1.0.27 (static)
# ---------------------------------------------------------------------------
# Source list mirrors libusb/android/jni/libusb.mk — only the Linux/USBFS
# backend + POSIX threads/events; the macOS/Windows/Haiku/etc. backends in
# libusb/os/ are intentionally not compiled.

set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/libusb)

add_library(usb1.0 STATIC
        ${LIBUSB_ROOT}/libusb/core.c
        ${LIBUSB_ROOT}/libusb/descriptor.c
        ${LIBUSB_ROOT}/libusb/hotplug.c
        ${LIBUSB_ROOT}/libusb/io.c
        ${LIBUSB_ROOT}/libusb/sync.c
        ${LIBUSB_ROOT}/libusb/strerror.c
        ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
        ${LIBUSB_ROOT}/libusb/os/events_posix.c
        ${LIBUSB_ROOT}/libusb/os/threads_posix.c
        ${LIBUSB_ROOT}/libusb/os/linux_netlink.c
)

target_include_directories(usb1.0
        PUBLIC
        ${LIBUSB_ROOT}/libusb
        PRIVATE
        ${LIBUSB_ROOT}/android
        ${LIBUSB_ROOT}/libusb/os
)

# libusb assumes POSIX threads, eventfd, timerfd, etc. — all present on
# Android. Hidden visibility matches upstream's Android build so we don't
# accidentally re-export libusb symbols from our .so.
target_compile_options(usb1.0 PRIVATE
        -fvisibility=hidden
        -pthread
        -Wno-pointer-arith
        -Wno-deprecated-declarations
)

# ---------------------------------------------------------------------------
# sstv_dsp — the clean-room SSTV codec (pure C11, host-testable)
# ---------------------------------------------------------------------------
# Same sources the host test scripts (sstvaf_glue/run_sstv_host_tests.*)
# compile; keep the two lists in sync.

add_library(sstv_dsp STATIC
        sstv_lib/sstv_modes.c
        sstv_lib/sstv_vis.c
        sstv_lib/sstv_osc.c
        sstv_lib/sstv_color.c
        sstv_lib/sstv_encode.c
        sstv_lib/sstv_demod.c
        sstv_lib/sstv_decode.c
)

target_include_directories(sstv_dsp PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/sstv_lib
)

# ---------------------------------------------------------------------------
# libsstvaf.so — unified JNI bridge
# ---------------------------------------------------------------------------

add_library(sstvaf SHARED
        # USB audio
        usb_audio.cpp
        usb_audio_capture.cpp
        # kissfft (waterfall FFT)
        kissfft/kiss_fft.c
        kissfft/kiss_fftr.c
        # JNI wrappers — FFT display (waterfall)
        ft8af_glue/ft8_fft_jni.cpp
        ft8af_glue/fft_display.c
        # JNI wrappers — audio resampler
        ft8af_glue/ft8_resample_jni.cpp
        # JNI wrappers — SSTV codec (NativeSstvCodec)
        sstvaf_glue/sstv_encode_jni.cpp
        sstvaf_glue/sstv_decode_jni.cpp
)

target_include_directories(sstvaf PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/kissfft
)

find_library(log-lib log)

target_link_options(sstvaf PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(sstvaf
        usb1.0
        sstv_dsp
        m
        ${log-lib}
)
