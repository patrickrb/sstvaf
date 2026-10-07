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
 * Golden byte-vector coverage for {@link KenwoodTS2000Rig}: the exact ASCII
 * CAT bytes the driver hands the transport for set-frequency, PTT, mode and
 * poll commands, plus the FA/RM reply parsing. Kenwood CAT is ASCII with a
 * {@code ';'} terminator; the TS-2000 keys with {@code TX0;} (its one
 * difference from the TS-590's {@code TX1;}).
 *
 * <p>Robolectric because the constructor posts on the main {@code Looper} and
 * the reply path reaches {@code GeneralVariables}/{@code ToastMessage}.
 */
@RunWith(RobolectricTestRunner.class)
public class KenwoodTS2000RigTest {

    private KenwoodTS2000Rig rig;
    private RecordingRigConnector connector;

    @Before
    public void setUp() {
        rig = new KenwoodTS2000Rig();
        connector = new RecordingRigConnector();
        rig.setConnector(connector);
    }

    @After
    public void tearDown() {
        rig.onDisconnecting();
    }

    @Test
    public void setFreqToRig_sendsElevenDigitZeroPaddedFa() {
        rig.setCommandedFreq(14_074_000L);
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA00014074000;");

        rig.setCommandedFreq(1_840_000L); // 160 m: exercises the longest zero pad
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA00001840000;");

        rig.setCommandedFreq(145_500_000L); // VHF: fills a ninth digit
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA00145500000;");
    }

    @Test
    public void setPtt_catMode_keysWithTx0AndUnkeysWithRx() {
        rig.setControlMode(ControlMode.CAT);
        rig.setPTT(true);
        assertThat(connector.lastPttCommandAscii()).isEqualTo("TX0;");
        rig.setPTT(false);
        assertThat(connector.lastPttCommandAscii()).isEqualTo("RX;");
        assertThat(connector.pttSignals).isEmpty();
    }

    @Test
    public void setPtt_rtsAndDtrModes_toggleTheControlLineNotCat() {
        rig.setControlMode(ControlMode.RTS);
        rig.setPTT(true);
        rig.setControlMode(ControlMode.DTR);
        rig.setPTT(false);
        assertThat(connector.pttSignals).containsExactly(true, false).inOrder();
        assertThat(connector.pttCommands).isEmpty();
    }

    @Test
    public void modePollAndAtuCommands_matchGoldenAscii() {
        rig.setUsbModeToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("MD2;");

        rig.readFreqFromRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA;");

        assertThat(rig.supportsAtuTune()).isTrue();
        rig.startAtuTune();
        assertThat(connector.lastSentAscii()).isEqualTo("AC111;");
    }

    @Test
    public void onReceiveData_faReply_setsFrequency() {
        rig.onReceiveData("FA00014074000;".getBytes());
        assertThat(rig.getFreq()).isEqualTo(14_074_000L);
    }

    @Test
    public void onReceiveData_faReplySplitAcrossReads_reassembles() {
        rig.onReceiveData("FA000070".getBytes());
        assertThat(rig.getFreq()).isEqualTo(0L);
        rig.onReceiveData("74000;".getBytes());
        assertThat(rig.getFreq()).isEqualTo(7_074_000L);
    }

    @Test
    public void onReceiveData_coalescedMeterReplies_forwardTimes8Normalization() {
        List<int[]> meter = new ArrayList<>();
        rig.setOnMeterData((alc, swr) -> meter.add(new int[]{alc, swr}));

        // TS-590 RM layout: selector at data index 0 ('1'=SWR, '3'=ALC), 4-digit
        // value at 1..4. Both replies coalesce into one read; the driver scales
        // each raw value x8 onto the 0-255 meter scale.
        rig.onReceiveData("RM10005;RM30010;".getBytes());

        assertThat(meter).hasSize(2);
        assertThat(meter.get(1)[0]).isEqualTo(80); // ALC 10 * 8
        assertThat(meter.get(1)[1]).isEqualTo(40); // SWR 5 * 8
    }
}
