package radio.ks3ckc.sstvaf.sstv

/**
 * How a transmission ended, and where the image sat inside it.
 *
 * Both are published by [SstvTransmitter] because both are things only the
 * transmitter knows, and a UI that guesses at them gets them wrong: watching
 * the transmitting flag alone cannot tell a completed image from a failed one,
 * and reading overall progress alone cannot tell image pixels from the VOX
 * leader or the CW tail.
 */

/** What happened to a transmission. */
enum class TxOutcome {
    /** The image, and any CW ID tail, played to the end. */
    COMPLETED,

    /** The operator stopped it. The picture is incomplete at the far end. */
    CANCELLED,

    /** Encoding, keying or playback failed. Nothing usable went out. */
    FAILED,
}

/**
 * A transmission's outcome, stamped with a monotonic sequence number.
 *
 * The sequence is what makes the value safe to retain. A consumer records the
 * sequence it last acted on, so it can be recreated (a tab switch, a rotation)
 * and still tell "a result I have already shown" from "a result that arrived
 * while I was not composed" — which is exactly the case the previous screen-local
 * edge detector could not see.
 */
data class TxResult(val outcome: TxOutcome, val sequence: Long)

/**
 * Where the image sits inside the whole on-air buffer, as fractions of total
 * duration.
 *
 * [start] is where image audio begins (after any VOX pre-tone leader) and [end]
 * is where it stops (before any CW station-ID tail).
 */
data class TxImageWindow(val start: Float, val end: Float) {

    companion object {
        /** No leader and no tail: the whole transmission is the image. */
        val WHOLE = TxImageWindow(0f, 1f)

        /**
         * The window for a buffer laid out as pre-tone, image, CW tail.
         *
         * Degenerate inputs fall back to [WHOLE] rather than producing a
         * zero-width or inverted window, which would make a scan-line
         * indicator jump or divide by zero.
         */
        fun of(preToneSamples: Int, imageSamples: Int, totalSamples: Int): TxImageWindow {
            if (totalSamples <= 0 || imageSamples <= 0) return WHOLE
            val lead = preToneSamples.coerceAtLeast(0)
            val start = (lead.toFloat() / totalSamples).coerceIn(0f, 1f)
            val end = ((lead + imageSamples).toFloat() / totalSamples).coerceIn(0f, 1f)
            return if (end <= start) WHOLE else TxImageWindow(start, end)
        }
    }
}

/**
 * Overall transmit progress mapped onto image scan progress.
 *
 * Returns 0 for the whole leader, sweeps 0 to 1 across the image, and holds at
 * 1 through the CW tail. Without this the scan line on the preview moved during
 * the VOX pre-tone, before a receiver had seen a single pixel, and only reached
 * the bottom of the frame while the station ID was being keyed.
 */
fun imageScanProgress(overallProgress: Float, window: TxImageWindow): Float {
    val overall = overallProgress.coerceIn(0f, 1f)
    val span = window.end - window.start
    if (span <= 0f) return overall
    return ((overall - window.start) / span).coerceIn(0f, 1f)
}
