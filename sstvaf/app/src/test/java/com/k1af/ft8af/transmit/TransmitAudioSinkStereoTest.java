package com.k1af.ft8af.transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.wave.AudioChannelSelect;

import org.junit.Test;

/**
 * The int16 twin of {@link TxChannelLayout#layOut}: a mono chunk expanded into
 * interleaved stereo with the excluded side silenced, so a Left/Right TX
 * channel selection on the 16-bit AudioTrack path keys only the intended rig.
 */
public class TransmitAudioSinkStereoTest {

    private static final short[] MONO = {100, -200, 300};

    @Test
    public void left_silencesTheRightChannel() {
        short[] out = TransmitAudioSink.expandShortsToStereo(
                MONO, MONO.length, AudioChannelSelect.LEFT, null);
        assertThat(out).isEqualTo(new short[] {100, 0, -200, 0, 300, 0});
    }

    @Test
    public void right_silencesTheLeftChannel() {
        short[] out = TransmitAudioSink.expandShortsToStereo(
                MONO, MONO.length, AudioChannelSelect.RIGHT, null);
        assertThat(out).isEqualTo(new short[] {0, 100, 0, -200, 0, 300});
    }

    @Test
    public void both_duplicatesToEachChannel() {
        short[] out = TransmitAudioSink.expandShortsToStereo(
                MONO, MONO.length, AudioChannelSelect.BOTH, null);
        assertThat(out).isEqualTo(new short[] {100, 100, -200, -200, 300, 300});
    }

    @Test
    public void reusesAnAdequateScratchBuffer() {
        short[] scratch = new short[8];
        short[] out = TransmitAudioSink.expandShortsToStereo(
                MONO, MONO.length, AudioChannelSelect.LEFT, scratch);
        assertThat(out).isSameInstanceAs(scratch);
        assertThat(out[0]).isEqualTo((short) 100);
        assertThat(out[5]).isEqualTo((short) 0);
    }

    @Test
    public void allocatesWhenScratchIsTooSmall() {
        short[] scratch = new short[2];
        short[] out = TransmitAudioSink.expandShortsToStereo(
                MONO, MONO.length, AudioChannelSelect.LEFT, scratch);
        assertThat(out).isNotSameInstanceAs(scratch);
        assertThat(out).hasLength(6);
    }

    @Test
    public void countLimitsHowMuchOfTheChunkIsExpanded() {
        short[] out = TransmitAudioSink.expandShortsToStereo(
                MONO, 2, AudioChannelSelect.BOTH, null);
        assertThat(out).isEqualTo(new short[] {100, 100, -200, -200});
    }
}
