package com.k1af.ft8af.transmit;

import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.rigs.BaseRigOperation;
import com.k1af.ft8af.ui.ToastMessage;

/**
 * The Tune function: an on-demand single-tone carrier for antenna/amplifier
 * tuning, extracted from the retired FT8 transmit engine. Keys the rig via
 * {@link PttController}, streams a phase-continuous tone from
 * {@link TuneToneGenerator} through {@link TransmitAudioSink}, and lets
 * {@link TuneController} enforce the safety invariants (hard max-on timeout,
 * single flight, deterministic stop reason).
 *
 * <p>The audio worker owns keying + sink teardown in a {@code finally} — a
 * stuck carrier is never acceptable.
 */
public class TuneOperator {
    private static final String TAG = "TuneOperator";

    /** Why a tune request was refused; NONE means it may start. */
    public enum TuneBlockReason {
        NONE,
        TX_ACTIVE,
        SWR_LOCKED,
        WSPR_FREQUENCY,
        UNSUPPORTED_ROUTE,
    }

    private final TuneController tuneController = new TuneController(System::currentTimeMillis);
    private final PttController pttController;
    private final TransmitAudioSink audioSink;
    private final MeterProtectionController meterProtectionController;

    public final MutableLiveData<Boolean> mutableIsTuning = new MutableLiveData<>(false);
    public final MutableLiveData<Integer> mutableTuneRemainingSec = new MutableLiveData<>(0);

    public TuneOperator(PttController pttController, TransmitAudioSink audioSink,
                        MeterProtectionController meterProtectionController) {
        this.pttController = pttController;
        this.audioSink = audioSink;
        this.meterProtectionController = meterProtectionController;
    }

    /**
     * Pure gate for starting the tune carrier. Ordered by severity: a live
     * transmission wins (tune must never fight another TX for the rig), then
     * the protections a TX path applies (SWR lockout, WSPR sub-band blacklist —
     * tune must not be a backdoor around TX inhibits), then route support (tune
     * plays through the Android AudioTrack sink only; the truSDX CAT-audio,
     * network-rig, and USB-direct routes are follow-ups).
     */
    static TuneBlockReason tuneBlockReason(boolean txActive,
                                           boolean swrLocked,
                                           boolean wsprFrequency,
                                           boolean catAudioRoute,
                                           boolean networkRoute,
                                           boolean usbDirectRoute) {
        if (txActive) return TuneBlockReason.TX_ACTIVE;
        if (swrLocked) return TuneBlockReason.SWR_LOCKED;
        if (wsprFrequency) return TuneBlockReason.WSPR_FREQUENCY;
        if (catAudioRoute || networkRoute || usbDirectRoute) {
            return TuneBlockReason.UNSUPPORTED_ROUTE;
        }
        return TuneBlockReason.NONE;
    }

    public boolean isTuning() {
        return tuneController.isActive();
    }

    /** Milliseconds until the tune safety timeout fires; 0 when not tuning. */
    public long tuneRemainingMs() {
        return tuneController.remainingMs();
    }

    /**
     * Start the tune carrier: key PTT, play a steady tone at the current TX
     * offset until {@link #stopTune()}, the max-on timeout, or an error.
     * Returns false (with an operator toast) when blocked.
     */
    public boolean startTune() {
        TuneBlockReason block = tuneBlockReason(
                false,
                meterProtectionController != null && meterProtectionController.isSwrLocked(),
                BaseRigOperation.checkIsWSPR2(
                        GeneralVariables.band + Math.round(GeneralVariables.getBaseFrequency())),
                catAudioRouteActive(),
                GeneralVariables.connectMode == ConnectMode.NETWORK,
                TransmitAudioSink.isUsbDirectOutput(GeneralVariables.audioOutputDeviceId,
                        GeneralVariables.usbAudioOutputVendorId));
        switch (block) {
            case TX_ACTIVE:
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.tune_blocked_tx));
                return false;
            case SWR_LOCKED:
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.swr_lockout_toast));
                return false;
            case WSPR_FREQUENCY:
                ToastMessage.show(String.format(
                        GeneralVariables.getStringFromResource(R.string.use_wspr2_error)
                        , BaseRigOperation.getFrequencyAllInfo(GeneralVariables.band)));
                return false;
            case UNSUPPORTED_ROUTE:
                ToastMessage.show(GeneralVariables.getStringFromResource(
                        R.string.tune_unavailable_route));
                return false;
            case NONE:
            default:
                break;
        }
        if (!tuneController.tryStart(GeneralVariables.tuneMaxOnSeconds)) {
            return false; // already tuning
        }
        Thread worker = new Thread(this::playTuneTone, "TuneTone");
        worker.start();
        return true;
    }

    /** Operator stop: the worker ramps the tone down and unkeys. */
    public void stopTune() {
        tuneController.requestStop(TuneController.STOP_USER);
    }

    /** Whether the truSDX-style audio-over-CAT route is the active TX path. */
    private CatAudioRouteCheck catAudioRouteCheck;

    /** Hook so MainViewModel can report whether audio-over-CAT is in effect. */
    public interface CatAudioRouteCheck {
        boolean isActive();
    }

    public void setCatAudioRouteCheck(CatAudioRouteCheck check) {
        this.catAudioRouteCheck = check;
    }

    private boolean catAudioRouteActive() {
        return catAudioRouteCheck != null && catAudioRouteCheck.isActive();
    }

    /** The tune level (0..100) resolved from settings, as an amplitude 0..1. */
    private static float currentTuneAmplitude() {
        return radio.ks3ckc.sstvaf.TuneLevelKt.currentTuneLevel() / 100f;
    }

    /**
     * Tune audio worker. Owns keying and the audio sink for its whole life;
     * every exit path (stop, timeout, write error, exception) releases PTT via
     * the finally block.
     */
    private void playTuneTone() {
        long startedAt = System.currentTimeMillis();
        float offsetHz = GeneralVariables.getBaseFrequency();
        boolean keyed = false;
        try {
            GeneralVariables.fileLog(String.format(
                    "TUNE: start offset=%.0fHz level=%d%% maxOn=%ds rate=%d",
                    offsetHz, radio.ks3ckc.sstvaf.TuneLevelKt.currentTuneLevel(),
                    TuneController.clampMaxOnSeconds(GeneralVariables.tuneMaxOnSeconds),
                    GeneralVariables.audioSampleRate));
            pttController.keyDown();
            keyed = true;
            mutableIsTuning.postValue(true);

            int sampleRate = GeneralVariables.audioSampleRate;
            TuneToneGenerator generator = new TuneToneGenerator(offsetHz, sampleRate,
                    Math.max(1, sampleRate / 200)); // ~5ms ramp

            final int[] lastPostedSec = {-1};
            TransmitAudioSink.ChunkSource source = (out, maxLen) -> {
                if (!tuneController.shouldContinue()) {
                    generator.requestStop(); // final chunk(s) ramp to zero
                }
                // Level read fresh per chunk so a settings change lands mid-tune.
                int written = generator.nextChunk(out, maxLen, currentTuneAmplitude());
                int remainingSec = (int) Math.ceil(tuneController.remainingMs() / 1000.0);
                if (remainingSec != lastPostedSec[0]) {
                    lastPostedSec[0] = remainingSec;
                    mutableTuneRemainingSec.postValue(remainingSec);
                }
                return written;
            };

            TransmitAudioSink.PlayResult result = audioSink.playStream(
                    source, sampleRate, GeneralVariables.audioOutput32Bit);
            if (result == TransmitAudioSink.PlayResult.ERROR) {
                tuneController.requestStop(TuneController.STOP_ERROR);
            }
        } catch (Exception e) {
            Log.e(TAG, "Tune worker failed: " + e);
            tuneController.requestStop(TuneController.STOP_ERROR);
        } finally {
            // Single point of teardown: whatever happened above, the carrier
            // stops and PTT drops here.
            tuneController.requestStop(TuneController.STOP_ERROR);
            if (keyed) {
                try {
                    pttController.keyUp();
                } catch (Exception e) {
                    Log.e(TAG, "Tune key-up failed: " + e);
                }
            }
            mutableIsTuning.postValue(false);
            // Between-cycle meter accumulator reset (ALC auto-volume).
            if (meterProtectionController != null) {
                meterProtectionController.onTxCycleEnd();
            }
            String reason = tuneController.lastStopReason();
            GeneralVariables.fileLog(String.format(
                    "TUNE: stop reason=%s durationMs=%d offset=%.0fHz",
                    reason, System.currentTimeMillis() - startedAt, offsetHz));
            if (TuneController.STOP_TIMEOUT.equals(reason)) {
                ToastMessage.show(String.format(
                        GeneralVariables.getStringFromResource(R.string.tune_stopped_timeout),
                        TuneController.clampMaxOnSeconds(GeneralVariables.tuneMaxOnSeconds)));
            }
        }
    }
}
