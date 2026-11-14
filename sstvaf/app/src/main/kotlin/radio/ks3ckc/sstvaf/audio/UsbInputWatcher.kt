package radio.ks3ckc.sstvaf.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.k1af.ft8af.GeneralVariables

/**
 * Re-applies [UsbInputAutoSelect] when a USB audio input appears while RX is
 * running (PR #148 review).
 *
 * The USB attach intent only reaches the app for devices in
 * `res/xml/device_filter.xml` (the rigs and CAT adapters it knows), so a
 * separate USB sound card or a codec outside that list would otherwise not
 * be picked until the next recorder rebuild. This listens to the audio
 * framework itself: when an added input is a USB input, the picker now
 * chooses an input of a type the recorder is not routed to, it asks for a
 * rebuild ([rebuild], run off the main thread by the caller's choice).
 *
 * @param routedType the type the running AudioRecord captures from
 *        (`MicRecorder.routedInputDeviceType()`), -1 when there is none.
 */
class UsbInputWatcher(
    private val context: Context,
    private val routedType: () -> Int,
    private val rebuild: () -> Unit,
) {
    private var registered = false

    // registerAudioDeviceCallback() first replays every device already
    // present as "added"; the recorder was just built against those, so that
    // batch is not an attachment.
    private var replayPending = false

    internal val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (replayPending) {
                replayPending = false
                return
            }
            // Any USB-typed device: whether it brought an input is the
            // picker's call (it only sees inputs), so a USB output alone
            // changes nothing.
            if (addedDevices.none { UsbInputAutoSelect.isUsbInputType(it.type) }) return
            val picked = UsbInputAutoSelect.resolve(
                context,
                GeneralVariables.audioInputDeviceId,
                GeneralVariables.connectMode,
            ) ?: return
            val routed = routedType()
            if (!shouldRebuild(picked.type, routed)) return
            GeneralVariables.fileLog(
                "UsbInputWatcher: USB input added (${picked.productName}, type=${picked.type}," +
                    " routed=$routed) -> rebuilding capture",
            )
            rebuild()
        }
    }

    fun register() {
        if (registered) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        replayPending = true
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        registered = true
    }

    fun unregister() {
        if (!registered) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.unregisterAudioDeviceCallback(callback)
        registered = false
    }

    companion object {
        /**
         * Rebuild unless the recorder already captures from an input of the
         * picked type (registering also replays the devices already present,
         * and the USB attach intent may have rebuilt first).
         */
        @JvmStatic
        fun shouldRebuild(pickedType: Int, routedType: Int): Boolean = routedType != pickedType
    }
}
