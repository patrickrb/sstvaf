package radio.ks3ckc.sstvaf.ui.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.k1af.ft8af.BuildConfig
import java.io.File

/**
 * The single source of truth for the FileProvider authority. Derived from the
 * applicationId to match `${applicationId}.fileprovider` in
 * AndroidManifest.xml; public so every share path (images here, debug logs in
 * ShareLogs.java via the same derivation) stays in lockstep.
 */
const val SSTV_FILE_PROVIDER_AUTHORITY = BuildConfig.APPLICATION_ID + ".fileprovider"

/**
 * Content URI for a saved SSTV PNG under `filesDir/sstv_images/` (the
 * `sstv_images` files-path in filepaths.xml).
 */
internal fun imageShareUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, SSTV_FILE_PROVIDER_AUTHORITY, file)

/**
 * The ACTION_SEND intent for sharing one saved image. Separated from the
 * chooser/startActivity plumbing so the intent contents are unit-testable.
 *
 * A non-blank [caption] rides along as EXTRA_TEXT (the body a chat/social
 * target shows next to the image) and EXTRA_SUBJECT (the title email-style
 * targets use), so the SSTV context travels with the picture. A null/blank
 * caption keeps the bare image-only intent unchanged.
 */
internal fun buildImageShareIntent(uri: Uri, caption: String? = null): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        if (!caption.isNullOrBlank()) {
            putExtra(Intent.EXTRA_TEXT, caption)
            putExtra(Intent.EXTRA_SUBJECT, caption)
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

/** Share [file] via the system chooser, optionally with a metadata [caption]. */
internal fun shareImage(
    context: Context,
    file: File,
    chooserTitle: String,
    caption: String? = null,
) {
    val send = buildImageShareIntent(imageShareUri(context, file), caption)
    context.startActivity(
        Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}
