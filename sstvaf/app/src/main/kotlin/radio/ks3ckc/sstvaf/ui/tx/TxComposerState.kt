package radio.ks3ckc.sstvaf.ui.tx

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * TX composer state that outlives the TX tab composable.
 *
 * The app shell swaps tabs with a plain `when (activeTab)`, so everything a
 * screen `remember`s is discarded on every tab switch (and on rotation). For
 * the TX composer that used to mean the operator's picked photo, crop, and
 * overlay edits silently vanished whenever they peeked at another tab —
 * infuriating mid-QSO. MainViewModel owns one instance of this class, so the
 * composition survives until the operator replaces the photo or clears it
 * explicitly (the CLEAR affordance on the preview).
 *
 * Bitmap ownership lives here too: the previous source is recycled only when
 * replaced or cleared, never on tab dispose. (Sources are downsampled to ~2x
 * the mode frame, so keeping one alive across tabs costs at most a few
 * hundred KB; on pre-O devices the pixels are native-heap, which is exactly
 * why the recycle stays deterministic instead of GC-driven.)
 */
class TxComposerState {

    /**
     * The editable composition, backed by Compose state so the TX screen
     * recomposes on every edit. Null until [refreshDefaults] first seeds it.
     * Written directly by the screen's edit handlers (crop gestures, mode
     * chips, overlay edits).
     */
    var composition: TxComposition? by mutableStateOf(null)

    /** The operator's picked/captured photo; null shows the pick prompt. */
    var sourceBitmap: Bitmap? by mutableStateOf(null)
        private set

    /**
     * What the composition looked like when defaults last seeded it — the
     * "untouched" marker for [refreshDefaults].
     */
    private var seededDefault: TxComposition? = null

    /**
     * Seed the composition with [defaultProvider] when it has never been
     * initialized, or re-seed when it is still *untouched* (no photo picked
     * and no edits since the last seeding). The untouched re-seed preserves
     * the old per-visit behavior of picking up operator changes — e.g. a
     * callsign entered in Settings after the TX tab was first opened still
     * produces the default callsign/CQ overlays on the next visit. Any
     * user-touched composition is returned unchanged, photo or not.
     *
     * Untouched is *structural*, deliberately: picking a photo and clearing it
     * again (with no mode/overlay edit) lands back on a composition equal to
     * the seeded default — genuinely pristine, so it re-seeds like one. The
     * clear itself is never undone (there is no photo either way), and any
     * real edit makes the state structurally distinct and thus protected.
     */
    fun refreshDefaults(defaultProvider: () -> TxComposition): TxComposition {
        val current = composition
        if (current == null || (sourceBitmap == null && current == seededDefault)) {
            val fresh = defaultProvider()
            seededDefault = fresh
            composition = fresh
            return fresh
        }
        return current
    }

    /**
     * Install a newly picked/captured photo: reset the crop to the aspect-fill
     * default, record [uri], and recycle the bitmap this one replaces. Must be
     * called after [refreshDefaults] has seeded the composition.
     */
    fun setImage(bitmap: Bitmap, uri: String) {
        val previous = sourceBitmap
        sourceBitmap = bitmap
        composition = checkNotNull(composition) { "refreshDefaults() must seed first" }
            .copy(sourceUri = uri, zoom = 1f, panX = 0f, panY = 0f)
        if (previous !== bitmap) previous?.recycle()
    }

    /**
     * The user's explicit clear: drop (and recycle) the photo and its crop,
     * keeping the selected mode and overlay setup. No-op when no photo is set.
     */
    fun clearImage() {
        val previous = sourceBitmap ?: return
        sourceBitmap = null
        composition = composition?.copy(sourceUri = null, zoom = 1f, panX = 0f, panY = 0f)
        previous.recycle()
    }
}
