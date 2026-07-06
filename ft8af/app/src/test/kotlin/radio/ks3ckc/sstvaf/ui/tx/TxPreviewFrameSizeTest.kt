package radio.ks3ckc.sstvaf.ui.tx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [previewFrameSize]: the mode-aspect preview must fill the width on a tall
 * (portrait) canvas exactly as before, but cap its height on a short
 * (landscape / tablet) canvas so the pick-image CTA, mode chips, and TRANSMIT
 * button stay on screen (issue #23). Pure geometry — no Robolectric runner
 * (nothing here touches android.graphics).
 */
class TxPreviewFrameSizeTest {

    // Scottie 1 frame is 320x256 (aspect 1.25); the math is mode-agnostic.
    private val modeW = 320
    private val modeH = 256
    private val aspect = modeW.toFloat() / modeH.toFloat()

    @Test
    fun `tall portrait canvas fills the width unchanged`() {
        // 288 = 360 / 1.25, well under the 0.6 * 700 = 420 cap.
        val size = previewFrameSize(modeW, modeH, availableWidthDp = 360f, availableHeightDp = 700f)
        assertThat(size.widthDp).isEqualTo(360f)
        assertThat(size.heightDp).isWithin(1e-3f).of(288f)
    }

    @Test
    fun `portrait tablet still fills the width`() {
        // width-based height 640 < 0.6 * 1100 = 660.
        val size = previewFrameSize(modeW, modeH, availableWidthDp = 800f, availableHeightDp = 1100f)
        assertThat(size.widthDp).isEqualTo(800f)
        assertThat(size.heightDp).isWithin(1e-3f).of(640f)
    }

    @Test
    fun `short landscape canvas caps the height and centers`() {
        // Width-based height would be 900 / 1.25 = 720, far taller than the
        // 0.6 * 360 = 216 cap, so the frame is sized from the cap.
        val size = previewFrameSize(modeW, modeH, availableWidthDp = 900f, availableHeightDp = 360f)
        assertThat(size.heightDp).isWithin(1e-3f).of(216f)
        assertThat(size.widthDp).isWithin(1e-3f).of(216f * aspect) // 270
        // The capped frame leaves room below for the controls...
        assertThat(size.heightDp).isLessThan(360f)
        // ...and never overflows the width.
        assertThat(size.widthDp).isAtMost(900f)
    }

    @Test
    fun `boundary where width-based height exactly equals the cap stays width-based`() {
        // availableWidth 320 -> width-based height 256; pick availableHeight so
        // 0.6 * availableHeight == 256, i.e. availableHeight = 426.667.
        val availableHeight = 256f / 0.6f
        val size = previewFrameSize(modeW, modeH, availableWidthDp = 320f, availableHeightDp = availableHeight)
        assertThat(size.widthDp).isEqualTo(320f)
        assertThat(size.heightDp).isWithin(1e-3f).of(256f)
    }

    @Test
    fun `just past the boundary switches to height-capped`() {
        // Same width (320 -> width-based height 256) but a hair shorter canvas,
        // so the cap (0.6 * height) drops below 256 and height-capping kicks in.
        val availableHeight = 256f / 0.6f - 1f
        val size = previewFrameSize(modeW, modeH, availableWidthDp = 320f, availableHeightDp = availableHeight)
        val expectedCap = availableHeight * 0.6f
        assertThat(size.heightDp).isWithin(1e-3f).of(expectedCap)
        assertThat(size.heightDp).isLessThan(256f)
        assertThat(size.widthDp).isWithin(1e-3f).of(expectedCap * aspect)
        assertThat(size.widthDp).isLessThan(320f)
    }

    @Test
    fun `non-positive available height falls back to width-based sizing`() {
        // BoxWithConstraints degenerate case: never divide the world by a zero
        // or negative viewport — keep the old width-based behavior.
        val size = previewFrameSize(modeW, modeH, availableWidthDp = 360f, availableHeightDp = 0f)
        assertThat(size.widthDp).isEqualTo(360f)
        assertThat(size.heightDp).isWithin(1e-3f).of(288f)
    }

    @Test
    fun `custom fraction changes the cap`() {
        // A 0.5 cap on a 360dp-tall canvas caps at 180 instead of 216.
        val size = previewFrameSize(
            modeW, modeH,
            availableWidthDp = 900f, availableHeightDp = 360f,
            maxHeightFraction = 0.5f,
        )
        assertThat(size.heightDp).isWithin(1e-3f).of(180f)
        assertThat(size.widthDp).isWithin(1e-3f).of(180f * aspect)
    }
}
