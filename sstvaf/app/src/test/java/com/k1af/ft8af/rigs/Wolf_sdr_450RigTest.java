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
 * Golden byte-vector coverage for {@link Wolf_sdr_450Rig} (FT-450-compatible
 * CAT): 8-digit {@code FA...;} frequency, {@code TX1;}/{@code TX0;} PTT, and
 * a constructor flag choosing {@code MD02;} (USB, full power on Wolf SDR) vs
 * {@code MD0C;} (DIG-U).
 *
 * <p>Like {@link YaesuDX10Rig} — and unlike {@link Yaesu39Rig} — the RM meter
 * branch updates only the internal alert state and never calls
 * {@code notifyMeterData}, so MeterProtectionController receives nothing from
 * this driver; that current behaviour is pinned here.
 *
 * <p>Robolectric because the reply path reaches
 * {@code GeneralVariables}/{@code ToastMessage}.
 */
@RunWith(RobolectricTestRunner.class)
public class Wolf_sdr_450RigTest {

    private Wolf_sdr_450Rig rig;
    private RecordingRigConnector connector;

    @Before
    public void setUp() {
        rig = new Wolf_sdr_450Rig(true);
        connector = new RecordingRigConnector();
        rig.setConnector(connector);
    }

    @After
    public void tearDown() {
        rig.onDisconnecting();
    }

    @Test
    public void setFreqToRig_sendsEightDigitZeroPaddedFa() {
        rig.setCommandedFreq(14_074_000L);
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA14074000;");

        rig.setCommandedFreq(3_573_000L); // 80 m: leading zero at 8 digits
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA03573000;");

        rig.setCommandedFreq(28_074_000L); // 10 m
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA28074000;");
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
    public void setPtt_dtrMode_togglesTheControlLine() {
        rig.setControlMode(ControlMode.DTR);
        rig.setPTT(true);
        assertThat(connector.pttSignals).containsExactly(true);
        assertThat(connector.pttCommands).isEmpty();
    }

    @Test
    public void setUsbModeToRig_usbFlag_sendsMd02() {
        rig.setUsbModeToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("MD02;");
    }

    @Test
    public void setUsbModeToRig_digUFlag_sendsMd0C() {
        Wolf_sdr_450Rig digRig = new Wolf_sdr_450Rig(false);
        RecordingRigConnector digConnector = new RecordingRigConnector();
        digRig.setConnector(digConnector);
        try {
            digRig.setUsbModeToRig();
            assertThat(digConnector.lastSentAscii()).isEqualTo("MD0C;");
        } finally {
            digRig.onDisconnecting();
        }
    }

    @Test
    public void readFreqFromRig_sendsFaPoll() {
        rig.readFreqFromRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA;");
    }

    @Test
    public void onReceiveData_faAndFbReplies_setFrequency() {
        rig.onReceiveData("FA14074000;".getBytes());
        assertThat(rig.getFreq()).isEqualTo(14_074_000L);

        rig.onReceiveData("FB03573000;".getBytes());
        assertThat(rig.getFreq()).isEqualTo(3_573_000L);
    }

    @Test
    public void onReceiveData_meterReplies_neverReachTheMeterCallback() {
        // Pins the missing notifyMeterData forward (see class doc).
        List<int[]> meter = new ArrayList<>();
        rig.setOnMeterData((alc, swr) -> meter.add(new int[]{alc, swr}));

        rig.onReceiveData("RM4090000;RM6100000;".getBytes());

        assertThat(meter).isEmpty();
    }
}
