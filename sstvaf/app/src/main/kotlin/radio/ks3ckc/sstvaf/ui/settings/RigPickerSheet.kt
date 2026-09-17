package radio.ks3ckc.sstvaf.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.theme.Accent
import radio.ks3ckc.sstvaf.theme.AccentSoft
import radio.ks3ckc.sstvaf.theme.BgSurface2
import radio.ks3ckc.sstvaf.theme.Border
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.theme.TextMuted
import radio.ks3ckc.sstvaf.theme.TextPrimary
import radio.ks3ckc.sstvaf.ui.components.SstvAfBottomSheet
import radio.ks3ckc.sstvaf.ui.components.SstvAfIcons
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.selection.selectable

/**
 * The rig model picker: a search field over the shipped model list.
 *
 * The list runs to roughly a hundred entries, which is too many to scroll
 * through on a phone in the field, and an operator already knows their model
 * name. Search is therefore the primary control rather than an accessory to
 * the list, and it is focused on the list rather than the keyboard so the sheet
 * does not open with half its content hidden.
 */
@Composable
internal fun RigPickerSheet(
    visible: Boolean,
    options: List<RigOption>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (RigOption) -> Unit,
) {
    var query by remember(visible) { mutableStateOf("") }
    val matches = remember(options, query) { searchRigs(options, query) }
    val listState = rememberLazyListState()

    // With no query, open on the current model rather than at the top: the
    // commonest reason to open this sheet is to check what is set, not change it.
    LaunchedEffect(visible, query) {
        if (visible && query.isEmpty()) {
            val position = matches.indexOfFirst { it.index == selectedIndex }
            if (position > 0) {
                listState.scrollToItem((position - 2).coerceAtLeast(0))
            }
        }
    }

    SstvAfBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.radio_rig_picker_title),
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )

            SearchField(query = query, onQueryChange = { query = it })

            if (matches.isEmpty()) {
                Text(
                    text = stringResource(R.string.radio_rig_no_matches),
                    color = TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(matches, key = { it.index }) { option ->
                        RigRow(
                            option = option,
                            selected = option.index == selectedIndex,
                            onClick = { onSelect(option) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(BgSurface2, shape)
            .border(1.dp, Border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SstvAfIcons.Search(
            modifier = Modifier.size(16.dp),
            color = TextFaint,
            strokeWidth = 1.8f,
        )
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.radio_rig_search_hint),
                    color = TextFaint,
                    fontSize = 14.sp,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            val clearLabel = stringResource(R.string.radio_rig_search_clear)
            Box(
                modifier = Modifier
                    // 48dp of target around a 14dp glyph. onClickLabel, which
                    // this used, changes the spoken action hint but leaves an
                    // icon-only node unnamed, so the description is set as a
                    // real semantic.
                    .size(48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(role = Role.Button) { onQueryChange("") }
                    .semantics { contentDescription = clearLabel },
                contentAlignment = Alignment.Center,
            ) {
                SstvAfIcons.Close(
                    modifier = Modifier.size(14.dp),
                    color = TextMuted,
                    strokeWidth = 1.8f,
                )
            }
        }
    }
}

@Composable
private fun RigRow(option: RigOption, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) AccentSoft else BgSurface2, shape)
            // The check mark beside the current model is a drawn icon, so the
            // selected state is what actually tells a screen reader which rig
            // is configured.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = option.name,
            color = if (selected) Accent else TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            SstvAfIcons.Check(
                modifier = Modifier.size(16.dp),
                color = Accent,
                strokeWidth = 2f,
            )
        }
    }
}
