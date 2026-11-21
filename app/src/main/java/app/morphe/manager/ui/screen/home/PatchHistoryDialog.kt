/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.domain.repository.PatchHistoryEntry
import app.morphe.manager.ui.screen.shared.DialogHostScope
import app.morphe.manager.ui.screen.shared.AppDialog
import app.morphe.manager.ui.screen.shared.AppDialogButtonRow
import app.morphe.manager.ui.screen.shared.Defaults
import app.morphe.manager.ui.screen.shared.SurfaceCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DialogHostScope.PatchHistoryDialog(
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
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)
    ) {
        val sortedEntries = entries.sortedByDescending { it.patchCompletedAtMillis }
        itemsIndexed(sortedEntries, key = { index, entry -> "${entry.id}-$index" }) { _, entry ->
            val patchedAt = runCatching { timeFormatter.format(Date(entry.patchCompletedAtMillis)) }
                .getOrElse { entry.patchCompletedAtMillis.toString() }
            SurfaceCard(
                cornerRadius = Defaults.CornerLarge,
                showBorder = true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(Defaults.SpaceMedium),
                    verticalArrangement = Arrangement.spacedBy(Defaults.SpaceExtraSmall)
                ) {
                    Text(entry.appName.ifBlank { entry.packageName }, style = MaterialTheme.typography.titleMedium)
                    HistoryLine(stringResource(R.string.package_name), entry.packageName.ifBlank { "unknown" })
                    HistoryLine(
                        stringResource(R.string.patch_history_base_apk),
                        listOfNotNull(entry.baseFileName.takeIf(String::isNotBlank), entry.baseVersionName, entry.baseVersionCode?.toString())
                            .joinToString(" · ").ifBlank { "unknown" }
                    )
                    entry.downloadSourceUrl?.takeIf(String::isNotBlank)?.let {
                        HistoryLine(stringResource(R.string.patch_history_download_source), it)
                    }
                    entry.patchSources.filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let {
                        HistoryLine(stringResource(R.string.patch_history_sources), it.joinToString())
                    }
                    entry.appliedPatches.filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let {
                        HistoryLine(stringResource(R.string.patch_history_patches), it.joinToString())
                    }
                    entry.libraries.filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let {
                        HistoryLine(stringResource(R.string.patch_history_libraries), it.joinToString())
                    }
                    HistoryLine(
                        stringResource(R.string.patch_history_manager_version),
                        listOf(entry.managerVersion, entry.patcherVersion).filter(String::isNotBlank).joinToString(" · ").ifBlank { "unknown" }
                    )
                    HistoryLine(
                        stringResource(R.string.patch_history_output_version),
                        listOfNotNull(entry.outputVersionName, entry.outputVersionCode?.toString())
                            .joinToString(" · ").ifBlank { "unknown" }
                    )
                    HistoryLine(stringResource(R.string.patch_history_patched_at), patchedAt)
                    entry.installConfirmedAtMillis?.let { installedAt ->
                        val installedAtText = runCatching { timeFormatter.format(Date(installedAt)) }
                            .getOrElse { installedAt.toString() }
                        HistoryLine(stringResource(R.string.patch_history_installed_at), installedAtText)
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
