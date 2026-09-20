package com.k1af.ft8af;
/**
 * MainViewModel: the radio-shell backbone. Lives for the entire app lifecycle.
 *
 * Owns the rig connection (USB cable / Bluetooth / WiFi / Flex / XieGu), the
 * audio input chain (HamRecorder/MicRecorder -> SpectrumListener -> waterfall),
 * the transmit plumbing (PttController + TransmitAudioSink + TuneOperator),
 * meter protection (ALC auto-volume + SWR halt), and the configuration
 * database. The FT8 decode/transmit engine was removed in the SSTVAF
 * transformation; SSTV RX/TX land in later PRs on top of the same plumbing.
 *
 * @author BG7YOZ
 * @date 2022.8.22
 */

import static com.k1af.ft8af.GeneralVariables.getStringFromResource;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.annotation.Nullable;

import com.k1af.ft8af.bluetooth.ScoLinkCoordinator;
import com.k1af.ft8af.bluetooth.ScoLinkTracker;
import com.k1af.ft8af.bluetooth.ScoPolicy;
import com.k1af.ft8af.bluetooth.TxScoLatch;
import com.k1af.ft8af.connector.BaseRigConnector;
import com.k1af.ft8af.connector.BluetoothRigConnector;
import com.k1af.ft8af.connector.CableConnector;
import com.k1af.ft8af.connector.CableSerialPort;
import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.connector.FlexConnector;
import com.k1af.ft8af.connector.IComWifiConnector;
import com.k1af.ft8af.connector.UsbPermissionThrottle;
import com.k1af.ft8af.connector.X6100Connector;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.database.DatabaseOpr;
import com.k1af.ft8af.database.OperationBand;
import com.k1af.ft8af.database.RigNameList;
import com.k1af.ft8af.flex.FlexRadio;
import com.k1af.ft8af.icom.WifiRig;
import com.k1af.ft8af.log.QSLCallsignRecord;
import com.k1af.ft8af.rigs.BaseRig;
import com.k1af.ft8af.rigs.BaseRigOperation;
import com.k1af.ft8af.rigs.CatConnectionState;
import com.k1af.ft8af.rigs.CatLiveness;
import com.k1af.ft8af.rigs.CatLivenessTracker;
import com.k1af.ft8af.rigs.CivAddressConfig;
import com.k1af.ft8af.rigs.DiscoveryTX500Rig;
import com.k1af.ft8af.rigs.ElecraftRig;
import com.k1af.ft8af.rigs.Flex6000Rig;
import com.k1af.ft8af.rigs.FlexNetworkRig;
import com.k1af.ft8af.rigs.GuoHeQ900Rig;
import com.k1af.ft8af.rigs.HamlibRig;
import com.k1af.ft8af.rigs.IcomRig;
import com.k1af.ft8af.rigs.InstructionSet;
import com.k1af.ft8af.rigs.KenwoodKT90Rig;
import com.k1af.ft8af.rigs.KenwoodTS2000Rig;
import com.k1af.ft8af.rigs.KenwoodTS440Rig;
import com.k1af.ft8af.rigs.KenwoodTS570Rig;
import com.k1af.ft8af.rigs.KenwoodTS590Rig;
import com.k1af.ft8af.rigs.OnRigStateChanged;
import com.k1af.ft8af.rigs.RetunePolicy;
import com.k1af.ft8af.rigs.RigDialTarget;
import com.k1af.ft8af.rigs.TrUSDXRig;
import com.k1af.ft8af.rigs.Wolf_sdr_450Rig;
import com.k1af.ft8af.rigs.XieGu6100NetRig;
import com.k1af.ft8af.rigs.XieGu6100Rig;
import com.k1af.ft8af.rigs.XieGuRig;
import com.k1af.ft8af.rigs.Yaesu2Rig;
import com.k1af.ft8af.rigs.Yaesu2_847Rig;
import com.k1af.ft8af.rigs.Yaesu38Rig;
import com.k1af.ft8af.rigs.Yaesu38_450Rig;
import com.k1af.ft8af.rigs.Yaesu39Rig;
import com.k1af.ft8af.rigs.YaesuDX10Rig;
import com.k1af.ft8af.spectrum.SpectrumListener;
import com.k1af.ft8af.timer.OnUtcTimer;
import com.k1af.ft8af.timer.UtcTimer;
import com.k1af.ft8af.transmit.MeterProtectionController;
import com.k1af.ft8af.transmit.PttController;
import com.k1af.ft8af.transmit.TransmitAudioSink;
import com.k1af.ft8af.transmit.TuneMethod;
import com.k1af.ft8af.transmit.TuneOperator;
import com.k1af.ft8af.ui.ToastMessage;
import com.k1af.ft8af.wave.HamRecorder;
import com.k1af.ft8af.wave.MicRecorder;
import com.k1af.ft8af.x6100.X6100Radio;

import radio.ks3ckc.sstvaf.UsbPermissionIntentsKt;
import radio.ks3ckc.sstvaf.sstv.NativeSstvCodec;
import radio.ks3ckc.sstvaf.gallery.ReceivedImageStore;
import radio.ks3ckc.sstvaf.gallery.RxAutoSaveController;
import radio.ks3ckc.sstvaf.sstv.SstvSignalListener;
import radio.ks3ckc.sstvaf.sstv.SstvTransmitter;
import radio.ks3ckc.sstvaf.ui.tx.TxComposerState;

import java.io.File;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;


public class MainViewModel extends ViewModel {
    String TAG = "sstvaf MainViewModel";
    public final MutableLiveData<Boolean> mutableConfigLoaded = new MutableLiveData<>(false);
    public boolean configIsLoaded = false;

    /** Write debug line to the app's external files debug.log */
    private void fileLog(String msg) {
        GeneralVariables.fileLog(msg);
    }

    private static MainViewModel viewModel = null;//current existing instance.

    public UtcTimer utcTimer;//timer for the 1Hz clock tick.

    public DatabaseOpr databaseOpr;//configuration info and related data database

    public MutableLiveData<Long> timerSec = new MutableLiveData<>();//current UTC time, ~1Hz.
    public MutableLiveData<Boolean> mutableIsRecording = new MutableLiveData<>();//whether currently recording
    public MutableLiveData<Boolean> mutableHamRecordIsRunning = new MutableLiveData<>();//whether HamRecord is running

    public MutableLiveData<Boolean> mutableIsFlexRadio = new MutableLiveData<>();//whether it's a Flex radio
    public MutableLiveData<Boolean> mutableIsXieguRadio = new MutableLiveData<>();//whether it's a XieGu radio

    //variables for displaying shared log generation progress
    public MutableLiveData<String> mutableShareInfo = new MutableLiveData<>("");//shared data status
    public MutableLiveData<Integer> mutableSharePosition = new MutableLiveData<>(0);//current position of shared data
    public MutableLiveData<Boolean> mutableShareRunning = new MutableLiveData<>(false);//whether generating shared data
    public MutableLiveData<Integer> mutableShareCount = new MutableLiveData<>(0);//total shared count
    public MutableLiveData<Boolean> mutableImportShareRunning = new MutableLiveData<>(false);//whether importing shared data

    public HamRecorder hamRecorder;//recording object
    public SpectrumListener spectrumListener;//object for drawing the spectrum/waterfall

    // SSTV engine (PR 5): lives where the FT8SignalListener used to — started
    // with the recorder, fed by the same fan-out, RxForegroundService unchanged.
    public SstvSignalListener sstvSignalListener;//continuous SSTV RX decode engine
    public SstvTransmitter sstvTransmitter;//SSTV image transmitter (PTT + audio sink)
    //TX composer photo/crop/overlays — outlives the TX tab composable so the
    //image survives tab switches/rotation; dropped only by the user's CLEAR
    public final TxComposerState txComposerState = new TxComposerState();

    // Received-image persistence (PR 6): PNG + metadata row per completed decode.
    public ReceivedImageStore receivedImageStore;//saved SSTV images (app storage + Photos)
    public RxAutoSaveController rxAutoSaveController;//auto-saves Complete decodes

    // Transmit plumbing, extracted from the retired FT8 engine (PR 3).
    public PttController pttController;//rig keying (CAT/RTS/DTR + SCO) around a TX
    public TransmitAudioSink transmitAudioSink;//audio playback sink (AudioTrack/USB/rig routes)
    public TuneOperator tuneOperator;//tune carrier (long tone + PTT + safety timeout)
    public MeterProtectionController meterProtectionController;//ALC auto-volume + SWR halt

    //rig control mode
    public OperationBand operationBand = null;

    public MutableLiveData<ArrayList<CableSerialPort.SerialPort>> mutableSerialPorts = new MutableLiveData<>();
    private ArrayList<CableSerialPort.SerialPort> serialPorts;//serial port list
    public BaseRig baseRig;//rig

    // "We keyed the rig and haven't confirmed it back off" — settled by
    // retryPendingUnkey() on reconnect and from the clock tick as a backstop.
    private final PttSafetyLatch pttSafetyLatch = new PttSafetyLatch();
    /**
     * Whether our SCO link was up when the current transmission was keyed —
     * snapshotted at key-down before {@code stopSco()} tears it down, because
     * the TX path asks after the PTT settle delay, by which time the tracker
     * already says "down" (see {@link TxScoLatch}).
     */
    private final TxScoLatch txScoLatch = new TxScoLatch();

    public boolean deNoise = false;//suppress noise in the spectrum

    //*********variables needed for log query********************
    public boolean logListShowCallsign = false;//display format in the log query list
    public String queryKey = "";//query keyword
    public int queryFilter = 0;//filter: 0=all, 1=confirmed, 2=unconfirmed
    public MutableLiveData<Integer> mutableQueryFilter = new MutableLiveData<>();
    public ArrayList<QSLCallsignRecord> callsignRecords = new ArrayList<>();
    //********************************************

    //Observable CAT connection state for the UI status chip. The callbacks below
    //fire off the UI thread, so LiveData is updated via postValue. catConnectionState
    //is a synchronous mirror of the same value: a failed Bluetooth connect calls
    //onRunError() immediately followed by onDisconnected(), and reading LiveData's
    //value across those two posts would race — so the ERROR-preserving guard reads
    //the synchronous field instead.
    public final MutableLiveData<CatConnectionState> mutableCatConnectionState =
            new MutableLiveData<>(CatConnectionState.DISCONNECTED);
    private volatile CatConnectionState catConnectionState = CatConnectionState.DISCONNECTED;

    private void setCatConnectionState(CatConnectionState state) {
        catConnectionState = state;
        mutableCatConnectionState.postValue(state);
    }

    private final OnRigStateChanged onRigStateChanged = new OnRigStateChanged() {
        @Override
        public void onDisconnected() {
            //disconnected from rig. A failed connect fires onRunError() then
            //onDisconnected(); afterDisconnect() preserves ERROR so the chip can
            //stay red until the next connect attempt (onConnecting) or a success.
            // State write is applied atomically with the tracker stop, so a reply racing
            // the disconnect can't heal the chip back to CONNECTED afterwards.
            stopCatLivenessWatchdog(() -> setCatConnectionState(
                    CatConnectionState.afterDisconnect(catConnectionState)));
            ToastMessage.show(getStringFromResource(R.string.disconnect_rig));
        }

        @Override
        public void onConnecting() {
            //connection attempt started
            setCatConnectionState(CatConnectionState.CONNECTING);
        }

        @Override
        public void onConnected() {
            //connected to rig
            setCatConnectionState(CatConnectionState.CONNECTED);
            ToastMessage.show(getStringFromResource(R.string.connected_rig));
            // A genuinely new link is a new session for the retune rate limit, so its
            // push below must be unthrottleable — otherwise the reconnect case the
            // comment below describes silently regresses.
            //
            // DEBOUNCED, because this callback is itself the ~1 Hz retune driver:
            // CableSerialPort fires it on every successful port open(), and a flapping
            // link re-opens the port about once a second. Resetting unconditionally
            // re-armed the limiter on every iteration of the very loop it contains. A
            // burst of connects seconds apart is one flapping link; a reconnect after
            // a real outage is minutes later. See RetunePolicy.CONNECT_RESET_DEBOUNCE_MS.
            long connectAtMs = System.currentTimeMillis();
            if (RetunePolicy.shouldResetOnConnect(connectAtMs, lastConnectCallbackAtMs)) {
                resetRetuneRateLimit();
            }
            lastConnectCallbackAtMs = connectAtMs;
            // Push the app's current band/frequency to the rig on every connect —
            // including an automatic reconnect, which previously left the rig on
            // whatever frequency it powered up on ("no frequency set after connecting").
            // setOperationBand() no-ops if the rig isn't actually connected and has its
            // own settle delay, so a short post keeps us off the connect-callback thread
            // without racing the link coming up.
            new Handler(Looper.getMainLooper()).postDelayed(MainViewModel.this::setOperationBand, 1500);
            // (Re)start the liveness watchdog for this connection.
            startCatLivenessWatchdog();
        }

        @Override
        public void onPttChanged(boolean isOn) {

        }

        @Override
        public void onRigResponded() {
            // The rig answered with a valid frequency (changed or not) — the liveness signal.
            // onFreqChanged only fires on a change, so it can't be used here (a stable dial
            // would look dead and falsely trip the watchdog).
            markRigResponded();
        }

        @Override
        public void onFreqChanged(long freq) {
            //current frequency: %s
            ToastMessage.show(String.format(getStringFromResource(R.string.current_frequency)
                    , BaseRigOperation.getFrequencyAllInfo(freq)));
            //write frequency changes back to global variables
            GeneralVariables.band = freq;
            // Observing a frequency is not the same as choosing one. Adopt it as the dial
            // we COMMAND only while the CAT stream is healthy AND no explicit operator
            // selection is pending — otherwise a reading gets pushed back at the rig by
            // the reassert heartbeat and fights the operator's band selection. Two
            // measured failures in FT8AF: a "?;"-desync reading of 14239985 (took a 30m
            // tap ~59s and four attempts), and a healthy echo of the OLD band overwriting
            // a tap the connected-gate had dropped before it reached the wire (the
            // heartbeat then re-asserted 20m against 30m taps all evening). See RigDialTarget.
            if (freq == GeneralVariables.commandedBandHz) {
                // The rig confirmed the operator's selection — back to follow mode.
                GeneralVariables.operatorDialAssertedAtMs = 0L;
            }
            if (RigDialTarget.shouldAdoptAsTarget(System.currentTimeMillis(),
                    GeneralVariables.rigRejectedAtMs, freq,
                    GeneralVariables.commandedBandHz,
                    GeneralVariables.operatorDialAssertedAtMs,
                    GeneralVariables.operatorDialDeliveredAtMs)) {
                GeneralVariables.commandedBandHz = freq;
            } else {
                fileLog("rig echo ignored as command target: reported " + freq
                        + " while CAT desynced or operator selection pending;"
                        + " still asserting " + GeneralVariables.commandedBandHz);
            }
            GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(freq);
            GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex);

            databaseOpr.getAllQSLCallsigns();//read out successfully contacted callsigns
        }

        @Override
        public void onRunError(String message) {
            //rig communication error. ERROR is written atomically with the tracker stop:
            //a reply parsed while the connector still reports connected can't overwrite it.
            stopCatLivenessWatchdog(() -> setCatConnectionState(CatConnectionState.ERROR));
            ToastMessage.show(String.format(getStringFromResource(R.string.radio_communication_error)
                    , message));
        }
    };

    // ===== CAT liveness watchdog =====
    // A Bluetooth/serial CAT link can stay "connected" after the radio is powered off (the
    // BT module keeps the socket up), so the chip stayed green and frequency writes went
    // nowhere. This watchdog periodically reads the rig's frequency and, if a previously-
    // responsive rig goes quiet for too long, flips the chip to error. See CatLiveness for
    // the guard logic (never judges during TX; only arms after the first reply, so a rig
    // that doesn't echo frequency reads is never falsely marked dead).
    private static final long CAT_LIVENESS_TICK_MS = 3000;
    private Timer catLivenessTimer;
    // Arm/trip/recover state lives in the tracker (pure, unit-tested). It is armed ONLY
    // by replies parsed from the rig (BaseRig.setFreq -> onRigResponded); the app's own
    // dial pushes go through BaseRig.setCommandedFreq and never count. A trip is not
    // terminal: probing continues and the next reply flips the chip back to CONNECTED.
    private final CatLivenessTracker catLiveness =
            new CatLivenessTracker(CatLiveness.DEFAULT_TIMEOUT_MS);

    /** Record that the rig just demonstrably responded (called from onRigResponded). */
    private void markRigResponded() {
        // The rig answered after the watchdog had declared it dead: heal the chip. The
        // CONNECTED write happens under the tracker's monitor, atomic with clearing the
        // trip. Only the watchdog's own ERROR is undone here: a connector I/O error stops
        // the tracker (under the same monitor) before writing ERROR, so this can't mask a
        // real link loss.
        catLiveness.onResponse(System.currentTimeMillis(), this::isRigConnected, () -> {
            fileLog("CAT liveness: rig answered again — chip back to CONNECTED");
            setCatConnectionState(CatConnectionState.CONNECTED);
        });
    }

    private synchronized void startCatLivenessWatchdog() {
        stopCatLivenessWatchdog();
        catLiveness.start(System.currentTimeMillis());
        catLivenessTimer = new Timer("cat-liveness");
        catLivenessTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                catLivenessTick();
            }
        }, CAT_LIVENESS_TICK_MS, CAT_LIVENESS_TICK_MS);
    }

    private synchronized void stopCatLivenessWatchdog() {
        stopCatLivenessWatchdog(null);
    }

    /**
     * Stop the watchdog, applying {@code then} (a chip-state write) atomically with the
     * tracker stop — see {@link CatLivenessTracker#stop(CatLivenessTracker.Transition)}.
     */
    private synchronized void stopCatLivenessWatchdog(CatLivenessTracker.Transition then) {
        if (catLivenessTimer != null) {
            catLivenessTimer.cancel();
            catLivenessTimer.purge();
            catLivenessTimer = null;
        }
        // Clear the "rig has answered" flag when the watchdog stops (disconnect, error,
        // or teardown) so hasRigRespondedToCat() can't report a stale true after the rig
        // is unplugged — the USB Diagnostics page would otherwise show "CAT Response: pass"
        // alongside "Device Found: fail". A fresh connect re-arms it in start...().
        catLiveness.stop(then);
    }

    /** Whether any transmission (tune carrier or SSTV image) is on the air right now. */
    private boolean isTransmittingNow() {
        return (tuneOperator != null && tuneOperator.isTuning())
                || (sstvTransmitter != null && sstvTransmitter.isTransmittingNow());
    }

    /** One watchdog tick: probe the rig, then declare it dead if it's gone quiet too long. */
    private void catLivenessTick() {
        try {
            // Sample the wall clock once per tick so the re-arm and staleness check reason
            // about the same instant — a clock change mid-tick can't skew the comparison.
            long nowMs = System.currentTimeMillis();
            boolean connected = isRigConnected();
            boolean transmitting = isTransmittingNow();
            // A trip writes ERROR under the tracker's monitor, and BEFORE this tick's probe
            // goes out: a fast reply to that probe then always finds the trip applied and
            // heals it, instead of healing first and being overwritten by a late ERROR
            // (which left the chip red with the tracker untripped, unrecoverable).
            CatLivenessTracker.Tick tick = catLiveness.tick(connected, transmitting, nowMs,
                    () -> setCatConnectionState(CatConnectionState.ERROR));
            // Actively probe (a frequency read); the reply lands in onRigResponded ->
            // markRigResponded() (onFreqChanged only fires on a change, so a stable dial
            // can't be used). On a dead-but-powered BT module the write succeeds but no
            // reply comes, so the quiet timer eventually trips. Probing continues while
            // tripped so the next reply can heal the chip.
            if (tick.probe && baseRig != null) {
                baseRig.readFreqFromRig();
            }
            if (tick.event == CatLivenessTracker.Event.TRIPPED) {
                // Watchdog stays running (see markRigResponded for the recovery path).
                fileLog("CAT liveness: no reply to freq reads for " + tick.quietMs
                        + "ms (transport still open) — chip ERROR until the rig answers");
                ToastMessage.show(String.format(
                        getStringFromResource(R.string.radio_communication_error),
                        getStringFromResource(R.string.disconnect_rig)));
            }
        } catch (Exception e) {
            // Never let a probe failure crash the timer thread; a real I/O error already
            // surfaces via onRunError(). Log the exception object (not just getMessage(),
            // which can be null) so the stack trace is preserved.
            Log.w(TAG, "cat liveness tick failed", e);
        }
    }

    /**
     * Get the MainViewModel instance, ensuring a unique instance exists throughout the entire app lifecycle.
     *
     * @param owner ViewModelStoreOwner owner, typically an Activity or Fragment.
     * @return MainViewModel Returns a MainViewModel instance.
     */
    public static MainViewModel getInstance(ViewModelStoreOwner owner) {
        if (viewModel == null) {
            viewModel = new ViewModelProvider(owner).get(MainViewModel.class);
        }
        return viewModel;
    }

    /**
     * Nullable peek at the singleton for observers that must never boot the engine —
     * only ComposeMainActivity may create the instance. Null means the phone UI hasn't
     * run yet this process.
     */
    @Nullable
    public static MainViewModel peekInstance() {
        return viewModel;
    }

    /**
     * MainViewModel constructor: builds the audio input chain, the transmit
     * plumbing (PTT + audio sink + tune), and the 1Hz clock tick.
     */
    public MainViewModel() {

        //get configuration info.
        databaseOpr = DatabaseOpr.getInstance(GeneralVariables.getMainContext()
                , "data.db");
        //create recording object
        hamRecorder = new HamRecorder(null);
        hamRecorder.startRecord();

        mutableIsFlexRadio.setValue(false);
        mutableIsXieguRadio.setValue(false);

        //create timer for displaying time — a 1-second (1000ms) tick, just to refresh the clock.
        utcTimer = new UtcTimer(1000, false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {//clock info when not triggered

            }

            @Override
            public void doOnSecTimer(long utc) {//triggered at specified interval
                timerSec.postValue(utc);//send current UTC time
                mutableIsRecording.postValue(hamRecorder.isRunning());
                mutableHamRecordIsRunning.postValue(hamRecorder.isRunning());//send current timer state
                // Backstop for an unkey owed after a link loss (see PttSafetyLatch):
                // FT8AF settles it at every slot boundary; SSTV has no slots, so
                // every 15 s of the clock tick plays that role. No-op unless armed.
                if (utc / 1000 % 15 == 0) {
                    retryPendingUnkey();
                }
            }
        });
        utcTimer.start();//start timer

        //synchronize time. Microsoft's NTP server
        UtcTimer.syncTime(null);

        //spectrum listener object (feeds the waterfall)
        spectrumListener = new SpectrumListener(hamRecorder);

        // ===== Transmit plumbing (extracted from the FT8 engine) =====
        pttController = new PttController(
                new PttController.Keyer() {
                    @Override
                    public boolean hasRig() {
                        return baseRig != null;
                    }

                    @Override
                    public void setPtt(boolean on) {
                        if (baseRig == null) {
                            return;
                        }
                        if (on) {
                            // Arm before the write, not after: if setPTT throws or the
                            // process dies mid-key, we still recorded that the rig may
                            // be keyed and the next reconnect settles it.
                            pttSafetyLatch.onKeyed();
                            baseRig.setPTT(true);
                        } else {
                            baseRig.setPTT(false);
                            // Only a confirmed write clears the latch. A PTT-off issued
                            // at a port that has already gone away leaves the rig keyed,
                            // so keep the debt and let retryPendingUnkey() settle it when
                            // the link is back.
                            pttSafetyLatch.onUnkeyAttempted(lastPttWriteReachedRig());
                        }
                    }
                },
                new PttController.ScoControl() {
                    @Override
                    public boolean needControlSco() {
                        return MainViewModel.this.needControlSco();
                    }

                    @Override
                    public void stopSco() {
                        MainViewModel.this.stopSco();
                    }

                    @Override
                    public void startSco() {
                        MainViewModel.this.startSco();
                    }
                },
                () -> GeneralVariables.controlMode);
        // Snapshot the Bluetooth SCO link state at every keying edge, BEFORE the
        // controller pauses SCO: the TX path asks after the PTT settle delay, by
        // which time the tracker already says "down" (see TxScoLatch). Two
        // different questions: needControlSco() — is this a Bluetooth RIG whose
        // TX audio must not ride SCO (true for Bluetooth + VOX too) — decides
        // whether the TX path may steer Default output to the rig's A2DP; the
        // control-path keying with a rig is what actually pauses SCO around PTT.
        pttController.setKeyingObserver(new PttController.KeyingObserver() {
            @Override
            public void beforeKeyDown(boolean keysViaControlPath) {
                boolean bluetoothRigTx = needControlSco();
                // The controller pauses SCO itself right after this snapshot, so
                // the latch only records here (stopsSco=false).
                txScoLatch.keyDown(bluetoothRigTx, false,
                        MainViewModel.this::isScoLinkUpOrPending,
                        MainViewModel.this::routedScoInputAddress,
                        MainViewModel.this::stopSco);
            }

            @Override
            public void afterKeyUp() {
                // The post-TX restart has been requested; the next over takes a
                // fresh snapshot in beforeKeyDown().
                txScoLatch.keyUp();
            }
        });

        transmitAudioSink = new TransmitAudioSink();
        transmitAudioSink.setTxScoState(new TransmitAudioSink.TxScoState() {
            @Override
            public boolean heldForTx() {
                return isScoHeldForTx();
            }

            @Override
            public String scoAddress() {
                return scoAddressForTx();
            }
        });
        transmitAudioSink.setRigWaveRoute(new TransmitAudioSink.RigWaveRoute() {
            @Override
            public boolean isAvailable() {
                if (baseRig == null || !baseRig.isConnected()) {
                    return false;
                }
                // Network rigs (ICOM/XieGu WiFi, Flex network) stream TX audio
                // themselves; truSDX-style rigs carry TX audio over the CAT link.
                if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
                    return true;
                }
                return GeneralVariables.controlMode == ControlMode.CAT
                        && baseRig.supportWaveOverCAT();
            }

            @Override
            public boolean sendWave(float[] buffer, int sampleRate) {
                if (baseRig == null) {
                    return false;
                }
                baseRig.sendWaveData(buffer, sampleRate);
                return true;
            }
        });

        //meter protection controller (ALC auto-volume + SWR halt)
        meterProtectionController = new MeterProtectionController();

        //tune carrier operator (long tone + PTT + safety timeout)
        tuneOperator = new TuneOperator(pttController, transmitAudioSink, meterProtectionController);
        tuneOperator.setCatAudioRouteCheck(() -> GeneralVariables.controlMode == ControlMode.CAT
                && baseRig != null && baseRig.isConnected() && baseRig.supportWaveOverCAT());

        // ===== SSTV engine (PR 5): continuous RX decode + image TX =====
        // One codec instance is shared: it is stateless apart from the decoder
        // sessions it hands out. The listener owns its decode thread and taps
        // the recorder fan-out (same mechanism as SpectrumListener); its
        // fileLog lines (VIS lock / image complete) come from its default
        // logger. The transmitter adapts the extracted PTT + audio-sink
        // plumbing exactly the way TuneOperator composes them.
        NativeSstvCodec sstvCodec = new NativeSstvCodec();
        sstvSignalListener = new SstvSignalListener(sstvCodec);
        sstvSignalListener.start();
        sstvSignalListener.attachToRecorder(hamRecorder);

        // Auto-save completed decodes (PR 6): every Complete transition pulls
        // the LastDecodedImage snapshot and persists it (PNG + sstv_images row
        // + optional Photos copy). observeForever is fine: both objects live
        // exactly as long as this ViewModel. Constructor runs on the main
        // thread (ViewModelProvider), which observeForever requires.
        receivedImageStore = new ReceivedImageStore(
                GeneralVariables.getMainContext(), databaseOpr.getDb());
        rxAutoSaveController = new RxAutoSaveController(receivedImageStore);
        rxAutoSaveController.attach(sstvSignalListener.getRxState());

        sstvTransmitter = new SstvTransmitter(sstvCodec,
                new SstvTransmitter.Keyer() {
                    @Override
                    public void keyDown() {
                        pttController.keyDown();
                    }

                    @Override
                    public void keyUp() {
                        pttController.keyUp();
                    }
                },
                new SstvTransmitter.Player() {
                    @Override
                    public boolean play(float[] buffer, int sampleRate) {
                        return transmitAudioSink.play(buffer, sampleRate,
                                GeneralVariables.audioOutput32Bit,
                                () -> GeneralVariables.volumePercent)
                                == TransmitAudioSink.PlayResult.COMPLETED;
                    }

                    @Override
                    public void cancel() {
                        transmitAudioSink.cancel();
                    }
                },
                () -> tuneOperator.isTuning());

        //an SWR halt stops the active transmission (tune carrier + SSTV TX)
        //immediately: pass sendCwId=false so a safety halt never follows the
        //cancelled image with a fresh CW station-ID transmission (issue #14).
        meterProtectionController.setOnSwrHalt(() -> {
            tuneOperator.stopTune();
            sstvTransmitter.cancel(false);
            transmitAudioSink.cancel();
        });

        // A failed USB capture session schedules a device reinit; that reinit
        // force-reclaims the shared USB audio device and would kill an
        // in-flight transmission seconds after key-down. Give MicRecorder the
        // TX-on-the-air check so it defers the reopen until the over ends.
        hamRecorder.getMicRecorder().setTxActiveCheck(() ->
                (sstvTransmitter != null && sstvTransmitter.isTransmittingNow())
                        || (tuneOperator != null && tuneOperator.isTuning()));
    }

    /** The continuous SSTV receive engine (decode state via {@code getRxState()}). */
    public SstvSignalListener getSstvSignalListener() {
        return sstvSignalListener;
    }

    /** The SSTV image transmitter. */
    public SstvTransmitter getSstvTransmitter() {
        return sstvTransmitter;
    }

    /**
     * Start a tune through the rig's internal ATU when the tune-method setting
     * (issue #425) says so and an ATU-capable CAT rig is connected and idle.
     *
     * @return true when this call handled the TUNE tap (ATU started, or the
     *         INTERNAL method was set but no ATU rig is available — the operator
     *         is told and no surprise carrier goes out); false when the caller
     *         should fall through to the carrier tone.
     */
    public boolean tryStartTuneViaAtu() {
        boolean atuAvailable = baseRig != null && baseRig.isConnected()
                && baseRig.supportsAtuTune() && !baseRig.isPttOn();
        int action = TuneMethod.decide(GeneralVariables.tuneMethod, atuAvailable);
        if (action == TuneMethod.ACTION_RIG_ATU) {
            fileLog("tune: starting rig ATU (method=" + GeneralVariables.tuneMethod
                    + ", rig=" + baseRig.getName() + ")");
            baseRig.startAtuTune();
            ToastMessage.show(getStringFromResource(R.string.tune_atu_started));
            return true;
        }
        if (action == TuneMethod.ACTION_UNAVAILABLE) {
            fileLog("tune: method=INTERNAL but no ATU-capable rig connected");
            ToastMessage.show(getStringFromResource(R.string.tune_atu_unavailable));
            return true;
        }
        return false;
    }

    /**
     * The TUNE chip's action: the rig's internal ATU when the tune method and the
     * connected rig allow it, otherwise the low-power carrier tone.
     *
     * @return true if a tune (ATU or carrier) was started, or the tap was consumed
     *         with an explanation; false if the carrier was refused (see
     *         {@link TuneOperator#startTune()}, which toasts the reason)
     */
    public boolean startTune() {
        if (tryStartTuneViaAtu()) {
            return true;
        }
        return tuneOperator.startTune();
    }

    // Rate-limit state for setOperationBand(). See RetunePolicy for why this exists and
    // what is still unexplained about the caller.
    private long lastPushedBandFreq = RetunePolicy.NO_PUSH;
    private long lastBandPushAtMs = 0L;
    private long lastRetuneSuppressionLogAtMs = RetunePolicy.NEVER_LOGGED;
    private int suppressedRetunes = 0;
    /** When the previous onConnected() callback arrived; see RetunePolicy.shouldResetOnConnect. */
    private volatile long lastConnectCallbackAtMs = RetunePolicy.NO_CONNECT;

    /**
     * Forget what we last pushed, so the next {@code setOperationBand()} is treated as a
     * first push and goes out unthrottled. Called on every successful connect.
     */
    private void resetRetuneRateLimit() {
        lastPushedBandFreq = RetunePolicy.NO_PUSH;
        lastBandPushAtMs = 0L;
        lastRetuneSuppressionLogAtMs = RetunePolicy.NEVER_LOGGED;
        suppressedRetunes = 0;
    }

    /**
     * Set the operating carrier frequency. Only operates if the rig is connected.
     *
     * <p>Redundant requests — same dial as the last push, rig already reporting it — are
     * suppressed down to a slow reassert heartbeat by {@link RetunePolicy}. A genuine
     * retune (new dial, or a rig that has moved) is never delayed. This is containment for
     * a ~1 Hz caller that has not been identified; the suppression log below names it.
     */
    public void setOperationBand() {
        if (!isRigConnected()) {
            fileLog("setOperationBand: rig not connected, skipping");
            return;
        }

        long nowMs = System.currentTimeMillis();
        // Assert the dial we CHOSE, never one echoed back by the rig. See RigDialTarget.
        final long dialHz = RigDialTarget.dialToCommand(
                GeneralVariables.commandedBandHz, GeneralVariables.band);
        if (!RetunePolicy.shouldRetune(dialHz, baseRig.getFreq(),
                lastPushedBandFreq, nowMs, lastBandPushAtMs)) {
            suppressedRetunes++;
            if (RetunePolicy.shouldLogSuppression(nowMs, lastRetuneSuppressionLogAtMs)) {
                // Name the caller: the ~1 Hz driver of this loop is not identifiable from
                // the source, so record who is actually asking. Only on the rate-limited
                // path — building a stack trace per suppressed call would be its own leak.
                fileLog("setOperationBand: suppressed " + suppressedRetunes
                        + " redundant retunes (freq=" + dialHz
                        + " already set) caller=" + RetunePolicy.callerOf(
                                Thread.currentThread().getStackTrace(),
                                MainViewModel.class.getName()));
                lastRetuneSuppressionLogAtMs = nowMs;
                suppressedRetunes = 0;
            }
            return;
        }
        lastPushedBandFreq = dialHz;
        lastBandPushAtMs = nowMs;

        fileLog("setOperationBand: sending USB mode, then freq=" + dialHz
                + " in 800ms (controlMode=" + GeneralVariables.controlMode + ")");
        //set USB mode first, then set frequency
        baseRig.setUsbModeToRig();//set USB mode

        //delay before sending the second command to prevent XieGu X6100 disconnection issues
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                // Re-read the commanded dial HERE rather than using the value captured
                // 800ms ago: the operator can change band inside that window, and a
                // stale capture would briefly retune the rig back to the old one.
                long sendHz = RigDialTarget.dialToCommand(
                        GeneralVariables.commandedBandHz, GeneralVariables.band);
                fileLog("setOperationBand: setting freq=" + sendHz
                        + " (rig.getFreq=" + baseRig.getFreq() + ")");
                // Commanded, not reported: must NOT arm the CAT liveness watchdog.
                baseRig.setCommandedFreq(sendHz);//set frequency
                baseRig.setFreqToRig();
                // A pending operator selection has now actually been dispatched (the
                // connected-gate above passed): start the confirm grace, after which a
                // still-differing rig report is trusted again. Only if the write really
                // reached the rig, though — a port that died between the gate and the
                // send returns false from sendData without throwing, and stamping that
                // as delivered would re-open the overwrite. See RigDialTarget.
                boolean catOk = baseRig.getConnector() != null
                        && baseRig.getConnector().isLastCatWriteOk();
                GeneralVariables.operatorDialDeliveredAtMs = RigDialTarget.deliveredStamp(
                        catOk, System.currentTimeMillis(),
                        GeneralVariables.operatorDialDeliveredAtMs);
            }
        }, 800);
    }

    public void setCivAddress() {
        if (baseRig != null) {
            baseRig.setCivAddress(GeneralVariables.civAddress);
        }
    }

    public void setControlMode() {
        if (baseRig != null) {
            baseRig.setControlMode(GeneralVariables.controlMode);
        }
        //Notify observers (CAT status chip visibility) of the mode change.
        GeneralVariables.mutableControlMode.postValue(GeneralVariables.controlMode);
    }


    /**
     * Connect to rig via USB
     *
     * @param context context
     * @param port    serial port
     */
    public void connectCableRig(Context context, CableSerialPort.SerialPort port) {
        if (GeneralVariables.controlMode == ControlMode.VOX) {//if currently VOX, switch to CAT mode
            GeneralVariables.controlMode = ControlMode.CAT;
            databaseOpr.writeConfig("ctrMode", String.valueOf(ControlMode.CAT), null);
            GeneralVariables.mutableControlMode.postValue(GeneralVariables.controlMode);
        }
        // Tear down any previous connector FIRST. It owns an open port and an
        // auto-reconnect loop; just overwriting the reference leaked both, so every
        // re-enumeration of a flapping link stacked another live connector — measured
        // in FT8AF as an orphaned port's poll timers spamming "port not open!"
        // interleaved with the live port's sends, and concurrent reconnect loops each
        // hammering port opens. disconnect() sets that connector's userDisconnected,
        // which is what actually ends its loop.
        if (baseRig != null && baseRig.getConnector() != null) {
            baseRig.getConnector().disconnect();
        }
        connectRig();

        if (baseRig == null) {
            return;
        }
        baseRig.setControlMode(GeneralVariables.controlMode);
        CableConnector connector = new CableConnector(context, port, GeneralVariables.baudRate
                , GeneralVariables.controlMode, baseRig);

        //2023-08-16 Modified by DS1UFX (based on v0.9), for (tr)uSDX audio over CAT support.
        connector.setOnCableDataReceived(new CableConnector.OnCableDataReceived() {
            @Override
            public void OnWaveReceived(int bufferLen, float[] buffer) {
                Log.i(TAG, "call hamRecorder.doOnWaveDataReceived");
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });

        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(connector);
        connector.connect();

        //delay 1 second before setting mode, to prevent some rigs from not responding in time
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                // Settle any unkey owed from before the link dropped BEFORE
                // retuning. If a brown-out killed the port mid-transmission the
                // rig is still keyed, and sending frequency/mode to a keyed rig
                // is exactly what makes it click and mis-set.
                retryPendingUnkey();
                setOperationBand();//set carrier frequency
            }
        }, 1000);

    }

    public void connectBluetoothRig(Context context, BluetoothDevice device) {
        // Remember this device so the SPP/CAT link can auto-reconnect on the next app launch
        // (issue #223). Centralized here so every entry point persists consistently.
        if (!device.getAddress().equals(GeneralVariables.bluetoothDeviceAddress)) {
            GeneralVariables.bluetoothDeviceAddress = device.getAddress();
            databaseOpr.writeConfig("bluetoothDeviceAddress", device.getAddress(), null);
        }

        GeneralVariables.controlMode = ControlMode.CAT;//Bluetooth control mode, only CAT control is supported
        GeneralVariables.mutableControlMode.postValue(GeneralVariables.controlMode);
        connectRig();
        if (baseRig == null) {
            return;
        }
        baseRig.setControlMode(GeneralVariables.controlMode);
        BluetoothRigConnector connector = BluetoothRigConnector.getInstance(context, device.getAddress()
                , GeneralVariables.controlMode);
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(connector);

        // Route HFP audio over SCO as soon as the CAT rig is wired -- but only when a Bluetooth
        // audio profile (headset/A2DP) is actually connected. SPP-only CAT adapters have no audio
        // path, so forcing SCO + headset mode on them would be wrong and disruptive (PR #227
        // review).
        new Handler(Looper.getMainLooper()).post(() -> {
            if (isBTConnected()) {
                setBlueToothOn();
            }
        });

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {//connection takes time, wait before setting frequency
            @Override
            public void run() {
                setOperationBand();//set carrier frequency
            }
        }, 5000);
    }

    /**
     * Connect to ICOM or XieGu X6100 series rig via network
     *
     * @param wifiRig ICom or XieGu WiFi mode rig
     */
    public void connectWifiRig(WifiRig wifiRig) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            if (baseRig != null) {
                if (baseRig.getConnector() != null) {
                    baseRig.getConnector().disconnect();
                }
            }
        }

        GeneralVariables.controlMode = ControlMode.CAT;//network control mode
        //currently Icom and XieGu X6100 share the same connector
        IComWifiConnector iComWifiConnector = new IComWifiConnector(GeneralVariables.controlMode
                , wifiRig);
        iComWifiConnector.setOnWifiDataReceived(new IComWifiConnector.OnWifiDataReceived() {
            @Override
            public void OnWaveReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }

            @Override
            public void OnCivReceived(byte[] data) {

            }
        });

        connectRig();//assign baseRig

        baseRig.setControlMode(GeneralVariables.controlMode);
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(iComWifiConnector);
        // Connect AFTER the rig-state listener is wired (setConnector above), otherwise the
        // onConnecting/onConnected edges the connector emits (FT8AF #754) would fire into a
        // null listener and the CAT chip would never leave grey.
        iComWifiConnector.connect();

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {//connection takes time, wait before setting frequency
            @Override
            public void run() {
                setOperationBand();//set carrier frequency
            }
        }, 1000);
    }

    /**
     * Connect to FlexRadio
     *
     * @param context   context
     * @param flexRadio FlexRadio object
     */
    public void connectFlexRadioRig(Context context, FlexRadio flexRadio) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            if (baseRig != null) {
                if (baseRig.getConnector() != null) {
                    baseRig.getConnector().disconnect();
                }
            }
        }
        GeneralVariables.controlMode = ControlMode.CAT;//network control mode
        FlexConnector flexConnector = new FlexConnector(context, flexRadio, GeneralVariables.controlMode);
        flexConnector.setOnWaveDataReceived(new FlexConnector.OnWaveDataReceived() {
            @Override
            public void OnDataReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });
        flexConnector.connect();
        connectRig();

        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(flexConnector);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {//connection takes time, wait before setting frequency
            @Override
            public void run() {
                setOperationBand();//set carrier frequency
            }
        }, 3000);
    }

    /**
     * Connect to XieGu Radio
     *
     * @param context    context
     * @param xieguRadio X6100Radio object
     */
    public void connectXieguRadioRig(Context context, X6100Radio xieguRadio) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            if (baseRig != null) {
                if (baseRig.getConnector() != null) {
                    baseRig.getConnector().disconnect();
                }
            }
        }
        GeneralVariables.controlMode = ControlMode.CAT;//network control mode
        X6100Connector xieguConnector = new X6100Connector(context, xieguRadio, GeneralVariables.controlMode);
        xieguConnector.setOnWaveDataReceived(new X6100Connector.OnWaveDataReceived() {
            @Override
            public void OnDataReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });


        xieguConnector.connect();
        connectRig();
        xieguConnector.setBaseRig(baseRig);
        //receive data sent back from the rig
        xieguRadio.setOnReceiveDataListener(new X6100Radio.OnReceiveDataListener() {
            @Override
            public void onDataReceive(byte[] data) {
                baseRig.onReceiveData(data);
            }
        });


        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(xieguConnector);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {//connection takes time, wait before setting frequency
            @Override
            public void run() {
                setOperationBand();//set carrier frequency
            }
        }, 3000);
    }


    /**
     * One-time repair for a CI-V address an earlier Compose rig picker persisted in
     * decimal (FT8AF #753). {@link com.k1af.ft8af.database.DatabaseOpr} already un-mangles
     * the unambiguous (three-digit) cases at load. The two-digit ones ("88" for an IC-706's
     * 0x58) are ambiguous: the same text is what a deliberate 0x88 override written by the
     * legacy hex field looks like, and there is no longer any UI to restore such an override
     * — so they are never rewritten. Instead, when the value's provenance is unknown
     * ({@link CivAddressConfig#FORMAT_KEY} absent) and it is the model's decimal twin, the
     * user is told once to re-select the rig if it uses the default; the picker then stores
     * hex with the marker. Whenever the marker is missing or the stored text isn't canonical
     * hex, the value is written back canonically together with the marker, so the repair
     * (and the hint) really is one-time.
     */
    private void repairCivAddressAgainstModel() {
        if (GeneralVariables.instructionSet != InstructionSet.ICOM
                && GeneralVariables.instructionSet != InstructionSet.ICOM_756) {
            return;
        }
        try {
            android.content.Context ctx = GeneralVariables.getMainContext();
            if (ctx == null) return;
            RigNameList.RigName model = RigNameList.getInstance(ctx)
                    .getRigNameByIndex(GeneralVariables.modelNo);
            int before = GeneralVariables.civAddress;
            CivAddressConfig.Repair plan = CivAddressConfig.planRepair(
                    GeneralVariables.civAddressStored, GeneralVariables.civAddressFormatKnown,
                    before, model.address);
            if (plan.address != before) {
                GeneralVariables.civAddress = plan.address;
                GeneralVariables.fileLog(String.format(java.util.Locale.US,
                        "CIV: repaired stored address 0x%02X -> 0x%02X (model %s)",
                        before, plan.address, model.modelName));
            }
            if (plan.ambiguous) {
                String hint = String.format(java.util.Locale.US,
                        getStringFromResource(R.string.civ_address_ambiguous_hint),
                        plan.address, model.modelName, model.address);
                GeneralVariables.fileLog(String.format(java.util.Locale.US,
                        "CIV: stored address 0x%02X is the decimal twin of model %s (0x%02X); "
                                + "kept as-is, user hinted to re-select the rig",
                        plan.address, model.modelName, model.address));
                ToastMessage.show(hint);
            }
            if (plan.writeBack && databaseOpr != null) {
                String encoded = CivAddressConfig.encode(plan.address);
                databaseOpr.writeConfig("civ", encoded, null);
                databaseOpr.writeConfig(CivAddressConfig.FORMAT_KEY,
                        CivAddressConfig.FORMAT_HEX, null);
                GeneralVariables.civAddressStored = encoded;
                GeneralVariables.civAddressFormatKnown = true;
                GeneralVariables.fileLog(String.format(java.util.Locale.US,
                        "CIV: stored address canonicalized to \"%s\" (+%s=%s)",
                        encoded, CivAddressConfig.FORMAT_KEY, CivAddressConfig.FORMAT_HEX));
            }
        } catch (Exception e) {
            GeneralVariables.fileLog("CIV: repair skipped: " + e.getMessage());
        }
    }

    /**
     * Create different rig models based on the instruction set
     */
    private void connectRig() {

        if (baseRig != null) {
            baseRig.onDisconnecting();
        }
        baseRig = null;
        repairCivAddressAgainstModel();
        //determine the rig type: ICOM, YAESU 2, YAESU 3
        switch (GeneralVariables.instructionSet) {
            case InstructionSet.ICOM:
                baseRig = new IcomRig(GeneralVariables.civAddress, true);
                break;
            case InstructionSet.ICOM_756:
                baseRig = new IcomRig(GeneralVariables.civAddress, false);
                break;
            case InstructionSet.YAESU_2:
                baseRig = new Yaesu2Rig();
                break;
            case InstructionSet.YAESU_847:
                baseRig = new Yaesu2_847Rig();
                break;
            case InstructionSet.YAESU_3_9:
                baseRig = new Yaesu39Rig(false);//Yaesu 3rd-gen commands, 9-digit frequency, USB mode
                break;
            case InstructionSet.YAESU_3_9_U_DIG:
                baseRig = new Yaesu39Rig(true);//Yaesu 3rd-gen commands, 9-digit frequency, DATA-USB mode
                break;
            case InstructionSet.YAESU_3_8:
                baseRig = new Yaesu38Rig();//Yaesu 3rd-gen commands, 8-digit frequency
                break;
            case InstructionSet.YAESU_3_450:
                baseRig = new Yaesu38_450Rig();//Yaesu 3rd-gen commands, 8-digit frequency
                break;
            case InstructionSet.KENWOOD_TK90:
                baseRig = new KenwoodKT90Rig();//Kenwood TK90
                break;
            case InstructionSet.YAESU_DX10:
                baseRig = new YaesuDX10Rig();//YAESU DX10 DX101
                break;
            case InstructionSet.YAESU_FT710:
                baseRig = new YaesuDX10Rig();//FT-710 reuses DX10 CAT; FT-710-specific fix is in CableSerialPort
                break;
            case InstructionSet.KENWOOD_TS590:
                baseRig = new KenwoodTS590Rig();//KENWOOD TS590
                break;
            case InstructionSet.GUOHE_Q900:
                baseRig = new GuoHeQ900Rig();//GuoHe Q900
                break;
            case InstructionSet.XIEGUG90S://XieGu, USB mode
                baseRig = new XieGuRig(GeneralVariables.civAddress);//XieGu G90S
                break;
            case InstructionSet.ELECRAFT:
                baseRig = new ElecraftRig();//ELECRAFT
                break;
            case InstructionSet.FLEX_CABLE:
                baseRig = new Flex6000Rig();//FLEX6000
                break;
            case InstructionSet.FLEX_NETWORK:
                baseRig = new FlexNetworkRig();
                break;
            case InstructionSet.XIEGU_6100_FT8CNS:
                if (GeneralVariables.connectMode == ConnectMode.NETWORK) {//only works in network mode
                    baseRig = new XieGu6100NetRig(GeneralVariables.civAddress);//XieGu 6100 network mode
                } else {//otherwise use legacy mode
                    baseRig = new XieGu6100Rig(GeneralVariables.civAddress);//XieGu 6100
                }
                break;
            case InstructionSet.XIEGU_6100:
                baseRig = new XieGu6100Rig(GeneralVariables.civAddress);//XieGu 6100
                break;
            case InstructionSet.KENWOOD_TS2000:
                baseRig = new KenwoodTS2000Rig();//Kenwood TS2000
                break;
            case InstructionSet.DISCOVERY_TX500:
                baseRig = new DiscoveryTX500Rig();//Lab599 Discovery TX-500 (TS-2000 + DATA mode)
                break;
            case InstructionSet.WOLF_SDR_DIGU:
                baseRig = new Wolf_sdr_450Rig(false);
                break;
            case InstructionSet.WOLF_SDR_USB:
                baseRig = new Wolf_sdr_450Rig(true);
                break;
            case InstructionSet.TRUSDX:
                baseRig = new TrUSDXRig();//(tr)uSDX
                break;
            case InstructionSet.KENWOOD_TS570:
                baseRig = new KenwoodTS570Rig();//KENWOOD TS-570D
                break;
            case InstructionSet.KENWOOD_TS440:
                baseRig = new KenwoodTS440Rig();//KENWOOD TS-440S (TS-570 CAT, USB mode)
                break;
            case InstructionSet.HAMLIB:
                // hamlib model number is carried in the rig table's address column
                // (parsed base-16), e.g. FT-891 = 1036 = 0x40C.
                baseRig = new HamlibRig(GeneralVariables.civAddress);
                break;
        }

        // Store the rig name for display (Settings connection card).
        // Use the user-selected model name from RigNameList (same source as the
        // Settings rig picker) rather than the Java class name, which can
        // differ (e.g. YaesuDX10Rig for FT-710).
        try {
            android.content.Context ctx = GeneralVariables.getMainContext();
            if (ctx != null) {
                RigNameList rigList = RigNameList.getInstance(ctx);
                GeneralVariables.myRigName =
                        rigList.getRigNameByIndex(GeneralVariables.modelNo).modelName;
            }
        } catch (Exception e) {
            GeneralVariables.myRigName = "";
        }

        if ((GeneralVariables.instructionSet == InstructionSet.FLEX_NETWORK)
                || ((GeneralVariables.instructionSet == InstructionSet.ICOM
                || GeneralVariables.instructionSet == InstructionSet.XIEGU_6100
                || GeneralVariables.instructionSet == InstructionSet.XIEGU_6100_FT8CNS)
                && GeneralVariables.connectMode == ConnectMode.NETWORK)) {
            hamRecorder.setDataFromLan();
        } else {
            if (GeneralVariables.controlMode != ControlMode.CAT || baseRig == null
                    || !baseRig.supportWaveOverCAT()) {
                hamRecorder.setDataFromMic();
            } else {
                hamRecorder.setDataFromLan();
            }
        }

        // Wire meter data callback for ALC auto-volume + SWR halt
        if (baseRig != null && meterProtectionController != null) {
            baseRig.setOnMeterData(new BaseRig.OnMeterData() {
                @Override
                public void onMeterUpdate(int normalizedAlc, int normalizedSwr) {
                    meterProtectionController.onMeterUpdate(normalizedAlc, normalizedSwr);
                }
            });
        }

        mutableIsFlexRadio.postValue(GeneralVariables.instructionSet == InstructionSet.FLEX_NETWORK);
        mutableIsXieguRadio.postValue(GeneralVariables.instructionSet == InstructionSet.XIEGU_6100_FT8CNS);

    }


    /**
     * Reinitialize the audio input to pick up newly connected USB audio devices.
     * Call this after a USB device attach event to switch to USB audio if available.
     */
    public void reinitializeAudioInput() {
        if (hamRecorder != null) {
            hamRecorder.reinitializeMicRecorder();
        }
    }

    /**
     * Settling time before an RX channel change actually reopens the capture.
     * The selector is an A/B control — the operator flips it while watching the
     * waterfall — and each reopen costs up to a second of RX plus, on USB-direct,
     * an interface re-claim and a libusb session restart. Rapid taps therefore
     * collapse to one reopen at the final value.
     */
    static final long RX_CHANNEL_REOPEN_DEBOUNCE_MS = 400;

    // Generation stamp for the debounce above: each reopen request bumps it, and
    // a request only fires if nothing newer superseded it while it waited.
    private final java.util.concurrent.atomic.AtomicInteger rxChannelReopenGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The operator changed the RX channel selection from {@code from} to
     * {@code to}. Reopens the audio input if — and only if — the running capture
     * cannot pick the new value up live (see
     * {@link com.k1af.ft8af.wave.MicRecorder#reopenRequiredForChannelChange}),
     * debounced and on a plain daemon thread rather than a composition-scoped
     * coroutine: the settings screen may be popped in the same gesture as the
     * tap, and a reopen that dies with the screen leaves the stored setting and
     * the open capture silently disagreeing until the next USB attach.
     */
    public void onRxAudioChannelChanged(int from, int to) {
        if (hamRecorder == null || !hamRecorder.rxChannelChangeNeedsReopen(from, to)) {
            return;
        }
        final int generation = rxChannelReopenGeneration.incrementAndGet();
        Thread reopen = new Thread(() -> {
            try {
                Thread.sleep(RX_CHANNEL_REOPEN_DEBOUNCE_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (rxChannelReopenGeneration.get() != generation) {
                return; // a later tap took over; it will do the reopen
            }
            reinitializeAudioInput();
        }, "RxChannelReopen");
        reopen.setDaemon(true);
        reopen.start();
    }

    // Tracks whether we've put the phone into Bluetooth headset (SCO) mode for audio, so
    // refreshBluetoothHeadsetMode() only toggles on an actual change. setBlueToothOn() does a
    // stop+start of SCO, which is disruptive to re-issue on every settings tap.
    private boolean btHeadsetModeActive = false;

    /**
     * Bring the phone's Bluetooth headset (SCO) link up or down to match the current rig +
     * audio-device selection (FT8AF issue #723).
     *
     * <p>Before this, SCO was entered only when the <em>rig</em> connection was Bluetooth. A
     * user on a USB/VOX rig who selected a Bluetooth headset as the mic got no SCO, so the
     * app captured the built-in mic instead and the headset never appeared to work. This now
     * also enters headset mode when the selected input or output device is a Bluetooth-SCO
     * endpoint, and rebuilds the AudioRecord so capture actually routes over the link.
     *
     * <p>Idempotent and safe to call from launch and from each device-picker change.
     */
    public void refreshBluetoothHeadsetMode() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;

        int inputType = audioDeviceType(audioManager,
                GeneralVariables.audioInputDeviceId, AudioManager.GET_DEVICES_INPUTS);
        int outputType = audioDeviceType(audioManager,
                GeneralVariables.audioOutputDeviceId, AudioManager.GET_DEVICES_OUTPUTS);

        boolean want = ScoPolicy.shouldEnterHeadsetMode(GeneralVariables.connectMode,
                isBTConnected(), inputType, outputType);

        // Cross-check the cached flag against the coordinator's tracked link state: SCO
        // drops by itself when the headset disconnects (and setBlueToothOn() can fail), so
        // the flag alone would skip re-entering and leave the selected BT mic/speaker dead
        // until restart. Not AudioManager.isBluetoothScoOn(): that only mirrors the legacy
        // force-use flag, which the setSpeakerphoneOn(false) in the SCO sink clears on newer
        // builds, so it can read false with the link up — and then a "deselect BT headset"
        // would FORGET instead of LEAVE and the coordinator would keep SCO on.
        boolean linkUp = scoLink.isLinkUpOrPending();
        boolean linkHeld = scoLink.isWanted();
        boolean bluetoothRig = GeneralVariables.connectMode == ConnectMode.BLUE_TOOTH;
        switch (ScoPolicy.headsetModeAction(want, btHeadsetModeActive, linkUp, linkHeld,
                bluetoothRig)) {
            case ScoPolicy.HEADSET_MODE_ENTER:
                setBlueToothOn();
                btHeadsetModeActive = true;
                // Rebuild capture so the AudioRecord binds to the freshly-opened SCO route
                // rather than the built-in mic it was created on.
                reinitializeAudioInput();
                break;
            case ScoPolicy.HEADSET_MODE_LEAVE:
                // Leave headset mode when the user picks a non-BT device — never from under
                // a Bluetooth rig, whose TX/RX path owns SCO (headsetModeAction guards that).
                setBlueToothOff();
                btHeadsetModeActive = false;
                reinitializeAudioInput();
                break;
            case ScoPolicy.HEADSET_MODE_FORGET:
                // The coordinator no longer holds a request (TX stopSco / shutdown already
                // took the link down); nothing to tear down.
                btHeadsetModeActive = false;
                break;
            default:
                break;
        }
    }

    /**
     * {@link android.media.AudioDeviceInfo#getType()} of the routed device matching
     * {@code deviceId} among {@code flags} (inputs or outputs), or -1 if none match (Default
     * row, USB-direct entry, or nothing connected).
     */
    private int audioDeviceType(AudioManager audioManager, int deviceId, int flags) {
        if (deviceId <= 0) return -1;
        for (android.media.AudioDeviceInfo d : audioManager.getDevices(flags)) {
            if (d.getId() == deviceId) return d.getType();
        }
        return -1;
    }

    /**
     * Check whether the rig is connected. Two cases: rigBaseClass not created, or serial port connection failed.
     *
     * @return whether connected
     */
    public boolean isRigConnected() {
        if (baseRig == null) {
            return false;
        } else {
            return baseRig.isConnected();
        }
    }

    /** Whether the rig's connector believes the last PTT write was delivered. */
    private boolean lastPttWriteReachedRig() {
        if (baseRig == null) return false;
        BaseRigConnector connector = baseRig.getConnector();
        // No connector at all means nothing was written; treat as undelivered so
        // the latch stays armed rather than silently forgiving a lost unkey.
        return connector != null && connector.isLastPttWriteOk();
    }

    /**
     * Send PTT-off if we still owe the rig one, and clear the debt when it lands.
     *
     * <p>Called wherever a CAT link may have just come back: after a cable
     * reconnect, and periodically from the clock tick as a backstop. Safe to call
     * at any time — it is a no-op unless an unkey is actually outstanding, and CAT
     * PTT-off is idempotent on a rig that is already receiving.
     *
     * <p>This is the recovery for the FT8AF field failure where a USB brown-out
     * killed the port mid-transmission and the rig stayed keyed for 97 seconds
     * while the port itself was back within two.
     *
     * @return true if an unkey was owed and has now been sent successfully
     */
    public boolean retryPendingUnkey() {
        if (!pttSafetyLatch.needsUnkey()) return false;
        if (baseRig == null || !baseRig.isConnected()) return false;
        fileLog("PTT: unkey still owed after link loss — re-sending PTT-off");
        baseRig.setPTT(false);
        boolean ok = lastPttWriteReachedRig();
        pttSafetyLatch.onUnkeyAttempted(ok);
        fileLog("PTT: unkey retry " + (ok ? "delivered" : "FAILED, still owed"));
        return ok;
    }

    /**
     * Whether the connected rig has answered at least one CAT probe since the
     * current connection came up. Backs the USB Diagnostics "CAT Response" check:
     * the serial port can open ({@link #isRigConnected()}) while the rig never
     * replies — wrong baud rate, wrong CAT protocol, or a powered-but-silent
     * adapter — and this flag distinguishes "link up" from "rig actually talking".
     * Reset to false on every (re)connect and set true in {@link #markRigResponded()}.
     *
     * @return true once a valid CAT reply has been seen on the live connection
     */
    public boolean hasRigRespondedToCat() {
        return catLiveness.hasSeenResponse();
    }

    /**
     * Re-trigger the current rig's CAT connection. Backs the tap-to-reconnect
     * status chip: Bluetooth often only connects on the second attempt, so this
     * reuses the connector's existing connect() path (which, for Bluetooth, runs
     * socketConnect() again). No-op when no rig/connector is configured.
     */
    public void reconnectRig() {
        if (baseRig == null || baseRig.getConnector() == null) {
            return;
        }
        setCatConnectionState(CatConnectionState.CONNECTING);
        baseRig.getConnector().connect();
    }

    /**
     * Get the serial port device list
     */
    public void getUsbDevice() {
        serialPorts =
                CableSerialPort.listSerialPorts(GeneralVariables.getMainContext());
        mutableSerialPorts.postValue(serialPorts);
    }


    // ---- Bluetooth SCO (hands-free audio link) -------------------------------
    //
    // All entry points below go through ScoLinkCoordinator (one ScoLinkTracker
    // driven on the main thread) so that (a) a link that is already being built
    // is never stopped and restarted underneath itself, (b) a start that fails
    // or a link that drops is retried, bounded, (c) once the link is up the
    // AudioRecord is verified to be capturing from it, and (d) requests from
    // the TX worker and the main-thread broadcasts/timers are applied in
    // order, never interleaved. FT8AF issue #759 (Android 8.x: Bluetooth RX dead).

    /**
     * Is this a Bluetooth RIG whose TX audio must not ride SCO? True for
     * Bluetooth + VOX too — VOX leaves SCO up, so its media stream is on the SCO
     * route and needs the output steering just as much. A USB/network rig with a
     * Bluetooth headset picked as its mic also holds a SCO link of ours, but its
     * audio belongs on the rig: false.
     */
    private boolean needControlSco() {
        return ScoPolicy.needControlSco(GeneralVariables.connectMode,
                GeneralVariables.controlMode,
                baseRig != null,
                baseRig != null && baseRig.supportWaveOverCAT());
    }

    /**
     * Whether this app currently holds a SCO session (CONNECTING or CONNECTED),
     * per the tracker rather than {@code AudioManager.isBluetoothScoOn()} — see
     * {@link ScoLinkCoordinator#isLinkUpOrPending()}. Snapshotted by the keying
     * path into {@link #isScoHeldForTx()}; the TX path must read that snapshot,
     * not this live value, because keying stops SCO before the audio starts.
     */
    public boolean isScoLinkUpOrPending() {
        return scoLink.isLinkUpOrPending();
    }

    /**
     * Whether our own SCO link was up (or coming up) when the current transmission
     * or tune carrier was keyed. Read by the TX path, which must not steer Default
     * output onto a Bluetooth sink unless our own SCO link is the thing displacing
     * the media route (see {@code AudioOutputRoutingPolicy}). Stable for the whole
     * transmission even though keying has already asked the tracker to drop the
     * link ({@link TxScoLatch}).
     */
    public boolean isScoHeldForTx() {
        return txScoLatch.heldForTx();
    }

    /**
     * Address of the device our SCO link was on when the current transmission was
     * keyed, or null when unknown (see {@link TxScoLatch#scoAddress()}).
     */
    public String scoAddressForTx() {
        return txScoLatch.scoAddress();
    }

    /**
     * Address of the SCO device the mic is being captured from right now, or
     * null when the capture is not on SCO / the platform withholds it. Read at
     * keying time, before SCO is stopped, so the TX path knows which of several
     * hands-free devices actually carries our link.
     */
    private String routedScoInputAddress() {
        if (hamRecorder == null) return null;
        MicRecorder mic = hamRecorder.getMicRecorder();
        return mic == null ? null : mic.routedScoInputAddress();
    }

    private final Handler scoHandler = new Handler(Looper.getMainLooper());
    private final ScoLinkCoordinator scoLink = ScoLinkCoordinator.onMainThread(
            new ScoLinkCoordinator.Sink() {
                @Override
                public void startSco() {
                    AudioManager audioManager = scoAudioManager();
                    if (audioManager == null) return;
                    audioManager.setBluetoothScoOn(true);
                    audioManager.startBluetoothSco();//71ms
                    audioManager.setSpeakerphoneOn(false);//enter headset mode
                }

                @Override
                public void stopScoForRestart() {
                    AudioManager audioManager = scoAudioManager();
                    if (audioManager != null) audioManager.stopBluetoothSco();
                }

                @Override
                public void stopSco() {
                    AudioManager audioManager = scoAudioManager();
                    if (audioManager == null) return;
                    audioManager.setBluetoothScoOn(false);
                    audioManager.stopBluetoothSco();
                    audioManager.setSpeakerphoneOn(true);//exit headset mode
                }

                @Override
                public void verifyMicRouting() {
                    verifyMicOnScoLink();
                }

                @Override
                public void log(String message) {
                    GeneralVariables.fileLog(message);
                }
            });

    private AudioManager scoAudioManager() {
        Context ctx = GeneralVariables.getMainContext();
        if (ctx == null) return null;
        return (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Bring SCO up (after TX). Idempotent while a link is pending/up. */
    public void startSco() {
        AudioManager audioManager = scoAudioManager();
        if (audioManager == null) return;
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            //Bluetooth device does not support recording
            ToastMessage.show(getStringFromResource(R.string.does_not_support_recording));
            return;
        }
        scoLink.requestOn("startSco", null);
    }

    /** Take SCO down (before TX). No-op unless we asked for it. */
    public void stopSco() {
        scoLink.requestOff("stopSco", null);
    }

    /** Enter Bluetooth headset mode: SCO up for RX audio from the rig. */
    public void setBlueToothOn() {
        AudioManager audioManager = scoAudioManager();
        if (audioManager == null) return;
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            //Bluetooth device does not support recording
            ToastMessage.show(getStringFromResource(R.string.does_not_support_recording));
        }
        scoLink.requestOn("setBlueToothOn", () -> {
            /*
            MODE_NORMAL corresponds to music playback. For speaker output, call audioManager.setSpeakerphoneOn(true).
            For headset or earpiece, set mode to MODE_IN_CALL (pre-3.0) or MODE_IN_COMMUNICATION (3.0+).
            Stays NORMAL on purpose: TX audio goes out over A2DP (SCO is dropped
            around PTT), and an in-call mode would pull media to the earpiece.
             */
            audioManager.setMode(AudioManager.MODE_NORMAL);//178ms
            //entering Bluetooth headset mode
            ToastMessage.show(getStringFromResource(R.string.bluetooth_headset_mode));
        });
    }

    /** Leave Bluetooth headset mode. */
    public void setBlueToothOff() {
        AudioManager audioManager = scoAudioManager();
        if (audioManager == null) return;
        scoLink.requestOff("setBlueToothOff", () -> {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            //leaving Bluetooth headset mode
            ToastMessage.show(getStringFromResource(R.string.bluetooth_Headset_mode_cancelled));
        });
    }

    /**
     * {@code AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED} landed (main thread).
     * Logs every transition, retries a failed/dropped link while we want it,
     * and once CONNECTED makes sure the mic is really on the headset.
     */
    public void onScoAudioStateUpdated(int state, int previousState) {
        scoLink.onStateUpdated(state, previousState);
    }

    /**
     * SCO is up: is the AudioRecord capturing from it? Android's own recipe is
     * to create the record only after SCO_AUDIO_STATE_CONNECTED; ours already
     * exists (opened at app start on the built-in mic), and on some builds -
     * Oreo in the field - the force-use change never re-routes it. Rebuild it
     * so the fresh open picks the headset.
     */
    private void verifyMicOnScoLink() {
        if (!scoLink.isWanted()
                || scoLink.linkState() != AudioManager.SCO_AUDIO_STATE_CONNECTED
                || hamRecorder == null) {
            return;
        }
        MicRecorder mic = hamRecorder.getMicRecorder();
        int routed = mic.routedInputDeviceType();
        int chosen = mic.chosenInputDeviceType();
        boolean reinit = ScoLinkTracker.needsMicReinit(routed, chosen);
        GeneralVariables.fileLog("SCO: mic routedType=" + routed + " chosenType=" + chosen
                + (reinit ? " -> rebuilding AudioRecord on the SCO link" : " ok"));
        if (!reinit) return;
        reinitializeAudioInput();
        scoHandler.postDelayed(() -> {
            if (hamRecorder == null) return;
            GeneralVariables.fileLog("SCO: mic after rebuild routedType="
                    + hamRecorder.getMicRecorder().routedInputDeviceType());
        }, ScoLinkCoordinator.MIC_ROUTE_CHECK_DELAY_MS);
    }


    /**
     * Check whether Bluetooth is connected
     *
     * @return whether connected
     */
    @SuppressLint("MissingPermission")
    public boolean isBTConnected() {
        // On Android 12+, getProfileConnectionState requires BLUETOOTH_CONNECT.
        // ComposeMainActivity.onCreate asks for it asynchronously, so on the first launch this
        // method may run before the user has answered the prompt — return false rather than crash.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Context ctx = GeneralVariables.getMainContext();
            if (ctx == null
                    || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        BluetoothAdapter blueAdapter = BluetoothAdapter.getDefaultAdapter();
        if (blueAdapter == null) return false;

        try {
            //Bluetooth headset, supports voice input and output
            int headset = blueAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);
            int a2dp = blueAdapter.getProfileConnectionState(BluetoothProfile.A2DP);
            return headset == BluetoothAdapter.STATE_CONNECTED || a2dp == BluetoothAdapter.STATE_CONNECTED;
        } catch (SecurityException se) {
            return false;
        }
    }

    /**
     * Delete a single file
     *
     * @param fileName the filename of the file to delete
     */
    public static void deleteFile(String fileName) {
        File file = new File(fileName);
        // If the file at the given path exists and is a file, delete it directly
        if (file.exists() && file.isFile()) {
            file.delete();
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // The liveness watchdog otherwise only stops on disconnect/error/stale; if the
        // ViewModel is cleared while still "connected" the Timer thread would keep probing
        // the rig indefinitely. Tear it down here too.
        stopCatLivenessWatchdog();
        // The rig's own poll timers are non-daemon and are cancelled nowhere else once
        // the ViewModel is gone.
        releaseRigOnClear(baseRig);
        // Drop any SCO request of ours so the headset link doesn't outlive the app.
        scoLink.shutdown();
    }

    /**
     * Run the rig's teardown hook. The ViewModel itself cannot be constructed in a
     * unit test (the constructor starts the audio recorder and the SSTV listener),
     * so {@code MainViewModelRigCleanupTest} exercises the hook wiring through this
     * method with a recording rig. Null-safe: nothing to release before a rig was
     * ever connected.
     */
    static void releaseRigOnClear(BaseRig rig) {
        if (rig != null) {
            rig.onDisconnecting();
        }
    }

    private static final String ACTION_USB_AUDIO_PERMISSION =
            "com.k1af.ft8af.USB_AUDIO_PERMISSION";

    /**
     * Request USB audio device permission if not already granted.
     */
    public void requestUsbPermissionIfNeeded(UsbDevice device) {
        if (device == null) return;
        Context context = GeneralVariables.getMainContext();
        if (context == null) return;
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return;

        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "USB audio device already has permission");
            return;
        }

        // Same nag-storm containment as the serial path (CableSerialPort.connect):
        // a flapping link re-fires the ATTACH handler per bounce, and each call here
        // raised a fresh system dialog for the audio device.
        if (!UsbPermissionThrottle.shouldRequestNow(
                device.getVendorId(), System.currentTimeMillis())) {
            fileLog(String.format("usbPermission: audio request for vendor 0x%04x throttled"
                    + " (asked <%ds ago)", device.getVendorId(),
                    UsbPermissionThrottle.REQUEST_COOLDOWN_MS / 1000));
            return;
        }
        UsbPermissionThrottle.markRequested(device.getVendorId(), System.currentTimeMillis());

        Log.d(TAG, "Requesting USB permission for audio device: " + device.getProductName());
        PendingIntent permissionIntent = UsbPermissionIntentsKt.createUsbPermissionIntent(
                context, ACTION_USB_AUDIO_PERMISSION);

        BroadcastReceiver permReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (ACTION_USB_AUDIO_PERMISSION.equals(intent.getAction())) {
                    boolean granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    Log.d(TAG, "USB audio permission " + (granted ? "granted" : "denied"));
                    GeneralVariables.fileLog("USB audio permission "
                            + (granted ? "granted" : "denied"));
                    try {
                        ctx.unregisterReceiver(this);
                    } catch (Exception ignored) {
                    }
                    if (granted) {
                        // The recorder was constructed before this permission
                        // existed and fell back to the built-in mic (see
                        // MicRecorder.openUsbAudioInput's hasPermission check).
                        // Now that we're allowed to open the device, rebind the
                        // input so RX actually uses the USB sound card. TX is
                        // immune to this bug because it opens the device lazily
                        // at transmit time, always after this grant.
                        reinitializeAudioInput();
                    }
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permReceiver,
                    new IntentFilter(ACTION_USB_AUDIO_PERMISSION),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(permReceiver,
                    new IntentFilter(ACTION_USB_AUDIO_PERMISSION));
        }
        usbManager.requestPermission(device, permissionIntent);
    }
}
