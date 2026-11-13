package radio.ks3ckc.sstvaf.ui.settings

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.database.RigNameList
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rig list as the picker sees it, read from the shipped `rigaddress.txt`.
 *
 * Robolectric because the list is loaded through the asset manager. This runs
 * against the real asset rather than a fixture on purpose: the thing that can
 * break here is the asset gaining a row the filter does not expect, and a
 * fixture would never notice.
 */
@RunWith(RobolectricTestRunner::class)
class RigOptionsTest {

    private val rigNameList: RigNameList
        get() = RigNameList(ApplicationProvider.getApplicationContext())

    @Test
    fun `the shipped list yields a usable set of models`() {
        val options = rigOptions(rigNameList)
        assertThat(options.size).isAtLeast(20)
    }

    @Test
    fun `commented-out models are not selectable`() {
        // rigaddress.txt carries at least one commented row that still contains
        // commas, so it survives the loader parse. Picking it would configure
        // the app for a line the maintainer had deliberately disabled.
        val options = rigOptions(rigNameList)
        assertThat(options.none { it.name.startsWith("#") }).isTrue()
    }

    @Test
    fun `each option points back at its own row in the shipped list`() {
        // The index is what gets persisted as the model number, so an
        // off-by-one here would silently configure the wrong radio.
        val list = rigNameList
        for (option in rigOptions(list)) {
            assertThat(list.getRigNameByIndex(option.index).name).isEqualTo(option.name)
        }
    }

    @Test
    fun `indices are unique`() {
        val options = rigOptions(rigNameList)
        assertThat(options.map { it.index }).containsNoDuplicates()
    }

    @Test
    fun `searching the real list finds a well-known rig`() {
        val options = rigOptions(rigNameList)
        assertThat(searchRigs(options, "ic-705")).isNotEmpty()
    }
}
