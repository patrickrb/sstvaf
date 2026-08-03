package radio.ks3ckc.sstvaf.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.sstvaf.sstv.VoxPreTone

/**
 * Guards the VOX pre-tone picker's option list and the pure
 * [voxPreToneIndex] mapping. No Robolectric — plain list/int logic.
 */
class VoxPreToneSettingTest {

    @Test
    fun `options run from off to the max in 100 ms steps`() {
        assertThat(VOX_PRE_TONE_MS_OPTIONS.first()).isEqualTo(0)
        assertThat(VOX_PRE_TONE_MS_OPTIONS.last()).isEqualTo(VoxPreTone.MAX_MS)
        assertThat(VOX_PRE_TONE_MS_OPTIONS.zipWithNext { a, b -> b - a }.distinct())
            .containsExactly(100)
    }

    @Test
    fun `options include the default so the picker can highlight it`() {
        assertThat(VOX_PRE_TONE_MS_OPTIONS).contains(VoxPreTone.DEFAULT_MS)
    }

    @Test
    fun `stored values map to their exact option`() {
        assertThat(voxPreToneIndex(0)).isEqualTo(0)
        assertThat(voxPreToneIndex(300)).isEqualTo(VOX_PRE_TONE_MS_OPTIONS.indexOf(300))
        assertThat(voxPreToneIndex(VoxPreTone.MAX_MS))
            .isEqualTo(VOX_PRE_TONE_MS_OPTIONS.lastIndex)
    }

    @Test
    fun `an out-of-list stored value falls back to the default entry`() {
        // A settings import can persist any int; the picker highlights the
        // default rather than crashing or selecting nothing.
        val defaultIndex = VOX_PRE_TONE_MS_OPTIONS.indexOf(VoxPreTone.DEFAULT_MS)
        assertThat(voxPreToneIndex(250)).isEqualTo(defaultIndex)
        assertThat(voxPreToneIndex(-1)).isEqualTo(defaultIndex)
        assertThat(voxPreToneIndex(99999)).isEqualTo(defaultIndex)
    }
}
