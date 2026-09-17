package radio.ks3ckc.sstvaf.ui.tx

import java.io.File

/**
 * Durable storage for the original photo behind a persisted edit list.
 *
 * "Send again" only restores editable overlays if the *source* picture can be
 * decoded again; the saved PNG has the overlays burned into its pixels, so
 * reopening that and replaying the list would draw everything twice. A camera
 * capture lands in `cacheDir/camera_captures`, which is explicitly throwaway
 * staging the system may evict at any time, so an edit list pointing there
 * outlives the picture it describes and the reopen silently downgrades to the
 * flattened image.
 *
 * Camera sources are therefore copied here, under `filesDir`, before the edit
 * list is written. Gallery-picked photos are left alone: they already live in
 * the media store, which is durable and not ours to duplicate.
 *
 * Cleanup is a sweep rather than a delete hooked to each gallery row. A row's
 * file name is generated inside the store at save time, so there is no name to
 * derive a source path from at delete time without threading one through; a
 * sweep over what is actually referenced needs no coupling at all and also
 * collects sources orphaned by a crash between the copy and the save.
 */

/** Subdirectory of `filesDir` holding durable TX sources. */
internal const val TX_SOURCES_DIR = "tx_sources"

/** The directory durable sources live in, created on demand. */
internal fun txSourcesDir(filesDir: File): File {
    val dir = File(filesDir, TX_SOURCES_DIR)
    if (!dir.isDirectory) dir.mkdirs()
    return dir
}

/**
 * Whether a source URI points into the evictable camera staging directory.
 *
 * Matched on the path segment rather than by resolving the file, so it works on
 * a `file://` URI string without touching the disk.
 */
internal fun isCameraStagedSource(sourceUri: String?): Boolean =
    sourceUri != null && sourceUri.contains("/$CAMERA_CAPTURE_DIR/")

/**
 * The durable destination for a staged capture, named from [nowMs].
 *
 * Never returns an existing path, so two saves in the same millisecond cannot
 * overwrite each other's source.
 */
internal fun durableSourceFile(filesDir: File, nowMs: Long): File {
    val dir = txSourcesDir(filesDir)
    var candidate = File(dir, "src_$nowMs.png")
    var suffix = 1
    while (candidate.exists()) {
        candidate = File(dir, "src_${nowMs}_$suffix.png")
        suffix++
    }
    return candidate
}

/**
 * The source files that no edit list refers to any more.
 *
 * [existingNames] are the file names in the sources directory and
 * [referencedUris] the source URIs across every stored edit list. A file is
 * orphaned when no URI mentions its name — matched by name because the stored
 * URI is a full `file://` path and only the final segment is stable.
 *
 * Pure so the decision can be tested without a filesystem; the caller does the
 * deleting.
 */
internal fun orphanedSourceNames(
    existingNames: List<String>,
    referencedUris: Collection<String>,
): List<String> {
    if (existingNames.isEmpty()) return emptyList()
    val referenced = referencedUris.mapNotNull { uri ->
        uri.substringAfterLast('/').takeIf { it.isNotEmpty() }
    }.toSet()
    return existingNames.filter { it !in referenced }
}
