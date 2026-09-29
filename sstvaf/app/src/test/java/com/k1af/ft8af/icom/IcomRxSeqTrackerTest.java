package com.k1af.ft8af.icom;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit coverage for {@link IcomRxSeqTracker} — the gap-detection half of the
 * inbound retransmit machinery that was a long-standing TODO in
 * {@link IcomUdpBase#onDataReceived}.
 */
public class IcomRxSeqTrackerTest {

    @Test
    public void firstSequenceOnlyInitializes() {
        IcomRxSeqTracker tracker = new IcomRxSeqTracker();
        // The session can join the stream at any sequence; nothing before the
        // first packet can be known to be lost.
        assertThat(tracker.onSeqReceived((short) 57)).isEmpty();
    }

    @Test
    public void inOrderPacketsRequestNothing() {
        IcomRxSeqTracker tracker = new IcomRxSeqTracker();
        tracker.onSeqReceived((short) 1);
        assertThat(tracker.onSeqReceived((short) 2)).isEmpty();
        assertThat(tracker.onSeqReceived((short) 3)).isEmpty();
    }

    @Test
    public void singleLostPacketIsRequested() {
        IcomRxSeqTracker tracker = new IcomRxSeqTracker();
        tracker.onSeqReceived((short) 1);
        assertThat(tracker.onSeqReceived((short) 3)).containsExactly((short) 2);
    }

    @Test
    public void multipleLostPacketsAreRequestedInOrder() {
        IcomRxSeqTracker tracker = new IcomRxSeqTracker();
        tracker.onSeqReceived((short) 10);
        assertThat(tracker.onSeqReceived((short) 14))
                .containsExactly((short) 11, (short) 12, (short) 13)
                .inOrder();
    }

    @Test
    public void lateArrivalOfARequestedPacketRequestsNothing() {
        IcomRxSeqTracker tracker = new IcomRxSeqTracker();
        tracker.onSeqReceived((short) 1);
        assertThat(tracker.onSeqReceived((short) 3)).containsExactly((short) 2);
        // Sequence 2 shows up after all — either plain UDP reordering or the
        // rig honoring our retransmit request. No further request.
        assertThat(tracker.onSeqReceived((short) 2)).isEmpty();
        // And the stream continues normally afterwards.
        assertThat(tracker.onSeqReceived((short) 4)).isEmpty();
    }

    @Test
    public void duplicatePacketRequestsNothing() {
        IcomRxSeqTracker tracker = new IcomRxSeqTracker();
        tracker.onSeqReceived((short) 1);
        tracker.onSeqReceived((short) 2);
        assertThat(tracker.onSeqReceived((short) 2)).isEmpty();
    }

    @Test
    public void sequenceWrapAroundIsAGapNotAResync() {
        IcomRxSeqTracker tracker = new IcomRxSeqTracker();
        tracker.onSeqReceived(Short.MAX_VALUE); // expected wraps to MIN_VALUE
        assertThat(tracker.onSeqReceived((short) (Short.MIN_VALUE + 1)))
                .containsExactly(Short.MIN_VALUE);
        assertThat(tracker.onSeqReceived((short) (Short.MIN_VALUE + 2))).isEmpty();
    }

    @Test
    public void hugeJumpIsTreatedAsResyncNotLoss() {
        IcomRxSeqTracker tracker = new IcomRxSeqTracker();
        tracker.onSeqReceived((short) 5);
        // A jump wider than MAX_GAP means the two sides disagree about where
        // the stream is; requesting thousands of retransmits would flood the
        // Wi-Fi link, so the tracker snaps forward silently instead.
        assertThat(tracker.onSeqReceived((short) (6 + IcomRxSeqTracker.MAX_GAP + 1))).isEmpty();
        // ...and tracking continues from the new position.
        assertThat(tracker.onSeqReceived((short) (6 + IcomRxSeqTracker.MAX_GAP + 2))).isEmpty();
        assertThat(tracker.onSeqReceived((short) (6 + IcomRxSeqTracker.MAX_GAP + 4)))
                .containsExactly((short) (6 + IcomRxSeqTracker.MAX_GAP + 3));
    }

    @Test
    public void maximumTolatedGapIsStillRequested() {
        IcomRxSeqTracker tracker = new IcomRxSeqTracker();
        tracker.onSeqReceived((short) 0);
        // Distance of exactly MAX_GAP is the widest run still treated as loss.
        assertThat(tracker.onSeqReceived((short) (1 + IcomRxSeqTracker.MAX_GAP)))
                .hasSize(IcomRxSeqTracker.MAX_GAP);
    }

    @Test
    public void outstandingSetIsCappedButRequestsStillReturned() {
        IcomRxSeqTracker tracker = new IcomRxSeqTracker();
        tracker.onSeqReceived((short) 0);
        // Drive enough max-width gaps to cross MAX_OUTSTANDING: the internal
        // memory is reset rather than growing forever, and every gap is still
        // reported to the caller.
        short seq = 0;
        for (int round = 0; round < 6; round++) {
            seq += IcomRxSeqTracker.MAX_GAP + 1;
            assertThat(tracker.onSeqReceived(seq)).hasSize(IcomRxSeqTracker.MAX_GAP);
        }
    }

    @Test
    public void resetForgetsTheSession() {
        IcomRxSeqTracker tracker = new IcomRxSeqTracker();
        tracker.onSeqReceived((short) 1);
        tracker.reset();
        // After reset the next sequence re-initializes — no phantom gap from
        // the previous session.
        assertThat(tracker.onSeqReceived((short) 500)).isEmpty();
    }
}
