package radio.ks3ckc.sstvaf.ui.tx

import radio.ks3ckc.sstvaf.sstv.SstvMode
import java.util.Locale

/**
 * The TX composer's editable state: which photo, how it is cropped into the
 * SSTV mode's frame, and the text overlays stamped on top. Plain immutable
 * data (no Android types) so every transformation is unit-testable; the
 * Compose screen holds one of these in a `mutableStateOf` and replaces it
 * wholesale on every edit.
 */

/** Where an overlay sits inside the composed image. */
enum class OverlayPosition {
    TOP_BAR,
    BOTTOM_BAR,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    CENTER,
}

/**
 * How an overlay is rendered: [BAR] is the SSTV convention — a solid dark
 * band behind the text; [OUTLINE] draws the text with a contrasting halo so
 * it stays readable on any background.
 */
enum class OverlayStyle {
    BAR,
    OUTLINE,
}

/**
 * One line of text stamped onto the composite.
 *
 * @param sizeFraction text height as a fraction of the composed image height
 *   (see [OVERLAY_SIZE_SMALL]/[OVERLAY_SIZE_MEDIUM]/[OVERLAY_SIZE_LARGE]).
 */
data class TextOverlay(
    val text: String,
    val colorArgb: Int = OVERLAY_COLOR_WHITE,
    val sizeFraction: Float = OVERLAY_SIZE_MEDIUM,
    val position: OverlayPosition = OverlayPosition.TOP_BAR,
    val style: OverlayStyle = OverlayStyle.BAR,
)

/**
 * The full composition. [zoom] is 1..4 (1 = the aspect-fill crop, larger
 * zooms in); [panX]/[panY] are in fraction-of-overflow units, -1..1, where 0
 * is centered (see `computeCoverCrop`).
 */
data class TxComposition(
    val sourceUri: String? = null,
    val mode: SstvMode,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val overlays: List<TextOverlay> = emptyList(),
)

// Zoom/pan bounds ------------------------------------------------------------

const val TX_MIN_ZOOM = 1f
const val TX_MAX_ZOOM = 4f

/** Zoom clamped to the legal 1..4 range; NaN degrades to 1 (no zoom). */
fun clampZoom(zoom: Float): Float =
    if (zoom.isNaN()) TX_MIN_ZOOM else zoom.coerceIn(TX_MIN_ZOOM, TX_MAX_ZOOM)

/** Pan clamped to -1..1 (fraction-of-overflow units); NaN degrades to 0 (centered). */
fun clampPan(pan: Float): Float =
    if (pan.isNaN()) 0f else pan.coerceIn(-1f, 1f)

// Overlay presets ------------------------------------------------------------

/** S / M / L size presets (fraction of composed image height). */
const val OVERLAY_SIZE_SMALL = 0.05f
const val OVERLAY_SIZE_MEDIUM = 0.07f
const val OVERLAY_SIZE_LARGE = 0.10f

/** Overlay color swatches (theme palette + white/black). */
const val OVERLAY_COLOR_WHITE = 0xFFFFFFFF.toInt()
const val OVERLAY_COLOR_BLACK = 0xFF000000.toInt()
const val OVERLAY_COLOR_CYAN = 0xFF5CD6E8.toInt()
const val OVERLAY_COLOR_AMBER = 0xFFFFAF5E.toInt()
const val OVERLAY_COLOR_GREEN = 0xFF4ADE80.toInt()

/** The swatch row offered by the overlay editor, in display order. */
val OVERLAY_COLOR_SWATCHES: List<Int> = listOf(
    OVERLAY_COLOR_WHITE,
    OVERLAY_COLOR_BLACK,
    OVERLAY_COLOR_CYAN,
    OVERLAY_COLOR_AMBER,
    OVERLAY_COLOR_GREEN,
)

// Factory + transformations ---------------------------------------------------

/**
 * The pre-seeded CQ bottom-bar text: "CQ SSTV de CALL", with the operator's
 * Maidenhead grid locator appended ("CQ SSTV de CALL GRID") when [grid] is set.
 * The grid is trimmed and uppercased to match the callsign styling and the
 * all-caps SSTV overlay convention; a blank grid is omitted so the bar reads
 * exactly as before. [call] is assumed already trimmed/uppercased by the caller.
 * Uppercasing is locale-stable ([Locale.ROOT]) — grid locators and callsigns are
 * ASCII, so a device locale like Turkish (where 'i' → 'İ') must not mangle them.
 */
internal fun cqBarText(call: String, grid: String): String {
    val loc = grid.trim().uppercase(Locale.ROOT)
    return if (loc.isEmpty()) "CQ SSTV de $call" else "CQ SSTV de $call $loc"
}

/**
 * A fresh composition for [mode], pre-seeded with the SSTV-conventional text:
 * a TOP_BAR with the operator's callsign and a BOTTOM_BAR "CQ SSTV de CALL"
 * (plus the operator's [grid] locator when one is set — see [cqBarText]).
 * A blank/unset callsign seeds no overlays — transmitting a wrong or empty
 * callsign bar would be worse than none — even if a grid is available, since a
 * lone grid line carries no identity.
 */
fun defaultTxComposition(callsign: String, mode: SstvMode, grid: String = ""): TxComposition {
    val call = callsign.trim().uppercase(Locale.ROOT)
    val overlays = if (call.isEmpty()) {
        emptyList()
    } else {
        listOf(
            TextOverlay(
                text = call,
                colorArgb = OVERLAY_COLOR_WHITE,
                sizeFraction = OVERLAY_SIZE_LARGE,
                position = OverlayPosition.TOP_BAR,
                style = OverlayStyle.BAR,
            ),
            TextOverlay(
                text = cqBarText(call, grid),
                colorArgb = OVERLAY_COLOR_WHITE,
                sizeFraction = OVERLAY_SIZE_MEDIUM,
                position = OverlayPosition.BOTTOM_BAR,
                style = OverlayStyle.BAR,
            ),
        )
    }
    return TxComposition(mode = mode, overlays = overlays)
}

/** Copy with [overlay] appended. */
fun TxComposition.withOverlayAdded(overlay: TextOverlay): TxComposition =
    copy(overlays = overlays + overlay)

/** Copy with the overlay at [index] replaced; out-of-range index is a no-op. */
fun TxComposition.withOverlayReplaced(index: Int, overlay: TextOverlay): TxComposition {
    if (index !in overlays.indices) return this
    return copy(overlays = overlays.mapIndexed { i, o -> if (i == index) overlay else o })
}

/** Copy with the overlay at [index] removed; out-of-range index is a no-op. */
fun TxComposition.withOverlayRemoved(index: Int): TxComposition {
    if (index !in overlays.indices) return this
    return copy(overlays = overlays.filterIndexed { i, _ -> i != index })
}

/** Copy with zoom/pan clamped into their legal ranges. */
fun TxComposition.withClampedView(
    zoom: Float = this.zoom,
    panX: Float = this.panX,
    panY: Float = this.panY,
): TxComposition = copy(zoom = clampZoom(zoom), panX = clampPan(panX), panY = clampPan(panY))
