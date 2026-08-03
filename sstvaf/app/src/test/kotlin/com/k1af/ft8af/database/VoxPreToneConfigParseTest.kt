package com.k1af.ft8af.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.sstvaf.sstv.VoxPreTone

/**
 * Defensive parse of the voxPreToneMs config value ([DatabaseOpr.parseVoxPreToneMs]):
 * settings import (#382) can feed a blank, null, or corrupted string at startup,
 * which must fall back to the default rather than throw; numeric input is
 * clamped to the picker's 0..MAX_MS range.
 *
 * Robolectric only because loading [DatabaseOpr] resolves its Android
 * superclasses; the method under test is pure.
 */
@RunWith(RobolectricTestRunner::class)
class VoxPreToneConfigParseTest {

    @Test
    fun `numeric values parse and whitespace is tolerated`() {
        assertThat(DatabaseOpr.parseVoxPreToneMs("0")).isEqualTo(0)
        assertThat(DatabaseOpr.parseVoxPreToneMs("300")).isEqualTo(300)
        assertThat(DatabaseOpr.parseVoxPreToneMs(" 500 ")).isEqualTo(500)
    }

    @Test
    fun `blank null or garbage falls back to the default`() {
        assertThat(DatabaseOpr.parseVoxPreToneMs("")).isEqualTo(VoxPreTone.DEFAULT_MS)
        assertThat(DatabaseOpr.parseVoxPreToneMs(null)).isEqualTo(VoxPreTone.DEFAULT_MS)
        assertThat(DatabaseOpr.parseVoxPreToneMs("abc")).isEqualTo(VoxPreTone.DEFAULT_MS)
        assertThat(DatabaseOpr.parseVoxPreToneMs("3.5")).isEqualTo(VoxPreTone.DEFAULT_MS)
    }

    @Test
    fun `out-of-range values clamp to the picker range`() {
        assertThat(DatabaseOpr.parseVoxPreToneMs("-100")).isEqualTo(0)
        assertThat(DatabaseOpr.parseVoxPreToneMs("99999")).isEqualTo(VoxPreTone.MAX_MS)
    }
}
