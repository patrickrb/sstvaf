package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.k1af.ft8af.connector.BaseRigConnector;
import com.k1af.ft8af.connector.CableConnector;
import com.k1af.ft8af.connector.CableSerialPort;
import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.rigs.BaseRig;
import com.k1af.ft8af.rigs.CatConnectionState;
import com.k1af.ft8af.rigs.InstructionSet;
import com.k1af.ft8af.rigs.OnRigStateChanged;
import com.k1af.ft8af.rigs.Yaesu2Rig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link MainViewModel} orchestration: the wiring between the view-model and
 * the rig/connector objects it owns — PTT delivery and the unkey-debt latch,
 * band/frequency pushes, the CAT state chip driven through the rig-state
 * callback, and the connect/reconnect lifecycle (old rig and connector torn
 * down before their replacements exist).
 *
 * <p>The view-model is constructed for real under Robolectric: every heavy
 * collaborator in its constructor degrades gracefully off-device (the native
 * codec guards its {@code loadLibrary}, {@code MicRecorder.start()} skips
 * when no audio source exists, NTP sync swallows its {@code IOException}).
 * Rig-side observation uses fakes extending the real {@link BaseRig}/
 * {@link BaseRigConnector} base classes, planted through the public
 * {@code baseRig} field — the same seam production wiring uses.
 *
 * <p>{@code PttSafetyLatch}, {@code RetunePolicy}, {@code RigDialTarget},
 * {@code CatLivenessTracker} etc. have their own unit tests; here only the
 * view-model's <em>wiring</em> of them is asserted.
 */
@RunWith(RobolectricTestRunner.class)
public class MainViewModelOrchestrationTest {

    /** A rig that records every call the view-model routes to it. */
    static final class RecordingRig extends BaseRig {
        boolean connected = true;
        final List<String> events = new ArrayList<>();
        final List<Boolean> pttWrites = new ArrayList<>();

        @Override public boolean isConnected() { return connected; }
        @Override public void setUsbModeToRig() { events.add("setUsbMode"); }
        @Override public void setFreqToRig() { events.add("setFreqToRig:" + getFreq()); }
        @Override public void onReceiveData(byte[] data) { }
        @Override public void readFreqFromRig() { events.add("readFreq"); }
        @Override public String getName() { return "recording"; }

        @Override
        public void setPTT(boolean on) {
            super.setPTT(on);
            pttWrites.add(on);
            events.add("setPTT:" + on);
        }

        @Override
        public void onDisconnecting() {
            events.add("onDisconnecting");
        }
    }

    /** A connector whose write-delivery answers and connect calls the test controls. */
    static final class RecordingConnector extends BaseRigConnector {
        boolean lastPttWriteOk = true;
        boolean lastCatWriteOk = true;
        int connectCalls;
        int disconnectCalls;

        RecordingConnector() { super(ControlMode.CAT); }

        @Override public boolean isLastPttWriteOk() { return lastPttWriteOk; }
        @Override public boolean isLastCatWriteOk() { return lastCatWriteOk; }
        @Override public void connect() { connectCalls++; }
        @Override public void disconnect() { disconnectCalls++; }
    }

    private Context context;
    private MainViewModel viewModel;
    private RecordingRig rig;
    private RecordingConnector connector;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        GeneralVariables.getInstance().setMainContext(context);

        // GeneralVariables is process-global static state that survives between
        // test methods in one class loader: pin everything the paths under test
        // read so no test depends on which ran before it.
        GeneralVariables.controlMode = ControlMode.CAT;
        GeneralVariables.connectMode = ConnectMode.USB_CABLE;
        GeneralVariables.instructionSet = InstructionSet.YAESU_2;
        GeneralVariables.band = 14_230_000L;
        GeneralVariables.commandedBandHz = 0L;
        GeneralVariables.rigRejectedAtMs = 0L;
        GeneralVariables.operatorDialAssertedAtMs = 0L;
        GeneralVariables.operatorDialDeliveredAtMs = 0L;

        viewModel = new MainViewModel();
        rig = new RecordingRig();
        connector = new RecordingConnector();
    }

    @After
    public void tearDown() {
        // Unroot the whole object graph: one MainViewModel per test is heavy
        // (recorder, decode engine, timers), and every live thread it started
        // pins it as a GC root — 17 pinned view-models OOM'd the test JVM.
        viewModel.onCleared();               // CAT watchdog + rig timers + SCO
        viewModel.sstvSignalListener.stop(); // decode thread + audio tap
        viewModel.hamRecorder.stopRecord();  // mic capture
        viewModel.utcTimer.stop();
        viewModel.utcTimer.delete();         // clock-tick timers + pools
        viewModel = null;
        // Flush anything the teardown itself posted to the main looper.
        ShadowLooper.shadowMainLooper().idle();
    }

    /** Plant the recording rig + connector as the connected rig, bypassing USB discovery. */
    private void plantConnectedRig() {
        rig.setConnector(connector);
        viewModel.baseRig = rig;
    }

    // ===== PTT request paths ==============================================

    @Test
    public void keyDown_reachesTheRig_andKeyUpReleasesIt() {
        plantConnectedRig();

        viewModel.pttController.keyDown();
        assertThat(rig.pttWrites).containsExactly(true);
        assertThat(rig.isPttOn()).isTrue();

        viewModel.pttController.keyUp();
        assertThat(rig.pttWrites).containsExactly(true, false).inOrder();
        assertThat(rig.isPttOn()).isFalse();
    }

    @Test
    public void keying_withoutARig_writesNothing() {
        viewModel.baseRig = null;
        viewModel.pttController.keyDown();
        viewModel.pttController.keyUp();
        // Nothing to assert on a rig; the check is simply that no NPE escaped
        // and no debt was invented.
        assertThat(viewModel.retryPendingUnkey()).isFalse();
    }

    @Test
    public void confirmedUnkey_leavesNoDebt() {
        plantConnectedRig();
        connector.lastPttWriteOk = true;

        viewModel.pttController.keyDown();
        viewModel.pttController.keyUp();

        // The PTT-off write reached the rig: nothing owed, no extra writes.
        assertThat(viewModel.retryPendingUnkey()).isFalse();
        assertThat(rig.pttWrites).containsExactly(true, false).inOrder();
    }

    @Test
    public void lostUnkeyWrite_keepsTheDebt_untilRetryDeliversIt() {
        plantConnectedRig();

        viewModel.pttController.keyDown();
        // The port died mid-over: the PTT-off write does not reach the rig.
        connector.lastPttWriteOk = false;
        rig.connected = false;
        viewModel.pttController.keyUp();
        assertThat(rig.pttWrites).containsExactly(true, false).inOrder();

        // Still down: the retry must not claim success (and cannot write).
        assertThat(viewModel.retryPendingUnkey()).isFalse();
        assertThat(rig.pttWrites).containsExactly(true, false).inOrder();

        // Link back: the owed PTT-off is re-sent and the debt settles.
        rig.connected = true;
        connector.lastPttWriteOk = true;
        assertThat(viewModel.retryPendingUnkey()).isTrue();
        assertThat(rig.pttWrites).containsExactly(true, false, false).inOrder();

        // Settled: a further retry is a no-op.
        assertThat(viewModel.retryPendingUnkey()).isFalse();
        assertThat(rig.pttWrites).containsExactly(true, false, false).inOrder();
    }

    @Test
    public void retryPendingUnkey_whileStillFailing_keepsTheDebtArmed() {
        plantConnectedRig();
        viewModel.pttController.keyDown();
        connector.lastPttWriteOk = false;
        viewModel.pttController.keyUp();

        // Rig reports connected but the write still doesn't land: debt stays.
        assertThat(viewModel.retryPendingUnkey()).isFalse();
        connector.lastPttWriteOk = true;
        assertThat(viewModel.retryPendingUnkey()).isTrue();
    }

    @Test
    public void voxMode_neverWritesPttToTheRig() {
        plantConnectedRig();
        GeneralVariables.controlMode = ControlMode.VOX;

        viewModel.pttController.keyDown();
        viewModel.pttController.keyUp();
        assertThat(rig.pttWrites).isEmpty();
    }

    // ===== Band / frequency propagation ===================================

    @Test
    public void setOperationBand_pushesUsbModeThenFrequencyToTheRig() {
        plantConnectedRig();
        GeneralVariables.commandedBandHz = 21_340_000L;

        viewModel.setOperationBand();
        // USB mode goes out immediately; the frequency follows after the
        // 800 ms settle delay (XieGu X6100 disconnect workaround).
        assertThat(rig.events).containsExactly("setUsbMode");

        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(800));
        assertThat(rig.events)
                .containsExactly("setUsbMode", "setFreqToRig:21340000").inOrder();
        assertThat(rig.getFreq()).isEqualTo(21_340_000L);
    }

    @Test
    public void setOperationBand_deliveredWrite_stampsTheOperatorDial() {
        plantConnectedRig();
        GeneralVariables.commandedBandHz = 21_340_000L;
        GeneralVariables.operatorDialDeliveredAtMs = 0L;
        connector.lastCatWriteOk = true;

        viewModel.setOperationBand();
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(800));

        // The CAT write reached the rig: the pending operator selection is
        // stamped as delivered so the confirm grace can start.
        assertThat(GeneralVariables.operatorDialDeliveredAtMs).isGreaterThan(0L);
    }

    @Test
    public void setOperationBand_writeLostAtDeadPort_doesNotStampDelivery() {
        plantConnectedRig();
        GeneralVariables.commandedBandHz = 21_340_000L;
        GeneralVariables.operatorDialDeliveredAtMs = 0L;
        connector.lastCatWriteOk = false;

        viewModel.setOperationBand();
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(800));

        // The port died between the connected-gate and the send: stamping the
        // selection as delivered would re-open the rig-echo overwrite.
        assertThat(GeneralVariables.operatorDialDeliveredAtMs).isEqualTo(0L);
    }

    @Test
    public void setOperationBand_rigNotConnected_sendsNothing() {
        plantConnectedRig();
        rig.connected = false;

        viewModel.setOperationBand();
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(800));
        assertThat(rig.events).isEmpty();
    }

    @Test
    public void onFreqChanged_fromTheRig_updatesTheGlobalBand() {
        connectCableRigWithoutDevice();
        OnRigStateChanged listener = viewModel.baseRig.getOnRigStateChanged();
        assertThat(listener).isNotNull();

        GeneralVariables.band = 14_230_000L;
        GeneralVariables.commandedBandHz = 14_230_000L;
        listener.onFreqChanged(7_171_000L);

        assertThat(GeneralVariables.band).isEqualTo(7_171_000L);
        // Healthy CAT and no pending operator selection: the report is adopted
        // as the commanded dial (follow mode).
        assertThat(GeneralVariables.commandedBandHz).isEqualTo(7_171_000L);
    }

    @Test
    public void onFreqChanged_whilePendingOperatorSelection_isNotAdopted() {
        connectCableRigWithoutDevice();
        OnRigStateChanged listener = viewModel.baseRig.getOnRigStateChanged();

        // Operator just tapped a band; nothing delivered to the rig yet.
        GeneralVariables.commandedBandHz = 21_340_000L;
        GeneralVariables.operatorDialAssertedAtMs = System.currentTimeMillis();
        GeneralVariables.operatorDialDeliveredAtMs = 0L;
        listener.onFreqChanged(14_230_000L);

        // The stale echo of the old dial must not overwrite the selection.
        assertThat(GeneralVariables.commandedBandHz).isEqualTo(21_340_000L);
    }

    // ===== Connect / disconnect lifecycle =================================

    /**
     * Run the real cable connect path. No USB device exists under Robolectric,
     * so the serial open fails and surfaces onRunError — which is itself the
     * error path under test; the rig/connector objects are still fully wired.
     */
    private void connectCableRigWithoutDevice() {
        viewModel.connectCableRig(context,
                new CableSerialPort.SerialPort(1, 0x10C4, 0xEA60, 0));
        ShadowLooper.shadowMainLooper().idle();
    }

    @Test
    public void connectCableRig_buildsTheSelectedRig_andWiresTheConnector() {
        GeneralVariables.instructionSet = InstructionSet.YAESU_2;
        connectCableRigWithoutDevice();

        assertThat(viewModel.baseRig).isInstanceOf(Yaesu2Rig.class);
        assertThat(viewModel.baseRig.getConnector()).isInstanceOf(CableConnector.class);
        assertThat(viewModel.baseRig.getOnRigStateChanged()).isNotNull();
    }

    @Test
    public void reconnect_tearsDownTheOldRigAndConnector_beforeTheNewOnesExist() {
        plantConnectedRig();
        RecordingRig oldRig = rig;
        RecordingConnector oldConnector = connector;

        connectCableRigWithoutDevice();

        // The old connector's auto-reconnect loop is ended (userDisconnected)
        // and the old rig's poll timers are cancelled — the leak that stacked
        // live connectors on every re-enumeration of a flapping link.
        assertThat(oldConnector.disconnectCalls).isEqualTo(1);
        assertThat(oldRig.events).contains("onDisconnecting");
        assertThat(viewModel.baseRig).isNotSameInstanceAs(oldRig);
        assertThat(viewModel.baseRig.getConnector()).isNotSameInstanceAs(oldConnector);
    }

    @Test
    public void connectCableRig_inVoxMode_switchesToCatControl() {
        GeneralVariables.controlMode = ControlMode.VOX;
        connectCableRigWithoutDevice();
        assertThat(GeneralVariables.controlMode).isEqualTo(ControlMode.CAT);
    }

    @Test
    public void failedCableConnect_leavesAReconnectableErrorState() {
        connectCableRigWithoutDevice();

        // The failed serial open surfaced as ERROR on the status chip...
        assertThat(viewModel.mutableCatConnectionState.getValue())
                .isEqualTo(CatConnectionState.ERROR);
        // ...and the rig/connector stayed wired, so the tap-to-reconnect chip
        // has something to drive.
        assertThat(viewModel.baseRig).isNotNull();
        assertThat(viewModel.baseRig.getConnector()).isNotNull();
        viewModel.reconnectRig();
        ShadowLooper.shadowMainLooper().idle();
        assertThat(viewModel.mutableCatConnectionState.getValue())
                .isNotEqualTo(CatConnectionState.DISCONNECTED);
    }

    @Test
    public void reconnectRig_reusesTheExistingConnector() {
        plantConnectedRig();

        viewModel.reconnectRig();
        ShadowLooper.shadowMainLooper().idle();

        assertThat(connector.connectCalls).isEqualTo(1);
        assertThat(viewModel.mutableCatConnectionState.getValue())
                .isEqualTo(CatConnectionState.CONNECTING);
    }

    @Test
    public void reconnectRig_withNoRigOrConnector_isANoOp() {
        viewModel.baseRig = null;
        viewModel.reconnectRig();

        viewModel.baseRig = rig; // rig without a connector
        viewModel.reconnectRig();
        ShadowLooper.shadowMainLooper().idle();
        assertThat(viewModel.mutableCatConnectionState.getValue())
                .isEqualTo(CatConnectionState.DISCONNECTED);
    }

    @Test
    public void isRigConnected_reflectsTheRigsOwnAnswer() {
        assertThat(viewModel.isRigConnected()).isFalse(); // no rig at all
        plantConnectedRig();
        assertThat(viewModel.isRigConnected()).isTrue();
        rig.connected = false;
        assertThat(viewModel.isRigConnected()).isFalse();
    }

    // ===== CAT state chip via the rig-state callback ======================

    @Test
    public void catChip_followsConnectingConnectedDisconnected() {
        connectCableRigWithoutDevice();
        OnRigStateChanged listener = viewModel.baseRig.getOnRigStateChanged();

        listener.onConnecting();
        ShadowLooper.shadowMainLooper().idle();
        assertThat(viewModel.mutableCatConnectionState.getValue())
                .isEqualTo(CatConnectionState.CONNECTING);

        listener.onConnected();
        ShadowLooper.shadowMainLooper().idle();
        assertThat(viewModel.mutableCatConnectionState.getValue())
                .isEqualTo(CatConnectionState.CONNECTED);

        listener.onDisconnected();
        ShadowLooper.shadowMainLooper().idle();
        assertThat(viewModel.mutableCatConnectionState.getValue())
                .isEqualTo(CatConnectionState.DISCONNECTED);
    }

    @Test
    public void hasRigRespondedToCat_armsOnReply_andResetsOnDisconnect() {
        connectCableRigWithoutDevice();
        OnRigStateChanged listener = viewModel.baseRig.getOnRigStateChanged();

        // Port open alone is not a talking rig (wrong baud, silent adapter).
        listener.onConnected();
        ShadowLooper.shadowMainLooper().idle();
        assertThat(viewModel.hasRigRespondedToCat()).isFalse();

        // A parsed CAT reply is the liveness signal.
        listener.onRigResponded();
        assertThat(viewModel.hasRigRespondedToCat()).isTrue();

        // Disconnect stops the watchdog and must clear the stale "pass" so USB
        // Diagnostics can't show "CAT Response: pass" beside "Device: fail".
        listener.onDisconnected();
        ShadowLooper.shadowMainLooper().idle();
        assertThat(viewModel.hasRigRespondedToCat()).isFalse();
    }

    @Test
    public void catChip_preservesErrorAcrossTheDisconnectThatFollowsIt() {
        connectCableRigWithoutDevice();
        OnRigStateChanged listener = viewModel.baseRig.getOnRigStateChanged();

        // A failed Bluetooth connect fires onRunError() immediately followed by
        // onDisconnected(); the chip must stay red, not fade to neutral grey.
        listener.onRunError("boom");
        listener.onDisconnected();
        ShadowLooper.shadowMainLooper().idle();
        assertThat(viewModel.mutableCatConnectionState.getValue())
                .isEqualTo(CatConnectionState.ERROR);

        // The next attempt clears it.
        listener.onConnecting();
        ShadowLooper.shadowMainLooper().idle();
        assertThat(viewModel.mutableCatConnectionState.getValue())
                .isEqualTo(CatConnectionState.CONNECTING);
    }
}
