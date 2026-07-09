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
 */
internal fun buildImageShareIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

/** Share [file] via the system chooser. */
internal fun shareImage(context: Context, file: File, chooserTitle: String) {
    val send = buildImageShareIntent(imageShareUri(context, file))
    context.startActivity(
        Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}
