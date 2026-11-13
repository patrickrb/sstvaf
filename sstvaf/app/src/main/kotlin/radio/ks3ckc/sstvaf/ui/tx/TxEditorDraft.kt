package radio.ks3ckc.sstvaf.ui.tx

import com.k1af.ft8af.R

/**
 * The editor's *pending* choices: what the next overlay or stroke will look
 * like, and the text being typed.
 *
 * Separate from [TxComposition] because these are not part of the picture. The
 * composition is what gets transmitted; this is the operator's current brush
 * settings. Keeping them apart means "Send again" can restore a picture exactly
 * (see the composition's edit list) without also restoring which swatch
 * happened to be selected at the time.
 *
 * Selecting an overlay copies its colour, size and style in here, so the
 * controls show that overlay's settings and changing one edits it — the design's
 * behaviour, and the reason a single set of controls can serve both "the next
 * thing" and "the selected thing".
 */
data class TxEditorDraft(
    val text: String = "",
    val colorArgb: Int = OVERLAY_COLOR_WHITE,
    val sizeFraction: Float = OVERLAY_SIZE_MEDIUM,
    val style: OverlayStyle = OverlayStyle.BAR,
    val strokeWidth: Float = STROKE_MEDIUM,
)

/** The draft updated to match [overlay], for when one is selected. */
internal fun TxEditorDraft.matching(overlay: TextOverlay): TxEditorDraft = copy(
    text = overlay.text,
    colorArgb = overlay.colorArgb,
    sizeFraction = overlay.sizeFraction,
    style = overlay.style,
)

/** The draft with its text cleared, for after an overlay is committed. */
internal fun TxEditorDraft.cleared(): TxEditorDraft = copy(text = "")

// ---------------------------------------------------------------------------
// Zoom slider mapping
// ---------------------------------------------------------------------------

/**
 * The zoom slider's range, in percent — 100 % is the aspect-fill crop.
 *
 * Capped at 250 % rather than the composition's own 4x limit, because the
 * design's slider stops at 2.5x and past roughly 2.5x a phone photo cropped to
 * a 320-pixel SSTV frame is being upscaled from so few source pixels that the
 * transmitted picture is mush. An operator can still reach higher zoom by
 * pinching, where they can see the result as they do it.
 */
internal const val ZOOM_SLIDER_MIN = 100
internal const val ZOOM_SLIDER_MAX = 250

/** Composition zoom (1..4) → slider percent, clamped to the slider's range. */
internal fun zoomToSlider(zoom: Float): Int =
    (clampZoom(zoom) * 100f).toInt().coerceIn(ZOOM_SLIDER_MIN, ZOOM_SLIDER_MAX)

/** Slider percent → composition zoom. */
internal fun sliderToZoom(percent: Int): Float =
    clampZoom(percent.coerceIn(ZOOM_SLIDER_MIN, ZOOM_SLIDER_MAX) / 100f)

/**
 * The zoom the "Fill frame" button applies.
 *
 * A slight zoom in, not a reset: the button exists for the case where the
 * aspect-fill crop leaves the subject too small in a frame of a different
 * shape, and a nudge in is what fixes that. Reset (1x) is the adjacent button
 * for going back.
 */
internal const val FILL_FRAME_ZOOM = 1.3f

// ---------------------------------------------------------------------------
// Labels
// ---------------------------------------------------------------------------

/** The Bar / Outline button label. */
internal fun overlayStyleLabelRes(style: OverlayStyle): Int = when (style) {
    OverlayStyle.BAR -> R.string.tx_style_bar
    OverlayStyle.OUTLINE -> R.string.tx_style_outline
}

/** A frame option's label. */
internal fun frameLabelRes(frame: ImageFrame): Int = when (frame) {
    ImageFrame.NONE -> R.string.tx_frame_none
    ImageFrame.THIN -> R.string.tx_frame_thin
    ImageFrame.BOLD -> R.string.tx_frame_bold
    ImageFrame.AMBER -> R.string.tx_frame_amber
}

/** A callsign preset's accessibility description. */
internal fun presetDescriptionRes(preset: CallsignPreset): Int = when (preset) {
    CallsignPreset.TOP_LEFT -> R.string.tx_preset_top_left
    CallsignPreset.TOP_CENTER -> R.string.tx_preset_top_center
    CallsignPreset.TOP_RIGHT -> R.string.tx_preset_top_right
    CallsignPreset.BOTTOM_LEFT -> R.string.tx_preset_bottom_left
    CallsignPreset.BOTTOM_CENTER -> R.string.tx_preset_bottom_center
    CallsignPreset.BOTTOM_RIGHT -> R.string.tx_preset_bottom_right
}
