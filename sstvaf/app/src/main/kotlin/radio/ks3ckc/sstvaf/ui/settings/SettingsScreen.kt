package radio.ks3ckc.sstvaf.ui.settings

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.k1af.ft8af.BuildConfig
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.database.ControlMode
import com.k1af.ft8af.location.GridLocationUpdater
import radio.ks3ckc.sstvaf.theme.TextFaint
import radio.ks3ckc.sstvaf.ui.components.GlassCard
import radio.ks3ckc.sstvaf.ui.components.SettingsRow
import radio.ks3ckc.sstvaf.ui.components.TopBar
import radio.ks3ckc.sstvaf.theme.loadTheme
import radio.ks3ckc.sstvaf.theme.currentThemeNameRes
import androidx.compose.ui.platform.LocalContext
import com.k1af.ft8af.rigs.CatConnectionState

/**
 * Resolves the rig name shown on the operator card.
 *
 * When disconnected, returns [notConnectedLabel]. When connected, returns the
 * trimmed [modelName] (the user-selected model from RigNameList), falling back
 * to "--" when it is blank. We deliberately avoid `baseRig.javaClass.simpleName`:
 * release builds run R8 with the `rigs` package obfuscated, so the class name
 * collapses to a single letter (e.g. "c") and is useless for display.
 */
internal fun resolveRigDisplayName(
    connected: Boolean,
    modelName: String,
    notConnectedLabel: String,
): String {
    if (!connected) return notConnectedLabel
    val trimmed = modelName.trim()
    return trimmed.ifEmpty { "--" }
}

/**
 * Settings screen host. Shows a short category list (landing) and drills down
 * into a focused detail screen per category. Drill-down is driven by internal
 * state (no NavHost) since Settings is hosted as a plain screen over a tab.
 *
 * [currentCategory] uses plain `remember` (not `rememberSaveable`), so leaving
 * Settings and coming back resets to the category list — conventional settings
 * behaviour.
 */
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentCategory by remember { mutableStateOf<SettingsCategory?>(null) }

    // Hardware back pops a detail screen back to the landing. On the landing the
    // handler is disabled so the event propagates up — to the shell, which pops
    // Settings itself (Settings is no longer a tab, it is a screen over one).
    BackHandler(enabled = currentCategory != null) { currentCategory = null }

    AnimatedContent(
        targetState = currentCategory,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState != null) {
                // Entering a detail: slide in from the right, fade out the landing.
                slideInHorizontally(initialOffsetX = { it }) + fadeIn() togetherWith fadeOut()
            } else {
                // Back to the landing: fade it in, slide the detail out to the right.
                fadeIn() togetherWith slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
            }
        },
        label = "settingsDrill",
    ) { category ->
        when (category) {
            null -> SettingsLanding(
                mainViewModel = mainViewModel,
                onBack = onBack,
                onOpenCategory = { currentCategory = it },
            )
            SettingsCategory.RADIO_AUDIO ->
                RadioAudioSettings(mainViewModel, onBack = { currentCategory = null })
            SettingsCategory.TRANSMISSION ->
                TransmissionSettings(mainViewModel, onBack = { currentCategory = null })
            SettingsCategory.LOGGING ->
                LoggingSettings(mainViewModel, onBack = { currentCategory = null })
            SettingsCategory.APPEARANCE ->
                AppearanceSettings(onBack = { currentCategory = null })
            SettingsCategory.USB_DIAGNOSTICS ->
                UsbDiagnosticsScreen(mainViewModel, onBack = { currentCategory = null })
            SettingsCategory.ADVANCED ->
                AdvancedSettings(mainViewModel, onBack = { currentCategory = null })
            SettingsCategory.ABOUT ->
                AboutSettings(mainViewModel, onBack = { currentCategory = null })
        }
    }
}

/**
 * Settings landing page: the operator identity card, the category list with a
 * current value on every row, the two station-wide toggles, and the version
 * footer.
 *
 * The category rows carry their current value so the landing answers most
 * "what is this set to?" questions without being opened, which is the point of
 * the redesign here: the previous landing was six bare labels.
 */
@Composable
private fun SettingsLanding(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Grid is observed so the operator card live-updates after a GPS grid change.
    val gridLive by GeneralVariables.mutableMyMaidenheadGrid.observeAsState(
        GeneralVariables.getMyMaidenheadGrid(),
    )

    var callsignState by remember { mutableStateOf(GeneralVariables.myCallsign.orEmpty()) }
    var antennaState by remember { mutableStateOf(GeneralVariables.myAntenna.orEmpty()) }
    var powerWattsState by remember { mutableIntStateOf(GeneralVariables.myPowerWatts) }
    var autoUpdateGridFromGPS by remember { mutableStateOf(GeneralVariables.autoUpdateGridFromGPS) }
    var saveToPhotos by remember { mutableStateOf(GeneralVariables.saveRxToPhotos) }
    var showEditOperator by remember { mutableStateOf(false) }

    val grid = gridLive.orEmpty()

    // Observed, not a snapshot. isRigConnected() only reports whether the
    // transport is open and does not recompose, so the strip stayed green after
    // the CAT watchdog had already given up on the rig - which is exactly the
    // moment an operator is looking at it. The link state is derived the same
    // way Radio & audio derives it, so the two screens cannot disagree.
    val catState by mainViewModel.mutableCatConnectionState.observeAsState(
        CatConnectionState.DISCONNECTED,
    )
    val controlMode = GeneralVariables.controlMode
    val linkState = rigLinkState(controlMode, catState)
    val rigConnected = linkState == RigLinkState.CONNECTED
    val rigName = resolveRigDisplayName(
        connected = rigConnected,
        // User-selected model name from RigNameList (set in MainViewModel.connectRig).
        // NOT baseRig.javaClass.simpleName: R8 obfuscates the rigs package in release
        // builds, so the class name collapses to a single letter like "c".
        modelName = GeneralVariables.myRigName.orEmpty(),
        notConnectedLabel = stringResource(R.string.common_not_connected),
    )


    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar(title = stringResource(R.string.settings_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OperatorCard(
                callsign = callsignState,
                detailLine = operatorDetailLine(grid, antennaState, powerWattsState),
                rigStatus = rigStatusLine(
                    connected = rigConnected,
                    rigName = rigName,
                    controlLabel = ControlMode.getControlModeStr(controlMode),
                    connectedFormat = stringResource(R.string.settings_rig_status_connected),
                    idleFormat = stringResource(R.string.settings_rig_status_idle),
                ),
                rigConnected = rigConnected,
                onEdit = { showEditOperator = true },
            )

            // -- Categories, each with its current value --
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsCategory.entries.forEachIndexed { index, category ->
                        if (index > 0) SectionDivider()
                        SettingsRow(
                            label = stringResource(categoryLabelRes(category)),
                            description = stringResource(categoryDescriptionRes(category)),
                            value = categoryValue(category, rigName, rigConnected),
                            showChevron = true,
                            onClick = { onOpenCategory(category) },
                        )
                    }
                }
            }

            // -- Station-wide toggles --
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.settings_auto_update_grid),
                        description = stringResource(R.string.settings_auto_update_grid_desc),
                        toggle = autoUpdateGridFromGPS,
                        onToggleChange = { checked ->
                            if (checked) {
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.ACCESS_FINE_LOCATION,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    val activity = context as? Activity
                                    if (activity != null) {
                                        ActivityCompat.requestPermissions(
                                            activity,
                                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                                            42,
                                        )
                                    }
                                }
                            }
                            autoUpdateGridFromGPS = checked
                            GeneralVariables.autoUpdateGridFromGPS = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "autoGridFromGPS", if (checked) "1" else "0", null,
                            )
                            GridLocationUpdater.refresh(context, mainViewModel)
                        },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_save_rx_photos),
                        description = stringResource(R.string.settings_save_rx_photos_desc),
                        toggle = saveToPhotos,
                        onToggleChange = { checked ->
                            saveToPhotos = checked
                            GeneralVariables.saveRxToPhotos = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "saveRxToPhotos", if (checked) "1" else "0", null,
                            )
                        },
                    )
                }
            }

            Text(
                text = stringResource(R.string.settings_footer, BuildConfig.VERSION_NAME),
                color = TextFaint,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            // Bottom spacer for scroll overscroll / nav bar inset.
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // After the content, so the sheet draws over it: unlike a dialog, a bottom
    // sheet is part of this layout rather than a window of its own.
    OperatorSheet(
        visible = showEditOperator,
        initialCallsign = callsignState,
        initialGrid = grid,
        initialAntenna = antennaState,
        initialPowerWatts = powerWattsState,
        onDismiss = { showEditOperator = false },
        onSave = { newCallsign, newGrid, newAntenna, newPowerWatts ->
            // Values arrive already normalised from the sheet.
            callsignState = newCallsign
            GeneralVariables.myCallsign = newCallsign
            mainViewModel.databaseOpr.writeConfig("callsign", newCallsign, null)

            GeneralVariables.setMyMaidenheadGrid(newGrid)
            mainViewModel.databaseOpr.writeConfig("grid", newGrid, null)

            antennaState = newAntenna
            GeneralVariables.myAntenna = newAntenna
            mainViewModel.databaseOpr.writeConfig("antenna", newAntenna, null)

            powerWattsState = newPowerWatts
            GeneralVariables.myPowerWatts = newPowerWatts
            mainViewModel.databaseOpr.writeConfig("powerWatts", newPowerWatts.toString(), null)

            showEditOperator = false
        },
    )
}

/**
 * The current-value summary shown on the right of a category row.
 *
 * Deliberately one short fact per category rather than a full summary: the row
 * has to stay one line on a compact phone, and the value is there to confirm a
 * setting at a glance, not to replace the screen behind it.
 */
@Composable
private fun categoryValue(
    category: SettingsCategory,
    rigName: String,
    rigConnected: Boolean,
): String = when (category) {
    SettingsCategory.RADIO_AUDIO -> rigName
    // The tune method is the only setting this screen holds, so it is the
    // value.
    SettingsCategory.TRANSMISSION -> stringResource(tuneMethodNameRes(GeneralVariables.tuneMethod))
    // Cloudlog upload is the consequential one: on means every QSO leaves the
    // device.
    SettingsCategory.LOGGING -> if (GeneralVariables.enableCloudlog) {
        stringResource(R.string.settings_value_cloudlog_on)
    } else {
        stringResource(R.string.settings_value_off)
    }
    SettingsCategory.APPEARANCE -> stringResource(currentThemeNameRes(loadTheme(LocalContext.current)))
    // PTT delay is the one an operator comes back to adjust.
    SettingsCategory.ADVANCED -> stringResource(
        R.string.settings_milliseconds_format, GeneralVariables.pttDelay,
    )
    SettingsCategory.USB_DIAGNOSTICS -> if (rigConnected) {
        stringResource(R.string.settings_value_usb_ok)
    } else {
        stringResource(R.string.settings_value_no_link)
    }
    SettingsCategory.ABOUT -> BuildConfig.VERSION_NAME
}
