package app.morphe.manager.ui.screen.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.MainActivity
import app.morphe.manager.R
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.repository.ApkDownloadManager
import app.morphe.manager.domain.repository.ApkDownloadRecord
import app.morphe.manager.domain.repository.ApkDownloadStatus
import app.morphe.manager.ui.theme.ManagerTheme
import app.morphe.manager.ui.theme.Theme
import app.morphe.manager.ui.theme.ThemeStyle
import app.morphe.manager.ui.theme.ThemeTraits
import app.morphe.manager.ui.theme.resolveThemeStyle
import app.morphe.manager.util.getAsState
import app.morphe.manager.ui.screen.shared.AppIcon
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File
import java.util.Locale

class DownloadedApksActivity : ComponentActivity() {
    private val preferences: PreferencesManager by inject()
    private val downloads: ApkDownloadManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val theme by preferences.theme.getAsState()
            val style by preferences.themeStyle.getAsState()
            val pureBlack by preferences.pureBlackTheme.getAsState()
            val accents by preferences.colorAccents.getAsState()
            val outlines by preferences.outlines.getAsState()
            val blur by preferences.blur.getAsState()
            val accent by preferences.customAccentColor.getAsState()
            val themeColor by preferences.customThemeColor.getAsState()
            val dark = when (theme) {
                Theme.LIGHT -> false
                Theme.DARK -> true
                Theme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            val resolved = resolveThemeStyle(style, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ManagerTheme(darkTheme = dark, dynamicColor = resolved == ThemeStyle.MATERIAL_YOU,
                pureBlackTheme = pureBlack,
                traits = ThemeTraits(monochrome = resolved == ThemeStyle.MONOCHROME, colorAccents = accents, outlines = outlines, blur = blur),
                accentColorHex = accent.takeUnless(String::isBlank), themeColorHex = themeColor.takeUnless(String::isBlank)) {
                DownloadedApksScreen(downloads, onClose = ::finish)
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, DownloadedApksActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadedApksScreen(manager: ApkDownloadManager, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val records by manager.downloads.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var saveRecord by remember { mutableStateOf<ApkDownloadRecord?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeleteIds by remember { mutableStateOf<List<String>?>(null) }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val record = saveRecord
        saveRecord = null
        if (uri != null && record != null) scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(record.filePath)
                    val output = context.contentResolver.openOutputStream(uri, "w") ?: error("No output stream")
                    output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
                }.isSuccess
            }
            Toast.makeText(context, if (saved) R.string.npmorphe_downloaded_apk_saved else R.string.npmorphe_downloaded_apk_save_failed, Toast.LENGTH_SHORT).show()
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.npmorphe_downloads_title)) },
            navigationIcon = { TextButton(onClick = onClose) { Text("‹") } },
            actions = {
                if (selectionMode) {
                    TextButton(
                        onClick = { pendingDeleteIds = selectedIds.toList() },
                        enabled = selectedIds.isNotEmpty()
                    ) { Text(stringResource(R.string.delete)) }
                    TextButton(onClick = { selectionMode = false; selectedIds = emptySet() }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                } else {
                    TextButton(
                        onClick = { selectionMode = true },
                        enabled = records.any { it.status == ApkDownloadStatus.COMPLETED }
                    ) { Text(stringResource(R.string.npmorphe_downloads_select)) }
                }
                TextButton(onClick = { confirmClear = true }, enabled = records.any { it.status == ApkDownloadStatus.COMPLETED }) {
                    Text(stringResource(R.string.npmorphe_downloads_clear_all))
                }
            }
        )
    }) { insets ->
        val active = records.filter { it.status in setOf(ApkDownloadStatus.QUEUED, ApkDownloadStatus.DOWNLOADING, ApkDownloadStatus.PAUSED, ApkDownloadStatus.FAILED) }
        val completed = records.filter { it.status == ApkDownloadStatus.COMPLETED }
        LazyColumn(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)) {
            item { Text(stringResource(R.string.npmorphe_downloads_active), style = MaterialTheme.typography.titleLarge) }
            if (active.isEmpty()) item { Text(stringResource(R.string.npmorphe_downloads_none_active), style = MaterialTheme.typography.bodyMedium) }
            items(active, key = ApkDownloadRecord::id) { record -> DownloadCard(record, manager, onSave = {}) }
            item { Text(stringResource(R.string.npmorphe_downloads_completed), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp)) }
            if (completed.isEmpty()) item { Text(stringResource(R.string.npmorphe_downloads_none_completed), style = MaterialTheme.typography.bodyMedium) }
            items(completed, key = ApkDownloadRecord::id) { record ->
                DownloadCard(
                    record = record,
                    manager = manager,
                    selectionMode = selectionMode,
                    selected = record.id in selectedIds,
                    onSelect = {
                        selectedIds = if (record.id in selectedIds) selectedIds - record.id else selectedIds + record.id
                    },
                    onSave = {
                        saveRecord = record
                        savePicker.launch(record.fileName)
                    }
                )
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.npmorphe_downloads_clear_all)) },
        text = { Text(stringResource(R.string.npmorphe_downloads_clear_confirm)) },
        confirmButton = { TextButton(onClick = { manager.clearCompleted(); confirmClear = false }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) } }
    )
    pendingDeleteIds?.let { ids ->
        AlertDialog(
            onDismissRequest = { pendingDeleteIds = null },
            title = { Text(stringResource(R.string.npmorphe_downloads_delete_selected, ids.size)) },
            text = { Text(stringResource(R.string.npmorphe_downloads_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    ids.forEach(manager::delete)
                    selectedIds = emptySet()
                    selectionMode = false
                    pendingDeleteIds = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteIds = null }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

@Composable
private fun DownloadCard(
    record: ApkDownloadRecord,
    manager: ApkDownloadManager,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onSelect: () -> Unit = {},
    onSave: () -> Unit
) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth().then(
            if (selectionMode && record.status == ApkDownloadStatus.COMPLETED) Modifier.clickable(onClick = onSelect) else Modifier
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (selectionMode && record.status == ApkDownloadStatus.COMPLETED) {
                    Checkbox(checked = selected, onCheckedChange = { onSelect() })
                }
                AppIcon(
                    modifier = Modifier.size(42.dp),
                    packageName = record.packageName,
                    contentDescription = record.fileName
                )
                Column(Modifier.weight(1f)) {
                    Text(record.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    record.packageName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Text("${statusLabel(record.status)} · ${formatBytes(record.downloadedBytes)}${if (record.totalBytes > 0) " / ${formatBytes(record.totalBytes)}" else ""}", style = MaterialTheme.typography.bodyMedium)
            if (record.status == ApkDownloadStatus.DOWNLOADING && record.bytesPerSecond > 0L) Text("${formatBytes(record.bytesPerSecond)}/s", style = MaterialTheme.typography.bodySmall)
            if (record.status in setOf(ApkDownloadStatus.DOWNLOADING, ApkDownloadStatus.QUEUED) && record.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { (record.downloadedBytes.toFloat() / record.totalBytes.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (record.versionName != null || record.timestamp > 0L) {
                val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(record.timestamp))
                Text(listOfNotNull(record.versionName, formatBytes(File(record.filePath).takeIf(File::isFile)?.length() ?: record.downloadedBytes), date).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            record.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (record.status) {
                    ApkDownloadStatus.DOWNLOADING, ApkDownloadStatus.QUEUED -> OutlinedButton(onClick = { ApkDownloadService.pause(context, record.id) }) { Text(stringResource(R.string.npmorphe_downloads_pause)) }
                    ApkDownloadStatus.PAUSED -> Button(onClick = { ApkDownloadService.resume(context, record.id) }) { Text(stringResource(R.string.npmorphe_downloads_resume)) }
                    ApkDownloadStatus.FAILED -> Button(onClick = { ApkDownloadService.resume(context, record.id) }) { Text(stringResource(R.string.npmorphe_downloads_retry)) }
                    ApkDownloadStatus.COMPLETED -> {
                        Button(onClick = { openForPatch(context, record) }) { Text(stringResource(R.string.npmorphe_downloads_load_to_patch)) }
                        TextButton(onClick = onSave) { Text(stringResource(R.string.npmorphe_downloads_save_copy)) }
                    }
                }
                TextButton(onClick = { manager.delete(record.id) }) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

private fun openForPatch(context: Context, record: ApkDownloadRecord) {
    val file = File(record.filePath)
    if (!file.isFile) return
    val intent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, R.string.npmorphe_downloads_open_failed, Toast.LENGTH_LONG).show() }
}

private fun statusLabel(status: ApkDownloadStatus) = when (status) {
    ApkDownloadStatus.QUEUED -> "Queued"
    ApkDownloadStatus.DOWNLOADING -> "Downloading"
    ApkDownloadStatus.PAUSED -> "Paused"
    ApkDownloadStatus.COMPLETED -> "Completed"
    ApkDownloadStatus.FAILED -> "Failed"
}

private fun formatBytes(bytes: Long) = String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
