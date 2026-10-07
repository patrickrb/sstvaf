package com.k1af.ft8af.icom;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Regression coverage for {@link IcomAudioUdp}'s TX-audio path.
 *
 * <p>The old code held a single shared {@code DoTXAudioRunnable}, mutated its
 * {@code audioData} field on every {@link IcomAudioUdp#sendTxAudioData} call, and
 * re-{@code execute()}d that same instance on a cached thread pool. A second TX (or
 * Tune) firing before the first ~12.6&nbsp;s run finished ran the <em>same</em>
 * Runnable on two pool threads concurrently — stomping the shared PCM buffer and
 * racing the unsynchronised {@code innerSeq}, garbling Icom-over-Wi-Fi audio. It also
 * left the pooled worker's interrupt flag set on exit.
 *
 * <p>The fix snapshots PCM per call into a fresh runnable and guards with an
 * {@code AtomicBoolean} so an overlapping submission is dropped instead of racing.
 * These tests drive the {@code submitTransmit} seam and the guard directly — no
 * socket, plain JUnit + Truth (the only Android type touched is {@code Log}, stubbed
 * via {@code returnDefaultValues}).
 */
public class IcomAudioUdpTest {

    /** Captures submissions instead of running them, so the guard stays "in flight". */
    private static class RecordingIcomAudioUdp extends IcomAudioUdp {
        final List<short[]> submitted = new ArrayList<>();

        @Override
        void submitTransmit(short[] pcm) {
            submitted.add(pcm);
        }
    }

    /**
     * Adds a count of how many times the PCM conversion actually ran, so the tests
     * can pin that an overlapping (dropped) request never pays for it, and can force
     * the conversion to fail.
     */
    private static class CountingIcomAudioUdp extends RecordingIcomAudioUdp {
        int conversions = 0;
        Error conversionFailure = null;

        @Override
        short[] convertForTransmit(float[] audioData) {
            conversions++;
            if (conversionFailure != null) {
                throw conversionFailure;
            }
            return super.convertForTransmit(audioData);
        }
    }

    // ---- floatToPcm16 conversion ----

    @Test
    public void floatToPcm16_clampsAndScales() {
        short[] out = IcomAudioUdp.floatToPcm16(new float[] {0f, 1.0f, -1.0f, 0.5f, 2.0f, -3.0f});

        assertThat(out.length).isEqualTo(6);
        assertThat(out[0]).isEqualTo((short) 0);
        assertThat(out[1]).isEqualTo((short) 32767);     // +full scale
        assertThat(out[2]).isEqualTo((short) -32767);    // -full scale (clamped to -1.0)
        assertThat(out[3]).isEqualTo((short) 16383);     // 0.5 * 32767 truncated
        assertThat(out[4]).isEqualTo((short) 32767);     // >1.0 clamped, not overflowed
        assertThat(out[5]).isEqualTo((short) -32767);    // <-1.0 clamped, not overflowed
    }

    @Test
    public void floatToPcm16_emptyInput_returnsEmpty() {
        assertThat(IcomAudioUdp.floatToPcm16(new float[0])).hasLength(0);
    }

    // ---- overlap guard ----

    @Test
    public void sendTxAudioData_dropsOverlappingRequestWhileTransmitting() {
        RecordingIcomAudioUdp rig = new RecordingIcomAudioUdp();

        rig.sendTxAudioData(new float[10]);
        // First call acquired the guard and submitted exactly one transmission.
        assertThat(rig.submitted).hasSize(1);
        assertThat(rig.transmitting.get()).isTrue();
        assertThat(rig.submitted.get(0)).hasLength(10);

        // A second call while the first is still "in flight" must be dropped — not
        // submitted as a concurrent runnable that would garble the shared stream.
        rig.sendTxAudioData(new float[10]);
        assertThat(rig.submitted).hasSize(1);

        // Once the running transmission releases the guard, the next TX starts again.
        rig.transmitting.set(false);
        rig.sendTxAudioData(new float[10]);
        assertThat(rig.submitted).hasSize(2);
    }

    @Test
    public void sendTxAudioData_droppedRequestSkipsTheConversionEntirely() {
        // The guard is claimed before the PCM conversion, so an overlapping request
        // costs nothing: no full-buffer short[] allocation and no per-sample loop for
        // audio that is about to be thrown away.
        CountingIcomAudioUdp rig = new CountingIcomAudioUdp();

        rig.sendTxAudioData(new float[10]);   // claims the guard, converts once
        assertThat(rig.conversions).isEqualTo(1);
        assertThat(rig.transmitting.get()).isTrue();

        rig.sendTxAudioData(new float[10]);   // overlapping: dropped before converting
        assertThat(rig.submitted).hasSize(1);
        assertThat(rig.conversions).isEqualTo(1);

        // ...and once the slot frees up, the next TX converts again as normal.
        rig.transmitting.set(false);
        rig.sendTxAudioData(new float[10]);
        assertThat(rig.conversions).isEqualTo(2);
    }

    @Test
    public void sendTxAudioData_conversionFailureReleasesTheGuard() {
        // If the conversion blows up (OOM on the ~12.6 s buffer is the realistic
        // case) we still hold the guard and never submitted. Leaving it latched
        // would block every future transmission for the life of the process.
        CountingIcomAudioUdp rig = new CountingIcomAudioUdp();
        rig.conversionFailure = new OutOfMemoryError("simulated");

        try {
            rig.sendTxAudioData(new float[10]);
            throw new AssertionError("expected the conversion failure to propagate");
        } catch (OutOfMemoryError expected) {
            // propagated to the caller, as before
        }

        assertThat(rig.submitted).isEmpty();
        assertThat(rig.transmitting.get()).isFalse();

        // A later TX still works.
        rig.conversionFailure = null;
        rig.sendTxAudioData(new float[10]);
        assertThat(rig.submitted).hasSize(1);
    }

    @Test
    public void sendTxAudioData_nullAudio_isNoOpAndLeavesGuardFree() {
        RecordingIcomAudioUdp rig = new RecordingIcomAudioUdp();

        rig.sendTxAudioData(null);

        assertThat(rig.submitted).isEmpty();
        assertThat(rig.transmitting.get()).isFalse();
    }

    @Test
    public void sendTxAudioData_dropDoesNotAdvanceSequence() {
        RecordingIcomAudioUdp rig = new RecordingIcomAudioUdp();
        short seqBefore = rig.innerSeq;

        rig.sendTxAudioData(new float[10]);   // acquires guard, no packets sent by the stub
        rig.sendTxAudioData(new float[10]);   // dropped

        // Neither the acquired-but-stubbed submit nor the dropped call touches innerSeq.
        assertThat(rig.innerSeq).isEqualTo(seqBefore);
    }

    // ---- RX: real-time delivery gate over tracked audio packets ----

    /**
     * Fake-socket RX rig, same pattern as {@link IcomUdpBaseRetransmitTest}:
     * outbound packets are recorded instead of sent, and every audio payload
     * handed to {@code OnReceivedAudioData} is captured.
     */
    private static class RecordingRxIcomAudioUdp extends IcomAudioUdp {
        final List<byte[]> sentPackets = new ArrayList<>();
        final List<byte[]> deliveredAudio = new ArrayList<>();

        RecordingRxIcomAudioUdp() {
            localId = 0x11223344;
            remoteId = 0x55667788;
            onStreamEvents = audioCapture(deliveredAudio);
        }

        @Override
        public synchronized void sendUntrackedPacket(byte[] data) {
            sentPackets.add(data);
        }
    }

    /** OnStreamEvents stub that only captures delivered audio payloads. */
    private static IcomUdpBase.OnStreamEvents audioCapture(final List<byte[]> deliveredAudio) {
        return new IcomUdpBase.OnStreamEvents() {
            @Override
            public void OnReceivedIAmHere(byte[] data) {
            }

            @Override
            public void OnReceivedCivData(byte[] data) {
            }

            @Override
            public void OnReceivedAudioData(byte[] audioData) {
                deliveredAudio.add(audioData);
            }

            @Override
            public void OnUdpSendIOException(IcomUdpBase.IcomUdpStyle style, java.io.IOException e) {
            }

            @Override
            public void OnLoginResponse(boolean authIsOK) {
            }
        };
    }

    /** A tracked audio packet as the rig would send it (type=0x00, given header seq). */
    private static byte[] rigAudioPacket(short seq, byte marker) {
        byte[] audio = new byte[]{marker, (byte) (marker + 1)};
        return IComPacketTypes.AudioPacket.getTxAudioPacket(
                audio, seq, 0x55667788, 0x11223344, (short) 0);
    }

    private static List<Byte> firstBytes(List<byte[]> payloads) {
        List<Byte> markers = new ArrayList<>();
        for (byte[] payload : payloads) {
            markers.add(payload[0]);
        }
        return markers;
    }

    @Test
    public void rxAudio_inOrderTrackedPacketsAreAllDelivered() {
        RecordingRxIcomAudioUdp rig = new RecordingRxIcomAudioUdp();

        rig.onDataReceived(null, rigAudioPacket((short) 1, (byte) 10));
        rig.onDataReceived(null, rigAudioPacket((short) 2, (byte) 20));
        rig.onDataReceived(null, rigAudioPacket((short) 3, (byte) 30));

        assertThat(firstBytes(rig.deliveredAudio))
                .containsExactly((byte) 10, (byte) 20, (byte) 30).inOrder();
        assertThat(rig.sentPackets).isEmpty();
    }

    @Test
    public void rxAudio_lateOriginalIsDroppedNotInjectedLate() {
        RecordingRxIcomAudioUdp rig = new RecordingRxIcomAudioUdp();

        rig.onDataReceived(null, rigAudioPacket((short) 1, (byte) 10));
        rig.onDataReceived(null, rigAudioPacket((short) 3, (byte) 30)); // 2 lost
        // Seq 2 shows up after all (UDP reordering or a retransmitted copy):
        // its play-out moment has passed — injecting it now would garble the
        // stream worse than the gap did, so it must be dropped.
        rig.onDataReceived(null, rigAudioPacket((short) 2, (byte) 20));

        assertThat(firstBytes(rig.deliveredAudio))
                .containsExactly((byte) 10, (byte) 30).inOrder();
    }

    @Test
    public void rxAudio_duplicateIsDeliveredOnlyOnce() {
        RecordingRxIcomAudioUdp rig = new RecordingRxIcomAudioUdp();
        byte[] packet = rigAudioPacket((short) 5, (byte) 50);

        rig.onDataReceived(null, packet);
        rig.onDataReceived(null, packet);

        assertThat(rig.deliveredAudio).hasSize(1);
    }

    @Test
    public void rxAudio_wrapBoundaryNewerPacketIsDelivered() {
        RecordingRxIcomAudioUdp rig = new RecordingRxIcomAudioUdp();

        rig.onDataReceived(null, rigAudioPacket((short) 0xFFFF, (byte) 10));
        // 0x0001 is a small forward step across the 16-bit wrap — newer, not
        // "65534 packets old"; the gate must be wrap-aware and deliver it.
        rig.onDataReceived(null, rigAudioPacket((short) 0x0001, (byte) 20));

        assertThat(firstBytes(rig.deliveredAudio))
                .containsExactly((byte) 10, (byte) 20).inOrder();
        // ...and the old pre-wrap sequence is now stale.
        rig.onDataReceived(null, rigAudioPacket((short) 0xFFFE, (byte) 30));
        assertThat(rig.deliveredAudio).hasSize(2);
    }

    @Test
    public void rxAudio_untrackedSeqZeroPacketsAlwaysPassAndDoNotMoveTheGate() {
        RecordingRxIcomAudioUdp rig = new RecordingRxIcomAudioUdp();

        rig.onDataReceived(null, rigAudioPacket((short) 0, (byte) 10)); // untracked
        rig.onDataReceived(null, rigAudioPacket((short) 7, (byte) 20));
        rig.onDataReceived(null, rigAudioPacket((short) 0, (byte) 30)); // untracked, still passes
        rig.onDataReceived(null, rigAudioPacket((short) 6, (byte) 40)); // late tracked: dropped

        assertThat(firstBytes(rig.deliveredAudio))
                .containsExactly((byte) 10, (byte) 20, (byte) 30).inOrder();
    }

    @Test
    public void rxAudio_gapSendsNoRetransmitRequest() {
        // Audio is real time: a retransmission would arrive after its play-out
        // moment, so the audio stream never asks for one (a control/CI-V
        // stream with the same gap does — see IcomUdpBaseRetransmitTest).
        RecordingRxIcomAudioUdp rig = new RecordingRxIcomAudioUdp();

        rig.onDataReceived(null, rigAudioPacket((short) 1, (byte) 10));
        rig.onDataReceived(null, rigAudioPacket((short) 4, (byte) 40)); // 2 and 3 lost

        assertThat(rig.sentPackets).isEmpty();
        // The stream itself keeps flowing: the newer packet is delivered.
        assertThat(firstBytes(rig.deliveredAudio))
                .containsExactly((byte) 10, (byte) 40).inOrder();
    }

    @Test
    public void rxAudio_xieguAudioStreamSharesTheSameGate() {
        // XieGuAudioUdp delivers audio through the same AudioUdp gate: dups
        // are dropped and a gap triggers no retransmit request (a request
        // would NPE here on the absent socket, failing the test).
        XieGuAudioUdp rig = new XieGuAudioUdp();
        List<byte[]> delivered = new ArrayList<>();
        rig.onStreamEvents = audioCapture(delivered);

        rig.onDataReceived(null, rigAudioPacket((short) 1, (byte) 10));
        rig.onDataReceived(null, rigAudioPacket((short) 1, (byte) 10)); // duplicate
        rig.onDataReceived(null, rigAudioPacket((short) 3, (byte) 30)); // gap over 2

        assertThat(firstBytes(delivered))
                .containsExactly((byte) 10, (byte) 30).inOrder();
    }

    @Test
    public void rxAudio_halfRangeForwardDistanceStillCountsAsNewer() {
        // The wrap-aware rule: forward distance <= 0x8000 is newer. Pin the
        // boundary on both sides.
        AudioUdp gate = new AudioUdp();
        assertThat(gate.shouldDeliverAudioSeq((short) 0x0001)).isTrue();
        assertThat(gate.shouldDeliverAudioSeq((short) 0x8001)).isTrue();  // +0x8000: newer
        assertThat(gate.shouldDeliverAudioSeq((short) 0x0002)).isFalse(); // +0x8001: older
    }

    // ---- guard is released when the real transmission finishes ----

    @Test
    public void realRun_releasesGuardWhenPttOff() throws Exception {
        // Use the real submitTransmit + cached pool. With PTT off, the runnable's
        // for-loop breaks before any sendTrackedPacket (so no socket is needed) and
        // the finally block must clear the guard so a later TX can start.
        IcomAudioUdp rig = new IcomAudioUdp();
        rig.isPttOn = false;

        rig.sendTxAudioData(new float[240]);

        long deadline = System.currentTimeMillis() + 2000;
        while (rig.transmitting.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(rig.transmitting.get()).isFalse();
    }
}
