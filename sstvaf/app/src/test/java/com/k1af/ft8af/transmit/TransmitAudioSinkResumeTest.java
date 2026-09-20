package com.k1af.ft8af.transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The AudioTrack-path resume behavior: a transient sink death mid-image
 * reopens the output and picks playback back up (rewound by whatever the dead
 * track had buffered but not played) instead of dropping the transmission —
 * SSTV has no next cycle to self-correct in. Only consecutive reopens with no
 * net progress give up.
 */
public class TransmitAudioSinkResumeTest {

    /** A scriptable PCM output: fails the write at a given index, reports a fixed head. */
    private static class ScriptedOutput implements TransmitAudioSink.PcmOutput {
        final List<float[]> floatWrites = new ArrayList<>();
        int framesWritten = 0;
        boolean released = false;
        int failAtWrite = -1;     // index of the write that fails (-1 = never)
        int headOverride = -1;    // playback head to report (-1 = frames written)

        @Override
        public int writeFloats(float[] data, int length) {
            if (failAtWrite >= 0 && floatWrites.size() >= failAtWrite) {
                return -6; // AudioTrack.ERROR_DEAD_OBJECT
            }
            float[] copy = new float[length];
            System.arraycopy(data, 0, copy, 0, length);
            floatWrites.add(copy);
            framesWritten += length;
            return length;
        }

        @Override
        public int writeShorts(short[] data, int length) {
            framesWritten += length;
            return length;
        }

        @Override
        public int playbackHeadPosition() {
            return headOverride >= 0 ? headOverride : framesWritten;
        }

        @Override
        public void pauseAndFlush() {
        }

        @Override
        public void release() {
            released = true;
        }
    }

    /** Hands out one scripted output per open() call. */
    private static class ScriptedFactory implements TransmitAudioSink.PcmOutputFactory {
        final List<ScriptedOutput> outputs = new ArrayList<>();
        int opened = 0;

        ScriptedFactory(ScriptedOutput... scripted) {
            for (ScriptedOutput o : scripted) outputs.add(o);
        }

        @Override
        public TransmitAudioSink.PcmOutput open(int sampleRate, boolean float32) {
            ScriptedOutput out = outputs.get(Math.min(opened, outputs.size() - 1));
            opened++;
            return out;
        }
    }

    private static TransmitAudioSink sinkFor(ScriptedFactory factory) {
        return new TransmitAudioSink(factory, ms -> { /* no real sleeping */ });
    }

    private static float[] ramp(int n) {
        float[] b = new float[n];
        for (int i = 0; i < n; i++) b[i] = (i % 100) / 100f;
        return b;
    }

    @Test
    public void transientWriteFailure_reopensAndCompletesTheWholeBuffer() {
        // 1000 samples at 1000Hz -> 50-sample chunks. First output dies at its
        // third write (150 samples buffered, all played); the reopened output
        // is healthy.
        ScriptedOutput first = new ScriptedOutput();
        first.failAtWrite = 3;
        ScriptedOutput second = new ScriptedOutput();
        ScriptedFactory factory = new ScriptedFactory(first, second);
        float[] buffer = ramp(1000);

        TransmitAudioSink.PlayResult result =
                sinkFor(factory).playViaPcmOutput(buffer, 1000, true, () -> 1.0f);

        assertThat(result).isEqualTo(TransmitAudioSink.PlayResult.COMPLETED);
        assertThat(factory.opened).isEqualTo(2);
        assertThat(first.released).isTrue();
        assertThat(second.released).isTrue();
        // Everything the first output played plus everything the second wrote
        // covers the full buffer exactly once (head == frames written, so no
        // rewind was owed).
        int total = 0;
        for (float[] w : first.floatWrites) total += w.length;
        for (float[] w : second.floatWrites) total += w.length;
        assertThat(total).isEqualTo(1000);
        // The resumed output picks up exactly where playback stopped.
        assertThat(second.floatWrites.get(0)[0]).isEqualTo(buffer[150]);
    }

    @Test
    public void resume_rewindsSamplesTheDeadTrackBufferedButNeverPlayed() {
        // First output accepts 2 chunks (100 samples) then dies having only
        // PLAYED 60 frames: 40 buffered frames were lost with the track and
        // must be transmitted again.
        ScriptedOutput first = new ScriptedOutput();
        first.failAtWrite = 2;
        first.headOverride = 60;
        ScriptedOutput second = new ScriptedOutput();
        ScriptedFactory factory = new ScriptedFactory(first, second);
        float[] buffer = ramp(1000);

        TransmitAudioSink.PlayResult result =
                sinkFor(factory).playViaPcmOutput(buffer, 1000, true, () -> 1.0f);

        assertThat(result).isEqualTo(TransmitAudioSink.PlayResult.COMPLETED);
        assertThat(second.floatWrites.get(0)[0]).isEqualTo(buffer[60]);
    }

    @Test
    public void persistentFailureWithNoProgress_givesUpAsError() {
        // Every output dies on its very first write: no progress is ever made,
        // so the sink stops after MAX_WRITE_REOPEN_ATTEMPTS reopens.
        ScriptedOutput dead = new ScriptedOutput();
        dead.failAtWrite = 0;
        ScriptedFactory factory = new ScriptedFactory(dead);

        TransmitAudioSink.PlayResult result =
                sinkFor(factory).playViaPcmOutput(ramp(1000), 1000, true, () -> 1.0f);

        assertThat(result).isEqualTo(TransmitAudioSink.PlayResult.ERROR);
        // Initial open + the bounded retries, not an infinite reopen loop.
        assertThat(factory.opened)
                .isEqualTo(TransmitAudioSink.MAX_WRITE_REOPEN_ATTEMPTS + 1);
    }

    @Test
    public void cancelDuringFailedWrite_reportsCancelledNotError() {
        ScriptedOutput first = new ScriptedOutput() {
            @Override
            public int writeFloats(float[] data, int length) {
                return -6; // dead from the start
            }
        };
        ScriptedFactory factory = new ScriptedFactory(first);
        TransmitAudioSink sink = new TransmitAudioSink(factory, ms -> { });

        // Cancel lands before play() gets anywhere: play() clears the flag on
        // entry, so flip it from inside the first (failing) write instead.
        ScriptedOutput cancelling = new ScriptedOutput() {
            @Override
            public int writeFloats(float[] data, int length) {
                sink.cancel();
                return -6;
            }
        };
        factory.outputs.set(0, cancelling);

        TransmitAudioSink.PlayResult result =
                sink.playViaPcmOutput(ramp(1000), 1000, true, () -> 1.0f);

        assertThat(result).isEqualTo(TransmitAudioSink.PlayResult.CANCELLED);
    }
}
