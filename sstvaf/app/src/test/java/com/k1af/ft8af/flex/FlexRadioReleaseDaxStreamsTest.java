package com.k1af.ft8af.flex;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Coverage for {@link FlexRadio#releaseDaxStreams()} — the fix for the DAX
 * stream leak: nothing ever removed the session's {@code stream create}d DAX
 * RX/TX streams on disconnect, so each app session left two stream objects on
 * the radio (the old TODO in {@code FlexConnector} suggested bumping the local
 * UDP port as a workaround instead).
 *
 * <p>Same fake pattern as {@link FlexRadioAudioWriteTest}: subclass the real
 * radio and override the {@code sendCommand} seam — no TCP, no Robolectric.
 */
public class FlexRadioReleaseDaxStreamsTest {

    private static class RecordingFlexRadio extends FlexRadio {
        final List<String> commands = new ArrayList<>();

        @Override
        public void sendCommand(FlexCommand command, String cmdContent) {
            commands.add(command + "|" + cmdContent);
        }
    }

    // ---- pure command building ----

    @Test
    public void removeCommands_bothStreams() {
        assertThat(FlexRadio.daxStreamRemoveCommands(0x04000008L, 0x84000001L))
                .containsExactly("stream remove 0x4000008", "stream remove 0x84000001")
                .inOrder();
    }

    @Test
    public void removeCommands_zeroIdsMeanNothingToRemove() {
        assertThat(FlexRadio.daxStreamRemoveCommands(0, 0)).isEmpty();
        assertThat(FlexRadio.daxStreamRemoveCommands(0x04000008L, 0))
                .containsExactly("stream remove 0x4000008");
        assertThat(FlexRadio.daxStreamRemoveCommands(0, 0x84000001L))
                .containsExactly("stream remove 0x84000001");
    }

    // ---- releaseDaxStreams behavior ----

    @Test
    public void releaseSendsRemoveForEachCreatedStream_handlingNegativeIntTxId() {
        RecordingFlexRadio radio = new RecordingFlexRadio();
        radio.daxAudioStreamId = 0x04000008L;
        // DAX TX ids like 0x84000001 are negative as int — the hex sent to the
        // radio must be the unsigned value, not 0xffffffff84000001.
        radio.daxTxAudioStreamId = 0x84000001;

        radio.releaseDaxStreams();

        assertThat(radio.commands)
                .containsExactly("STREAM_REMOVE|stream remove 0x4000008"
                        , "STREAM_REMOVE|stream remove 0x84000001")
                .inOrder();
    }

    @Test
    public void releaseIsIdempotent() {
        RecordingFlexRadio radio = new RecordingFlexRadio();
        radio.daxAudioStreamId = 0x04000008L;
        radio.daxTxAudioStreamId = 0x84000001;

        radio.releaseDaxStreams();
        radio.releaseDaxStreams(); // e.g. a second disconnect

        assertThat(radio.commands).hasSize(2);
        assertThat(radio.daxAudioStreamId).isEqualTo(0);
        assertThat(radio.daxTxAudioStreamId).isEqualTo(0);
    }

    @Test
    public void releaseWithNoStreamsCreatedSendsNothing() {
        // Connect failed before the "stream create" responses arrived — there
        // is nothing to remove and no command goes out.
        RecordingFlexRadio radio = new RecordingFlexRadio();

        radio.releaseDaxStreams();

        assertThat(radio.commands).isEmpty();
    }

    @Test
    public void releaseWhileDisconnectedIsSafeOnTheRealSendPath() {
        // Un-overridden sendCommand drops commands without a TCP link, so
        // releasing after the link is already gone must not throw.
        FlexRadio radio = new FlexRadio();
        radio.daxAudioStreamId = 0x04000008L;
        radio.daxTxAudioStreamId = 0x84000001;

        radio.releaseDaxStreams(); // must not throw

        assertThat(radio.daxAudioStreamId).isEqualTo(0);
        assertThat(radio.daxTxAudioStreamId).isEqualTo(0);
    }
}
