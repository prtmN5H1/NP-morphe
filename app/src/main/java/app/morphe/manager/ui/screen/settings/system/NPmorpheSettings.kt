/*
 * Fork-specific settings for NPmorphe.
 */

package app.morphe.manager.ui.screen.settings.system

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.R
import app.morphe.manager.domain.manager.APK_BROWSER_APP_DEFAULT_URL_SEPARATOR
import app.morphe.manager.domain.manager.ApkBrowserPreference
import app.morphe.manager.domain.manager.DownloadAutoDeleteMode
import app.morphe.manager.domain.backup.NpmorpheBackupManager
import app.morphe.manager.domain.backup.NpmorpheBackupContents
import app.morphe.manager.domain.repository.BrowserDataRepository
import app.morphe.manager.domain.repository.PatchHistoryRepository
import app.morphe.manager.ui.screen.home.PatchHistoryDialog
import app.morphe.manager.ui.screen.home.ApkDownloadBrowserDialog
import app.morphe.manager.ui.screen.home.BrowserSettingsActivity
import app.morphe.manager.ui.screen.home.DownloadedApksActivity
import app.morphe.manager.ui.screen.home.InAppBrowserActivity
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.SettingsViewModel
import app.morphe.manager.ui.viewmodel.StorageManagementViewModel
import app.morphe.manager.ui.viewmodel.ImportExportViewModel
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

private data class CrashLogCardData(val header: String, val preview: String, val fullText: String)

private fun parseCrashLogEntries(rawLogs: String): List<CrashLogCardData> =
    rawLogs.split("=== NPmorphe crash ===")
        .map(String::trim)
        .filter(String::isNotBlank)
        .asReversed()
        .map { block ->
            val lines = block.lines()
            val timestamp = lines.firstOrNull { it.startsWith("Timestamp:") }
                ?.substringAfter(':')?.trim().orEmpty()
            val stackStart = lines.indexOfFirst { it.trim().equals("Stack trace:", ignoreCase = true) }
            val stack = if (stackStart >= 0) lines.drop(stackStart + 1).dropWhile(String::isBlank) else emptyList()
            val title = stack.firstOrNull(String::isNotBlank)?.trim()
                ?: lines.firstOrNull { it.startsWith("Thread:") }?.substringAfter(':')?.trim()
                ?: "Crash report"
            val preview = stack.take(12).joinToString("\n").ifBlank {
                lines.take(12).joinToString("\n")
            }
            val full = "=== NPmorphe crash ===\n" + block
            CrashLogCardData(
                header = listOf(timestamp, title).filter(String::isNotBlank).joinToString(" · "),
                preview = preview,
                fullText = full
            )
        }

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
    val browserDataRepository = koinInject<BrowserDataRepository>()
    val backupManager = koinInject<NpmorpheBackupManager>()
    val importExportViewModel = koinInject<ImportExportViewModel>()
    val autoDeleteMode by prefs.downloadAutoDeleteMode.getAsState()

    var showBrowserPreference by rememberSaveable { mutableStateOf(false) }
    var showDefaultUrlEditor by rememberSaveable { mutableStateOf(false) }
    var defaultUrlDraft by rememberSaveable { mutableStateOf("") }
    var editingDefaultPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingBackup by remember { mutableStateOf<NpmorpheBackupContents?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var showAppDefaultsReset by rememberSaveable { mutableStateOf(false) }
    var showCrashLogs by rememberSaveable { mutableStateOf(false) }
    var crashLogs by remember { mutableStateOf("") }
    val crashEntries = remember(crashLogs) { parseCrashLogEntries(crashLogs) }

    val exportBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            runCatching { backupManager.export(uri) }
                .onSuccess { fileName -> Toast.makeText(context, fileName, Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.npmorphe_settings_backup_failed), Toast.LENGTH_LONG).show() }
        }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { backupManager.read(uri) }
                .onSuccess { pendingBackup = it }
                .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.npmorphe_settings_backup_failed), Toast.LENGTH_LONG).show() }
        }
    }

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
                scope.launch {
                    val packageName = editingDefaultPackage
                    if (packageName == null) {
                        prefs.apkBrowserDefaultUrl.update(defaultUrlDraft.trim())
                    } else {
                        val prefix = packageName + APK_BROWSER_APP_DEFAULT_URL_SEPARATOR
                        prefs.apkBrowserAppDefaultUrls.update(
                            (prefs.apkBrowserAppDefaultUrls.get().filterNot { it.startsWith(prefix) } + (prefix + defaultUrlDraft.trim())).toSet()
                        )
                    }
                }
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
            title = stringResource(R.string.npmorphe_settings_custom_urls),
            description = stringResource(R.string.npmorphe_settings_reset_app_defaults_description),
            footer = {
                AppDialogButtonRow(
                    primaryText = stringResource(R.string.close),
                    onPrimaryClick = { showAppDefaultsReset = false }
                )
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            prefs.apkBrowserDefaultUrl.update("")
                            prefs.apkBrowserAppDefaultUrls.update(emptySet())
                        }
                    },
                    enabled = browserDefaultUrl.isNotBlank() || appBrowserDefaults.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.npmorphe_settings_reset_all))
                }
                if (appBrowserDefaults.isEmpty()) {
                    Text(
                        text = stringResource(R.string.npmorphe_settings_no_app_defaults),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (browserDefaultUrl.isNotBlank()) {
                    SurfaceCard(cornerRadius = Defaults.CornerLarge, showBorder = true, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(Defaults.SpaceMedium), verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)) {
                            Text(stringResource(R.string.npmorphe_settings_custom_default_url), style = MaterialTheme.typography.titleSmall)
                            Text(browserDefaultUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)) {
                                TextButton(onClick = {
                                    context.startActivity(InAppBrowserActivity.intent(context, browserDefaultUrl, null, null, null, true))
                                }) { Text(stringResource(R.string.npmorphe_settings_open_url)) }
                                TextButton(onClick = { editingDefaultPackage = null; defaultUrlDraft = browserDefaultUrl; showDefaultUrlEditor = true }) { Text(stringResource(R.string.npmorphe_settings_edit_url)) }
                                TextButton(onClick = { scope.launch { prefs.apkBrowserDefaultUrl.update("") } }) { Text(stringResource(R.string.npmorphe_settings_delete_url)) }
                            }
                        }
                    }
                }
                appBrowserDefaults.forEach { (packageName, url) ->
                    SurfaceCard(cornerRadius = Defaults.CornerLarge, showBorder = true, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(Defaults.SpaceMedium), verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)) {
                            Text(packageName, style = MaterialTheme.typography.titleSmall)
                            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)) {
                                TextButton(onClick = {
                                    context.startActivity(InAppBrowserActivity.intent(context, url, packageName, null, null, false))
                                }) { Text(stringResource(R.string.npmorphe_settings_open_url)) }
                                TextButton(onClick = { editingDefaultPackage = packageName; defaultUrlDraft = url; showDefaultUrlEditor = true }) { Text(stringResource(R.string.npmorphe_settings_edit_url)) }
                                TextButton(onClick = {
                                    val prefix = packageName + APK_BROWSER_APP_DEFAULT_URL_SEPARATOR
                                    scope.launch { prefs.apkBrowserAppDefaultUrls.update(prefs.apkBrowserAppDefaultUrls.get().filterNot { it.startsWith(prefix) }.toSet()) }
                                }) { Text(stringResource(R.string.npmorphe_settings_delete_url)) }
                            }
                        }
                    }
                }
            }
        }
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
                    title = stringResource(R.string.npmorphe_settings_copy_all_logs)
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
                if (crashEntries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.npmorphe_settings_logs_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    crashEntries.forEach { entry ->
                        SurfaceCard(
                            cornerRadius = Defaults.CornerLarge,
                            showBorder = true,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(Defaults.SpaceMedium),
                                verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)
                            ) {
                                Text(
                                    text = entry.header,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = entry.preview,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("NPmorphe crash log", entry.fullText))
                                        Toast.makeText(context, context.getString(R.string.npmorphe_settings_logs_copied), Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text(stringResource(R.string.npmorphe_copy))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    DialogHost(target = pendingBackup) { backup ->
        AppDialog(
            onDismissRequest = { pendingBackup = null },
            title = stringResource(R.string.npmorphe_settings_backup_restore),
            description = stringResource(R.string.npmorphe_settings_restore_confirm, backup.sourceCount, backup.historyCount),
            footer = {
                AppDialogButtonRow(
                    primaryText = stringResource(R.string.npmorphe_settings_import_backup),
                    onPrimaryClick = {
                        scope.launch {
                            runCatching {
                                importExportViewModel.applyManagerSettingsJson(backup.managerSettingsJson)
                                browserDataRepository.importJson(backup.browserDataJson)
                                historyRepository.replaceAllJson(backup.patchHistoryJson)
                            }.onSuccess {
                                pendingBackup = null
                                Toast.makeText(context, R.string.npmorphe_settings_backup_imported, Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(context, it.message ?: context.getString(R.string.npmorphe_settings_backup_failed), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    secondaryText = stringResource(android.R.string.cancel),
                    onSecondaryClick = { pendingBackup = null }
                )
            }
        ) {
            Text(stringResource(R.string.npmorphe_settings_backup_ready), style = MaterialTheme.typography.bodyMedium)
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
                    onClick = { showAppDefaultsReset = true },
                    icon = MaterialSymbols.Rounded.Edit,
                    title = stringResource(R.string.npmorphe_settings_custom_urls),
                    subtitle = stringResource(R.string.npmorphe_settings_reset_app_defaults_description)
                )
                SettingsItem(
                    onClick = { context.startActivity(BrowserSettingsActivity.intent(context)) },
                    icon = MaterialSymbols.Rounded.Language,
                    title = stringResource(R.string.npmorphe_settings_open_browser_settings),
                    subtitle = stringResource(R.string.npmorphe_browser_settings_title)
                )
                SettingsItem(
                    onClick = { context.startActivity(DownloadedApksActivity.intent(context)) },
                    icon = MaterialSymbols.Rounded.Download,
                    title = stringResource(R.string.npmorphe_downloaded_apks),
                    subtitle = stringResource(R.string.npmorphe_downloads_title)
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
            }
        }

        RollbackBackupSettingsSection(storageViewModel)

        SettingsSection(
            title = stringResource(R.string.npmorphe_settings_backup_restore),
            icon = MaterialSymbols.Rounded.Backup
        ) {
            SettingsGroup {
                SettingsItem(
                    onClick = { exportBackupLauncher.launch(NpmorpheBackupManager.defaultBackupFileName()) },
                    icon = MaterialSymbols.Rounded.File_upload,
                    title = stringResource(R.string.npmorphe_settings_export_backup),
                    subtitle = stringResource(R.string.npmorphe_settings_backup_ready)
                )
                SettingsItem(
                    onClick = { importBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    icon = MaterialSymbols.Rounded.File_download,
                    title = stringResource(R.string.npmorphe_settings_import_backup),
                    subtitle = stringResource(R.string.npmorphe_settings_restore_confirm, 0, 0)
                )
            }
        }

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
