package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.database.ControlMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Golden byte-vector coverage for {@link Yaesu39Rig} (FT-891): Yaesu gen-3
 * ASCII CAT with a 9-digit {@code FA...;} frequency, {@code TX1;}/{@code TX0;}
 * PTT, a two-command mode setup (mode + width), {@code AC002;} ATU start, and
 * the FT-891 ("39") RM meter layout forwarded raw to the meter callback.
 *
 * <p>Robolectric because the driver logs through
 * {@code GeneralVariables.getMainContext()} and {@code android.util.Log}.
 */
@RunWith(RobolectricTestRunner.class)
public class Yaesu39RigTest {

    private Yaesu39Rig rig;
    private RecordingRigConnector connector;

    @Before
    public void setUp() {
        rig = new Yaesu39Rig(false);
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

        rig.setCommandedFreq(7_074_000L);
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA007074000;");

        rig.setCommandedFreq(144_174_000L); // fills all nine digits
        rig.setFreqToRig();
        assertThat(connector.lastSentAscii()).isEqualTo("FA144174000;");
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
    public void setUsbModeToRig_ssb_sendsUsbModeThenMaxSsbWidth() {
        rig.setUsbModeToRig();
        assertThat(connector.sent).hasSize(2);
        assertThat(connector.sentAscii(0)).isEqualTo("MD02;");
        assertThat(connector.sentAscii(1)).isEqualTo("NA00;SH0120;");
    }

    @Test
    public void setUsbModeToRig_dataUsb_sendsDataModeThenMaxDataWidth() {
        Yaesu39Rig dataRig = new Yaesu39Rig(true);
        RecordingRigConnector dataConnector = new RecordingRigConnector();
        dataRig.setConnector(dataConnector);
        try {
            dataRig.setUsbModeToRig();
            assertThat(dataConnector.sent).hasSize(2);
            assertThat(dataConnector.sentAscii(0)).isEqualTo("MD0C;");
            assertThat(dataConnector.sentAscii(1)).isEqualTo("NA00;SH0117;");
        } finally {
            dataRig.onDisconnecting();
        }
    }

    @Test
    public void pollAndAtuCommands_matchGoldenAscii() {
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

        rig.onReceiveData("FB007074000;".getBytes());
        assertThat(rig.getFreq()).isEqualTo(7_074_000L);
    }

    @Test
    public void onReceiveData_coalescedMeterReplies_forwardRawValues() {
        List<int[]> meter = new ArrayList<>();
        rig.setOnMeterData((alc, swr) -> meter.add(new int[]{alc, swr}));

        // FT-891 "39" layout: RM reply data is "<selector><3 digits>",
        // selector '4' = ALC, '6' = SWR. Values are forwarded UN-scaled.
        rig.onReceiveData("RM4050;RM6030;".getBytes());

        assertThat(meter).hasSize(2);
        assertThat(meter.get(1)[0]).isEqualTo(50); // ALC raw
        assertThat(meter.get(1)[1]).isEqualTo(30); // SWR raw
    }

    @Test
    public void onReceiveData_unparseableFrame_marksRigRejected() {
        GeneralVariables.rigRejectedAtMs = 0L;
        try {
            // "?;" is the rig's rejection reply; its frame ("?") cannot parse,
            // which must flag the CAT stream as desynchronised (RigDialTarget).
            rig.onReceiveData("?;".getBytes());
            assertThat(GeneralVariables.rigRejectedAtMs).isGreaterThan(0L);
        } finally {
            GeneralVariables.rigRejectedAtMs = 0L;
        }
    }
}
