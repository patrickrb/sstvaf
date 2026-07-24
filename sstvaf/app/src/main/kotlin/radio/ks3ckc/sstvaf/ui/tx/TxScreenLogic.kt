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
 * The mode's picture resolution as a compact "width×height" label, e.g.
 * "320×256". Surfaced next to the duration so an operator picking among the
 * (now 16) modes sees the picture-quality half of the trade-off — Robot 36 is
 * 320×240 in 37 s, PD 290 is 800×616 but takes 290 s — not just the airtime.
 */
internal fun modeResolutionLabel(mode: SstvMode): String = "${mode.width}×${mode.height}"

/** Mode selector chip label, e.g. "Scottie 1 · 320×256 · 111 s" (duration rounded to whole seconds). */
internal fun modeChipLabel(mode: SstvMode): String =
    "${mode.displayName} · ${modeResolutionLabel(mode)} · ${mode.txDurationSeconds.roundToInt()} s"

/**
 * Total on-air seconds for a transmission: the [mode] image scan plus the
 * optional CW station-ID tail ([cwTailSeconds], 0 when the ID is off; a
 * negative value is treated as 0). This is the duration the transmitter's
 * progress ticker actually measures (image + CW — see SstvTransmitter), so the
 * confirm sheet and the progress readout use it rather than the bare
 * [SstvMode.txDurationSeconds], which under-reports airtime whenever the CW ID
 * is enabled.
 */
internal fun totalTxDurationSeconds(mode: SstvMode, cwTailSeconds: Double): Double =
    mode.txDurationSeconds + cwTailSeconds.coerceAtLeast(0.0)

/**
 * Confirm-sheet duration line, e.g. "Robot 36 — 320×240 — 37 seconds". When a
 * CW station-ID tail is appended ([cwTailSeconds] > 0) the total airtime is
 * shown and flagged so the operator knows how long the rig will actually key,
 * e.g. "Robot 36 — 320×240 — 42 seconds (incl. CW ID)".
 */
internal fun confirmDurationLine(mode: SstvMode, cwTailSeconds: Double = 0.0): String {
    val total = totalTxDurationSeconds(mode, cwTailSeconds).roundToInt()
    val idNote = if (cwTailSeconds > 0.0) " (incl. CW ID)" else ""
    return "${mode.displayName} — ${modeResolutionLabel(mode)} — $total seconds$idNote"
}

/**
 * A plain-language class for how long the rig will be keyed, so the confirm
 * sheet can tell an operator whether they are committing to a quick or a
 * multi-minute transmission before they tie up the frequency. It is purely a
 * function of the *total* airtime (image scan + optional CW ID tail — see
 * [totalTxDurationSeconds]); the picture-quality half of the trade-off is
 * already carried by the resolution in [confirmDurationLine]/[modeChipLabel].
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
