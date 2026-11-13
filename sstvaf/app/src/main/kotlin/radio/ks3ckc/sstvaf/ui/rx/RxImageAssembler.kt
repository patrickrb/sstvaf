package radio.ks3ckc.sstvaf.ui.rx

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * Assembles the in-progress RX image: owns a mutable ARGB_8888 [Bitmap] sized
 * on VIS lock from the mode dimensions and paints decoded rows into it as they
 * arrive. NOT a composable — [RxScreen] feeds it from `rxState` transitions and
 * renders [snapshotBitmap] frames, so Compose never observes the working
 * bitmap directly.
 *
 * Not thread-safe by design: all calls happen on the UI thread (LiveData
 * delivery).
 */
class RxImageAssembler {

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    private var bitmap: Bitmap? = null

    // Snapshot double-buffer: [snapshotBitmap] alternates between two reused
    // bitmaps instead of allocating a fresh copy per call — a multi-minute
    // PD120 decode at ~4 updates/s would otherwise churn ~5 MB/s of garbage.
    // The frame handed out is not rewritten until the next-but-one snapshot;
    // by then Compose is already displaying the newer frame, and since SSTV
    // rows only ever append, a late read of a recycled frame shows at worst a
    // few additional completed rows.
    private var snapshotA: Bitmap? = null
    private var snapshotB: Bitmap? = null
    private var nextSnapshotIsA = true

    /**
     * Size (or re-size) the canvas for a newly locked mode. Returns true when
     * a fresh bitmap was created (caller must restart its row bookkeeping);
     * false when the existing canvas already matches.
     */
    fun ensureSize(width: Int, height: Int): Boolean {
        require(width > 0 && height > 0) { "bad image size ${width}x$height" }
        if (bitmap != null && this.width == width && this.height == height) return false
        this.width = width
        this.height = height
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF000000.toInt()) // undecoded rows render black
        }
        return true
    }

    /**
     * Paint [nRows] decoded rows starting at [firstRow]. [pixels] is row-major
     * 0xAARRGGBB, at least `nRows * width` long. Rows outside the canvas are
     * clipped; a call before [ensureSize] is a no-op.
     */
    fun applyRows(firstRow: Int, nRows: Int, pixels: IntArray) {
        val b = bitmap ?: return
        if (firstRow < 0 || firstRow >= height) return
        val rows = nRows.coerceAtMost(height - firstRow).coerceAtMost(pixels.size / width)
        if (rows <= 0) return
        b.setPixels(pixels, 0, width, 0, firstRow, width, rows)
    }

    /**
     * A frame for Compose reflecting the rows applied so far. The returned
     * bitmap stays stable through the NEXT [snapshotBitmap] call (the two
     * snapshot buffers alternate); it is recycled for reuse after that.
     */
    fun snapshotBitmap(): Bitmap? {
        val src = bitmap ?: return null
        val reuse = if (nextSnapshotIsA) snapshotA else snapshotB
        val dst = if (reuse != null && reuse.width == src.width && reuse.height == src.height) {
            reuse
        } else {
            Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also {
                if (nextSnapshotIsA) snapshotA = it else snapshotB = it
            }
        }
        Canvas(dst).drawBitmap(src, 0f, 0f, null)
        nextSnapshotIsA = !nextSnapshotIsA
        return dst
    }

    /** Drop the canvas and snapshot buffers (back to hunting). */
    fun reset() {
        bitmap = null
        snapshotA = null
        snapshotB = null
        nextSnapshotIsA = true
        width = 0
        height = 0
    }
}
