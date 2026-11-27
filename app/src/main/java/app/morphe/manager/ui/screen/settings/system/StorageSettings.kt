/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.system

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.morphe.manager.R
import app.morphe.manager.domain.repository.StorageStats
import app.morphe.manager.domain.repository.RollbackBackup
import app.morphe.manager.domain.repository.RollbackBackupRepository
import app.morphe.manager.domain.repository.ApkDownloadManager
import app.morphe.manager.domain.repository.ApkDownloadStatus
import app.morphe.manager.ui.screen.home.DownloadedApksActivity
import app.morphe.manager.ui.screen.shared.AppIcon
import app.morphe.manager.ui.screen.settings.SettingsSubpage
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.InstallViewModel
import app.morphe.manager.ui.viewmodel.StorageManagementViewModel
import app.morphe.manager.util.formatBytes
import app.morphe.manager.util.openAppDetailsSettings
import app.morphe.manager.util.withVersionPrefix
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Where the manager's storage goes and what clears it, on a page of its own, see
 * [SettingsSubpage.STORAGE]. The saved APKs of each kind open over it, see [ApkManagementPage].
 * Storage preferences and the durable previous-version copies live here too.
 */
@Composable
fun StorageSettings(viewModel: StorageManagementViewModel = koinViewModel()) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    var apkType by rememberSaveable { mutableStateOf<ApkManagementType?>(null) }

    DialogHost(target = apkType) { type ->
        ApkManagementPage(type = type, onDismiss = { apkType = null })
    }

    if (LocalSettingsSearch.current != null) {
        SavedApksSection(viewModel = viewModel, stats = stats, onOpen = { apkType = it })
        RollbackBackupSettingsSection(viewModel)
    } else {
        StorageOverview(
            viewModel = viewModel,
            stats = stats,
            apksOpen = apkType != null,
            onOpenApks = { apkType = it }
        )
    }
}

/** How much the manager holds and in what, with whether APKs are kept and what clears the rest. */
@Composable
private fun StorageOverview(
    viewModel: StorageManagementViewModel,
    stats: StorageStats,
    apksOpen: Boolean,
    onOpenApks: (ApkManagementType) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val messages = koinInject<UserMessages>()
    val downloadManager = koinInject<ApkDownloadManager>()
    val downloads by downloadManager.downloads.collectAsStateWithLifecycle()
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showClearDownloadedConfirm by remember { mutableStateOf(false) }
    val patchInProgress by viewModel.patchInProgress.collectAsStateWithLifecycle()
    // A clear that would pull a running patch's files from under it, held until the user confirms
    var guardedClear by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    fun clearGuarded(title: String, clear: () -> Unit) {
        if (patchInProgress) guardedClear = title to clear else clear()
    }
    val patchRunningWarning = stringResource(R.string.settings_system_storage_patch_running_warning)
    val clearAnywayText = stringResource(R.string.settings_system_storage_clear_anyway)
    val patcherWorkspaceTitle = stringResource(R.string.settings_system_storage_patcher_workspace_title)
    val temporaryTitle = stringResource(R.string.settings_system_storage_temporary_title)

    val nothingToClearText = stringResource(R.string.settings_system_storage_nothing_to_clear)
    val clearedTemplate = stringResource(R.string.settings_system_storage_cleared)

    val onCleared: (Long) -> Unit = { freed ->
        val message = if (freed <= 0L) nothingToClearText
        else clearedTemplate.format(context.formatBytes(freed))
        messages.show(message)
    }

    // Read again whenever the page comes back into view, from the saved APKs as from elsewhere,
    // as either may have freed or taken up room meanwhile
    LaunchedEffect(lifecycleOwner, apksOpen) {
        if (apksOpen) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refresh() }
    }

    DialogHost(target = guardedClear) { (title, clear) ->
        ConfirmDialog(
            title = title,
            message = patchRunningWarning,
            primaryText = clearAnywayText,
            onConfirm = {
                guardedClear = null
                clear()
            },
            onDismiss = { guardedClear = null }
        )
    }

    DialogHost(visible = showClearAllConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_system_storage_clear_all),
            message = stringResource(R.string.settings_system_storage_clear_all_confirm),
            primaryText = if (patchInProgress) clearAnywayText else stringResource(R.string.clear),
            notice = patchRunningWarning.takeIf { patchInProgress },
            onConfirm = {
                showClearAllConfirm = false
                viewModel.clearAllCaches(onCleared)
            },
            onDismiss = { showClearAllConfirm = false },
            // Empty caches free nothing, so they are left out
            items = listOf(
                Triple(MaterialSymbols.Rounded.Cloud_download, R.string.settings_system_storage_http_cache_title, stats.httpCacheBytes),
                Triple(MaterialSymbols.Rounded.Share, R.string.settings_system_storage_installer_cache_title, stats.installerShareBytes),
                Triple(MaterialSymbols.Rounded.Build, R.string.settings_system_storage_patcher_workspace_title, stats.patcherWorkspaceBytes),
                Triple(MaterialSymbols.Rounded.Hourglass_empty, R.string.settings_system_storage_temporary_title, stats.temporaryBytes)
            ).filter { (_, _, bytes) -> bytes > 0 }.map { (icon, title, bytes) ->
                ConfirmItem(icon, stringResource(title), context.formatBytes(bytes))
            }
        )
    }

    DialogHost(visible = showClearDownloadedConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.npmorphe_downloads_clear_all),
            message = stringResource(R.string.npmorphe_downloads_clear_confirm),
            primaryText = stringResource(R.string.delete),
            onConfirm = {
                downloadManager.clearCompleted()
                showClearDownloadedConfirm = false
                viewModel.refresh()
            },
            onDismiss = { showClearDownloadedConfirm = false }
        )
    }

    // Nothing has been read until the first reading lands, and a real one always has room free
    val loading = stats == StorageStats.Empty

    SettingsColumn {
        SettingsGroup {
            SettingsItemCard(onClick = null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Defaults.SpaceLarge),
                    verticalArrangement = Arrangement.spacedBy(Defaults.SpaceLarge)
                ) {
                    // How much Morphe holds against what is left free, heading the breakdown of it.
                    // The free space is left out where the device does not report it
                    val free = when {
                        loading -> "…"
                        stats.deviceFreeBytes > 0L -> context.formatBytes(stats.deviceFreeBytes)
                        else -> null
                    }
                    InfoStatBox(
                        value = if (loading) "…" else context.formatBytes(stats.appUsedBytes),
                        subtitle = free?.let { stringResource(R.string.settings_system_storage_free, it) },
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                    StorageHistogram(segments = stats.toSegments(), loading = loading)
                }
            }
        }

        SavedApksSection(viewModel = viewModel, stats = stats, onOpen = onOpenApks)
        SettingsSection(
            title = stringResource(R.string.npmorphe_downloaded_apks),
            icon = MaterialSymbols.Rounded.Download
        ) {
            SettingsGroup {
                SettingsItem(
                    onClick = { context.startActivity(DownloadedApksActivity.intent(context)) },
                    icon = MaterialSymbols.Rounded.Inventory_2,
                    title = stringResource(R.string.npmorphe_downloads_title),
                    subtitle = stringResource(
                        R.string.npmorphe_downloads_storage_summary,
                        stats.downloadedApksCount,
                        context.formatBytes(stats.downloadedApksBytes)
                    ),
                    trailingContent = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            IconButton(
                                onClick = { showClearDownloadedConfirm = true },
                                enabled = downloads.any { it.status == ApkDownloadStatus.COMPLETED }
                            ) {
                                Icon(
                                    imageVector = MaterialSymbols.Rounded.Delete_sweep,
                                    contentDescription = stringResource(R.string.npmorphe_downloads_clear_all),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                            ForwardChevronIcon()
                        }
                    }
                )
            }
        }
        RollbackBackupSettingsSection(viewModel)

        SettingsSection(
            title = stringResource(R.string.settings_system_storage_caches),
            icon = MaterialSymbols.Rounded.Cached
        ) {
            SettingsGroup {
                CacheActionRow(
                    icon = MaterialSymbols.Rounded.Cloud_download,
                    accentColor = StorageColors.HttpCache,
                    title = stringResource(R.string.settings_system_storage_http_cache_title),
                    description = stringResource(R.string.settings_system_storage_http_cache_description),
                    bytes = stats.httpCacheBytes,
                    onClear = { viewModel.clearHttpCache(onCleared) }
                )
                CacheActionRow(
                    icon = MaterialSymbols.Rounded.Share,
                    accentColor = StorageColors.InstallerShare,
                    title = stringResource(R.string.settings_system_storage_installer_cache_title),
                    description = stringResource(R.string.settings_system_storage_installer_cache_description),
                    bytes = stats.installerShareBytes,
                    onClear = { viewModel.clearInstallerShareCache(onCleared) }
                )
                CacheActionRow(
                    icon = MaterialSymbols.Rounded.Build,
                    accentColor = StorageColors.PatcherWorkspace,
                    title = patcherWorkspaceTitle,
                    description = stringResource(R.string.settings_system_storage_patcher_workspace_description),
                    bytes = stats.patcherWorkspaceBytes,
                    onClear = { clearGuarded(patcherWorkspaceTitle) { viewModel.clearPatcherWorkspace(onCleared) } }
                )
                CacheActionRow(
                    icon = MaterialSymbols.Rounded.Hourglass_empty,
                    accentColor = StorageColors.Temporary,
                    title = temporaryTitle,
                    description = stringResource(R.string.settings_system_storage_temporary_description),
                    bytes = stats.temporaryBytes,
                    onClear = { clearGuarded(temporaryTitle) { viewModel.clearTemporary(onCleared) } }
                )
            }
            // Apart from the caches it sums up, so it does not read as one more of them
            SettingsGroup {
                CacheActionRow(
                    icon = MaterialSymbols.Rounded.Delete_sweep,
                    accentColor = MaterialTheme.colorScheme.error,
                    title = stringResource(R.string.settings_system_storage_clear_all),
                    description = stringResource(R.string.settings_system_storage_clear_all_description),
                    bytes = stats.totalCacheBytes,
                    onClear = { showClearAllConfirm = true }
                )
            }
        }

        SettingsGroup {
            SettingsItem(
                onClick = { context.openAppDetailsSettings() },
                title = stringResource(R.string.settings_system_storage_open_app_info_title),
                subtitle = stringResource(R.string.settings_system_storage_open_app_info_description),
                leadingContent = { ThemedIcon(icon = MaterialSymbols.Rounded.Phone_android) },
                // Leaves for the system settings rather than going on within the manager
                trailingContent = { ThemedIcon(icon = MaterialSymbols.Rounded.Open_in_new.autoMirrored) }
            )
        }
    }
}

/**
 * Whether original and patched APKs are kept, each over the row opening those already kept.
 *
 * @param stats How much the kept APKs take up, as last read.
 */
@Composable
private fun SavedApksSection(
    viewModel: StorageManagementViewModel,
    stats: StorageStats,
    onOpen: (ApkManagementType) -> Unit
) {
    val context = LocalContext.current
    val saveOriginalApks by viewModel.prefs.saveOriginalApks.getAsState()
    val savePatchedApks by viewModel.prefs.savePatchedApks.getAsState()
    // Nothing has been read until the first reading lands, and a real one always has room free
    val loading = stats == StorageStats.Empty

    SettingsSection(
        title = stringResource(R.string.settings_system_storage_saved_apks),
        icon = MaterialSymbols.Rounded.Inventory_2
    ) {
        SavedApksGroup(
            icon = MaterialSymbols.Rounded.File_download,
            accentColor = StorageColors.OriginalApks,
            saveTitle = stringResource(R.string.settings_system_save_original_apks_title),
            saveDescription = stringResource(R.string.settings_system_save_original_apks_description),
            save = saveOriginalApks,
            onToggleSave = { viewModel.setSaveOriginalApks(!saveOriginalApks) },
            title = stringResource(R.string.settings_system_original_apks_title),
            size = context.formatBytes(stats.originalApksBytes).takeUnless { loading },
            onOpen = { onOpen(ApkManagementType.ORIGINAL) }
        )
        SavedApksGroup(
            icon = MaterialSymbols.Rounded.Apps,
            accentColor = StorageColors.PatchedApks,
            saveTitle = stringResource(R.string.settings_system_save_patched_apks_title),
            saveDescription = stringResource(R.string.settings_system_save_patched_apks_description),
            save = savePatchedApks,
            onToggleSave = { viewModel.setSavePatchedApks(!savePatchedApks) },
            title = stringResource(R.string.settings_system_patched_apks_title),
            size = context.formatBytes(stats.patchedApksBytes).takeUnless { loading },
            onOpen = { onOpen(ApkManagementType.PATCHED) }
        )
    }
}

/** Durable rollback copies, their global controls, and one-tap restore. */
@Composable
internal fun RollbackBackupSettingsSection(viewModel: StorageManagementViewModel) {
    val repository = koinInject<RollbackBackupRepository>()
    val installViewModel: InstallViewModel = koinViewModel()
    val backups by repository.backups.collectAsStateWithLifecycle()
    val backupEnabled by viewModel.prefs.rollbackBackupsEnabled.getAsState()
    val autoCleanupEnabled by viewModel.prefs.rollbackAutoCleanupEnabled.getAsState()
    val scope = rememberCoroutineScope()
    var showBackups by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RollbackBackup?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var selectedBackupPackages by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(repository) { repository.refresh() }

    DialogHost(visible = showBackups) {
        ListDialog(
            onDismissRequest = { showBackups = false },
            header = {
                ListDialogHeader(
                    icon = { modifier ->
                        ListDialogHeaderIcon(
                            icon = MaterialSymbols.Rounded.History,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = modifier
                        )
                    },
                    title = stringResource(R.string.settings_system_rollback_manage),
                    subtitle = pluralStringResource(
                        R.plurals.settings_system_rollback_backup_count,
                        backups.size,
                        backups.size
                    ),
                    alwaysShowBand = true
                )
            },
            footer = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Defaults.SpaceLarge),
                    verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)
                ) {
                    if (selectedBackupPackages.isNotEmpty()) {
                        TextButton(onClick = { confirmDeleteSelected = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.npmorphe_rollback_delete_selected, selectedBackupPackages.size))
                        }
                    }
                    if (backups.isNotEmpty()) {
                        TextButton(
                            onClick = { confirmClearAll = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.settings_system_rollback_clear_all_title))
                        }
                    }
                    AppDialogOutlinedButton(
                        text = stringResource(R.string.close),
                        onClick = { showBackups = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        ) {
            DialogScrollColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)
            ) {
                if (backups.isEmpty()) {
                    Footnote(
                        text = stringResource(R.string.settings_system_rollback_empty),
                        tone = SemanticTone.Neutral,
                        modifier = Modifier.fillMaxWidth().padding(Defaults.SpaceLarge)
                    )
                } else {
                    backups.forEach { backup ->
                        val context = LocalContext.current
                        val appName = remember(backup.packageName) {
                            runCatching {
                                context.packageManager.getApplicationInfo(backup.packageName, 0)
                                    .loadLabel(context.packageManager).toString()
                            }.getOrDefault(backup.packageName)
                        }
                        val backupDate = remember(backup.backedUpAt) {
                            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(backup.backedUpAt))
                        }
                        SettingsItem(
                            onClick = {
                                if (selectedBackupPackages.isNotEmpty()) {
                                    selectedBackupPackages = if (backup.packageName in selectedBackupPackages) selectedBackupPackages - backup.packageName else selectedBackupPackages + backup.packageName
                                } else {
                                    installViewModel.rollback(backup.packageName)
                                    showBackups = false
                                }
                            },
                            leadingContent = {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AppIcon(Modifier.size(42.dp), packageName = backup.packageName, contentDescription = appName)
                                    Checkbox(
                                        checked = backup.packageName in selectedBackupPackages,
                                        onCheckedChange = { selected ->
                                            selectedBackupPackages = if (selected) selectedBackupPackages + backup.packageName else selectedBackupPackages - backup.packageName
                                        }
                                    )
                                }
                            },
                            title = appName,
                            subtitle = stringResource(
                                R.string.settings_system_rollback_version_detail,
                                backup.versionName?.withVersionPrefix() ?: stringResource(R.string.version),
                                backup.versionCode
                            ) + " · " + context.formatBytes(backup.sizeBytes) + " · " + backupDate,
                            trailingContent = {
                                IconButton(onClick = { pendingDelete = backup }) {
                                    Icon(
                                        imageVector = MaterialSymbols.Rounded.Delete,
                                        contentDescription = stringResource(R.string.delete),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    DialogHost(visible = confirmDeleteSelected) {
        ConfirmDialog(
            title = stringResource(R.string.npmorphe_rollback_delete_selected, selectedBackupPackages.size),
            message = stringResource(R.string.settings_system_rollback_clear_all_message),
            primaryText = stringResource(R.string.delete),
            isPrimaryDestructive = true,
            onConfirm = {
                val targets = selectedBackupPackages.toList()
                selectedBackupPackages = emptySet()
                confirmDeleteSelected = false
                scope.launch { targets.forEach { repository.delete(it) } }
            },
            onDismiss = { confirmDeleteSelected = false }
        )
    }

    DialogHost(target = pendingDelete) { backup ->
        ConfirmDialog(
            title = stringResource(R.string.settings_system_rollback_delete_title),
            message = stringResource(R.string.settings_system_rollback_delete_message, backup.packageName),
            primaryText = stringResource(R.string.delete),
            isPrimaryDestructive = true,
            onConfirm = {
                pendingDelete = null
                scope.launch { repository.delete(backup.packageName) }
            },
            onDismiss = { pendingDelete = null }
        )
    }

    DialogHost(visible = confirmClearAll) {
        ConfirmDialog(
            title = stringResource(R.string.settings_system_rollback_clear_all_title),
            message = stringResource(R.string.settings_system_rollback_clear_all_message),
            primaryText = stringResource(R.string.clear),
            isPrimaryDestructive = true,
            onConfirm = {
                confirmClearAll = false
                scope.launch { repository.clear() }
            },
            onDismiss = { confirmClearAll = false }
        )
    }

    SettingsSection(
        title = stringResource(R.string.settings_system_rollback_title),
        icon = MaterialSymbols.Rounded.History
    ) {
        SettingsGroup {
            SettingsSwitchItem(
                checked = backupEnabled,
                onToggle = { viewModel.setRollbackBackupsEnabled(!backupEnabled) },
                icon = MaterialSymbols.Rounded.Save,
                title = stringResource(R.string.settings_system_rollback_backup_enabled),
                subtitle = stringResource(R.string.settings_system_rollback_backup_enabled_description)
            )
            SettingsSwitchItem(
                checked = autoCleanupEnabled,
                onToggle = { viewModel.setRollbackAutoCleanupEnabled(!autoCleanupEnabled) },
                icon = MaterialSymbols.Rounded.Delete_sweep,
                title = stringResource(R.string.settings_system_rollback_cleanup_enabled),
                subtitle = stringResource(R.string.settings_system_rollback_cleanup_enabled_description)
            )
            SettingsItem(
                onClick = { showBackups = true },
                icon = MaterialSymbols.Rounded.Folder_open,
                title = stringResource(R.string.settings_system_rollback_manage),
                subtitle = pluralStringResource(
                    R.plurals.settings_system_rollback_backup_count,
                    backups.size,
                    backups.size
                )
            )
        }
    }
}

/**
 * Whether APKs of one kind are kept, over the row opening those already kept. The row stays while
 * keeping is off, as what was kept before is still there to clear.
 *
 * @param size How much the kept APKs take up, null while it is still being read.
 */
@Composable
private fun SavedApksGroup(
    icon: ImageVector,
    accentColor: Color,
    saveTitle: String,
    saveDescription: String,
    save: Boolean,
    onToggleSave: () -> Unit,
    title: String,
    size: String?,
    onOpen: () -> Unit
) {
    SettingsGroup {
        SettingsSwitchItem(
            checked = save,
            onToggle = onToggleSave,
            leadingContent = { ThemedIcon(icon = icon, tint = accentColor) },
            title = saveTitle,
            subtitle = saveDescription
        )
        SettingsItem(
            onClick = onOpen,
            icon = MaterialSymbols.Rounded.Folder_open,
            title = title,
            subtitle = size ?: "…"
        )
    }
}

@Composable
private fun CacheActionRow(
    icon: ImageVector,
    accentColor: Color,
    title: String,
    description: String,
    bytes: Long,
    onClear: () -> Unit
) {
    SettingsItemCard(onClick = null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Defaults.SpaceLarge),
            verticalArrangement = Arrangement.spacedBy(Defaults.SpaceLarge)
        ) {
            IconTextRow(
                leadingContent = { ThemedIcon(icon = icon, tint = accentColor) },
                title = title,
                description = description
            )
            CardActionRow(
                actions = listOf(
                    CardAction(
                        icon = MaterialSymbols.Rounded.Delete_sweep,
                        label = "${stringResource(R.string.clear)} (${LocalContext.current.formatBytes(bytes)})",
                        onClick = onClear,
                        enabled = bytes > 0L,
                        destructive = true
                    )
                )
            )
        }
    }
}

@Composable
private fun StorageStats.toSegments(): List<StorageSegment> {
    val originalLabel = stringResource(R.string.settings_system_original_apks_title)
    val patchedLabel = stringResource(R.string.settings_system_patched_apks_title)
    val rollbackLabel = stringResource(R.string.settings_system_rollback_title)
    val downloadedLabel = stringResource(R.string.npmorphe_downloaded_apks)
    val bundlesLabel = stringResource(R.string.settings_system_storage_patch_bundles_title)
    val keystoreLabel = stringResource(R.string.settings_system_storage_keystore_title)
    val appDataLabel = stringResource(R.string.settings_system_storage_app_data_title)
    val httpLabel = stringResource(R.string.settings_system_storage_http_cache_title)
    val shareLabel = stringResource(R.string.settings_system_storage_installer_cache_title)
    val patcherLabel = stringResource(R.string.settings_system_storage_patcher_workspace_title)
    val tempLabel = stringResource(R.string.settings_system_storage_temporary_title)
    return listOf(
        StorageSegment("original", originalLabel, originalApksBytes, StorageColors.OriginalApks),
        StorageSegment("patched", patchedLabel, patchedApksBytes, StorageColors.PatchedApks),
        StorageSegment("rollback", rollbackLabel, rollbackBackupBytes, StorageColors.RollbackBackups),
        StorageSegment("downloaded", downloadedLabel, downloadedApksBytes, StorageColors.DownloadedApks),
        StorageSegment("bundles", bundlesLabel, patchBundlesBytes, StorageColors.PatchBundles),
        StorageSegment("keystore", keystoreLabel, keystoreBytes, StorageColors.Keystore),
        StorageSegment("appdata", appDataLabel, appDataBytes, StorageColors.AppData),
        StorageSegment("http", httpLabel, httpCacheBytes, StorageColors.HttpCache),
        StorageSegment("share", shareLabel, installerShareBytes, StorageColors.InstallerShare),
        StorageSegment("patcher", patcherLabel, patcherWorkspaceBytes, StorageColors.PatcherWorkspace),
        StorageSegment("temp", tempLabel, temporaryBytes, StorageColors.Temporary)
    )
}
