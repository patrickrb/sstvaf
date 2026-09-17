package radio.ks3ckc.sstvaf.ui.tx

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Durable TX source storage and its orphan sweep.
 *
 * The reason this exists: a camera capture lands in the evictable cache, so an
 * edit list pointing at it outlives the picture it describes and "Send again"
 * silently degrades to the flattened image. Copying the source somewhere
 * durable fixes that but creates files nobody deletes, which is what the sweep
 * is for.
 */
class TxSourceStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ----- recognising a staged capture ---------------------------------------

    @Test
    fun `a camera staging path is recognised`() {
        assertThat(
            isCameraStagedSource("file:///data/user/0/app/cache/$CAMERA_CAPTURE_DIR/shot.jpg"),
        ).isTrue()
    }

    @Test
    fun `a media store photo is not a staged capture`() {
        // Gallery picks already live somewhere durable and must not be copied.
        assertThat(isCameraStagedSource("content://media/external/images/media/42")).isFalse()
    }

    @Test
    fun `a durable source is not mistaken for a staged one`() {
        // Otherwise reopening and resending would copy the copy, every time.
        val dir = txSourcesDir(temp.root)
        val uri = "file://" + dir.absolutePath.replace('\\', '/') + "/src_1.png"
        assertThat(isCameraStagedSource(uri)).isFalse()
    }

    @Test
    fun `a generated card source is not a staged capture`() {
        assertThat(isCameraStagedSource(cardSourceUri(TxCardKind.CQ))).isFalse()
    }

    @Test
    fun `a missing source is not a staged capture`() {
        assertThat(isCameraStagedSource(null)).isFalse()
    }

    // ----- allocating a destination -------------------------------------------

    @Test
    fun `the sources directory is created on demand`() {
        val dir = txSourcesDir(temp.root)
        assertThat(dir.isDirectory).isTrue()
        assertThat(dir.name).isEqualTo(TX_SOURCES_DIR)
    }

    @Test
    fun `two saves in the same millisecond do not collide`() {
        // Same clock reading, two files: the second must not overwrite the
        // first, or one gallery row loses its source to another.
        val first = durableSourceFile(temp.root, 1000L)
        first.writeBytes(byteArrayOf(1))
        val second = durableSourceFile(temp.root, 1000L)
        assertThat(second.path).isNotEqualTo(first.path)
        assertThat(second.exists()).isFalse()
    }

    // ----- the orphan sweep ---------------------------------------------------

    @Test
    fun `a referenced source is kept`() {
        val orphans = orphanedSourceNames(
            existingNames = listOf("src_1.png", "src_2.png"),
            referencedUris = listOf("file:///data/app/tx_sources/src_1.png"),
        )
        assertThat(orphans).containsExactly("src_2.png")
    }

    @Test
    fun `everything is orphaned when nothing refers to a source`() {
        val orphans = orphanedSourceNames(
            existingNames = listOf("src_1.png"),
            referencedUris = emptyList(),
        )
        assertThat(orphans).containsExactly("src_1.png")
    }

    @Test
    fun `an empty directory yields nothing to delete`() {
        assertThat(orphanedSourceNames(emptyList(), listOf("file:///x/src_1.png"))).isEmpty()
    }

    @Test
    fun `non-file source uris do not orphan anything by accident`() {
        // A content:// URI has no bearing on these files; the sweep must not
        // read it as "nothing is referenced".
        val orphans = orphanedSourceNames(
            existingNames = listOf("src_1.png"),
            referencedUris = listOf("content://media/external/images/media/9"),
        )
        assertThat(orphans).containsExactly("src_1.png")
    }

    @Test
    fun `matching is on the file name, not the full path`() {
        // The stored URI is an absolute file:// path recorded on a previous
        // install layout; only the final segment is stable.
        val orphans = orphanedSourceNames(
            existingNames = listOf("src_1.png"),
            referencedUris = listOf("file:///some/other/prefix/src_1.png"),
        )
        assertThat(orphans).isEmpty()
    }

    @Test
    fun `a trailing-slash uri does not match everything`() {
        val orphans = orphanedSourceNames(
            existingNames = listOf("src_1.png"),
            referencedUris = listOf("file:///data/tx_sources/"),
        )
        assertThat(orphans).containsExactly("src_1.png")
    }
}
