package com.k1af.ft8af.transmit;

/**
 * Resume-or-abort decision for a failed direct-USB TX write.
 *
 * <p>The libusb write path inherited FT8's failure handling: any mid-stream
 * error more than ~1s in dropped the whole over, because a restarted FT8
 * message would land off the WSJT-X time grid and the QSO sequencer
 * self-corrects next cycle anyway. An SSTV image is a one-shot 36-268s
 * transmission with no next cycle — dropping it over a single transient USB
 * event (an alt-setting flip from Android routing a sound to the still-
 * registered UAC card, RFI into a marginal cable) costs the operator the
 * entire picture. A receiver, by contrast, re-syncs on every scan line, so a
 * brief dropout costs a few garbled lines and nothing more.
 *
 * <p>So instead of dropping, the caller estimates how much audio the device
 * clocked out (the device consumes samples at the negotiated rate, so
 * streaming time maps to samples within a frame), reopens the device (a fresh
 * open/alt-setting restores an endpoint torn down by the kernel driver), and
 * resumes from that offset. This class is the pure decision: where to resume,
 * and when to give up because the device is not actually streaming.
 *
 * <p>Pure and static so the arithmetic and the give-up rule are unit-tested
 * without USB hardware.
 */
final class UsbTxResumePolicy {

    /**
     * Consecutive attempts that each streamed less than {@link #MIN_PROGRESS_MS}
     * before giving up. Three sub-second attempts back to back mean the device
     * is not accepting audio at all (unplugged, endpoint unrecoverable), and
     * keeping the rig keyed on dead air any longer helps nobody.
     */
    static final int MAX_STALLED_ATTEMPTS = 3;

    /** An attempt that streamed at least this long counts as real progress. */
    static final long MIN_PROGRESS_MS = 1000;

    /**
     * Remaining audio at or below this is not worth another device reopen —
     * report the transmission complete. 100ms is well under one SSTV scan
     * line; no receiver renders the difference.
     */
    static final long COMPLETE_SLACK_MS = 100;

    /** What the caller should do after a failed write attempt. */
    static final class Decision {
        /** Reopen the device and resume from {@link #nextOffsetSamples}. */
        final boolean retry;
        /** So little audio remains that the transmission counts as complete. */
        final boolean treatAsComplete;
        /** Mono-sample offset into the original buffer to resume from. */
        final int nextOffsetSamples;
        /** Updated consecutive-stalled-attempt tally to carry forward. */
        final int stalledAttempts;

        Decision(boolean retry, boolean treatAsComplete, int nextOffsetSamples,
                 int stalledAttempts) {
            this.retry = retry;
            this.treatAsComplete = treatAsComplete;
            this.nextOffsetSamples = nextOffsetSamples;
            this.stalledAttempts = stalledAttempts;
        }
    }

    private UsbTxResumePolicy() {
    }

    /**
     * Decide the response to a failed write attempt.
     *
     * @param offsetSamples    mono-sample offset the failed attempt started at
     * @param totalSamples     total mono samples in the transmission
     * @param attemptElapsedMs how long the failed attempt streamed before dying
     * @param sampleRate       mono buffer sample rate in Hz
     * @param stalledAttempts  consecutive stalled attempts before this one
     */
    static Decision onWriteFailure(int offsetSamples, int totalSamples,
                                   long attemptElapsedMs, int sampleRate,
                                   int stalledAttempts) {
        if (attemptElapsedMs < 0) attemptElapsedMs = 0;
        long consumed = attemptElapsedMs * sampleRate / 1000;
        long remainingAtStart = totalSamples - offsetSamples;
        if (consumed > remainingAtStart) consumed = remainingAtStart;
        int nextOffset = offsetSamples + (int) consumed;

        long remainingMs = (long) (totalSamples - nextOffset) * 1000 / sampleRate;
        if (remainingMs <= COMPLETE_SLACK_MS) {
            return new Decision(false, true, nextOffset, stalledAttempts);
        }

        int newStalled = attemptElapsedMs >= MIN_PROGRESS_MS ? 0 : stalledAttempts + 1;
        boolean retry = newStalled < MAX_STALLED_ATTEMPTS;
        return new Decision(retry, false, nextOffset, newStalled);
    }
}
