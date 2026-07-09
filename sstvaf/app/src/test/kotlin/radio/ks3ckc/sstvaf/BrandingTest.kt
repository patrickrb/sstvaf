package radio.ks3ckc.sstvaf

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the SSTVAF rebrand (new applicationId, app name, manifest wiring).
 * These assertions run against the merged debug manifest under Robolectric, so
 * they fail if the launcher activity FQN, the FileProvider authority, or the
 * user-visible app name ever drift from the rebranded values.
 */
@RunWith(RobolectricTestRunner::class)
class BrandingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `app name resource is SSTVAF`() {
        assertThat(context.getString(R.string.app_name)).isEqualTo("SSTVAF")
    }

    @Test
    fun `application id is the rebranded package`() {
        assertThat(context.packageName).isEqualTo("radio.ks3ckc.sstvaf")
    }

    @Test
    fun `launcher intent resolves to the rebranded ComposeMainActivity`() {
        val launcher = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val resolved = context.packageManager.resolveActivity(launcher, 0)
        assertThat(resolved).isNotNull()
        assertThat(resolved!!.activityInfo.name)
            .isEqualTo("radio.ks3ckc.sstvaf.ComposeMainActivity")
    }

    @Test
    fun `rebranded file provider authority resolves`() {
        val provider = context.packageManager
            .resolveContentProvider("radio.ks3ckc.sstvaf.fileprovider", 0)
        assertThat(provider).isNotNull()
        assertThat(provider!!.name).isEqualTo("androidx.core.content.FileProvider")
    }
}
