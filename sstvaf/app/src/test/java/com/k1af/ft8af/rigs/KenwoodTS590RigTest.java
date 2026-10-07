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
 * Golden byte-vector coverage for {@link KenwoodTS590Rig}: 11-digit
 * {@code FA...;} frequency, {@code TX1;}/{@code RX;} PTT (TX1 = rear/data
 * PTT), {@code MD2;} USB mode, {@code AC111;} ATU start, and the TS-590 RM
 * meter reply layout.
 *
 * <p>Robolectric because the constructor posts on the main {@code Looper}.
 */
@RunWith(RobolectricTestRunner.class)
public class KenwoodTS590RigTest {

    private KenwoodTS590Rig rig;
    private RecordingRigConnector connector;

    @Before
    public void setUp() {
        rig = new KenwoodTS590Rig();
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

        rig.setCommandedFreq(28_074_000L); // 10 m
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA00028074000;");

        rig.setCommandedFreq(1_840_000L); // 160 m: longest zero pad
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA00001840000;");
    }

    @Test
    public void setPtt_catMode_keysWithTx1AndUnkeysWithRx() {
        rig.setControlMode(ControlMode.CAT);
        rig.setPTT(true);
        assertThat(connector.lastPttCommandAscii()).isEqualTo("TX1;");
        rig.setPTT(false);
        assertThat(connector.lastPttCommandAscii()).isEqualTo("RX;");
    }

    @Test
    public void setPtt_dtrMode_togglesTheControlLine() {
        rig.setControlMode(ControlMode.DTR);
        rig.setPTT(true);
        assertThat(connector.pttSignals).containsExactly(true);
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
        rig.onReceiveData("FA00028074000;".getBytes());
        assertThat(rig.getFreq()).isEqualTo(28_074_000L);
    }

    @Test
    public void onReceiveData_meterReplies_forwardTimes8Normalization() {
        List<int[]> meter = new ArrayList<>();
        rig.setOnMeterData((alc, swr) -> meter.add(new int[]{alc, swr}));

        // Coalesced SWR then ALC reply, TS-590 layout (selector at data index 0,
        // 4-digit value at 1..4); driver scales raw x8 onto the 0-255 scale.
        rig.onReceiveData("RM10005;RM30010;".getBytes());

        assertThat(meter).hasSize(2);
        assertThat(meter.get(1)[0]).isEqualTo(80); // ALC 10 * 8
        assertThat(meter.get(1)[1]).isEqualTo(40); // SWR 5 * 8
    }

    @Test
    public void onReceiveData_largeMeterValue_clampsAt255() {
        List<int[]> meter = new ArrayList<>();
        rig.setOnMeterData((alc, swr) -> meter.add(new int[]{alc, swr}));

        // Raw 40 * 8 = 320 would overflow the 0-255 scale; driver clamps.
        rig.onReceiveData("RM30040;".getBytes());

        assertThat(meter.get(0)[0]).isEqualTo(255);
    }
}
