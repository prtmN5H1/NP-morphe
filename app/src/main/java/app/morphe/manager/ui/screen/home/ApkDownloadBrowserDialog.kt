/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.morphe.manager.R
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.ui.screen.shared.DialogAction
import app.morphe.manager.ui.screen.shared.DialogActionEmphasis
import app.morphe.manager.ui.screen.shared.DialogHostScope
import app.morphe.manager.ui.screen.shared.ListDialog
import app.morphe.manager.ui.screen.shared.ListDialogHeader
import app.morphe.manager.ui.screen.shared.ListDialogHeaderIcon
import app.morphe.manager.ui.screen.shared.LocalDialogBarInsets
import app.morphe.manager.ui.screen.shared.SettingsItem
import app.morphe.manager.ui.screen.shared.SettingsSwitchItem
import app.morphe.manager.ui.screen.shared.TitleAction
import app.morphe.manager.ui.screen.shared.UserMessages
import app.morphe.manager.util.APK_MIMETYPE
import app.morphe.manager.util.APK_BROWSER_DEFAULT_URL
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import org.koin.compose.koinInject
import kotlinx.coroutines.launch
import java.util.Locale

/** Browser for finding a base APK; downloads stay app-private until the patched app installs. */
@Composable
internal fun DialogHostScope.ApkDownloadBrowserDialog(
    initialUrl: String,
    appName: String? = null,
    packageName: String? = null,
    requestedVersion: String? = null,
    onDismiss: () -> Unit,
    onApkDownloaded: (uri: Uri, url: String, downloadId: Long, permanent: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val messages = koinInject<UserMessages>()
    val prefs: PreferencesManager = koinInject()
    val lifecycleOwner = LocalLifecycleOwner.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val downloadManager = remember(context) { context.getSystemService(DownloadManager::class.java) }
    val currentOnApkDownloaded by rememberUpdatedState(onApkDownloaded)
    val downloadPermissionDenied = stringResource(R.string.home_apk_browser_storage_permission_denied)
    val browserDefaultUrl by prefs.apkBrowserDefaultUrl.getAsState()
    var webView by remember { mutableStateOf<WebView?>(null) }
    val builtInDefaultUrl = remember(appName, packageName, requestedVersion) {
        expandApkBrowserTemplate(APK_BROWSER_DEFAULT_URL, appName, packageName, requestedVersion)
    }
    val configuredStartUrl = remember(initialUrl, browserDefaultUrl, builtInDefaultUrl) {
        browserDefaultUrl.takeIf(String::isNotBlank)
            ?.let { normalizeApkBrowserAddress(expandApkBrowserTemplate(it, appName, packageName, requestedVersion)) }
            ?: initialUrl.takeIf { Uri.parse(it).host.orEmpty().endsWith("apkmirror.com", ignoreCase = true) }
            ?: builtInDefaultUrl
    }
    var currentUrl by remember(configuredStartUrl) { mutableStateOf(configuredStartUrl) }
    var addressText by remember(configuredStartUrl) { mutableStateOf(configuredStartUrl) }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var pageProgress by remember { mutableIntStateOf(0) }
    var navigationRevision by remember { mutableIntStateOf(0) }
    var savePermanently by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var waitingForStoragePermission by remember { mutableStateOf(false) }
    val pendingDownloads = remember { mutableMapOf<Long, PendingBrowserDownload>() }
    val pendingPermissionDownload = remember { mutableStateOf<PendingBrowserDownload?>(null) }

    fun navigateToAddress(value: String) {
        val destination = normalizeApkBrowserAddress(value)
        addressText = destination
        currentUrl = destination
        keyboardController?.hide()
        webView?.loadUrl(destination)
    }

    fun resetBrowserDefault() {
        scope.launch { prefs.apkBrowserDefaultUrl.update("") }
        addressText = builtInDefaultUrl
        currentUrl = builtInDefaultUrl
        webView?.loadUrl(builtInDefaultUrl)
    }

    val canNavigateBack = remember(webView, navigationRevision) { webView?.canGoBack() == true }
    val canNavigateForward = remember(webView, navigationRevision) { webView?.canGoForward() == true }

    fun enqueueDownload(download: PendingBrowserDownload) {
        val manager = downloadManager ?: run {
            messages.show(R.string.home_apk_browser_download_failed)
            return
        }
        val fileName = safeDownloadName(
            URLUtil.guessFileName(download.url, download.contentDisposition, download.mimeType)
        )
        if (!isApkFileName(fileName)) {
            messages.show(R.string.home_apk_browser_not_apk)
            return
        }

        runCatching {
            val request = DownloadManager.Request(Uri.parse(download.url))
                .setMimeType(download.mimeType?.takeIf(String::isNotBlank) ?: APK_MIMETYPE)
                .setTitle(fileName)
                .setDescription(context.getString(R.string.home_apk_browser_download_description))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .addRequestHeader("User-Agent", download.userAgent)
            download.referer?.let { request.addRequestHeader("Referer", it) }
            CookieManager.getInstance().getCookie(download.url)
                ?.takeIf(String::isNotBlank)
                ?.let { request.addRequestHeader("Cookie", it) }

            if (download.permanent) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            } else {
                request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            manager.enqueue(request).also { id ->
                pendingDownloads[id] = download
                downloading = true
            }
        }.onFailure {
            Log.w("MorpheApkBrowser", "Could not start the APK download", it)
            messages.show(R.string.home_apk_browser_download_failed)
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingPermissionDownload.value
        pendingPermissionDownload.value = null
        waitingForStoragePermission = false
        if (granted && pending != null) enqueueDownload(pending)
        else if (!granted) messages.show(downloadPermissionDenied)
    }

    fun startDownload(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        referer: String?,
    ) {
        if (downloading) {
            messages.show(R.string.home_apk_browser_download_in_progress)
            return
        }
        val pending = PendingBrowserDownload(
            url = url,
            userAgent = userAgent,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            referer = referer,
            permanent = savePermanently,
        )
        val needsLegacyPermission = pending.permanent && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
        if (needsLegacyPermission && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPermissionDownload.value = pending
            waitingForStoragePermission = true
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            enqueueDownload(pending)
        }
    }

    fun dismissBrowser() {
        if (downloading || waitingForStoragePermission) {
            messages.show(R.string.home_apk_browser_download_in_progress)
        } else {
            onDismiss()
        }
    }

    DisposableEffect(context, downloadManager) {
        if (downloadManager == null) return@DisposableEffect onDispose { }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                val pending = pendingDownloads.remove(id) ?: return
                val query = DownloadManager.Query().setFilterById(id)
                downloadManager.query(query).use { cursor ->
                    val successful = cursor.moveToFirst() && cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    ) == DownloadManager.STATUS_SUCCESSFUL
                    if (successful) {
                        val uri = downloadManager.getUriForDownloadedFile(id)
                        if (uri != null) {
                            downloading = pendingDownloads.isNotEmpty()
                            currentOnApkDownloaded(uri, pending.url, id, pending.permanent)
                        } else {
                            downloading = pendingDownloads.isNotEmpty()
                            messages.show(R.string.home_apk_browser_download_failed)
                        }
                    } else {
                        downloading = pendingDownloads.isNotEmpty()
                        messages.show(R.string.home_apk_browser_download_failed)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    DisposableEffect(lifecycleOwner, webView) {
        val current = webView ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> current.onResume()
                Lifecycle.Event.ON_PAUSE -> current.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(webView) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
        }
    }

    BackHandler(enabled = downloading || waitingForStoragePermission || webView?.canGoBack() == true) {
        if (downloading || waitingForStoragePermission) {
            messages.show(R.string.home_apk_browser_download_in_progress)
        } else {
            webView?.goBack()
        }
    }

    ListDialog(
        onDismissRequest = onDismiss,
        accentColor = null,
        header = {
            ListDialogHeader(
                icon = { modifier ->
                    ListDialogHeaderIcon(
                        MaterialSymbols.Rounded.Language,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = modifier
                    )
                },
                title = pageTitle?.takeIf(String::isNotBlank) ?: stringResource(R.string.home_apk_browser_title),
                subtitle = Uri.parse(currentUrl).host.orEmpty(),
                alwaysShowBand = true,
                actions = {
                    TitleAction(
                        icon = MaterialSymbols.Rounded.Arrow_back,
                        contentDescription = stringResource(R.string.back),
                        onClick = { if (webView?.canGoBack() == true) webView?.goBack() else dismissBrowser() }
                    )
                    TitleAction(
                        icon = MaterialSymbols.Rounded.Arrow_forward,
                        contentDescription = stringResource(R.string.npmorphe_apk_browser_forward),
                        onClick = { webView?.goForward() },
                        enabled = canNavigateForward
                    )
                    TitleAction(
                        icon = MaterialSymbols.Rounded.Refresh,
                        contentDescription = stringResource(R.string.refresh),
                        onClick = { webView?.reload() }
                    )
                    TitleAction(
                        icon = MaterialSymbols.Rounded.Close,
                        contentDescription = stringResource(R.string.close),
                        onClick = ::dismissBrowser
                    )
                },
                content = {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = addressText,
                            onValueChange = { addressText = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { androidx.compose.material3.Text(stringResource(R.string.npmorphe_apk_browser_address)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { navigateToAddress(addressText) })
                        )
                        IconButton(onClick = { navigateToAddress(addressText) }) {
                            Icon(MaterialSymbols.Rounded.Search, contentDescription = stringResource(R.string.npmorphe_apk_browser_go))
                        }
                    }
                    SettingsItem(
                        onClick = {
                            scope.launch { prefs.apkBrowserDefaultUrl.update(currentUrl) }
                            messages.show(R.string.home_apk_browser_default_saved)
                        },
                        icon = MaterialSymbols.Rounded.Bookmark,
                        title = stringResource(R.string.home_apk_browser_set_default),
                        subtitle = currentUrl
                    )
                    SettingsItem(
                        onClick = ::resetBrowserDefault,
                        icon = MaterialSymbols.Rounded.Refresh,
                        title = stringResource(R.string.home_apk_browser_reset_default),
                        subtitle = stringResource(R.string.home_apk_browser_reset_default_description)
                    )
                    SettingsSwitchItem(
                        checked = savePermanently,
                        onToggle = { savePermanently = !savePermanently },
                        icon = MaterialSymbols.Rounded.Save,
                        title = stringResource(R.string.home_apk_browser_save_permanently),
                        subtitle = stringResource(R.string.home_apk_browser_save_permanently_description)
                    )
                    if (pageProgress in 1..99) {
                        LinearProgressIndicator(
                            progress = { pageProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (waitingForStoragePermission) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            )
        },
        footer = null,
        content = {
            AndroidView(
                modifier = Modifier
                    .padding(LocalDialogBarInsets.current)
                    .fillMaxSize(),
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(false)
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                currentUrl = url
                                addressText = url
                                navigationRevision++
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val scheme = request.url.scheme?.lowercase(Locale.ROOT)
                                if (scheme == "http" || scheme == "https") return false
                                return runCatching {
                                    view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                    true
                                }.getOrDefault(false)
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                pageProgress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView, title: String?) {
                                pageTitle = title
                            }
                        }
                        setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                            startDownload(
                                url = url,
                                userAgent = userAgent,
                                contentDisposition = contentDisposition,
                                mimeType = mimeType,
                                referer = currentUrl,
                            )
                        })
                        loadUrl(configuredStartUrl)
                    }
                }
            )
        }
    )
}

private fun expandApkBrowserTemplate(
    template: String,
    appName: String?,
    packageName: String?,
    requestedVersion: String?
): String {
    val query = appName?.takeIf(String::isNotBlank) ?: packageName?.takeIf(String::isNotBlank) ?: "Android apps"
    return template
        .replace("{query}", Uri.encode(query))
        .replace("{app}", Uri.encode(appName.orEmpty()))
        .replace("{package}", Uri.encode(packageName.orEmpty()))
        .replace("{version}", Uri.encode(requestedVersion.orEmpty()))
}

private fun normalizeApkBrowserAddress(value: String): String {
    val input = value.trim()
    if (input.isEmpty()) return APK_BROWSER_DEFAULT_URL.replace("{query}", "Android%20apps")
    val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(input)
    val candidate = if (hasScheme) input else if (!input.any(Char::isWhitespace) && input.contains('.')) {
        "https://$input"
    } else {
        "https://www.google.com/search?q=${Uri.encode(input)}"
    }
    val uri = runCatching { Uri.parse(candidate) }.getOrNull()
    val isWebUrl = uri != null && (uri.scheme == "http" || uri.scheme == "https") &&
            !uri.host.isNullOrBlank()
    return if (isWebUrl) candidate
    else "https://www.google.com/search?q=${Uri.encode(input)}"
}

private data class PendingBrowserDownload(
    val url: String,
    val userAgent: String,
    val contentDisposition: String?,
    val mimeType: String?,
    val referer: String?,
    val permanent: Boolean,
)

private fun safeDownloadName(name: String) =
    name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(150).ifBlank { "download.apk" }

private fun isApkFileName(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return listOf(".apk", ".apkm", ".apks", ".xapk").any(lower::endsWith)
}
