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
 * Golden byte-vector coverage for {@link XieGuRig} (XieGu 6100 family):
 * Icom CI-V binary frames {@code FE FE <rig> E0 <cmd> ... FD} with
 * little-endian BCD frequency (10 digits, 1 Hz LSB). PTT is command
 * {@code 1C 00}, mode set is {@code 06}, ATU start is {@code 1C 01 02}.
 * Reply frames from the rig arrive as {@code FE FE E0 <rig> ... FD}.
 *
 * <p>Robolectric because the CI-V helpers log via {@code android.util.Log}
 * and the reply path reaches {@code GeneralVariables}/{@code ToastMessage}.
 */
@RunWith(RobolectricTestRunner.class)
public class XieGuRigTest {

    private static final int CIV = 0x70; // XieGu default CI-V address

    private XieGuRig rig;
    private RecordingRigConnector connector;

    @Before
    public void setUp() {
        rig = new XieGuRig(CIV);
        connector = new RecordingRigConnector();
        rig.setConnector(connector);
    }

    @After
    public void tearDown() {
        rig.onDisconnecting();
    }

    private static byte[] frame(int... unsigned) {
        byte[] out = new byte[unsigned.length];
        for (int i = 0; i < unsigned.length; i++) {
            out[i] = (byte) unsigned[i];
        }
        return out;
    }

    @Test
    public void setFreqToRig_sendsCommand05WithLittleEndianBcd() {
        rig.setCommandedFreq(14_074_000L);
        rig.setFreqToRig();
        assertThat(connector.lastSent()).isEqualTo(
                frame(0xFE, 0xFE, CIV, 0xE0, 0x05, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD));

        rig.setCommandedFreq(7_074_000L);
        rig.setFreqToRig();
        assertThat(connector.lastSent()).isEqualTo(
                frame(0xFE, 0xFE, CIV, 0xE0, 0x05, 0x00, 0x40, 0x07, 0x07, 0x00, 0xFD));

        rig.setCommandedFreq(28_500_000L); // exercises the 100 kHz BCD nibble
        rig.setFreqToRig();
        assertThat(connector.lastSent()).isEqualTo(
                frame(0xFE, 0xFE, CIV, 0xE0, 0x05, 0x00, 0x00, 0x50, 0x28, 0x00, 0xFD));
    }

    @Test
    public void setPtt_catMode_sendsCiv1cCommand() {
        rig.setControlMode(ControlMode.CAT);
        rig.setPTT(true);
        assertThat(connector.lastPttCommand()).isEqualTo(
                frame(0xFE, 0xFE, CIV, 0xE0, 0x1C, 0x00, 0x01, 0xFD));
        rig.setPTT(false);
        assertThat(connector.lastPttCommand()).isEqualTo(
                frame(0xFE, 0xFE, CIV, 0xE0, 0x1C, 0x00, 0x00, 0xFD));
        assertThat(connector.pttSignals).isEmpty();
    }

    @Test
    public void setPtt_rtsMode_togglesTheControlLine() {
        rig.setControlMode(ControlMode.RTS);
        rig.setPTT(true);
        assertThat(connector.pttSignals).containsExactly(true);
        assertThat(connector.pttCommands).isEmpty();
    }

    @Test
    public void modePollAndAtuCommands_matchGoldenFrames() {
        rig.setUsbModeToRig(); // USB = mode 01, FIL1
        assertThat(connector.lastSent()).isEqualTo(
                frame(0xFE, 0xFE, CIV, 0xE0, 0x06, 0x01, 0x01, 0xFD));

        rig.readFreqFromRig(); // command 03
        assertThat(connector.lastSent()).isEqualTo(
                frame(0xFE, 0xFE, CIV, 0xE0, 0x03, 0xFD));

        assertThat(rig.supportsAtuTune()).isTrue();
        rig.startAtuTune(); // 1C 01 02 = ATU tune start
        assertThat(connector.lastSent()).isEqualTo(
                frame(0xFE, 0xFE, CIV, 0xE0, 0x1C, 0x01, 0x02, 0xFD));
    }

    @Test
    public void onReceiveData_frequencyReply_setsFrequency() {
        rig.onReceiveData(
                frame(0xFE, 0xFE, 0xE0, CIV, 0x03, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD));
        assertThat(rig.getFreq()).isEqualTo(14_074_000L);
    }

    @Test
    public void onReceiveData_frequencyReplySplitAcrossReads_reassembles() {
        rig.onReceiveData(frame(0xFE, 0xFE, 0xE0, CIV, 0x03, 0x00, 0x40));
        assertThat(rig.getFreq()).isEqualTo(0L);
        rig.onReceiveData(frame(0x07, 0x07, 0x00, 0xFD));
        assertThat(rig.getFreq()).isEqualTo(7_074_000L);
    }

    @Test
    public void onReceiveData_frequencyOutsideXieGuRange_isRejected() {
        rig.onReceiveData(
                frame(0xFE, 0xFE, 0xE0, CIV, 0x03, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD));
        assertThat(rig.getFreq()).isEqualTo(14_074_000L);

        // 455 kHz is below the 500 kHz floor the driver enforces; dial unchanged.
        rig.onReceiveData(
                frame(0xFE, 0xFE, 0xE0, CIV, 0x03, 0x00, 0x50, 0x45, 0x00, 0x00, 0xFD));
        assertThat(rig.getFreq()).isEqualTo(14_074_000L);
    }

    @Test
    public void onReceiveData_meterReplies_decodeLittleEndianBcdAndNotify() {
        List<int[]> meter = new ArrayList<>();
        rig.setOnMeterData((alc, swr) -> meter.add(new int[]{alc, swr}));

        // SWR reply (15 12): two BCD bytes, XieGu little-endian -> 0x50 0x00 = 50.
        rig.onReceiveData(frame(0xFE, 0xFE, 0xE0, CIV, 0x15, 0x12, 0x50, 0x00, 0xFD));
        // ALC reply (15 13): 0x62 0x00 = 62 (inside the 36..84 no-alert window).
        rig.onReceiveData(frame(0xFE, 0xFE, 0xE0, CIV, 0x15, 0x13, 0x62, 0x00, 0xFD));

        assertThat(meter).hasSize(2);
        assertThat(meter.get(0)[1]).isEqualTo(50);  // SWR after first reply
        assertThat(meter.get(1)[0]).isEqualTo(62);  // ALC after second reply
        assertThat(meter.get(1)[1]).isEqualTo(50);  // SWR retained
    }
}
