package radio.ks3ckc.sstvaf

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.connector.ConnectMode
import com.k1af.ft8af.database.OperationBand
import com.k1af.ft8af.database.RigNameList
import com.k1af.ft8af.rigs.CatConnectionState
import com.k1af.ft8af.rigs.BaseRigOperation
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.ui.components.AdaptiveShell
import radio.ks3ckc.sstvaf.ui.components.AppHeader
import radio.ks3ckc.sstvaf.ui.components.FrequencyPickerSheet
import radio.ks3ckc.sstvaf.ui.components.MoreDestination
import radio.ks3ckc.sstvaf.ui.components.MoreSheet
import radio.ks3ckc.sstvaf.ui.components.SstvTab
import radio.ks3ckc.sstvaf.ui.components.TabBar
import radio.ks3ckc.sstvaf.ui.components.TabRail
import radio.ks3ckc.sstvaf.ui.components.TransmitGlow
import radio.ks3ckc.sstvaf.ui.components.controlModeLabel
import radio.ks3ckc.sstvaf.ui.components.frequencyChipLabel
import radio.ks3ckc.sstvaf.ui.components.headerCatDotColor
import radio.ks3ckc.sstvaf.ui.components.operatorSummaryLine
import radio.ks3ckc.sstvaf.ui.components.radioSummaryLine
import radio.ks3ckc.sstvaf.ui.components.selectBandIndex
import radio.ks3ckc.sstvaf.ui.gallery.GalleryScreen
import radio.ks3ckc.sstvaf.ui.logbook.LogbookScreen
import radio.ks3ckc.sstvaf.ui.rx.RxScreen
import radio.ks3ckc.sstvaf.ui.settings.RadioAudioSettings
import radio.ks3ckc.sstvaf.ui.settings.SettingsScreen
import radio.ks3ckc.sstvaf.ui.tx.TxComposeScreen

/**
 * A full screen reached from the header's overflow sheet. These cover the whole
 * canvas — no tab bar, no dial chip — because they are places you go to read or
 * configure, not surfaces you operate a radio from.
 */
private enum class AppScreen { SETTINGS, RADIO_AUDIO, LOGBOOK }

/** Which bottom sheet the shell is showing, if any. */
private enum class AppSheet { FREQUENCY, MORE }

/**
 * The app shell: a Receive / Send / Gallery tab bar under a header carrying the
 * dial and an overflow button.
 *
 * Down from six tabs and a permanent TX strip. The strip is gone entirely (see
 * [AppHeader]) and Waterfall with it — SSTV puts its mode in the VIS header, so
 * the decoder reads the mode itself and there was nothing on that screen for an
 * operator to act on. Logbook and Settings moved into the overflow sheet
 * ([MoreSheet]), which leaves the bar carrying only the three screens an
 * operator actually works from.
 *
 * Navigation is three pieces of state: which [SstvTab] is active, which
 * [AppScreen] is covering it (if any), and which [AppSheet] is open (if any).
 * No NavHost — the tab content is a plain swap, as it has always been here.
 */
@Composable
fun SstvAfApp(mainViewModel: MainViewModel) {
    val context = LocalContext.current
    var activeTab by rememberSaveable { mutableStateOf(SstvTab.RX) }
    var activeScreen by rememberSaveable { mutableStateOf<AppScreen?>(null) }
    var activeSheet by rememberSaveable { mutableStateOf<AppSheet?>(null) }

    // Tune carrier state. TUNE now lives in the Frequency sheet rather than on a
    // permanently-visible strip, so it can't be hit by a stray thumb mid-QSO.
    val isTuning by mainViewModel.tuneOperator.mutableIsTuning.observeAsState(false)
    val tuneRemainingSec by mainViewModel.tuneOperator.mutableTuneRemainingSec.observeAsState(0)

    // CAT connection status for the header's dot. Observed control mode so the
    // dot re-colours the moment the user switches VOX <-> CAT/RTS/DTR.
    val catState by mainViewModel.mutableCatConnectionState.observeAsState(CatConnectionState.DISCONNECTED)
    val controlMode by GeneralVariables.mutableControlMode.observeAsState(GeneralVariables.controlMode)

    // TX level — observe LiveData so hardware buttons, ALC auto-volume, and the
    // settings slider all keep the sheet's slider in step.
    val volumeLive by GeneralVariables.mutableVolumePercent.observeAsState(
        GeneralVariables.volumePercent,
    )
    var txLevel by remember { mutableIntStateOf((GeneralVariables.volumePercent * 100).toInt()) }
    LaunchedEffect(volumeLive) {
        txLevel = ((volumeLive ?: GeneralVariables.volumePercent) * 100).toInt()
    }

    // The dial, for the header chip and the sheet's selected row. bandIndex is
    // observed so both recompose when the operator (or the rig) retunes.
    val bandIndex by GeneralVariables.mutableBandChange.observeAsState(GeneralVariables.bandListIndex)
    val freqHz = GeneralVariables.band
    val bandName = OperationBand.bandList.getOrNull(bandIndex)?.waveLength
        ?: OperationBand.bandList.firstOrNull { it.band == freqHz }?.waveLength
        ?: BaseRigOperation.getMeterFromFreq(freqHz)
        ?: ""
    val frequencyLabel = frequencyChipLabel(freqHz, bandName)
    val catDotColor = headerCatDotColor(controlMode, catState)

    // Live rig summary for the Frequency sheet's status line and the More
    // sheet's radio row.
    val rigNameList = remember { RigNameList.getInstance(context) }
    val rigName = remember(GeneralVariables.modelNo) {
        rigNameList.getRigNameByIndex(GeneralVariables.modelNo).name
    }
    val controlLabel = controlModeLabel(controlMode)
    val radioSummary = radioSummaryLine(
        rigName = rigName,
        connectionLabel = ConnectMode.getModeStr(GeneralVariables.connectMode),
        controlLabel = controlLabel,
    )
    val gridLive by GeneralVariables.mutableMyMaidenheadGrid.observeAsState(
        GeneralVariables.getMyMaidenheadGrid(),
    )
    val operatorSummary = operatorSummaryLine(
        callsign = GeneralVariables.myCallsign.orEmpty(),
        grid = gridLive.orEmpty(),
        fallbackCallsign = stringResource(R.string.op_no_call),
    )

    // Observe SWR lockout state
    val swrLocked by mainViewModel.meterProtectionController.swrLockout.observeAsState(false)
    val lockoutSwrRatio by mainViewModel.meterProtectionController.lockoutSwrRatio.observeAsState("")

    // Wide canvases (tablets in either orientation, phones in landscape) move
    // navigation to a side rail so the short landscape content area keeps its
    // full height instead of losing it to the bottom bar (issue #20).
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val useRail = AdaptiveShell.useNavigationRail(screenWidthDp)

    // Back pops the screen layer before it reaches the activity's exit handler.
    // Sheets install their own handler (see SstvAfBottomSheet), so an open sheet
    // consumes Back first and this only fires once the sheet is gone.
    BackHandler(enabled = activeScreen != null && activeSheet == null) { activeScreen = null }

    // The tab content is wrapped in movableContentOf so switching between the
    // bottom-bar and rail layouts (e.g. on rotation) re-parents the live screen
    // and its scroll/decode state instead of recomposing it from scratch. The
    // content [Box]'s weight modifier is applied at each call site because
    // `Modifier.weight` is only in scope inside the enclosing Column.
    //
    // Only the content is movable. The header is stateless and is called
    // directly in each layout branch: a `remember`ed movableContentOf closes
    // over the values it captured on FIRST composition, so a header built that
    // way would show the frequency and CAT colour from app start and never
    // update. The content lambda captures nothing but `activeTab` (a snapshot
    // state read, so it stays live) and the view model.
    val content = remember {
        movableContentOf {
            // Note: tab switching here is a plain swap, not AnimatedContent — the
            // RX canvas animates continuously and fighting it with graphicsLayer
            // translations during an enter/exit transition looked wrong. The
            // TabBar/TabRail selection itself still animates.
            when (activeTab) {
                SstvTab.RX -> RxScreen(
                    mainViewModel,
                    onViewInGallery = { activeTab = SstvTab.GALLERY },
                )
                SstvTab.TX -> TxComposeScreen(mainViewModel)
                SstvTab.GALLERY -> GalleryScreen(mainViewModel)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BgApp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // SWR lockout banner — red warning spanning the top in every layout,
            // including over a full screen: the rig refusing to transmit outranks
            // whatever the operator is currently reading.
            if (swrLocked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFCC2222))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.swr_lockout_title, lockoutSwrRatio),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = stringResource(R.string.swr_lockout_body),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            mainViewModel.meterProtectionController.clearSwrLockout()
                        },
                    ) {
                        Text(
                            stringResource(R.string.swr_lockout_dismiss),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            val screen = activeScreen
            if (screen != null) {
                // Full screen: it owns its own chrome (title + back chevron), and
                // neither the tab bar nor the dial chip is shown.
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        AppScreen.SETTINGS -> SettingsScreen(
                            mainViewModel,
                            onBack = { activeScreen = null },
                        )
                        AppScreen.RADIO_AUDIO -> RadioAudioSettings(
                            mainViewModel,
                            onBack = { activeScreen = null },
                        )
                        AppScreen.LOGBOOK -> LogbookScreen(
                            mainViewModel,
                            onBack = { activeScreen = null },
                        )
                    }
                }
            } else if (useRail) {
                // Wide layout: rail on the left, header + content fill the rest.
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    TabRail(
                        activeTab = activeTab,
                        onTabSelected = { activeTab = it; activeSheet = null },
                    )
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        AppHeader(
                            title = stringResource(activeTab.labelRes),
                            frequencyLabel = frequencyLabel,
                            catDotColor = catDotColor,
                            onOpenFrequency = { activeSheet = AppSheet.FREQUENCY },
                            onOpenMore = { activeSheet = AppSheet.MORE },
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
                    }
                }
            } else {
                // Compact layout: header, content, then the bottom tab bar.
                AppHeader(
                    title = stringResource(activeTab.labelRes),
                    frequencyLabel = frequencyLabel,
                    catDotColor = catDotColor,
                    onOpenFrequency = { activeSheet = AppSheet.FREQUENCY },
                    onOpenMore = { activeSheet = AppSheet.MORE },
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
                TabBar(
                    activeTab = activeTab,
                    onTabSelected = { activeTab = it; activeSheet = null },
                )
            }
        }

        // Transmit breathing border — a sibling overlay so its per-frame
        // invalidations don't bubble into the screen below. Pointer events pass
        // through. The tune carrier is a real transmission (issue #408): the
        // operator must never be unsure whether the rig is keyed.
        TransmitGlow(isTransmitting = isTuning)

        // Sheets are siblings of the whole shell so their scrim covers the tab
        // bar and header too.
        FrequencyPickerSheet(
            visible = activeSheet == AppSheet.FREQUENCY,
            currentFreqHz = freqHz,
            catStatusLabel = radioSummary,
            catDotColor = catDotColor,
            txLevel = txLevel,
            isTuning = isTuning,
            tuneRemainingSec = tuneRemainingSec,
            tuneMaxSeconds = GeneralVariables.tuneMaxOnSeconds,
            onDismiss = { activeSheet = null },
            onSelectBandIndex = { idx ->
                selectBandIndex(mainViewModel, context, idx)
                activeSheet = null
            },
            onTxLevelChange = { newLevel ->
                txLevel = newLevel
                GeneralVariables.volumePercent = newLevel / 100f
                GeneralVariables.mutableVolumePercent.postValue(newLevel / 100f)
            },
            onTxLevelChangeFinished = {
                mainViewModel.databaseOpr.writeConfig("volumeValue", txLevel.toString(), null)
                mainViewModel.baseRig?.connector?.setRFVolume(txLevel)
                saveOutputLevelForCurrentBand(mainViewModel.databaseOpr, txLevel)
            },
            onToggleTune = {
                // Toggle (WSJT-X style latching Tune): tap to key the carrier,
                // tap again to stop. startTune() toasts the reason when blocked.
                if (isTuning) {
                    mainViewModel.tuneOperator.stopTune()
                } else {
                    mainViewModel.startTune()
                }
            },
        )

        MoreSheet(
            visible = activeSheet == AppSheet.MORE,
            radioSummary = radioSummary,
            operatorSummary = operatorSummary,
            onDismiss = { activeSheet = null },
            onNavigate = { destination ->
                activeSheet = null
                activeScreen = when (destination) {
                    MoreDestination.RADIO_AUDIO -> AppScreen.RADIO_AUDIO
                    // Operator identity lives on the Settings landing, as the card
                    // at the top; it gets its own sheet when Settings is rebuilt.
                    MoreDestination.OPERATOR -> AppScreen.SETTINGS
                    MoreDestination.LOGBOOK -> AppScreen.LOGBOOK
                    MoreDestination.SETTINGS -> AppScreen.SETTINGS
                }
            },
        )
    }
}
