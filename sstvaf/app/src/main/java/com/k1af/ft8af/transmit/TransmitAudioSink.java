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
import com.k1af.ft8af.bluetooth.DefaultOutputRouting;
import com.k1af.ft8af.ui.ToastMessage;
import com.k1af.ft8af.wave.AudioChannelCapability;
import com.k1af.ft8af.wave.AudioChannelSelect;
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
 *       TX volume is applied live inside the native write loop, and the TX
 *       channel selection is honoured by the device's interleave.</li>
 *   <li><b>AudioTrack</b> (Android default sink) — chunked MODE_STREAM playback
 *       with the TX volume re-read per ~50ms chunk, so a slider move lands
 *       mid-transmission. Holds transient-exclusive audio focus for the
 *       duration ({@link TxAudioFocus}) so other apps' sounds don't mix into
 *       the rig feed, opens stereo with one side silenced when the operator
 *       picked Left/Right on an explicit sink ({@link TxChannelLayout}), and
 *       steers the "Default" sink to the rig's A2DP endpoint while our own
 *       Bluetooth SCO link is holding the media route
 *       ({@link DefaultOutputRouting}).</li>
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

    /**
     * Consecutive AudioTrack reopens with no net playback progress before a
     * transmission is abandoned. A transient sink death (route change, USB
     * hiccup) recovers on the first reopen; a genuinely dead sink burns
     * through these in well under a second.
     */
    static final int MAX_WRITE_REOPEN_ATTEMPTS = 3;

    /**
     * Pause before reopening the USB output device after a mid-stream write
     * failure, so a kernel-driver alt-setting flip (another sound routed to
     * the still-registered card) finishes before we re-claim the endpoint.
     */
    private static final long USB_REOPEN_DELAY_MS = 250;

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
     * unit-testable without an Android AudioTrack. The sink hands it MONO
     * sample buffers; an implementation that opened stereo expands them
     * itself and reports frames, not samples, from the write calls.
     */
    public interface PcmOutput {
        /** Blocking write of {@code length} mono samples; returns frames written or a negative error. */
        int writeFloats(float[] data, int length);

        /** Blocking write of {@code length} mono samples; returns frames written or a negative error. */
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

    /**
     * Whether the app's own Bluetooth SCO link was up when the current
     * transmission was keyed, and which device carried it — the snapshot
     * {@code MainViewModel} takes at key-down ({@code TxScoLatch}). Read by the
     * Default-sink routing override: TX audio must only be steered onto a
     * Bluetooth A2DP endpoint when our SCO session is the thing displacing the
     * media route (see {@code AudioOutputRoutingPolicy}).
     */
    public interface TxScoState {
        boolean heldForTx();

        /** Bluetooth address of the SCO device, or null when unknown. */
        String scoAddress();
    }

    /** Millisecond sleeper, injected for tests. */
    public interface Sleeper {
        void sleepMs(long ms) throws InterruptedException;
    }

    private final PcmOutputFactory outputFactory;
    private final Sleeper sleeper;
    private RigWaveRoute rigWaveRoute;
    // Created lazily on the real AudioTrack path only: its constructor pins a
    // Handler to the main looper, which a plain JVM unit test doesn't have.
    private TxAudioFocus txAudioFocus;

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
     * Wire the SCO-at-keying snapshot the Default-sink routing override reads.
     * Only the real AudioTrack factory consumes it; a test factory ignores it.
     */
    public void setTxScoState(TxScoState state) {
        if (outputFactory instanceof AudioTrackOutputFactory) {
            ((AudioTrackOutputFactory) outputFactory).scoState = state;
        }
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

        // 3) AudioTrack (Android default sink). This branch shares Android's
        // mixer with every other app, so claim exclusive focus for the
        // transmission. Denial is log-only: TX must still go out.
        GeneralVariables.fileLog("TransmitAudioSink: using AudioTrack output (Android default sink)");
        TxAudioFocus focus = acquireAudioFocus("play");
        try {
            return playViaPcmOutput(buffer, sampleRate, float32, volume);
        } finally {
            if (focus != null) focus.release();
        }
    }

    /**
     * Stream an open-ended chunk source (the Tune carrier) through the local
     * AudioTrack sink. Blocking; runs until the source is exhausted, a write
     * error occurs, or {@link #cancel()} fires. The source is responsible for
     * volume scaling and its own ramp-down.
     */
    public PlayResult playStream(ChunkSource source, int sampleRate, boolean float32) {
        cancelled = false;
        TxAudioFocus focus = acquireAudioFocus("playStream");
        PcmOutput out;
        try {
            out = outputFactory.open(sampleRate, float32);
        } catch (Exception e) {
            Log.e(TAG, "playStream: failed to open output: " + e);
            if (focus != null) focus.release();
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
            if (focus != null) focus.release();
        }
    }

    /**
     * Take transient-exclusive audio focus for an AudioTrack transmission, or
     * return null when there is no app context (unit tests, or before the
     * activity is up) — TX proceeds regardless.
     */
    private TxAudioFocus acquireAudioFocus(String what) {
        Context ctx = GeneralVariables.getMainContext();
        if (ctx == null) return null;
        if (txAudioFocus == null) {
            txAudioFocus = new TxAudioFocus();
        }
        boolean granted = txAudioFocus.acquire(ctx);
        GeneralVariables.fileLog("TransmitAudioSink: " + what + " audio focus "
                + (granted ? "granted (exclusive)" : "NOT granted — other-app audio may mix into TX"));
        return txAudioFocus;
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
     * The chunked MODE_STREAM playback loop, extracted from the FT8 engine.
     * Package-visible core so tests can drive it with a fake output.
     *
     * <p>Unlike the FT8 version, a write error does not drop the transmission:
     * an SSTV image is a one-shot multi-minute buffer with no next cycle, so a
     * transient sink death (ERROR_DEAD_OBJECT from a route change, a USB sink
     * hiccup) reopens the output and resumes from where playback stopped —
     * rewound by whatever the dead track had buffered but not yet played. Only
     * {@link #MAX_WRITE_REOPEN_ATTEMPTS} consecutive reopens with no progress
     * give up, so a genuinely dead sink still fails fast.
     */
    PlayResult playViaPcmOutput(float[] buffer, int sampleRate, boolean float32,
                                VolumeSource volume) {
        final int chunkSamples = Math.max(1, sampleRate / 20); // ~50ms
        int offset = 0;
        // Consecutive reopen attempts that wrote nothing before failing again.
        int stalledReopens = 0;
        while (true) {
            PcmOutput out;
            try {
                out = outputFactory.open(sampleRate, float32);
            } catch (Exception e) {
                Log.e(TAG, "play: failed to open output: " + e);
                return PlayResult.ERROR;
            }
            activeOutput = out;
            try {
                final int attemptStartOffset = offset;
                int framesWritten = 0; // frames written to THIS output instance
                boolean writeError = false;
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

                if (writeError) {
                    if (cancelled) return PlayResult.CANCELLED;
                    // Samples the dead track buffered but never played would be
                    // skipped on resume; rewind so the receiver loses nothing it
                    // was owed. A head the wrapper can no longer read (released
                    // underneath us reports MAX_VALUE) just resumes in place.
                    int head = out.playbackHeadPosition();
                    int unplayed = (head >= 0 && head < framesWritten)
                            ? framesWritten - head : 0;
                    offset = Math.max(0, offset - unplayed);
                    // Progress is judged net of the rewind: an output that only
                    // ever buffers a chunk and dies would otherwise count every
                    // attempt as progress while the offset never advances.
                    stalledReopens = offset > attemptStartOffset ? 1 : stalledReopens + 1;
                    if (stalledReopens > MAX_WRITE_REOPEN_ATTEMPTS) {
                        GeneralVariables.fileLog(String.format(
                                "TransmitAudioSink: giving up after %d sink reopens "
                                        + "with no progress (offset=%d/%d)",
                                MAX_WRITE_REOPEN_ATTEMPTS, offset, buffer.length));
                        return PlayResult.ERROR;
                    }
                    GeneralVariables.fileLog(String.format(
                            "TransmitAudioSink: sink write failed, reopening and "
                                    + "resuming at sample %d/%d (rewound %d unplayed)",
                            offset, buffer.length, unplayed));
                    continue; // finally releases this output; loop reopens
                }

                // Append the 8-sample zero pad once at the end (QP-7C RP2040 audio
                // detection compatibility), only for the int16 path.
                if (!cancelled && !float32) {
                    short[] pad = new short[8];
                    int padResult = out.writeShorts(pad, pad.length);
                    if (padResult > 0) framesWritten += padResult;
                }

                // Blocking writes return once data is *buffered*, not played. Wait for
                // the tail to actually drain before releasing, so the end of the
                // message isn't truncated. A cancel skips the wait (the canceller has
                // already paused+flushed for immediate silence).
                if (!cancelled) {
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

                return cancelled ? PlayResult.CANCELLED : PlayResult.COMPLETED;
            } finally {
                activeOutput = null;
                out.release();
            }
        }
    }

    /**
     * Direct USB audio output. TX volume is applied live inside the native
     * write loop (UsbAudioNative.setTxVolume), so the buffer is handed over at
     * full scale, and the TX channel selection (GeneralVariables.txAudioChannel)
     * is applied by the device's interleave. The iso-packet-length math in
     * cpp/usb_audio_capture.cpp is load-bearing (see CLAUDE.md) and untouched.
     *
     * <p>Unlike the FT8 engine this was extracted from, a mid-stream write
     * failure does not drop the transmission: the device is closed, reopened
     * (a fresh open/alt-setting restores an endpoint the kernel driver tore
     * down), and the write resumes from the sample the device had clocked out
     * when it died — see {@link UsbTxResumePolicy} for why and for the give-up
     * rule.
     */
    private PlayResult playViaUsbAudio(float[] buffer, int sampleRate) {
        GeneralVariables.fileLog(String.format(
                "playViaUsbAudio: start, VID=%04X PID=%04X samples=%d rate=%d",
                GeneralVariables.usbAudioOutputVendorId,
                GeneralVariables.usbAudioOutputProductId,
                buffer.length, sampleRate));

        int offset = 0;
        int stalledAttempts = 0;
        while (true) {
            if (cancelled) return PlayResult.CANCELLED;

            UsbAudioDevice usbDev = openUsbOutputDevice();
            if (usbDev == null) {
                // Device setup failed outright (gone from the bus, permission
                // revoked, descriptor/alt-setting failure). Nothing to resume.
                if (!cancelled) {
                    ToastMessage.show(
                            GeneralVariables.getStringFromResource(R.string.tx_audio_dropped));
                }
                return cancelled ? PlayResult.CANCELLED : PlayResult.ERROR;
            }

            boolean success;
            long attemptElapsedMs;
            try {
                float[] slice = offset == 0 ? buffer
                        : java.util.Arrays.copyOfRange(buffer, offset, buffer.length);
                GeneralVariables.fileLog(String.format(
                        "playViaUsbAudio: calling writeAudio playLength=%d rate=%d offset=%d",
                        slice.length, sampleRate, offset));
                long startedAt = android.os.SystemClock.elapsedRealtime();
                success = usbDev.writeAudio(slice, sampleRate);
                long wallMs = android.os.SystemClock.elapsedRealtime() - startedAt;
                // Prefer the device's own streaming time when available: the
                // wall clock also counts resample/interleave setup, which
                // would over-estimate the consumed audio and skip real samples
                // on resume.
                long streamedMs = usbDev.getLastWriteStreamedMs();
                attemptElapsedMs = streamedMs > 0 ? Math.min(streamedMs, wallMs) : wallMs;
            } finally {
                usbDev.close();
            }
            if (success) {
                GeneralVariables.fileLog(buildWriteAudioResultLog(true));
                return PlayResult.COMPLETED;
            }
            boolean wasCancelled = UsbAudioNative.writeCancelled;
            if (wasCancelled || cancelled) return PlayResult.CANCELLED;
            // Not "TX DROPPED" yet — the resume/give-up decision below says
            // which this failure turns out to be.
            GeneralVariables.fileLog("playViaUsbAudio: writeAudio attempt failed after "
                    + attemptElapsedMs + "ms");

            UsbTxResumePolicy.Decision decision = UsbTxResumePolicy.onWriteFailure(
                    offset, buffer.length, attemptElapsedMs, sampleRate, stalledAttempts);
            if (decision.treatAsComplete) {
                GeneralVariables.fileLog(
                        "playViaUsbAudio: write failed with <100ms of audio left — "
                                + "transmission counts as complete");
                return PlayResult.COMPLETED;
            }
            if (!decision.retry) {
                GeneralVariables.fileLog(String.format(
                        "playViaUsbAudio: giving up after %d stalled attempts "
                                + "(offset=%d/%d) — TX DROPPED",
                        decision.stalledAttempts, decision.nextOffsetSamples, buffer.length));
                // A drop is invisible at the rig (it keys and shows normal
                // behavior but transmits dead air), so tell the operator.
                ToastMessage.show(
                        GeneralVariables.getStringFromResource(R.string.tx_audio_dropped));
                return PlayResult.ERROR;
            }
            offset = decision.nextOffsetSamples;
            stalledAttempts = decision.stalledAttempts;
            GeneralVariables.fileLog(String.format(
                    "playViaUsbAudio: resuming at sample %d/%d (%.0f%%) after "
                            + "%dms attempt, stalledAttempts=%d",
                    offset, buffer.length, 100f * offset / buffer.length,
                    attemptElapsedMs, stalledAttempts));
            try {
                sleeper.sleepMs(USB_REOPEN_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return PlayResult.ERROR;
            }
        }
    }

    /**
     * Find, open, and activate the selected USB audio output device, or null
     * with the reason logged. One attempt of the resume loop — each retry
     * reopens from scratch because a torn-down endpoint needs the full
     * open/alt-setting sequence to come back.
     */
    private UsbAudioDevice openUsbOutputDevice() {
        Context context = GeneralVariables.getMainContext();
        if (context == null) {
            GeneralVariables.fileLog("playViaUsbAudio: ABORT no main context");
            return null;
        }

        UsbDevice device = UsbAudioDevice.findDeviceByVidPid(context,
                GeneralVariables.usbAudioOutputVendorId,
                GeneralVariables.usbAudioOutputProductId);
        if (device == null) {
            GeneralVariables.fileLog(String.format(
                    "playViaUsbAudio: ABORT USB audio output device not found by VID:PID %04X:%04X",
                    GeneralVariables.usbAudioOutputVendorId,
                    GeneralVariables.usbAudioOutputProductId));
            return null;
        }

        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            GeneralVariables.fileLog("playViaUsbAudio: ABORT UsbManager is null");
            return null;
        }
        if (!usbManager.hasPermission(device)) {
            GeneralVariables.fileLog(
                    "playViaUsbAudio: ABORT no USB permission for output device "
                            + "(re-pick (USB direct) in Settings to re-grant)");
            return null;
        }

        UsbAudioDevice usbDev = new UsbAudioDevice();
        if (!usbDev.open(context, device)) {
            GeneralVariables.fileLog(
                    "playViaUsbAudio: ABORT UsbAudioDevice.open() failed "
                            + "(descriptor parse or claimInterface failed)");
            return null;
        }

        if (!usbDev.hasOutput()) {
            GeneralVariables.fileLog("playViaUsbAudio: ABORT device has no output endpoint");
            usbDev.close();
            return null;
        }
        if (!usbDev.activateOutput(48000)) {
            GeneralVariables.fileLog(
                    "playViaUsbAudio: ABORT activateOutput(48000) failed "
                            + "(alt-setting select or rate setup failed)");
            usbDev.close();
            return null;
        }
        GeneralVariables.fileLog("playViaUsbAudio: device opened, output activated at 48000 Hz");
        return usbDev;
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
     * Expand {@code count} mono 16-bit samples into interleaved stereo with the
     * excluded side silenced, per the operator's TX channel selection. The
     * float path uses {@link TxChannelLayout#layOut}; this is its int16 twin.
     * Pure function — unit-tested.
     */
    static short[] expandShortsToStereo(short[] mono, int count, int selection, short[] scratch) {
        int needed = count * 2;
        short[] out = (scratch != null && scratch.length >= needed) ? scratch : new short[needed];
        boolean left = AudioChannelSelect.writesChannel(selection, AudioChannelSelect.CHANNEL_LEFT);
        boolean right = AudioChannelSelect.writesChannel(selection, AudioChannelSelect.CHANNEL_RIGHT);
        for (int i = 0; i < count; i++) {
            out[2 * i] = left ? mono[i] : 0;
            out[2 * i + 1] = right ? mono[i] : 0;
        }
        return out;
    }

    /**
     * The real AudioTrack-backed output. MODE_STREAM with a deliberately small
     * (~200ms) buffer so live volume changes land within one chunk + buffer
     * depth — instant enough to pull drive down and protect the rig mid-over.
     */
    private static class AudioTrackOutputFactory implements PcmOutputFactory {
        /** SCO-at-keying snapshot for the Default-sink routing override (may be null). */
        volatile TxScoState scoState;

        @Override
        public PcmOutput open(int sampleRate, boolean float32) {
            // Resolve the preferred sink up front: the TX channel selection needs
            // its channel count before the track is built, and the same
            // AudioDeviceInfo is handed to setPreferredDevice below.
            final AudioDeviceInfo preferredOutputDevice = GeneralVariables.audioOutputDeviceId > 0
                    ? findAudioDeviceById(GeneralVariables.audioOutputDeviceId,
                            AudioManager.GET_DEVICES_OUTPUTS)
                    : null;
            // Which side of a stereo sink carries the waveform. "Both" — the
            // default and, on a mono or unknown device, the only possibility —
            // keeps the historical MONO open: the framework duplicates it to
            // every channel, so that path is byte-for-byte what it always was.
            final TxChannelLayout layout = TxChannelLayout.resolve(
                    GeneralVariables.txAudioChannel,
                    GeneralVariables.audioOutputDeviceId > 0,
                    outputMaxChannels(preferredOutputDevice));
            if (layout.isStereo()) {
                GeneralVariables.fileLog(
                        "TransmitAudioSink: stereo TX open, channel select=" + layout.selection);
            }

            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            int encoding = float32
                    ? AudioFormat.ENCODING_PCM_FLOAT : AudioFormat.ENCODING_PCM_16BIT;
            AudioFormat format = new AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(layout.channelMask).build();
            int bytesPerSample = float32 ? 4 : 2;
            // ~200ms at whatever channel count the layout opened with.
            int targetBufBytes = layout.bufferBytes(sampleRate, bytesPerSample);
            int minBuf = AudioTrack.getMinBufferSize(sampleRate, layout.channelMask, encoding);
            int bufBytes = Math.max(targetBufBytes, minBuf > 0 ? minBuf : targetBufBytes);
            final AudioTrack track = new AudioTrack(attributes, format, bufBytes,
                    AudioTrack.MODE_STREAM, 0);

            // Set the user-preferred output device (null resets to default); on
            // the Default sink, steer around a live SCO session of ours instead.
            if (GeneralVariables.audioOutputDeviceId > 0) {
                track.setPreferredDevice(preferredOutputDevice);
            } else {
                applyDefaultOutputRoutingOverride(track, scoState);
            }

            // Keep the track at unity: TX level is carried in the sample values.
            track.play();
            track.setVolume(1.0f);

            return new PcmOutput() {
                // Stereo scratch, allocated once: the interleave runs every
                // ~50ms and a fresh array per chunk is churn for nothing.
                private float[] floatScratch;
                private short[] shortScratch;

                @Override
                public int writeFloats(float[] data, int length) {
                    int samples = layout.samplesForFrames(length);
                    if (layout.isStereo()
                            && (floatScratch == null || floatScratch.length < samples)) {
                        floatScratch = new float[samples];
                    }
                    float[] toWrite = layout.layOut(data, length, floatScratch);
                    int r = track.write(toWrite, 0, samples, AudioTrack.WRITE_BLOCKING);
                    // write() counts samples, getPlaybackHeadPosition() counts
                    // frames — the drain wait compares the two, so convert here.
                    return r < 0 ? r : layout.framesFromSamples(r);
                }

                @Override
                public int writeShorts(short[] data, int length) {
                    short[] toWrite = data;
                    int samples = layout.samplesForFrames(length);
                    if (layout.isStereo()) {
                        shortScratch = expandShortsToStereo(data, length, layout.selection,
                                shortScratch);
                        toWrite = shortScratch;
                    }
                    int r = track.write(toWrite, 0, samples, AudioTrack.WRITE_BLOCKING);
                    return r < 0 ? r : layout.framesFromSamples(r);
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

    /**
     * Channel count the chosen sink reports, or {@link AudioChannelCapability#UNKNOWN}
     * when there is no explicit device.
     */
    static int outputMaxChannels(AudioDeviceInfo device) {
        if (device == null) return AudioChannelCapability.UNKNOWN;
        return AudioChannelCapability.maxChannelCount(device.getChannelCounts());
    }

    /**
     * When the user picked "Default" output and <em>this app</em> is holding a
     * Bluetooth SCO link, pin the AudioTrack to the A2DP endpoint of the same
     * Bluetooth device. On Android 8.1 (FT8AF issue #759 follow-up) the OS
     * routes the USAGE_MEDIA stream through SCO while the hands-free link is
     * active, leaving TX inaudible on a rig that only listens for the A2DP music
     * channel. Leaves routing to the OS whenever the conditions aren't met; see
     * {@code AudioOutputRoutingPolicy} for why the enumerated device types alone
     * are not enough to decide.
     */
    private static void applyDefaultOutputRoutingOverride(final AudioTrack track,
                                                          TxScoState scoState) {
        Context context = GeneralVariables.getMainContext();
        if (context == null) return;
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        AudioDeviceInfo[] outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        boolean scoHeld = scoState != null && scoState.heldForTx();
        String scoAddress = scoState == null ? null : scoState.scoAddress();
        // The enumeration-to-policy-to-track wiring (and the address gating for
        // API < 28 / a denied BLUETOOTH_CONNECT) lives in DefaultOutputRouting so
        // it is covered by DefaultOutputRoutingTest; only the real track and the
        // debug log are supplied from here.
        DefaultOutputRouting.apply(outputs, scoHeld, scoAddress,
                new DefaultOutputRouting.Sink() {
                    @Override
                    public boolean setPreferredDevice(AudioDeviceInfo device) {
                        return track.setPreferredDevice(device);
                    }

                    @Override
                    public void log(String line) {
                        GeneralVariables.fileLog(line);
                    }
                });
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
