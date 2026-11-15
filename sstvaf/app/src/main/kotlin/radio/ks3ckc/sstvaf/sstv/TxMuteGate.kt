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
 * and the room both take a moment to go quiet.
 *
 * The tail is counted in samples delivered, not in wall-clock time. That is
 * deterministic in tests, and it is the safe side when capture stalls: audio
 * the recorder was holding from the transmission is still muted whenever it
 * finally arrives. The price is that capture which stops during a
 * transmission and comes back much later loses its first [tailMs] to a mute
 * that is no longer needed.
 *
 * The capture thread is the only caller. [mutes] is synchronized all the
 * same, because that thread is replaced when the capture device is reopened.
 */
internal class TxMuteGate(sampleRate: Int, tailMs: Int = DEFAULT_TAIL_MS) {

    private val tailSamples = sampleRate.toLong() * tailMs / 1000L
    private var tailLeft = 0L

    /**
     * Account for one capture buffer of [samples] samples, delivered while the
     * app is ([txActive]) or is not transmitting. True when that buffer must
     * be replaced with silence. A buffer the tail only partly covers is muted
     * whole.
     */
    @Synchronized
    fun mutes(txActive: Boolean, samples: Int): Boolean {
        if (txActive) {
            tailLeft = tailSamples
            return true
        }
        if (tailLeft > 0) {
            tailLeft -= samples
            return true
        }
        return false
    }

    companion object {
        /** How much capture audio stays muted after the transmission ends. */
        const val DEFAULT_TAIL_MS = 1000
    }
}
