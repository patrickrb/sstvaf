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

/**
 * One of the six positions the Callsign tool offers, as the design lays them
 * out: a 3x2 grid of top and bottom, left / centre / right.
 *
 * A preset is a *starting point*, not a constraint - the stamp it places is an
 * ordinary overlay the operator can then drag anywhere. That is why this enum
 * carries percentages rather than the overlay storing which preset made it:
 * once dragged, "bottom-left" would be a lie.
 *
 * The centre presets place a BAR and the corners place an OUTLINE, which is the
 * design's rule and also the sensible one: a bar only makes sense spanning the
 * full width, and a corner stamp needs a halo because it sits over picture
 * rather than over its own band.
 */
enum class CallsignPreset(
    val xPercent: Float,
    val yPercent: Float,
    val style: OverlayStyle,
) {
    TOP_LEFT(12f, 9f, OverlayStyle.OUTLINE),
    TOP_CENTER(50f, 8f, OverlayStyle.BAR),
    TOP_RIGHT(88f, 9f, OverlayStyle.OUTLINE),
    BOTTOM_LEFT(12f, 91f, OverlayStyle.OUTLINE),
    BOTTOM_CENTER(50f, 92f, OverlayStyle.BAR),
    BOTTOM_RIGHT(88f, 91f, OverlayStyle.OUTLINE),
}

/**
 * The preset an existing stamp currently matches, or null once it has been
 * dragged somewhere of its own.
 *
 * Drives which preset cell reads as selected. Compared with a tolerance
 * because a drag stores whatever percentage the finger landed on, so exact
 * equality would almost never hold - but a stamp the operator nudged by a
 * pixel should still show its preset as the active one.
 */
fun matchingCallsignPreset(overlay: TextOverlay): CallsignPreset? =
    CallsignPreset.entries.firstOrNull { preset ->
        kotlin.math.abs(preset.xPercent - overlay.xPercent) < PRESET_MATCH_TOLERANCE &&
            kotlin.math.abs(preset.yPercent - overlay.yPercent) < PRESET_MATCH_TOLERANCE
    }

/** How close (in frame percent) a stamp has to be to count as "on" a preset. */
const val PRESET_MATCH_TOLERANCE = 1.5f

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
 * Positioned by [xPercent]/[yPercent], the overlay's centre as a percentage of
 * the composed frame — not by a preset corner. Percentages because the operator
 * drags overlays around the canvas now, and because changing SSTV mode changes
 * the frame's aspect ratio: 320×256 to 640×496 to 320×128. A position in
 * percentages survives that change, a position in pixels or a corner preset
 * does not.
 *
 * [id] is stable across edits and is what makes the callsign stamp a *move*
 * rather than a duplicate: the stamp always reuses [CALLSIGN_OVERLAY_ID], so
 * re-tapping a position preset repositions the one stamp instead of piling up
 * six of them (see [TxComposition.withOverlayStamped]).
 *
 * @param sizeFraction text height as a fraction of the composed image height
 *   (see [OVERLAY_SIZE_SMALL]/[OVERLAY_SIZE_MEDIUM]/[OVERLAY_SIZE_LARGE]).
 */
data class TextOverlay(
    val id: String,
    val text: String,
    val xPercent: Float = 50f,
    val yPercent: Float = 50f,
    val colorArgb: Int = OVERLAY_COLOR_WHITE,
    val sizeFraction: Float = OVERLAY_SIZE_MEDIUM,
    val style: OverlayStyle = OverlayStyle.BAR,
)

/**
 * The id the callsign stamp always uses. One stamp per picture: the Callsign
 * tool's six position presets move it, they do not add another.
 */
const val CALLSIGN_OVERLAY_ID = "cs"

/** A freehand stroke drawn on the canvas, in frame percentages. */
data class DrawPath(
    val colorArgb: Int,
    /** Stroke width as a fraction of the frame's smaller dimension. */
    val widthFraction: Float,
    val points: List<PathPoint>,
)

/** One point of a [DrawPath], as a percentage of the frame. */
data class PathPoint(val xPercent: Float, val yPercent: Float)

/**
 * Brightness / contrast / saturation, each 50..150 where 100 is untouched.
 *
 * The same 50..150 range the design's sliders use, held as whole numbers rather
 * than multipliers so the UI's "+12" readout and the value are the same thing
 * and can't round differently.
 */
data class ImageAdjustments(
    val brightness: Int = ADJUSTMENT_NEUTRAL,
    val contrast: Int = ADJUSTMENT_NEUTRAL,
    val saturation: Int = ADJUSTMENT_NEUTRAL,
)

/** The untouched value for every [ImageAdjustments] channel. */
const val ADJUSTMENT_NEUTRAL = 100

/** Adjustment slider bounds, matching the design. */
const val ADJUSTMENT_MIN = 50
const val ADJUSTMENT_MAX = 150

/**
 * The border burned into the transmitted picture.
 *
 * Burned in, not drawn as UI chrome: the receiving station only ever sees the
 * pixels, so a frame that existed only on this screen would be a decoration
 * the operator alone could see.
 */
enum class ImageFrame {
    NONE,

    /** A thin white line — reads on almost any picture. */
    THIN,

    /** A thick dark band, the classic SSTV mat. */
    BOLD,

    /** An amber line, matching the app's signal colour. */
    AMBER,
}

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
    val paths: List<DrawPath> = emptyList(),
    val adjustments: ImageAdjustments = ImageAdjustments(),
    val frame: ImageFrame = ImageFrame.NONE,
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
                id = CALLSIGN_OVERLAY_ID,
                text = call,
                xPercent = 50f,
                yPercent = TOP_BAR_Y_PERCENT,
                colorArgb = OVERLAY_COLOR_WHITE,
                sizeFraction = OVERLAY_SIZE_LARGE,
                style = OverlayStyle.BAR,
            ),
            TextOverlay(
                id = "cq",
                text = cqBarText(call, grid),
                xPercent = 50f,
                yPercent = BOTTOM_BAR_Y_PERCENT,
                colorArgb = OVERLAY_COLOR_WHITE,
                sizeFraction = OVERLAY_SIZE_MEDIUM,
                style = OverlayStyle.BAR,
            ),
        )
    }
    return TxComposition(mode = mode, overlays = overlays)
}

/**
 * Y positions matching the design's bar placements: a top bar sits just inside
 * the top edge, a bottom bar just inside the bottom. Percentages, so they hold
 * when the mode's aspect ratio changes.
 */
const val TOP_BAR_Y_PERCENT = 8f
const val BOTTOM_BAR_Y_PERCENT = 92f

/** Position percentages are clamped to the frame; NaN degrades to centred. */
fun clampPercent(value: Float): Float =
    if (value.isNaN()) 50f else value.coerceIn(0f, 100f)

/**
 * Copy with [overlay] added, or - if an overlay with the same id already exists
 * - that one replaced in place.
 *
 * One function for both, because the distinction is not something a caller
 * should have to get right: the callsign stamp must move rather than
 * duplicate, and a text edit must update rather than append. Keying on the id
 * makes both fall out of the same call. A replaced overlay keeps its slot in
 * the list, so draw order (and therefore which overlay sits on top) does not
 * shuffle as the operator edits.
 */
fun TxComposition.withOverlayStamped(overlay: TextOverlay): TxComposition {
    val clamped = overlay.copy(
        xPercent = clampPercent(overlay.xPercent),
        yPercent = clampPercent(overlay.yPercent),
    )
    val existing = overlays.indexOfFirst { it.id == clamped.id }
    return if (existing < 0) {
        copy(overlays = overlays + clamped)
    } else {
        copy(overlays = overlays.mapIndexed { i, o -> if (i == existing) clamped else o })
    }
}

/** Copy with the overlay carrying [id] removed; an unknown id is a no-op. */
fun TxComposition.withOverlayRemoved(id: String): TxComposition =
    copy(overlays = overlays.filterNot { it.id == id })

/**
 * Copy with the overlay carrying [id] moved.
 *
 * A bar-style overlay is locked to the horizontal centre and only moves
 * vertically: a bar spans the full frame width, so dragging it sideways would
 * shift a band that is already edge-to-edge and look like nothing happened.
 * Outline overlays move freely.
 */
fun TxComposition.withOverlayMoved(id: String, xPercent: Float, yPercent: Float): TxComposition =
    copy(
        overlays = overlays.map { overlay ->
            if (overlay.id != id) {
                overlay
            } else {
                overlay.copy(
                    xPercent = if (overlay.style == OverlayStyle.BAR) 50f else clampPercent(xPercent),
                    yPercent = clampPercent(yPercent),
                )
            }
        },
    )

/** Copy with a patch applied to the overlay carrying [id]; unknown id is a no-op. */
fun TxComposition.withOverlayPatched(
    id: String,
    patch: (TextOverlay) -> TextOverlay,
): TxComposition = copy(
    overlays = overlays.map { if (it.id == id) patch(it) else it },
)

/**
 * The next free free-text overlay id.
 *
 * Counts up from the highest numeric suffix already present rather than from
 * the list size, so deleting a middle overlay and adding another can never
 * hand out an id that is already taken - which would make the new overlay
 * silently replace an existing one via [withOverlayStamped].
 */
fun TxComposition.nextOverlayId(): String {
    val highest = overlays.mapNotNull { it.id.removePrefix("t").toIntOrNull() }.maxOrNull() ?: 0
    return "t" + (highest + 1)
}

// Draw paths -----------------------------------------------------------------

/** Stroke widths offered by the Draw tool, as fractions of the frame's short side. */
const val STROKE_THIN = 0.006f
const val STROKE_MEDIUM = 0.012f
const val STROKE_THICK = 0.024f

/** The three stroke widths, thinnest first. */
val STROKE_WIDTHS: List<Float> = listOf(STROKE_THIN, STROKE_MEDIUM, STROKE_THICK)

/** Copy with a new stroke started at [point]. */
fun TxComposition.withStrokeStarted(
    colorArgb: Int,
    widthFraction: Float,
    point: PathPoint,
): TxComposition = copy(paths = paths + DrawPath(colorArgb, widthFraction, listOf(point)))

/**
 * Copy with [point] appended to the stroke in progress. A no-op when no stroke
 * has been started, so a stray move event cannot create a path with no origin.
 */
fun TxComposition.withStrokeExtended(point: PathPoint): TxComposition {
    if (paths.isEmpty()) return this
    val last = paths.last()
    return copy(paths = paths.dropLast(1) + last.copy(points = last.points + point))
}

/** Copy with the most recent stroke dropped; empty is a no-op. */
fun TxComposition.withStrokeUndone(): TxComposition =
    if (paths.isEmpty()) this else copy(paths = paths.dropLast(1))

/** Copy with every stroke dropped. */
fun TxComposition.withStrokesCleared(): TxComposition =
    if (paths.isEmpty()) this else copy(paths = emptyList())

// Adjustments / frame ---------------------------------------------------------

/** An adjustment value clamped to the slider's range. */
fun clampAdjustment(value: Int): Int = value.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX)

/** Copy with the adjustments replaced, each clamped. */
fun TxComposition.withAdjustments(adjustments: ImageAdjustments): TxComposition =
    copy(
        adjustments = ImageAdjustments(
            brightness = clampAdjustment(adjustments.brightness),
            contrast = clampAdjustment(adjustments.contrast),
            saturation = clampAdjustment(adjustments.saturation),
        ),
    )

/** Copy with every adjustment back to neutral. */
fun TxComposition.withAdjustmentsReset(): TxComposition =
    copy(adjustments = ImageAdjustments())

/** Whether every adjustment is untouched - drives the Reset control's relevance. */
fun ImageAdjustments.isNeutral(): Boolean =
    brightness == ADJUSTMENT_NEUTRAL &&
        contrast == ADJUSTMENT_NEUTRAL &&
        saturation == ADJUSTMENT_NEUTRAL

/** The signed offset shown next to an adjustment slider, e.g. "+12", "-8", "0". */
fun adjustmentOffsetLabel(value: Int): String {
    val offset = clampAdjustment(value) - ADJUSTMENT_NEUTRAL
    return if (offset > 0) "+" + offset else offset.toString()
}

/** Copy with the burned-in frame replaced. */
fun TxComposition.withFrame(frame: ImageFrame): TxComposition = copy(frame = frame)

/** Copy with zoom/pan clamped into their legal ranges. */
fun TxComposition.withClampedView(
    zoom: Float = this.zoom,
    panX: Float = this.panX,
    panY: Float = this.panY,
): TxComposition = copy(zoom = clampZoom(zoom), panX = clampPan(panX), panY = clampPan(panY))

/**
 * A persisted overlay size clamped into the range the UI can express.
 *
 * Taken as a Double because that is what the JSON reader returns, and because
 * NaN and the infinities have to be caught before the narrowing to Float hides
 * them. An unclamped value from a corrupt blob reached `TextPaint.textSize` and
 * threw, or produced a glyph larger than the frame; either way the edit list
 * failed to do the one thing it promises, which is to degrade safely.
 */
internal fun clampOverlaySize(raw: Double): Float {
    if (raw.isNaN() || raw.isInfinite()) return OVERLAY_SIZE_MEDIUM
    return raw.toFloat().coerceIn(OVERLAY_SIZE_SMALL, OVERLAY_SIZE_LARGE)
}

/**
 * A persisted stroke width clamped into the range the Draw tool offers.
 *
 * Same reasoning as [clampOverlaySize]: the width scales a paint stroke, so a
 * non-finite value poisons the whole flatten.
 */
internal fun clampStrokeWidth(raw: Double): Float {
    if (raw.isNaN() || raw.isInfinite()) return STROKE_MEDIUM
    return raw.toFloat().coerceIn(STROKE_THIN, STROKE_THICK)
}
