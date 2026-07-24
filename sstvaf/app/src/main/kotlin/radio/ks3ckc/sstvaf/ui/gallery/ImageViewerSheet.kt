package radio.ks3ckc.sstvaf.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.gallery.IMAGE_NOTES_MAX_LENGTH
import radio.ks3ckc.sstvaf.gallery.SavedImage
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.AccentSoft
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.theme.BgSurface
import radio.ks3ckc.sstvaf.theme.BgSurface2
import radio.ks3ckc.sstvaf.theme.BgSurface3
import radio.ks3ckc.sstvaf.theme.Border
import radio.ks3ckc.sstvaf.theme.BorderAmber
import radio.ks3ckc.sstvaf.theme.GeistMonoFamily
import radio.ks3ckc.sstvaf.theme.StatusBad
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.SstvAfBottomSheet
import java.io.File

/**
 * Bottom-sheet viewer for one saved SSTV image: the picture at its native
 * aspect ratio, a metadata block, and Share / Save to Photos / Delete actions.
 * Delete asks for confirmation first; the caller performs the store operations
 * and closes the sheet.
 */
@Composable
fun ImageViewerSheet(
    visible: Boolean,
    entry: SavedImage?,
    imageFile: File?,
    onDismiss: () -> Unit,
    onShare: (SavedImage) -> Unit,
    onSaveToPhotos: (SavedImage) -> Unit,
    onDelete: (SavedImage) -> Unit,
    onSaveNote: (SavedImage, String) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    // A reopened sheet must never start on a stale confirm/edit dialog.
    LaunchedEffect(visible) {
        if (!visible) {
            confirmDelete = false
            editingNote = false
        }
    }

    SstvAfBottomSheet(visible = visible, onDismiss = onDismiss) {
        if (entry != null && imageFile != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            ) {
                AsyncImage(
                    model = imageFile,
                    contentDescription = entry.fileName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(viewerAspectRatio(entry.width, entry.height))
                        .clip(RoundedCornerShape(10.dp))
                        .background(BgSurface),
                    contentScale = ContentScale.Fit,
                )

                Spacer(modifier = Modifier.height(14.dp))

                MetaRow(stringResource(R.string.gallery_meta_mode), entry.mode)
                MetaRow(
                    stringResource(R.string.gallery_meta_frequency),
                    formatViewerFrequency(entry.freqHz),
                )
                // Only when the dial frequency lands in a known amateur band —
                // an out-of-band or hand-edited row simply omits the row.
                amateurBand(entry.freqHz)?.let { band ->
                    MetaRow(stringResource(R.string.gallery_meta_band), band)
                }
                MetaRow(
                    stringResource(R.string.gallery_meta_time),
                    formatViewerUtc(entry.utcMillis),
                )
                MetaRow(
                    stringResource(R.string.gallery_meta_size),
                    formatViewerDimensions(entry.width, entry.height),
                )
                MetaRow(
                    stringResource(R.string.gallery_meta_quality),
                    formatViewerQuality(entry.quality),
                )
                MetaRow(
                    stringResource(R.string.gallery_meta_direction),
                    stringResource(viewerDirectionRes(entry.direction)),
                )
                MetaRow(
                    stringResource(R.string.gallery_meta_status),
                    stringResource(viewerCompletenessRes(entry.complete)),
                )
                // Tap to add or edit a free-text note (callsign, QTH, comment)
                // that persists and rides along in the share caption.
                NoteRow(note = entry.notes, onEdit = { editingNote = true })

                Spacer(modifier = Modifier.height(18.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ViewerActionButton(
                        label = stringResource(R.string.gallery_share),
                        modifier = Modifier.weight(1f),
                        background = AccentSoft,
                        borderColor = BorderAmber,
                        textColor = Accent,
                        onClick = { onShare(entry) },
                    )
                    // No exported-already tracking exists, so the button is
                    // always offered; repeating it just inserts an (acceptable)
                    // duplicate MediaStore entry. Pre-API-29 the export is a
                    // no-op and the caller toasts the failure.
                    ViewerActionButton(
                        label = stringResource(R.string.gallery_save_photos),
                        modifier = Modifier.weight(1f),
                        background = BgSurface3,
                        borderColor = Border,
                        textColor = TextPrimary,
                        onClick = { onSaveToPhotos(entry) },
                    )
                    ViewerActionButton(
                        label = stringResource(R.string.gallery_delete),
                        modifier = Modifier.weight(1f),
                        background = BgSurface3,
                        borderColor = Border,
                        textColor = StatusBad,
                        onClick = { confirmDelete = true },
                    )
                }
            }
        }
    }

    if (confirmDelete && entry != null) {
        DeleteConfirmDialog(
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete(entry)
            },
        )
    }

    if (editingNote && entry != null) {
        NoteEditDialog(
            initial = entry.notes,
            onCancel = { editingNote = false },
            onSave = { text ->
                editingNote = false
                onSaveNote(entry, text)
            },
        )
    }
}

/**
 * Metadata row for the free-text note. Shows the note when set, or an "Add a
 * note" prompt (accent-colored) when empty; the whole row is clickable to open
 * the editor.
 */
@Composable
private fun NoteRow(note: String, onEdit: () -> Unit) {
    val hasNote = note.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onEdit)
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = stringResource(R.string.gallery_meta_note),
            modifier = Modifier.fillMaxWidth(0.4f),
            color = TextMuted,
            fontSize = 12.sp,
        )
        Text(
            text = if (hasNote) note else stringResource(R.string.gallery_note_add),
            color = if (hasNote) TextPrimary else Accent,
            fontFamily = GeistMonoFamily,
            fontSize = 12.sp,
        )
    }
}

/** Centered modal to add/edit an image note (style matches DeleteConfirmDialog). */
@Composable
private fun NoteEditDialog(
    initial: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    // The field caps the typed length to the persisted maximum so the counter
    // and the stored value can never disagree; the store re-sanitizes anyway.
    var text by remember { mutableStateOf(initial) }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(BgSurface2)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.gallery_note_title),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.06.sp,
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(IMAGE_NOTES_MAX_LENGTH) },
                label = { Text(stringResource(R.string.gallery_note_hint)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    focusedLabelColor = Accent,
                ),
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgSurface3)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Accent)
                        .clickable(onClick = { onSave(text) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.gallery_note_save),
                        color = BgApp,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

/**
 * Aspect ratio for the viewer image; degenerate stored dimensions (0 — e.g. a
 * hand-edited row) fall back to SSTV's classic 4:3 rather than crashing the
 * layout with a 0/NaN ratio.
 */
internal fun viewerAspectRatio(width: Int, height: Int): Float =
    if (width > 0 && height > 0) width.toFloat() / height else 4f / 3f

@Composable
private fun ViewerActionButton(
    label: String,
    background: androidx.compose.ui.graphics.Color,
    borderColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/** Centered confirm modal for deleting one image (style matches ExitConfirmDialog). */
@Composable
private fun DeleteConfirmDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(BgSurface2)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.gallery_delete_title),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.06.sp,
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.gallery_delete_message),
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgSurface3)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(StatusBad)
                        .clickable(onClick = onConfirm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.gallery_delete),
                        color = BgApp,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}
