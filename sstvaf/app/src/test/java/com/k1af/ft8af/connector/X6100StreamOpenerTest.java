package com.k1af.ft8af.connector;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit coverage for {@link X6100StreamOpener} — the fix for the historical
 * "Commands are frequently dropped here" TODO in {@code X6100Connector}: the
 * {@code audio get all} / {@code sub all} follow-ups sent while the stream
 * handshake was still in progress could be dropped by the radio, and the old
 * loop exited on port confirmation without ever resending them.
 *
 * <p>Pure state machine: no threads, no sleeps — the connector's 300 ms tick
 * is driven by calling {@link X6100StreamOpener#tick()} directly.
 */
public class X6100StreamOpenerTest {

    private static class RecordingTransport implements X6100StreamOpener.Transport {
        final List<String> sent = new ArrayList<>();

        @Override
        public void sendOpenStream() {
            sent.add("open");
        }

        @Override
        public void sendGetAudioInfo() {
            sent.add("audio");
        }

        @Override
        public void sendSubAllMeter() {
            sent.add("sub");
        }
    }

    /** Opener with a controllable clock (starts at 0). */
    private static class TestOpener extends X6100StreamOpener {
        long nowMs; // 0 while the super constructor captures startedAt

        TestOpener(Transport transport) {
            super(transport);
        }

        @Override
        protected long now() {
            return nowMs;
        }
    }

    @Test
    public void beforePortConfirmation_everyTickResendsTheWholeBatch() {
        RecordingTransport transport = new RecordingTransport();
        TestOpener opener = new TestOpener(transport);

        // Original behavior preserved: open + follow-ups each round until the
        // radio answers with the stream port.
        assertThat(opener.tick()).isTrue();
        assertThat(opener.tick()).isTrue();
        assertThat(transport.sent)
                .containsExactly("open", "audio", "sub", "open", "audio", "sub")
                .inOrder();
        assertThat(opener.isComplete()).isFalse();
    }

    @Test
    public void neverConfirmed_timesOutAfterThirtySeconds() {
        RecordingTransport transport = new RecordingTransport();
        TestOpener opener = new TestOpener(transport);

        assertThat(opener.tick()).isTrue();
        opener.nowMs = X6100StreamOpener.OPEN_TIMEOUT_MS; // 30 s later, still no port
        assertThat(opener.tick()).isFalse();
        assertThat(opener.hasTimedOut()).isTrue();
        assertThat(opener.hasGivenUp()).isFalse();
        assertThat(opener.isComplete()).isFalse();
        // The timed-out tick sent nothing, and the opener stays finished.
        assertThat(transport.sent).hasSize(3);
        assertThat(opener.tick()).isFalse();
        assertThat(transport.sent).hasSize(3);
    }

    @Test
    public void afterPortConfirmation_onlyUnansweredFollowupsAreResent() {
        RecordingTransport transport = new RecordingTransport();
        TestOpener opener = new TestOpener(transport);

        opener.tick(); // open+audio+sub — the batch the radio may have dropped
        opener.onStreamPortOpen();
        transport.sent.clear();

        // Port is up: no more "open", but both dropped follow-ups are retried.
        assertThat(opener.tick()).isTrue();
        assertThat(transport.sent).containsExactly("audio", "sub").inOrder();

        // Audio info answered — only the meter subscription is still retried.
        opener.onAudioInfoResponse();
        transport.sent.clear();
        assertThat(opener.tick()).isTrue();
        assertThat(transport.sent).containsExactly("sub");

        // Both answered — handshake complete, nothing more is sent.
        opener.onMeterSubResponse();
        transport.sent.clear();
        assertThat(opener.tick()).isFalse();
        assertThat(transport.sent).isEmpty();
        assertThat(opener.isComplete()).isTrue();
        assertThat(opener.hasGivenUp()).isFalse();
        assertThat(opener.hasTimedOut()).isFalse();
    }

    @Test
    public void responsesArrivingBetweenTicks_finishWithoutAnotherSend() {
        RecordingTransport transport = new RecordingTransport();
        TestOpener opener = new TestOpener(transport);

        opener.tick();
        // All three answers arrive during the 300 ms sleep.
        opener.onStreamPortOpen();
        opener.onAudioInfoResponse();
        opener.onMeterSubResponse();
        transport.sent.clear();

        assertThat(opener.tick()).isFalse();
        assertThat(transport.sent).isEmpty();
        assertThat(opener.isComplete()).isTrue();
    }

    @Test
    public void followupsNeverAnswered_givesUpAfterBoundedRetries() {
        RecordingTransport transport = new RecordingTransport();
        TestOpener opener = new TestOpener(transport);
        opener.onStreamPortOpen();

        for (int i = 0; i < X6100StreamOpener.MAX_POST_OPEN_ATTEMPTS; i++) {
            assertThat(opener.tick()).isTrue();
        }
        assertThat(opener.tick()).isFalse(); // budget exhausted
        assertThat(opener.hasGivenUp()).isTrue();
        assertThat(opener.hasTimedOut()).isFalse();
        assertThat(opener.isComplete()).isFalse();
        // Exactly MAX_POST_OPEN_ATTEMPTS retry batches — bounded, no hammering.
        assertThat(transport.sent).hasSize(X6100StreamOpener.MAX_POST_OPEN_ATTEMPTS * 2);
    }

    @Test
    public void cancelledOpener_ticksAreNoOpsAndSendNothing() {
        RecordingTransport transport = new RecordingTransport();
        TestOpener opener = new TestOpener(transport);

        assertThat(opener.tick()).isTrue(); // one live round
        opener.cancel();

        // Every subsequent tick finishes immediately without touching the
        // transport — this is what stops a stale opener thread from spamming
        // the new session's socket after a quick reconnect.
        assertThat(opener.tick()).isFalse();
        assertThat(opener.tick()).isFalse();
        assertThat(transport.sent).hasSize(3); // just the pre-cancel batch
        assertThat(opener.isCancelled()).isTrue();
        // Cancel is not a failure mode: neither timeout nor give-up is reported.
        assertThat(opener.hasTimedOut()).isFalse();
        assertThat(opener.hasGivenUp()).isFalse();
    }

    @Test
    public void cancelAfterPortConfirmation_stopsFollowupRetriesToo() {
        RecordingTransport transport = new RecordingTransport();
        TestOpener opener = new TestOpener(transport);

        opener.onStreamPortOpen();
        opener.cancel();

        assertThat(opener.tick()).isFalse();
        assertThat(transport.sent).isEmpty();
    }

    @Test
    public void openTimeoutDoesNotApplyOncePortIsConfirmed() {
        RecordingTransport transport = new RecordingTransport();
        TestOpener opener = new TestOpener(transport);

        opener.onStreamPortOpen();
        opener.nowMs = X6100StreamOpener.OPEN_TIMEOUT_MS * 2; // way past the open timeout
        // The 30 s clock guards only the port confirmation; follow-up retries
        // are bounded by attempt count instead.
        assertThat(opener.tick()).isTrue();
        assertThat(opener.hasTimedOut()).isFalse();
        assertThat(transport.sent).containsExactly("audio", "sub").inOrder();
    }
}
