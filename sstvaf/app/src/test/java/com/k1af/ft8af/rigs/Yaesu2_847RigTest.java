package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.database.ControlMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Golden byte-vector coverage for {@link Yaesu2_847Rig}: Yaesu gen-2 5-byte
 * binary CAT blocks (4 BCD/parameter bytes + opcode). Set-frequency packs the
 * dial into 8 BCD digits with a 10 Hz LSB and opcode 0x01; PTT is opcode
 * 0x08/0x88; the 847 USB mode set is {@code 01 00 00 00 07}.
 *
 * <p>Reply parsing: a 5-byte block is a frequency read-back; a 2-byte block is
 * the FT-817-style 0xBD meter (ALC low nibble of byte 0, SWR high nibble of
 * byte 1). The 0xBD parse is inherited from FT8CN and is an FT-817/818
 * command, not documented FT-847 CAT — see the driver's own comment; these
 * tests pin the current mapping either way.
 *
 * <p>Robolectric because the reply path reaches
 * {@code GeneralVariables}/{@code ToastMessage} (Android types).
 */
@RunWith(RobolectricTestRunner.class)
public class Yaesu2_847RigTest {

    private Yaesu2_847Rig rig;
    private RecordingRigConnector connector;

    @Before
    public void setUp() {
        rig = new Yaesu2_847Rig();
        connector = new RecordingRigConnector();
        rig.setConnector(connector);
    }

    @After
    public void tearDown() {
        rig.onDisconnecting();
    }

    @Test
    public void setFreqToRig_sendsBcdBlockWithSetOpcode() {
        rig.setCommandedFreq(14_074_000L);
        rig.setFreqToRig();
        assertThat(connector.lastSent()).isEqualTo(new byte[]{
                (byte) 0x01, (byte) 0x40, (byte) 0x74, (byte) 0x00, (byte) 0x01});

        rig.setCommandedFreq(7_074_000L);
        rig.setFreqToRig();
        assertThat(connector.lastSent()).isEqualTo(new byte[]{
                (byte) 0x00, (byte) 0x70, (byte) 0x74, (byte) 0x00, (byte) 0x01});

        // Tens-of-Hz digit lands in the low nibble of the last BCD byte.
        rig.setCommandedFreq(144_174_050L);
        rig.setFreqToRig();
        assertThat(connector.lastSent()).isEqualTo(new byte[]{
                (byte) 0x14, (byte) 0x41, (byte) 0x74, (byte) 0x05, (byte) 0x01});
    }

    @Test
    public void setPtt_catMode_usesOpcode08And88() {
        rig.setControlMode(ControlMode.CAT);
        rig.setPTT(true);
        assertThat(connector.lastPttCommand()).isEqualTo(new byte[]{0, 0, 0, 0, (byte) 0x08});
        rig.setPTT(false);
        assertThat(connector.lastPttCommand()).isEqualTo(new byte[]{0, 0, 0, 0, (byte) 0x88});
        assertThat(connector.pttSignals).isEmpty();
    }

    @Test
    public void setPtt_dtrMode_togglesTheControlLine() {
        rig.setControlMode(ControlMode.DTR);
        rig.setPTT(true);
        assertThat(connector.pttSignals).containsExactly(true);
        assertThat(connector.pttCommands).isEmpty();
    }

    @Test
    public void setUsbModeToRig_sends847UsbModeBlock() {
        rig.setUsbModeToRig();
        assertThat(connector.lastSent()).isEqualTo(new byte[]{
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x07});
    }

    @Test
    public void readFreqFromRig_sendsReadOpcode03() {
        rig.readFreqFromRig();
        assertThat(connector.lastSent()).isEqualTo(new byte[]{0, 0, 0, 0, (byte) 0x03});
    }

    @Test
    public void onReceiveData_fiveByteReply_parsesBcdFrequency() {
        rig.onReceiveData(new byte[]{
                (byte) 0x01, (byte) 0x40, (byte) 0x74, (byte) 0x00, (byte) 0x01});
        assertThat(rig.getFreq()).isEqualTo(14_074_000L);
    }

    @Test
    public void onReceiveData_twoByteMeterReply_mapsNibblesAndNormalizes() {
        List<int[]> meter = new ArrayList<>();
        rig.setOnMeterData((alc, swr) -> meter.add(new int[]{alc, swr}));

        // byte0 low nibble = ALC (5), byte1 high nibble = SWR (4, ~2.25:1).
        rig.onReceiveData(new byte[]{(byte) 0x35, (byte) 0x40});

        assertThat(meter).hasSize(1);
        assertThat(meter.get(0)[0]).isEqualTo(5 * 17); // ALC nibble * 17
        assertThat(meter.get(0)[1]).isEqualTo(Yaesu2RigConstant.normalizeSwr817(4));
    }

    @Test
    public void onDisconnecting_sendsTheDisconnectBlock() {
        rig.onDisconnecting();
        assertThat(connector.lastSent()).isEqualTo(new byte[]{0, 0, 0, 0, (byte) 0x80});
    }
}
