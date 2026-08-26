package radio.ks3ckc.sstvaf.sstv.digital

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** CRC-16/CCITT-FALSE and CRC-32 against the standard "123456789" check vectors. */
class CrcTest {

    private val check = "123456789".toByteArray(Charsets.US_ASCII)

    @Test
    fun `crc16 check vector`() {
        assertThat(Crc.crc16(check)).isEqualTo(0x29B1)
    }

    @Test
    fun `crc32 check vector`() {
        assertThat(Crc.crc32(check)).isEqualTo(0xCBF43926L)
    }

    @Test
    fun `crc detects single-bit changes`() {
        val data = ByteArray(64) { (it * 3).toByte() }
        val c16 = Crc.crc16(data)
        val c32 = Crc.crc32(data)
        data[10] = (data[10].toInt() xor 0x01).toByte()
        assertThat(Crc.crc16(data)).isNotEqualTo(c16)
        assertThat(Crc.crc32(data)).isNotEqualTo(c32)
    }

    @Test
    fun `crc32 stays within 32 bits`() {
        val data = ByteArray(200) { (255 - it).toByte() }
        assertThat(Crc.crc32(data)).isAtMost(0xFFFFFFFFL)
        assertThat(Crc.crc32(data)).isAtLeast(0L)
    }

    @Test
    fun `range overload matches copy`() {
        val data = ByteArray(20) { it.toByte() }
        val slice = data.copyOfRange(4, 12)
        assertThat(Crc.crc16(data, 4, 8)).isEqualTo(Crc.crc16(slice))
        assertThat(Crc.crc32(data, 4, 8)).isEqualTo(Crc.crc32(slice))
    }
}
