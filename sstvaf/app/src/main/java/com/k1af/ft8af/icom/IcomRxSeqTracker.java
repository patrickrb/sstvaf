package com.k1af.ft8af.icom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Tracks the sequence numbers of tracked packets received from the rig on one
 * UDP session and decides which missing sequence numbers should be requested
 * for retransmission.
 *
 * <p>The Icom Wi-Fi protocol numbers every tracked packet with a 16-bit
 * sequence that wraps. Each side keeps a short history of what it sent
 * ({@link IcomSeqBuffer}) and may ask the other side to resend a sequence it
 * missed (control packet {@code type=0x01}). The rig-to-app direction of that
 * machinery was a long-standing TODO; this class is the decision half — pure
 * bookkeeping, no sockets — so it can be unit tested. {@link IcomUdpBase}
 * owns the sending half.
 *
 * <p>Rules, kept deliberately conservative:
 * <ul>
 *   <li>The first sequence seen only initializes the expected counter.</li>
 *   <li>An in-order packet advances the counter and requests nothing.</li>
 *   <li>A packet behind the counter (late arrival, duplicate, or the rig
 *       honoring our retransmit request) requests nothing and clears the
 *       sequence from the outstanding-request set.</li>
 *   <li>A packet ahead of the counter yields the missing range — but each
 *       missing sequence is requested at most once, and a jump larger than
 *       {@link #MAX_GAP} is treated as a stream resync (rig rebooted, long
 *       stall, counter wrap after silence) rather than a flood of losses:
 *       nothing is requested and the counter snaps forward.</li>
 * </ul>
 */
public class IcomRxSeqTracker {

    /**
     * Largest gap treated as packet loss. Anything wider means the two sides
     * disagree about where the stream is (resync) — requesting thousands of
     * retransmits would only flood the link. 32 packets is several hundred ms
     * of even the densest (audio) stream.
     */
    public static final int MAX_GAP = 32;

    /** Safety cap on remembered outstanding requests (never re-request a seq twice). */
    static final int MAX_OUTSTANDING = 128;

    private boolean started = false;
    private short expected;//Next sequence number we expect from the rig
    private final HashSet<Short> outstanding = new HashSet<>();

    /**
     * Record one received tracked sequence number.
     *
     * @param seq sequence number from the packet header
     * @return the missing sequence numbers that should be requested from the
     * rig now (possibly empty, never null)
     */
    public synchronized List<Short> onSeqReceived(short seq) {
        List<Short> toRequest = new ArrayList<>();
        if (!started) {
            started = true;
            expected = (short) (seq + 1);
            return toRequest;
        }
        //Signed 16-bit distance handles wraparound: 0x7fff steps forward at most.
        short distance = (short) (seq - expected);
        if (distance < 0) {//Late, duplicate, or a retransmission we asked for
            outstanding.remove(seq);
            return toRequest;
        }
        if (distance > MAX_GAP) {//Stream resync, not loss — snap forward silently
            outstanding.clear();
            expected = (short) (seq + 1);
            return toRequest;
        }
        for (short missing = expected; missing != seq; missing++) {
            if (outstanding.size() >= MAX_OUTSTANDING) {
                outstanding.clear();//Degenerate link; forget history rather than grow forever
            }
            if (outstanding.add(missing)) {//Only request each sequence once
                toRequest.add(missing);
            }
        }
        expected = (short) (seq + 1);
        return toRequest;
    }

    /** Forget everything (new session). */
    public synchronized void reset() {
        started = false;
        outstanding.clear();
    }
}
