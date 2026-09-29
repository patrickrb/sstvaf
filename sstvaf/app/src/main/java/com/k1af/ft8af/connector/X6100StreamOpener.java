package com.k1af.ft8af.connector;

/**
 * Decision logic for the X6100 stream-open handshake.
 *
 * <p>Historically {@code X6100Connector} ran a loop that resent
 * {@code stream on} every 300&nbsp;ms until the radio confirmed the stream
 * port, and fired the follow-up commands ({@code audio get all},
 * {@code sub all}) inside that same loop. The radio frequently drops commands
 * that arrive while the stream handshake is still in progress, so the
 * follow-ups sent in the final round before the port confirmation could be
 * lost — and, because the loop exited as soon as the port was confirmed, they
 * were never resent. Symptom: connected radio, working audio, but no meter
 * data / wrong playback parameters until the app reconnected.
 *
 * <p>This class keeps the original cadence (each round sends the open command
 * plus the follow-ups, the caller sleeps between rounds) and adds a bounded
 * retry <em>after</em> the port is confirmed: any follow-up whose response has
 * not been seen yet is resent, up to {@link #MAX_POST_OPEN_ATTEMPTS} more
 * rounds. Both follow-ups are safe to repeat — {@code audio get all} is a
 * query and {@code sub all} re-subscribes idempotently.
 *
 * <p>It is a plain state machine — no threads, no radio — so it is unit
 * testable; {@code X6100Connector} supplies the transport, the response
 * callbacks and the 300&nbsp;ms tick.
 */
public class X6100StreamOpener {

    /** The three handshake commands, supplied by the connector. */
    public interface Transport {
        void sendOpenStream();

        void sendGetAudioInfo();

        void sendSubAllMeter();
    }

    /** Give up waiting for the stream-port confirmation after this long. */
    public static final long OPEN_TIMEOUT_MS = 30_000;
    /**
     * How many extra rounds the un-acknowledged follow-ups are resent once the
     * stream port is confirmed. 5 rounds at the caller's 300 ms tick is 1.5 s —
     * enough for several UDP round trips without hammering the radio forever
     * if a firmware simply never answers.
     */
    public static final int MAX_POST_OPEN_ATTEMPTS = 5;

    private final Transport transport;
    private final long startedAt;

    private boolean streamPortOpen = false;
    private boolean audioInfoAcked = false;
    private boolean meterSubAcked = false;
    private int postOpenAttempts = 0;
    private boolean timedOut = false;
    private boolean gaveUp = false;
    private boolean finished = false;
    //Volatile so a cancel from another thread (reconnect/disconnect) is seen by
    //the next tick() even without taking the monitor.
    private volatile boolean cancelled = false;

    public X6100StreamOpener(Transport transport) {
        this.transport = transport;
        this.startedAt = now();
    }

    /** Overridable clock seam for tests. */
    protected long now() {
        return System.currentTimeMillis();
    }

    /** The radio confirmed the stream port ({@code STREAM} response containing {@code PORT=}). */
    public synchronized void onStreamPortOpen() {
        streamPortOpen = true;
    }

    /** Any response to {@code audio get all} arrived — the command was not dropped. */
    public synchronized void onAudioInfoResponse() {
        audioInfoAcked = true;
    }

    /** Any response to {@code sub all} arrived — the command was not dropped. */
    public synchronized void onMeterSubResponse() {
        meterSubAcked = true;
    }

    /**
     * Run one handshake round.
     *
     * @return true while another round (after the caller's delay) is still
     * needed; false once the handshake completed, timed out or gave up.
     */
    public synchronized boolean tick() {
        if (cancelled || finished) {
            return false;
        }
        if (!streamPortOpen) {
            if (now() - startedAt >= OPEN_TIMEOUT_MS) {
                timedOut = true;
                finished = true;
                return false;
            }
            // Original behavior: keep resending the whole batch until the
            // radio confirms the port.
            transport.sendOpenStream();
            transport.sendGetAudioInfo();
            transport.sendSubAllMeter();
            return true;
        }
        if (audioInfoAcked && meterSubAcked) {
            finished = true;
            return false;
        }
        if (postOpenAttempts >= MAX_POST_OPEN_ATTEMPTS) {
            gaveUp = true;
            finished = true;
            return false;
        }
        postOpenAttempts++;
        // The fix: the port is up, so resend exactly the follow-ups whose
        // responses we have not seen — these are the commands that used to be
        // silently dropped during the handshake.
        if (!audioInfoAcked) {
            transport.sendGetAudioInfo();
        }
        if (!meterSubAcked) {
            transport.sendSubAllMeter();
        }
        return true;
    }

    /** True when the port was confirmed and both follow-ups were answered. */
    public synchronized boolean isComplete() {
        return streamPortOpen && audioInfoAcked && meterSubAcked;
    }

    public synchronized boolean isStreamPortOpen() {
        return streamPortOpen;
    }

    public synchronized boolean hasTimedOut() {
        return timedOut;
    }

    /** True when the port opened but a follow-up was never answered within the retry budget. */
    public synchronized boolean hasGivenUp() {
        return gaveUp;
    }

    /**
     * Abandon this handshake: every subsequent {@link #tick()} is a no-op that
     * returns false, so the connector's tick thread exits without sending
     * anything more. Called when the session this opener belongs to is gone —
     * a reconnect built a new opener, or the connector disconnected. Without
     * it a stale opener kept resending its 300&nbsp;ms open-stream batch for
     * up to {@link #OPEN_TIMEOUT_MS} on whatever socket the shared
     * {@code RadioTcpClient} was connected to by then — i.e. the NEW session.
     */
    public void cancel() {
        cancelled = true;
    }

    /** True once {@link #cancel()} has been called. */
    public boolean isCancelled() {
        return cancelled;
    }
}
