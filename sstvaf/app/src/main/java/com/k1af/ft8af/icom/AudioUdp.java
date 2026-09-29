package com.k1af.ft8af.icom;
/**
 * Base class for audio stream handling.
 * @author BGY70Z
 * @date 2023-08-26
 */

import android.util.Log;

import com.k1af.ft8af.GeneralVariables;

import java.net.DatagramPacket;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioUdp extends IcomUdpBase {
    private static final String TAG = "AudioUdp";

    public AudioUdp() {
        udpStyle = IcomUdpStyle.AudioUdp;
    }

    //Monotonic delivery gate over the tracked (header seq!=0) audio packets.
    //Guarded by the gate's own monitor via the synchronized method below.
    private boolean audioDeliveryStarted = false;
    private short highestDeliveredAudioSeq;

    /**
     * Audio is real time: a retransmitted packet arrives after its play-out
     * moment has passed, so asking the rig to resend lost audio only wastes
     * airtime. Losses stay what they always were — a short gap in the stream.
     */
    @Override
    protected boolean requestRetransmits() {
        return false;
    }

    /**
     * Decide whether a received audio packet with the given tracked (header)
     * sequence number may be handed to {@code OnReceivedAudioData}.
     *
     * <p>The base class buffers every tracked packet and (for non-audio
     * streams) requests retransmissions, so a late original, a duplicate, or a
     * solicited retransmission can still show up here out of order. Injecting
     * it into the live 12&nbsp;kHz stream late/twice garbles SSTV audio far
     * worse than the plain gap the loss caused, so only strictly newer packets
     * pass: the gate tracks the highest delivered sequence, 16-bit wrap-aware
     * (a forward distance of at most {@code 0x8000} counts as newer).
     * Untracked packets ({@code seq==0}) always pass and never move the gate.
     *
     * @param seq tracked sequence number from the packet header
     * @return true when the packet should be delivered
     */
    protected synchronized boolean shouldDeliverAudioSeq(short seq) {
        if (seq == 0) return true;//Untracked: outside the sequence stream
        if (!audioDeliveryStarted) {
            audioDeliveryStarted = true;
            highestDeliveredAudioSeq = seq;
            return true;
        }
        int distance = (seq - highestDeliveredAudioSeq) & 0xFFFF;//Forward 16-bit distance
        if (distance == 0 || distance > 0x8000) {
            return false;//Duplicate or older than something already delivered
        }
        highestDeliveredAudioSeq = seq;
        return true;
    }

    public void sendTxAudioData(float[] audioData){}
    public void startTxAudio(){}
    public void stopTXAudio(){}
}
