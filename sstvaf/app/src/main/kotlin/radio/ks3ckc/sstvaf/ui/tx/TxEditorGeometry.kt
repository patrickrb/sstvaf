package radio.ks3ckc.sstvaf.ui.tx

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure geometry for the composer canvas: converting touches to frame
 * percentages, hit-testing overlays, and sizing the selection outline.
 *
 * Separated from [TxEditorCanvas] because this is where dragging is either
 * right or wrong, and it is all testable without a pointer or a display.
 */

/**
 * A touch in canvas pixels as a percentage of the frame, clamped to it.
 *
 * Clamped because a drag that leaves the canvas should park the overlay at the
 * edge rather than fling it to a position the operator cannot reach to fix.
 */
internal fun toFramePercent(
    xPx: Float,
    yPx: Float,
    widthPx: Float,
    heightPx: Float,
): Pair<Float, Float> {
    if (widthPx <= 0f || heightPx <= 0f) return 50f to 50f
    val x = (xPx / widthPx * 100f).coerceIn(0f, 100f)
    val y = (yPx / heightPx * 100f).coerceIn(0f, 100f)
    return x to y
}

/**
 * The half-height of an overlay's touch target, in frame percent.
 *
 * Derived from the text size rather than fixed, so a Large overlay is easier to
 * grab than a Small one — which is what the operator expects, since it looks
 * bigger. The floor stops a Small overlay on a tall mode from having a target
 * only a couple of pixels high.
 */
internal fun overlayTouchHalfHeightPercent(sizeFraction: Float): Float =
    (sizeFraction * 100f * BAR_HEIGHT_FACTOR / 2f).coerceAtLeast(MIN_TOUCH_HALF_PERCENT)

/** Minimum half-height of an overlay touch target, in frame percent. */
internal const val MIN_TOUCH_HALF_PERCENT = 6f

/**
 * Half-width of an outline overlay's touch target, in frame percent, estimated
 * from its character count.
 *
 * An estimate on purpose: the exact width needs a laid-out Paint, which is not
 * available (or desirable) in a gesture handler on every pointer move. Being
 * slightly generous is the right failure — a target a little wider than the
 * glyphs is forgiving, one narrower than them feels broken.
 */
internal fun overlayTouchHalfWidthPercent(overlay: TextOverlay): Float {
    if (overlay.style == OverlayStyle.BAR) return 50f
    val approxCharWidth = overlay.sizeFraction * 100f * 0.6f
    val half = overlay.text.trim().length * approxCharWidth / 2f
    return half.coerceIn(MIN_TOUCH_HALF_PERCENT, 50f)
}

/**
 * The overlay under a touch, or null.
 *
 * Searched back-to-front so the topmost overlay wins, matching what the
 * operator sees: later overlays are drawn over earlier ones, so a tap where two
 * overlap should grab the one on top.
 *
 * A BAR overlay spans the full frame width, so only its vertical band is
 * tested — tapping anywhere along the band grabs it, which is the whole target
 * an operator perceives.
 */
internal fun overlayHitTest(
    overlays: List<TextOverlay>,
    xPercent: Float,
    yPercent: Float,
): TextOverlay? = overlays.lastOrNull { overlay ->
    val halfHeight = overlayTouchHalfHeightPercent(overlay.sizeFraction)
    val halfWidth = overlayTouchHalfWidthPercent(overlay)
    abs(yPercent - overlay.yPercent) <= halfHeight &&
        abs(xPercent - overlay.xPercent) <= halfWidth
}

/** A rectangle in canvas pixels. */
internal data class CanvasBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * The selection outline's box in canvas pixels.
 *
 * Matches the touch target rather than the glyphs, so what the operator can see
 * they have selected is also what they can grab. A mismatch here is the kind of
 * thing that makes a UI feel imprecise without anyone being able to say why.
 */
internal fun overlaySelectionBox(
    overlay: TextOverlay,
    widthPx: Float,
    heightPx: Float,
): CanvasBox {
    val halfHeight = overlayTouchHalfHeightPercent(overlay.sizeFraction) / 100f * heightPx
    val halfWidth = overlayTouchHalfWidthPercent(overlay) / 100f * widthPx
    val centreX = clampPercent(overlay.xPercent) / 100f * widthPx
    val centreY = clampPercent(overlay.yPercent) / 100f * heightPx
    val left = (centreX - halfWidth).coerceAtLeast(0f)
    val top = (centreY - halfHeight).coerceAtLeast(0f)
    val right = (centreX + halfWidth).coerceAtMost(widthPx)
    val bottom = (centreY + halfHeight).coerceAtMost(heightPx)
    return CanvasBox(
        left = left,
        top = top,
        width = (right - left).coerceAtLeast(0f),
        height = (bottom - top).coerceAtLeast(0f),
    )
}

/**
 * The inset's box in frame percent: left, top, width, height.
 *
 * The one place the inset's on-canvas rectangle is worked out for the editor,
 * shared by hit-testing and the selection outline so the two cannot drift.
 */
internal fun insetBoxPercent(inset: ImageInset, frameAspect: Float): CanvasBox {
    val (w, h) = insetSizePercent(inset.widthFraction, inset.aspect, frameAspect)
    val centreX = clampInsetCentre(inset.xPercent, w)
    val centreY = clampInsetCentre(inset.yPercent, h)
    return CanvasBox(left = centreX - w / 2f, top = centreY - h / 2f, width = w, height = h)
}

/**
 * Whether a touch that lands on the inset grabs it, given the open tool.
 *
 * Not in Crop or Draw. A text overlay wins under any tool because it is a line
 * high and hard to hit by accident; the inset can be two fifths of the frame,
 * and letting it take every touch that starts on it would leave Crop unable to
 * pan or pinch from that part of the picture and Draw unable to start a stroke
 * there. Those two tools own the whole canvas; every other tool has no use for
 * a touch on the picture, so there the inset can have it.
 */
internal fun insetTakesTouch(tool: TxTool, touchIsOnInset: Boolean): Boolean =
    touchIsOnInset && tool != TxTool.CROP && tool != TxTool.DRAW

/** Whether a touch (in frame percent) lands on the inset. */
internal fun insetHitTest(
    inset: ImageInset?,
    frameAspect: Float,
    xPercent: Float,
    yPercent: Float,
): Boolean {
    if (inset == null) return false
    val box = insetBoxPercent(inset, frameAspect)
    return xPercent >= box.left && xPercent <= box.left + box.width &&
        yPercent >= box.top && yPercent <= box.top + box.height
}

/** The inset's selection outline in canvas pixels. */
internal fun insetSelectionBox(
    inset: ImageInset,
    frameAspect: Float,
    widthPx: Float,
    heightPx: Float,
): CanvasBox {
    val box = insetBoxPercent(inset, frameAspect)
    return CanvasBox(
        left = box.left / 100f * widthPx,
        top = box.top / 100f * heightPx,
        width = box.width / 100f * widthPx,
        height = box.height / 100f * heightPx,
    )
}

/**
 * Where the inset's centre goes for a finger at ([xPercent], [yPercent]),
 * given where on the inset the drag started.
 *
 * A text overlay snaps its centre to the finger, which is fine for something a
 * line high. The inset is a third of the frame: snapping would make it jump
 * the moment it was touched anywhere but dead centre, so the grab offset is
 * carried through the drag instead.
 */
internal fun insetDragCentre(
    xPercent: Float,
    yPercent: Float,
    grabOffsetX: Float,
    grabOffsetY: Float,
): Pair<Float, Float> = (xPercent - grabOffsetX) to (yPercent - grabOffsetY)

/**
 * One drag step applied to the crop pan.
 *
 * The picture follows the finger, so a drag right reveals more of the left of
 * the photo — which means the pan value moves the opposite way to the finger.
 * The step is divided by the zoom overflow: at 1x there is nothing to pan, and
 * the further in the operator is zoomed, the less a given finger movement
 * should shift the crop, or panning becomes uncontrollable at high zoom.
 *
 * @param dxFraction finger movement as a fraction of the canvas width.
 */
internal fun panStep(
    currentPan: Float,
    dxFraction: Float,
    zoom: Float,
): Float {
    val z = clampZoom(zoom)
    // At 1x the crop fills the frame: there is no overflow to pan across.
    if (z <= TX_MIN_ZOOM) return clampPan(currentPan)
    // Full travel (-1..1) spans the overflow, which grows with zoom.
    val travelPerFraction = 2f * z / (z - TX_MIN_ZOOM) / PAN_DAMPING
    return clampPan(currentPan - dxFraction * travelPerFraction)
}

/**
 * Damping on the pan gesture. Without it a 1.1x zoom — where the overflow is a
 * sliver — sends the crop from edge to edge on a few pixels of movement.
 */
internal const val PAN_DAMPING = 6f

/**
 * The distance between two pinch pointers, in pixels.
 *
 * The ratio of one frame's span to the previous frame's is the zoom step. Pulled
 * out of the gesture loop so the degenerate cases have somewhere to be tested:
 * two fingers landing on the same pixel gives a zero span, and dividing by it
 * would send the zoom to infinity or NaN and take the crop with it.
 */
internal fun pinchSpan(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x2 - x1
    val dy = y2 - y1
    return sqrt(dx * dx + dy * dy)
}
