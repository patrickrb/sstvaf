package radio.ks3ckc.sstvaf.ui.rx

import radio.ks3ckc.sstvaf.sstv.SstvMode
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
 * Whether an Aborted state has a partial image worth showing (some rows
 * decoded under a known mode); otherwise the empty state is shown instead.
 */
internal fun showsPartialImage(state: SstvRxState.Aborted): Boolean =
    state.partialRows > 0 && state.mode != null

// ---------------------------------------------------------------------------
// Estimated time remaining (ETA) for the forming image
// ---------------------------------------------------------------------------

/**
 * The fixed calibration-header portion of every [SstvMode.txDurationSeconds]
 * budget (leader + 1200 Hz break + VIS). Documented on [SstvMode]; subtracting
 * it leaves the image-scan time, which is what the RX progress ETA counts down.
 */
private const val SSTV_CALIBRATION_HEADER_SECONDS = 0.91

/**
 * Estimated seconds of image scan still to arrive, for the ETA label under the
 * forming image. An image scans top-to-bottom at a constant line rate, so the
 * remaining time is the per-row scan time times the rows not yet decoded, where
 * the total scan time is the mode's transmission budget minus the fixed
 * calibration header. [rowsReady] is clamped into 0..[totalRows] so an engine
 * overrun (rowsReady > totalRows) or a pre-lock negative can't produce a bogus
 * (negative or oversized) estimate; a degenerate row count yields 0.
 */
internal fun rxRemainingSeconds(mode: SstvMode, rowsReady: Int, totalRows: Int): Int {
    if (totalRows <= 0) return 0
    val scanSeconds = (mode.txDurationSeconds - SSTV_CALIBRATION_HEADER_SECONDS).coerceAtLeast(0.0)
    val remainingRows = (totalRows - rowsReady.coerceIn(0, totalRows)).toDouble()
    return (scanSeconds * remainingRows / totalRows).roundToInt()
}

/** Remaining scan time as a "m:ss" clock, e.g. "0:34" — the ETA label body. */
internal fun formatRxEta(mode: SstvMode, rowsReady: Int, totalRows: Int): String {
    val total = rxRemainingSeconds(mode, rowsReady, totalRows)
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
