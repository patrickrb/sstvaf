package radio.ks3ckc.sstvaf.ui.tx

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
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
 * `dstW x dstH`: bilinear-filtered cover-crop draw, then each overlay in
 * list order. Blank-text overlays are skipped entirely.
 */
internal fun renderComposite(src: Bitmap, comp: TxComposition, dstW: Int, dstH: Int): Bitmap {
    require(dstW > 0 && dstH > 0) { "bad composite size ${dstW}x$dstH" }
    val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)

    val crop = computeCoverCrop(src.width, src.height, dstW, dstH, comp.zoom, comp.panX, comp.panY)
    if (!crop.isEmpty) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, crop, Rect(0, 0, dstW, dstH), paint)
    }

    for (overlay in comp.overlays) {
        drawOverlay(canvas, overlay, dstW, dstH)
    }
    return out
}

/**
 * The full-width band rect for a BAR-style overlay, or null when the
 * position is not a bar (corner/center BAR overlays get a text-fitted
 * backing rect instead, computed in [drawOverlay]).
 */
internal fun barBandRect(position: OverlayPosition, sizeFraction: Float, dstW: Int, dstH: Int): Rect? {
    val bandH = (sizeFraction * dstH * BAR_HEIGHT_FACTOR).roundToInt().coerceIn(1, dstH)
    return when (position) {
        OverlayPosition.TOP_BAR -> Rect(0, 0, dstW, bandH)
        OverlayPosition.BOTTOM_BAR -> Rect(0, dstH - bandH, dstW, dstH)
        else -> null
    }
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

    // Horizontal anchor by position.
    val x = when (overlay.position) {
        OverlayPosition.TOP_LEFT, OverlayPosition.BOTTOM_LEFT -> pad
        OverlayPosition.TOP_RIGHT, OverlayPosition.BOTTOM_RIGHT -> dstW - pad - lineWidth
        else -> (dstW - lineWidth) / 2f
    }

    // Vertical anchor: bars center the text inside the band; corners hug the
    // edge with padding; CENTER centers in the frame.
    val fm = textPaint.fontMetrics
    val textHeight = fm.descent - fm.ascent
    val band = if (overlay.style == OverlayStyle.BAR) {
        barBandRect(overlay.position, overlay.sizeFraction, dstW, dstH)
    } else {
        null
    }
    val centerY = when {
        band != null -> band.exactCenterY()
        else -> when (overlay.position) {
            OverlayPosition.TOP_BAR, OverlayPosition.TOP_LEFT, OverlayPosition.TOP_RIGHT ->
                pad + textHeight / 2f
            OverlayPosition.BOTTOM_BAR, OverlayPosition.BOTTOM_LEFT, OverlayPosition.BOTTOM_RIGHT ->
                dstH - pad - textHeight / 2f
            OverlayPosition.CENTER -> dstH / 2f
        }
    }
    val baseline = centerY - (fm.ascent + fm.descent) / 2f

    if (overlay.style == OverlayStyle.BAR) {
        val bandPaint = Paint().apply { color = BAR_BAND_COLOR }
        if (band != null) {
            canvas.drawRect(band, bandPaint)
        } else {
            // Corner/center BAR: a text-fitted backing rect.
            canvas.drawRect(
                x - pad / 2f,
                baseline + fm.ascent - pad / 2f,
                x + lineWidth + pad / 2f,
                baseline + fm.descent + pad / 2f,
                bandPaint,
            )
        }
    } else {
        // OUTLINE: contrasting halo — the text drawn 4x diagonally offset.
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
