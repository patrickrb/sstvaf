package radio.ks3ckc.sstvaf.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.k1af.ft8af.connector.ConnectMode
import com.k1af.ft8af.handoff.RigHandoffGate

/**
 * Picks the radio's USB audio input automatically when the operator left the
 * input on "system default" (issue #145).
 *
 * A Discord tester's IC-705 was connected over USB for CAT, but the RX log
 * said `MicRecorder: ... using system default mic`: the app never asked for
 * the rig's USB codec and left capture to Android's routing, which need not
 * be the radio — so SSTV the operator could plainly hear never reached the
 * decoder. Patrick's call (#145): when a USB audio input is plugged in, use
 * it.
 *
 * Rules, in order:
 * - An input the operator pinned in Settings (id > 0) always wins.
 * - While the rig is handed to a sibling app ([RigHandoffGate.isYielded]),
 *   nothing is picked: the release rebuilds capture precisely to let go of
 *   the rig's audio, and asking for its USB input again would take it back.
 * - A Bluetooth rig keeps the default route: its audio rides the headset
 *   (SCO) link, which `refreshBluetoothHeadsetMode` manages, and a USB
 *   preference would fight that route.
 * - Otherwise prefer a USB input: [AudioDeviceInfo.TYPE_USB_DEVICE] (a rig's
 *   codec, a sound-card interface) before [AudioDeviceInfo.TYPE_USB_HEADSET],
 *   then the lowest device id so the choice is stable across rebuilds.
 *
 * Applied each time the recorder builds its AudioRecord, so it follows the
 * radio being plugged in (the USB attach already rebuilds the recorder) and
 * the saved setting is never changed.
 *
 * SSTVAF adaptation: [com.k1af.ft8af.wave.MicRecorder] is synced from FT8AF;
 * it calls [resolve] from its two AudioRecord set-up paths.
 */
object UsbInputAutoSelect {

    /** An input as seen by the picker: [AudioDeviceInfo.getId] and its type. */
    data class Input(val id: Int, val type: Int)

    /** The input id to prefer, or null to leave the system default. */
    @JvmStatic
    @JvmOverloads
    fun pick(configuredInputId: Int, connectMode: Int, inputs: List<Input>, yielded: Boolean = false): Int? =
        pickOf(configuredInputId, connectMode, inputs, yielded, { it.id }, { it.type })?.id

    /** [pick] over any device representation, returning the chosen element. */
    fun <T> pickOf(
        configuredInputId: Int,
        connectMode: Int,
        inputs: List<T>,
        yielded: Boolean,
        id: (T) -> Int,
        type: (T) -> Int,
    ): T? {
        if (configuredInputId > 0) return null
        if (yielded) return null
        if (connectMode == ConnectMode.BLUE_TOOTH) return null
        return inputs
            .filter { isUsbInputType(type(it)) }
            .sortedWith(compareBy<T>({ type(it) != AudioDeviceInfo.TYPE_USB_DEVICE }, { id(it) }))
            .firstOrNull()
    }

    /** [pick] against the inputs present right now; null when none applies. */
    @JvmStatic
    fun resolve(context: Context?, configuredInputId: Int, connectMode: Int): AudioDeviceInfo? {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        return pickOf(configuredInputId, connectMode, devices, RigHandoffGate.isYielded(), { it.id }, { it.type })
    }

    /** True for the USB input types [pick] chooses between. */
    @JvmStatic
    fun isUsbInputType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET
}
