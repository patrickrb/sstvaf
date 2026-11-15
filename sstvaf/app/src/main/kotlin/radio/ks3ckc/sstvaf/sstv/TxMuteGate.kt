package radio.ks3ckc.sstvaf.sstv

/**
 * Decides, capture buffer by capture buffer, whether the receiver may hear
 * the audio or must be fed silence because the app itself is on the air.
 *
 * Nothing used to stop the receiver listening during a transmission. With the
 * phone's speaker driving the radio the microphone hears the picture going
 * out, and with a USB rig the monitor audio comes back on the codec, so the
 * decoder drew the operator's own picture and filed it with the received
 * ones (issue #153).
 *
 * The mute outlasts the transmission by [tailMs] of capture audio. A buffer
 * delivered just after the transmitter reports idle was recorded while it was
 * still on the air (capture latency), and the rig's switch back to receive
 * and the room both take a moment to go quiet. The tail is counted in
 * samples, not wall-clock time: it is deterministic in tests and covers that
 * much audio even when the capture thread delivers a backlog in a burst.
 *
 * The capture thread is the only caller. [onBuffer] is synchronized all the
 * same, because that thread is replaced when the capture device is reopened.
 */
internal class TxMuteGate(sampleRate: Int, tailMs: Int = DEFAULT_TAIL_MS) {

    /** A change of state worth one log line. */
    enum class Edge { NONE, MUTED, UNMUTED }

    private val tailSamples = sampleRate.toLong() * tailMs / 1000L
    private var tailLeft = 0L

    /** Whether the buffer last passed to [onBuffer] must be replaced with silence. */
    @Volatile
    var muted = false
        private set

    /**
     * Account for one capture buffer of [samples] samples, delivered while the
     * app is ([txActive]) or is not transmitting. [muted] then says what to do
     * with that buffer. A buffer the tail only partly covers is muted whole.
     */
    @Synchronized
    fun onBuffer(txActive: Boolean, samples: Int): Edge {
        val was = muted
        when {
            txActive -> {
                tailLeft = tailSamples
                muted = true
            }

            tailLeft > 0 -> {
                tailLeft -= samples
                muted = true
            }

            else -> muted = false
        }
        return when {
            muted == was -> Edge.NONE
            muted -> Edge.MUTED
            else -> Edge.UNMUTED
        }
    }

    companion object {
        /** How much capture audio stays muted after the transmission ends. */
        const val DEFAULT_TAIL_MS = 1000
    }
}
