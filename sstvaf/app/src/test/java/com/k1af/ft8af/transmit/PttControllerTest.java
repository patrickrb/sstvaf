package com.k1af.ft8af.transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.database.ControlMode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for the rig keying sequence extracted from the FT8 engine:
 * PTT-on → settle delay → play → PTT-off, with the un-key guaranteed even on
 * failure/cancel/exception, plus the CAT/RTS/DTR-vs-VOX dispatch and the SCO
 * pause/resume ordering.
 */
public class PttControllerTest {

    /** Records the exact order of keyer/SCO/play events. */
    private static class Harness {
        final List<String> events = new ArrayList<>();
        boolean rigPresent = true;
        boolean needSco = false;
        int controlMode = ControlMode.CAT;

        final PttController controller = new PttController(
                new PttController.Keyer() {
                    @Override
                    public boolean hasRig() {
                        return rigPresent;
                    }

                    @Override
                    public void setPtt(boolean on) {
                        events.add(on ? "ptt-on" : "ptt-off");
                    }
                },
                new PttController.ScoControl() {
                    @Override
                    public boolean needControlSco() {
                        return needSco;
                    }

                    @Override
                    public void stopSco() {
                        events.add("sco-stop");
                    }

                    @Override
                    public void startSco() {
                        events.add("sco-start");
                    }
                },
                () -> controlMode,
                ms -> events.add("sleep-" + ms));
    }

    // ---- control-mode dispatch -------------------------------------------------

    @Test
    public void controlsPtt_catRtsDtrYes_voxNo() {
        assertThat(PttController.controlsPtt(ControlMode.CAT)).isTrue();
        assertThat(PttController.controlsPtt(ControlMode.RTS)).isTrue();
        assertThat(PttController.controlsPtt(ControlMode.DTR)).isTrue();
        assertThat(PttController.controlsPtt(ControlMode.VOX)).isFalse();
    }

    @Test
    public void keyDown_voxMode_isNoOp() {
        Harness h = new Harness();
        h.controlMode = ControlMode.VOX;
        h.controller.keyDown();
        h.controller.keyUp();
        assertThat(h.events).isEmpty();
    }

    @Test
    public void keyDown_noRig_isNoOp() {
        Harness h = new Harness();
        h.rigPresent = false;
        h.controller.keyDown();
        h.controller.keyUp();
        assertThat(h.events).isEmpty();
    }

    // ---- SCO ordering ------------------------------------------------------------

    @Test
    public void scoIsPausedBeforeKeyDownAndResumedAfterKeyUp() {
        Harness h = new Harness();
        h.needSco = true;
        h.controller.keyDown();
        h.controller.keyUp();
        assertThat(h.events)
                .containsExactly("sco-stop", "ptt-on", "ptt-off", "sco-start")
                .inOrder();
    }

    @Test
    public void scoUntouchedWhenNotNeeded() {
        Harness h = new Harness();
        h.controller.keyDown();
        h.controller.keyUp();
        assertThat(h.events).containsExactly("ptt-on", "ptt-off").inOrder();
    }

    // ---- transmit sequencing --------------------------------------------------------

    @Test
    public void transmit_keysThenSettlesThenPlaysThenUnkeys() {
        Harness h = new Harness();
        boolean ok = h.controller.transmit(100, () -> {
            h.events.add("play");
            return true;
        });
        assertThat(ok).isTrue();
        assertThat(h.events)
                .containsExactly("ptt-on", "sleep-100", "play", "ptt-off")
                .inOrder();
    }

    @Test
    public void transmit_zeroSettleDelaySkipsSleep() {
        Harness h = new Harness();
        h.controller.transmit(0, () -> {
            h.events.add("play");
            return true;
        });
        assertThat(h.events).containsExactly("ptt-on", "play", "ptt-off").inOrder();
    }

    @Test
    public void transmit_playFailureStillUnkeys() {
        Harness h = new Harness();
        boolean ok = h.controller.transmit(0, () -> {
            h.events.add("play-failed");
            return false;// cancelled / write error
        });
        assertThat(ok).isFalse();
        assertThat(h.events)
                .containsExactly("ptt-on", "play-failed", "ptt-off")
                .inOrder();
    }

    @Test
    public void transmit_playThrowing_unkeysAndReturnsFalse() {
        Harness h = new Harness();
        boolean ok = h.controller.transmit(0, () -> {
            throw new IllegalStateException("audio device vanished");
        });
        assertThat(ok).isFalse();
        assertThat(h.events).containsExactly("ptt-on", "ptt-off").inOrder();
    }

    @Test
    public void transmit_interruptedDuringSettle_unkeysAndSetsInterruptFlag() {
        List<String> events = new ArrayList<>();
        PttController controller = new PttController(
                new PttController.Keyer() {
                    @Override
                    public boolean hasRig() {
                        return true;
                    }

                    @Override
                    public void setPtt(boolean on) {
                        events.add(on ? "ptt-on" : "ptt-off");
                    }
                },
                new PttController.ScoControl() {
                    @Override
                    public boolean needControlSco() {
                        return false;
                    }

                    @Override
                    public void stopSco() {
                    }

                    @Override
                    public void startSco() {
                    }
                },
                () -> ControlMode.CAT,
                ms -> {
                    throw new InterruptedException("stop");
                });

        boolean ok = controller.transmit(100, () -> {
            events.add("play");
            return true;
        });

        assertThat(ok).isFalse();
        assertThat(events).containsExactly("ptt-on", "ptt-off").inOrder();// play never ran
        assertThat(Thread.interrupted()).isTrue();// interrupt status restored (and cleared here)
    }

    @Test
    public void transmit_voxMode_playsWithoutKeying() {
        Harness h = new Harness();
        h.controlMode = ControlMode.VOX;
        boolean ok = h.controller.transmit(0, () -> {
            h.events.add("play");
            return true;
        });
        assertThat(ok).isTrue();
        assertThat(h.events).containsExactly("play");// audio itself keys a VOX rig
    }
}
