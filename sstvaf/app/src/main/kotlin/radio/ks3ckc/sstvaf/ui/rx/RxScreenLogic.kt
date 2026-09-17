package radio.ks3ckc.sstvaf.ui.rx

import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.gallery.ImageDirection
import radio.ks3ckc.sstvaf.gallery.SavedImage
import radio.ks3ckc.sstvaf.sstv.SstvMode
import radio.ks3ckc.sstvaf.sstv.SstvRxState
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Date
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
 * The RX tab's dial-frequency label, optionally suffixed with the amateur band
 * ("14.230 MHz · 20m"). This mirrors the always-on TX strip pill in the app
 * shell ([radio.ks3ckc.sstvaf.SstvAfApp]) — same " · " separator, same
 * MHz-then-band ordering — so the two live-tuning readouts read identically and
 * the operator sees at a glance which band they're monitoring. A null or blank
 * [bandName] (an out-of-band dial, or a band the rig helper can't name) drops
 * the suffix, leaving the plain "14.230 MHz" rather than a trailing separator.
 */
internal fun formatDialFrequencyWithBand(freqHz: Long, bandName: String?): String {
    val base = formatDialFrequency(freqHz)
    val trimmed = bandName?.trim().orEmpty()
    return if (trimmed.isEmpty()) base else "$base · $trimmed"
}

/**
 * Estimated seconds of image scan still to receive. SSTV scans rows at a
 * constant rate, so the time left is the fraction of rows not yet decoded
 * scaled by the mode's image-scan time (its total TX duration minus the fixed
 * calibration header, [SstvMode.CALIBRATION_HEADER_SECONDS], which has already
 * elapsed by the time rows arrive). Clamped to ≥0; returns 0 for a
 * degenerate/empty total and for an overrun (rowsReady ≥ totalRows).
 */
internal fun rxSecondsRemaining(rowsReady: Int, totalRows: Int, txDurationSeconds: Double): Int {
    if (totalRows <= 0) return 0
    val done = rowsReady.coerceIn(0, totalRows)
    val imageSeconds = (txDurationSeconds - SstvMode.CALIBRATION_HEADER_SECONDS).coerceAtLeast(0.0)
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

// ---------------------------------------------------------------------------
// Status card (the redesigned Receive screen)
// ---------------------------------------------------------------------------

/**
 * What the status card under the canvas is reporting. Distinct from
 * [RxRenderKind], which decides what the *canvas* draws: the canvas has a
 * separate "signal detected" look while the card treats a heard leader as
 * still listening — nothing has been decoded yet, so promising the operator a
 * picture would be premature.
 */
internal enum class RxStatusKind {
    /** The operator switched receive off. Nothing is being listened for. */
    OFF,

    /** Waiting for a transmission. */
    LISTENING,

    /** Rows are arriving. */
    DECODING,

    /** A full image finished decoding; persistence has not reported yet. */
    COMPLETE,

    /** A full image finished and the store confirmed it was written. */
    SAVED,

    /** A full image finished but saving it failed — nothing reached the Gallery. */
    SAVE_FAILED,

    /** Signal lost mid-picture. */
    LOST,
}

/**
 * What persistence has reported about the decode that just finished.
 *
 * Separate from [SstvRxState] because the two are genuinely separate events:
 * [SstvRxState.Complete] means the *decoder* stopped, while the image is
 * written by [radio.ks3ckc.sstvaf.gallery.RxAutoSaveController] on its own
 * thread afterwards — and that save can be skipped (`frameAvailable = false`)
 * or fail outright, in which case nothing ever reaches the Gallery.
 */
internal enum class RxSaveState {
    /** No completed decode to save, or the save was never started. */
    NONE,

    /** A save is in flight. */
    PENDING,

    /** The store confirmed the write. */
    SAVED,

    /** The save threw; the image is lost. */
    FAILED,
}

/**
 * Engine state + the receive switch + persistence → status card.
 *
 * Three inputs, not one. `rxEnabled` is here because
 * [radio.ks3ckc.sstvaf.sstv.SstvSignalListener.setEnabled] only stops feeding
 * the decoder — it publishes no new `rxState` — so a switched-off receiver
 * sits on its last state and would otherwise keep claiming to be "Listening",
 * with a pulsing dot, while the canvas says receive is off. [RxSaveState] is
 * here because a finished decode is not a saved image: see [RxSaveState].
 */
internal fun rxStatusKind(
    state: SstvRxState,
    receiveEnabled: Boolean,
    saveState: RxSaveState,
): RxStatusKind = when {
    !receiveEnabled -> RxStatusKind.OFF
    state is SstvRxState.Decoding -> RxStatusKind.DECODING
    state is SstvRxState.Complete -> when (saveState) {
        RxSaveState.SAVED -> RxStatusKind.SAVED
        RxSaveState.FAILED -> RxStatusKind.SAVE_FAILED
        RxSaveState.NONE, RxSaveState.PENDING -> RxStatusKind.COMPLETE
    }
    state is SstvRxState.Aborted -> RxStatusKind.LOST
    else -> RxStatusKind.LISTENING
}

/**
 * Whether the status dot animates. A receiver that is switched off is not
 * doing anything, and a lost signal is a finished failure — neither should
 * imply ongoing activity.
 */
internal fun rxStatusPulses(kind: RxStatusKind): Boolean =
    kind != RxStatusKind.OFF && kind != RxStatusKind.LOST

/**
 * Whether the canvas earns its "Saved ✓" badge.
 *
 * Only on a confirmed write. The badge is a promise that the picture is in the
 * Gallery, so deriving it from decoder completion made it lie whenever the
 * frame snapshot failed or the asynchronous save threw.
 */
internal fun rxShowsSavedBadge(state: SstvRxState, saveState: RxSaveState): Boolean =
    state is SstvRxState.Complete && saveState == RxSaveState.SAVED

/**
 * The card's right-hand readout.
 *
 * While listening it says "auto-detect" rather than a mode or a dash: the most
 * useful thing to tell an operator staring at an idle receiver is that they do
 * not have to pick anything. Decoding shows the countdown to the end of the
 * picture; a finished decode shows how long the picture took, which is the
 * number an operator compares against the mode they expected.
 */
internal fun rxStatusRightLabel(state: SstvRxState, receiveEnabled: Boolean): String? =
    if (!receiveEnabled) null else when (state) {
    is SstvRxState.Idle, is SstvRxState.Leader -> null
    is SstvRxState.Decoding ->
        formatRxEta(rxSecondsRemaining(state.rowsReady, state.totalRows, state.mode.txDurationSeconds))
    // formatRxEta already renders "m:ss"; reused rather than adding a second
    // identical formatter. Here it is a total, not a countdown.
    is SstvRxState.Complete -> formatRxEta(state.mode.txDurationSeconds.roundToInt())
    is SstvRxState.Aborted -> null
}

/**
 * The 0..1 fill of the card's progress bar. Empty while listening (there is no
 * picture in progress to measure), the decode fraction while rows arrive, full
 * on a completed decode, and — deliberately — the fraction reached when a
 * signal was lost, so the bar shows how much of the picture made it rather
 * than snapping back to zero.
 */
internal fun rxProgressFraction(state: SstvRxState): Float = when (state) {
    is SstvRxState.Idle, is SstvRxState.Leader -> 0f
    is SstvRxState.Decoding ->
        if (state.totalRows <= 0) 0f
        else (state.rowsReady.toFloat() / state.totalRows).coerceIn(0f, 1f)
    is SstvRxState.Complete -> 1f
    is SstvRxState.Aborted -> {
        val total = state.mode?.totalRows ?: 0
        if (total <= 0) 0f else (state.partialRows.toFloat() / total).coerceIn(0f, 1f)
    }
}

/** The rows counter, e.g. `"159 / 256"`, or em dashes before a decode starts. */
internal fun rxRowsLabel(state: SstvRxState): String = when (state) {
    is SstvRxState.Decoding -> "${state.rowsReady} / ${state.totalRows}"
    is SstvRxState.Complete -> "${state.mode.totalRows} / ${state.mode.totalRows}"
    is SstvRxState.Aborted -> {
        val total = state.mode?.totalRows
        if (total == null) "— / —" else "${state.partialRows} / $total"
    }
    else -> "— / —"
}

/** The quality readout as a whole percent, or an em dash when nothing decoded. */
internal fun rxQualityLabel(state: SstvRxState): String = when (state) {
    is SstvRxState.Decoding -> "${(rxQualityFraction(state.quality) * 100f).roundToInt()}%"
    is SstvRxState.Complete -> "${(rxQualityFraction(state.quality) * 100f).roundToInt()}%"
    else -> "—"
}

/** The slant readout, e.g. `"+12 ppm"`, or blank when there is nothing to measure. */
internal fun rxSlantLabel(state: SstvRxState): String = when (state) {
    is SstvRxState.Decoding -> "${formatSlantPpm(state.slantPpm)} ppm"
    else -> ""
}

/**
 * Whether the canvas should be drawing an image at all. False while listening,
 * so the canvas shows its idle animation instead of a stale picture from the
 * previous decode.
 */
internal fun rxShowsCanvasImage(state: SstvRxState): Boolean = when (state) {
    is SstvRxState.Decoding -> true
    // frameAvailable = false means the row read failed and nothing was
    // snapshotted, so LastDecodedImage.frame still holds the PREVIOUS decode.
    // Showing it would present a stale picture as the one that just finished.
    is SstvRxState.Complete -> state.frameAvailable
    is SstvRxState.Aborted -> showsPartialImage(state)
    else -> false
}

/**
 * The top-down reveal fraction for a decoding canvas: how much of the image
 * height is filled in. The design clips the image to this and rides a glowing
 * line at the boundary, which is the same thing an operator sees on a real
 * SSTV monitor — the picture painting itself downward.
 *
 * A finished picture is fully revealed; anything else is 0.
 */
internal fun rxRevealFraction(state: SstvRxState): Float = when (state) {
    is SstvRxState.Decoding -> rxProgressFraction(state)
    is SstvRxState.Complete -> 1f
    is SstvRxState.Aborted -> rxProgressFraction(state)
    else -> 0f
}

/**
 * How many thumbnails the "Received today" strip shows.
 *
 * Four. The strip is a glance, not a browser — it answers "did anything come
 * in while I was away?" and hands off to the Gallery for everything else. Four
 * 84dp cells plus gaps is also what fits across a compact phone without the
 * last one being a sliver that invites a scroll the row does not have.
 */
internal const val RX_RECENT_LIMIT = 4

/**
 * The caption under a "Received today" thumbnail: mode short code and the
 * decode time, e.g. `"S1 · 14:02"`.
 *
 * Local wall-clock time, not UTC, and not a relative age. The strip answers
 * "when did this come in?" for someone who was in the room, and "14:02" is
 * what they compare against their own memory of the afternoon. The Gallery,
 * which is for going back through a log, uses UTC.
 */
internal fun rxRecentCaption(entry: SavedImage): String {
    val shortCode = SstvMode.entries.firstOrNull { it.displayName == entry.mode }?.shortCode
        ?: entry.mode
    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(entry.utcMillis))
    return "$shortCode · $time"
}

/**
 * The status card's label resource.
 *
 * Decoding's string takes the mode name as a format argument ("Decoding
 * Scottie 1") — naming the mode is the moment the operator learns what is
 * arriving, since they never chose it. The other three take no arguments; see
 * [rxStatusLabelTakesMode].
 */
internal fun rxStatusLabelRes(kind: RxStatusKind): Int = when (kind) {
    RxStatusKind.OFF -> R.string.rx_status_off
    RxStatusKind.LISTENING -> R.string.rx_status_listening
    RxStatusKind.DECODING -> R.string.rx_status_decoding
    RxStatusKind.COMPLETE -> R.string.rx_status_complete
    RxStatusKind.SAVED -> R.string.rx_status_saved
    RxStatusKind.SAVE_FAILED -> R.string.rx_status_save_failed
    RxStatusKind.LOST -> R.string.rx_status_lost
}

/**
 * Whether [rxStatusLabelRes] returns a format string needing the mode name.
 * Only the decoding label does. Kept as its own predicate so the caller cannot
 * pass an argument to a string that has no placeholder (which silently formats
 * to the bare text) or omit one from the string that does.
 */
internal fun rxStatusLabelTakesMode(kind: RxStatusKind): Boolean =
    kind == RxStatusKind.DECODING

// ---------------------------------------------------------------------------
// "Received today" strip
// ---------------------------------------------------------------------------

/**
 * The saved images the "RECEIVED TODAY" strip should show: received (not
 * transmitted), dated today in the operator's own time zone, newest first,
 * capped at [RX_RECENT_LIMIT].
 *
 * The heading is a promise the list has to keep. Filtering only by direction
 * meant the strip showed whatever the last four RX images were — a week old,
 * a year old — and kept showing yesterday's pictures after local midnight
 * because nothing re-evaluated the day.
 *
 * "Today" is the operator's local calendar day, not a rolling 24 hours: the
 * strip sits next to a UTC-stamped log, and an operator reading "today" means
 * the day they are having.
 */
internal fun rxImagesReceivedToday(
    images: List<SavedImage>,
    nowMillis: Long,
    zone: ZoneId,
    limit: Int = RX_RECENT_LIMIT,
): List<SavedImage> {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return images
        .filter { it.direction == ImageDirection.RX }
        .filter { Instant.ofEpochMilli(it.utcMillis).atZone(zone).toLocalDate() == today }
        .sortedByDescending { it.utcMillis }
        .take(limit)
}

/**
 * Milliseconds from [nowMillis] until the next local midnight — when the strip
 * has to re-evaluate "today" or it will keep yesterday's pictures on screen
 * for a receiver left running overnight.
 *
 * Always strictly positive, so a caller using it as a delay cannot spin: at
 * exactly midnight the answer is a whole day, not zero.
 */
internal fun rxMillisUntilNextLocalDay(nowMillis: Long, zone: ZoneId): Long {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
    return Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L)
}

/**
 * The tallest the receive canvas may be, in dp, for a screen [screenHeightDp]
 * tall.
 *
 * The canvas is a full-width 4:3 box, so on a wide-but-short canvas — a phone
 * in landscape, or the tablet rail layout — its natural height is most of the
 * screen and the status card and the "received today" strip get measured out
 * of existence below it. This caps it at just over half the height so the rest
 * of the screen keeps room, with a floor so a very short window still shows a
 * usable picture rather than a sliver (the screen scrolls in that case).
 */
internal fun rxCanvasMaxHeightDp(screenHeightDp: Int): Int =
    (screenHeightDp * 0.56f).roundToInt().coerceAtLeast(140)
