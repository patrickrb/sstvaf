package radio.ks3ckc.sstvaf.ui.tx

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric because [compressForDigital] rides Bitmap.compress. */
@RunWith(RobolectricTestRunner::class)
class TxDigitalCompressTest {

    private fun gradientBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                bmp.setPixel(x, y, Color.rgb(x * 255 / w, y * 255 / h, (x + y) % 255))
            }
        }
        return bmp
    }

    @Test
    fun producesAJpegPayload() {
        val bytes = compressForDigital(gradientBitmap(64, 48))
        assertThat(bytes.size).isGreaterThan(2)
        // JPEG SOI marker.
        assertThat(bytes[0]).isEqualTo(0xFF.toByte())
        assertThat(bytes[1]).isEqualTo(0xD8.toByte())
    }

    @Test
    fun staysUnderTheBudgetWhenALadderRungFits() {
        val bytes = compressForDigital(gradientBitmap(64, 48), budgetBytes = 64 * 1024)
        assertThat(bytes.size).isAtMost(64 * 1024)
    }

    @Test
    fun impossibleBudgetStillReturnsTheLowestRung() {
        // A 1-byte budget can never be met; the lowest-quality bytes come
        // back anyway (a longer transmission beats refusing to send).
        val bytes = compressForDigital(gradientBitmap(64, 48), budgetBytes = 1)
        assertThat(bytes.size).isGreaterThan(1)
    }
}
