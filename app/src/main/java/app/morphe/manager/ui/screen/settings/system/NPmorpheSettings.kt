/*
 * Fork-specific settings for NPmorphe.
 */

package app.morphe.manager.ui.screen.settings.system

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.R
import app.morphe.manager.domain.manager.ApkBrowserPreference
import app.morphe.manager.domain.repository.PatchHistoryRepository
import app.morphe.manager.ui.screen.home.PatchHistoryDialog
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.SettingsViewModel
import app.morphe.manager.ui.viewmodel.StorageManagementViewModel
import app.morphe.manager.util.APK_BROWSER_DEFAULT_URL
import app.morphe.manager.util.CUSTOM_DEVELOPER_NAME
import app.morphe.manager.util.CUSTOM_SOURCE_REPO_URL
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** Fork identity, browser defaults, history and rollback controls in one settings destination. */
@Composable
fun NPmorpheSettings(
    settingsViewModel: SettingsViewModel = koinViewModel(),
    storageViewModel: StorageManagementViewModel = koinViewModel()
) {
    val prefs = settingsViewModel.prefs
    val historyRepository = koinInject<PatchHistoryRepository>()
    val historyEntries by historyRepository.entries.collectAsStateWithLifecycle()
    val historyEnabled by prefs.patchHistoryEnabled.getAsState()
    val browserPreference by prefs.apkBrowserPreference.getAsState()
    val browserDefaultUrl by prefs.apkBrowserDefaultUrl.getAsState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var showBrowserPreference by rememberSaveable { mutableStateOf(false) }
    var showDefaultUrlEditor by rememberSaveable { mutableStateOf(false) }
    var defaultUrlDraft by rememberSaveable { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }

    val browserPreferenceLabel = when (browserPreference) {
        ApkBrowserPreference.ASK -> stringResource(R.string.settings_system_apk_browser_ask)
        ApkBrowserPreference.IN_APP -> stringResource(R.string.settings_system_apk_browser_in_app)
        ApkBrowserPreference.EXTERNAL -> stringResource(R.string.settings_system_apk_browser_external)
    }

    DialogHost(visible = showBrowserPreference) {
        AppDialog(
            onDismissRequest = { showBrowserPreference = false },
            title = stringResource(R.string.settings_system_apk_browser_preference),
            description = stringResource(R.string.settings_system_apk_browser_preference_description),
            footer = {
                AppDialogButtonRow(
                    primaryText = stringResource(R.string.close),
                    onPrimaryClick = { showBrowserPreference = false }
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)) {
                ApkBrowserPreference.entries.forEach { preference ->
                    val label = when (preference) {
                        ApkBrowserPreference.ASK -> stringResource(R.string.settings_system_apk_browser_ask)
                        ApkBrowserPreference.IN_APP -> stringResource(R.string.settings_system_apk_browser_in_app)
                        ApkBrowserPreference.EXTERNAL -> stringResource(R.string.settings_system_apk_browser_external)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch { prefs.apkBrowserPreference.update(preference) }
                                showBrowserPreference = false
                            }
                            .padding(vertical = Defaults.SpaceSmall),
                        horizontalArrangement = Arrangement.spacedBy(Defaults.SpaceMedium),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = browserPreference == preference, onClick = null)
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }

    DialogHost(visible = showDefaultUrlEditor) {
        TextInputDialog(
            title = stringResource(R.string.npmorphe_settings_custom_default_url),
            value = defaultUrlDraft,
            onValueChange = { defaultUrlDraft = it },
            leadingIcon = MaterialSymbols.Rounded.Language,
            confirmText = stringResource(R.string.save),
            confirmEnabled = defaultUrlDraft.isNotBlank(),
            onConfirm = {
                scope.launch { prefs.apkBrowserDefaultUrl.update(defaultUrlDraft.trim()) }
                showDefaultUrlEditor = false
            },
            onDismiss = { showDefaultUrlEditor = false },
            description = stringResource(R.string.home_apk_browser_custom_default_description),
            placeholder = APK_BROWSER_DEFAULT_URL
        )
    }

    DialogHost(visible = showHistory) {
        PatchHistoryDialog(
            entries = historyEntries,
            onDismiss = { showHistory = false },
            onClear = {
                showHistory = false
                confirmClearHistory = true
            }
        )
    }

    DialogHost(visible = confirmClearHistory) {
        ConfirmDialog(
            title = stringResource(R.string.patch_history_clear_all),
            message = stringResource(R.string.patch_history_clear_confirm),
            primaryText = stringResource(R.string.clear),
            isPrimaryDestructive = true,
            onConfirm = {
                confirmClearHistory = false
                scope.launch { historyRepository.clear() }
            },
            onDismiss = { confirmClearHistory = false }
        )
    }

    SettingsColumn {
        SettingsSection(
            title = stringResource(R.string.npmorphe_settings_apk_browser),
            icon = MaterialSymbols.Rounded.Language
        ) {
            SettingsGroup {
                SettingsItem(
                    onClick = { showBrowserPreference = true },
                    icon = MaterialSymbols.Rounded.Language,
                    title = stringResource(R.string.settings_system_apk_browser_preference),
                    subtitle = browserPreferenceLabel
                )
                SettingsItem(
                    onClick = {
                        defaultUrlDraft = browserDefaultUrl
                        showDefaultUrlEditor = true
                    },
                    icon = MaterialSymbols.Rounded.Edit,
                    title = stringResource(R.string.npmorphe_settings_custom_default_url),
                    subtitle = browserDefaultUrl.ifBlank { APK_BROWSER_DEFAULT_URL }
                )
                SettingsItem(
                    onClick = { scope.launch { prefs.apkBrowserDefaultUrl.update("") } },
                    icon = MaterialSymbols.Rounded.Refresh,
                    title = stringResource(R.string.home_apk_browser_reset_default),
                    subtitle = stringResource(R.string.home_apk_browser_reset_default_description),
                    enabled = browserDefaultUrl.isNotBlank()
                )
                SettingsItem(
                    onClick = { scope.launch { prefs.apkBrowserPreference.update(ApkBrowserPreference.ASK) } },
                    icon = MaterialSymbols.Rounded.Close,
                    title = stringResource(R.string.npmorphe_settings_clear_default_browser),
                    subtitle = stringResource(R.string.home_apk_browser_clear_choice_description),
                    enabled = browserPreference != ApkBrowserPreference.ASK
                )
            }
        }

        SettingsSection(
            title = stringResource(R.string.patch_history_title),
            icon = MaterialSymbols.Rounded.History
        ) {
            SettingsGroup {
                SettingsSwitchItem(
                    checked = historyEnabled,
                    onToggle = { scope.launch { prefs.patchHistoryEnabled.update(!historyEnabled) } },
                    icon = MaterialSymbols.Rounded.History,
                    title = stringResource(R.string.patch_history_enabled),
                    subtitle = stringResource(R.string.patch_history_enabled_description)
                )
                SettingsItem(
                    onClick = { showHistory = true },
                    icon = MaterialSymbols.Rounded.History,
                    title = stringResource(R.string.npmorphe_settings_view_history),
                    subtitle = stringResource(R.string.patch_history_description)
                )
                SettingsItem(
                    onClick = { confirmClearHistory = true },
                    icon = MaterialSymbols.Rounded.Delete,
                    title = stringResource(R.string.patch_history_clear_all),
                    subtitle = stringResource(R.string.patch_history_clear_confirm),
                    enabled = historyEntries.isNotEmpty()
                )
            }
        }

        RollbackBackupSettingsSection(storageViewModel)

        SettingsSection(
            title = stringResource(R.string.npmorphe_settings_about),
            icon = MaterialSymbols.Rounded.Info
        ) {
            SettingsGroup {
                SettingsItemCard(onClick = null) {
                    IconTextRow(
                        modifier = Modifier.padding(Defaults.SpaceLarge),
                        leadingContent = { ThemedIcon(icon = MaterialSymbols.Rounded.Person) },
                        title = stringResource(R.string.npmorphe_settings_developer),
                        description = CUSTOM_DEVELOPER_NAME
                    )
                }
                SettingsItem(
                    onClick = { uriHandler.openUri(CUSTOM_SOURCE_REPO_URL) },
                    icon = MaterialSymbols.Rounded.Link,
                    title = stringResource(R.string.npmorphe_settings_repository),
                    subtitle = CUSTOM_SOURCE_REPO_URL
                )
            }
        }
    }
}
