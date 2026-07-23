package radio.ks3ckc.sstvaf.gallery

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import com.k1af.ft8af.GeneralVariables
import radio.ks3ckc.sstvaf.sstv.SstvMode
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Whether a stored SSTV image was received or transmitted. TX arrives in PR 8. */
enum class ImageDirection { RX, TX }

/** One row of the `sstv_images` table (metadata for a PNG on disk). */
data class SavedImage(
    val id: Long,
    val fileName: String,
    val direction: ImageDirection,
    val mode: String,
    val freqHz: Long,
    val utcMillis: Long,
    val width: Int,
    val height: Int,
    val complete: Boolean,
    val quality: Float,
    val notes: String,
)

/** Subdirectory of `filesDir` holding saved SSTV PNGs (see res/xml/filepaths.xml). */
internal const val SSTV_IMAGES_DIR = "sstv_images"

/** The `sstv_images` metadata table (created by DatabaseOpr at DB v19). */
internal const val SSTV_IMAGES_TABLE = "sstv_images"

/**
 * Builds the on-disk PNG file name for a saved image:
 * `SSTV_20260704T153012Z_S1_14230000_RX.png`
 * (UTC compact ISO timestamp, mode short code, dial frequency in Hz, direction).
 */
internal fun buildImageFileName(
    utcMillis: Long,
    modeShortCode: String,
    freqHz: Long,
    direction: ImageDirection,
): String {
    val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return "SSTV_${fmt.format(Date(utcMillis))}_${modeShortCode}_${freqHz}_${direction.name}.png"
}

/** Maps a save request to the `sstv_images` ContentValues (pure, testable). */
internal fun imageMetadataValues(
    fileName: String,
    direction: ImageDirection,
    modeName: String,
    freqHz: Long,
    utcMillis: Long,
    width: Int,
    height: Int,
    complete: Boolean,
    quality: Float,
): ContentValues = ContentValues().apply {
    put("fileName", fileName)
    put("direction", direction.name)
    put("mode", modeName)
    put("freqHz", freqHz)
    put("utcMillis", utcMillis)
    put("width", width)
    put("height", height)
    put("complete", if (complete) 1 else 0)
    put("quality", quality)
    put("notes", "")
}

/** Maps a `sstv_images` cursor row back to a [SavedImage]. */
internal fun savedImageFromCursor(cursor: Cursor): SavedImage = SavedImage(
    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
    fileName = cursor.getString(cursor.getColumnIndexOrThrow("fileName")),
    direction = imageDirectionFromDb(cursor.getString(cursor.getColumnIndexOrThrow("direction"))),
    mode = cursor.getString(cursor.getColumnIndexOrThrow("mode")),
    freqHz = cursor.getLong(cursor.getColumnIndexOrThrow("freqHz")),
    utcMillis = cursor.getLong(cursor.getColumnIndexOrThrow("utcMillis")),
    width = cursor.getInt(cursor.getColumnIndexOrThrow("width")),
    height = cursor.getInt(cursor.getColumnIndexOrThrow("height")),
    complete = cursor.getInt(cursor.getColumnIndexOrThrow("complete")) != 0,
    quality = cursor.getFloat(cursor.getColumnIndexOrThrow("quality")),
    notes = cursor.getString(cursor.getColumnIndexOrThrow("notes")) ?: "",
)

/** DB text → direction; unknown text degrades to RX rather than crashing list(). */
internal fun imageDirectionFromDb(text: String?): ImageDirection =
    if (text == ImageDirection.TX.name) ImageDirection.TX else ImageDirection.RX

/**
 * Decides whether a save should ALSO be exported to the system Photos app
 * (MediaStore). RX images only, API 29+ only (scoped storage / RELATIVE_PATH —
 * pre-29 would need WRITE_EXTERNAL_STORAGE, so it is skipped silently), and
 * only when the `saveRxToPhotos` setting is on.
 */
internal fun shouldExportToPhotos(
    direction: ImageDirection,
    settingEnabled: Boolean,
    sdkInt: Int,
): Boolean = direction == ImageDirection.RX && settingEnabled && sdkInt >= Build.VERSION_CODES.Q

/**
 * Persistence for decoded (and, from PR 8, transmitted) SSTV images: a PNG in
 * `filesDir/sstv_images/` plus a metadata row in the `sstv_images` SQLite
 * table (DatabaseOpr DB v19). Completed RX images are optionally mirrored to
 * the system Photos app via MediaStore under `Pictures/SSTVAF`.
 *
 * All methods are synchronous — callers pick the thread (the auto-save
 * controller runs saves off the main thread).
 */
class ReceivedImageStore @JvmOverloads constructor(
    private val context: Context,
    private val db: SQLiteDatabase,
    private val log: (String) -> Unit = { GeneralVariables.fileLog(it) },
    private val saveToPhotosEnabled: () -> Boolean = { GeneralVariables.saveRxToPhotos },
) {

    /** `filesDir/sstv_images/`, created on demand. */
    private fun imagesDir(): File = File(context.filesDir, SSTV_IMAGES_DIR).apply { mkdirs() }

    /** The on-disk PNG backing [entry]. */
    fun imageFile(entry: SavedImage): File = File(imagesDir(), entry.fileName)

    /**
     * Save one image: PNG to app storage, metadata row to `sstv_images`, and
     * (RX + setting + API 29+) a copy into the system Photos app.
     *
     * @param pixels 0xAARRGGBB row-major, `width * height` long.
     * @throws java.io.IOException when the PNG can't be encoded/written or the
     *   metadata row can't be inserted (low storage, I/O error). Nothing is
     *   left behind on failure — a caller must never believe a save happened
     *   when it didn't.
     */
    fun save(
        pixels: IntArray,
        width: Int,
        height: Int,
        mode: SstvMode,
        utcMillis: Long,
        freqHz: Long,
        direction: ImageDirection,
        complete: Boolean,
        quality: Float,
    ): SavedImage {
        require(pixels.size >= width * height) {
            "pixels too small: ${pixels.size} < ${width}x$height"
        }
        val fileName = buildImageFileName(utcMillis, mode.shortCode, freqHz, direction)
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

        val file = File(imagesDir(), fileName)
        var written = false
        try {
            file.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("PNG encode failed for $fileName")
                }
            }
            written = true
        } finally {
            // Never leave a truncated/corrupt PNG behind (encode failure or
            // an IOException out of the stream itself).
            if (!written) file.delete()
        }

        val values = imageMetadataValues(
            fileName, direction, mode.displayName, freqHz, utcMillis,
            width, height, complete, quality,
        )
        val id = db.insert(SSTV_IMAGES_TABLE, null, values)
        if (id == -1L) {
            file.delete()
            throw IOException("sstv_images insert failed for $fileName")
        }

        if (shouldExportToPhotos(direction, saveToPhotosEnabled(), Build.VERSION.SDK_INT)) {
            try {
                exportToPhotos(bitmap, fileName, utcMillis)
            } catch (t: Throwable) {
                // Photos export is best-effort; the app-storage copy is canonical.
                log("SSTV image store: Photos export failed for $fileName: $t")
            }
        }

        return SavedImage(
            id = id,
            fileName = fileName,
            direction = direction,
            mode = mode.displayName,
            freqHz = freqHz,
            utcMillis = utcMillis,
            width = width,
            height = height,
            complete = complete,
            quality = quality,
            notes = "",
        )
    }

    /** All saved images, newest first. */
    fun list(): List<SavedImage> {
        val result = mutableListOf<SavedImage>()
        db.query(
            SSTV_IMAGES_TABLE, null, null, null, null, null,
            "utcMillis DESC, id DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(savedImageFromCursor(cursor))
            }
        }
        return result
    }

    /**
     * Update the free-text note on one saved image (the sender's callsign, a
     * comment). The caller passes the note already normalized (single-line,
     * length-capped — see `normalizeNote`); the store just writes it. Returns
     * true if a row was updated — a false means the id no longer exists (e.g.
     * the image was deleted from another screen), which the caller can ignore.
     */
    fun updateNotes(id: Long, notes: String): Boolean {
        val values = ContentValues().apply { put("notes", notes) }
        return db.update(SSTV_IMAGES_TABLE, values, "id = ?", arrayOf(id.toString())) > 0
    }

    /**
     * Delete one image: the metadata row first (committed), THEN the file —
     * so a crash between the two leaves an orphan file (harmless) rather than
     * a metadata row pointing at nothing. A missing file is tolerated.
     */
    fun delete(id: Long) {
        var entry: SavedImage? = null
        db.query(
            SSTV_IMAGES_TABLE, null, "id = ?", arrayOf(id.toString()),
            null, null, null,
        ).use { cursor ->
            if (cursor.moveToFirst()) entry = savedImageFromCursor(cursor)
        }
        db.delete(SSTV_IMAGES_TABLE, "id = ?", arrayOf(id.toString()))
        entry?.let { imageFile(it).delete() } // false (missing file) is fine
    }

    /**
     * Manual "Save to Photos" from the gallery viewer. Unlike the automatic
     * export on save, this works for any direction and ignores the
     * `saveRxToPhotos` setting — the user asked explicitly. API 29+ only
     * (pre-29 would need WRITE_EXTERNAL_STORAGE; callers toast on `false`).
     * Nothing tracks whether an image was already exported, so repeating the
     * action inserts a duplicate MediaStore entry — acceptable for a manual,
     * user-driven action.
     *
     * @return true if a copy was handed to MediaStore.
     */
    fun exportToPhotos(entry: SavedImage): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val file = imageFile(entry)
        if (!file.exists()) return false
        // Stream the PNG bytes straight into MediaStore — decoding to a
        // Bitmap just to re-encode identical pixels would burn CPU and, for
        // large modes, risk pressure on the heap for no benefit.
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, entry.fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.DATE_TAKEN, entry.utcMillis)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SSTVAF")
            // Invisible to Photos until fully written.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            log("SSTV image store: manual Photos export insert returned null for ${entry.fileName}")
            return false
        }
        var written = false
        try {
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                log("SSTV image store: manual Photos export stream was null for ${entry.fileName}")
            } else {
                out.use { o -> file.inputStream().use { it.copyTo(o) } }
                written = true
            }
        } catch (t: Throwable) {
            log("SSTV image store: manual Photos export failed for ${entry.fileName}: $t")
        } finally {
            if (written) {
                val publish = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
            } else {
                // Never leave a hidden/empty orphan row in MediaStore.
                resolver.delete(uri, null, null)
            }
        }
        return written
    }

    /**
     * Insert a copy into MediaStore under `Pictures/SSTVAF`. API 29+ only
     * (RELATIVE_PATH needs no storage permission there); callers gate via
     * [shouldExportToPhotos].
     */
    private fun exportToPhotos(bitmap: Bitmap, fileName: String, utcMillis: Long) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.DATE_TAKEN, utcMillis)
            // String constants, safe to reference below Q; only used on Q+.
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SSTVAF")
            // Keep the row invisible to Photos until the pixels are written,
            // so a crash or write failure never surfaces a blank image.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            log("SSTV image store: Photos export insert returned null for $fileName")
            return
        }
        var written = false
        try {
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                log("SSTV image store: Photos export stream was null for $fileName")
            } else {
                out.use { written = bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!written) {
                    log("SSTV image store: Photos export encode failed for $fileName")
                }
            }
        } finally {
            if (written) {
                val publish = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
            } else {
                // Never leave a hidden/empty orphan row in MediaStore.
                resolver.delete(uri, null, null)
            }
        }
    }
}
