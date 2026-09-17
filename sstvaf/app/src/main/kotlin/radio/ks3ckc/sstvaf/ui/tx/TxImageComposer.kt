package radio.ks3ckc.sstvaf.ui.tx

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import androidx.exifinterface.media.ExifInterface
import kotlin.math.roundToInt

/**
 * Pure (non-Compose) rendering for the TX composer: crop geometry, the
 * ARGB_8888 composite at the SSTV mode's exact resolution, and source-photo
 * decoding. Top-level internal functions per the project testing policy —
 * everything here is exercised by [TxImageComposerTest].
 */

// ---------------------------------------------------------------------------
// Crop geometry
// ---------------------------------------------------------------------------

/**
 * The source rectangle to draw into a `dstW x dstH` frame: aspect-fill
 * ("cover") of the source, shrunk by [zoom] (1..4), shifted by [panX]/[panY]
 * in fraction-of-overflow units (-1 = flush left/top, 0 = centered, +1 =
 * flush right/bottom). Always fully inside the source bounds; degenerate
 * dimensions yield an empty rect.
 */
internal fun computeCoverCrop(
    srcW: Int,
    srcH: Int,
    dstW: Int,
    dstH: Int,
    zoom: Float,
    panX: Float,
    panY: Float,
): Rect {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return Rect(0, 0, 0, 0)

    val z = clampZoom(zoom)
    val dstAspect = dstW.toFloat() / dstH
    val srcAspect = srcW.toFloat() / srcH

    // Aspect-fill base crop: the largest dst-shaped rect inside the source.
    var cropW: Float
    var cropH: Float
    if (srcAspect > dstAspect) {
        cropH = srcH.toFloat()
        cropW = cropH * dstAspect
    } else {
        cropW = srcW.toFloat()
        cropH = cropW / dstAspect
    }

    // Zoom shrinks the visible window (shows less source = zooms in).
    cropW /= z
    cropH /= z

    val w = cropW.roundToInt().coerceIn(1, srcW)
    val h = cropH.roundToInt().coerceIn(1, srcH)

    // Pan across the leftover overflow; 0 centers, ±1 hits the edge.
    val maxLeft = srcW - w
    val maxTop = srcH - h
    val left = ((maxLeft / 2f) * (1f + clampPan(panX))).roundToInt().coerceIn(0, maxLeft)
    val top = ((maxTop / 2f) * (1f + clampPan(panY))).roundToInt().coerceIn(0, maxTop)

    return Rect(left, top, left + w, top + h)
}

// ---------------------------------------------------------------------------
// Composite rendering
// ---------------------------------------------------------------------------

/** BAR band height as a multiple of the overlay text size. */
internal const val BAR_HEIGHT_FACTOR = 1.6f

/** BAR band fill: semi-opaque black (the classic SSTV callsign band). */
internal const val BAR_BAND_COLOR = 0xB3000000.toInt() // 70 % black

/**
 * Render [comp] over [src] into a new ARGB_8888 bitmap of exactly
 * `dstW x dstH` - the SSTV mode's native pixel dimensions.
 *
 * Everything the operator did is flattened here, in the order the editor
 * stacks it: the cover-cropped photo with its adjustments baked in, then the
 * freehand strokes, then the text overlays, then the burned-in frame. This is
 * the *only* image the transmitter ever sees, so anything not flattened here
 * simply does not go on the air - the receiving station gets pixels, not a
 * layer list.
 *
 * Adjustments apply to the photo alone. A brightness change that also washed
 * out the callsign bar would be fighting the operator: they adjusted the
 * picture, not the text they put on top of it.
 */
internal fun renderComposite(src: Bitmap, comp: TxComposition, dstW: Int, dstH: Int): Bitmap {
    require(dstW > 0 && dstH > 0) { "bad composite size " + dstW + "x" + dstH }
    val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)

    val crop = computeCoverCrop(src.width, src.height, dstW, dstH, comp.zoom, comp.panX, comp.panY)
    if (!crop.isEmpty) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // The colour filter rides on the photo's own draw call, so strokes and
        // text drawn afterwards are untouched by it.
        if (!comp.adjustments.isNeutral()) {
            paint.colorFilter = ColorMatrixColorFilter(buildAdjustmentMatrix(comp.adjustments))
        }
        canvas.drawBitmap(src, crop, Rect(0, 0, dstW, dstH), paint)
    }

    for (path in comp.paths) {
        drawStroke(canvas, path, dstW, dstH)
    }
    for (overlay in comp.overlays) {
        drawOverlay(canvas, overlay, dstW, dstH)
    }
    drawFrame(canvas, comp.frame, dstW, dstH)
    return out
}

// ---------------------------------------------------------------------------
// Adjustments
// ---------------------------------------------------------------------------

/**
 * The colour matrix for [adjustments], composed saturation then contrast then
 * brightness.
 *
 * Matches what the editor's live preview shows, which in the design is a CSS
 * `filter: brightness() contrast() saturate()` chain - so the same order, and
 * the same definitions: brightness and saturation scale, contrast scales about
 * mid-grey. Getting this wrong would mean the transmitted picture did not match
 * the one the operator approved in the confirm sheet.
 */
internal fun buildAdjustmentMatrix(adjustments: ImageAdjustments): ColorMatrix {
    val brightness = clampAdjustment(adjustments.brightness) / 100f
    val contrast = clampAdjustment(adjustments.contrast) / 100f
    val saturation = clampAdjustment(adjustments.saturation) / 100f

    val matrix = ColorMatrix().apply { setSaturation(saturation) }

    // Contrast pivots around mid-grey: out = in * c + 128 * (1 - c). The fifth
    // column is a 0..255 translate, which is why the constant is 128 and not 0.5.
    val shift = 128f * (1f - contrast)
    matrix.postConcat(
        ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, shift,
                0f, contrast, 0f, 0f, shift,
                0f, 0f, contrast, 0f, shift,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    matrix.postConcat(
        ColorMatrix(
            floatArrayOf(
                brightness, 0f, 0f, 0f, 0f,
                0f, brightness, 0f, 0f, 0f,
                0f, 0f, brightness, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    return matrix
}

// ---------------------------------------------------------------------------
// Freehand strokes
// ---------------------------------------------------------------------------

/**
 * Stroke width in pixels for a width fraction, measured against the frame's
 * *shorter* side.
 *
 * The short side, so a stroke keeps its apparent weight across modes of wildly
 * different shapes - Martin 4 is 320x128, PD 290 is 800x616. Scaling off the
 * width would make every stroke look hairline on a tall frame.
 */
internal fun strokeWidthPx(widthFraction: Float, dstW: Int, dstH: Int): Float =
    (widthFraction * minOf(dstW, dstH)).coerceAtLeast(1f)

/** Draw one freehand stroke, converting its percentage points to pixels. */
private fun drawStroke(canvas: Canvas, stroke: DrawPath, dstW: Int, dstH: Int) {
    if (stroke.points.isEmpty()) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke.colorArgb
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx(stroke.widthFraction, dstW, dstH)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // A single tap is a dot, not a zero-length line: Path.lineTo to the same
    // point draws nothing, so a tapped stroke would silently vanish between
    // the preview and the transmitted picture.
    if (stroke.points.size == 1) {
        val only = stroke.points.first()
        canvas.drawPoint(
            only.xPercent / 100f * dstW,
            only.yPercent / 100f * dstH,
            paint.apply { style = Paint.Style.FILL },
        )
        return
    }

    val path = Path()
    stroke.points.forEachIndexed { index, point ->
        val x = point.xPercent / 100f * dstW
        val y = point.yPercent / 100f * dstH
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    canvas.drawPath(path, paint)
}

// ---------------------------------------------------------------------------
// Burned-in frame
// ---------------------------------------------------------------------------

/** Frame inset widths, as fractions of the frame's shorter side. */
internal fun frameWidthFraction(frame: ImageFrame): Float = when (frame) {
    ImageFrame.NONE -> 0f
    ImageFrame.THIN -> 0.012f
    ImageFrame.BOLD -> 0.032f
    ImageFrame.AMBER -> 0.016f
}

/** Frame colours. Bold uses the app background, which reads as a dark mat. */
internal fun frameColorArgb(frame: ImageFrame): Int = when (frame) {
    ImageFrame.NONE -> 0
    ImageFrame.THIN -> OVERLAY_COLOR_WHITE
    ImageFrame.BOLD -> 0xFF07090F.toInt()
    ImageFrame.AMBER -> OVERLAY_COLOR_AMBER
}

/**
 * Draw the frame as an inset border - four filled edges rather than a stroked
 * rect, so its width is entirely inside the picture. A stroked rectangle
 * centres the line on its path and would spill half the frame off the edge of
 * the bitmap, leaving a frame thinner on the outside than the operator chose.
 */
private fun drawFrame(canvas: Canvas, frame: ImageFrame, dstW: Int, dstH: Int) {
    if (frame == ImageFrame.NONE) return
    val inset = (frameWidthFraction(frame) * minOf(dstW, dstH)).coerceAtLeast(1f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = frameColorArgb(frame) }
    canvas.drawRect(0f, 0f, dstW.toFloat(), inset, paint)
    canvas.drawRect(0f, dstH - inset, dstW.toFloat(), dstH.toFloat(), paint)
    canvas.drawRect(0f, inset, inset, dstH - inset, paint)
    canvas.drawRect(dstW - inset, inset, dstW.toFloat(), dstH - inset, paint)
}

// ---------------------------------------------------------------------------
// Text overlays
// ---------------------------------------------------------------------------

/**
 * The full-width band rect behind a BAR overlay centred at [yPercent].
 *
 * The band spans the frame and is centred on the overlay, so dragging a bar up
 * and down moves the band with it. Clamped to the frame so a bar dragged to the
 * very edge still shows its full height rather than half of one.
 */
internal fun barBandRect(yPercent: Float, sizeFraction: Float, dstW: Int, dstH: Int): Rect {
    val bandH = (sizeFraction * dstH * BAR_HEIGHT_FACTOR).roundToInt().coerceIn(1, dstH)
    val centerY = clampPercent(yPercent) / 100f * dstH
    var top = (centerY - bandH / 2f).roundToInt()
    top = top.coerceIn(0, dstH - bandH)
    return Rect(0, top, dstW, top + bandH)
}

/**
 * The halo color for an OUTLINE overlay: black behind light text, white
 * behind dark text (relative luminance threshold at 0.5).
 */
internal fun contrastColorFor(colorArgb: Int): Int {
    val r = (colorArgb ushr 16 and 0xFF) / 255f
    val g = (colorArgb ushr 8 and 0xFF) / 255f
    val b = (colorArgb and 0xFF) / 255f
    val luminance = 0.299f * r + 0.587f * g + 0.114f * b
    return if (luminance >= 0.5f) OVERLAY_COLOR_BLACK else OVERLAY_COLOR_WHITE
}

/** The 4 diagonal halo offsets for an OUTLINE overlay at [textSizePx]. */
internal fun outlineOffsets(textSizePx: Float): List<Pair<Float, Float>> {
    val d = (textSizePx / 12f).coerceAtLeast(1f)
    return listOf(-d to -d, d to -d, -d to d, d to d)
}

/**
 * The text baseline for an overlay whose centre is at [centerYPx], given the
 * font metrics. Extracted because getting this wrong shifts every overlay by
 * half a line height, which is invisible in a 320-pixel preview and obvious in
 * the received picture.
 */
internal fun overlayBaseline(centerYPx: Float, ascent: Float, descent: Float): Float =
    centerYPx - (ascent + descent) / 2f

/**
 * The left edge for a line of text of [lineWidth] centred at [xPercent],
 * clamped so the text never runs off either edge of the frame.
 *
 * Clamped rather than allowed to overflow: an overlay dragged near the edge
 * should butt against it, not have its first or last characters cut off by the
 * bitmap boundary where the operator cannot see that it happened.
 */
internal fun overlayLeft(xPercent: Float, lineWidth: Float, dstW: Int, padPx: Float): Float {
    val centre = clampPercent(xPercent) / 100f * dstW
    val ideal = centre - lineWidth / 2f
    val maxLeft = dstW - padPx - lineWidth
    return if (maxLeft < padPx) {
        // Text wider than the frame even after ellipsizing: pin it left.
        padPx
    } else {
        ideal.coerceIn(padPx, maxLeft)
    }
}

/** Draw one overlay (band/backing + halo + text) onto [canvas]. */
private fun drawOverlay(canvas: Canvas, overlay: TextOverlay, dstW: Int, dstH: Int) {
    val text = overlay.text.trim()
    if (text.isEmpty()) return

    val textSize = overlay.sizeFraction * dstH
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        this.textSize = textSize
        color = overlay.colorArgb
    }
    val pad = textSize * 0.4f
    val availableWidth = dstW - 2f * pad
    if (availableWidth <= 0f) return
    val line = TextUtils.ellipsize(text, textPaint, availableWidth, TextUtils.TruncateAt.END).toString()
    if (line.isEmpty()) return
    val lineWidth = textPaint.measureText(line)

    val fm = textPaint.fontMetrics
    val isBar = overlay.style == OverlayStyle.BAR
    val band = if (isBar) barBandRect(overlay.yPercent, overlay.sizeFraction, dstW, dstH) else null

    // A bar is centred in its own band; an outline is centred on its position.
    val centerY = band?.exactCenterY() ?: (clampPercent(overlay.yPercent) / 100f * dstH)
    val baseline = overlayBaseline(centerY, fm.ascent, fm.descent)

    // A bar spans the frame, so its text is always centred horizontally.
    val x = if (isBar) {
        (dstW - lineWidth) / 2f
    } else {
        overlayLeft(overlay.xPercent, lineWidth, dstW, pad)
    }

    if (isBar) {
        canvas.drawRect(band!!, Paint().apply { color = BAR_BAND_COLOR })
    } else {
        // OUTLINE: contrasting halo - the text drawn 4x diagonally offset.
        val haloPaint = TextPaint(textPaint).apply { color = contrastColorFor(overlay.colorArgb) }
        for ((dx, dy) in outlineOffsets(textSize)) {
            canvas.drawText(line, x + dx, baseline + dy, haloPaint)
        }
    }

    canvas.drawText(line, x, baseline, textPaint)
}

// ---------------------------------------------------------------------------
// Source decoding
// ---------------------------------------------------------------------------

/**
 * Extra decode resolution beyond the mode frame so a 4x zoom crop still has
 * source pixels to work with.
 */
internal const val DECODE_OVERSAMPLE = 4

/**
 * The BitmapFactory power-of-two `inSampleSize` that keeps the decoded image
 * at or above `reqW x reqH` (standard Android downsampling math; photos are
 * huge, the SSTV frame is tiny).
 */
internal fun computeInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
    if (srcW <= 0 || srcH <= 0 || reqW <= 0 || reqH <= 0) return 1
    var sampleSize = 1
    while (srcW / (sampleSize * 2) >= reqW && srcH / (sampleSize * 2) >= reqH) {
        sampleSize *= 2
    }
    return sampleSize
}

/** EXIF orientation tag → clockwise rotation degrees (flips treated as their rotation). */
internal fun exifRotationDegrees(exifOrientation: Int): Int = when (exifOrientation) {
    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
    ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
    ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
    else -> 0
}

/**
 * Decode the picked photo at a size suited to compositing into a
 * `targetW x targetH` SSTV frame (downsampled to ~[DECODE_OVERSAMPLE]x the
 * frame so 4x zoom crops stay sharp), with EXIF rotation applied. Returns
 * null when the uri can't be opened or decoded.
 */
internal fun loadSourceBitmap(
    contentResolver: ContentResolver,
    uri: Uri,
    targetW: Int,
    targetH: Int,
): Bitmap? {
    return try {
        // Pass 1: bounds only. NOTE: decodeStream returns null BY DESIGN under
        // inJustDecodeBounds, so null-check the stream — not the use{} result.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri) ?: return null
        boundsStream.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Pass 2: downsampled decode.
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(
                bounds.outWidth, bounds.outHeight,
                targetW * DECODE_OVERSAMPLE, targetH * DECODE_OVERSAMPLE,
            )
        }
        val decoded = contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null

        // EXIF rotation (portrait phone photos are usually stored rotated).
        val rotation = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                exifRotationDegrees(
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    ),
                )
            } ?: 0
        } catch (t: Throwable) {
            0 // EXIF parse failure: keep the unrotated image.
        }
        if (rotation == 0) return decoded

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated != decoded) decoded.recycle()
        rotated
    } catch (t: Throwable) {
        null
    }
}

// ---------------------------------------------------------------------------
// Text-only cards
// ---------------------------------------------------------------------------

/**
 * A gradient bitmap to stand in for a photo on the CQ / grid cards, at the
 * mode's native size.
 *
 * Generated at mode size rather than at some canvas size and scaled: the whole
 * point of the flattening pipeline is that the operator's picture is composed
 * at the exact resolution the codec will encode, and a card is no different.
 */
internal fun buildCardBitmap(kind: TxCardKind, dstW: Int, dstH: Int): Bitmap {
    require(dstW > 0 && dstH > 0) { "bad card size " + dstW + "x" + dstH }
    val (from, to) = cardGradientArgb(kind)
    val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, dstW.toFloat(), dstH.toFloat(),
            from, to, android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, dstW.toFloat(), dstH.toFloat(), paint)
    return out
}

/**
 * Decode a saved PNG back into a source bitmap, for "Last sent".
 *
 * The saved file is the *flattened* picture: the overlays, strokes, adjustments
 * and frame are already pixels in it. So it comes back as a plain source with an
 * empty edit list - the text is still visible because it is part of the image,
 * but it is no longer editable, and applying a restored edit list on top of this
 * file would draw every overlay twice.
 *
 * Restoring an editable edit list needs it stored alongside the image, which is
 * a schema change and lands separately. Until then "Last sent" means "start
 * from that picture", not "reopen that edit".
 *
 * Returns null when the file is missing or unreadable, so a deleted gallery
 * entry degrades to the empty state rather than crashing.
 */
internal fun loadSavedBitmap(file: java.io.File): Bitmap? =
    try {
        if (!file.exists()) null else BitmapFactory.decodeFile(file.absolutePath)
    } catch (t: Throwable) {
        null
    }
