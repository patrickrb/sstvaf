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
 * Golden byte-vector coverage for {@link YaesuDX10Rig} (FTDX10 family): Yaesu
 * gen-3 ASCII CAT with a 9-digit {@code FA...;} frequency,
 * {@code TX1;}/{@code TX0;} PTT, {@code MD0C;} (DATA-U) mode and
 * {@code AC002;} ATU start.
 *
 * <p>Two behaviours are pinned as-is but look suspicious next to the tested
 * siblings (do not "fix" the expectations without touching the driver):
 * <ul>
 *   <li>The RM parser uses the FT-991 "38" meter helpers, which require at
 *   least 7 data chars ({@code RM6125000;}), while the FTDX10's CAT spec
 *   answers {@code RM} with 3 value digits ({@code RM6125;}) — a real FTDX10
 *   reply is therefore ignored (parsed to 0/false).</li>
 *   <li>Unlike {@link Yaesu39Rig} and the Kenwood clones, the RM branch never
 *   calls {@code notifyMeterData}, so {@code MeterProtectionController}
 *   (ALC auto-level / SWR TX halt) receives nothing from this driver.</li>
 * </ul>
 *
 * <p>Robolectric because the reply path reaches
 * {@code GeneralVariables}/{@code ToastMessage}.
 */
@RunWith(RobolectricTestRunner.class)
public class YaesuDX10RigTest {

    private YaesuDX10Rig rig;
    private RecordingRigConnector connector;

    @Before
    public void setUp() {
        rig = new YaesuDX10Rig();
        connector = new RecordingRigConnector();
        rig.setConnector(connector);
    }

    @After
    public void tearDown() {
        rig.onDisconnecting();
    }

    @Test
    public void setFreqToRig_sendsNineDigitZeroPaddedFa() {
        rig.setCommandedFreq(14_074_000L);
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA014074000;");

        rig.setCommandedFreq(1_840_000L); // 160 m: longest zero pad at 9 digits
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA001840000;");

        rig.setCommandedFreq(50_313_000L); // 6 m
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA050313000;");
    }

    @Test
    public void setPtt_catMode_usesTx1Tx0() {
        rig.setControlMode(ControlMode.CAT);
        rig.setPTT(true);
        assertThat(connector.lastPttCommandAscii()).isEqualTo("TX1;");
        rig.setPTT(false);
        assertThat(connector.lastPttCommandAscii()).isEqualTo("TX0;");
    }

    @Test
    public void setPtt_rtsMode_togglesTheControlLine() {
        rig.setControlMode(ControlMode.RTS);
        rig.setPTT(true);
        assertThat(connector.pttSignals).containsExactly(true);
        assertThat(connector.pttCommands).isEmpty();
    }

    @Test
    public void modePollAndAtuCommands_matchGoldenAscii() {
        rig.setUsbModeToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("MD0C;");

        rig.readFreqFromRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA;");

        assertThat(rig.supportsAtuTune()).isTrue();
        rig.startAtuTune();
        assertThat(connector.lastSentAscii()).isEqualTo("AC002;");
    }

    @Test
    public void onReceiveData_faAndFbReplies_setFrequency() {
        rig.onReceiveData("FA014074000;".getBytes());
        assertThat(rig.getFreq()).isEqualTo(14_074_000L);

        rig.onReceiveData("FB050313000;".getBytes());
        assertThat(rig.getFreq()).isEqualTo(50_313_000L);
    }

    @Test
    public void onReceiveData_meterReplies_neverReachTheMeterCallback() {
        // Pins the missing notifyMeterData forward described in the class doc:
        // even a long-form "38"-layout reply that the parser DOES understand
        // updates only the internal alert state, never the protection callback.
        List<int[]> meter = new ArrayList<>();
        rig.setOnMeterData((alc, swr) -> meter.add(new int[]{alc, swr}));

        rig.onReceiveData("RM4090000;RM6100000;".getBytes());

        assertThat(meter).isEmpty();
    }

    @Test
    public void onReceiveData_shortFtdx10StyleMeterReply_isIgnoredByThe38Parser() {
        // A spec-format FTDX10 reply ("RM6125;", 4 data chars) is below the
        // 7-char minimum of the "38" helpers, so it parses to nothing; the
        // frequency must be untouched and no callback fires.
        List<int[]> meter = new ArrayList<>();
        rig.setOnMeterData((alc, swr) -> meter.add(new int[]{alc, swr}));

        rig.onReceiveData("RM6125;".getBytes());

        assertThat(meter).isEmpty();
        assertThat(rig.getFreq()).isEqualTo(0L);
    }
}
