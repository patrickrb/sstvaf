package radio.ks3ckc.sstvaf.ui.rx

import radio.ks3ckc.sstvaf.sstv.SstvRxState
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure decision/formatting logic for [RxScreen], extracted per project testing
 * policy (Composables stay thin wrappers; this file carries the unit tests).
 */

/** What the RX tab's content area shows for a given engine state. */
internal enum class RxRenderKind {
    /** Hunting (or RX toggled off): empty state + "Listening for SSTV…". */
    LISTENING,

    /** Leader/VIS heard: "Signal detected…" indicator. */
    SIGNAL_DETECTED,

    /** VIS locked: the forming image + status strip. */
    DECODING,

    /** Full image decoded (auto-saved): image + "Saved" chip. */
    COMPLETE,

    /** Signal lost mid-image: dimmed partial (if any rows) + note. */
    ABORTED,
}

/** State → render decision. */
internal fun rxRenderKind(state: SstvRxState): RxRenderKind = when (state) {
    is SstvRxState.Idle -> RxRenderKind.LISTENING
    is SstvRxState.Leader -> RxRenderKind.SIGNAL_DETECTED
    is SstvRxState.Decoding -> RxRenderKind.DECODING
    is SstvRxState.Complete -> RxRenderKind.COMPLETE
    is SstvRxState.Aborted -> RxRenderKind.ABORTED
}

/** Decode progress in whole percent, clamped 0..100; 0 for a degenerate total. */
internal fun rxProgressPercent(rowsReady: Int, totalRows: Int): Int {
    if (totalRows <= 0) return 0
    return (rowsReady * 100 / totalRows).coerceIn(0, 100)
}

/** Quality (engine's 0..1) as a 0..1 meter fill fraction, clamped. */
internal fun rxQualityFraction(quality: Float): Float = quality.coerceIn(0f, 1f)

/**
 * Slant in ppm as a short signed label, e.g. "+12", "-3", "0". Rounded to
 * whole ppm — finer than that is noise at SSTV line rates.
 */
internal fun formatSlantPpm(slantPpm: Float): String {
    val rounded = slantPpm.roundToInt()
    return if (rounded > 0) "+$rounded" else rounded.toString()
}

/** Dial frequency in Hz → "14.230 MHz"-style label (three decimals, kHz resolution). */
internal fun formatDialFrequency(freqHz: Long): String =
    String.format(Locale.US, "%.3f MHz", freqHz / 1_000_000.0)

/**
 * Calibration-header seconds baked into every mode's `txDurationSeconds` (the
 * 0.91 s leader / VIS preamble documented on [radio.ks3ckc.sstvaf.sstv.SstvMode]).
 * Header time has already elapsed by the time rows arrive, so the ETA below
 * subtracts it to work from the image-scan portion alone.
 */
private const val SSTV_HEADER_SECONDS = 0.91

/**
 * Estimated seconds of image scan still to receive. SSTV scans rows at a
 * constant rate, so the time left is the fraction of rows not yet decoded
 * scaled by the mode's image-scan time (its total TX duration minus the fixed
 * calibration header). Clamped to ≥0; returns 0 for a degenerate/empty total
 * and for an overrun (rowsReady ≥ totalRows).
 */
internal fun rxSecondsRemaining(rowsReady: Int, totalRows: Int, txDurationSeconds: Double): Int {
    if (totalRows <= 0) return 0
    val done = rowsReady.coerceIn(0, totalRows)
    val imageSeconds = (txDurationSeconds - SSTV_HEADER_SECONDS).coerceAtLeast(0.0)
    val remainingFraction = (totalRows - done).toDouble() / totalRows
    return (remainingFraction * imageSeconds).roundToInt().coerceAtLeast(0)
}

/** ETA label for the RX strip in "m:ss" (e.g. 110 → "1:50", 0 → "0:00"). */
internal fun formatRxEta(secondsRemaining: Int): String {
    val s = secondsRemaining.coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/**
 * Whether an Aborted state has a partial image worth showing (some rows
 * decoded under a known mode); otherwise the empty state is shown instead.
 */
internal fun showsPartialImage(state: SstvRxState.Aborted): Boolean =
    state.partialRows > 0 && state.mode != null
