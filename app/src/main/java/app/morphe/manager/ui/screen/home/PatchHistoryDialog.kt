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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val context = LocalContext.current
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
                        Text(
                            entry.appName.ifBlank { entry.packageName },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
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
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        lines.forEach { (label, value) ->
                            HistoryLine(label, value)
                        }
                    }
                }
            }
        }
    }
}

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
