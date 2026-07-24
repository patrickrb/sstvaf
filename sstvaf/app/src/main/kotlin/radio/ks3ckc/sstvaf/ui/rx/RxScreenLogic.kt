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
 * The fixed calibration header (1900 Hz leader + VIS) every SSTV mode plays
 * before the first image row. [SstvMode.txDurationSeconds] bundles it into the
 * total transmission time (see the enum's kdoc); the ETA math removes it,
 * because by the time rows are arriving in the Decoding state the header is
 * already behind us.
 */
internal const val SSTV_HEADER_SECONDS = 0.91

/**
 * Estimated seconds of image left to receive: the mode's image-only scan time
 * scaled by the fraction of rows still to come. The scan time is the mode's
 * total [SstvMode.txDurationSeconds] minus the [SSTV_HEADER_SECONDS] header
 * (SSTV timing is symmetric, so a mode receives in the same time it transmits).
 * Clamped so a completed or overrun frame (rowsReady >= totalRows) and a
 * degenerate total (<= 0) both yield 0.
 */
internal fun rxEtaSeconds(mode: SstvMode, rowsReady: Int, totalRows: Int): Double {
    if (totalRows <= 0) return 0.0
    val imageSeconds = (mode.txDurationSeconds - SSTV_HEADER_SECONDS).coerceAtLeast(0.0)
    val remainingRows = (totalRows - rowsReady).coerceIn(0, totalRows)
    return imageSeconds * remainingRows / totalRows
}

/**
 * ETA as a short "~m:ss" label (e.g. "~0:12"), rounding to whole seconds and
 * never going negative. Shown under the forming image so the operator knows
 * roughly how long until the picture completes.
 */
internal fun formatRxEta(secondsRemaining: Double): String {
    val total = secondsRemaining.roundToInt().coerceAtLeast(0)
    return String.format(Locale.US, "~%d:%02d", total / 60, total % 60)
}

/** ETA "~m:ss" label straight from the live decode counters. */
internal fun rxEtaLabel(mode: SstvMode, rowsReady: Int, totalRows: Int): String =
    formatRxEta(rxEtaSeconds(mode, rowsReady, totalRows))

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
