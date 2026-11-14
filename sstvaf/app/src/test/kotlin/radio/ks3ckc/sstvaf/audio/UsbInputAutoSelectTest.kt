package radio.ks3ckc.sstvaf.audio

import android.media.AudioDeviceInfo
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.connector.ConnectMode
import org.junit.Test
import radio.ks3ckc.sstvaf.audio.UsbInputAutoSelect.Input

/**
 * Issue #145: with the input on "system default", a plugged-in USB audio
 * input (the rig's codec) is used automatically.
 */
class UsbInputAutoSelectTest {

    private val builtInMic = Input(id = 3, type = AudioDeviceInfo.TYPE_BUILTIN_MIC)
    private val rigCodec = Input(id = 41, type = AudioDeviceInfo.TYPE_USB_DEVICE)
    private val usbHeadset = Input(id = 12, type = AudioDeviceInfo.TYPE_USB_HEADSET)
    private val btHeadset = Input(id = 7, type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

    @Test
    fun `default input with a USB rig plugged in picks the USB input`() {
        assertThat(UsbInputAutoSelect.pick(0, ConnectMode.USB_CABLE, listOf(builtInMic, rigCodec)))
            .isEqualTo(41)
    }

    @Test
    fun `no USB input leaves the system default`() {
        assertThat(UsbInputAutoSelect.pick(0, ConnectMode.USB_CABLE, listOf(builtInMic, btHeadset)))
            .isNull()
    }

    @Test
    fun `an input pinned in Settings always wins`() {
        assertThat(UsbInputAutoSelect.pick(3, ConnectMode.USB_CABLE, listOf(builtInMic, rigCodec)))
            .isNull()
    }

    @Test
    fun `USB direct that fell back to AudioRecord still prefers the USB input`() {
        // -1 = "USB direct" capture; MicRecorder only reaches AudioRecord when
        // that failed, and the operator's intent was the USB device.
        assertThat(UsbInputAutoSelect.pick(-1, ConnectMode.USB_CABLE, listOf(builtInMic, rigCodec)))
            .isEqualTo(41)
    }

    @Test
    fun `a Bluetooth rig keeps the default route`() {
        assertThat(UsbInputAutoSelect.pick(0, ConnectMode.BLUE_TOOTH, listOf(builtInMic, rigCodec)))
            .isNull()
    }

    @Test
    fun `network rig with a USB codec plugged in still picks it`() {
        assertThat(UsbInputAutoSelect.pick(0, ConnectMode.NETWORK, listOf(rigCodec, builtInMic)))
            .isEqualTo(41)
    }

    @Test
    fun `a USB device is preferred over a USB headset`() {
        assertThat(UsbInputAutoSelect.pick(0, ConnectMode.USB_CABLE, listOf(usbHeadset, rigCodec)))
            .isEqualTo(41)
    }

    @Test
    fun `only a USB headset is still picked`() {
        assertThat(UsbInputAutoSelect.pick(0, ConnectMode.USB_CABLE, listOf(builtInMic, usbHeadset)))
            .isEqualTo(12)
    }

    @Test
    fun `two USB devices pick the lowest id, stable across rebuilds`() {
        val second = Input(id = 40, type = AudioDeviceInfo.TYPE_USB_DEVICE)
        assertThat(UsbInputAutoSelect.pick(0, ConnectMode.USB_CABLE, listOf(rigCodec, second)))
            .isEqualTo(40)
        assertThat(UsbInputAutoSelect.pick(0, ConnectMode.USB_CABLE, listOf(second, rigCodec)))
            .isEqualTo(40)
    }
}
