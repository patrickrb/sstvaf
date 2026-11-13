package radio.ks3ckc.sstvaf.wefax

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WefaxAutoSaveControllerTest {

    // ----- gray -> ARGB expansion -----

    @Test
    fun grayExpandsToOpaqueEqualChannels() {
        val argb = wefaxGrayToArgb(byteArrayOf(0, 0x40, 0xFF.toByte()), 3)
        assertThat(argb).isEqualTo(
            intArrayOf(0xFF000000.toInt(), 0xFF404040.toInt(), 0xFFFFFFFF.toInt()),
        )
    }

    @Test
    fun grayBytesAboveByteMaxReadUnsigned() {
        // 0x80 as a signed Kotlin Byte is -128; the pixel must read 128.
        val argb = wefaxGrayToArgb(byteArrayOf(0x80.toByte()), 1)
        assertThat(argb.single()).isEqualTo(0xFF808080.toInt())
    }

    @Test
    fun countIsClampedToTheBuffer() {
        assertThat(wefaxGrayToArgb(byteArrayOf(1, 2), 10)).hasLength(2)
    }

    // ----- mode strings -----

    @Test
    fun modeStringsCarryTheScheduleNotation() {
        assertThat(wefaxModeDisplayName(120, 576)).isEqualTo("WEFAX 120/576")
        assertThat(WEFAX_MODE_SHORT_CODE).isEqualTo("WEFAX")
    }
}
