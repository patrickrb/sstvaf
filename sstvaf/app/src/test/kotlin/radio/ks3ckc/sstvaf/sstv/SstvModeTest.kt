package radio.ks3ckc.sstvaf.sstv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the Kotlin mode table against the C codec's (cpp/sstv_lib/sstv.h +
 * sstv_modes.c). The mode ids cross JNI verbatim, so any drift between this
 * enum and the C `SSTV_MODE_*` order silently decodes/encodes the WRONG mode
 * — this test turns that into a build failure. The expected values below are
 * hardcoded on purpose (do not derive them from SstvMode).
 */
class SstvModeTest {

    @Test
    fun modeIdsPinnedToNativeEnumOrder() {
        // Mirror of the SSTV_MODE_* enum in cpp/sstv_lib/sstv.h.
        val expected = mapOf(
            SstvMode.ROBOT_36 to 0,
            SstvMode.ROBOT_72 to 1,
            SstvMode.MARTIN_1 to 2,
            SstvMode.MARTIN_2 to 3,
            SstvMode.SCOTTIE_1 to 4,
            SstvMode.SCOTTIE_2 to 5,
            SstvMode.PD_50 to 6,
            SstvMode.PD_90 to 7,
            SstvMode.PD_120 to 8,
            SstvMode.SCOTTIE_DX to 9,
            SstvMode.MARTIN_3 to 10,
            SstvMode.MARTIN_4 to 11,
            SstvMode.PD_160 to 12,
            SstvMode.PD_180 to 13,
            SstvMode.PD_240 to 14,
            SstvMode.PD_290 to 15,
        )
        assertThat(expected).hasSize(SstvMode.entries.size)
        for ((mode, id) in expected) {
            assertThat(mode.modeId).isEqualTo(id)
        }
    }

    @Test
    fun decodeStatusValuesPinnedToNativeEnum() {
        // Mirror of SSTV_STATUS_* in cpp/sstv_lib/sstv.h.
        assertThat(DecodeStatus.IDLE.nativeValue).isEqualTo(0)
        assertThat(DecodeStatus.LEADER.nativeValue).isEqualTo(1)
        assertThat(DecodeStatus.VIS.nativeValue).isEqualTo(2)
        assertThat(DecodeStatus.IMAGE.nativeValue).isEqualTo(3)
        assertThat(DecodeStatus.DONE.nativeValue).isEqualTo(4)
        assertThat(DecodeStatus.ABORTED.nativeValue).isEqualTo(5)
        assertThat(DecodeStatus.fromNative(3)).isEqualTo(DecodeStatus.IMAGE)
        assertThat(DecodeStatus.fromNative(99)).isEqualTo(DecodeStatus.IDLE)
    }

    @Test
    fun dimensionsMatchModeTable() {
        assertThat(SstvMode.ROBOT_36.width to SstvMode.ROBOT_36.height).isEqualTo(320 to 240)
        assertThat(SstvMode.ROBOT_72.width to SstvMode.ROBOT_72.height).isEqualTo(320 to 240)
        assertThat(SstvMode.MARTIN_1.width to SstvMode.MARTIN_1.height).isEqualTo(320 to 256)
        assertThat(SstvMode.MARTIN_2.width to SstvMode.MARTIN_2.height).isEqualTo(320 to 256)
        assertThat(SstvMode.SCOTTIE_1.width to SstvMode.SCOTTIE_1.height).isEqualTo(320 to 256)
        assertThat(SstvMode.SCOTTIE_2.width to SstvMode.SCOTTIE_2.height).isEqualTo(320 to 256)
        assertThat(SstvMode.PD_50.width to SstvMode.PD_50.height).isEqualTo(320 to 256)
        assertThat(SstvMode.PD_90.width to SstvMode.PD_90.height).isEqualTo(320 to 256)
        assertThat(SstvMode.PD_120.width to SstvMode.PD_120.height).isEqualTo(640 to 496)
        assertThat(SstvMode.SCOTTIE_DX.width to SstvMode.SCOTTIE_DX.height).isEqualTo(320 to 256)
        assertThat(SstvMode.MARTIN_3.width to SstvMode.MARTIN_3.height).isEqualTo(320 to 128)
        assertThat(SstvMode.MARTIN_4.width to SstvMode.MARTIN_4.height).isEqualTo(320 to 128)
        assertThat(SstvMode.PD_160.width to SstvMode.PD_160.height).isEqualTo(512 to 400)
        assertThat(SstvMode.PD_180.width to SstvMode.PD_180.height).isEqualTo(640 to 496)
        assertThat(SstvMode.PD_240.width to SstvMode.PD_240.height).isEqualTo(640 to 496)
        assertThat(SstvMode.PD_290.width to SstvMode.PD_290.height).isEqualTo(800 to 616)
    }

    @Test
    fun visCodesMatchModeTable() {
        assertThat(SstvMode.ROBOT_36.visCode).isEqualTo(8)
        assertThat(SstvMode.ROBOT_72.visCode).isEqualTo(12)
        assertThat(SstvMode.MARTIN_1.visCode).isEqualTo(44)
        assertThat(SstvMode.MARTIN_2.visCode).isEqualTo(40)
        assertThat(SstvMode.SCOTTIE_1.visCode).isEqualTo(60)
        assertThat(SstvMode.SCOTTIE_2.visCode).isEqualTo(56)
        assertThat(SstvMode.PD_50.visCode).isEqualTo(93)
        assertThat(SstvMode.PD_90.visCode).isEqualTo(99)
        assertThat(SstvMode.PD_120.visCode).isEqualTo(95)
        assertThat(SstvMode.SCOTTIE_DX.visCode).isEqualTo(76)
        assertThat(SstvMode.MARTIN_3.visCode).isEqualTo(36)
        assertThat(SstvMode.MARTIN_4.visCode).isEqualTo(32)
        assertThat(SstvMode.PD_160.visCode).isEqualTo(98)
        assertThat(SstvMode.PD_180.visCode).isEqualTo(96)
        assertThat(SstvMode.PD_240.visCode).isEqualTo(97)
        assertThat(SstvMode.PD_290.visCode).isEqualTo(94)
    }

    @Test
    fun visCodesAreUnique() {
        assertThat(SstvMode.entries.map { it.visCode }.toSet())
            .hasSize(SstvMode.entries.size)
    }

    @Test
    fun shortCodesMatchAndAreUnique() {
        val expected = mapOf(
            SstvMode.ROBOT_36 to "R36",
            SstvMode.ROBOT_72 to "R72",
            SstvMode.MARTIN_1 to "M1",
            SstvMode.MARTIN_2 to "M2",
            SstvMode.SCOTTIE_1 to "S1",
            SstvMode.SCOTTIE_2 to "S2",
            SstvMode.PD_50 to "PD50",
            SstvMode.PD_90 to "PD90",
            SstvMode.PD_120 to "PD120",
            SstvMode.SCOTTIE_DX to "SDX",
            SstvMode.MARTIN_3 to "M3",
            SstvMode.MARTIN_4 to "M4",
            SstvMode.PD_160 to "PD160",
            SstvMode.PD_180 to "PD180",
            SstvMode.PD_240 to "PD240",
            SstvMode.PD_290 to "PD290",
        )
        for ((mode, code) in expected) {
            assertThat(mode.shortCode).isEqualTo(code)
        }
        assertThat(SstvMode.entries.map { it.shortCode }.toSet())
            .hasSize(SstvMode.entries.size)
    }

    @Test
    fun txDurationsMatchModeTable() {
        // 0.91 s calibration header + the image duration from the C mode
        // table (sstv_mode_image_us). Tolerance is one scan line.
        val expectedSeconds = mapOf(
            SstvMode.ROBOT_36 to 36.91,
            SstvMode.ROBOT_72 to 72.91,
            SstvMode.MARTIN_1 to 115.200176,
            SstvMode.MARTIN_2 to 58.970288,
            SstvMode.SCOTTIE_1 to 110.54332,
            SstvMode.SCOTTIE_2 to 72.008152,
            SstvMode.PD_50 to 50.59448,
            SstvMode.PD_90 to 90.89912,
            SstvMode.PD_120 to 127.01304,
            SstvMode.SCOTTIE_DX to 269.7958,
            SstvMode.MARTIN_3 to 58.055088,
            SstvMode.MARTIN_4 to 29.940144,
            SstvMode.PD_160 to 161.7932,
            SstvMode.PD_180 to 187.96152,
            SstvMode.PD_240 to 248.91,
            SstvMode.PD_290 to 289.59224,
        )
        for ((mode, seconds) in expectedSeconds) {
            assertThat(mode.txDurationSeconds).isWithin(0.001).of(seconds)
        }
    }

    @Test
    fun totalRowsEqualsHeight() {
        for (mode in SstvMode.entries) {
            assertThat(mode.totalRows).isEqualTo(mode.height)
        }
    }

    @Test
    fun fromModeIdRoundTripsAndRejectsUnknown() {
        for (mode in SstvMode.entries) {
            assertThat(SstvMode.fromModeId(mode.modeId)).isEqualTo(mode)
        }
        assertThat(SstvMode.fromModeId(-1)).isNull()
        assertThat(SstvMode.fromModeId(16)).isNull()
    }
}
