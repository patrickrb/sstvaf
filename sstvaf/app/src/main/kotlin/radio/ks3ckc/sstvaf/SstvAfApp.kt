package radio.ks3ckc.sstvaf

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
import com.k1af.ft8af.database.OperationBand
import com.k1af.ft8af.rigs.CatConnectionState
import com.k1af.ft8af.rigs.BaseRigOperation
import radio.ks3ckc.sstvaf.theme.BgApp
import radio.ks3ckc.sstvaf.ui.components.AdaptiveShell
import radio.ks3ckc.sstvaf.ui.components.shouldShowCatChip
import radio.ks3ckc.sstvaf.ui.components.SstvTab
import radio.ks3ckc.sstvaf.ui.components.FrequencyPickerSheet
import radio.ks3ckc.sstvaf.ui.components.formatMhz
import radio.ks3ckc.sstvaf.ui.components.TabBar
import radio.ks3ckc.sstvaf.ui.components.TabRail
import radio.ks3ckc.sstvaf.ui.components.TransmitGlow
import radio.ks3ckc.sstvaf.ui.components.TxStrip
import radio.ks3ckc.sstvaf.ui.components.selectBandIndex
import radio.ks3ckc.sstvaf.ui.gallery.GalleryScreen
import radio.ks3ckc.sstvaf.ui.logbook.LogbookScreen
import radio.ks3ckc.sstvaf.ui.rx.RxScreen
import radio.ks3ckc.sstvaf.ui.settings.SettingsScreen
import radio.ks3ckc.sstvaf.ui.tx.TxComposeScreen
import radio.ks3ckc.sstvaf.ui.waterfall.WaterfallScreen

/**
 * The app shell: RX / GALLERY / WATERFALL / LOG / SETTINGS tabs above the TX
 * strip and tab bar. RX (the live SSTV decode view, PR 6) is the landing tab,
 * GALLERY (PR 7) browses the saved images; the only transmitter for now is
 * the Tune carrier, and SSTV TX lands in PR 8 on top of the same rig/audio
 * plumbing.
 */
@Composable
fun SstvAfApp(mainViewModel: MainViewModel) {
    val context = LocalContext.current
    var activeTab by rememberSaveable { mutableStateOf(SstvTab.RX) }

    // Tune carrier state — the only TX source in the radio shell.
    val isTuning by mainViewModel.tuneOperator.mutableIsTuning.observeAsState(false)
    val tuneRemainingSec by mainViewModel.tuneOperator.mutableTuneRemainingSec.observeAsState(0)

    // CAT connection status for the TX-strip chip. Hidden for VOX / audio-only
    // setups (see shouldShowCatChip); tap reconnects (handy for Bluetooth, which
    // often only connects on the second attempt).
    val catState by mainViewModel.mutableCatConnectionState.observeAsState(CatConnectionState.DISCONNECTED)
    // Observe control mode so the chip shows/hides immediately when the user
    // switches VOX <-> CAT/RTS/DTR in Settings (seeds from the current value).
    val controlMode by GeneralVariables.mutableControlMode.observeAsState(GeneralVariables.controlMode)
    val showCatChip = shouldShowCatChip(controlMode, catState)

    // TX Volume state — observe LiveData so hardware buttons, ALC auto-volume,
    // and the settings slider all update the inline slider bidirectionally.
    val volumeLive by GeneralVariables.mutableVolumePercent.observeAsState(
        GeneralVariables.volumePercent,
    )
    var txVolume by remember { mutableIntStateOf((GeneralVariables.volumePercent * 100).toInt()) }
    LaunchedEffect(volumeLive) {
        txVolume = ((volumeLive ?: GeneralVariables.volumePercent) * 100).toInt()
    }

    // Inline volume slider visibility — observed so toggling in Settings
    // immediately shows/hides the slider on the main screen.
    val showVolumeSliderLive by GeneralVariables.mutableShowTxVolumeSlider.observeAsState(
        GeneralVariables.showTxVolumeSlider,
    )
    var showVolumeSlider by remember { mutableStateOf(GeneralVariables.showTxVolumeSlider) }
    LaunchedEffect(showVolumeSliderLive) {
        showVolumeSlider = showVolumeSliderLive ?: GeneralVariables.showTxVolumeSlider
    }

    // Frequency picker sheet state
    var showFrequencyPicker by rememberSaveable { mutableStateOf(false) }

    // Pill label combines MHz frequency and band name, e.g. "14.230 MHz · 20m".
    // bandIndex is observed so the pill recomposes when the user retunes.
    val bandIndex by GeneralVariables.mutableBandChange.observeAsState(GeneralVariables.bandListIndex)
    val freq = GeneralVariables.band
    val bandName = OperationBand.bandList.getOrNull(bandIndex)?.waveLength
        ?: OperationBand.bandList.firstOrNull { it.band == freq }?.waveLength
        ?: BaseRigOperation.getMeterFromFreq(freq)
        ?: ""
    val frequencyLabel = buildString {
        append(formatMhz(freq))
        append(" MHz")
        if (bandName.isNotBlank()) {
            append(" · ")
            append(bandName)
        }
    }

    // Observe SWR lockout state
    val swrLocked by mainViewModel.meterProtectionController.swrLockout.observeAsState(false)
    val lockoutSwrRatio by mainViewModel.meterProtectionController.lockoutSwrRatio.observeAsState("")

    // Wide canvases (tablets in either orientation, phones in landscape) move
    // navigation to a side rail so the short landscape content area keeps its
    // full height instead of losing it to the bottom bar + TX strip (issue #20).
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val useRail = AdaptiveShell.useNavigationRail(screenWidthDp)

    // Content + TX strip are wrapped in movableContentOf so switching between the
    // bottom-bar and rail layouts (e.g. on rotation) re-parents the live screen
    // and its scroll/decode state instead of recomposing it from scratch. The
    // content [Box]'s weight modifier is applied at each call site because
    // `Modifier.weight` is only in scope inside the enclosing Column.
    val content = remember {
        movableContentOf {
            // Note: AndroidView-wrapped legacy views (waterfall/columnar) interact badly with
            // AnimatedContent's graphicsLayer translations during enter/exit, so tab switching
            // here is a plain swap. The TabBar/TabRail selection itself still animates.
            when (activeTab) {
                SstvTab.RX -> RxScreen(
                    mainViewModel,
                    onViewInGallery = { activeTab = SstvTab.GALLERY },
                )
                SstvTab.GALLERY -> GalleryScreen(mainViewModel)
                SstvTab.TX -> TxComposeScreen(mainViewModel)
                SstvTab.WATERFALL -> WaterfallScreen(mainViewModel)
                SstvTab.LOG -> LogbookScreen(mainViewModel)
                SstvTab.SETTINGS -> SettingsScreen(mainViewModel)
            }
        }
    }
    val txStrip = remember {
        movableContentOf {
            // TX status strip — always visible above the bottom bar / below content.
            TxStrip(
                isTransmitting = isTuning,
                frequencyLabel = frequencyLabel,
                catState = catState,
                showCatChip = showCatChip,
                txVolume = txVolume,
                showVolumeSlider = showVolumeSlider,
                isTuning = isTuning,
                tuneRemainingSec = tuneRemainingSec,
                onToggleTune = {
                    // Toggle (WSJT-X style latching Tune): tap to key the carrier,
                    // tap again to stop. startTune() toasts the reason when blocked.
                    if (isTuning) {
                        mainViewModel.tuneOperator.stopTune()
                    } else {
                        mainViewModel.tuneOperator.startTune()
                    }
                },
                onVolumeChange = { newVolume ->
                    txVolume = newVolume
                    GeneralVariables.volumePercent = newVolume / 100f
                    GeneralVariables.mutableVolumePercent.postValue(newVolume / 100f)
                },
                onVolumeChangeFinished = {
                    mainViewModel.databaseOpr.writeConfig("volumeValue", txVolume.toString(), null)
                    mainViewModel.baseRig?.connector?.setRFVolume(txVolume)
                    saveOutputLevelForCurrentBand(mainViewModel.databaseOpr, txVolume)
                },
                onReconnectCat = { mainViewModel.reconnectRig() },
                onOpenFrequencyPicker = { showFrequencyPicker = true },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BgApp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // SWR lockout banner — red warning spanning the top in both layouts.
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

            if (useRail) {
                // Wide layout: rail on the left, content + TX strip fill the rest.
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    TabRail(
                        activeTab = activeTab,
                        onTabSelected = { activeTab = it },
                    )
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
                        txStrip()
                    }
                }
            } else {
                // Compact layout: content, TX strip, then the bottom tab bar.
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
                txStrip()
                TabBar(
                    activeTab = activeTab,
                    onTabSelected = { activeTab = it },
                )
            }
        }

        // Transmit breathing border — sibling overlay so its per-frame invalidations
        // don't bubble into the waterfall composable. Pointer events pass through.
        // The tune carrier is a real transmission (issue #408): the operator must
        // never be unsure whether the rig is keyed.
        TransmitGlow(isTransmitting = isTuning)

        // Frequency picker — sibling overlay so the scrim and sheet sit above the
        // tab bar and TxStrip.
        FrequencyPickerSheet(
            visible = showFrequencyPicker,
            currentBandIndex = bandIndex,
            onDismiss = { showFrequencyPicker = false },
            onSelect = { idx ->
                selectBandIndex(mainViewModel, context, idx)
                showFrequencyPicker = false
            },
        )
    }
}
