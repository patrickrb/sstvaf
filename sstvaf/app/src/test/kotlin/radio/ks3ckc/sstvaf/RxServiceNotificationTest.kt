package radio.ks3ckc.sstvaf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import com.k1af.ft8af.service.RxForegroundService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Release-polish guard for the RX foreground-service notification: the copy
 * must describe SSTV (not the retired FT8 engine), while the notification
 * channel id stays stable so existing installs keep their user-set channel
 * preferences across the SSTVAF upgrade.
 */
@RunWith(RobolectricTestRunner::class)
class RxServiceNotificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `notification title says SSTVAF is listening for SSTV images`() {
        assertThat(context.getString(R.string.rx_service_title))
            .isEqualTo("SSTVAF is listening for SSTV images")
    }

    @Test
    fun `notification body and channel description mention SSTV not FT8`() {
        val text = context.getString(R.string.rx_service_text)
        val channelDesc = context.getString(R.string.rx_service_channel_desc)
        assertThat(text).contains("SSTV")
        assertThat(channelDesc).contains("SSTV")
        assertThat(text).doesNotContain("FT8")
        assertThat(channelDesc).doesNotContain("FT8")
    }

    @Test
    fun `channel id is unchanged for existing installs`() {
        assertThat(RxForegroundService.CHANNEL_ID).isEqualTo("rx_running")
    }
}
