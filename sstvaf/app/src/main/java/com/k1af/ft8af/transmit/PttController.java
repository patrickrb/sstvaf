package com.k1af.ft8af.transmit;

import android.util.Log;

import com.k1af.ft8af.database.ControlMode;

/**
 * Rig keying sequence around a transmission, extracted from the retired FT8
 * transmit engine (FT8TransmitSignal + MainViewModel's transmit callbacks):
 *
 * <pre>  key PTT down → settle delay → play audio → key PTT up (always)</pre>
 *
 * <p>PTT is asserted through the configured control path (CAT, RTS, or DTR —
 * {@code BaseRig.setPTT} does the per-mode dispatch); with no control path
 * (VOX) keying is a no-op and the audio itself keys the rig. Bluetooth SCO is
 * paused around CAT keying on setups that need it (the audio must go out the
 * A2DP/wired path while the SCO link carries CAT).
 *
 * <p>The un-key in {@link #transmit} lives in a {@code finally}: a play failure,
 * cancellation, or exception must never leave the rig keyed.
 */
public class PttController {
    private static final String TAG = "PttController";

    /** The rig-side keying surface (implemented over BaseRig in MainViewModel). */
    public interface Keyer {
        /** Whether a rig is present and PTT can be commanded. */
        boolean hasRig();

        /** Assert or release PTT on the rig (CAT/RTS/DTR dispatch inside). */
        void setPtt(boolean on);
    }

    /** Bluetooth SCO pause/resume hooks (no-ops when SCO isn't in play). */
    public interface ScoControl {
        boolean needControlSco();

        void stopSco();

        void startSco();
    }

    /** Current control mode supplier (GeneralVariables.controlMode). */
    public interface ControlModeSource {
        int controlMode();
    }

    /** Millisecond sleeper, injected for tests. */
    public interface Sleeper {
        void sleepMs(long ms) throws InterruptedException;
    }

    /** The audio action run between key-down and key-up. */
    public interface PlayAction {
        /** @return true if the audio played to completion. */
        boolean play();
    }

    private final Keyer keyer;
    private final ScoControl sco;
    private final ControlModeSource controlModeSource;
    private final Sleeper sleeper;

    public PttController(Keyer keyer, ScoControl sco, ControlModeSource controlModeSource) {
        this(keyer, sco, controlModeSource, new Sleeper() {
            @Override
            public void sleepMs(long ms) throws InterruptedException {
                Thread.sleep(ms);
            }
        });
    }

    PttController(Keyer keyer, ScoControl sco, ControlModeSource controlModeSource,
                  Sleeper sleeper) {
        this.keyer = keyer;
        this.sco = sco;
        this.controlModeSource = controlModeSource;
        this.sleeper = sleeper;
    }

    /**
     * Whether the given control mode commands PTT explicitly (CAT/RTS/DTR).
     * VOX (and anything unknown) does not — the audio keys the rig. Public
     * because SstvTransmitter uses it to decide whether to prepend a VOX
     * pre-tone and whether the PTT settle delay applies.
     */
    public static boolean controlsPtt(int controlMode) {
        return controlMode == ControlMode.CAT
                || controlMode == ControlMode.RTS
                || controlMode == ControlMode.DTR;
    }

    /**
     * Assert PTT (+ pause SCO) through the configured control path. No-op for
     * VOX or when no rig is connected.
     */
    public void keyDown() {
        if (!controlsPtt(controlModeSource.controlMode()) || !keyer.hasRig()) {
            return;
        }
        if (sco.needControlSco()) {
            sco.stopSco();
        }
        keyer.setPtt(true);
    }

    /** Release PTT (+ restore SCO); counterpart of {@link #keyDown()}. */
    public void keyUp() {
        if (!controlsPtt(controlModeSource.controlMode()) || !keyer.hasRig()) {
            return;
        }
        keyer.setPtt(false);
        if (sco.needControlSco()) {
            sco.startSco();
        }
    }

    /**
     * Run one keyed transmission: PTT down, wait {@code settleDelayMs} for the
     * rig to switch over, run {@code play}, then PTT up — unconditionally, even
     * when play fails, is cancelled, or throws.
     *
     * @param settleDelayMs rig settle time after the PTT command (GeneralVariables.pttDelay)
     * @param play          the blocking audio action
     * @return {@code play}'s result; false when interrupted or on exception
     */
    public boolean transmit(long settleDelayMs, PlayAction play) {
        keyDown();
        try {
            if (settleDelayMs > 0) {
                sleeper.sleepMs(settleDelayMs);
            }
            return play.play();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            Log.e(TAG, "transmit: play action failed: " + e);
            return false;
        } finally {
            keyUp();
        }
    }
}
