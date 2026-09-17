package radio.ks3ckc.sstvaf.ui.tx

import org.json.JSONArray
import org.json.JSONObject
import radio.ks3ckc.sstvaf.sstv.SstvMode

/**
 * The operator's edit list, serialized so a transmitted picture can be
 * *reopened* in the composer rather than only re-sent as the flat image it
 * became.
 *
 * Stored alongside the saved PNG (the `edits` column, DB v21). The PNG is the
 * flattened result — overlays and strokes already burned in — so restoring an
 * edit means loading the original source and the edit list and re-rendering,
 * not drawing the list over the flattened file. That is why the source URI
 * travels with the list.
 *
 * Hand-rolled JSON via org.json rather than a serialization library: it is
 * already on the platform, this is the only thing in the app that needs it,
 * and taking a dependency to persist six numbers and two lists would be the
 * larger cost. Every read tolerates absent or malformed fields, because a
 * stored blob outlives the code that wrote it.
 */
object TxEditList {

    /** Bumped when the shape changes incompatibly; readers refuse a higher one. */
    const val VERSION = 1

    private const val KEY_VERSION = "v"
    private const val KEY_MODE = "mode"
    private const val KEY_SOURCE = "src"
    private const val KEY_ZOOM = "zoom"
    private const val KEY_PAN_X = "panX"
    private const val KEY_PAN_Y = "panY"
    private const val KEY_BRIGHTNESS = "b"
    private const val KEY_CONTRAST = "c"
    private const val KEY_SATURATION = "s"
    private const val KEY_FRAME = "frame"
    private const val KEY_OVERLAYS = "overlays"
    private const val KEY_PATHS = "paths"

    private const val KEY_ID = "id"
    private const val KEY_TEXT = "text"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_COLOR = "color"
    private const val KEY_SIZE = "size"
    private const val KEY_STYLE = "style"
    private const val KEY_WIDTH = "w"
    private const val KEY_POINTS = "pts"

    /**
     * Serialize [composition] to a JSON string.
     *
     * The mode is stored by enum name, not by its native id: those ids cross
     * the JNI boundary and are pinned to the C table's order, so a name is the
     * more stable thing to write into a database row that has to survive an app
     * update.
     */
    fun serialize(composition: TxComposition): String {
        val root = JSONObject()
        root.put(KEY_VERSION, VERSION)
        root.put(KEY_MODE, composition.mode.name)
        composition.sourceUri?.let { root.put(KEY_SOURCE, it) }
        root.put(KEY_ZOOM, composition.zoom.toDouble())
        root.put(KEY_PAN_X, composition.panX.toDouble())
        root.put(KEY_PAN_Y, composition.panY.toDouble())
        root.put(KEY_BRIGHTNESS, composition.adjustments.brightness)
        root.put(KEY_CONTRAST, composition.adjustments.contrast)
        root.put(KEY_SATURATION, composition.adjustments.saturation)
        root.put(KEY_FRAME, composition.frame.name)

        val overlays = JSONArray()
        for (overlay in composition.overlays) {
            overlays.put(
                JSONObject().apply {
                    put(KEY_ID, overlay.id)
                    put(KEY_TEXT, overlay.text)
                    put(KEY_X, overlay.xPercent.toDouble())
                    put(KEY_Y, overlay.yPercent.toDouble())
                    put(KEY_COLOR, overlay.colorArgb)
                    put(KEY_SIZE, overlay.sizeFraction.toDouble())
                    put(KEY_STYLE, overlay.style.name)
                },
            )
        }
        root.put(KEY_OVERLAYS, overlays)

        val paths = JSONArray()
        for (path in composition.paths) {
            val points = JSONArray()
            for (point in path.points) {
                // A flat x, y, x, y array rather than an object per point: a
                // long freehand stroke runs to hundreds of points and this goes
                // into a database row that gets read while the gallery scrolls.
                points.put(point.xPercent.toDouble())
                points.put(point.yPercent.toDouble())
            }
            paths.put(
                JSONObject().apply {
                    put(KEY_COLOR, path.colorArgb)
                    put(KEY_WIDTH, path.widthFraction.toDouble())
                    put(KEY_POINTS, points)
                },
            )
        }
        root.put(KEY_PATHS, paths)
        return root.toString()
    }

    /**
     * Parse an edit list back into a composition, or null when there is nothing
     * usable in it.
     *
     * Null rather than an exception or a half-built composition, for every
     * failure: blank, malformed, a future [VERSION], or a mode this build does
     * not know. A stored blob outlives the code that wrote it, and the caller's
     * fallback — open the flattened picture as a plain source — is always
     * available and always better than a crash in the gallery.
     */
    fun parse(json: String): TxComposition? {
        if (json.isBlank()) return null
        return try {
            val root = JSONObject(json)
            if (root.optInt(KEY_VERSION, 0) > VERSION) return null
            val mode = SstvMode.entries.firstOrNull { it.name == root.optString(KEY_MODE) }
                ?: return null

            val overlays = mutableListOf<TextOverlay>()
            val overlayArray = root.optJSONArray(KEY_OVERLAYS)
            if (overlayArray != null) {
                for (i in 0 until overlayArray.length()) {
                    // A malformed entry rejects the whole blob rather than
                    // being skipped. Skipping returned a composition that
                    // looked complete but was missing an overlay, so resending
                    // silently dropped burned-in text; the flattened-image
                    // fallback at least shows the operator what was sent.
                    val item = overlayArray.optJSONObject(i) ?: return null
                    val id = item.optString(KEY_ID)
                    if (id.isEmpty()) return null
                    overlays.add(
                        TextOverlay(
                            id = id,
                            text = item.optString(KEY_TEXT),
                            xPercent = clampPercent(item.optDouble(KEY_X, 50.0).toFloat()),
                            yPercent = clampPercent(item.optDouble(KEY_Y, 50.0).toFloat()),
                            colorArgb = item.optInt(KEY_COLOR, OVERLAY_COLOR_WHITE),
                            sizeFraction = item.optDouble(
                                KEY_SIZE, OVERLAY_SIZE_MEDIUM.toDouble(),
                            ).toFloat(),
                            style = OverlayStyle.entries
                                .firstOrNull { it.name == item.optString(KEY_STYLE) }
                                ?: OverlayStyle.BAR,
                        ),
                    )
                }
            }

            val paths = mutableListOf<DrawPath>()
            val pathArray = root.optJSONArray(KEY_PATHS)
            if (pathArray != null) {
                for (i in 0 until pathArray.length()) {
                    val item = pathArray.optJSONObject(i) ?: continue
                    val flat = item.optJSONArray(KEY_POINTS) ?: continue
                    val points = mutableListOf<PathPoint>()
                    // Two at a time; an odd trailing value is dropped rather
                    // than paired with a default, which would plant a point
                    // somewhere the operator never drew.
                    var j = 0
                    while (j + 1 < flat.length()) {
                        points.add(
                            PathPoint(
                                clampPercent(flat.optDouble(j, 50.0).toFloat()),
                                clampPercent(flat.optDouble(j + 1, 50.0).toFloat()),
                            ),
                        )
                        j += 2
                    }
                    if (points.isEmpty()) continue
                    paths.add(
                        DrawPath(
                            colorArgb = item.optInt(KEY_COLOR, OVERLAY_COLOR_WHITE),
                            widthFraction = item.optDouble(
                                KEY_WIDTH, STROKE_MEDIUM.toDouble(),
                            ).toFloat(),
                            points = points,
                        ),
                    )
                }
            }

            TxComposition(
                sourceUri = root.optString(KEY_SOURCE).ifEmpty { null },
                mode = mode,
                zoom = clampZoom(root.optDouble(KEY_ZOOM, 1.0).toFloat()),
                panX = clampPan(root.optDouble(KEY_PAN_X, 0.0).toFloat()),
                panY = clampPan(root.optDouble(KEY_PAN_Y, 0.0).toFloat()),
                overlays = overlays,
                paths = paths,
                adjustments = ImageAdjustments(
                    brightness = clampAdjustment(root.optInt(KEY_BRIGHTNESS, ADJUSTMENT_NEUTRAL)),
                    contrast = clampAdjustment(root.optInt(KEY_CONTRAST, ADJUSTMENT_NEUTRAL)),
                    saturation = clampAdjustment(root.optInt(KEY_SATURATION, ADJUSTMENT_NEUTRAL)),
                ),
                frame = ImageFrame.entries.firstOrNull { it.name == root.optString(KEY_FRAME) }
                    ?: ImageFrame.NONE,
            )
        } catch (t: Throwable) {
            null
        }
    }
}

/** Shorthand for [TxEditList.parse], for readability at call sites. */
internal fun parseEditList(json: String): TxComposition? = TxEditList.parse(json)

/**
 * Whether a saved picture carries an edit list worth reopening.
 *
 * Drives whether the viewer offers "Send again" as a reopen or as a plain
 * re-send of the flattened image. A row from before DB v21, or one whose blob
 * no longer parses, has to fall back — and the operator should not be offered
 * an editor that cannot deliver.
 */
internal fun hasRestorableEdits(editsJson: String): Boolean = parseEditList(editsJson) != null
