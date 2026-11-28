/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.sources

import android.net.Uri
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.colorResource
import app.morphe.manager.R
import app.morphe.manager.domain.repository.PatchBundleRepository.LocalFileCheck
import app.morphe.manager.domain.repository.PatchBundleRepository.RemoteSourceRejection
import app.morphe.manager.domain.repository.PatchBundleRepository.RemoteUrlCheck
import app.morphe.manager.ui.model.NewSourceOptions
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.HomeViewModel.PickedBundle
import app.morphe.manager.util.CodeHost
import app.morphe.manager.util.REMOTE_URL_PLACEHOLDER
import app.morphe.manager.util.toFilePath
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Locale

/**
 * Panel sources are added from, several at once: links pasted in any form and files attached from
 * storage, listed together above the field. Each pending source says whether it will be added, and
 * only those that will are counted and submitted.
 *
 * @param localFiles Files picked so far with what importing each would do, held by the caller
 * since the picker opens outside.
 * @param onCheckUrl Checks a link the way the add will, so a refusal shows before submitting.
 */
@Composable
fun AddSourcePanel(
    visible: Boolean,
    onClose: () -> Unit,
    onSubmit: (urls: List<String>, options: NewSourceOptions) -> Unit,
    onPickFiles: () -> Unit,
    onRemoveFile: (Uri) -> Unit,
    localFiles: List<PickedBundle>,
    onCheckUrl: (String) -> RemoteUrlCheck,
    installedSourceUrls: List<String> = emptyList(),
    modifier: Modifier = Modifier
) {
    var showCommunityNotice by rememberSaveable { mutableStateOf(false) }
    var showCommunityCatalog by rememberSaveable { mutableStateOf(false) }
    var returnedCommunitySources by remember { mutableStateOf<List<String>?>(null) }
    var communityResultPending by remember { mutableStateOf(false) }
    var queuedCommunitySources by remember { mutableStateOf(emptyList<String>()) }
    val context = LocalContext.current
    val communityCatalogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        returnedCommunitySources = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(CommunityCatalogActivity.EXTRA_SELECTED_URLS).orEmpty()
        } else queuedCommunitySources
        communityResultPending = true
        showCommunityCatalog = false
    }
    val prefs: PreferencesManager = koinInject()
    val communityNoticeAcknowledged by prefs.communityNoticeAcknowledged.getAsState()
    val openCommunityPatches = {
        if (communityNoticeAcknowledged) showCommunityCatalog = true
        else showCommunityNotice = true
    }

    DialogHost(visible = showCommunityNotice) {
        ConfirmDialog(
            title = stringResource(R.string.sources_dialog_community_notice_title),
            message = stringResource(R.string.sources_dialog_community_notice_message),
            primaryText = stringResource(R.string.open),
            isPrimaryDestructive = false,
            onDismiss = { showCommunityNotice = false },
            onConfirm = {
                showCommunityNotice = false
                showCommunityCatalog = true
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    prefs.communityNoticeAcknowledged.update(true)
                }
            }
        )
    }

    // Submitting clears the files, which would otherwise drop out of the panel as it slides away
    val files = rememberWhileVisible(visible, localFiles)

    DockedPanel(visible = visible, onBack = onClose, modifier = modifier) {
        // Composed only while the panel is up, so the links typed and the choices made start over
        // each time it opens
        var options by rememberSaveable { mutableStateOf(NewSourceOptions()) }
        val remote = rememberRemoteLinksState(onCheckUrl)
        val readyCount = remote.readyLinks.size + files.count { it.adds }

        LaunchedEffect(showCommunityCatalog) {
            if (showCommunityCatalog) {
                showCommunityCatalog = false
                communityCatalogLauncher.launch(
                    CommunityCatalogActivity.intent(context, installedSourceUrls, remote.readyLinks)
                )
            }
        }
        LaunchedEffect(communityResultPending, returnedCommunitySources) {
            if (communityResultPending) {
                val selected = returnedCommunitySources.orEmpty()
                remote.replaceCommunitySources(queuedCommunitySources, selected)
                queuedCommunitySources = selected
                returnedCommunitySources = null
                communityResultPending = false
            }
        }

        Column(modifier = Modifier.padding(bottom = Defaults.SpaceSmall)) {
            PanelHeader(title = { PanelTitle(text = stringResource(R.string.sources_dialog_add_source)) }) {
                // Where sources are found rather than a way of adding one, so it stays out of the actions
                TitleAction(
                    icon = MaterialSymbols.Rounded.Groups,
                    contentDescription = stringResource(R.string.sources_dialog_community),
                    onClick = openCommunityPatches,
                    style = TitleActionStyle.Neutral
                )
                TitleAction(
                    icon = MaterialSymbols.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                    onClick = onClose,
                    style = TitleActionStyle.Neutral
                )
            }
            // Scrolled between the header and the actions once the keyboard leaves too little room
            // for it all, so adding stays in reach however many sources are pending
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScrollFade(scrollState)
                    .verticalScroll(scrollState)
                    .padding(horizontal = Defaults.SpaceLarge)
                    .padding(bottom = Defaults.SpaceMedium),
                verticalArrangement = Arrangement.spacedBy(Defaults.SpaceMedium)
            ) {
                PendingSources(state = remote, files = files, onRemoveFile = onRemoveFile)
                // The two ways a source comes in, kept together apart from what is asked of it
                Column(verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)) {
                    LinkField(state = remote, showHint = remote.entries.isEmpty() && files.isEmpty())
                    // Files join the same list as links, picked from storage rather than typed
                    AppDialogOutlinedButton(
                        text = stringResource(R.string.sources_dialog_local_files),
                        onClick = onPickFiles,
                        icon = MaterialSymbols.Rounded.Upload_file,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                NewSourceOptionsGroup(
                    options = options,
                    onOptionsChange = { options = it },
                    offersChooseApps = true,
                    offersPrerelease = remote.offersPrerelease
                )
            }
            Box(modifier = Modifier.padding(horizontal = Defaults.SpaceLarge)) {
                AppDialogButtonRow(
                    primaryText = if (readyCount > 1) {
                        pluralStringResource(R.plurals.sources_dialog_add_count, readyCount, readyCount.toString())
                    } else {
                        stringResource(R.string.add)
                    },
                    onPrimaryClick = { onSubmit(remote.readyLinks, options) },
                    primaryEnabled = readyCount > 0
                )
            }
        }
    }
}

/**
 * The switches a source is added with, as one group: whether its app list opens once it has loaded,
 * and whether it starts on prerelease. Each row shows only where its choice applies, and the group
 * nothing where none does.
 */
@Composable
internal fun NewSourceOptionsGroup(
    options: NewSourceOptions,
    onOptionsChange: (NewSourceOptions) -> Unit,
    offersChooseApps: Boolean,
    offersPrerelease: Boolean,
    modifier: Modifier = Modifier
) {
    if (!offersChooseApps && !offersPrerelease) return

    // Spaced by each row rather than by the column, so a row folding in brings its gap along
    Column(modifier = modifier) {
        // What a source holds is only known once it has loaded, so this asks now and the list opens then
        if (offersChooseApps) {
            ToggleRow(
                title = stringResource(R.string.sources_dialog_choose_apps),
                description = stringResource(R.string.sources_dialog_choose_apps_description),
                checked = options.chooseApps,
                onCheckedChange = { onOptionsChange(options.copy(chooseApps = it)) },
                showDivider = false
            )
        }
        // Only a source on a stable or dev branch has a pre-release channel, see createRemotes
        ListRowFold(visible = offersPrerelease) {
            ToggleRow(
                title = stringResource(R.string.sources_management_prerelease_toggle),
                description = stringResource(R.string.sources_management_prerelease_toggle_description),
                checked = options.usePrerelease,
                onCheckedChange = { onOptionsChange(options.copy(usePrerelease = it)) },
                modifier = Modifier.padding(top = if (offersChooseApps) Defaults.SpaceSmall else 0.dp),
                showDivider = false
            )
        }
    }
}

/**
 * Links in pasted text however they came: one per line, in a list, or inside prose or markdown.
 * With its scheme a link may name any host, such as an address or a name on the local network,
 * without one only a domain or an IPv4 address reads as a link rather than as a word.
 */
private val LINK_PATTERN = Regex(
    """(?:https?://(?:\[[0-9a-f:.]+]|(?:[\w-]+\.)*[\w-]+)|(?:[\w-]+\.)+[a-z]{2,}|\d{1,3}(?:\.\d{1,3}){3})(?::\d+)?(?:/[^\s,;<>()\[\]"'`]*)?""",
    RegexOption.IGNORE_CASE
)

private fun extractLinks(text: String): List<String> =
    LINK_PATTERN.findAll(text).map { it.value.trimEnd('.') }.toList()

/** A link waiting in the panel, with what adding it would do. */
private class RemoteLinkEntry(val link: String, val check: RemoteUrlCheck) {
    /** Two links reading the same bundle are the same source, however each was written. */
    val key = ((check as? RemoteUrlCheck.Accepted)?.endpoint ?: link).lowercase(Locale.US)
}

/**
 * Links of the panel: the ones taken into the list, and the ones still in the field, which
 * count as well. A paste of several links or the keyboard's Done moves the field into the list.
 *
 * Typing is never rewritten. A field reset under the keyboard races the keys still on their way,
 * and whichever lands in between is lost, so links typed one after another simply stay in the
 * field until Done.
 */
@Stable
private class RemoteLinksState(
    private val check: (String) -> RemoteUrlCheck,
    linksState: MutableState<List<String>>,
    inputState: MutableState<String>
) {
    private var links by linksState
    var input by inputState
        private set

    val entries by derivedStateOf { links.map { RemoteLinkEntry(it, check(it)) } }

    /** Links in the field. Text that holds none is still checked, to say why it is not one. */
    private val inputEntries by derivedStateOf {
        if (input.isBlank()) emptyList()
        else extractLinks(input).ifEmpty { listOf(input.trim()) }.map { RemoteLinkEntry(it, check(it)) }
    }

    /** What the field holds, checked: the first link it would leave out, or null while it is empty. */
    val inputCheck by derivedStateOf {
        inputEntries.firstOrNull { it.check is RemoteUrlCheck.Rejected }?.check
            ?: inputEntries.firstOrNull()?.check
    }

    private val readyEntries by derivedStateOf {
        (entries + inputEntries)
            .distinctBy { it.key }
            .filter { it.check is RemoteUrlCheck.Accepted }
    }

    /** Links that will become sources, the ones in the field included. */
    val readyLinks by derivedStateOf { readyEntries.map { it.link } }

    /** Whether any of [readyLinks] could start on prerelease. */
    val offersPrerelease by derivedStateOf {
        readyEntries.any { (it.check as RemoteUrlCheck.Accepted).supportsPrerelease }
    }

    fun onInputChange(text: String) {
        // Grown by more than a key at once is a paste, which leaves nothing in flight to race
        val pastedLinks = extractLinks(text).takeIf { text.length - input.length > 1 && it.size > 1 }
        input = if (pastedLinks != null && add(pastedLinks)) "" else text
    }

    fun clearInput() {
        input = ""
    }

    fun commitInput() {
        if (add(extractLinks(input))) input = ""
    }

    /** @return Whether [text] held any link. */
    fun paste(text: String): Boolean = add(extractLinks(text))

    fun remove(link: String) {
        links = links - link
    }

    /** Normalizes selected catalogue repositories before they enter the pending source list. */
    private fun communityRepositoryUrl(addSourceUrl: String): String? {
        val normalizedLink = if (addSourceUrl.startsWith("intent://")) {
            "https://" + addSourceUrl.removePrefix("intent://").substringBefore("#Intent;")
        } else addSourceUrl
        val uri = runCatching { Uri.parse(normalizedLink) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val acceptedHost = host == "morphe-patches.software" ||
            host.endsWith(".morphe-patches.software") ||
            host == "morphe.software" || host.endsWith(".morphe.software")
        if (acceptedHost && uri.path == "/add-source") {
            return uri.getQueryParameter("github")?.trim('/')?.let { "https://github.com/$it" }
                ?: uri.getQueryParameter("gitlab")?.trim('/')?.let { "https://gitlab.com/$it" }
        }
        canonicalGitHubSlug(uri)?.let { return "https://github.com/$it" }
        if (host == "gitlab.com" && uri.pathSegments.size >= 2) {
            return "https://gitlab.com/${uri.pathSegments.take(2).joinToString("/")}"
        }
        return null
    }

    fun replaceCommunitySources(previous: List<String>, selected: List<String>) {
        val previousKeys = previous.flatMap(::canonicalSourceKeys).toSet()
        if (previousKeys.isNotEmpty()) {
            links = links.filterNot { entry -> canonicalSourceKeys(entry).any(previousKeys::contains) }
        }
        val accepted = selected.mapNotNull(::communityRepositoryUrl)
            .filter { check(it) is RemoteUrlCheck.Accepted }
        add(accepted)
    }

    private fun add(newLinks: List<String>): Boolean {
        if (newLinks.isEmpty()) return false
        val taken = entries.mapTo(mutableSetOf()) { it.key }
        links = links + newLinks.filter { taken.add(RemoteLinkEntry(it, check(it)).key) }
        return true
    }
}

/** Canonical keys let catalogue cards match a source URL even when their URL forms differ. */
private fun canonicalSourceKeys(rawValue: String): Set<String> {
    val value = rawValue.trim()
    if (value.isEmpty()) return emptySet()
    val normalizedValue = if (value.startsWith("intent://")) {
        "https://${value.removePrefix("intent://").substringBefore("#Intent;")}"
    } else value
    val uri = runCatching { Uri.parse(normalizedValue) }.getOrNull() ?: return emptySet()
    val host = uri.host?.lowercase(Locale.ROOT)
    if (host == "morphe-patches.software" || host?.endsWith(".morphe-patches.software") == true ||
        host == "morphe.software" || host?.endsWith(".morphe.software") == true
    ) {
        if (uri.path == "/add-source") {
            val repo = uri.getQueryParameter("github")?.let { "https://github.com/$it" }
                ?: uri.getQueryParameter("gitlab")?.let { "https://gitlab.com/$it" }
            if (repo != null) return canonicalSourceKeys(repo)
        }
    }
    val slug = canonicalGitHubSlug(uri)
        ?: Regex("^([\\w.-]+)/([\\w.-]+)$").matchEntire(value)?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val path = uri.encodedPath.orEmpty().trimEnd('/').replace(Regex("\\.git$", RegexOption.IGNORE_CASE), "")
    val normalizedUrl = if (scheme in setOf("http", "https") && !host.isNullOrBlank()) {
        val port = uri.port.takeIf { it > 0 && !((scheme == "https" && it == 443) || (scheme == "http" && it == 80)) }
            ?.let { ":$it" }.orEmpty()
        "$scheme://$host$port$path" + uri.encodedQuery?.let { "?$it" }.orEmpty()
    } else null
    return buildSet {
        normalizedUrl?.let { add("url:$it") }
        slug?.let { add("gh:${it.lowercase(Locale.ROOT).removeSuffix(".git")}") }
    }
}

private fun canonicalGitHubSlug(uri: Uri): String? {
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    val parts = uri.pathSegments
    val slugParts = when (host) {
        "github.com" -> parts.take(2)
        "raw.githubusercontent.com" -> parts.take(2)
        "api.github.com" -> if (parts.firstOrNull() == "repos") parts.drop(1).take(2) else emptyList()
        else -> emptyList()
    }
    if (slugParts.size < 2) return null
    val owner = slugParts[0].trim().trim('/').lowercase(Locale.ROOT)
    val repo = slugParts[1].substringBefore('/').trim().lowercase(Locale.ROOT).removeSuffix(".git")
    return "$owner/$repo".takeIf { owner.isNotBlank() && repo.isNotBlank() }
}

@Composable
private fun rememberRemoteLinksState(check: (String) -> RemoteUrlCheck): RemoteLinksState {
    val links = rememberSaveable(
        stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it })
    ) { mutableStateOf(emptyList()) }
    val input = rememberSaveable { mutableStateOf("") }
    return remember(check) { RemoteLinksState(check, links, input) }
}

private val RemoteSourceRejection.hintRes: Int
    get() = when (this) {
        // Says what a link has to be rather than only that it is not
        RemoteSourceRejection.Invalid -> R.string.sources_dialog_url_invalid
        else -> messageRes
    }

/** The link without its scheme, the way it reads in the address bar. */
internal fun String.bareLink() = substringAfter("://").removePrefix("www.")

private fun String.linkHost() = bareLink().substringBefore('/').lowercase(Locale.US)

/** The code host whose links name a repository. Links to any other host serve a bundle file. */
private fun String.codeHost() = CodeHost.of(linkHost())

/** A repository as owner/repo, anything else as its bare link. */
internal fun remoteLinkTitle(link: String): String {
    val bare = link.bareLink()
    val segments = bare.substringAfter('/', "").split('/').filter { it.isNotBlank() }
    return if (link.codeHost() != null && segments.size >= 2) "${segments[0]}/${segments[1]}" else bare
}

internal fun remoteLinkIcon(link: String): ImageVector = link.codeHost()?.icon ?: MaterialSymbols.Rounded.Link

@Composable
private fun LinkField(state: RemoteLinksState, showHint: Boolean) {
    val context = LocalContext.current
    val messages = koinInject<UserMessages>()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val inputCheck = state.inputCheck
    val noLinksMessage = stringResource(R.string.sources_dialog_paste_no_links)

    // Links taken from the field into the list above push it down, out of the room the keyboard
    // leaves, so it is scrolled back into view as the list grows. A row removed leaves it be
    val inView = remember { BringIntoViewRequester() }
    val entryCount = state.entries.size
    var lastEntryCount by remember { mutableIntStateOf(entryCount) }
    LaunchedEffect(entryCount) {
        if (entryCount > lastEntryCount) {
            // The rows are laid out by the next frame, and the field's place with them
            withFrameNanos { }
            inView.bringIntoView()
        }
        lastEntryCount = entryCount
    }

    Column(
        modifier = Modifier.bringIntoViewRequester(inView),
        verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)
    ) {
        AppDialogTextField(
            value = state.input,
            onValueChange = state::onInputChange,
            label = { Text(stringResource(R.string.sources_dialog_remote_url)) },
            placeholder = { Text(REMOTE_URL_PLACEHOLDER) },
            trailingIcon = {
                if (state.input.isNotEmpty()) {
                    TooltipIconButton(
                        onClick = state::clearInput,
                        icon = MaterialSymbols.Rounded.Close,
                        contentDescription = stringResource(R.string.clear)
                    )
                } else {
                    TooltipIconButton(
                        onClick = {
                            scope.launch {
                                val text = clipboard.getClipEntry()?.clipData
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)?.coerceToText(context)?.toString()
                                if (text == null || !state.paste(text)) {
                                    messages.show(noLinksMessage)
                                }
                            }
                        },
                        icon = MaterialSymbols.Rounded.Content_paste,
                        contentDescription = stringResource(R.string.paste)
                    )
                }
            },
            isError = inputCheck is RemoteUrlCheck.Rejected,
            keyboardOptions = RemoteUrlKeyboardOptions,
            keyboardActions = KeyboardActions(onDone = { state.commitInput() })
        )

        // What a link can be until the first source is in the list
        RemoteUrlCheckHint(check = inputCheck, showHint = showHint)
    }
}

/** Keyboard for typing a source link, which takes it as written. */
internal val RemoteUrlKeyboardOptions = KeyboardOptions(
    // A keyboard fixing "github" into "GitHub" or capitalizing a pasted link rewrites it
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri,
    imeAction = ImeAction.Done
)

/**
 * Live validation feedback on a link being typed, from its [check]. Without a link to check, it
 * says what a link can be where [showHint] is set, and is gone otherwise.
 */
@Composable
internal fun RemoteUrlCheckHint(check: RemoteUrlCheck?, showHint: Boolean) {
    AnimatedVisibility(visible = check != null || showHint) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)
        ) {
            val (icon, color, text) = when (check) {
                is RemoteUrlCheck.Rejected -> Triple(
                    MaterialSymbols.Rounded.Error,
                    MaterialTheme.colorScheme.error,
                    stringResource(check.reason.hintRes)
                )
                is RemoteUrlCheck.Accepted -> Triple(
                    MaterialSymbols.Rounded.Check_circle,
                    SemanticTone.Success.accent,
                    stringResource(R.string.sources_dialog_url_valid)
                )
                null -> Triple(
                    MaterialSymbols.Rounded.Info,
                    dialogSecondaryTextColor(),
                    stringResource(R.string.sources_dialog_remote_url_hint)
                )
            }
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

/** The links and files waiting to be added, links first. */
@Composable
private fun PendingSources(
    state: RemoteLinksState,
    files: List<PickedBundle>,
    onRemoveFile: (Uri) -> Unit,
    showOrdinals: Boolean = false,
) {
    if (state.entries.isEmpty() && files.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(CompactCardSpacing)) {
        state.entries.forEachIndexed { index, entry ->
            val rejection = (entry.check as? RemoteUrlCheck.Rejected)?.reason
            PendingSourceRow(
                icon = remoteLinkIcon(entry.link),
                title = remoteLinkTitle(entry.link),
                detail = rejection?.let { stringResource(it.hintRes) } ?: entry.link.bareLink(),
                isError = rejection != null,
                onRemove = { state.remove(entry.link) },
                ordinal = (index + 1).takeIf { showOrdinals },
            )
        }
        files.forEach { file ->
            val check = file.check
            PendingSourceRow(
                icon = if (check is LocalFileCheck.Update) MaterialSymbols.Rounded.Update else MaterialSymbols.Rounded.Draft.autoMirrored,
                title = file.name,
                detail = when (check) {
                    is LocalFileCheck.Update -> stringResource(R.string.sources_dialog_local_updates, check.title)
                    LocalFileCheck.Duplicate -> stringResource(R.string.sources_management_already_exists)
                    LocalFileCheck.NotBundle -> stringResource(R.string.sources_dialog_local_invalid_extension)
                    // Where it is read from, while it is checked and once it is known to be new
                    else -> file.uri.toFilePath().takeIf { it.startsWith("/") }?.substringBeforeLast("/").orEmpty()
                },
                isError = check == LocalFileCheck.Duplicate || check == LocalFileCheck.NotBundle,
                onRemove = { onRemoveFile(file.uri) },
            )
        }
    }
}

/**
 * One source waiting to be added, a link or a file: what it is, and under it where it comes
 * from or why it will be left out.
 */
@Composable
private fun PendingSourceRow(
    icon: ImageVector,
    title: String,
    detail: String,
    isError: Boolean,
    onRemove: () -> Unit,
    ordinal: Int? = null,
) {
    val colors = MaterialTheme.colorScheme
    CompactListCard(onClick = null) {
        if (ordinal != null) {
            Box(
                modifier = Modifier
                    .size(CompactCardGlyphSize)
                    .background(colors.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$ordinal.",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSecondaryContainer
                )
            }
        } else if (isError) {
            CompactCardIconTile(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer) {
                Icon(MaterialSymbols.Rounded.Error, contentDescription = null, modifier = Modifier.size(CompactCardGlyphSize))
            }
        } else {
            CompactCardIconTile {
                Icon(icon, contentDescription = null, modifier = Modifier.size(CompactCardGlyphSize))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        TooltipIconButton(
            onClick = onRemove,
            icon = MaterialSymbols.Rounded.Close,
            contentDescription = stringResource(R.string.remove),
            tint = dialogSecondaryTextColor()
        )
    }
}
