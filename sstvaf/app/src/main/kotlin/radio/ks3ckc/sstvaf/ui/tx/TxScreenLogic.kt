package radio.ks3ckc.sstvaf.ui.tx

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

/** Mode selector chip label, e.g. "Scottie 1 · 111 s" (duration rounded to whole seconds). */
internal fun modeChipLabel(mode: SstvMode): String =
    "${mode.displayName} · ${mode.txDurationSeconds.roundToInt()} s"

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
 * Confirm-sheet duration line, e.g. "Robot 36 — 37 seconds". When a CW
 * station-ID tail is appended ([cwTailSeconds] > 0) the total airtime is shown
 * and flagged so the operator knows how long the rig will actually key, e.g.
 * "Robot 36 — 42 seconds (incl. CW ID)".
 */
internal fun confirmDurationLine(mode: SstvMode, cwTailSeconds: Double = 0.0): String {
    val total = totalTxDurationSeconds(mode, cwTailSeconds).roundToInt()
    val idNote = if (cwTailSeconds > 0.0) " (incl. CW ID)" else ""
    return "${mode.displayName} — $total seconds$idNote"
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
