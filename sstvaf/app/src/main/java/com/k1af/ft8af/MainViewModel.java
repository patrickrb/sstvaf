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

import com.k1af.ft8af.bluetooth.ScoPolicy;
import com.k1af.ft8af.connector.BluetoothRigConnector;
import com.k1af.ft8af.connector.CableConnector;
import com.k1af.ft8af.connector.CableSerialPort;
import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.connector.FlexConnector;
import com.k1af.ft8af.connector.IComWifiConnector;
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
import com.k1af.ft8af.rigs.DiscoveryTX500Rig;
import com.k1af.ft8af.rigs.ElecraftRig;
import com.k1af.ft8af.rigs.Flex6000Rig;
import com.k1af.ft8af.rigs.FlexNetworkRig;
import com.k1af.ft8af.rigs.GuoHeQ900Rig;
import com.k1af.ft8af.rigs.IcomRig;
import com.k1af.ft8af.rigs.InstructionSet;
import com.k1af.ft8af.rigs.KenwoodKT90Rig;
import com.k1af.ft8af.rigs.KenwoodTS2000Rig;
import com.k1af.ft8af.rigs.KenwoodTS440Rig;
import com.k1af.ft8af.rigs.KenwoodTS570Rig;
import com.k1af.ft8af.rigs.KenwoodTS590Rig;
import com.k1af.ft8af.rigs.OnRigStateChanged;
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
import com.k1af.ft8af.transmit.TuneOperator;
import com.k1af.ft8af.ui.ToastMessage;
import com.k1af.ft8af.wave.HamRecorder;
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
            stopCatLivenessWatchdog();
            setCatConnectionState(CatConnectionState.afterDisconnect(catConnectionState));
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
            GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(freq);
            GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex);

            databaseOpr.getAllQSLCallsigns();//read out successfully contacted callsigns
        }

        @Override
        public void onRunError(String message) {
            //rig communication error
            stopCatLivenessWatchdog();
            setCatConnectionState(CatConnectionState.ERROR);
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
    private volatile long lastRigResponseMs = 0;
    private volatile boolean sawRigResponseSinceConnect = false;

    /** Record that the rig just demonstrably responded (called from onRigResponded). */
    private void markRigResponded() {
        lastRigResponseMs = System.currentTimeMillis();
        sawRigResponseSinceConnect = true;
    }

    private synchronized void startCatLivenessWatchdog() {
        stopCatLivenessWatchdog();
        lastRigResponseMs = System.currentTimeMillis();
        sawRigResponseSinceConnect = false;
        catLivenessTimer = new Timer("cat-liveness");
        catLivenessTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                catLivenessTick();
            }
        }, CAT_LIVENESS_TICK_MS, CAT_LIVENESS_TICK_MS);
    }

    private synchronized void stopCatLivenessWatchdog() {
        if (catLivenessTimer != null) {
            catLivenessTimer.cancel();
            catLivenessTimer.purge();
            catLivenessTimer = null;
        }
    }

    /** One watchdog tick: probe the rig, then declare it dead if it's gone quiet too long. */
    private void catLivenessTick() {
        try {
            boolean connected = isRigConnected();
            boolean transmitting = tuneOperator != null && tuneOperator.isTuning();
            // Actively probe (a frequency read); the reply lands in onRigResponded ->
            // markRigResponded() (onFreqChanged only fires on a change, so a stable dial
            // can't be used). On a dead-but-powered BT module the write succeeds but no
            // reply comes, so the quiet timer below eventually trips.
            if (CatLiveness.shouldProbe(connected, transmitting) && baseRig != null) {
                baseRig.readFreqFromRig();
            }
            if (CatLiveness.isRigStale(connected, transmitting, sawRigResponseSinceConnect,
                    System.currentTimeMillis(), lastRigResponseMs, CatLiveness.DEFAULT_TIMEOUT_MS)) {
                stopCatLivenessWatchdog();
                setCatConnectionState(CatConnectionState.ERROR);
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
                        if (baseRig != null) {
                            baseRig.setPTT(on);
                        }
                    }
                },
                new PttController.ScoControl() {
                    @Override
                    public boolean needControlSco() {
                        return ScoPolicy.needControlSco(GeneralVariables.connectMode,
                                GeneralVariables.controlMode,
                                baseRig != null,
                                baseRig != null && baseRig.supportWaveOverCAT());
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

        transmitAudioSink = new TransmitAudioSink();
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
     * Set the operating carrier frequency. Only operates if the rig is connected.
     */
    public void setOperationBand() {
        if (!isRigConnected()) {
            fileLog("setOperationBand: rig not connected, skipping");
            return;
        }

        fileLog("setOperationBand: sending USB mode, then freq=" + GeneralVariables.band
                + " in 800ms (controlMode=" + GeneralVariables.controlMode + ")");
        //set USB mode first, then set frequency
        baseRig.setUsbModeToRig();//set USB mode

        //delay before sending the second command to prevent XieGu X6100 disconnection issues
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                fileLog("setOperationBand: setting freq=" + GeneralVariables.band
                        + " (rig.getFreq=" + baseRig.getFreq() + ")");
                baseRig.setFreq(GeneralVariables.band);//set frequency
                baseRig.setFreqToRig();
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

        iComWifiConnector.connect();
        connectRig();//assign baseRig

        baseRig.setControlMode(GeneralVariables.controlMode);
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(iComWifiConnector);

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
     * Create different rig models based on the instruction set
     */
    private void connectRig() {

        if (baseRig != null) {
            baseRig.onDisconnecting();
        }
        baseRig = null;
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


    public void startSco() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            //Bluetooth device does not support recording
            ToastMessage.show(getStringFromResource(R.string.does_not_support_recording));
            return;
        }
        audioManager.setBluetoothScoOn(true);
        audioManager.startBluetoothSco();//71ms
        audioManager.setSpeakerphoneOn(false);//enter headset mode
    }

    public void stopSco() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (audioManager.isBluetoothScoOn()) {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setSpeakerphoneOn(true);//exit headset mode
        }

    }


    public void setBlueToothOn() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            //Bluetooth device does not support recording
            ToastMessage.show(getStringFromResource(R.string.does_not_support_recording));
        }

        /*
        MODE_NORMAL corresponds to music playback. For speaker output, call audioManager.setSpeakerphoneOn(true).
        For headset or earpiece, set mode to MODE_IN_CALL (pre-3.0) or MODE_IN_COMMUNICATION (3.0+).
         */
        audioManager.setMode(AudioManager.MODE_NORMAL);//178ms
        audioManager.setBluetoothScoOn(true);
        audioManager.stopBluetoothSco();
        audioManager.startBluetoothSco();//71ms
        audioManager.setSpeakerphoneOn(false);//enter headset mode

        //entering Bluetooth headset mode
        ToastMessage.show(getStringFromResource(R.string.bluetooth_headset_mode));

    }

    public void setBlueToothOff() {

        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (audioManager.isBluetoothScoOn()) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setSpeakerphoneOn(true);//exit headset mode
        }
        //leaving Bluetooth headset mode
        ToastMessage.show(getStringFromResource(R.string.bluetooth_Headset_mode_cancelled));

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
