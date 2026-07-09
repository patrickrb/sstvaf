package radio.ks3ckc.sstvaf.ui.tx

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import radio.ks3ckc.sstvaf.ui.gallery.SSTV_FILE_PROVIDER_AUTHORITY
import java.io.File

/**
 * Camera capture staging for the TX composer (issue #11): the system camera
 * app writes a full-resolution JPEG into a file we own, which the composer
 * then downsamples into its source bitmap. We take the picture via
 * [androidx.activity.result.contract.ActivityResultContracts.TakePicture],
 * which delegates to whatever camera app the user has — so the app needs no
 * CAMERA permission of its own (declaring one without granting it would in
 * fact make ACTION_IMAGE_CAPTURE fail).
 *
 * The composer decodes with EXIF rotation applied (see loadSourceBitmap), so
 * portrait captures come out upright.
 *
 * This file carries the plain, unit-testable staging-path logic; the Compose
 * launcher wiring lives in TxComposeScreen.kt.
 */

/** Cache subdir the camera app writes captures into (matches filepaths.xml). */
internal const val CAMERA_CAPTURE_DIR = "camera_captures"

/**
 * A fresh file to receive a camera capture, named by [nowMs]. Creates the
 * [CAMERA_CAPTURE_DIR] subdir under [cacheDir] if needed and never returns a
 * path that already exists, so two shots in the same millisecond (or a
 * lingering capture) can't collide. The capture is throwaway staging — once
 * loaded into the composer's downsampled source bitmap it is no longer needed
 * — so it lives in the cache dir (evictable) rather than filesDir.
 */
internal fun cameraCaptureFile(cacheDir: File, nowMs: Long): File {
    val dir = File(cacheDir, CAMERA_CAPTURE_DIR)
    // mkdirs() returns false when the dir already exists (the common case), so
    // gate on the actual outcome — we only need it to end up a directory.
    if (!dir.isDirectory) dir.mkdirs()
    // Suffix until the name is free: nowMs alone isn't unique across two
    // launches within the same millisecond, and we must never hand back a path
    // that's mid-flight for an earlier capture.
    var candidate = File(dir, "capture_$nowMs.jpg")
    var suffix = 1
    while (candidate.exists()) {
        candidate = File(dir, "capture_${nowMs}_${suffix++}.jpg")
    }
    return candidate
}

/**
 * A content:// URI for [file] via the shared FileProvider. TakePicture grants
 * the camera app temporary write access to this URI; the same URI reads back
 * through the ContentResolver in loadSourceBitmap.
 */
internal fun cameraCaptureUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, SSTV_FILE_PROVIDER_AUTHORITY, file)
