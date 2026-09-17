package com.k1af.ft8af.transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.database.ControlMode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link PttController.KeyingObserver} sees every keying edge — even ones that
 * don't drive PTT (VOX, no rig) — and sees key-down BEFORE the SCO pause, so a
 * snapshot of the Bluetooth link state ({@code TxScoLatch}) is taken while the
 * link is still up.
 */
public class PttControllerKeyingObserverTest {

    /** Records the order of keyer / SCO / observer events. */
    private static final class Trace {
        final List<String> events = new ArrayList<>();
        boolean hasRig = true;
        boolean needSco = true;
        int controlMode = ControlMode.CAT;

        PttController controller() {
            PttController c = new PttController(
                    new PttController.Keyer() {
                        @Override
                        public boolean hasRig() {
                            return hasRig;
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
                    ms -> { });
            c.setKeyingObserver(new PttController.KeyingObserver() {
                @Override
                public void beforeKeyDown(boolean keysViaControlPath) {
                    events.add("before-keydown:" + keysViaControlPath);
                }

                @Override
                public void afterKeyUp() {
                    events.add("after-keyup");
                }
            });
            return c;
        }
    }

    @Test
    public void catKeying_observerRunsBeforeScoPauseAndAfterScoRestore() {
        Trace t = new Trace();
        PttController c = t.controller();

        c.keyDown();
        c.keyUp();

        assertThat(t.events).containsExactly(
                "before-keydown:true", "sco-stop", "ptt-on",
                "ptt-off", "sco-start", "after-keyup").inOrder();
    }

    @Test
    public void voxKeying_observerStillSeesBothEdges_withNoPttOrSco() {
        Trace t = new Trace();
        t.controlMode = ControlMode.VOX;
        PttController c = t.controller();

        c.keyDown();
        c.keyUp();

        assertThat(t.events).containsExactly(
                "before-keydown:false", "after-keyup").inOrder();
    }

    @Test
    public void noRig_observerReportsNoControlPath() {
        Trace t = new Trace();
        t.hasRig = false;
        PttController c = t.controller();

        c.keyDown();

        assertThat(t.events).containsExactly("before-keydown:false");
    }

    @Test
    public void transmit_observerBracketsThePlayAction() {
        Trace t = new Trace();
        PttController c = t.controller();

        boolean ok = c.transmit(0, () -> {
            t.events.add("play");
            return true;
        });

        assertThat(ok).isTrue();
        assertThat(t.events).containsExactly(
                "before-keydown:true", "sco-stop", "ptt-on", "play",
                "ptt-off", "sco-start", "after-keyup").inOrder();
    }

    @Test
    public void clearingTheObserver_stopsNotifications() {
        Trace t = new Trace();
        PttController c = t.controller();
        c.setKeyingObserver(null);

        c.keyDown();
        c.keyUp();

        assertThat(t.events).containsExactly("sco-stop", "ptt-on", "ptt-off", "sco-start").inOrder();
    }
}
