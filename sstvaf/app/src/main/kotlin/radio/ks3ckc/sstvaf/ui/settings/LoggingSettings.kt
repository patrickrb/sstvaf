package radio.ks3ckc.sstvaf.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.log.ThirdPartyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import radio.ks3ckc.sstvaf.theme.*
import radio.ks3ckc.sstvaf.ui.components.GlassCard
import radio.ks3ckc.sstvaf.ui.components.SettingsRow

/**
 * Logging & awards settings: Cloudlog integration.
 */
@Composable
fun LoggingSettings(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
) {
    var enableCloudlog by remember { mutableStateOf(GeneralVariables.enableCloudlog) }
    var cloudlogAddress by remember { mutableStateOf(GeneralVariables.cloudlogServerAddress.orEmpty()) }

    var showCloudlog by remember { mutableStateOf(false) }

    // -- Cloudlog Settings Dialog --
    if (showCloudlog) {
        CloudlogSettingsDialog(
            initialAddress = GeneralVariables.cloudlogServerAddress.orEmpty(),
            initialApiKey = GeneralVariables.cloudlogApiKey.orEmpty(),
            initialStationId = GeneralVariables.cloudlogStationID.orEmpty(),
            onDismiss = { showCloudlog = false },
            onSave = { address, apiKey, stationId ->
                GeneralVariables.cloudlogServerAddress = address
                GeneralVariables.cloudlogApiKey = apiKey
                GeneralVariables.cloudlogStationID = stationId
                cloudlogAddress = address
                mainViewModel.databaseOpr.writeConfig("cloudlogServerAddress", address, null)
                mainViewModel.databaseOpr.writeConfig("cloudlogApiKey", apiKey, null)
                mainViewModel.databaseOpr.writeConfig("cloudlogStationID", stationId, null)
                showCloudlog = false
            },
        )
    }

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_cat_logging),
        onBack = onBack,
    ) {
        SettingsSection(title = stringResource(R.string.settings_section_logging_awards)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.settings_cloudlog),
                        description = stringResource(R.string.settings_cloudlog_desc),
                        value = cloudlogAddress.ifEmpty { stringResource(R.string.common_not_configured) },
                        toggle = enableCloudlog,
                        onToggleChange = { checked ->
                            enableCloudlog = checked
                            GeneralVariables.enableCloudlog = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "enableCloudlog", if (checked) "1" else "0", null,
                            )
                        },
                        showChevron = true,
                        onClick = { showCloudlog = true },
                    )
                    SectionDivider()
                    run {
                        var saveToPhotos by remember {
                            mutableStateOf(GeneralVariables.saveRxToPhotos)
                        }
                        SettingsRow(
                            label = stringResource(R.string.settings_save_rx_photos),
                            description = stringResource(R.string.settings_save_rx_photos_desc),
                            toggle = saveToPhotos,
                            onToggleChange = { enabled ->
                                saveToPhotos = enabled
                                GeneralVariables.saveRxToPhotos = enabled
                                mainViewModel.databaseOpr.writeConfig(
                                    "saveRxToPhotos", if (enabled) "1" else "0", null,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog for configuring Cloudlog server address, API key, and station ID.
 * Includes a Test Connection button that calls [ThirdPartyService.CheckCloudlogConnection].
 */
@Composable
private fun CloudlogSettingsDialog(
    initialAddress: String,
    initialApiKey: String,
    initialStationId: String,
    onDismiss: () -> Unit,
    onSave: (address: String, apiKey: String, stationId: String) -> Unit,
) {
    var addressInput by remember { mutableStateOf(TextFieldValue(initialAddress)) }
    var apiKeyInput by remember { mutableStateOf(TextFieldValue(initialApiKey)) }
    var stationIdInput by remember { mutableStateOf(TextFieldValue(initialStationId)) }

    // Test connection state: null = idle, true = pass, false = fail
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Station picker state
    var stationList by remember { mutableStateOf<List<ThirdPartyService.StationProfile>>(emptyList()) }
    var isFetchingStations by remember { mutableStateOf(false) }
    var manualStationEntry by remember { mutableStateOf(false) }
    var showStationPicker by remember { mutableStateOf(false) }

    // Auto-fetch stations on dialog open if credentials are present
    LaunchedEffect(Unit) {
        val addr = initialAddress.trim()
        val key = initialApiKey.trim()
        if (addr.isNotBlank() && key.isNotBlank()) {
            isFetchingStations = true
            val result = withContext(Dispatchers.IO) {
                ThirdPartyService.FetchCloudlogStations(addr, key)
            }
            stationList = result
            isFetchingStations = false
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Accent,
        focusedBorderColor = Accent,
        unfocusedBorderColor = BorderStrong,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextMuted,
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_logging_server),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            Text(
                text = stringResource(R.string.settings_logging_server_desc),
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )

            OutlinedTextField(
                value = addressInput,
                onValueChange = {
                    addressInput = it
                    testResult = null
                    stationList = emptyList()
                },
                label = { Text(stringResource(R.string.settings_server_address)) },
                placeholder = { Text("https://log.example.com/", color = TextFaint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = fieldColors,
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = {
                    apiKeyInput = it
                    testResult = null
                    stationList = emptyList()
                },
                label = { Text(stringResource(R.string.settings_api_key)) },
                placeholder = { Text(stringResource(R.string.settings_api_key_hint), color = TextFaint) },
                singleLine = true,
                colors = fieldColors,
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )

            // Station ID: picker when stations are loaded, manual entry otherwise
            if (isFetchingStations) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp).height(16.dp),
                        strokeWidth = 2.dp,
                        color = Accent,
                    )
                    Text(
                        text = stringResource(R.string.settings_loading_stations),
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                }
            } else if (stationList.isNotEmpty() && !manualStationEntry) {
                // Picker mode: show selected station as a clickable read-only field
                val selectedLabel = stationList
                    .firstOrNull { it.stationId == stationIdInput.text }
                    ?.displayLabel()
                    ?: stationIdInput.text.ifBlank { stringResource(R.string.settings_select_a_station) }

                Column {
                    Text(
                        text = stringResource(R.string.settings_station_id),
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = selectedLabel,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(BgSurface3)
                            .clickable { showStationPicker = true }
                            .padding(12.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_enter_manually),
                        color = Accent,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { manualStationEntry = true },
                    )
                }
            } else {
                // Manual entry mode
                OutlinedTextField(
                    value = stationIdInput,
                    onValueChange = { stationIdInput = it },
                    label = { Text(stringResource(R.string.settings_station_id)) },
                    placeholder = { Text(stringResource(R.string.settings_station_id_hint), color = TextFaint) },
                    singleLine = true,
                    colors = fieldColors,
                    textStyle = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (stationList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_choose_from_server),
                        color = Accent,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { manualStationEntry = false },
                    )
                }
            }

            // Test Connection button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = {
                        // Write current input values to GeneralVariables so the test uses them
                        GeneralVariables.cloudlogServerAddress = addressInput.text
                        GeneralVariables.cloudlogApiKey = apiKeyInput.text
                        GeneralVariables.cloudlogStationID = stationIdInput.text
                        isTesting = true
                        testResult = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                ThirdPartyService.CheckCloudlogConnection()
                            }
                            testResult = result
                            isTesting = false
                            // On success, also fetch station profiles
                            if (result) {
                                isFetchingStations = true
                                val stations = withContext(Dispatchers.IO) {
                                    ThirdPartyService.FetchCloudlogStations(
                                        addressInput.text.trim(),
                                        apiKeyInput.text.trim(),
                                    )
                                }
                                stationList = stations
                                isFetchingStations = false
                            }
                        }
                    },
                    enabled = !isTesting,
                ) {
                    Text(
                        text = stringResource(R.string.common_test_connection),
                        color = if (isTesting) TextFaint else Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(16.dp)
                            .height(16.dp),
                        strokeWidth = 2.dp,
                        color = Accent,
                    )
                }
                if (testResult != null) {
                    Text(
                        text = if (testResult == true) stringResource(R.string.common_pass)
                        else stringResource(R.string.common_fail),
                        color = if (testResult == true) StatusConfirmed else StatusBad,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(
                    onClick = {
                        onSave(
                            addressInput.text.trim(),
                            apiKeyInput.text.trim(),
                            stationIdInput.text.trim(),
                        )
                    },
                ) {
                    Text(stringResource(R.string.action_save), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    // Station picker dialog
    if (showStationPicker && stationList.isNotEmpty()) {
        val items = stationList.map { it.displayLabel() }
        val selectedIdx = stationList.indexOfFirst { it.stationId == stationIdInput.text }
        ListPickerDialog(
            title = stringResource(R.string.settings_station_profile),
            items = items,
            selectedIndex = selectedIdx.coerceAtLeast(0),
            onDismiss = { showStationPicker = false },
            onSelect = { index ->
                stationIdInput = TextFieldValue(stationList[index].stationId)
                showStationPicker = false
            },
        )
    }
}
