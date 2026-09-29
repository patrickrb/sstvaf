package com.k1af.ft8af.connector;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.x6100.X6100Radio;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Coverage for {@link X6100Connector#routeHandshakeResponse} — the mapping
 * from radio command responses onto the {@link X6100StreamOpener} state
 * machine. The connector's {@code onResponse} listener delegates here, so
 * these tests pin the routing rules without needing a live TCP connection.
 */
public class X6100ConnectorHandshakeTest {

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

    @Test
    public void streamResponseWithPort_confirmsThePort_caseInsensitively() {
        X6100StreamOpener opener = new X6100StreamOpener(new RecordingTransport());

        // Same rule as the legacy streamIsOn latch: only a STREAM response
        // carrying PORT= means the stream port is open, in any case.
        X6100Connector.routeHandshakeResponse(opener
                , X6100Radio.XieguCommand.STREAM, "ok port=7003");
        assertThat(opener.isStreamPortOpen()).isTrue();
    }

    @Test
    public void streamResponseWithoutPort_doesNotConfirmThePort() {
        X6100StreamOpener opener = new X6100StreamOpener(new RecordingTransport());

        // "stream get" also answers as STREAM — it must not confirm the port.
        X6100Connector.routeHandshakeResponse(opener
                , X6100Radio.XieguCommand.STREAM, "stream info 48000");
        assertThat(opener.isStreamPortOpen()).isFalse();
    }

    @Test
    public void audioAndSubResponses_ackTheMatchingFollowups() {
        RecordingTransport transport = new RecordingTransport();
        X6100StreamOpener opener = new X6100StreamOpener(transport);
        opener.onStreamPortOpen();

        // AUDIO acks "audio get all": the next tick retries only "sub all".
        X6100Connector.routeHandshakeResponse(opener
                , X6100Radio.XieguCommand.AUDIO, "audio 48000 768 64000");
        assertThat(opener.tick()).isTrue();
        assertThat(transport.sent).containsExactly("sub");

        // SUB acks "sub all": the handshake is complete.
        X6100Connector.routeHandshakeResponse(opener
                , X6100Radio.XieguCommand.SUB, "ok");
        assertThat(opener.tick()).isFalse();
        assertThat(opener.isComplete()).isTrue();
    }

    @Test
    public void unrelatedCommandsAndNulls_areIgnoredSafely() {
        X6100StreamOpener opener = new X6100StreamOpener(new RecordingTransport());

        X6100Connector.routeHandshakeResponse(opener, X6100Radio.XieguCommand.PTT, "ok");
        X6100Connector.routeHandshakeResponse(opener, X6100Radio.XieguCommand.STREAM, null);
        X6100Connector.routeHandshakeResponse(opener, null, "ok");
        X6100Connector.routeHandshakeResponse(null, X6100Radio.XieguCommand.AUDIO, "ok");

        assertThat(opener.isStreamPortOpen()).isFalse();
        assertThat(opener.isComplete()).isFalse();
    }

    // ---- stale-opener cancellation on reconnect / disconnect ----

    /** Thread-safe transport: the opener thread writes, the test thread reads. */
    private static class ConcurrentRecordingTransport implements X6100StreamOpener.Transport {
        final List<String> sent = new CopyOnWriteArrayList<>();

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

    private static X6100Connector newConnector() {
        // Context is unused by the constructor; X6100Radio's no-arg constructor
        // touches no Android machinery, so the connector's handshake plumbing
        // is testable in a plain JVM.
        return new X6100Connector(null, new X6100Radio(), 0);
    }

    @Test
    public void reconnect_cancelsThePriorOpenerAndStopsItsThread() throws Exception {
        X6100Connector connector = newConnector();
        ConcurrentRecordingTransport first = new ConcurrentRecordingTransport();

        connector.startStreamHandshake(first);
        X6100StreamOpener firstOpener = connector.peekStreamOpener();
        Thread firstThread = connector.peekStreamOpenerThread();
        assertThat(firstOpener).isNotNull();
        assertThat(firstThread).isNotNull();

        // A quick reconnect starts a new handshake: the stale opener must be
        // cancelled and its thread interrupted out of the 300 ms sleep — the
        // old behavior let it spray "open stream" batches onto the NEW live
        // socket for up to 30 s.
        ConcurrentRecordingTransport second = new ConcurrentRecordingTransport();
        connector.startStreamHandshake(second);

        assertThat(firstOpener.isCancelled()).isTrue();
        firstThread.join(2000);
        assertThat(firstThread.isAlive()).isFalse();

        // Only the new session's opener is live now.
        X6100StreamOpener secondOpener = connector.peekStreamOpener();
        assertThat(secondOpener).isNotNull();
        assertThat(secondOpener).isNotSameInstanceAs(firstOpener);
        assertThat(secondOpener.isCancelled()).isFalse();

        // With the first thread dead, its transport's traffic is frozen: any
        // commands arriving after the reconnect can only come from the second
        // opener.
        int firstSendsAfterDeath = first.sent.size();
        long deadline = System.currentTimeMillis() + 2000;
        while (second.sent.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(second.sent).isNotEmpty();
        assertThat(first.sent).hasSize(firstSendsAfterDeath);

        connector.cancelPendingHandshake();//Cleanup: stop the second thread too
    }

    @Test
    public void cancelPendingHandshake_stopsTheRunningHandshake() throws Exception {
        // This is the disconnect() path: letting go of the radio must not
        // leave an opener thread ticking toward the next session's socket.
        X6100Connector connector = newConnector();
        ConcurrentRecordingTransport transport = new ConcurrentRecordingTransport();

        connector.startStreamHandshake(transport);
        X6100StreamOpener opener = connector.peekStreamOpener();
        Thread thread = connector.peekStreamOpenerThread();

        connector.cancelPendingHandshake();

        assertThat(opener.isCancelled()).isTrue();
        thread.join(2000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(connector.peekStreamOpener()).isNull();
        assertThat(connector.peekStreamOpenerThread()).isNull();
    }

    @Test
    public void cancelPendingHandshake_withNoHandshake_isANoOp() {
        X6100Connector connector = newConnector();
        connector.cancelPendingHandshake();//Nothing running — must not throw
        assertThat(connector.peekStreamOpener()).isNull();
        assertThat(connector.peekStreamOpenerThread()).isNull();
    }
}
