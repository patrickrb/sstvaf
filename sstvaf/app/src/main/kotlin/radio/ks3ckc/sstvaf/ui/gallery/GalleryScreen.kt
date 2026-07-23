package radio.ks3ckc.sstvaf.ui.gallery

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import radio.ks3ckc.sstvaf.gallery.ImageDirection
import radio.ks3ckc.sstvaf.gallery.SavedImage
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.AccentSoft
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.Signal
import radio.ks3ckc.sstvaf.theme.SignalSoft
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.EmptyStateWaves
import radio.ks3ckc.sstvaf.ui.components.FilterChips
import radio.ks3ckc.sstvaf.ui.components.TopBar

/**
 * The Gallery tab: a grid of saved SSTV images (received today; transmitted
 * ones join in PR 8) with a direction filter row and a tap-to-open viewer
 * sheet (share / save to Photos / delete). All decision/formatting logic
 * lives in GalleryLogic.kt (unit tested); this file is the thin Compose
 * wrapper over [radio.ks3ckc.sstvaf.gallery.ReceivedImageStore].
 */
@Composable
fun GalleryScreen(mainViewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = mainViewModel.receivedImageStore

    // Bumped after a delete; also fresh on every tab visit (the tab switch in
    // SstvAfApp is a plain swap, so entering the tab recomposes from scratch
    // and reloads the list).
    var refreshKey by remember { mutableIntStateOf(0) }
    var images by remember { mutableStateOf<List<SavedImage>>(emptyList()) }
    LaunchedEffect(refreshKey) {
        images = withContext(Dispatchers.IO) { sortGalleryImages(store.list()) }
    }

    var filter by rememberSaveable { mutableStateOf(GalleryFilter.ALL) }
    val shown = filterGalleryImages(images, filter)

    // Clock for the cells' relative ages ("2 h ago"): ticks once a minute so
    // labels don't go stale while the screen stays open.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMs = System.currentTimeMillis()
        }
    }

    // Newest-day-first sections for the grid's date headers; recomputed when the
    // list/filter changes, and when the UTC day rolls over (not every minute tick).
    val todayIdx = Math.floorDiv(nowMs, 86_400_000L)
    val sections = remember(shown, todayIdx) { buildGallerySections(shown, nowMs) }
    // Viewer sheet: entry outlives visibility so the slide-out animation still
    // has content to draw after dismiss.
    var viewerVisible by remember { mutableStateOf(false) }
    var viewerEntry by remember { mutableStateOf<SavedImage?>(null) }

    val filterLabels = GalleryFilter.entries.associateWith { stringResource(it.labelRes()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp),
    ) {
        TopBar(title = stringResource(R.string.gallery_title))

        FilterChips(
            options = GalleryFilter.entries.map { filterLabels.getValue(it) },
            selected = filterLabels.getValue(filter),
            onSelected = { label ->
                filter = GalleryFilter.entries.first { filterLabels.getValue(it) == label }
            },
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (shown.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Center within the visible band, not the full content Box: in a
                    // short/landscape canvas the illustration is taller than this Box
                    // and (a Box doesn't clip) overflows its bottom edge, where the
                    // later-composed TX strip draws over the spill. See
                    // emptyStateBottomPadding (#24).
                    .padding(bottom = emptyStateBottomPadding()),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyStateWaves()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(galleryEmptyStateRes(filter)),
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                sections.forEach { section ->
                    item(
                        key = "hdr:${gallerySectionKey(section.header)}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        GallerySectionTitle(section.header)
                    }
                    items(section.images, key = { it.id }) { entry ->
                        GalleryCell(
                            entry = entry,
                            imageFile = store.imageFile(entry),
                            nowMs = nowMs,
                            onClick = {
                                viewerEntry = entry
                                viewerVisible = true
                            },
                        )
                    }
                }
            }
        }
    }

    ImageViewerSheet(
        visible = viewerVisible,
        entry = viewerEntry,
        imageFile = viewerEntry?.let { store.imageFile(it) },
        onDismiss = { viewerVisible = false },
        onShare = { entry ->
            shareImage(
                context,
                store.imageFile(entry),
                context.getString(R.string.gallery_share_chooser_title),
            )
        },
        onSaveToPhotos = { entry ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { store.exportToPhotos(entry) }
                Toast.makeText(
                    context,
                    if (ok) R.string.gallery_saved_to_photos else R.string.gallery_save_photos_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        onDelete = { entry ->
            scope.launch {
                withContext(Dispatchers.IO) { store.delete(entry.id) }
                viewerVisible = false
                refreshKey++
            }
        },
    )
}

/** Chip-row label for each filter value. */
internal fun GalleryFilter.labelRes(): Int = when (this) {
    GalleryFilter.ALL -> R.string.gallery_filter_all
    GalleryFilter.RX -> R.string.gallery_filter_rx
    GalleryFilter.TX -> R.string.gallery_filter_tx
}

/** Full-width date header above a run of same-day cells (Today / Yesterday / date). */
@Composable
private fun GallerySectionTitle(header: GallerySectionHeader) {
    val text = when (header) {
        GallerySectionHeader.Today -> stringResource(R.string.gallery_section_today)
        GallerySectionHeader.Yesterday -> stringResource(R.string.gallery_section_yesterday)
        is GallerySectionHeader.Earlier -> header.dateLabel
    }
    Text(
        text = text,
        // The grid's contentPadding already insets 16dp horizontally, so the
        // header only needs vertical breathing room to align with the cells.
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        color = TextMuted,
        fontFamily = GeistMonoFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.06.sp,
    )
}

/** One grid cell: cropped thumbnail + RX/TX chip overlay + mode/date line. */
@Composable
private fun GalleryCell(
    entry: SavedImage,
    imageFile: java.io.File,
    nowMs: Long,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box {
            AsyncImage(
                model = imageFile,
                contentDescription = entry.fileName,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgSurface),
                contentScale = ContentScale.Crop,
            )
            DirectionChip(
                direction = entry.direction,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = galleryCellMeta(entry, nowMs),
            color = TextMuted,
            fontFamily = GeistMonoFamily,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

/** Small RX/TX badge (cyan for received, amber for sent). */
@Composable
internal fun DirectionChip(direction: ImageDirection, modifier: Modifier = Modifier) {
    val (bg, fg) = when (direction) {
        ImageDirection.RX -> AccentSoft to Accent
        ImageDirection.TX -> SignalSoft to Signal
    }
    Text(
        text = direction.name,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BgApp.copy(alpha = 0.72f))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        color = fg,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.08.sp,
    )
}

/** Metadata label/value row used by the viewer sheet. */
@Composable
internal fun MetaRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(0.4f),
            color = TextMuted,
            fontSize = 12.sp,
        )
        Text(
            text = value,
            color = TextPrimary,
            fontFamily = GeistMonoFamily,
            fontSize = 12.sp,
        )
    }
}
