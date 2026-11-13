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
 * Golden byte-vector coverage for {@link KenwoodTS570Rig}: original-Kenwood
 * ASCII CAT. The TS-570 keys with plain {@code TX;}/{@code RX;} (no VFO digit,
 * unlike the TS-590's {@code TX1;}), sets frequency with an 11-digit
 * zero-padded {@code FA...;} and mode with {@code MD2;}.
 *
 * <p>Robolectric because the constructor posts on the main {@code Looper}.
 */
@RunWith(RobolectricTestRunner.class)
public class KenwoodTS570RigTest {

    private KenwoodTS570Rig rig;
    private RecordingRigConnector connector;

    @Before
    public void setUp() {
        rig = new KenwoodTS570Rig();
        connector = new RecordingRigConnector();
        rig.setConnector(connector);
    }

    @After
    public void tearDown() {
        rig.onDisconnecting();
    }

    @Test
    public void setFreqToRig_sendsElevenDigitZeroPaddedFa() {
        rig.setCommandedFreq(7_074_000L);
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA00007074000;");

        rig.setCommandedFreq(50_313_000L); // 6 m: leading digit pair changes
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA00050313000;");
    }

    @Test
    public void setPtt_catMode_usesBareTxRx() {
        rig.setControlMode(ControlMode.CAT);
        rig.setPTT(true);
        assertThat(connector.lastPttCommandAscii()).isEqualTo("TX;");
        rig.setPTT(false);
        assertThat(connector.lastPttCommandAscii()).isEqualTo("RX;");
    }

    @Test
    public void setPtt_rtsMode_togglesTheControlLine() {
        rig.setControlMode(ControlMode.RTS);
        rig.setPTT(true);
        assertThat(connector.pttSignals).containsExactly(true);
        assertThat(connector.pttCommands).isEmpty();
    }

    @Test
    public void modeAndPollCommands_matchGoldenAscii() {
        rig.setUsbModeToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("MD2;");

        rig.readFreqFromRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA;");

        // The TS-570 driver does not claim ATU support (BaseRig default).
        assertThat(rig.supportsAtuTune()).isFalse();
    }

    @Test
    public void onReceiveData_faReply_setsFrequency() {
        rig.onReceiveData("FA00007074000;".getBytes());
        assertThat(rig.getFreq()).isEqualTo(7_074_000L);
    }

    @Test
    public void onReceiveData_meterReplies_forwardTimes8Normalization() {
        List<int[]> meter = new ArrayList<>();
        rig.setOnMeterData((alc, swr) -> meter.add(new int[]{alc, swr}));

        rig.onReceiveData("RM10003;RM30012;".getBytes());

        assertThat(meter).hasSize(2);
        assertThat(meter.get(1)[0]).isEqualTo(96); // ALC 12 * 8
        assertThat(meter.get(1)[1]).isEqualTo(24); // SWR 3 * 8
    }
}
