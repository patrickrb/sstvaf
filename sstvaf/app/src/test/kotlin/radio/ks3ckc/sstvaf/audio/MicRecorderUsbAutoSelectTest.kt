package radio.ks3ckc.sstvaf.audio

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.connector.ConnectMode
import com.k1af.ft8af.wave.MicRecorder
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

/**
 * Issue #145, end to end through the recorder: with the input on "system
 * default" and the rig's USB codec plugged in, MicRecorder must ask its
 * AudioRecord for the USB input. On origin/dev it left capture on the system
 * route; the tester's debug.log read "using system default mic".
 *
 * Robolectric's AudioRecord cannot hold a preferred device (setPreferredDevice
 * returns false there), so the recorder's decision is read from the line it
 * writes to debug.log — the same line a tester's shared log shows — and
 * [UsbInputAutoSelect.resolve] is checked against the shadowed device list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MicRecorderUsbAutoSelectTest {

    private lateinit var context: Context
    private var savedInput = 0
    private var savedMode = 0
    private var savedContext: Context? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        savedInput = GeneralVariables.audioInputDeviceId
        savedMode = GeneralVariables.connectMode
        savedContext = GeneralVariables.getMainContext()
        GeneralVariables.getInstance().setMainContext(context)
    }

    @After
    fun tearDown() {
        GeneralVariables.audioInputDeviceId = savedInput
        GeneralVariables.connectMode = savedMode
        GeneralVariables.getInstance().setMainContext(savedContext)
    }

    private fun inputs(vararg types: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(am).setInputDevices(types.map { AudioDeviceInfoBuilder.newBuilder().setType(it).build() })
    }

    /** RigHandoffGate.yieldTo/clear are package-private to the handoff package. */
    private fun setYielded(pkg: String?) {
        val gate = Class.forName("com.k1af.ft8af.handoff.RigHandoffGate")
        val m = if (pkg != null) {
            gate.getDeclaredMethod("yieldTo", String::class.java)
        } else {
            gate.getDeclaredMethod("clear")
        }
        m.isAccessible = true
        if (pkg != null) m.invoke(null, pkg) else m.invoke(null)
    }

    private fun logAfterBuildingRecorder(): String {
        val log = java.io.File(context.getExternalFilesDir(null), "debug.log")
        log.delete()
        MicRecorder()
        return if (log.exists()) log.readText() else ""
    }

    @Test
    fun defaultInput_withUsbCodecPlugged_prefersTheUsbInput() {
        GeneralVariables.audioInputDeviceId = 0
        GeneralVariables.connectMode = ConnectMode.USB_CABLE
        inputs(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_USB_DEVICE)
        assertThat(UsbInputAutoSelect.resolve(context, 0, ConnectMode.USB_CABLE)?.type)
            .isEqualTo(AudioDeviceInfo.TYPE_USB_DEVICE)
        val log = logAfterBuildingRecorder()
        assertThat(log).contains("auto-selected USB input")
        assertThat(log).doesNotContain("using system default mic")
    }

    @Test
    fun defaultInput_withoutUsb_staysOnTheSystemRoute() {
        GeneralVariables.audioInputDeviceId = 0
        GeneralVariables.connectMode = ConnectMode.USB_CABLE
        inputs(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        assertThat(UsbInputAutoSelect.resolve(context, 0, ConnectMode.USB_CABLE)).isNull()
        assertThat(logAfterBuildingRecorder()).contains("using system default mic")
    }

    @Test
    fun rigHandedToSiblingApp_withUsbPlugged_staysOnTheSystemRoute() {
        // PR #148 review: the handoff release rebuilds the recorder to give
        // the rig's audio up; it must not ask for the USB input again.
        GeneralVariables.audioInputDeviceId = 0
        GeneralVariables.connectMode = ConnectMode.USB_CABLE
        inputs(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_USB_DEVICE)
        setYielded("radio.ks3ckc.ft8af")
        try {
            assertThat(UsbInputAutoSelect.resolve(context, 0, ConnectMode.USB_CABLE)).isNull()
            assertThat(logAfterBuildingRecorder()).contains("using system default mic")
        } finally {
            setYielded(null)
        }
    }

    @Test
    fun bluetoothRig_withUsbPlugged_staysOnTheSystemRoute() {
        GeneralVariables.audioInputDeviceId = 0
        GeneralVariables.connectMode = ConnectMode.BLUE_TOOTH
        inputs(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_USB_DEVICE)
        assertThat(UsbInputAutoSelect.resolve(context, 0, ConnectMode.BLUE_TOOTH)).isNull()
        assertThat(logAfterBuildingRecorder()).contains("using system default mic")
    }
}
