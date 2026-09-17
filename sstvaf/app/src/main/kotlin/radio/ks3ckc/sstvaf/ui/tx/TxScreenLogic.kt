package radio.ks3ckc.sstvaf.ui.tx

import androidx.annotation.StringRes
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.sstv.SstvMode
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure decision/formatting logic for [TxComposeScreen], extracted per project
 * testing policy (Composables stay thin wrappers; this file carries the unit
 * tests — see TxScreenLogicTest and TxTransmitFlowTest).
 */

// ---------------------------------------------------------------------------
// Transmit gating
// ---------------------------------------------------------------------------

/** Why (or whether) the TRANSMIT button is available. */
internal enum class TxGate {
    /** Ready to transmit. */
    READY,

    /** No photo picked yet. */
    NO_IMAGE,

    /** An SSTV transmission is already running. */
    TRANSMITTING,

    /** The Tune carrier owns the rig. */
    TUNE_ACTIVE,
}

/**
 * The single gating decision for the TRANSMIT button. Order matters: an
 * active transmission (which also blocks editing) outranks Tune, which
 * outranks a missing image.
 */
internal fun transmitGate(hasImage: Boolean, isTransmitting: Boolean, tuneActive: Boolean): TxGate =
    when {
        isTransmitting -> TxGate.TRANSMITTING
        tuneActive -> TxGate.TUNE_ACTIVE
        !hasImage -> TxGate.NO_IMAGE
        else -> TxGate.READY
    }

// ---------------------------------------------------------------------------
// Labels
// ---------------------------------------------------------------------------

/**
 * The mode's picture resolution, e.g. "320 × 256". Surfaced next to the
 * duration so an operator picking among the (now 16) modes sees the
 * picture-quality half of the trade-off — Robot 36 is 320 × 240 in 37 s,
 * PD 290 is 800 × 616 but takes 290 s — not just the airtime.
 *
 * Spaces around the multiplication sign because this is set in 11sp mono in the
 * mode sheet, where "320×256" closes up into a single glyph-blur. The confirm
 * sheet shares the helper rather than keeping its own spelling.
 */
internal fun modeResolutionLabel(mode: SstvMode): String = "${mode.width} × ${mode.height}"

/**
 * Total on-air seconds for a transmission: the [mode] image scan plus the
 * optional CW station-ID tail ([cwTailSeconds], 0 when the ID is off) plus the
 * VOX pre-tone prepended in VOX control mode ([voxPreToneSeconds], 0 when a
 * rig is keyed explicitly); negative values are treated as 0. This is the
 * duration the transmitter's progress ticker actually measures (pre-tone +
 * image + CW — see SstvTransmitter), so the confirm sheet and the progress
 * readout use it rather than the bare [SstvMode.txDurationSeconds], which
 * under-reports airtime whenever either extra is in play.
 */
internal fun totalTxDurationSeconds(
    mode: SstvMode,
    cwTailSeconds: Double,
    voxPreToneSeconds: Double = 0.0,
): Double =
    mode.txDurationSeconds + cwTailSeconds.coerceAtLeast(0.0) +
        voxPreToneSeconds.coerceAtLeast(0.0)

/**
 * Confirm-sheet duration line, e.g. "Robot 36 — 320 × 240 — 37 seconds". When a
 * CW station-ID tail is appended ([cwTailSeconds] > 0) the total airtime is
 * shown and flagged so the operator knows how long the rig will actually key,
 * e.g. "Robot 36 — 320 × 240 — 42 seconds (incl. CW ID)". A VOX pre-tone folds
 * into the total silently — it is sub-second leader, not a separate segment
 * the operator would notice on air.
 */
internal fun confirmDurationLine(
    mode: SstvMode,
    cwTailSeconds: Double = 0.0,
    voxPreToneSeconds: Double = 0.0,
): String {
    val total = totalTxDurationSeconds(mode, cwTailSeconds, voxPreToneSeconds).roundToInt()
    val idNote = if (cwTailSeconds > 0.0) " (incl. CW ID)" else ""
    return "${mode.displayName} — ${modeResolutionLabel(mode)} — $total seconds$idNote"
}

/**
 * A plain-language class for how long the rig will be keyed, so the confirm
 * sheet can tell an operator whether they are committing to a quick or a
 * multi-minute transmission before they tie up the frequency. It is purely a
 * function of the *total* airtime (image scan + optional CW ID tail — see
 * [totalTxDurationSeconds]); the picture-quality half of the trade-off is
 * already carried by the resolution in [confirmDurationLine].
 */
internal enum class TxAirtimeClass(@StringRes val labelRes: Int) {
    QUICK(R.string.tx_airtime_quick),
    MODERATE(R.string.tx_airtime_moderate),
    LONG(R.string.tx_airtime_long),
    VERY_LONG(R.string.tx_airtime_very_long),
}

/**
 * Classify a transmission's total airtime into a [TxAirtimeClass]. Thresholds
 * (inclusive lower bounds), chosen around the practical SSTV airtime spread the
 * app supports (Martin 4 ≈ 30 s … PD 290 ≈ 290 s) so every class is actually
 * reachable — each of the 16 modes falls into one of these buckets:
 *   < 60 s   → QUICK       (Robot 36, Martin 2/3/4, PD 50, …)
 *   < 120 s  → MODERATE    (Robot 72, Scottie 1/2, Martin 1, PD 90, …)
 *   < 240 s  → LONG        (PD 120/160/180)
 *   ≥ 240 s  → VERY_LONG   (Scottie DX, PD 240/290)
 * Folds in the CW ID tail via [totalTxDurationSeconds], so enabling the ID can
 * bump a mode into the next class up when it nudges the total past a boundary.
 */
internal fun txAirtimeClass(mode: SstvMode, cwTailSeconds: Double = 0.0): TxAirtimeClass {
    val total = totalTxDurationSeconds(mode, cwTailSeconds)
    return when {
        total < 60.0 -> TxAirtimeClass.QUICK
        total < 120.0 -> TxAirtimeClass.MODERATE
        total < 240.0 -> TxAirtimeClass.LONG
        else -> TxAirtimeClass.VERY_LONG
    }
}

/** Seconds → "m:ss". */
internal fun formatMinSec(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/**
 * The elapsed/total line under the TX progress bar, e.g. "0:42 / 1:51".
 * Elapsed is derived from the transmitter's 0..1 progress fraction (the sink
 * has no sample-accurate callback; see SstvTransmitter's ticker).
 */
internal fun txElapsedLabel(progress: Float, durationSeconds: Double): String {
    val total = durationSeconds.roundToInt().coerceAtLeast(0)
    val elapsed = (progress.coerceIn(0f, 1f) * total).roundToInt().coerceAtMost(total)
    return "${formatMinSec(elapsed)} / ${formatMinSec(total)}"
}

/**
 * Whole seconds of transmission still to play, the mirror of the elapsed value
 * inside [txElapsedLabel] (total − elapsed) so the countdown and the elapsed/
 * total line never disagree by a rounding tick. Clamped to 0..total, so a
 * finished (progress ≥ 1f) or degenerate (duration ≤ 0) transmission reads 0
 * rather than going negative.
 */
internal fun txRemainingSeconds(progress: Float, durationSeconds: Double): Int {
    val total = durationSeconds.roundToInt().coerceAtLeast(0)
    val elapsed = (progress.coerceIn(0f, 1f) * total).roundToInt().coerceIn(0, total)
    return total - elapsed
}

/**
 * Preformatted "m:ss" countdown of transmit time left, e.g. "1:09". Fed into
 * the `tx_remaining_format` resource ("%1$s left") for display under the TX
 * progress bar, mirroring the RX decode ETA so both directions surface a
 * plain-language "how much longer" readout.
 */
internal fun txRemainingLabel(progress: Float, durationSeconds: Double): String =
    formatMinSec(txRemainingSeconds(progress, durationSeconds))

// ---------------------------------------------------------------------------
// Last-used-mode persistence (config key "sstvTxMode")
// ---------------------------------------------------------------------------

/** The mode the composer opens with: the persisted enum name, else Scottie 1. */
internal fun initialTxMode(configValue: String?): SstvMode =
    SstvMode.entries.firstOrNull { it.name == configValue } ?: SstvMode.SCOTTIE_1

// ---------------------------------------------------------------------------
// Preview frame sizing
// ---------------------------------------------------------------------------

/**
 * Height cap (dp) for the mode-aspect preview frame so the pick/camera
 * buttons, mode chips and TRANSMIT stay reachable.
 *
 * Portrait (or square) returns null: the frame fills the width as before, and
 * its aspect-derived height is comfortably shorter than the tall screen.
 *
 * Landscape returns a bound — the frame is then sized by height and centered,
 * so a very wide screen no longer stretches it to full width (which made it
 * taller than the whole screen and shoved every control off the bottom). The
 * cap is ~half the screen height, further limited so the aspect-derived width
 * (`height * aspect`) still fits the screen width.
 *
 * @param aspect frame width / height (e.g. 320/256 = 1.25).
 */
internal fun previewMaxHeightDp(screenWidthDp: Int, screenHeightDp: Int, aspect: Float): Float? {
    if (screenHeightDp >= screenWidthDp || aspect <= 0f) return null
    val byHeight = screenHeightDp * 0.5f
    val byWidth = screenWidthDp / aspect
    return minOf(byHeight, byWidth)
}

// ---------------------------------------------------------------------------
// Gestures
// ---------------------------------------------------------------------------

/**
 * Apply one pinch/drag gesture step to the composition.
 *
 * The drag delta arrives in preview pixels; converting to the composition's
 * fraction-of-overflow pan units needs the current crop size in source
 * pixels: dragging the finger by `dx` preview px shifts the crop window by
 * `dx * cropW / previewW` source px, and one pan unit is `overflow / 2`
 * source px. Dragging right moves the image right (reveals more of the
 * left), i.e. decreases panX. Zero overflow on an axis leaves that pan
 * untouched. Everything is clamped.
 */
internal fun applyPanZoomGesture(
    comp: TxComposition,
    srcW: Int,
    srcH: Int,
    previewW: Float,
    previewH: Float,
    panDxPx: Float,
    panDyPx: Float,
    zoomFactor: Float,
): TxComposition {
    if (srcW <= 0 || srcH <= 0 || previewW <= 0f || previewH <= 0f) return comp

    val newZoom = clampZoom(comp.zoom * zoomFactor)

    // Crop geometry at the NEW zoom (so pan deltas mid-pinch track the finger).
    val crop = computeCoverCrop(srcW, srcH, comp.mode.width, comp.mode.height, newZoom, comp.panX, comp.panY)
    if (crop.isEmpty) return comp.withClampedView(zoom = newZoom)

    val overflowX = (srcW - crop.width()).toFloat()
    val overflowY = (srcH - crop.height()).toFloat()

    // Start from clamped values so a NaN smuggled in through corrupted state
    // degrades to centered instead of surviving forever on a zero-overflow
    // axis; valid values on a zero-overflow axis stay untouched as before.
    var panX = clampPan(comp.panX)
    var panY = clampPan(comp.panY)
    if (overflowX > 0f) {
        panX = clampPan(panX - panDxPx * (crop.width() / previewW) / (overflowX / 2f))
    }
    if (overflowY > 0f) {
        panY = clampPan(panY - panDyPx * (crop.height() / previewH) / (overflowY / 2f))
    }
    return comp.copy(zoom = newZoom, panX = panX, panY = panY)
}

// ---------------------------------------------------------------------------
// Transmit + save orchestration
// ---------------------------------------------------------------------------

/** Starts a transmission (the screen adapts SstvTransmitter.transmit). */
internal fun interface TransmitStarter {
    fun start(pixels: IntArray, width: Int, height: Int, mode: SstvMode): Boolean
}

/** Persists the transmitted composite (the screen adapts ReceivedImageStore.save). */
internal fun interface TxImageSaver {
    fun save(pixels: IntArray, width: Int, height: Int, mode: SstvMode, utcMillis: Long, freqHz: Long)
}

/**
 * The confirm-sheet CONFIRM action: hand the rendered composite to the
 * transmitter; only if it ACCEPTS (keys up) does the image get persisted to
 * the gallery (direction TX) and logged. A rejected start (tune active /
 * already transmitting — the transmitter re-checks under its own lock) saves
 * nothing: the gallery must only contain images that actually went to RF.
 *
 * A save failure (ReceivedImageStore.save throws IOException on encode/insert
 * failure) is logged and swallowed: by that point the transmitter has already
 * keyed up and audio is playing, so the transmission proceeds — it just won't
 * appear in the gallery.
 *
 * @return true when the transmission started.
 */
internal fun performTransmit(
    pixels: IntArray,
    width: Int,
    height: Int,
    mode: SstvMode,
    freqHz: Long,
    utcMillis: Long,
    starter: TransmitStarter,
    saver: TxImageSaver,
    log: (String) -> Unit,
): Boolean {
    val accepted = starter.start(pixels, width, height, mode)
    if (!accepted) {
        log("SSTV TX composer: transmit rejected — mode=${mode.displayName}")
        return false
    }
    try {
        saver.save(pixels, width, height, mode, utcMillis, freqHz)
    } catch (e: IOException) {
        // The documented failure mode of ReceivedImageStore.save (encode/
        // insert failure). Transmission is already on the air; losing the
        // gallery copy is the lesser failure — log and continue. Anything
        // else is a programmer error and should surface loudly.
        log("SSTV TX composer: gallery save failed — $e")
    }
    log(
        "SSTV TX composer: transmit started — mode=${mode.displayName}" +
            " ${width}x$height freqHz=$freqHz",
    )
    return true
}

// ---------------------------------------------------------------------------
// Mode sheet: speed groups and duration colouring
// ---------------------------------------------------------------------------

/**
 * How the mode sheet groups the mode list.
 *
 * Operators do not pick an SSTV mode by name, they pick by what they are
 * willing to spend: how long the frequency is tied up, and how much detail
 * survives the trip. The groups are that trade-off made visible.
 */
internal enum class ModeSpeedGroup {
    /** Under a minute on the air. */
    FAST,

    /** A minute or two — where almost all activity sits. */
    STANDARD,

    /** Wider than the 320-pixel standard: more detail, much more air time. */
    HIGH_RESOLUTION,
}

/**
 * Width above which a mode counts as high resolution.
 *
 * 320 pixels is the SSTV standard raster; every classic Scottie/Martin/Robot
 * mode is 320 wide. Anything wider (PD 120 at 640, PD 290 at 800) is trading
 * a lot of air time for detail, which is a different decision from "how fast
 * do I want this over with" — hence its own group rather than sorting by
 * duration alongside the rest.
 */
internal const val HIGH_RESOLUTION_MIN_WIDTH = 320

/** Duration at or above which a mode stops counting as fast, in seconds. */
internal const val FAST_MODE_MAX_SECONDS = 60.0

/**
 * Which group a mode belongs to. Resolution is checked first: a 640-wide mode
 * is high-resolution whatever its duration.
 */
internal fun modeSpeedGroup(mode: SstvMode): ModeSpeedGroup = when {
    mode.width > HIGH_RESOLUTION_MIN_WIDTH -> ModeSpeedGroup.HIGH_RESOLUTION
    mode.txDurationSeconds < FAST_MODE_MAX_SECONDS -> ModeSpeedGroup.FAST
    else -> ModeSpeedGroup.STANDARD
}

/** One group of modes in the sheet, in display order. */
internal data class ModeGroup(
    val group: ModeSpeedGroup,
    val modes: List<SstvMode>,
)

/**
 * The mode sheet's contents: every mode the codec supports, grouped by the
 * trade-off above and sorted shortest-first inside each group.
 *
 * All of [SstvMode.entries], not a curated nine. The handoff's sheet lists the
 * nine classic modes, but the codec has shipped sixteen since issue #16 and the
 * transmitter will happily send any of them — hiding seven working modes behind
 * no UI at all would be worse than a slightly longer sheet. The grouping rule
 * places the handoff's nine exactly where its design puts them, and gives the
 * other seven a home without a second hand-maintained list to drift.
 *
 * Empty groups are omitted so the sheet never renders a header with nothing
 * under it.
 */
internal fun modeGroups(modes: List<SstvMode> = SstvMode.entries): List<ModeGroup> =
    ModeSpeedGroup.entries.mapNotNull { group ->
        val inGroup = modes.filter { modeSpeedGroup(it) == group }
            .sortedBy { it.txDurationSeconds }
        if (inGroup.isEmpty()) null else ModeGroup(group, inGroup)
    }

/** How a mode's duration reads in the sheet: reassuring, neutral, or a warning. */
internal enum class ModeDurationTone {
    /** Under a minute — cheap to send. */
    QUICK,

    /** A minute or two — the normal case. */
    NORMAL,

    /** Long enough that the frequency is tied up for a while. */
    LONG,
}

/** Upper bound (exclusive) of the neutral duration band, in seconds. */
internal const val NORMAL_DURATION_MAX_SECONDS = 100.0

/**
 * The tone for a mode's duration readout. The thresholds match the groups'
 * spirit but are deliberately independent of them: PD 120 sits in the
 * high-resolution group and still needs its two-minute duration flagged.
 */
internal fun modeDurationTone(mode: SstvMode): ModeDurationTone = when {
    mode.txDurationSeconds < FAST_MODE_MAX_SECONDS -> ModeDurationTone.QUICK
    mode.txDurationSeconds < NORMAL_DURATION_MAX_SECONDS -> ModeDurationTone.NORMAL
    else -> ModeDurationTone.LONG
}

/** A mode's duration as the sheet's `m:ss` readout. */
internal fun modeDurationLabel(mode: SstvMode): String =
    formatMinSec(mode.txDurationSeconds.roundToInt())

/**
 * The line under a mode's name in the sheet: its dimensions, plus a note when
 * the mode has one worth saying (Scottie 1 is what you will actually hear on
 * 20m, which is the single most useful thing a newcomer can be told here).
 *
 * [note] is passed in already resolved so this stays a pure function; the
 * resource lookup lives with the composable (see `modeNoteRes`). A blank or
 * absent note yields the dimensions alone rather than a trailing separator.
 */
internal fun modeSubLabel(mode: SstvMode, note: String?): String {
    val dimensions = modeResolutionLabel(mode)
    val trimmed = note?.trim().orEmpty()
    return if (trimmed.isEmpty()) dimensions else "$dimensions · $trimmed"
}

/**
 * The confirm sheet's frequency line, e.g. `"14.230 MHz · 20m"`.
 *
 * Both the dial and the band, unlike the header chip which drops the unit for
 * space. This is the sheet where an operator is about to tie up a frequency,
 * so it says exactly which one in full.
 */
internal fun confirmFrequencyLine(freqHz: Long, bandLabel: String): String {
    val mhz = String.format(Locale.US, "%.3f MHz", freqHz / 1_000_000.0)
    val band = bandLabel.trim()
    return if (band.isEmpty()) mhz else "$mhz · $band"
}
