package radio.ks3ckc.sstvaf.sstv

/**
 * Live SSTV receive state published by [SstvSignalListener.rxState].
 * PR 6 (the RX tab) renders these; nothing UI-side exists yet.
 */
sealed class SstvRxState {

    /** Hunting for a 1900 Hz leader. */
    object Idle : SstvRxState()

    /** Leader heard / VIS being sampled — a transmission may be starting. */
    object Leader : SstvRxState()

    /** VIS locked; image rows are arriving. */
    data class Decoding(
        val mode: SstvMode,
        val rowsReady: Int,
        val totalRows: Int,
        val quality: Float,
        val slantPpm: Float,
    ) : SstvRxState()

    /**
     * A full image decoded. [frameAvailable] is true when the finished frame
     * was snapshotted into [LastDecodedImage] (always, unless the row read
     * failed) — the decoder itself has already been reset back to hunting.
     */
    data class Complete(
        val mode: SstvMode,
        val quality: Float,
        val frameAvailable: Boolean,
    ) : SstvRxState()

    /** Signal lost mid-image; a partial frame (if any rows) was snapshotted. */
    data class Aborted(
        val partialRows: Int,
        val mode: SstvMode?,
    ) : SstvRxState()
}

/**
 * Holder for the most recently finished (or aborted-partial) decode, so the
 * frame survives between the engine (this PR) and the gallery persistence
 * that lands in PR 6. Written only by the decode thread, read from anywhere.
 */
object LastDecodedImage {

    class Frame(
        /** 0xAARRGGBB row-major, always width*height long (missing rows black). */
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val mode: SstvMode,
        /** Rows actually decoded ([height] when [complete]). */
        val rowsDecoded: Int,
        val quality: Float,
        val slantPpm: Float,
        /** Wall-clock UTC millis when the decode finished. */
        val utcMillis: Long,
        /** Dial frequency (GeneralVariables.band) at decode time, Hz. */
        val dialFrequencyHz: Long,
        /** True for a DONE decode, false for an ABORTED partial. */
        val complete: Boolean,
    )

    @Volatile
    var frame: Frame? = null
}
