/*
 * Fork-specific settings for NPmorphe.
 */

package app.morphe.manager.ui.screen.settings.system

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.R
import app.morphe.manager.domain.manager.APK_BROWSER_APP_DEFAULT_URL_SEPARATOR
import app.morphe.manager.domain.manager.ApkBrowserPreference
import app.morphe.manager.domain.repository.PatchHistoryRepository
import app.morphe.manager.ui.screen.home.PatchHistoryDialog
import app.morphe.manager.ui.screen.home.ApkDownloadBrowserDialog
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.SettingsViewModel
import app.morphe.manager.ui.viewmodel.StorageManagementViewModel
import app.morphe.manager.util.APK_BROWSER_DEFAULT_URL
import app.morphe.manager.util.CUSTOM_DEVELOPER_NAME
import app.morphe.manager.util.CUSTOM_SOURCE_REPO_URL
import app.morphe.manager.util.CrashLogManager
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val appDefaultUrlRecords by prefs.apkBrowserAppDefaultUrls.getAsState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    var showBrowserPreference by rememberSaveable { mutableStateOf(false) }
    var showDefaultUrlEditor by rememberSaveable { mutableStateOf(false) }
    var defaultUrlDraft by rememberSaveable { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var showAppDefaultsReset by rememberSaveable { mutableStateOf(false) }
    var showStandaloneBrowser by rememberSaveable { mutableStateOf(false) }
    var showCrashLogs by rememberSaveable { mutableStateOf(false) }
    var crashLogs by remember { mutableStateOf("") }

    val browserPreferenceLabel = when (browserPreference) {
        ApkBrowserPreference.ASK -> stringResource(R.string.settings_system_apk_browser_ask)
        ApkBrowserPreference.IN_APP -> stringResource(R.string.settings_system_apk_browser_in_app)
        ApkBrowserPreference.EXTERNAL -> stringResource(R.string.settings_system_apk_browser_external)
    }

    val appBrowserDefaults = remember(appDefaultUrlRecords) {
        appDefaultUrlRecords.mapNotNull { record ->
            val split = record.indexOf(APK_BROWSER_APP_DEFAULT_URL_SEPARATOR)
            if (split <= 0 || split == record.lastIndex) null
            else record.substring(0, split) to record.substring(split + 1)
        }.sortedBy { it.first }
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

    DialogHost(visible = showAppDefaultsReset) {
        AppDialog(
            onDismissRequest = { showAppDefaultsReset = false },
            title = stringResource(R.string.home_apk_browser_reset_default),
            description = stringResource(R.string.npmorphe_settings_reset_app_defaults_description),
            footer = {
                AppDialogButtonRow(
                    primaryText = stringResource(R.string.close),
                    onPrimaryClick = { showAppDefaultsReset = false },
                    secondaryText = stringResource(R.string.npmorphe_settings_reset_all).takeIf {
                        browserDefaultUrl.isNotBlank() || appBrowserDefaults.isNotEmpty()
                    },
                    onSecondaryClick = {
                        scope.launch {
                            prefs.apkBrowserDefaultUrl.update("")
                            prefs.apkBrowserAppDefaultUrls.update(emptySet())
                        }
                        showAppDefaultsReset = false
                    }
                )
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)
            ) {
                if (appBrowserDefaults.isEmpty()) {
                    Text(
                        text = stringResource(R.string.npmorphe_settings_no_app_defaults),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                appBrowserDefaults.forEach { (packageName, url) ->
                    SettingsItem(
                        onClick = {
                            val prefix = packageName + APK_BROWSER_APP_DEFAULT_URL_SEPARATOR
                            scope.launch {
                                prefs.apkBrowserAppDefaultUrls.update(
                                    prefs.apkBrowserAppDefaultUrls.get().filterNot { it.startsWith(prefix) }.toSet()
                                )
                            }
                        },
                        icon = MaterialSymbols.Rounded.Refresh,
                        title = stringResource(R.string.npmorphe_settings_reset_app_default, packageName),
                        subtitle = url
                    )
                }
            }
        }
    }

    DialogHost(visible = showStandaloneBrowser) {
        ApkDownloadBrowserDialog(
            initialUrl = APK_BROWSER_DEFAULT_URL,
            onDismiss = { showStandaloneBrowser = false },
            onApkDownloaded = { _, _, _, _ ->
                Toast.makeText(context, context.getString(R.string.npmorphe_settings_browser_download_saved), Toast.LENGTH_SHORT).show()
            }
        )
    }

    LaunchedEffect(showCrashLogs) {
        if (showCrashLogs) crashLogs = withContext(Dispatchers.IO) { CrashLogManager.read(context) }
    }

    DialogHost(visible = showCrashLogs) {
        AppDialog(
            onDismissRequest = { showCrashLogs = false },
            title = stringResource(R.string.npmorphe_settings_crash_logs),
            description = stringResource(R.string.npmorphe_settings_crash_logs_description),
            footer = {
                AppDialogButtonRow(
                    primaryText = stringResource(R.string.close),
                    onPrimaryClick = { showCrashLogs = false }
                )
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)
            ) {
                SettingsItem(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("NPmorphe logs", crashLogs))
                        Toast.makeText(context, context.getString(R.string.npmorphe_settings_logs_copied), Toast.LENGTH_SHORT).show()
                    },
                    icon = MaterialSymbols.Rounded.Content_copy,
                    title = stringResource(R.string.npmorphe_settings_copy_logs)
                )
                SettingsItem(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, crashLogs)
                        }
                        runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.npmorphe_settings_share_logs))) }
                    },
                    icon = MaterialSymbols.Rounded.Share,
                    title = stringResource(R.string.npmorphe_settings_share_logs)
                )
                SettingsItem(
                    onClick = {
                        CrashLogManager.clear(context)
                        crashLogs = ""
                    },
                    icon = MaterialSymbols.Rounded.Delete,
                    title = stringResource(R.string.npmorphe_settings_clear_logs)
                )
                Text(
                    text = crashLogs.ifBlank { stringResource(R.string.npmorphe_settings_logs_empty) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
                    onClick = { showAppDefaultsReset = true },
                    icon = MaterialSymbols.Rounded.Refresh,
                    title = stringResource(R.string.home_apk_browser_reset_default),
                    subtitle = stringResource(R.string.npmorphe_settings_reset_app_defaults_description),
                    enabled = browserDefaultUrl.isNotBlank() || appBrowserDefaults.isNotEmpty()
                )
                SettingsItem(
                    onClick = { showStandaloneBrowser = true },
                    icon = MaterialSymbols.Rounded.Language,
                    title = stringResource(R.string.npmorphe_settings_open_browser),
                    subtitle = stringResource(R.string.npmorphe_settings_open_browser_description)
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
            title = stringResource(R.string.npmorphe_settings_crash_logs),
            icon = MaterialSymbols.Rounded.Bug_report
        ) {
            SettingsGroup {
                SettingsItem(
                    onClick = { showCrashLogs = true },
                    icon = MaterialSymbols.Rounded.Article,
                    title = stringResource(R.string.npmorphe_settings_crash_logs),
                    subtitle = stringResource(R.string.npmorphe_settings_crash_logs_description)
                )
            }
        }

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
