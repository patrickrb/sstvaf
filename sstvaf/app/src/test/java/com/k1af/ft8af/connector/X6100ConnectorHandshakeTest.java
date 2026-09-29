package com.k1af.ft8af.connector;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.x6100.X6100Radio;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

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
}
