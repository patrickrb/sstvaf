package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The TX-idle gate in front of a USB capture reinit. A reinit force-reclaims
 * the shared USB audio device, so running it while a transmission is on the
 * air kills the image seconds after key-down — the gate must hold the reinit
 * until TX ends, and must not hold it forever on a wedged flag.
 */
public class MicRecorderTxIdleWaitTest {

    @Test
    public void idleTx_returnsImmediatelyWithoutSleeping() {
        List<Long> sleeps = new ArrayList<>();

        long waited = MicRecorder.awaitTxIdle(() -> false, sleeps::add, 250, 5000);

        assertThat(waited).isEqualTo(0);
        assertThat(sleeps).isEmpty();
    }

    @Test
    public void activeTx_waitsUntilTheTransmissionEnds() {
        // TX stays on the air for three polls, then drops.
        AtomicInteger polls = new AtomicInteger();
        List<Long> sleeps = new ArrayList<>();

        long waited = MicRecorder.awaitTxIdle(
                () -> polls.getAndIncrement() < 3, sleeps::add, 250, 60_000);

        assertThat(waited).isEqualTo(750);
        assertThat(sleeps).containsExactly(250L, 250L, 250L);
    }

    @Test
    public void wedgedTxFlag_stopsWaitingAtTheCap() {
        long waited = MicRecorder.awaitTxIdle(() -> true, ms -> { }, 250, 1000);

        assertThat(waited).isEqualTo(1000);
    }

    @Test
    public void interrupt_stopsTheWaitAndReassertsTheFlag() {
        long waited = MicRecorder.awaitTxIdle(
                () -> true,
                ms -> {
                    throw new InterruptedException();
                },
                250, 60_000);

        assertThat(waited).isEqualTo(0);
        assertThat(Thread.interrupted()).isTrue(); // also clears it for the next test
    }
}
