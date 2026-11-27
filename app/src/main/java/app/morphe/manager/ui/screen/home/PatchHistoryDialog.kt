/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morphe.manager.R
import app.morphe.manager.domain.repository.PatchHistoryEntry
import app.morphe.manager.ui.screen.shared.DialogHostScope
import app.morphe.manager.ui.screen.shared.AppDialog
import app.morphe.manager.ui.screen.shared.AppDialogButtonRow
import app.morphe.manager.ui.screen.shared.Defaults
import app.morphe.manager.ui.screen.shared.SurfaceCard
import app.morphe.manager.ui.screen.shared.AppIcon
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DialogHostScope.PatchHistoryDialog(
    entries: List<PatchHistoryEntry>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onDelete: (String) -> Unit = {},
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.patch_history_title),
        description = stringResource(R.string.patch_history_description),
        footer = {
            AppDialogButtonRow(
                primaryText = stringResource(R.string.close),
                onPrimaryClick = onDismiss,
                secondaryText = null,
                onSecondaryClick = null
            )
        }
    ) {
        PatchHistoryContent(entries, onClear = onClear, onDelete = onDelete)
    }
}

@Composable
internal fun PatchHistoryContent(
    entries: List<PatchHistoryEntry>,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val filteredEntries = remember(entries, query) {
        entries.filter { query.isBlank() || it.appName.contains(query, true) || it.packageName.contains(query, true) ||
            it.outputVersionName.orEmpty().contains(query, true) || it.patchSources.any { source -> source.contains(query, true) } }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.npmorphe_history_search)) },
                singleLine = true
            )
            if (onClear != null && entries.isNotEmpty()) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.patch_history_clear_all)) }
            }
        }
    if (filteredEntries.isEmpty()) {
        Text(
            text = stringResource(if (entries.isEmpty()) R.string.patch_history_empty else R.string.search_no_results),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = Defaults.SpaceLarge)
        )
        return@Column
    }

    val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)
    ) {
        val sortedEntries = filteredEntries.sortedByDescending { it.patchCompletedAtMillis }
        itemsIndexed(sortedEntries, key = { index, entry -> "${entry.id}-$index" }) { _, entry ->
            var expanded by remember(entry.id) { mutableStateOf(false) }
            val patchedAt = runCatching { timeFormatter.format(Date(entry.patchCompletedAtMillis)) }
                .getOrElse { entry.patchCompletedAtMillis.toString() }
            val lines = mutableListOf(
                stringResource(R.string.package_name) to entry.packageName.ifBlank { "unknown" },
                stringResource(R.string.patch_history_base_apk) to listOfNotNull(
                    entry.baseFileName.takeIf(String::isNotBlank),
                    entry.baseVersionName,
                    entry.baseVersionCode?.toString()
                ).joinToString(" · ").ifBlank { "unknown" },
                stringResource(R.string.patch_history_manager) to listOf(entry.managerVersion, entry.patcherVersion)
                    .filter(String::isNotBlank).joinToString(" · ").ifBlank { "unknown" },
                stringResource(R.string.patch_history_runtime) to entry.libraries.filter(String::isNotBlank)
                    .joinToString().ifBlank { "unknown" },
                stringResource(R.string.patch_history_patched_version) to listOfNotNull(
                    entry.outputVersionName,
                    entry.outputVersionCode?.toString()
                ).joinToString(" · ").ifBlank { "unknown" },
                stringResource(R.string.patch_history_patched_at) to patchedAt
            )
            entry.downloadSourceUrl?.takeIf(String::isNotBlank)?.let {
                lines += stringResource(R.string.patch_history_download_source) to it
            }
            entry.patchSources.filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let {
                lines += stringResource(R.string.patch_history_source) to it.joinToString()
            }
            entry.appliedPatches.filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let {
                lines += stringResource(R.string.patch_history_patches) to it.joinToString()
            }
            entry.installConfirmedAtMillis?.let { installedAt ->
                val installedAtText = runCatching { timeFormatter.format(Date(installedAt)) }
                    .getOrElse { installedAt.toString() }
                lines += stringResource(R.string.patch_history_installed_at) to installedAtText
            }
            entry.previousVersionName?.let { previous ->
                lines += stringResource(R.string.patch_history_overwrote) to
                    listOfNotNull(previous, entry.previousVersionCode?.toString()).joinToString(" · ")
            }
            val copyText = buildString {
                append(entry.appName.ifBlank { entry.packageName })
                lines.forEach { (label, value) ->
                    append('\n')
                    append(label)
                    append(": ")
                    append(value)
                }
            }
            SurfaceCard(
                cornerRadius = Defaults.CornerLarge,
                showBorder = true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(
                            modifier = Modifier.size(42.dp),
                            packageName = entry.packageName,
                            contentDescription = entry.appName.ifBlank { entry.packageName }
                        )
                        Text(
                            entry.appName.ifBlank { entry.packageName },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(start = 10.dp)
                        )
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Patch history", copyText))
                                Toast.makeText(context, context.getString(R.string.npmorphe_settings_logs_copied), Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(MaterialSymbols.Rounded.Content_copy, contentDescription = stringResource(R.string.npmorphe_copy))
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.packageName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(stringResource(if (expanded) R.string.npmorphe_history_collapse else R.string.npmorphe_history_expand))
                        }
                        if (onDelete != null) {
                            IconButton(onClick = { onDelete(entry.id) }) {
                                Icon(MaterialSymbols.Rounded.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        }
                    }
                    Text(
                        text = listOfNotNull(entry.outputVersionName, entry.outputVersionCode?.toString(), entry.apkSizeBytes?.let(::formatApkSize), patchedAt).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        lines.forEach { (label, value) -> HistoryLine(label, value) }
                    }
                }
            }
        }
    }
    }
}

private fun formatApkSize(bytes: Long): String = String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))

@Composable
private fun HistoryLine(label: String, value: String) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(label)
                append(": ")
            }
            append(value)
        },
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
