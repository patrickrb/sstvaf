package com.k1af.ft8af.connector;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.flex.FlexRadio;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Pins that {@link FlexConnector#disconnect()} releases the radio-side DAX
 * streams — and does it while the TCP link is still up, i.e. before
 * {@code disConnect()} — closing the historical stream-port leak (old TODO:
 * "To prevent stream ports from not being released, change the port?").
 *
 * <p>Robolectric because the {@code FlexConnector} constructor reaches
 * {@code GeneralVariables}, which touches Android types at class-load. The
 * radio's teardown seams are overridden so no audio, UDP or TCP resources are
 * created.
 */
@RunWith(RobolectricTestRunner.class)
public class FlexConnectorDisconnectTest {

    /** Records the order of teardown calls instead of touching real resources. */
    private static class RecordingFlexRadio extends FlexRadio {
        final List<String> calls = new ArrayList<>();

        @Override
        public synchronized void releaseDaxStreams() {
            calls.add("releaseDaxStreams");
        }

        @Override
        public void closeAudio() {
            calls.add("closeAudio");
        }

        @Override
        public synchronized void closeStreamPort() {
            calls.add("closeStreamPort");
        }

        @Override
        public synchronized void disConnect() {
            calls.add("disConnect");
        }
    }

    @Test
    public void disconnect_releasesDaxStreamsBeforeDroppingTheTcpLink() {
        RecordingFlexRadio radio = new RecordingFlexRadio();
        FlexConnector connector = new FlexConnector(null, radio, 0);

        connector.disconnect();

        // The "stream remove" commands can only reach the radio while the TCP
        // command link still exists, so the release must come first.
        assertThat(radio.calls)
                .containsExactly("releaseDaxStreams", "closeAudio", "closeStreamPort", "disConnect")
                .inOrder();
    }

    @Test
    public void disconnectTwice_staysSafeAndReleasesEachTime() {
        RecordingFlexRadio radio = new RecordingFlexRadio();
        FlexConnector connector = new FlexConnector(null, radio, 0);

        connector.disconnect();
        connector.disconnect();

        // releaseDaxStreams itself is idempotent (covered in
        // FlexRadioReleaseDaxStreamsTest); the connector may call it again
        // without harm and nothing throws on a second teardown.
        assertThat(radio.calls).hasSize(8);
    }
}
