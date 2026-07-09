package radio.ks3ckc.sstvaf.ui.tx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [cameraCaptureFile]: staging-path naming and the on-demand subdir. Pure
 * filesystem logic (no Android types), so no Robolectric runner is needed.
 */
class TxCameraCaptureTest {

    private fun freshCacheDir(): File =
        Files.createTempDirectory("cache").toFile().also { it.deleteOnExit() }

    @Test
    fun `capture file lands under the camera_captures subdir`() {
        val cache = freshCacheDir()
        val file = cameraCaptureFile(cache, nowMs = 1_700_000_000_000L)
        assertThat(file.parentFile).isEqualTo(File(cache, CAMERA_CAPTURE_DIR))
    }

    @Test
    fun `file name encodes the timestamp and is a jpg`() {
        val file = cameraCaptureFile(freshCacheDir(), nowMs = 1_700_000_000_000L)
        assertThat(file.name).isEqualTo("capture_1700000000000.jpg")
    }

    @Test
    fun `creates the subdir when absent`() {
        val cache = freshCacheDir()
        assertThat(File(cache, CAMERA_CAPTURE_DIR).exists()).isFalse()
        cameraCaptureFile(cache, nowMs = 1L)
        assertThat(File(cache, CAMERA_CAPTURE_DIR).isDirectory).isTrue()
    }

    @Test
    fun `distinct timestamps yield distinct files so shots never collide`() {
        val cache = freshCacheDir()
        val a = cameraCaptureFile(cache, nowMs = 100L)
        val b = cameraCaptureFile(cache, nowMs = 200L)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `same timestamp never returns a path already in flight`() {
        val cache = freshCacheDir()
        val first = cameraCaptureFile(cache, nowMs = 100L)
        first.writeBytes(byteArrayOf(1)) // an earlier capture already staged here
        // A second launch in the same millisecond must not reuse the path.
        val second = cameraCaptureFile(cache, nowMs = 100L)
        assertThat(second).isNotEqualTo(first)
        assertThat(second.exists()).isFalse()
    }

    @Test
    fun `reuses an existing camera_captures subdir`() {
        val cache = freshCacheDir()
        assertThat(File(cache, CAMERA_CAPTURE_DIR).mkdirs()).isTrue() // pre-create
        // mkdirs() now returns false, but the function must still succeed.
        val file = cameraCaptureFile(cache, nowMs = 1L)
        assertThat(file.parentFile!!.isDirectory).isTrue()
        assertThat(file.name).isEqualTo("capture_1.jpg")
    }
}
