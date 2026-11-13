package radio.ks3ckc.sstvaf.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import com.k1af.ft8af.R
import radio.ks3ckc.sstvaf.theme.ThemeOption
import radio.ks3ckc.sstvaf.theme.applyTheme
import radio.ks3ckc.sstvaf.theme.currentThemeNameRes
import radio.ks3ckc.sstvaf.theme.loadTheme
import radio.ks3ckc.sstvaf.theme.saveTheme
import radio.ks3ckc.sstvaf.ui.components.GlassCard
import radio.ks3ckc.sstvaf.ui.components.SettingsRow

/**
 * Appearance settings: theme and language.
 *
 * Its own screen rather than two sections buried in Advanced. The redesign
 * lists Appearance as a landing category, and an operator looking for the
 * theme or the app language has no reason to guess that "Advanced" is where
 * they live — Advanced is otherwise PTT timing, CW identification and backup,
 * which is a different kind of setting entirely.
 */
@Composable
internal fun AppearanceSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    var currentTheme by remember { mutableStateOf(loadTheme(context)) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    // Selecting a theme applies it live (swaps the Compose palette + night
    // mode, no activity recreate) and persists the choice.
    if (showThemePicker) {
        val themes = ThemeOption.entries
        val themeLabels = ArrayList<String>(themes.size)
        for (theme in themes) {
            themeLabels.add(stringResource(theme.nameRes))
        }
        ListPickerDialog(
            title = stringResource(R.string.settings_theme),
            items = themeLabels,
            selectedIndex = themes.indexOf(currentTheme).coerceAtLeast(0),
            onDismiss = { showThemePicker = false },
            onSelect = { index ->
                showThemePicker = false
                val theme = themes[index]
                currentTheme = theme
                applyTheme(theme)
                saveTheme(context, theme)
            },
        )
    }

    // Choosing a language calls AppCompatDelegate.setApplicationLocales, which
    // persists the choice (framework LocaleManager on API 33+, AppCompat
    // autoStore backport on older) and recreates the activity so the new locale
    // takes effect immediately.
    if (showLanguagePicker) {
        val languageTags = LANGUAGE_TAGS
        // Built with a for-loop (not map/forEach) so the @Composable
        // stringResource calls run in a permitted context; mirrors
        // LANGUAGE_TAGS index-for-index.
        val languageLabels = ArrayList<String>(LANGUAGE_TAGS.size)
        languageLabels.add(stringResource(R.string.settings_language_system))
        for (lang in SUPPORTED_LANGUAGES) {
            languageLabels.add(stringResource(lang.nameRes))
        }
        val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val currentIndex = languageTags
            .indexOfFirst { it.isNotEmpty() && currentTags.startsWith(it) }
            .let { if (it >= 0) it else 0 }
        ListPickerDialog(
            title = stringResource(R.string.settings_language),
            items = languageLabels,
            selectedIndex = currentIndex,
            onDismiss = { showLanguagePicker = false },
            onSelect = { index ->
                showLanguagePicker = false
                val tag = languageTags[index]
                val locales = if (tag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(tag)
                }
                AppCompatDelegate.setApplicationLocales(locales)
            },
        )
    }

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_cat_appearance),
        onBack = onBack,
    ) {
        SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    label = stringResource(R.string.settings_theme),
                    description = stringResource(R.string.settings_theme_desc),
                    value = stringResource(currentThemeNameRes(currentTheme)),
                    showChevron = true,
                    onClick = { showThemePicker = true },
                )
            }
        }

        SettingsSection(title = stringResource(R.string.settings_section_language)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                SettingsRow(
                    label = stringResource(R.string.settings_language),
                    description = stringResource(R.string.settings_language_desc),
                    value = stringResource(currentLanguageNameRes(currentTags)),
                    showChevron = true,
                    onClick = { showLanguagePicker = true },
                )
            }
        }
    }
}
