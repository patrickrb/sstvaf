package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.database.ControlMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Golden byte-vector coverage for {@link KenwoodTS440Rig}. The TS-440 is the
 * TS-570 command set under another name (the driver only overrides
 * {@code getName()}): 11-digit {@code FA...;} frequency, plain
 * {@code TX;}/{@code RX;} PTT and {@code MD2;} for USB. These tests pin that
 * the inherited byte vectors really are the TS-570 ones.
 *
 * <p>Robolectric because the inherited constructor posts on the main
 * {@code Looper}.
 */
@RunWith(RobolectricTestRunner.class)
public class KenwoodTS440RigTest {

    private KenwoodTS440Rig rig;
    private RecordingRigConnector connector;

    @Before
    public void setUp() {
        rig = new KenwoodTS440Rig();
        connector = new RecordingRigConnector();
        rig.setConnector(connector);
    }

    @After
    public void tearDown() {
        rig.onDisconnecting();
    }

    @Test
    public void getName_isTs440ButBehaviourIsTs570() {
        assertThat(rig.getName()).isEqualTo("KENWOOD TS-440");
    }

    @Test
    public void setFreqToRig_sendsElevenDigitZeroPaddedFa() {
        rig.setCommandedFreq(14_074_000L);
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA00014074000;");

        rig.setCommandedFreq(3_573_000L); // 80 m
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA00003573000;");
    }

    @Test
    public void setPtt_catMode_usesBareTxRxLikeTheTs570() {
        rig.setControlMode(ControlMode.CAT);
        rig.setPTT(true);
        assertThat(connector.lastPttCommandAscii()).isEqualTo("TX;");
        rig.setPTT(false);
        assertThat(connector.lastPttCommandAscii()).isEqualTo("RX;");
    }

    @Test
    public void modeAndPollCommands_matchGoldenAscii() {
        rig.setUsbModeToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("MD2;");
        rig.readFreqFromRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA;");
    }

    @Test
    public void onReceiveData_faReply_setsFrequency() {
        rig.onReceiveData("FA00003573000;".getBytes());
        assertThat(rig.getFreq()).isEqualTo(3_573_000L);
    }
}
