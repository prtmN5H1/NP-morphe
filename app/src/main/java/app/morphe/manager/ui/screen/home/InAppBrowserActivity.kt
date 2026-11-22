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
import android.os.SystemClock
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
import app.morphe.manager.util.APK_BROWSER_DEFAULT_URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class DownloadedBaseApk(
    val file: File,
    val requestedVersion: String?,
    val sourceUrl: String?
)

/** Full-window browser Activity; its WebView is never attached to a Compose dialog window. */
class InAppBrowserActivity : ComponentActivity() {
    private val preferences: PreferencesManager by inject()
    private var browserWebView: WebView? = null
    private var addressContainer: FrameLayout? = null
    private var addressLabel: TextView? = null
    private var addressEditor: EditText? = null
    private var progressBar: ProgressBar? = null
    private var bottomDownloadStatus: TextView? = null
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
    private var activeDownloadJob: Job? = null
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var activePartFile: File? = null
    private var closingAfterCancel = false
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activeDownloadJob?.isActive == true) {
                    confirmCancelDownload(closeWhenCancelled = false)
                } else if (browserWebView?.canGoBack() == true) {
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
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.userAgentString = settings.userAgentString
                .replace("; wv", "", ignoreCase = true)
                .replace("Version/4.0 ", "", ignoreCase = true)
            mobileUserAgent = settings.userAgentString
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (currentUrl != url && isHttpAddress(currentUrl)) previousSafeUrl = currentUrl
                    currentUrl = url
                    addressLabel?.text = cleanAddress(url)
                    pageLoading = true
                    updateNavigationButtons()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    currentUrl = url
                    if (addressEditor == null) addressLabel?.text = cleanAddress(url)
                    pageLoading = false
                    progressBar?.visibility = View.GONE
                    updateNavigationButtons()
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    handleUrlScheme(request.url)

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    handleUrlScheme(Uri.parse(url))

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
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    pageLoading = newProgress < 100
                    progressBar?.progress = newProgress
                    progressBar?.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                    updateNavigationButtons()
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (addressEditor == null) addressLabel?.text = cleanAddress(view.url.orEmpty(), title)
                }
            }
            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                beginDirectDownload(url, userAgent, contentDisposition, mimeType, this.url.orEmpty())
            })
        }
        browserWebView = webView
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))

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
            if (activeDownloadJob?.isActive == true) confirmCancelDownload(false)
            else if (browserWebView?.canGoBack() == true) browserWebView?.goBack()
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
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SET_DEFAULT -> { saveCurrentAsDefault(); true }
                    MENU_RELOAD -> { browserWebView?.reload(); true }
                    MENU_COPY -> { copyCurrentLink(); true }
                    MENU_EXTERNAL -> { openExternalBrowser(); true }
                    else -> false
                }
            }
        }.show()
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
        val destination = normalizeApkBrowserAddress(value)
        currentUrl = destination
        val editor = addressEditor
        showAddressLabel()
        browserWebView?.loadUrl(destination)
        val input = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        editor?.windowToken?.let { input?.hideSoftInputFromWindow(it, 0) }
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
        bottomDownloadStatus?.text = if (activeDownloadJob?.isActive == true) {
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
        if (activeDownloadJob?.isActive == true) {
            Toast.makeText(this, R.string.home_apk_browser_download_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        val agent = userAgent?.takeIf(String::isNotBlank) ?: mobileUserAgent
        activeDownloadJob = lifecycleScope.launch {
            showDownloadCard(getString(R.string.npmorphe_browser_download_preparing), 0L, -1L, 0.0)
            try {
                val file = withContext(Dispatchers.IO) {
                    streamDownload(url, agent, referer, contentDisposition, mimeType)
                }
                rememberDownloadedFile(file, url)
                val uri = Uri.fromFile(file)
                setResult(RESULT_OK, Intent().apply {
                    putExtra(EXTRA_RESULT_URI, uri.toString())
                    putExtra(EXTRA_RESULT_FILE_PATH, file.absolutePath)
                    putExtra(EXTRA_RESULT_URL, url)
                    putExtra(EXTRA_RESULT_DOWNLOAD_ID, NO_DOWNLOAD_MANAGER_ID)
                    putExtra(EXTRA_RESULT_PERMANENT, false)
                })
                finish()
            } catch (cancelled: CancellationException) {
                if (!closingAfterCancel && !isFinishing && !isDestroyed) {
                    Toast.makeText(this@InAppBrowserActivity, R.string.npmorphe_browser_download_cancelled, Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                android.util.Log.w(TAG, "Direct APK download failed", error)
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this@InAppBrowserActivity, R.string.npmorphe_browser_download_failed, Toast.LENGTH_LONG).show()
                }
            } finally {
                activeConnection?.disconnect()
                activeConnection = null
                activePartFile?.delete()
                activePartFile = null
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    activeDownloadJob = null
                    closingAfterCancel = false
                    if (!isDestroyed) {
                        downloadCard?.visibility = View.GONE
                        updateNavigationButtons()
                    }
                }
            }
        }
    }

    private suspend fun streamDownload(
        initialUrl: String,
        userAgent: String,
        initialReferer: String,
        contentDispositionHint: String?,
        mimeTypeHint: String?
    ): File {
        var current = initialUrl
        var referer = initialReferer.takeIf(String::isNotBlank)
        var connection: HttpURLConnection? = null
        try {
            var redirects = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val opened = URL(current).openConnection() as? HttpURLConnection
                    ?: throw IOException(getString(R.string.npmorphe_browser_download_failed))
                connection = opened
                activeConnection = opened
                opened.instanceFollowRedirects = false
                opened.connectTimeout = CONNECT_TIMEOUT_MILLIS
                opened.readTimeout = READ_TIMEOUT_MILLIS
                opened.requestMethod = "GET"
                opened.setRequestProperty("User-Agent", userAgent)
                opened.setRequestProperty("Accept", "*/*")
                referer?.let { opened.setRequestProperty("Referer", it) }
                CookieManager.getInstance().getCookie(current)
                    ?.takeIf(String::isNotBlank)
                    ?.let { opened.setRequestProperty("Cookie", it) }

                val status = opened.responseCode
                if (status in REDIRECT_CODES) {
                    val location = opened.getHeaderField("Location")
                        ?: throw IOException(getString(R.string.npmorphe_browser_download_failed))
                    if (++redirects > MAX_REDIRECTS) {
                        throw IOException(getString(R.string.npmorphe_browser_download_failed))
                    }
                    val previous = current
                    current = URL(URL(current), location).toString()
                    referer = previous
                    opened.disconnect()
                    activeConnection = null
                    continue
                }
                if (status !in 200..299) throw IOException("HTTP " + status)
                val responseMime = opened.contentType ?: mimeTypeHint.orEmpty()
                if (responseMime.contains("text/html", ignoreCase = true)) {
                    throw IOException("The page returned HTML instead of an APK.")
                }
                val disposition = opened.getHeaderField("Content-Disposition") ?: contentDispositionHint
                val fileName = resolveDownloadName(current, disposition, responseMime)
                val directory = File(filesDir, "downloaded_base_apks")
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IOException("Could not create the managed APK folder.")
                }
                val target = uniqueFile(directory, fileName)
                val part = File(directory, "." + target.name + ".part")
                activePartFile = part
                val totalBytes = opened.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                var lastUpdateAt = SystemClock.elapsedRealtime()
                var lastUpdateBytes = 0L
                var downloadedBytes = 0L
                opened.inputStream.use { input ->
                    FileOutputStream(part).buffered().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloadedBytes += count
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUpdateAt >= PROGRESS_UPDATE_MILLIS) {
                                val speed = (downloadedBytes - lastUpdateBytes) * 1000.0 / (now - lastUpdateAt).coerceAtLeast(1L)
                                showDownloadCard(fileName, downloadedBytes, totalBytes, speed)
                                lastUpdateAt = now
                                lastUpdateBytes = downloadedBytes
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (downloadedBytes <= 0L || !looksLikeZipArchive(part)) {
                    throw IOException("The response was not a valid APK or bundle.")
                }
                if (!part.renameTo(target)) throw IOException("Could not finish saving the APK.")
                activePartFile = null
                showDownloadCard(fileName, downloadedBytes, totalBytes, 0.0)
                return target
            }
        } finally {
            connection?.disconnect()
            if (activeConnection === connection) activeConnection = null
        }
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
        closingAfterCancel = false
        activeConnection?.disconnect()
        activeDownloadJob?.cancel()
    }

    private fun confirmCancelDownload(closeWhenCancelled: Boolean) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.npmorphe_browser_download_cancel_title)
            .setMessage(R.string.npmorphe_browser_download_cancel_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.npmorphe_browser_download_cancel) { _, _ ->
                closingAfterCancel = closeWhenCancelled
                activeConnection?.disconnect()
                activeDownloadJob?.cancel()
                if (closeWhenCancelled) finish()
            }
            .show()
    }

    private fun requestClose() {
        if (activeDownloadJob?.isActive == true) confirmCancelDownload(closeWhenCancelled = true)
        else finish()
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
        activeConnection?.disconnect()
        activeDownloadJob?.cancel()
        browserWebView?.let(::disposeWebView)
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
        const val NO_DOWNLOAD_MANAGER_ID = -1L
        private const val DOWNLOAD_PREFERENCES = "npmorphe_downloaded_base_apks"
        private const val DARK_BACKGROUND = 0xFF121212.toInt()
        private const val DARK_TOOLBAR = 0xFF191919.toInt()
        private const val DARK_PILL = 0xFF2B2B2B.toInt()
        private const val PROGRESS_UPDATE_MILLIS = 180L
        private const val MAX_REDIRECTS = 10
        private const val CONNECT_TIMEOUT_MILLIS = 30_000
        private const val READ_TIMEOUT_MILLIS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
        private const val TAG = "NPmorpheBrowser"
        private const val MENU_SET_DEFAULT = 1
        private const val MENU_RELOAD = 2
        private const val MENU_COPY = 3
        private const val MENU_EXTERNAL = 4
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

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

private fun uniqueFile(directory: File, name: String): File {
    val extension = name.substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
    val base = if (extension.isBlank()) name else name.removeSuffix(extension)
    var candidate = File(directory, name)
    var index = 1
    while (candidate.exists()) {
        candidate = File(directory, base + " (" + index + ")" + extension)
        index++
    }
    return candidate
}

private fun looksLikeZipArchive(file: File): Boolean = runCatching {
    FileInputStream(file).use { input ->
        input.read() == 0x50 && input.read() == 0x4b && input.read() in 0x03..0x05
    }
}.getOrDefault(false)

private fun isApkFileName(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return listOf(".apk", ".apkm", ".apks", ".xapk").any(lower::endsWith)
}
