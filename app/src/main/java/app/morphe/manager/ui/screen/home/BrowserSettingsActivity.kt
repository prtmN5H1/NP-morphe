package app.morphe.manager.ui.screen.home

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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.R
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.manager.DownloadAutoDeleteMode
import app.morphe.manager.domain.repository.ApkDownloadManager
import app.morphe.manager.domain.repository.BrowserBookmark
import app.morphe.manager.domain.repository.BrowserDataRepository
import app.morphe.manager.domain.repository.BrowserSearchEngine
import app.morphe.manager.domain.repository.BrowserUserScript
import app.morphe.manager.ui.theme.ManagerTheme
import app.morphe.manager.ui.theme.Theme
import app.morphe.manager.ui.theme.ThemeStyle
import app.morphe.manager.ui.theme.ThemeTraits
import app.morphe.manager.ui.theme.resolveThemeStyle
import app.morphe.manager.util.getAsState
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Locale

class BrowserSettingsActivity : ComponentActivity() {
    private val preferences: PreferencesManager by inject()
    private val browserData: BrowserDataRepository by inject()
    private val downloads: ApkDownloadManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val theme by preferences.theme.getAsState()
            val style by preferences.themeStyle.getAsState()
            val pureBlack by preferences.pureBlackTheme.getAsState()
            val colorAccents by preferences.colorAccents.getAsState()
            val outlines by preferences.outlines.getAsState()
            val blur by preferences.blur.getAsState()
            val accent by preferences.customAccentColor.getAsState()
            val themeColor by preferences.customThemeColor.getAsState()
            val darkTheme = when (theme) {
                Theme.LIGHT -> false
                Theme.DARK -> true
                Theme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            val resolved = resolveThemeStyle(style, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ManagerTheme(
                darkTheme = darkTheme,
                dynamicColor = resolved == ThemeStyle.MATERIAL_YOU,
                pureBlackTheme = pureBlack,
                traits = ThemeTraits(monochrome = resolved == ThemeStyle.MONOCHROME, colorAccents = colorAccents, outlines = outlines, blur = blur),
                accentColorHex = accent.takeUnless(String::isBlank),
                themeColorHex = themeColor.takeUnless(String::isBlank)
            ) {
                BrowserSettingsScreen(
                    repository = browserData,
                    preferences = preferences,
                    downloads = downloads,
                    startUrl = intent.getStringExtra(EXTRA_START_URL).orEmpty(),
                    onBack = ::finish
                )
            }
        }
    }

    companion object {
        const val EXTRA_START_URL = "npmorphe.browser.settings.start_url"

        fun intent(context: android.content.Context, currentUrl: String? = null) =
            Intent(context, BrowserSettingsActivity::class.java).putExtra(EXTRA_START_URL, currentUrl)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserSettingsScreen(
    repository: BrowserDataRepository,
    preferences: PreferencesManager,
    downloads: ApkDownloadManager,
    startUrl: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by repository.state.collectAsStateWithLifecycle()
    val downloadRecords by downloads.downloads.collectAsStateWithLifecycle()
    val autoDeleteMode by preferences.downloadAutoDeleteMode.getAsState()
    var showAutoDeleteOptions by remember { mutableStateOf(false) }
    var expandedEngine by remember { mutableStateOf(false) }
    var editingSearch by remember { mutableStateOf(false) }
    var searchDraft by remember(snapshot.customSearchUrl) { mutableStateOf(snapshot.customSearchUrl) }
    var editingBookmark by remember { mutableStateOf<BrowserBookmark?>(null) }
    var bookmarkTitle by remember { mutableStateOf("") }
    var bookmarkUrl by remember { mutableStateOf("") }
    var bookmarkPinned by remember { mutableStateOf(false) }
    var scriptText by remember { mutableStateOf<String?>(null) }
    var scriptUrl by remember { mutableStateOf<String?>(null) }
    var historyQuery by remember { mutableStateOf("") }
    val scriptPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Cannot read selected userscript")
        }.onSuccess { raw ->
            runCatching { repository.addUserScript(raw, uri.lastPathSegment.orEmpty()) }
                .onSuccess { Toast.makeText(context, it.name, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "Could not import script", Toast.LENGTH_LONG).show() }
        }.onFailure { Toast.makeText(context, it.message ?: "Could not read file", Toast.LENGTH_LONG).show() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.npmorphe_browser_settings_title)) }, navigationIcon = {
            TextButton(onClick = onBack) { Text("‹") }
        }) }
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsCard {
                    Text(stringResource(R.string.npmorphe_browser_search_engine), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { expandedEngine = true }) {
                        Text(snapshot.searchEngine.name.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercase))
                    }
                    DropdownMenu(expanded = expandedEngine, onDismissRequest = { expandedEngine = false }) {
                        BrowserSearchEngine.entries.forEach { engine ->
                            DropdownMenuItem(
                                text = { Text(engine.name.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercase)) },
                                onClick = {
                                    repository.setSearchEngine(engine)
                                    expandedEngine = false
                                    if (engine == BrowserSearchEngine.CUSTOM) editingSearch = true
                                }
                            )
                        }
                    }
                    if (snapshot.searchEngine == BrowserSearchEngine.CUSTOM) {
                        Text(snapshot.customSearchUrl.ifBlank { stringResource(R.string.npmorphe_browser_custom_search_hint) }, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { searchDraft = snapshot.customSearchUrl; editingSearch = true }) { Text(stringResource(R.string.npmorphe_settings_edit_url)) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.npmorphe_browser_ad_blocker), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.npmorphe_browser_ad_blocker_description), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = snapshot.adBlockEnabled, onCheckedChange = repository::setAdBlockEnabled)
                    }
                    TextButton(onClick = { showAutoDeleteOptions = true }) {
                        Text(stringResource(R.string.npmorphe_download_auto_delete_mode))
                    }
                    Text(
                        text = stringResource(
                            when (autoDeleteMode) {
                                DownloadAutoDeleteMode.AFTER_INSTALL -> R.string.npmorphe_download_delete_after_install
                                DownloadAutoDeleteMode.AFTER_PATCH -> R.string.npmorphe_download_delete_after_patch
                                DownloadAutoDeleteMode.KEEP_MANUALLY -> R.string.npmorphe_download_keep_manual
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item {
                SectionTitle(stringResource(R.string.npmorphe_downloaded_apks)) {
                    TextButton(onClick = { context.startActivity(DownloadedApksActivity.intent(context)) }) {
                        Text("${downloadRecords.count { it.status == app.morphe.manager.domain.repository.ApkDownloadStatus.DOWNLOADING || it.status == app.morphe.manager.domain.repository.ApkDownloadStatus.QUEUED }} · ${downloadRecords.count { it.status == app.morphe.manager.domain.repository.ApkDownloadStatus.COMPLETED }}")
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.npmorphe_browser_bookmarks)) {
                    OutlinedButton(onClick = {
                        bookmarkTitle = ""
                        bookmarkUrl = startUrl
                        bookmarkPinned = false
                        editingBookmark = BrowserBookmark("", "", "")
                    }) { Text(stringResource(R.string.npmorphe_browser_add_bookmark)) }
                }
            }
            if (snapshot.bookmarks.isEmpty()) item { Text(stringResource(R.string.npmorphe_browser_no_bookmarks), style = MaterialTheme.typography.bodyMedium) }
            items(snapshot.bookmarks, key = { it.id }) { bookmark ->
                SettingsCard {
                    Text(bookmark.title, fontWeight = FontWeight.SemiBold)
                    Text(bookmark.url, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { openUrl(context, bookmark.url) }) { Text(stringResource(R.string.open)) }
                        TextButton(onClick = {
                            bookmarkTitle = bookmark.title
                            bookmarkUrl = bookmark.url
                            bookmarkPinned = bookmark.pinned
                            editingBookmark = bookmark
                        }) { Text(stringResource(R.string.npmorphe_settings_edit_url)) }
                        TextButton(onClick = { repository.updateBookmark(bookmark.copy(pinned = !bookmark.pinned)) }) {
                            Text(if (bookmark.pinned) stringResource(R.string.npmorphe_browser_unpin_bookmark) else stringResource(R.string.npmorphe_browser_pin_bookmark))
                        }
                        TextButton(onClick = { repository.deleteBookmark(bookmark.id) }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.npmorphe_browser_history)) {
                    TextButton(onClick = repository::clearHistory) { Text(stringResource(R.string.npmorphe_browser_clear_history)) }
                }
                OutlinedTextField(
                    value = historyQuery,
                    onValueChange = { historyQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.npmorphe_browser_history_search)) },
                    singleLine = true
                )
            }
            val history = snapshot.history.filter {
                historyQuery.isBlank() || it.title.contains(historyQuery, true) || it.url.contains(historyQuery, true)
            }
            if (history.isEmpty()) item { Text(stringResource(R.string.npmorphe_browser_no_history), style = MaterialTheme.typography.bodyMedium) }
            items(history, key = { it.id }) { entry ->
                SettingsCard {
                    Text(entry.title, fontWeight = FontWeight.SemiBold)
                    Text(entry.url, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Row {
                        TextButton(onClick = { openUrl(context, entry.url) }) { Text(stringResource(R.string.open)) }
                        TextButton(onClick = { repository.deleteHistoryEntry(entry.id) }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.npmorphe_browser_userscripts)) {
                    TextButton(onClick = { scriptText = "" }) { Text(stringResource(R.string.npmorphe_browser_add_script)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scriptPicker.launch(arrayOf("text/*", "application/javascript", "application/x-javascript")) }) {
                        Text(stringResource(R.string.npmorphe_browser_import_script))
                    }
                    OutlinedButton(onClick = { scriptUrl = "" }) { Text(stringResource(R.string.npmorphe_browser_install_script_url)) }
                }
            }
            if (snapshot.userScripts.isEmpty()) item { Text(stringResource(R.string.npmorphe_browser_no_scripts), style = MaterialTheme.typography.bodyMedium) }
            items(snapshot.userScripts, key = { it.id }) { script -> ScriptRow(script, repository) }
        }
    }

    if (editingSearch) InputDialog(
        title = stringResource(R.string.npmorphe_browser_custom_search_url),
        initial = searchDraft,
        label = stringResource(R.string.npmorphe_browser_custom_search_hint),
        onDismiss = { editingSearch = false },
        onSave = { value ->
            val trimmed = value.trim()
            if (trimmed.startsWith("https://") && "{query}" in trimmed) {
                repository.setSearchEngine(BrowserSearchEngine.CUSTOM, trimmed)
                editingSearch = false
            } else Toast.makeText(context, R.string.npmorphe_browser_custom_search_hint, Toast.LENGTH_LONG).show()
        }
    )
    if (editingBookmark != null) BookmarkEditor(
        title = bookmarkTitle, url = bookmarkUrl, pinned = bookmarkPinned,
        onTitleChange = { bookmarkTitle = it }, onUrlChange = { bookmarkUrl = it }, onPinnedChange = { bookmarkPinned = it },
        onDismiss = { editingBookmark = null },
        onSave = {
            if (bookmarkUrl.startsWith("http://") || bookmarkUrl.startsWith("https://")) {
                            editingBookmark?.takeIf { it.id.isNotBlank() }?.let { existing ->
                                repository.updateBookmark(existing.copy(title = bookmarkTitle, url = bookmarkUrl, pinned = bookmarkPinned))
                            } ?: repository.addBookmark(bookmarkTitle, bookmarkUrl, bookmarkPinned)
                editingBookmark = null
            }
        }
    )
    if (scriptText != null) InputDialog(
        title = stringResource(R.string.npmorphe_browser_add_script),
        initial = scriptText.orEmpty(),
        label = stringResource(R.string.npmorphe_browser_script_source),
        multiline = true,
        onDismiss = { scriptText = null },
        onSave = { raw -> runCatching { repository.addUserScript(raw) }
            .onSuccess { scriptText = null }
            .onFailure { Toast.makeText(context, it.message ?: "Invalid script", Toast.LENGTH_LONG).show() }
        }
    )
    if (scriptUrl != null) InputDialog(
        title = stringResource(R.string.npmorphe_browser_install_script_url),
        initial = scriptUrl.orEmpty(),
        label = stringResource(R.string.npmorphe_browser_script_url),
        onDismiss = { scriptUrl = null },
        onSave = { url -> scope.launch {
            runCatching { repository.installUserScript(url) }
                .onSuccess { scriptUrl = null; Toast.makeText(context, it.name, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "Could not install script", Toast.LENGTH_LONG).show() }
        } }
    )
    if (showAutoDeleteOptions) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showAutoDeleteOptions = false },
        title = { Text(stringResource(R.string.npmorphe_download_auto_delete_mode)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadAutoDeleteMode.entries.forEach { mode ->
                    val label = when (mode) {
                        DownloadAutoDeleteMode.AFTER_INSTALL -> stringResource(R.string.npmorphe_download_delete_after_install)
                        DownloadAutoDeleteMode.AFTER_PATCH -> stringResource(R.string.npmorphe_download_delete_after_patch)
                        DownloadAutoDeleteMode.KEEP_MANUALLY -> stringResource(R.string.npmorphe_download_keep_manual)
                    }
                    TextButton(onClick = { scope.launch { preferences.downloadAutoDeleteMode.update(mode) }; showAutoDeleteOptions = false }) {
                        Text(if (autoDeleteMode == mode) "✓ $label" else label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showAutoDeleteOptions = false }) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun ScriptRow(script: BrowserUserScript, repository: BrowserDataRepository) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(script.name, fontWeight = FontWeight.SemiBold)
                Text(script.matches.joinToString(), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = script.enabled, onCheckedChange = { repository.setUserScriptEnabled(script.id, it) })
        }
        TextButton(onClick = { repository.deleteUserScript(script.id) }) { Text(stringResource(R.string.delete)) }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun SectionTitle(title: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        action()
    }
}

@Composable
private fun InputDialog(
    title: String,
    initial: String,
    label: String,
    multiline: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth().heightIn(min = if (multiline) 180.dp else 56.dp, max = 420.dp),
                minLines = if (multiline) 6 else 1,
                maxLines = if (multiline) 14 else 1
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
private fun BookmarkEditor(
    title: String,
    url: String,
    pinned: Boolean,
    onTitleChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onPinnedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.npmorphe_browser_bookmark_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, onTitleChange, label = { Text(stringResource(R.string.npmorphe_browser_bookmark_title)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, onUrlChange, label = { Text(stringResource(R.string.npmorphe_browser_bookmark_url)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.npmorphe_browser_pin_bookmark), Modifier.weight(1f))
                    Switch(checked = pinned, onCheckedChange = onPinnedChange)
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(InAppBrowserActivity.intent(context, url, null, null, null, false)) }
        .onFailure { Toast.makeText(context, R.string.npmorphe_browser_external_failed, Toast.LENGTH_SHORT).show() }
}
