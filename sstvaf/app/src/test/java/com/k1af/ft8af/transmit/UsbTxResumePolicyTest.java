package com.k1af.ft8af.transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * The resume-or-abort arithmetic for a failed direct-USB TX write. A 2-minute
 * SSTV image must survive a transient mid-stream USB error by resuming at the
 * sample the device had clocked out, and only give up when consecutive
 * attempts stop making real progress.
 */
public class UsbTxResumePolicyTest {

    private static final int RATE = 12000;
    // A Scottie-1-sized image: ~110s at 12kHz.
    private static final int TOTAL = 110 * RATE;

    @Test
    public void midStreamFailure_resumesAtElapsedOffset() {
        // Died 2.5s in from the start of the buffer.
        UsbTxResumePolicy.Decision d =
                UsbTxResumePolicy.onWriteFailure(0, TOTAL, 2500, RATE, 0);

        assertThat(d.retry).isTrue();
        assertThat(d.treatAsComplete).isFalse();
        assertThat(d.nextOffsetSamples).isEqualTo(2500 * RATE / 1000);
        // 2.5s of streaming is real progress: the stalled tally resets.
        assertThat(d.stalledAttempts).isEqualTo(0);
    }

    @Test
    public void resumeOffset_accumulatesAcrossAttempts() {
        int offset = 30 * RATE; // already 30s in from earlier attempts
        UsbTxResumePolicy.Decision d =
                UsbTxResumePolicy.onWriteFailure(offset, TOTAL, 5000, RATE, 0);

        assertThat(d.nextOffsetSamples).isEqualTo(offset + 5 * RATE);
        assertThat(d.retry).isTrue();
    }

    @Test
    public void subSecondAttempts_countAsStalled_andGiveUpAtLimit() {
        UsbTxResumePolicy.Decision first =
                UsbTxResumePolicy.onWriteFailure(0, TOTAL, 300, RATE, 0);
        assertThat(first.stalledAttempts).isEqualTo(1);
        assertThat(first.retry).isTrue();

        UsbTxResumePolicy.Decision second = UsbTxResumePolicy.onWriteFailure(
                first.nextOffsetSamples, TOTAL, 200, RATE, first.stalledAttempts);
        assertThat(second.stalledAttempts).isEqualTo(2);
        assertThat(second.retry).isTrue();

        UsbTxResumePolicy.Decision third = UsbTxResumePolicy.onWriteFailure(
                second.nextOffsetSamples, TOTAL, 100, RATE, second.stalledAttempts);
        assertThat(third.stalledAttempts).isEqualTo(UsbTxResumePolicy.MAX_STALLED_ATTEMPTS);
        assertThat(third.retry).isFalse();
        assertThat(third.treatAsComplete).isFalse();
    }

    @Test
    public void realProgress_resetsTheStalledTally() {
        // Two stalled attempts, then one that streamed 4s before dying.
        UsbTxResumePolicy.Decision d =
                UsbTxResumePolicy.onWriteFailure(0, TOTAL, 4000, RATE, 2);

        assertThat(d.stalledAttempts).isEqualTo(0);
        assertThat(d.retry).isTrue();
    }

    @Test
    public void failureNearTheEnd_countsAsComplete() {
        // Died with 50ms of audio left — not worth another device reopen.
        int offset = TOTAL - RATE; // 1s left at attempt start
        long elapsed = 950; // streamed all but ~50ms of it
        UsbTxResumePolicy.Decision d =
                UsbTxResumePolicy.onWriteFailure(offset, TOTAL, elapsed, RATE, 0);

        assertThat(d.treatAsComplete).isTrue();
        assertThat(d.retry).isFalse();
    }

    @Test
    public void elapsedBeyondRemaining_clampsToBufferEnd() {
        // Clock says more streamed than the buffer holds (drain overhead):
        // never index past the end.
        int offset = TOTAL - 2 * RATE;
        UsbTxResumePolicy.Decision d =
                UsbTxResumePolicy.onWriteFailure(offset, TOTAL, 60_000, RATE, 0);

        assertThat(d.nextOffsetSamples).isEqualTo(TOTAL);
        assertThat(d.treatAsComplete).isTrue();
    }

    @Test
    public void negativeElapsed_treatedAsZero() {
        UsbTxResumePolicy.Decision d =
                UsbTxResumePolicy.onWriteFailure(0, TOTAL, -50, RATE, 0);

        assertThat(d.nextOffsetSamples).isEqualTo(0);
        assertThat(d.stalledAttempts).isEqualTo(1);
        assertThat(d.retry).isTrue();
    }
}
