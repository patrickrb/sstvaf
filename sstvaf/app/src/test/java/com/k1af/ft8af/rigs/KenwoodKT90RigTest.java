package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.database.ControlMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Golden byte-vector coverage for {@link KenwoodKT90Rig} (TK-90). Same ASCII
 * command set as the Kenwood clones but with the native TK-90 CR
 * ({@code "\r"}) terminator instead of {@code ';'}: {@code FA%011d\r}
 * frequency, {@code TX\r}/{@code RX\r} PTT, {@code MD2\r} USB, {@code FA\r}
 * poll. The reply parser likewise splits on CR.
 *
 * <p>Robolectric because the constructor posts on the main {@code Looper}.
 */
@RunWith(RobolectricTestRunner.class)
public class KenwoodKT90RigTest {

    private KenwoodKT90Rig rig;
    private RecordingRigConnector connector;

    @Before
    public void setUp() {
        rig = new KenwoodKT90Rig();
        connector = new RecordingRigConnector();
        rig.setConnector(connector);
    }

    @After
    public void tearDown() {
        rig.onDisconnecting();
    }

    @Test
    public void setFreqToRig_sendsElevenDigitFaWithCrTerminator() {
        rig.setCommandedFreq(14_074_000L);
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA00014074000\r");

        rig.setCommandedFreq(7_074_000L);
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA00007074000\r");
    }

    @Test
    public void setPtt_catMode_usesCrTerminatedTxRx() {
        rig.setControlMode(ControlMode.CAT);
        rig.setPTT(true);
        assertThat(connector.lastPttCommandAscii()).isEqualTo("TX\r");
        rig.setPTT(false);
        assertThat(connector.lastPttCommandAscii()).isEqualTo("RX\r");
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
        assertThat(connector.lastSentAscii()).isEqualTo("MD2\r");

        rig.readFreqFromRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA\r");
    }

    @Test
    public void onReceiveData_crTerminatedFaReply_setsFrequency() {
        rig.onReceiveData("FA00014074000\r".getBytes());
        assertThat(rig.getFreq()).isEqualTo(14_074_000L);
    }

    @Test
    public void onReceiveData_replySplitAcrossReads_reassembles() {
        rig.onReceiveData("FA000070".getBytes());
        assertThat(rig.getFreq()).isEqualTo(0L);
        rig.onReceiveData("74000\r".getBytes());
        assertThat(rig.getFreq()).isEqualTo(7_074_000L);
    }
}
