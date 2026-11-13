package com.k1af.ft8af.icom;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Coverage for the inbound half of {@link IcomUdpBase}'s retransmit machinery
 * (the long-standing rxSeqBuffer TODOs), plus a pin of the existing outbound
 * half (rig asks us → we answer from txSeqBuffer).
 *
 * <p>Same fake pattern as {@link IcomAudioUdpTest}: the socket seam
 * ({@code sendUntrackedPacket}) is overridden to record packets, and
 * {@code onDataReceived} is fed hand-built datagrams — no network, plain
 * JUnit + Truth ({@code returnDefaultValues} covers {@code Log}).
 */
public class IcomUdpBaseRetransmitTest {

    private static final int LOCAL_ID = 0x11223344;
    private static final int REMOTE_ID = 0x55667788;

    /** Records every outbound packet instead of touching a socket. */
    private static class RecordingUdp extends IcomUdpBase {
        final List<byte[]> sent = new ArrayList<>();

        RecordingUdp() {
            localId = LOCAL_ID;
            remoteId = REMOTE_ID;
        }

        @Override
        public synchronized void sendUntrackedPacket(byte[] data) {
            sent.add(data);
        }
    }

    /** A tracked data packet (type=0x00, given seq) with a CI-V style payload. */
    private static byte[] trackedDataPacket(short seq) {
        return IComPacketTypes.CivPacket.setCivData(seq, REMOTE_ID, LOCAL_ID
                , (short) 0, new byte[]{(byte) 0xfe, (byte) 0xfd});
    }

    // ---- rxSeqBuffer: buffering inbound tracked packets ----

    @Test
    public void trackedDataPacketIsBufferedBySequence() {
        RecordingUdp udp = new RecordingUdp();
        byte[] packet = trackedDataPacket((short) 7);

        udp.onDataReceived(null, packet);

        assertThat(udp.rxSeqBuffer.get((short) 7)).isEqualTo(packet);
        assertThat(udp.sent).isEmpty(); // in-sequence: nothing to request
    }

    @Test
    public void pingAndUntrackedPacketsAreNotBuffered() {
        RecordingUdp udp = new RecordingUdp();

        // A ping reply (type=0x07) — its seq lives in a separate ping sequence.
        byte[] pingReply = IComPacketTypes.PingPacket.sendReplayPingData(
                IComPacketTypes.PingPacket.sendPingData(REMOTE_ID, LOCAL_ID, (short) 9)
                , REMOTE_ID, LOCAL_ID);
        udp.onDataReceived(null, pingReply);
        assertThat(udp.rxSeqBuffer.get((short) 9)).isNull();

        // An untracked control packet (seq=0) is not part of the tracked stream.
        udp.onDataReceived(null, IComPacketTypes.ControlPacket.idlePacketData(
                (short) 0, REMOTE_ID, LOCAL_ID));
        assertThat(udp.rxSeqBuffer.get((short) 0)).isNull();
        assertThat(udp.sent).isEmpty();
    }

    // ---- gap detection: we ask the rig to retransmit what we missed ----

    @Test
    public void singleGapSendsOneRetransmitRequest() {
        RecordingUdp udp = new RecordingUdp();
        udp.onDataReceived(null, trackedDataPacket((short) 1));
        udp.onDataReceived(null, trackedDataPacket((short) 3)); // 2 was lost

        assertThat(udp.sent).hasSize(1);
        byte[] request = udp.sent.get(0);
        assertThat(request.length).isEqualTo(IComPacketTypes.CONTROL_SIZE);
        assertThat(IComPacketTypes.ControlPacket.getType(request))
                .isEqualTo(IComPacketTypes.CMD_RETRANSMIT);
        assertThat(IComPacketTypes.ControlPacket.getSeq(request)).isEqualTo((short) 2);
        assertThat(IComPacketTypes.ControlPacket.getSentId(request)).isEqualTo(LOCAL_ID);
        assertThat(IComPacketTypes.ControlPacket.getRcvdId(request)).isEqualTo(REMOTE_ID);
    }

    @Test
    public void widerGapSendsOneMultiRetransmitRequest() {
        RecordingUdp udp = new RecordingUdp();
        udp.onDataReceived(null, trackedDataPacket((short) 1));
        udp.onDataReceived(null, trackedDataPacket((short) 5)); // 2,3,4 lost

        assertThat(udp.sent).hasSize(1);
        byte[] request = udp.sent.get(0);
        // Multi shape: control header (type=0x01) + big-endian short array —
        // the same shape retransmitMultiPacket() parses from the rig.
        assertThat(request.length).isEqualTo(IComPacketTypes.CONTROL_SIZE + 3 * 2);
        assertThat(IComPacketTypes.ControlPacket.getType(request))
                .isEqualTo(IComPacketTypes.CMD_RETRANSMIT);
        assertThat(IComPacketTypes.readShortBigEndianData(request, 0x10)).isEqualTo((short) 2);
        assertThat(IComPacketTypes.readShortBigEndianData(request, 0x12)).isEqualTo((short) 3);
        assertThat(IComPacketTypes.readShortBigEndianData(request, 0x14)).isEqualTo((short) 4);
    }

    @Test
    public void multiRequestRoundTrip_peerAnswersFromItsTxBuffer() {
        // Our multi request must be understood by the same parser the app uses
        // for the rig's requests — the protocol is symmetric.
        RecordingUdp receiver = new RecordingUdp();
        receiver.onDataReceived(null, trackedDataPacket((short) 1));
        receiver.onDataReceived(null, trackedDataPacket((short) 4)); // 2,3 lost
        byte[] request = receiver.sent.get(0);

        RecordingUdp sender = new RecordingUdp();
        byte[] packet2 = trackedDataPacket((short) 2);
        byte[] packet3 = trackedDataPacket((short) 3);
        sender.txSeqBuffer.add((short) 2, packet2);
        sender.txSeqBuffer.add((short) 3, packet3);

        sender.onDataReceived(null, request);

        assertThat(sender.sent).containsExactly(packet2, packet3).inOrder();
    }

    @Test
    public void lateArrivalAfterRequestIsBufferedWithoutANewRequest() {
        RecordingUdp udp = new RecordingUdp();
        udp.onDataReceived(null, trackedDataPacket((short) 1));
        udp.onDataReceived(null, trackedDataPacket((short) 3));
        assertThat(udp.sent).hasSize(1); // request for 2

        byte[] late = trackedDataPacket((short) 2);
        udp.onDataReceived(null, late); // the retransmission arrives

        assertThat(udp.rxSeqBuffer.get((short) 2)).isEqualTo(late);
        assertThat(udp.sent).hasSize(1); // no additional request
    }

    // ---- pin of the existing outbound retransmit reply (rig asks us) ----

    @Test
    public void rigRetransmitRequestIsAnsweredFromTxHistory() {
        RecordingUdp udp = new RecordingUdp();
        byte[] history = trackedDataPacket((short) 9);
        udp.txSeqBuffer.add((short) 9, history);

        udp.onDataReceived(null, IComPacketTypes.ControlPacket.toBytes(
                IComPacketTypes.CMD_RETRANSMIT, (short) 9, REMOTE_ID, LOCAL_ID));

        assertThat(udp.sent).containsExactly(history);
    }

    @Test
    public void rigRetransmitRequestForPurgedSeqGetsAnIdleSubstitute() {
        RecordingUdp udp = new RecordingUdp();

        udp.onDataReceived(null, IComPacketTypes.ControlPacket.toBytes(
                IComPacketTypes.CMD_RETRANSMIT, (short) 9, REMOTE_ID, LOCAL_ID));

        assertThat(udp.sent).hasSize(1);
        byte[] substitute = udp.sent.get(0);
        assertThat(IComPacketTypes.ControlPacket.getType(substitute))
                .isEqualTo(IComPacketTypes.CMD_NULL);
        assertThat(IComPacketTypes.ControlPacket.getSeq(substitute)).isEqualTo((short) 9);
    }

    // ---- requestRetransmit edge cases ----

    @Test
    public void requestRetransmitWithNothingMissingSendsNothing() {
        RecordingUdp udp = new RecordingUdp();
        udp.requestRetransmit(null);
        udp.requestRetransmit(new ArrayList<Short>());
        assertThat(udp.sent).isEmpty();
    }
}
