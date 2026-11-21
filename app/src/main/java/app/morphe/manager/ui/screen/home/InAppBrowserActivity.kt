/*
 * Copyright 2026 Morphe.
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
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.morphe.manager.R
import app.morphe.manager.domain.manager.APK_BROWSER_APP_DEFAULT_URL_SEPARATOR
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.util.APK_MIMETYPE
import app.morphe.manager.util.APK_BROWSER_DEFAULT_URL
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Locale

/** Full-window browser Activity; the WebView is never attached to a Compose dialog window. */
class InAppBrowserActivity : ComponentActivity() {
    private val preferences: PreferencesManager by inject()
    private var browserWebView: WebView? = null
    private var addressField: EditText? = null
    private var progressBar: ProgressBar? = null
    private var currentUrl = ""
    private var targetPackageName: String? = null
    private var appName: String? = null
    private var requestedVersion: String? = null
    private var permanentDownload = false
    private var downloading = false
    private var pendingPermissionDownload: PendingDownload? = null
    private val downloads = mutableMapOf<Long, PendingDownload>()
    private var receiverRegistered = false

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingPermissionDownload
        pendingPermissionDownload = null
        if (granted && pending != null) enqueueDownload(pending)
        else if (!granted) toast(R.string.home_apk_browser_storage_permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        appName = intent.getStringExtra(EXTRA_APP_NAME)
        requestedVersion = intent.getStringExtra(EXTRA_REQUESTED_VERSION)
        val standalone = intent.getBooleanExtra(EXTRA_STANDALONE, false)
        permanentDownload = standalone || targetPackageName.isNullOrBlank()
        window.statusBarColor = android.graphics.Color.rgb(18, 18, 18)
        window.navigationBarColor = android.graphics.Color.rgb(18, 18, 18)
        window.decorView.systemUiVisibility = 0
        registerDownloadReceiver()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (downloading) toast(R.string.home_apk_browser_download_in_progress)
                else if (browserWebView?.canGoBack() == true) browserWebView?.goBack()
                else finish()
            }
        })

        lifecycleScope.launch {
            val globalDefault = preferences.apkBrowserDefaultUrl.get()
            val appDefaults = preferences.apkBrowserAppDefaultUrls.get()
            val packageId = targetPackageName
            val appDefault = packageId?.let { id ->
                appDefaults.firstNotNullOfOrNull { record ->
                    val split = record.indexOf(APK_BROWSER_APP_DEFAULT_URL_SEPARATOR)
                    if (split > 0 && record.substring(0, split) == id) record.substring(split + 1) else null
                }
            }
            val builtIn = expandApkBrowserTemplate(APK_BROWSER_DEFAULT_URL, appName, targetPackageName, requestedVersion)
            val supplied = intent.getStringExtra(EXTRA_INITIAL_URL).orEmpty()
            val startUrl = (appDefault ?: globalDefault).takeIf(String::isNotBlank)
                ?.let { normalizeApkBrowserAddress(expandApkBrowserTemplate(it, appName, targetPackageName, requestedVersion)) }
                ?: supplied.takeIf { raw ->
                    val uri = runCatching { Uri.parse(raw) }.getOrNull()
                    uri != null && (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
                }?.let { normalizeApkBrowserAddress(expandApkBrowserTemplate(it, appName, targetPackageName, requestedVersion)) }
                ?: builtIn
            currentUrl = startUrl
            setContentView(createBrowserLayout(startUrl))
            browserWebView?.loadUrl(startUrl)
        }
    }

    private fun createBrowserLayout(startUrl: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(18, 18, 18))
            fitsSystemWindows = true
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        toolbar.addView(actionText("‹", getString(R.string.back)) {
            if (browserWebView?.canGoBack() == true) browserWebView?.goBack() else finish()
        })
        toolbar.addView(actionText("›", getString(R.string.npmorphe_apk_browser_forward)) {
            browserWebView?.goForward()
        })
        toolbar.addView(actionText("↻", getString(R.string.refresh)) { browserWebView?.reload() })
        val address = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.npmorphe_apk_browser_address)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.LTGRAY)
            setPadding(dp(8), 0, dp(8), 0)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(startUrl)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    navigateToAddress(text.toString())
                    true
                } else false
            }
        }
        addressField = address
        toolbar.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(actionText("★", getString(R.string.home_apk_browser_set_default), ::saveCurrentAsDefault))
        toolbar.addView(actionText(if (permanentDownload) "⬇" else "↓", getString(R.string.home_apk_browser_save_permanently)) {
            permanentDownload = !permanentDownload
            toast(if (permanentDownload) R.string.home_apk_browser_save_permanently else R.string.home_apk_browser_download_description)
        })
        toolbar.addView(actionText("×", getString(R.string.close)) { closeBrowser() })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(52)))

        val progressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = 0
        }
        progressBar = progressView
        root.addView(progressView, LinearLayout.LayoutParams(-1, dp(3)))

        val view = WebView(this@InAppBrowserActivity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(android.graphics.Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.userAgentString = settings.userAgentString
                .replace("; wv", "", ignoreCase = true)
                .replace("Version/4.0 ", "", ignoreCase = true)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    currentUrl = url
                    addressField?.setText(url)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase(Locale.ROOT)
                    if (scheme == "http" || scheme == "https") return false
                    return runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        true
                    }.getOrDefault(false)
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    val uri = Uri.parse(url)
                    val scheme = uri.scheme?.lowercase(Locale.ROOT)
                    if (scheme == "http" || scheme == "https") return false
                    return runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    }.getOrDefault(false)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressBar?.progress = newProgress
                    progressBar?.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                startDownload(url, userAgent, contentDisposition, mimeType, currentUrl)
            })
        }
        browserWebView = view
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun actionText(glyph: String, description: String, action: () -> Unit) =
        TextView(this).apply {
            text = glyph
            contentDescription = description
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(48))
        }

    private fun navigateToAddress(value: String) {
        val destination = normalizeApkBrowserAddress(value)
        currentUrl = destination
        addressField?.setText(destination)
        browserWebView?.loadUrl(destination)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.let { input -> addressField?.windowToken?.let { token -> input.hideSoftInputFromWindow(token, 0) } }
    }

    private fun saveCurrentAsDefault() {
        if (!currentUrl.startsWith("http://") && !currentUrl.startsWith("https://")) return
        lifecycleScope.launch {
            val packageId = targetPackageName
            if (packageId.isNullOrBlank()) {
                preferences.apkBrowserDefaultUrl.update(currentUrl)
            } else {
                val prefix = packageId + APK_BROWSER_APP_DEFAULT_URL_SEPARATOR
                val current = preferences.apkBrowserAppDefaultUrls.get()
                preferences.apkBrowserAppDefaultUrls.update(
                    (current.filterNot { it.startsWith(prefix) } + (prefix + currentUrl)).toSet()
                )
            }
        }
        toast(R.string.home_apk_browser_default_saved)
    }

    private fun startDownload(url: String, userAgent: String, disposition: String?, mimeType: String?, referer: String?) {
        if (downloading) {
            toast(R.string.home_apk_browser_download_in_progress)
            return
        }
        val pending = PendingDownload(url, userAgent, disposition, mimeType, referer, permanentDownload)
        val needsPermission = pending.permanent && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingPermissionDownload = pending
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else enqueueDownload(pending)
    }

    private fun enqueueDownload(pending: PendingDownload) {
        val manager = getSystemService(DownloadManager::class.java) ?: return toast(R.string.home_apk_browser_download_failed)
        val fileName = safeDownloadName(URLUtil.guessFileName(pending.url, pending.disposition, pending.mimeType))
        if (!isApkFileName(fileName)) return toast(R.string.home_apk_browser_not_apk)
        runCatching {
            val request = DownloadManager.Request(Uri.parse(pending.url))
                .setMimeType(pending.mimeType?.takeIf(String::isNotBlank) ?: APK_MIMETYPE)
                .setTitle(fileName)
                .setDescription(getString(R.string.home_apk_browser_download_description))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .addRequestHeader("User-Agent", pending.userAgent)
            pending.referer?.let { request.addRequestHeader("Referer", it) }
            CookieManager.getInstance().getCookie(pending.url)?.takeIf(String::isNotBlank)?.let {
                request.addRequestHeader("Cookie", it)
            }
            if (pending.permanent) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            } else {
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val id = manager.enqueue(request)
            downloads[id] = pending
            downloading = true
            toast(R.string.home_apk_browser_download_description)
        }.onFailure {
            android.util.Log.w("NPmorpheBrowser", "APK download could not be started", it)
            toast(R.string.home_apk_browser_download_failed)
        }
    }

    private fun registerDownloadReceiver() {
        val manager = getSystemService(DownloadManager::class.java) ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                val pending = downloads.remove(id) ?: return
                val successful = runCatching {
                    manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                    }
                }.getOrDefault(false)
                downloading = downloads.isNotEmpty()
                val uri = if (successful) manager.getUriForDownloadedFile(id) else null
                if (uri == null) {
                    toast(R.string.home_apk_browser_download_failed)
                    return
                }
                setResult(RESULT_OK, Intent().apply {
                    putExtra(EXTRA_RESULT_URI, uri.toString())
                    putExtra(EXTRA_RESULT_URL, pending.url)
                    putExtra(EXTRA_RESULT_DOWNLOAD_ID, id)
                    putExtra(EXTRA_RESULT_PERMANENT, pending.permanent)
                })
                finish()
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_NOT_EXPORTED)
        downloadReceiver = receiver
        receiverRegistered = true
    }

    private var downloadReceiver: BroadcastReceiver? = null

    private fun closeBrowser() {
        if (downloading) toast(R.string.home_apk_browser_download_in_progress) else finish()
    }

    private fun toast(resource: Int) = Toast.makeText(this, resource, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun disposeWebView(view: WebView) {
        runCatching { view.stopLoading() }
        runCatching { view.setDownloadListener(null) }
        runCatching { view.webChromeClient = null }
        runCatching { view.webViewClient = WebViewClient() }
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.destroy() }
        if (browserWebView === view) browserWebView = null
    }

    override fun onDestroy() {
        browserWebView?.let(::disposeWebView)
        if (receiverRegistered) downloadReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        receiverRegistered = false
        super.onDestroy()
    }

    private data class PendingDownload(
        val url: String,
        val userAgent: String,
        val disposition: String?,
        val mimeType: String?,
        val referer: String?,
        val permanent: Boolean
    )

    companion object {
        const val EXTRA_INITIAL_URL = "npmorphe.browser.initial_url"
        const val EXTRA_PACKAGE_NAME = "npmorphe.browser.package_name"
        const val EXTRA_APP_NAME = "npmorphe.browser.app_name"
        const val EXTRA_REQUESTED_VERSION = "npmorphe.browser.requested_version"
        const val EXTRA_STANDALONE = "npmorphe.browser.standalone"
        const val EXTRA_RESULT_URI = "npmorphe.browser.result_uri"
        const val EXTRA_RESULT_URL = "npmorphe.browser.result_url"
        const val EXTRA_RESULT_DOWNLOAD_ID = "npmorphe.browser.result_download_id"
        const val EXTRA_RESULT_PERMANENT = "npmorphe.browser.result_permanent"

        fun intent(
            context: Context,
            initialUrl: String,
            packageName: String?,
            appName: String?,
            requestedVersion: String?,
            isStandalone: Boolean
        ) = Intent(context, InAppBrowserActivity::class.java).apply {
            putExtra(EXTRA_INITIAL_URL, initialUrl)
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_APP_NAME, appName)
            putExtra(EXTRA_REQUESTED_VERSION, requestedVersion)
            putExtra(EXTRA_STANDALONE, isStandalone)
        }
    }
}

private fun expandApkBrowserTemplate(template: String, appName: String?, packageName: String?, requestedVersion: String?): String {
    val query = appName?.takeIf(String::isNotBlank) ?: packageName?.takeIf(String::isNotBlank) ?: "Android apps"
    return template.replace("{query}", Uri.encode(query))
        .replace("{app}", Uri.encode(appName.orEmpty()))
        .replace("{package}", Uri.encode(packageName.orEmpty()))
        .replace("{version}", Uri.encode(requestedVersion.orEmpty()))
}

private fun normalizeApkBrowserAddress(value: String): String {
    val input = value.trim()
    if (input.isEmpty()) return APK_BROWSER_DEFAULT_URL.replace("{query}", "Android%20apps")
    val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(input)
    val candidate = if (hasScheme) input else if (!input.any(Char::isWhitespace) && input.contains('.')) {
        "https://" + input
    } else "https://www.google.com/search?q=" + Uri.encode(input)
    val uri = runCatching { Uri.parse(candidate) }.getOrNull()
    return if (uri != null && (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) candidate
    else "https://www.google.com/search?q=" + Uri.encode(input)
}

private fun safeDownloadName(name: String) =
    name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(150).ifBlank { "download.apk" }

private fun isApkFileName(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return listOf(".apk", ".apkm", ".apks", ".xapk").any(lower::endsWith)
}
