package radio.ks3ckc.sstvaf.ui.rx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [RxImageAssembler] row painting, resizing, and snapshot isolation. */
@RunWith(RobolectricTestRunner::class)
class RxImageAssemblerTest {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val black = 0xFF000000.toInt()

    private fun rowPixels(width: Int, rows: Int, color: Int) = IntArray(width * rows) { color }

    @Test
    fun ensureSize_createsBlackCanvas() {
        val assembler = RxImageAssembler()
        assertThat(assembler.ensureSize(4, 3)).isTrue()
        assertThat(assembler.width).isEqualTo(4)
        assertThat(assembler.height).isEqualTo(3)

        val snap = assembler.snapshotBitmap()!!
        for (y in 0 until 3) {
            for (x in 0 until 4) {
                assertThat(snap.getPixel(x, y)).isEqualTo(black)
            }
        }
    }

    @Test
    fun ensureSize_sameSize_keepsCanvasAndPixels() {
        val assembler = RxImageAssembler()
        assembler.ensureSize(4, 3)
        assembler.applyRows(0, 1, rowPixels(4, 1, red))

        assertThat(assembler.ensureSize(4, 3)).isFalse()
        assertThat(assembler.snapshotBitmap()!!.getPixel(0, 0)).isEqualTo(red)
    }

    @Test
    fun ensureSize_newModeSize_resetsToFreshCanvas() {
        val assembler = RxImageAssembler()
        assembler.ensureSize(4, 3)
        assembler.applyRows(0, 3, rowPixels(4, 3, red))

        // Mode change mid-hunt: new dimensions -> brand-new black canvas.
        assertThat(assembler.ensureSize(6, 2)).isTrue()
        assertThat(assembler.width).isEqualTo(6)
        assertThat(assembler.height).isEqualTo(2)
        val snap = assembler.snapshotBitmap()!!
        assertThat(snap.width).isEqualTo(6)
        assertThat(snap.height).isEqualTo(2)
        assertThat(snap.getPixel(0, 0)).isEqualTo(black)
    }

    @Test
    fun applyRows_paintsOnlyTheGivenRows() {
        val assembler = RxImageAssembler()
        assembler.ensureSize(4, 4)

        assembler.applyRows(1, 2, rowPixels(4, 2, green))

        val snap = assembler.snapshotBitmap()!!
        for (x in 0 until 4) {
            assertThat(snap.getPixel(x, 0)).isEqualTo(black)
            assertThat(snap.getPixel(x, 1)).isEqualTo(green)
            assertThat(snap.getPixel(x, 2)).isEqualTo(green)
            assertThat(snap.getPixel(x, 3)).isEqualTo(black)
        }
    }

    @Test
    fun applyRows_isIncremental() {
        val assembler = RxImageAssembler()
        assembler.ensureSize(2, 3)

        assembler.applyRows(0, 1, rowPixels(2, 1, red))
        assembler.applyRows(1, 1, rowPixels(2, 1, green))

        val snap = assembler.snapshotBitmap()!!
        assertThat(snap.getPixel(0, 0)).isEqualTo(red)
        assertThat(snap.getPixel(0, 1)).isEqualTo(green)
        assertThat(snap.getPixel(0, 2)).isEqualTo(black)
    }

    @Test
    fun applyRows_clipsRowsBeyondCanvas() {
        val assembler = RxImageAssembler()
        assembler.ensureSize(2, 2)

        // 3 rows offered for a 2-row canvas starting at row 1: only row 1 fits.
        assembler.applyRows(1, 3, rowPixels(2, 3, red))

        val snap = assembler.snapshotBitmap()!!
        assertThat(snap.getPixel(0, 0)).isEqualTo(black)
        assertThat(snap.getPixel(0, 1)).isEqualTo(red)
    }

    @Test
    fun applyRows_outOfRangeOrBeforeSizing_isNoOp() {
        val assembler = RxImageAssembler()
        // Before ensureSize: nothing to paint, nothing thrown.
        assembler.applyRows(0, 1, rowPixels(2, 1, red))
        assertThat(assembler.snapshotBitmap()).isNull()

        assembler.ensureSize(2, 2)
        assembler.applyRows(5, 1, rowPixels(2, 1, red)) // firstRow past the end
        assembler.applyRows(-1, 1, rowPixels(2, 1, red)) // negative firstRow
        val snap = assembler.snapshotBitmap()!!
        assertThat(snap.getPixel(0, 0)).isEqualTo(black)
        assertThat(snap.getPixel(0, 1)).isEqualTo(black)
    }

    @Test
    fun snapshot_isIsolatedFromLaterRows() {
        val assembler = RxImageAssembler()
        assembler.ensureSize(2, 2)
        assembler.applyRows(0, 1, rowPixels(2, 1, red))

        val before = assembler.snapshotBitmap()!!
        assembler.applyRows(1, 1, rowPixels(2, 1, green))

        // The earlier snapshot must not see the later row.
        assertThat(before.getPixel(0, 1)).isEqualTo(black)
        assertThat(assembler.snapshotBitmap()!!.getPixel(0, 1)).isEqualTo(green)
    }

    @Test
    fun snapshot_reusesBuffersAlternately() {
        val assembler = RxImageAssembler()
        assembler.ensureSize(2, 2)

        val s1 = assembler.snapshotBitmap()!!
        val s2 = assembler.snapshotBitmap()!!
        val s3 = assembler.snapshotBitmap()!!
        val s4 = assembler.snapshotBitmap()!!

        // Steady state allocates nothing: the two buffers alternate A,B,A,B.
        assertThat(s3).isSameInstanceAs(s1)
        assertThat(s4).isSameInstanceAs(s2)
        assertThat(s2).isNotSameInstanceAs(s1)
    }

    @Test
    fun snapshot_recycledBufferCarriesLatestRows() {
        val assembler = RxImageAssembler()
        assembler.ensureSize(2, 2)
        assembler.applyRows(0, 1, rowPixels(2, 1, red))

        val s1 = assembler.snapshotBitmap()!!
        assembler.snapshotBitmap() // second buffer
        assembler.applyRows(1, 1, rowPixels(2, 1, green))
        val s3 = assembler.snapshotBitmap()!! // first buffer recycled

        assertThat(s3).isSameInstanceAs(s1)
        assertThat(s3.getPixel(0, 0)).isEqualTo(red)
        assertThat(s3.getPixel(0, 1)).isEqualTo(green)
    }

    @Test
    fun reset_dropsCanvas() {
        val assembler = RxImageAssembler()
        assembler.ensureSize(2, 2)
        assembler.reset()

        assertThat(assembler.snapshotBitmap()).isNull()
        assertThat(assembler.width).isEqualTo(0)
        assertThat(assembler.height).isEqualTo(0)
    }
}
