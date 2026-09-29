package com.k1af.ft8af.rigs;

import com.k1af.ft8af.connector.BaseRigConnector;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double for the rig-driver golden byte-vector tests: records every byte
 * vector a driver hands the transport ({@code sendData}), every CAT PTT
 * command ({@code setPttOn(byte[])}) and every hardware PTT line toggle
 * ({@code setPttOn(boolean)}), and reports the link as up so the drivers'
 * {@code isConnected()} gates pass.
 */
class RecordingRigConnector extends BaseRigConnector {
    final List<byte[]> sent = new ArrayList<>();
    final List<byte[]> pttCommands = new ArrayList<>();
    final List<Boolean> pttSignals = new ArrayList<>();

    RecordingRigConnector() {
        super(0);
    }

    @Override
    public synchronized void sendData(byte[] data) {
        sent.add(data.clone());
    }

    @Override
    public void setPttOn(byte[] command) {
        pttCommands.add(command.clone());
    }

    @Override
    public void setPttOn(boolean on) {
        pttSignals.add(on);
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    byte[] lastSent() {
        return sent.get(sent.size() - 1);
    }

    String lastSentAscii() {
        return new String(lastSent(), StandardCharsets.US_ASCII);
    }

    String sentAscii(int index) {
        return new String(sent.get(index), StandardCharsets.US_ASCII);
    }

    byte[] lastPttCommand() {
        return pttCommands.get(pttCommands.size() - 1);
    }

    String lastPttCommandAscii() {
        return new String(lastPttCommand(), StandardCharsets.US_ASCII);
    }
}
