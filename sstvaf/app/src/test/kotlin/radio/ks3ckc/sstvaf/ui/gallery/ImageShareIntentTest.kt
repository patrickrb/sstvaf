package radio.ks3ckc.sstvaf.ui.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The gallery's Share action: the ACTION_SEND intent contents and the
 * FileProvider authority wiring. Robolectric because the authority is checked
 * against the merged manifest.
 *
 * Note: [imageShareUri] (the real `FileProvider.getUriForFile` call) is not
 * exercised here — androidx FileProvider's root matching compares canonical
 * paths against `rootPath + '/'`, which never matches the '\'-separated
 * canonical paths a Windows JVM produces, so the call throws under Robolectric
 * on Windows regardless of correct wiring. The authority + files-path wiring
 * it depends on is asserted below and in res/xml/filepaths.xml; URI resolution
 * itself is covered on-device.
 */
@RunWith(RobolectricTestRunner::class)
class ImageShareIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** What a shared image's content URI looks like (sstv_images files-path). */
    private val uri: Uri = Uri.parse(
        "content://$SSTV_FILE_PROVIDER_AUTHORITY/sstv_images/SSTV_20260704T153012Z_S1_14230000_RX.png",
    )

    @Test
    fun authorityConstant_matchesManifestProvider() {
        val provider = context.packageManager
            .resolveContentProvider(SSTV_FILE_PROVIDER_AUTHORITY, 0)
        assertThat(provider).isNotNull()
        assertThat(provider!!.name).isEqualTo("androidx.core.content.FileProvider")
    }

    @Test
    fun shareIntent_isActionSendPng() {
        val intent = buildImageShareIntent(uri)

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.type).isEqualTo("image/png")
    }

    @Test
    fun shareIntent_carriesStreamUriWithProviderAuthority() {
        val intent = buildImageShareIntent(uri)

        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertThat(stream).isEqualTo(uri)
        assertThat(stream!!.authority).isEqualTo(SSTV_FILE_PROVIDER_AUTHORITY)
    }

    @Test
    fun shareIntent_grantsReadUriPermission() {
        val intent = buildImageShareIntent(uri)

        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
