package radio.ks3ckc.sstvaf.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.connector.ConnectMode
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

/**
 * PR #148 review: a USB input attached while RX runs re-applies the automatic
 * USB selection even when the device is outside device_filter.xml (no USB
 * attach intent reaches the app for it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsbInputWatcherTest {

    private lateinit var context: Context
    private var savedInput = 0
    private var savedMode = 0
    private var routed = AudioDeviceInfo.TYPE_BUILTIN_MIC
    private var rebuilds = 0
    private lateinit var watcher: UsbInputWatcher

    private val usbCodec = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build()
    private val builtIn = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        savedInput = GeneralVariables.audioInputDeviceId
        savedMode = GeneralVariables.connectMode
        GeneralVariables.audioInputDeviceId = 0
        GeneralVariables.connectMode = ConnectMode.USB_CABLE
        watcher = UsbInputWatcher(context, { routed }, { rebuilds++ })
    }

    @After
    fun tearDown() {
        GeneralVariables.audioInputDeviceId = savedInput
        GeneralVariables.connectMode = savedMode
    }

    private fun present(vararg devices: AudioDeviceInfo) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(am).setInputDevices(devices.toList())
    }

    @Test
    fun `USB codec attached while on the built-in mic rebuilds capture`() {
        present(builtIn, usbCodec)
        watcher.callback.onAudioDevicesAdded(arrayOf(usbCodec))
        assertThat(rebuilds).isEqualTo(1)
    }

    @Test
    fun `already capturing from a USB device does not rebuild again`() {
        routed = AudioDeviceInfo.TYPE_USB_DEVICE
        present(builtIn, usbCodec)
        watcher.callback.onAudioDevicesAdded(arrayOf(usbCodec))
        assertThat(rebuilds).isEqualTo(0)
    }

    @Test
    fun `a non-USB input attaching does nothing`() {
        present(builtIn)
        watcher.callback.onAudioDevicesAdded(arrayOf(builtIn))
        assertThat(rebuilds).isEqualTo(0)
    }

    @Test
    fun `a pinned input is left alone`() {
        GeneralVariables.audioInputDeviceId = 5
        present(builtIn, usbCodec)
        watcher.callback.onAudioDevicesAdded(arrayOf(usbCodec))
        assertThat(rebuilds).isEqualTo(0)
    }

    @Test
    fun `the replay of present devices at registration is not an attachment`() {
        present(builtIn, usbCodec)
        // Registration replays the already-present devices (Android and
        // Robolectric both do): the USB codec there is not an attachment.
        watcher.register()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertThat(rebuilds).isEqualTo(0)
        // A later addition is.
        watcher.callback.onAudioDevicesAdded(arrayOf(usbCodec))
        assertThat(rebuilds).isEqualTo(1)
        watcher.unregister()
    }

    @Test
    fun `rebuild decision follows the routed type`() {
        assertThat(UsbInputWatcher.shouldRebuild(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BUILTIN_MIC)).isTrue()
        assertThat(UsbInputWatcher.shouldRebuild(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)).isTrue()
        assertThat(UsbInputWatcher.shouldRebuild(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_DEVICE)).isFalse()
    }
}
