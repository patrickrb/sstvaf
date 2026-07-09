package com.k1af.ft8af.transmit;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.ui.ToastMessage;
import com.k1af.ft8af.wave.UsbAudioDevice;
import com.k1af.ft8af.wave.UsbAudioNative;

/**
 * The transmit audio playback sink, extracted from the retired FT8 transmit
 * engine ({@code FT8TransmitSignal}). Plays a mono float waveform through the
 * currently selected output route:
 *
 * <ol>
 *   <li><b>Rig wave route</b> (network rigs / truSDX audio-over-CAT) — the
 *       whole buffer is handed to the rig, which streams it itself.</li>
 *   <li><b>Direct USB audio</b> — libusb-backed {@link UsbAudioDevice#writeAudio};
 *       TX volume is applied live inside the native write loop.</li>
 *   <li><b>AudioTrack</b> (Android default sink) — chunked MODE_STREAM playback
 *       with the TX volume re-read per ~50ms chunk, so a slider move lands
 *       mid-transmission.</li>
 * </ol>
 *
 * <p>Two hard-won behaviors are preserved here (see CLAUDE.md):
 * <ul>
 *   <li>The buffer is played <b>in full</b> — no leading samples are ever
 *       clipped. (The old {@code lateStartSkipMs} trim was FT8 slot logic;
 *       SSTV has no slot.)</li>
 *   <li>Cancellation never releases the AudioTrack from the UI thread: the
 *       worker owns teardown; {@link #cancel()} only flips the flag and
 *       pause+flushes for immediate silence.</li>
 * </ul>
 */
public class TransmitAudioSink {
    private static final String TAG = "TransmitAudioSink";

    /** Live TX volume source, read fresh per chunk. */
    public interface VolumeSource {
        /** Gain 0.0-1.0. */
        float volume();
    }

    /** Open-ended chunk producer for streamed tones (Tune). */
    public interface ChunkSource {
        /**
         * Fill {@code out[0..maxLen)} with the next (already volume-scaled)
         * samples. Return the number written; {@code <= 0} ends the stream.
         */
        int nextChunk(float[] out, int maxLen);
    }

    /** Result of a playback call. */
    public enum PlayResult { COMPLETED, CANCELLED, ERROR }

    /**
     * Abstraction over the PCM output device so the chunked loop is
     * unit-testable without an Android AudioTrack.
     */
    public interface PcmOutput {
        /** Blocking write; returns frames written or a negative error. */
        int writeFloats(float[] data, int length);

        /** Blocking write; returns frames written or a negative error. */
        int writeShorts(short[] data, int length);

        /** Current playback head position in frames (for the drain wait). */
        int playbackHeadPosition();

        /** Immediate silence: pause + flush (never release — worker owns that). */
        void pauseAndFlush();

        /** Worker-thread teardown. */
        void release();
    }

    /** Factory for the PCM output; the default opens a real AudioTrack. */
    public interface PcmOutputFactory {
        PcmOutput open(int sampleRate, boolean float32) throws Exception;
    }

    /**
     * Optional rig-side wave route (network rig / truSDX over CAT). When
     * available it takes priority over local audio output.
     */
    public interface RigWaveRoute {
        boolean isAvailable();

        /** Send the whole waveform through the rig. Blocking; true on success. */
        boolean sendWave(float[] buffer, int sampleRate);
    }

    /** Millisecond sleeper, injected for tests. */
    public interface Sleeper {
        void sleepMs(long ms) throws InterruptedException;
    }

    private final PcmOutputFactory outputFactory;
    private final Sleeper sleeper;
    private RigWaveRoute rigWaveRoute;

    private volatile boolean cancelled = false;
    private volatile PcmOutput activeOutput = null;

    public TransmitAudioSink() {
        this(new AudioTrackOutputFactory(), new Sleeper() {
            @Override
            public void sleepMs(long ms) throws InterruptedException {
                Thread.sleep(ms);
            }
        });
    }

    TransmitAudioSink(PcmOutputFactory outputFactory, Sleeper sleeper) {
        this.outputFactory = outputFactory;
        this.sleeper = sleeper;
    }

    public void setRigWaveRoute(RigWaveRoute route) {
        this.rigWaveRoute = route;
    }

    /**
     * Abort an in-progress playback from any thread. The playback worker owns
     * the actual stop/release; this only signals it (and pause+flushes the
     * AudioTrack for immediate silence, which also unblocks a full-buffer
     * blocking write).
     */
    public void cancel() {
        cancelled = true;
        // Abort any in-progress direct-USB write so TX audio stops immediately.
        if (UsbAudioNative.isAvailable()) {
            UsbAudioNative.cancelWrite();
        }
        PcmOutput out = activeOutput;
        if (out != null) {
            out.pauseAndFlush();
        }
    }

    /**
     * Play the whole waveform through the current route. Blocking; call from a
     * worker thread. The entire buffer is played — never trimmed.
     *
     * @param buffer     mono float waveform, full scale (volume applied here)
     * @param sampleRate waveform sample rate in Hz
     * @param float32    true to write PCM float, false to convert to 16-bit
     * @param volume     live volume source, re-read per chunk
     */
    public PlayResult play(float[] buffer, int sampleRate, boolean float32, VolumeSource volume) {
        cancelled = false;
        if (buffer == null || buffer.length == 0) {
            return PlayResult.ERROR;
        }

        // 1) Rig wave route (network rig / truSDX audio over CAT).
        RigWaveRoute route = rigWaveRoute;
        if (route != null && route.isAvailable()) {
            GeneralVariables.fileLog("TransmitAudioSink: using rig wave route (network/CAT)");
            long startedAt = System.currentTimeMillis();
            long durationMs = (long) buffer.length * 1000L / sampleRate;
            boolean ok = route.sendWave(buffer, sampleRate);
            // Some rig transports hand the waveform off asynchronously; hold the
            // caller (and thus PTT) for the audio duration so the rig finishes
            // streaming before key-up. A blocking transport has already consumed
            // the time, so this loop exits immediately.
            while (ok && !cancelled
                    && System.currentTimeMillis() - startedAt < durationMs) {
                try {
                    sleeper.sleepMs(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (cancelled) return PlayResult.CANCELLED;
            return ok ? PlayResult.COMPLETED : PlayResult.ERROR;
        }

        // 2) Direct USB audio (libusb) when the user picked a USB device.
        if (useUsbDirectOutput()) {
            GeneralVariables.fileLog("TransmitAudioSink: using USB audio (direct) output");
            return playViaUsbAudio(buffer, sampleRate);
        }

        // 3) AudioTrack (Android default sink).
        GeneralVariables.fileLog("TransmitAudioSink: using AudioTrack output (Android default sink)");
        return playViaPcmOutput(buffer, sampleRate, float32, volume);
    }

    /**
     * Stream an open-ended chunk source (the Tune carrier) through the local
     * AudioTrack sink. Blocking; runs until the source is exhausted, a write
     * error occurs, or {@link #cancel()} fires. The source is responsible for
     * volume scaling and its own ramp-down.
     */
    public PlayResult playStream(ChunkSource source, int sampleRate, boolean float32) {
        cancelled = false;
        PcmOutput out;
        try {
            out = outputFactory.open(sampleRate, float32);
        } catch (Exception e) {
            Log.e(TAG, "playStream: failed to open output: " + e);
            return PlayResult.ERROR;
        }
        activeOutput = out;
        try {
            final int chunkSamples = Math.max(1, sampleRate / 20); // ~50ms
            float[] chunk = new float[chunkSamples];
            while (true) {
                if (cancelled) return PlayResult.CANCELLED;
                int written = source.nextChunk(chunk, chunkSamples);
                if (written <= 0) {
                    return PlayResult.COMPLETED;
                }
                int writeResult = float32
                        ? out.writeFloats(chunk, written)
                        : out.writeShorts(floatToInt16NoPad(chunk, written), written);
                if (writeResult < 0) {
                    Log.e(TAG, "playStream: write error " + writeResult);
                    return PlayResult.ERROR;
                }
            }
        } finally {
            activeOutput = null;
            out.release();
        }
    }

    /** Whether the user picked a direct-USB audio output device. */
    static boolean isUsbDirectOutput(int audioOutputDeviceId, int usbAudioOutputVendorId) {
        return audioOutputDeviceId == -1 && usbAudioOutputVendorId != 0;
    }

    private boolean useUsbDirectOutput() {
        return isUsbDirectOutput(GeneralVariables.audioOutputDeviceId,
                GeneralVariables.usbAudioOutputVendorId);
    }

    /**
     * The chunked MODE_STREAM playback loop, extracted intact from the FT8
     * engine. Package-visible core so tests can drive it with a fake output.
     */
    PlayResult playViaPcmOutput(float[] buffer, int sampleRate, boolean float32,
                                VolumeSource volume) {
        PcmOutput out;
        try {
            out = outputFactory.open(sampleRate, float32);
        } catch (Exception e) {
            Log.e(TAG, "play: failed to open output: " + e);
            return PlayResult.ERROR;
        }
        activeOutput = out;
        try {
            final int chunkSamples = Math.max(1, sampleRate / 20); // ~50ms
            int framesWritten = 0;
            boolean writeError = false;
            int offset = 0;
            while (offset < buffer.length) {
                if (cancelled) break;
                int chunkLen = Math.min(chunkSamples, buffer.length - offset);
                // applyVolume reads the volume source fresh for this chunk (live gain).
                float[] chunk = applyVolume(buffer, offset, chunkLen, volume.volume());

                int writeResult = float32
                        ? out.writeFloats(chunk, chunkLen)
                        : out.writeShorts(floatToInt16NoPad(chunk, chunkLen), chunkLen);
                if (writeResult < 0) {
                    Log.e(TAG, String.format("Playback error: %d", writeResult));
                    writeError = true;
                    break;
                }
                framesWritten += writeResult;
                offset += chunkLen;
            }

            // Append the 8-sample zero pad once at the end (QP-7C RP2040 audio
            // detection compatibility), only for the int16 path.
            if (!writeError && !cancelled && !float32) {
                short[] pad = new short[8];
                int padResult = out.writeShorts(pad, pad.length);
                if (padResult > 0) framesWritten += padResult;
            }

            // Blocking writes return once data is *buffered*, not played. Wait for
            // the tail to actually drain before releasing, so the end of the
            // message isn't truncated. A cancel skips the wait (the canceller has
            // already paused+flushed for immediate silence).
            if (!writeError && !cancelled) {
                while (!cancelled) {
                    if (out.playbackHeadPosition() >= framesWritten) break;
                    try {
                        sleeper.sleepMs(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (writeError) return PlayResult.ERROR;
            return cancelled ? PlayResult.CANCELLED : PlayResult.COMPLETED;
        } finally {
            activeOutput = null;
            out.release();
        }
    }

    /**
     * Direct USB audio output, extracted intact from the FT8 engine. TX volume
     * is applied live inside the native write loop (UsbAudioNative.setTxVolume),
     * so the buffer is handed over at full scale. The iso-packet-length math in
     * cpp/usb_audio_capture.cpp is load-bearing (see CLAUDE.md) and untouched.
     */
    private PlayResult playViaUsbAudio(float[] buffer, int sampleRate) {
        GeneralVariables.fileLog(String.format(
                "playViaUsbAudio: start, VID=%04X PID=%04X samples=%d rate=%d",
                GeneralVariables.usbAudioOutputVendorId,
                GeneralVariables.usbAudioOutputProductId,
                buffer.length, sampleRate));

        Context context = GeneralVariables.getMainContext();
        if (context == null) {
            GeneralVariables.fileLog("playViaUsbAudio: ABORT no main context");
            return PlayResult.ERROR;
        }

        UsbDevice device = UsbAudioDevice.findDeviceByVidPid(context,
                GeneralVariables.usbAudioOutputVendorId,
                GeneralVariables.usbAudioOutputProductId);
        if (device == null) {
            GeneralVariables.fileLog(String.format(
                    "playViaUsbAudio: ABORT USB audio output device not found by VID:PID %04X:%04X",
                    GeneralVariables.usbAudioOutputVendorId,
                    GeneralVariables.usbAudioOutputProductId));
            return PlayResult.ERROR;
        }

        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            GeneralVariables.fileLog("playViaUsbAudio: ABORT UsbManager is null");
            return PlayResult.ERROR;
        }
        if (!usbManager.hasPermission(device)) {
            GeneralVariables.fileLog(
                    "playViaUsbAudio: ABORT no USB permission for output device "
                            + "(re-pick (USB direct) in Settings to re-grant)");
            return PlayResult.ERROR;
        }

        UsbAudioDevice usbDev = new UsbAudioDevice();
        if (!usbDev.open(context, device)) {
            GeneralVariables.fileLog(
                    "playViaUsbAudio: ABORT UsbAudioDevice.open() failed "
                            + "(descriptor parse or claimInterface failed)");
            return PlayResult.ERROR;
        }

        try {
            if (!usbDev.hasOutput()) {
                GeneralVariables.fileLog("playViaUsbAudio: ABORT device has no output endpoint");
                return PlayResult.ERROR;
            }

            if (!usbDev.activateOutput(48000)) {
                GeneralVariables.fileLog(
                        "playViaUsbAudio: ABORT activateOutput(48000) failed "
                                + "(alt-setting select or rate setup failed)");
                return PlayResult.ERROR;
            }
            GeneralVariables.fileLog("playViaUsbAudio: device opened, output activated at 48000 Hz");

            GeneralVariables.fileLog(String.format(
                    "playViaUsbAudio: calling writeAudio playLength=%d rate=%d",
                    buffer.length, sampleRate));
            boolean success = usbDev.writeAudio(buffer, sampleRate);
            GeneralVariables.fileLog(buildWriteAudioResultLog(success));
            boolean wasCancelled = UsbAudioNative.writeCancelled;
            // A drop is invisible at the rig (it keys and shows normal behavior
            // but transmits dead air), so tell the operator — unless the
            // "failure" is just the user cancelling mid-message.
            if (shouldWarnTxDropped(success, wasCancelled)) {
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.tx_audio_dropped));
            }
            if (success) return PlayResult.COMPLETED;
            return wasCancelled ? PlayResult.CANCELLED : PlayResult.ERROR;
        } finally {
            usbDev.close();
        }
    }

    /**
     * Whether a failed USB-audio write warrants the on-screen "TX dropped"
     * warning. A cancelled write is the operator's own stop, not a fault.
     */
    static boolean shouldWarnTxDropped(boolean success, boolean cancelled) {
        return !success && !cancelled;
    }

    /** Debug-log line for a {@code writeAudio} result. */
    static String buildWriteAudioResultLog(boolean success) {
        if (success) {
            return "playViaUsbAudio: writeAudio returned OK";
        }
        return "playViaUsbAudio: writeAudio returned FAILED — TX DROPPED, "
                + "no audio sent (rig keyed but silent)";
    }

    /**
     * Scale a slice of the waveform by the TX volume, returning a new buffer of
     * exactly {@code playLength} samples. This is the single source of truth
     * for TX level on the AudioTrack path: {@code AudioTrack.setVolume()} is a
     * no-op when the track is routed to a USB Audio Class device, so the gain
     * must be baked into the samples. Pure function — unit-tested.
     *
     * @param source      full waveform
     * @param skipSamples offset of the slice start; clamped to 0
     * @param playLength  number of samples to emit
     * @param volume      gain 0.0-1.0; 0 yields digital silence
     */
    static float[] applyVolume(float[] source, int skipSamples, int playLength, float volume) {
        if (skipSamples < 0) skipSamples = 0;
        float[] out = new float[playLength];
        for (int i = 0; i < playLength; i++) {
            out[i] = source[skipSamples + i] * volume;
        }
        return out;
    }

    /**
     * Convert the first {@code length} float samples to 16-bit, with no
     * trailing zero padding (the 8-sample QP-7C pad is appended once at the
     * very end of the message, not after every chunk). Pure function.
     */
    static short[] floatToInt16NoPad(float[] buffer, int length) {
        short[] out = new short[length];
        for (int i = 0; i < length; i++) {
            float x = buffer[i];
            if (x > 1.0f) x = 1.0f;
            else if (x < -1.0f) x = -1.0f;
            out[i] = (short) (x * 32767.0);
        }
        return out;
    }

    /**
     * The real AudioTrack-backed output. MODE_STREAM with a deliberately small
     * (~200ms) buffer so live volume changes land within one chunk + buffer
     * depth — instant enough to pull drive down and protect the rig mid-over.
     */
    private static class AudioTrackOutputFactory implements PcmOutputFactory {
        @Override
        public PcmOutput open(int sampleRate, boolean float32) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            int encoding = float32
                    ? AudioFormat.ENCODING_PCM_FLOAT : AudioFormat.ENCODING_PCM_16BIT;
            AudioFormat format = new AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
            int bytesPerSample = float32 ? 4 : 2;
            int targetBufBytes = (sampleRate / 5) * bytesPerSample; // ~200ms mono
            int minBuf = AudioTrack.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, encoding);
            int bufBytes = Math.max(targetBufBytes, minBuf > 0 ? minBuf : targetBufBytes);
            final AudioTrack track = new AudioTrack(attributes, format, bufBytes,
                    AudioTrack.MODE_STREAM, 0);

            // Set the user-preferred output device (null resets to default).
            if (GeneralVariables.audioOutputDeviceId > 0) {
                track.setPreferredDevice(findAudioDeviceById(
                        GeneralVariables.audioOutputDeviceId, AudioManager.GET_DEVICES_OUTPUTS));
            }

            // Keep the track at unity: TX level is carried in the sample values.
            track.play();
            track.setVolume(1.0f);

            return new PcmOutput() {
                @Override
                public int writeFloats(float[] data, int length) {
                    return track.write(data, 0, length, AudioTrack.WRITE_BLOCKING);
                }

                @Override
                public int writeShorts(short[] data, int length) {
                    return track.write(data, 0, length, AudioTrack.WRITE_BLOCKING);
                }

                @Override
                public int playbackHeadPosition() {
                    try {
                        return track.getPlaybackHeadPosition();
                    } catch (IllegalStateException e) {
                        return Integer.MAX_VALUE; // released underneath us: stop waiting
                    }
                }

                @Override
                public void pauseAndFlush() {
                    // Immediate silence without releasing (the worker may be
                    // blocked in a WRITE_BLOCKING call; flush() also unblocks it).
                    try {
                        if (track.getState() != AudioTrack.STATE_UNINITIALIZED
                                && track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                            track.pause();
                            track.flush();
                        }
                    } catch (IllegalStateException ignored) {
                        // Worker already released the track.
                    }
                }

                @Override
                public void release() {
                    try {
                        track.stop();
                    } catch (IllegalStateException ignored) {
                    }
                    track.release();
                }
            };
        }
    }

    /** Find an AudioDeviceInfo by device ID. */
    private static AudioDeviceInfo findAudioDeviceById(int deviceId, int deviceType) {
        Context context = GeneralVariables.getMainContext();
        if (context == null) return null;
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return null;
        AudioDeviceInfo[] devices = audioManager.getDevices(deviceType);
        for (AudioDeviceInfo device : devices) {
            if (device.getId() == deviceId) {
                return device;
            }
        }
        return null;
    }
}
