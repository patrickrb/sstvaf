package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests {@link MicRecorder#startAfterReinit(boolean, boolean)} — whether a reinit starts
 * the rebuilt capture.
 *
 * <p>Regression: on first launch the capture starts before RECORD_AUDIO is granted, its
 * AudioRecord fails and the capture stops. A plain reinit only restarts a capture that was
 * running, so granting the permission left RX deaf until the next app launch. The grant
 * path forces the start.
 */
public class MicRecorderStartAfterReinitTest {

    @Test
    public void runningCaptureRestarts() {
        assertThat(MicRecorder.startAfterReinit(true, false)).isTrue();
    }

    @Test
    public void stoppedCaptureStaysStopped() {
        // USB attach/detach and channel-change reinits must not start a capture the
        // operator (or a LAN audio source) had stopped.
        assertThat(MicRecorder.startAfterReinit(false, false)).isFalse();
    }

    @Test
    public void permissionGrantStartsStoppedCapture() {
        assertThat(MicRecorder.startAfterReinit(false, true)).isTrue();
    }

    @Test
    public void permissionGrantOnRunningCaptureRestarts() {
        assertThat(MicRecorder.startAfterReinit(true, true)).isTrue();
    }
}
