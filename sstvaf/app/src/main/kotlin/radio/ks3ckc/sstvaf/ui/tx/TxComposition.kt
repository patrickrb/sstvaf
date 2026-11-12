package radio.ks3ckc.sstvaf.ui.tx

import radio.ks3ckc.sstvaf.sstv.SstvMode
import radio.ks3ckc.sstvaf.sstv.digital.DigitalSstvMode
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
    /**
     * Non-null sends this composition as a digital-SSTV (COFDM) frame in the
     * chosen robustness mode instead of the analog scanline mode. [mode]
     * still defines the canvas the operator composes on — the digital
     * payload embeds its own dimensions.
     */
    val digitalMode: DigitalSstvMode? = null,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val overlays: List<TextOverlay> = emptyList(),
    val paths: List<DrawPath> = emptyList(),
    val adjustments: ImageAdjustments = ImageAdjustments(),
    val frame: ImageFrame = ImageFrame.NONE,
    /** A received picture pinned over this one, or null for none. */
    val inset: ImageInset? = null,
)

/** The frame's width over its height, which is what inset geometry scales by. */
fun TxComposition.frameAspect(): Float = mode.width.toFloat() / mode.height

// Inset -----------------------------------------------------------------------

/**
 * A received picture pinned to the operator's own, the SSTV "reply" convention:
 * the other station sees what reached this one, on the air, inside the answer.
 *
 * Positioned by its centre in frame percentages, like a text overlay, and for
 * the same reason - the frame's shape changes with the SSTV mode and a
 * percentage survives that.
 *
 * The inset keeps the *received picture's* shape rather than the frame's. Its
 * width is a fraction of the frame; its height follows from [aspect]. Cropping
 * it to the frame's shape would cut the edges off the very picture it exists to
 * show, and those edges are where the other station's callsign bars are.
 *
 * @param sourceUri where the received PNG lives (a `file:` URI into the
 *   gallery's own storage), so a reopened composition can load it again.
 * @param aspect the received picture's width over its height.
 * @param widthFraction inset width as a fraction of the frame width (see
 *   [INSET_SIZE_SMALL]/[INSET_SIZE_MEDIUM]/[INSET_SIZE_LARGE]).
 * @param caption a one-line description of the received picture ("Scottie 1 ·
 *   14:32 UTC · 14.230 MHz"), shown wherever the editor names the inset.
 */
data class ImageInset(
    val sourceUri: String,
    val aspect: Float,
    val xPercent: Float,
    val yPercent: Float,
    val widthFraction: Float = INSET_SIZE_MEDIUM,
    val caption: String = "",
)

/** The four places the Inset tool's presets pin the picture. */
enum class InsetCorner(val left: Boolean, val top: Boolean) {
    TOP_LEFT(left = true, top = true),
    TOP_RIGHT(left = false, top = true),
    BOTTOM_LEFT(left = true, top = false),
    BOTTOM_RIGHT(left = false, top = false),
}

/** S / M / L inset widths, as fractions of the frame width. */
const val INSET_SIZE_SMALL = 0.25f
const val INSET_SIZE_MEDIUM = 0.33f
const val INSET_SIZE_LARGE = 0.40f

/** The three inset widths, smallest first. */
val INSET_SIZES: List<Float> = listOf(INSET_SIZE_SMALL, INSET_SIZE_MEDIUM, INSET_SIZE_LARGE)

/** Gap between a corner-pinned inset and the frame's side edge, in percent. */
const val INSET_SIDE_MARGIN_PERCENT = 3f

/**
 * The vertical band a corner preset keeps the inset inside, in frame percent.
 *
 * Not the frame's edges: the conventional callsign bar sits across the top and
 * the CQ bar across the bottom (see [TOP_BAR_Y_PERCENT]/[BOTTOM_BAR_Y_PERCENT]),
 * and a preset that parked the received picture on top of the operator's own
 * callsign would be hiding the one thing the transmission must carry. Dragging
 * is unrestricted - the operator can put it over a bar on purpose.
 */
const val INSET_SAFE_TOP_PERCENT = 18f
const val INSET_SAFE_BOTTOM_PERCENT = 84f

/** Aspect bounds for an inset; outside them the picture is a sliver nobody can read. */
const val INSET_MIN_ASPECT = 0.25f
const val INSET_MAX_ASPECT = 4f

/** Where a new inset lands: bottom right, clear of both bars. */
val DEFAULT_INSET_CORNER = InsetCorner.BOTTOM_RIGHT

/**
 * An inset aspect made safe to divide by.
 *
 * A stored row with a zero or negative dimension (or a corrupt edit list) must
 * not turn into an infinite or NaN height that poisons the whole flatten; it
 * degrades to SSTV's classic 4:3 instead.
 */
fun clampInsetAspect(aspect: Float): Float =
    if (aspect.isNaN() || aspect.isInfinite() || aspect <= 0f) {
        4f / 3f
    } else {
        aspect.coerceIn(INSET_MIN_ASPECT, INSET_MAX_ASPECT)
    }

/** An inset width clamped into the range the tool offers; NaN degrades to medium. */
fun clampInsetSize(fraction: Float): Float =
    if (fraction.isNaN() || fraction.isInfinite()) {
        INSET_SIZE_MEDIUM
    } else {
        fraction.coerceIn(INSET_SIZE_SMALL, INSET_SIZE_LARGE)
    }

/** The same, for a value read back from JSON (see [clampOverlaySize]). */
internal fun clampInsetSize(raw: Double): Float =
    if (raw.isNaN() || raw.isInfinite()) INSET_SIZE_MEDIUM else clampInsetSize(raw.toFloat())

/** Inset width in frame percent. */
fun insetWidthPercent(widthFraction: Float): Float = clampInsetSize(widthFraction) * 100f

/**
 * Inset height in frame percent.
 *
 * Width and height percentages are of *different* lengths - the frame's width
 * and its height - so converting one to the other needs the frame's own shape
 * as well as the picture's. Capped at the full frame height: a tall received
 * picture on a wide frame (a 320x256 picture on Martin 4's 320x128) would
 * otherwise be taller than the picture it is pinned to.
 */
fun insetHeightPercent(widthFraction: Float, insetAspect: Float, frameAspect: Float): Float {
    val frame = if (frameAspect.isNaN() || frameAspect <= 0f) 4f / 3f else frameAspect
    return (insetWidthPercent(widthFraction) * frame / clampInsetAspect(insetAspect))
        .coerceAtMost(100f)
}

/**
 * An inset centre clamped so the whole inset stays inside the frame.
 *
 * Clamped by half the inset's size rather than to 0..100 like a text overlay:
 * a centre on the frame's edge would leave half the received picture off the
 * bitmap, where it is silently not transmitted.
 */
fun clampInsetCentre(centrePercent: Float, sizePercent: Float): Float {
    val half = sizePercent.coerceIn(0f, 100f) / 2f
    return if (centrePercent.isNaN()) 50f else centrePercent.coerceIn(half, 100f - half)
}

/**
 * The centre a corner preset gives an inset of this size and shape.
 *
 * Horizontally it sits [INSET_SIDE_MARGIN_PERCENT] in from the side; vertically
 * it butts against the edge of the safe band. An inset taller than the band is
 * centred on it, which is the least-bad place for something that cannot fit.
 */
fun insetCornerCentre(
    corner: InsetCorner,
    widthFraction: Float,
    insetAspect: Float,
    frameAspect: Float,
): Pair<Float, Float> {
    val w = insetWidthPercent(widthFraction)
    val h = insetHeightPercent(widthFraction, insetAspect, frameAspect)
    val x = if (corner.left) INSET_SIDE_MARGIN_PERCENT + w / 2f else 100f - INSET_SIDE_MARGIN_PERCENT - w / 2f
    val band = INSET_SAFE_BOTTOM_PERCENT - INSET_SAFE_TOP_PERCENT
    val y = when {
        h >= band -> (INSET_SAFE_TOP_PERCENT + INSET_SAFE_BOTTOM_PERCENT) / 2f
        corner.top -> INSET_SAFE_TOP_PERCENT + h / 2f
        else -> INSET_SAFE_BOTTOM_PERCENT - h / 2f
    }
    return clampInsetCentre(x, w) to clampInsetCentre(y, h)
}

/**
 * The corner preset an inset currently sits on, or null once it has been
 * dragged somewhere of its own. Same tolerance, and same reasoning, as
 * [matchingCallsignPreset].
 */
fun matchingInsetCorner(inset: ImageInset, frameAspect: Float): InsetCorner? =
    InsetCorner.entries.firstOrNull { corner ->
        val (x, y) = insetCornerCentre(corner, inset.widthFraction, inset.aspect, frameAspect)
        kotlin.math.abs(x - inset.xPercent) < PRESET_MATCH_TOLERANCE &&
            kotlin.math.abs(y - inset.yPercent) < PRESET_MATCH_TOLERANCE
    }

/**
 * A new inset for a received picture of [aspect], pinned to
 * [DEFAULT_INSET_CORNER] of a frame shaped [frameAspect].
 */
fun newImageInset(
    sourceUri: String,
    aspect: Float,
    caption: String,
    frameAspect: Float,
    widthFraction: Float = INSET_SIZE_MEDIUM,
): ImageInset {
    val safeAspect = clampInsetAspect(aspect)
    val size = clampInsetSize(widthFraction)
    val (x, y) = insetCornerCentre(DEFAULT_INSET_CORNER, size, safeAspect, frameAspect)
    return ImageInset(
        sourceUri = sourceUri,
        aspect = safeAspect,
        xPercent = x,
        yPercent = y,
        widthFraction = size,
        caption = caption,
    )
}

/** Copy with [inset] pinned, replacing any inset already there: one per picture. */
fun TxComposition.withInset(inset: ImageInset): TxComposition = copy(inset = inset)

/** Copy with the inset dropped; no inset is a no-op. */
fun TxComposition.withInsetRemoved(): TxComposition =
    if (inset == null) this else copy(inset = null)

/** Copy with the inset's centre moved, kept wholly inside the frame. */
fun TxComposition.withInsetMoved(xPercent: Float, yPercent: Float): TxComposition {
    val current = inset ?: return this
    val w = insetWidthPercent(current.widthFraction)
    val h = insetHeightPercent(current.widthFraction, current.aspect, frameAspect())
    return copy(
        inset = current.copy(
            xPercent = clampInsetCentre(xPercent, w),
            yPercent = clampInsetCentre(yPercent, h),
        ),
    )
}

/** Copy with the inset pinned to [corner] at its current size. */
fun TxComposition.withInsetCorner(corner: InsetCorner): TxComposition {
    val current = inset ?: return this
    val (x, y) = insetCornerCentre(corner, current.widthFraction, current.aspect, frameAspect())
    return copy(inset = current.copy(xPercent = x, yPercent = y))
}

/**
 * Copy with the inset resized.
 *
 * An inset sitting on a corner preset stays pinned to that corner, so it grows
 * away from the edge instead of off it or over a bar. One the operator dragged
 * somewhere of their own keeps its centre and is only nudged as far as it takes
 * to stay inside the frame.
 */
fun TxComposition.withInsetSized(widthFraction: Float): TxComposition {
    val current = inset ?: return this
    val size = clampInsetSize(widthFraction)
    val corner = matchingInsetCorner(current, frameAspect())
    val resized = copy(inset = current.copy(widthFraction = size))
    return if (corner != null) {
        resized.withInsetCorner(corner)
    } else {
        resized.withInsetMoved(current.xPercent, current.yPercent)
    }
}

/**
 * Copy with the SSTV mode changed and the inset re-fitted to the new frame.
 *
 * Text overlays survive a mode change untouched because they have no height to
 * speak of. The inset does: its height in percent depends on the frame's
 * shape, so the same centre can leave it hanging off a wider frame, and a
 * corner-pinned one would drift away from its corner.
 */
fun TxComposition.withModeChanged(newMode: SstvMode): TxComposition {
    val current = inset ?: return copy(mode = newMode)
    val corner = matchingInsetCorner(current, frameAspect())
    val changed = copy(mode = newMode)
    return if (corner != null) {
        changed.withInsetCorner(corner)
    } else {
        changed.withInsetMoved(current.xPercent, current.yPercent)
    }
}

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
