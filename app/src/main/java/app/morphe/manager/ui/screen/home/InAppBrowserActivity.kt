/*
 * Copyright 2026 Morphe.
 */

package app.morphe.manager.ui.screen.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.morphe.manager.R
import app.morphe.manager.domain.manager.APK_BROWSER_APP_DEFAULT_URL_SEPARATOR
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.repository.ApkDownloadManager
import app.morphe.manager.domain.repository.ApkDownloadStatus
import app.morphe.manager.domain.repository.BrowserDataRepository
import app.morphe.manager.domain.repository.UserScriptRunAt
import app.morphe.manager.service.ApkDownloadService
import app.morphe.manager.util.APK_BROWSER_DEFAULT_URL
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale

data class DownloadedBaseApk(
    val file: File,
    val requestedVersion: String?,
    val sourceUrl: String?
)

/** Full-window browser Activity; its WebView is never attached to a Compose dialog window. */
class InAppBrowserActivity : ComponentActivity() {
    private val preferences: PreferencesManager by inject()
    private val downloadManager: ApkDownloadManager by inject()
    private val browserData: BrowserDataRepository by inject()
    private var browserWebView: WebView? = null
    private var browserWebViewClient: WebViewClient? = null
    private var browserWebChromeClient: WebChromeClient? = null
    private var addressContainer: FrameLayout? = null
    private var browserTabHost: FrameLayout? = null
    private val browserTabs = mutableListOf<WebView>()
    private var desktopMode = false
    private var addressLabel: TextView? = null
    private var addressEditor: EditText? = null
    private var progressBar: ProgressBar? = null
    private var bottomDownloadStatus: TextView? = null
    private var toolbarDownloadStatus: TextView? = null
    private var reloadButton: TextView? = null
    private var downloadCard: LinearLayout? = null
    private var downloadNameView: TextView? = null
    private var downloadProgressView: ProgressBar? = null
    private var downloadDetailsView: TextView? = null
    private var pageLoading = false
    private var currentUrl = ""
    private var previousSafeUrl: String? = null
    private var recoveringCacheMiss = false
    private var targetPackageName: String? = null
    private var appName: String? = null
    private var requestedVersion: String? = null
    private var activeDownloadId: String? = null
    private val activeDownloadIds = linkedSetOf<String>()
    private var returnOnDownload = false
    private var downloadObserverJob: Job? = null
    private lateinit var mobileUserAgent: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.rgb(18, 18, 18)
        window.navigationBarColor = Color.rgb(25, 25, 25)
        window.decorView.systemUiVisibility = 0
        targetPackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        appName = intent.getStringExtra(EXTRA_APP_NAME)
        requestedVersion = intent.getStringExtra(EXTRA_REQUESTED_VERSION)
        returnOnDownload = intent.getBooleanExtra(EXTRA_RETURN_ON_DOWNLOAD, false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (browserWebView?.canGoBack() == true) {
                    browserWebView?.goBack()
                } else {
                    finish()
                }
            }
        })

        lifecycleScope.launch {
            val packageId = targetPackageName
            val appDefault = packageId?.let { findAppDefaultUrl(preferences.apkBrowserAppDefaultUrls.get(), it) }
            val globalDefault = preferences.apkBrowserDefaultUrl.get().takeIf(String::isNotBlank)
            val savedDefault = (appDefault ?: globalDefault)
                ?.let { normalizeApkBrowserAddress(expandApkBrowserTemplate(it, appName, packageId, requestedVersion)) }
            val supplied = intent.getStringExtra(EXTRA_INITIAL_URL).orEmpty()
            val suppliedUrl = supplied.takeIf(::isHttpAddress)
                ?.let { normalizeApkBrowserAddress(expandApkBrowserTemplate(it, appName, packageId, requestedVersion)) }
            val builtIn = normalizeApkBrowserAddress(
                expandApkBrowserTemplate(APK_BROWSER_DEFAULT_URL, appName, packageId, requestedVersion)
            )
            val startUrl = savedDefault ?: suppliedUrl ?: builtIn
            if (isFinishing || isDestroyed) return@launch
            currentUrl = startUrl
            setContentView(createBrowserLayout())
            browserWebView?.loadUrl(startUrl)
            downloadObserverJob = lifecycleScope.launch {
                downloadManager.downloads.collect { records ->
                    val record = records.firstOrNull { it.id in activeDownloadIds } ?: return@collect
                    activeDownloadId = record.id
                    when (record.status) {
                        ApkDownloadStatus.QUEUED, ApkDownloadStatus.DOWNLOADING -> showDownloadCard(record.fileName, record.downloadedBytes, record.totalBytes, record.bytesPerSecond.toDouble())
                        ApkDownloadStatus.PAUSED -> downloadDetailsView?.text = getString(R.string.npmorphe_downloads_paused)
                        ApkDownloadStatus.FAILED -> {
                            activeDownloadIds.remove(record.id)
                            activeDownloadId = null
                            downloadDetailsView?.text = record.error ?: getString(R.string.npmorphe_browser_download_failed)
                            Toast.makeText(this@InAppBrowserActivity, record.error ?: getString(R.string.npmorphe_browser_download_failed), Toast.LENGTH_LONG).show()
                            updateNavigationButtons()
                        }
                        ApkDownloadStatus.COMPLETED -> {
                            activeDownloadIds.remove(record.id)
                            activeDownloadId = activeDownloadIds.lastOrNull()
                            val file = File(record.filePath)
                            rememberDownloadedFile(file, record.sourceUrl)
                            if (returnOnDownload && !isFinishing && !isDestroyed) {
                                setResult(RESULT_OK, Intent().apply {
                                    putExtra(EXTRA_RESULT_URI, Uri.fromFile(file).toString())
                                    putExtra(EXTRA_RESULT_FILE_PATH, file.absolutePath)
                                    putExtra(EXTRA_RESULT_URL, record.sourceUrl)
                                    putExtra(EXTRA_RESULT_DOWNLOAD_ID, NO_DOWNLOAD_MANAGER_ID)
                                    putExtra(EXTRA_RESULT_PERMANENT, false)
                                })
                                finish()
                            } else {
                                downloadCard?.visibility = View.GONE
                                Toast.makeText(this@InAppBrowserActivity, R.string.npmorphe_downloaded_apk_saved, Toast.LENGTH_SHORT).show()
                                updateNavigationButtons()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createBrowserLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DARK_BACKGROUND)
        }
        val statusInset = View(this)
        root.addView(statusInset, LinearLayout.LayoutParams(-1, 0))

        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        toolbar.addView(iconButton("×", getString(R.string.close), dp(44)) { requestClose() })
        val addressBox = FrameLayout(this).apply {
            background = roundedBackground(DARK_PILL, dp(24))
            minimumHeight = dp(44)
            isClickable = true
            isFocusable = true
        }
        val addressText = TextView(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), 0, dp(14), 0)
            text = cleanAddress(currentUrl)
            setOnClickListener { beginAddressEdit() }
        }
        addressLabel = addressText
        addressBox.addView(addressText, FrameLayout.LayoutParams(-1, -1))
        addressContainer = addressBox
        toolbar.addView(addressBox, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        toolbarDownloadStatus = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(7), 0, dp(7), 0)
            background = roundedBackground(DARK_PILL, dp(16))
            visibility = View.GONE
            contentDescription = getString(R.string.npmorphe_downloads_title)
            setOnClickListener { startActivity(DownloadedApksActivity.intent(this@InAppBrowserActivity)) }
        }
        toolbar.addView(toolbarDownloadStatus, LinearLayout.LayoutParams(-2, dp(30)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })
        toolbar.addView(iconButton("▢", getString(R.string.npmorphe_browser_tabs), dp(42), ::showTabsMenu))
        toolbar.addView(iconButton("⋮", getString(R.string.npmorphe_browser_more_options), dp(44), ::showBrowserMenu))
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))

        val progressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        progressBar = progressView
        root.addView(progressView, LinearLayout.LayoutParams(-1, dp(2)))

        val webView = WebView(this@InAppBrowserActivity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.setSupportMultipleWindows(true)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.userAgentString = settings.userAgentString
                .replace("; wv", "", ignoreCase = true)
                .replace("Version/4.0 ", "", ignoreCase = true)
            mobileUserAgent = settings.userAgentString
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            val sharedWebViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (view === browserWebView) {
                        if (currentUrl != url && isHttpAddress(currentUrl)) previousSafeUrl = currentUrl
                        currentUrl = url
                        addressLabel?.text = cleanAddress(url)
                        pageLoading = true
                        updateNavigationButtons()
                    }
                    browserData.scriptsFor(url, UserScriptRunAt.DOCUMENT_START).forEach { script ->
                        view.evaluateJavascript(script.source, null)
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    browserData.recordVisit(url, view.title.orEmpty())
                    browserData.scriptsFor(url, UserScriptRunAt.DOCUMENT_END).forEach { script ->
                        view.evaluateJavascript(script.source, null)
                    }
                    if (view === browserWebView) {
                        currentUrl = url
                        if (addressEditor == null) addressLabel?.text = cleanAddress(url)
                        pageLoading = false
                        progressBar?.visibility = View.GONE
                        updateNavigationButtons()
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    handleUrlScheme(request.url)

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    handleUrlScheme(Uri.parse(url))

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (browserData.shouldBlockRequest(request.url.toString())) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    val cacheMiss = error.description?.toString()?.contains("ERR_CACHE_MISS", ignoreCase = true) == true
                    if (!request.isForMainFrame || !cacheMiss || recoveringCacheMiss) return
                    recoveringCacheMiss = true
                    view.post {
                        val history = view.copyBackForwardList()
                        if (history.currentIndex >= 2) {
                            view.goBackOrForward(-2)
                        } else {
                            previousSafeUrl?.takeIf(::isHttpAddress)?.let(view::loadUrl) ?: view.reload()
                        }
                        view.postDelayed({ recoveringCacheMiss = false }, 600)
                    }
                }
            }
            val sharedWebChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (view === browserWebView) {
                        pageLoading = newProgress < 100
                        progressBar?.progress = newProgress
                        progressBar?.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                        updateNavigationButtons()
                    }
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (view === browserWebView && addressEditor == null) addressLabel?.text = cleanAddress(view.url.orEmpty(), title)
                }

                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                    val host = browserTabHost ?: return false
                    val popup = WebView(this@InAppBrowserActivity)
                    popup.settings.javaScriptEnabled = true
                    popup.settings.domStorageEnabled = true
                    popup.settings.databaseEnabled = true
                    popup.settings.javaScriptCanOpenWindowsAutomatically = true
                    popup.settings.loadsImagesAutomatically = true
                    popup.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    popup.settings.userAgentString = mobileUserAgent
                    popup.settings.setSupportMultipleWindows(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
                    popup.webViewClient = browserWebViewClient ?: WebViewClient()
                    popup.webChromeClient = browserWebChromeClient ?: WebChromeClient()
                    popup.setDownloadListener { url, agent, disposition, mime, _ ->
                        beginDirectDownload(url, agent, disposition, mime, popup.url.orEmpty())
                    }
                    host.addView(popup, FrameLayout.LayoutParams(-1, -1))
                    browserTabs += popup
                    switchToTab(browserTabs.lastIndex)
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }
            browserWebViewClient = sharedWebViewClient
            browserWebChromeClient = sharedWebChromeClient
            webViewClient = sharedWebViewClient
            webChromeClient = sharedWebChromeClient
            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                beginDirectDownload(url, userAgent, contentDisposition, mimeType, this.url.orEmpty())
            })
        }
        browserWebView = webView
        browserTabHost = FrameLayout(this).also { host ->
            browserTabs.clear()
            browserTabs += webView
            host.addView(webView, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(browserTabHost, LinearLayout.LayoutParams(-1, 0, 1f))

        val card = createDownloadCard()
        downloadCard = card
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(12), dp(8), dp(12), dp(4))
        })

        val navigation = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setBackgroundColor(DARK_TOOLBAR)
        }
        navigation.addView(iconButton("←", getString(R.string.back), dp(52)) {
            if (browserWebView?.canGoBack() == true) browserWebView?.goBack()
        })
        navigation.addView(iconButton("→", getString(R.string.npmorphe_apk_browser_forward), dp(52)) {
            browserWebView?.goForward()
        })
        val reloadControl = iconButton("↻", getString(R.string.refresh), dp(52)) {
            if (pageLoading) browserWebView?.stopLoading() else browserWebView?.reload()
        }
        reloadButton = reloadControl
        navigation.addView(reloadControl)
        val downloadStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bottomDownloadStatus = downloadStatus
        navigation.addView(downloadStatus, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(navigation, LinearLayout.LayoutParams(-1, dp(52)))
        val navigationInset = View(this).apply { setBackgroundColor(DARK_TOOLBAR) }
        root.addView(navigationInset, LinearLayout.LayoutParams(-1, 0))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            statusInset.layoutParams = statusInset.layoutParams.apply { height = bars.top }
            navigationInset.layoutParams = navigationInset.layoutParams.apply { height = bars.bottom }
            insets
        }
        root.post { ViewCompat.requestApplyInsets(root) }
        updateNavigationButtons()
        return root
    }

    private fun createDownloadCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground(Color.rgb(38, 38, 38), dp(14))
            visibility = View.GONE
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val fileNameView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        downloadNameView = fileNameView
        titleRow.addView(fileNameView, LinearLayout.LayoutParams(0, dp(36), 1f))
        titleRow.addView(TextView(this).apply {
            text = getString(R.string.npmorphe_browser_download_cancel)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { cancelDownload() }
        })
        card.addView(titleRow)
        val downloadProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        downloadProgressView = downloadProgress
        card.addView(downloadProgress, LinearLayout.LayoutParams(-1, dp(4)))
        val detailsView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(5), 0, 0)
        }
        downloadDetailsView = detailsView
        card.addView(detailsView)
        return card
    }

    private fun iconButton(glyph: String, description: String, width: Int, action: (View) -> Unit) =
        TextView(this).apply {
            text = glyph
            contentDescription = description
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            setOnClickListener { view -> action(view) }
            layoutParams = LinearLayout.LayoutParams(width, dp(48))
        }

    private fun showBrowserMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_SET_DEFAULT, 0, getString(R.string.home_apk_browser_set_default))
            menu.add(0, MENU_RELOAD, 1, getString(R.string.refresh))
            menu.add(0, MENU_COPY, 2, getString(R.string.npmorphe_browser_copy_link))
            menu.add(0, MENU_EXTERNAL, 3, getString(R.string.npmorphe_browser_open_external))
            menu.add(0, MENU_BOOKMARK, 4, getString(R.string.npmorphe_browser_add_bookmark))
            menu.add(0, MENU_DESKTOP, 5, getString(R.string.npmorphe_browser_desktop_site)).isCheckable = true
            menu.findItem(MENU_DESKTOP).isChecked = desktopMode
            menu.add(0, MENU_SETTINGS, 6, getString(R.string.npmorphe_browser_settings_title))
            menu.add(0, MENU_DOWNLOADS, 7, getString(R.string.npmorphe_downloads_title))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SET_DEFAULT -> { saveCurrentAsDefault(); true }
                    MENU_RELOAD -> { browserWebView?.reload(); true }
                    MENU_COPY -> { copyCurrentLink(); true }
                    MENU_EXTERNAL -> { openExternalBrowser(); true }
                    MENU_BOOKMARK -> { browserData.addBookmark(browserWebView?.title.orEmpty(), currentUrl); Toast.makeText(this@InAppBrowserActivity, R.string.npmorphe_browser_bookmark_added, Toast.LENGTH_SHORT).show(); true }
                    MENU_DESKTOP -> { setDesktopMode(!desktopMode); true }
                    MENU_SETTINGS -> { startActivity(BrowserSettingsActivity.intent(this@InAppBrowserActivity, currentUrl)); true }
                    MENU_DOWNLOADS -> { startActivity(DownloadedApksActivity.intent(this@InAppBrowserActivity)); true }
                    else -> false
                }
            }
        }.show()
    }

    private fun showTabsMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_NEW_TAB, 0, getString(R.string.npmorphe_browser_new_tab))
            browserTabs.forEachIndexed { index, tab ->
                val title = tab.title?.takeIf(String::isNotBlank) ?: cleanAddress(tab.url.orEmpty())
                menu.add(0, MENU_TAB_BASE + index, index + 1, "${index + 1}. $title")
                    .isChecked = tab === browserWebView
            }
            setOnMenuItemClickListener { item ->
                if (item.itemId == MENU_NEW_TAB) {
                    openNewTab(browserSearchEngineUrl())
                    true
                } else {
                    val index = item.itemId - MENU_TAB_BASE
                    if (index in browserTabs.indices) { switchToTab(index); true } else false
                }
            }
        }.show()
    }

    private fun browserSearchEngineUrl(): String = browserData.state.value.searchUrl("Android apps")

    private fun openNewTab(url: String) {
        val host = browserTabHost ?: return
        val template = browserWebView ?: return
        val tab = WebView(this@InAppBrowserActivity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = template.settings.userAgentString
            settings.setSupportMultipleWindows(true)
            webViewClient = browserWebViewClient ?: WebViewClient()
            webChromeClient = browserWebChromeClient ?: WebChromeClient()
            setDownloadListener { downloadUrl, agent, disposition, mimeType, _ ->
                beginDirectDownload(downloadUrl, agent, disposition, mimeType, this.url.orEmpty())
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        host.addView(tab, FrameLayout.LayoutParams(-1, -1))
        browserTabs += tab
        switchToTab(browserTabs.lastIndex)
        tab.loadUrl(url)
    }

    private fun switchToTab(index: Int) {
        if (index !in browserTabs.indices) return
        browserTabs.forEachIndexed { i, tab -> tab.visibility = if (i == index) View.VISIBLE else View.GONE }
        browserWebView = browserTabs[index]
        currentUrl = browserWebView?.url.orEmpty().ifBlank { currentUrl }
        addressLabel?.text = cleanAddress(currentUrl, browserWebView?.title)
        pageLoading = false
        progressBar?.visibility = View.GONE
        updateNavigationButtons()
    }

    private fun setDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        browserTabs.forEach { tab ->
            tab.settings.userAgentString = if (enabled) DESKTOP_USER_AGENT else mobileUserAgent
            tab.settings.useWideViewPort = enabled
            tab.settings.loadWithOverviewMode = enabled
            tab.reload()
        }
    }

    private fun beginAddressEdit() {
        val container = addressContainer ?: return
        if (addressEditor != null) return
        container.removeAllViews()
        val editor = EditText(this).apply {
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(currentUrl)
            setSelection(text.length)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(dp(14), 0, dp(14), 0)
            background = null
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    navigateToAddress(text.toString())
                    true
                } else false
            }
        }
        addressEditor = editor
        container.addView(editor, FrameLayout.LayoutParams(-1, -1))
        editor.requestFocus()
        editor.post {
            val input = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            input?.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showAddressLabel() {
        val container = addressContainer ?: return
        addressEditor = null
        container.removeAllViews()
        val label = addressLabel ?: return
        label.text = cleanAddress(currentUrl)
        container.addView(label, FrameLayout.LayoutParams(-1, -1))
    }

    private fun navigateToAddress(value: String) {
        val address = value.trim()
        val destination = if (isHttpAddress(address)) address else browserData.state.value.searchUrl(address)
        currentUrl = destination
        val editor = addressEditor
        showAddressLabel()
        browserWebView?.loadUrl(destination)
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        editor?.windowToken?.let { inputMethodManager?.hideSoftInputFromWindow(it, 0) }
    }

    private fun handleUrlScheme(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "javascript") {
            return false
        }
        return runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        }.getOrDefault(true)
    }

    private fun updateNavigationButtons() {
        reloadButton?.text = if (pageLoading) "×" else "↻"
        toolbarDownloadStatus?.apply {
            text = "↓${activeDownloadIds.size}"
            visibility = if (activeDownloadIds.isNotEmpty()) View.VISIBLE else View.GONE
        }
        bottomDownloadStatus?.text = if (activeDownloadId != null) {
            getString(R.string.npmorphe_browser_downloading)
        } else ""
    }

    private fun beginDirectDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        referer: String
    ) {
        val agent = userAgent?.takeIf(String::isNotBlank) ?: mobileUserAgent
        val fileName = runCatching { resolveDownloadName(url, contentDisposition, mimeType) }.getOrDefault("download.apk")
        activeDownloadId = ApkDownloadService.enqueue(
            context = this,
            url = url,
            fileName = fileName,
            packageName = targetPackageName,
            versionName = requestedVersion,
            userAgent = agent,
            cookie = CookieManager.getInstance().getCookie(url),
            referer = referer
        )
        activeDownloadIds += activeDownloadId!!
        showDownloadCard(fileName, 0L, -1L, 0.0)
        updateNavigationButtons()
    }

    private fun showDownloadCard(fileName: String, downloaded: Long, total: Long, speedBytesPerSecond: Double) {
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            downloadCard?.visibility = View.VISIBLE
            downloadNameView?.text = fileName
            val progress = downloadProgressView
            if (total > 0L) {
                progress?.isIndeterminate = false
                progress?.progress = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
            } else {
                progress?.isIndeterminate = true
            }
            val totalText = if (total > 0L) " / " + formatMegabytes(total) else ""
            val percentText = if (total > 0L) " (" + ((downloaded * 100L) / total).toInt().coerceIn(0, 100) + "%)" else ""
            val speedText = if (speedBytesPerSecond > 0.0) " · " + formatMegabytes(speedBytesPerSecond.toLong()) + "/s" else ""
            downloadDetailsView?.text = if (downloaded == 0L && total < 0L) {
                getString(R.string.npmorphe_browser_download_preparing)
            } else {
                formatMegabytes(downloaded) + totalText + percentText + speedText
            }
            bottomDownloadStatus?.text = if (total > 0L) {
                ((downloaded * 100L) / total).toInt().coerceIn(0, 100).toString() + "%"
            } else getString(R.string.npmorphe_browser_downloading)
        }
    }

    private fun rememberDownloadedFile(file: File, sourceUrl: String) {
        val packageId = targetPackageName?.takeIf(String::isNotBlank) ?: return
        val prefs = getSharedPreferences(DOWNLOAD_PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(downloadKey(packageId, "file"), file.absolutePath)
            .putString(downloadKey(packageId, "version"), requestedVersion)
            .putString(downloadKey(packageId, "url"), sourceUrl)
            .apply()
    }

    private fun cancelDownload() {
        activeDownloadId?.let {
            ApkDownloadService.cancel(this, it)
            activeDownloadIds.remove(it)
        }
        activeDownloadId = activeDownloadIds.lastOrNull()
        downloadCard?.visibility = if (activeDownloadId == null) View.GONE else View.VISIBLE
        updateNavigationButtons()
    }

    private fun requestClose() {
        finish()
    }

    private fun saveCurrentAsDefault() {
        if (!isHttpAddress(currentUrl)) return
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
            Toast.makeText(this@InAppBrowserActivity, R.string.home_apk_browser_default_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyCurrentLink() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("NPmorphe browser link", currentUrl))
        Toast.makeText(this, R.string.npmorphe_browser_link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openExternalBrowser() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))) }
            .onFailure { Toast.makeText(this, R.string.npmorphe_browser_external_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun cleanAddress(url: String, title: String? = null): String {
        val host = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull()
        return title?.takeIf(String::isNotBlank) ?: host ?: url
    }

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatMegabytes(bytes: Long): String =
        String.format(Locale.getDefault(), "%.1f MB", bytes / BYTES_PER_MEGABYTE)

    private fun disposeWebView(view: WebView) {
        runCatching { view.stopLoading() }
        runCatching { view.setDownloadListener(null) }
        runCatching { view.webChromeClient = WebChromeClient() }
        runCatching { view.webViewClient = WebViewClient() }
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.destroy() }
        if (browserWebView === view) browserWebView = null
    }

    override fun onDestroy() {
        downloadObserverJob?.cancel()
        browserTabs.toList().forEach(::disposeWebView)
        browserTabs.clear()
        browserWebViewClient = null
        browserWebChromeClient = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INITIAL_URL = "npmorphe.browser.initial_url"
        const val EXTRA_PACKAGE_NAME = "npmorphe.browser.package_name"
        const val EXTRA_APP_NAME = "npmorphe.browser.app_name"
        const val EXTRA_REQUESTED_VERSION = "npmorphe.browser.requested_version"
        const val EXTRA_STANDALONE = "npmorphe.browser.standalone"
        const val EXTRA_RESULT_URI = "npmorphe.browser.result_uri"
        const val EXTRA_RESULT_FILE_PATH = "npmorphe.browser.result_file_path"
        const val EXTRA_RESULT_URL = "npmorphe.browser.result_url"
        const val EXTRA_RESULT_DOWNLOAD_ID = "npmorphe.browser.result_download_id"
        const val EXTRA_RESULT_PERMANENT = "npmorphe.browser.result_permanent"
        const val EXTRA_RETURN_ON_DOWNLOAD = "npmorphe.browser.return_on_download"
        const val NO_DOWNLOAD_MANAGER_ID = -1L
        private const val DOWNLOAD_PREFERENCES = "npmorphe_downloaded_base_apks"
        private const val DARK_BACKGROUND = 0xFF121212.toInt()
        private const val DARK_TOOLBAR = 0xFF191919.toInt()
        private const val DARK_PILL = 0xFF2B2B2B.toInt()
        private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
        private const val TAG = "NPmorpheBrowser"
        private const val MENU_SET_DEFAULT = 1
        private const val MENU_RELOAD = 2
        private const val MENU_COPY = 3
        private const val MENU_EXTERNAL = 4
        private const val MENU_BOOKMARK = 5
        private const val MENU_DESKTOP = 6
        private const val MENU_SETTINGS = 7
        private const val MENU_DOWNLOADS = 8
        private const val MENU_NEW_TAB = 9
        private const val MENU_TAB_BASE = 100
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun intent(
            context: Context,
            initialUrl: String,
            packageName: String?,
            appName: String?,
            requestedVersion: String?,
            isStandalone: Boolean,
            returnOnDownload: Boolean = false
        ) = Intent(context, InAppBrowserActivity::class.java).apply {
            putExtra(EXTRA_INITIAL_URL, initialUrl)
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_APP_NAME, appName)
            putExtra(EXTRA_REQUESTED_VERSION, requestedVersion)
            putExtra(EXTRA_STANDALONE, isStandalone)
            putExtra(EXTRA_RETURN_ON_DOWNLOAD, returnOnDownload)
        }

        fun findLatestDownloadedBaseApk(
            context: Context,
            packageName: String?,
            requestedVersion: String?
        ): DownloadedBaseApk? {
            val packageId = packageName?.takeIf(String::isNotBlank) ?: return null
            val prefs = context.getSharedPreferences(DOWNLOAD_PREFERENCES, Context.MODE_PRIVATE)
            val filePath = prefs.getString(downloadKey(packageId, "file"), null) ?: return null
            val file = File(filePath)
            if (!file.isFile || file.length() <= 0L) {
                prefs.edit()
                    .remove(downloadKey(packageId, "file"))
                    .remove(downloadKey(packageId, "version"))
                    .remove(downloadKey(packageId, "url"))
                    .apply()
                return null
            }
            val savedVersion = prefs.getString(downloadKey(packageId, "version"), null)
            if (!requestedVersion.isNullOrBlank() && !savedVersion.isNullOrBlank() &&
                normalizeVersion(requestedVersion) != normalizeVersion(savedVersion)
            ) return null
            return DownloadedBaseApk(
                file = file,
                requestedVersion = savedVersion,
                sourceUrl = prefs.getString(downloadKey(packageId, "url"), null)
            )
        }

        private fun downloadKey(packageName: String, field: String) = "$packageName:$field"

        private fun normalizeVersion(value: String) = value.trim().removePrefix("v").lowercase(Locale.ROOT)
    }
}

private fun findAppDefaultUrl(values: Collection<String>, packageName: String): String? =
    values.firstNotNullOfOrNull { record ->
        val split = record.indexOf(APK_BROWSER_APP_DEFAULT_URL_SEPARATOR)
        if (split > 0 && record.substring(0, split) == packageName) record.substring(split + 1) else null
    }

private fun isHttpAddress(value: String): Boolean {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
    return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
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
    return if (isHttpAddress(candidate)) candidate else "https://www.google.com/search?q=" + Uri.encode(input)
}

private fun resolveDownloadName(url: String, disposition: String?, mimeType: String?): String {
    val guessed = safeDownloadName(URLUtil.guessFileName(url, disposition, mimeType))
    if (isApkFileName(guessed)) return guessed
    val urlName = safeDownloadName(Uri.parse(url).lastPathSegment.orEmpty())
    if (isApkFileName(urlName)) return urlName
    if (mimeType?.contains("text/html", ignoreCase = true) == true) {
        throw IOException("The site returned a web page rather than an APK.")
    }
    val extension = when {
        mimeType?.contains("apkm", ignoreCase = true) == true -> ".apkm"
        mimeType?.contains("apks", ignoreCase = true) == true -> ".apks"
        mimeType?.contains("xapk", ignoreCase = true) == true -> ".xapk"
        else -> ".apk"
    }
    return guessed.substringBeforeLast('.', guessed) + extension
}

private fun safeDownloadName(name: String) =
    name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(150)
        .ifBlank { "download.apk" }

private fun isApkFileName(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return listOf(".apk", ".apkm", ".apks", ".xapk").any(lower::endsWith)
}
