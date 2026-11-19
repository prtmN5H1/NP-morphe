/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.domain.repository.PatchHistoryEntry
import app.morphe.manager.ui.screen.shared.AppDialog
import app.morphe.manager.ui.screen.shared.AppDialogButtonRow
import app.morphe.manager.ui.screen.shared.Defaults
import app.morphe.manager.ui.screen.shared.SurfaceCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun PatchHistoryDialog(
    entries: List<PatchHistoryEntry>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.patch_history_title),
        description = stringResource(R.string.patch_history_description),
        footer = {
            AppDialogButtonRow(
                primaryText = stringResource(R.string.close),
                onPrimaryClick = onDismiss,
                secondaryText = stringResource(R.string.patch_history_clear_all).takeIf { entries.isNotEmpty() },
                onSecondaryClick = onClear.takeIf { entries.isNotEmpty() }
            )
        }
    ) {
        PatchHistoryContent(entries)
    }
}

@Composable
internal fun PatchHistoryContent(
    entries: List<PatchHistoryEntry>,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Text(
            text = stringResource(R.string.patch_history_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = Defaults.SpaceLarge)
        )
        return
    }

    val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)
    ) {
        entries.sortedByDescending { it.patchCompletedAtMillis }.forEach { entry ->
            SurfaceCard(
                cornerRadius = Defaults.CornerLarge,
                showBorder = true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(Defaults.SpaceMedium),
                    verticalArrangement = Arrangement.spacedBy(Defaults.SpaceExtraSmall)
                ) {
                    Text(entry.appName, style = MaterialTheme.typography.titleMedium)
                    HistoryLine(stringResource(R.string.package_name), entry.packageName)
                    HistoryLine(
                        stringResource(R.string.patch_history_base_apk),
                        listOfNotNull(entry.baseFileName, entry.baseVersionName, entry.baseVersionCode?.toString())
                            .joinToString(" · ")
                    )
                    entry.downloadSourceUrl?.let {
                        HistoryLine(stringResource(R.string.patch_history_download_source), it)
                    }
                    if (entry.patchSources.isNotEmpty()) {
                        HistoryLine(stringResource(R.string.patch_history_sources), entry.patchSources.joinToString())
                    }
                    if (entry.appliedPatches.isNotEmpty()) {
                        HistoryLine(stringResource(R.string.patch_history_patches), entry.appliedPatches.joinToString())
                    }
                    if (entry.libraries.isNotEmpty()) {
                        HistoryLine(stringResource(R.string.patch_history_libraries), entry.libraries.joinToString())
                    }
                    HistoryLine(
                        stringResource(R.string.patch_history_manager_version),
                        "${entry.managerVersion} · ${entry.patcherVersion}"
                    )
                    HistoryLine(
                        stringResource(R.string.patch_history_output_version),
                        listOfNotNull(entry.outputVersionName, entry.outputVersionCode?.toString())
                            .joinToString(" · ").ifBlank { "unknown" }
                    )
                    HistoryLine(
                        stringResource(R.string.patch_history_patched_at),
                        timeFormatter.format(Date(entry.patchCompletedAtMillis))
                    )
                    entry.installConfirmedAtMillis?.let { installedAt ->
                        HistoryLine(
                            stringResource(R.string.patch_history_installed_at),
                            timeFormatter.format(Date(installedAt))
                        )
                    }
                    entry.previousVersionName?.let { previous ->
                        HistoryLine(
                            stringResource(R.string.patch_history_overwrote),
                            listOfNotNull(previous, entry.previousVersionCode?.toString()).joinToString(" · ")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
