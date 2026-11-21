/*
 * Copyright 2026 Morphe.
 */

package app.morphe.manager.ui.screen.sources

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import app.morphe.manager.R
import org.json.JSONArray
import java.util.Locale

/** A dedicated Activity gives Chromium popups and WebView cleanup a stable window owner. */
class CommunityCatalogActivity : ComponentActivity() {
    private val selectedSources = mutableListOf<String>()
    private var installedKeysJson = "[]"
    @Volatile private var queuedKeysJson = "[]"
    @Volatile private var lastTappedSource = ""
    private var webView: WebView? = null
    private lateinit var countView: TextView
    private lateinit var queueContainer: LinearLayout
    private lateinit var queueScroll: ScrollView
    private lateinit var addButton: Button
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(18, 18, 18)
        window.navigationBarColor = android.graphics.Color.rgb(18, 18, 18)
        window.decorView.systemUiVisibility = 0
        val installedKeys = intent.getStringArrayListExtra(EXTRA_INSTALLED_KEYS).orEmpty()
        selectedSources.addAll(intent.getStringArrayListExtra(EXTRA_QUEUED_URLS).orEmpty()
            .mapNotNull(::repositoryUrl).distinctBy { canonicalKeys(it).sorted().joinToString("|") })
        installedKeysJson = JSONArray(installedKeys).toString()
        refreshQueuedKeys()
        setContentView(createContent())
        renderQueue()
        webView?.loadUrl("https://morphe-patches.software/")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishWithSelection()
        })
    }

    private fun createContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(18, 18, 18))
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        toolbar.addView(actionText("‹", getString(R.string.back), ::finishWithSelection))
        toolbar.addView(TextView(this).apply {
            text = getString(R.string.sources_dialog_community)
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        countView = TextView(this).apply {
            textSize = 14f
            setTextColor(android.graphics.Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }
        toolbar.addView(countView)
        toolbar.addView(actionText("×", getString(R.string.close), ::finishWithSelection))
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(52)))

        val catalogView = WebView(this).apply {
            destroyed = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
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
            addJavascriptInterface(object {
                @JavascriptInterface fun installedKeys(): String = installedKeysJson
                @JavascriptInterface fun queuedKeys(): String = queuedKeysJson
                @JavascriptInterface fun recordTap(value: String) { lastTappedSource = value.take(2048) }
                @JavascriptInterface fun toggleSource(value: String) { runOnUiThread { toggleSourceOnMain(value) } }
                @JavascriptInterface fun removeSource(value: String) { runOnUiThread { removeSourceOnMain(value) } }
            }, "NPMorpheCatalog")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    if (request.isForMainFrame) handleTopLevel(request.url) else false
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    handleTopLevel(Uri.parse(url))
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    view.evaluateJavascript(catalogJavascript(), null)
                }
            }
        }
        webView = catalogView
        root.addView(catalogView, LinearLayout.LayoutParams(-1, 0, 1f))

        queueScroll = ScrollView(this)
        queueContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        queueScroll.addView(queueContainer)
        root.addView(queueScroll, LinearLayout.LayoutParams(-1, dp(144)))
        addButton = Button(this).apply {
            setOnClickListener { finishWithSelection() }
        }
        root.addView(addButton, LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(dp(12), dp(4), dp(12), dp(8))
        })
        return root
    }

    private fun actionText(glyph: String, description: String, action: () -> Unit) =
        TextView(this).apply {
            text = glyph
            contentDescription = description
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

    private fun renderQueue() {
        if (!::countView.isInitialized) return
        countView.text = getString(R.string.npmorphe_catalog_selected_count, selectedSources.size)
        queueContainer.removeAllViews()
        selectedSources.forEachIndexed { index, url ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = (index + 1).toString() + "."
                setTextColor(android.graphics.Color.WHITE)
                setPadding(0, 0, dp(8), 0)
            })
            row.addView(TextView(this).apply {
                text = Uri.parse(url).lastPathSegment ?: url
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(android.graphics.Color.LTGRAY)
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
            row.addView(actionText("×", getString(R.string.remove)) { removeSourceOnMain(url) })
            queueContainer.addView(row, LinearLayout.LayoutParams(-1, dp(40)))
        }
        addButton.text = if (selectedSources.size > 1) {
            "Add " + selectedSources.size + " sources"
        } else getString(R.string.add)
        addButton.isEnabled = selectedSources.isNotEmpty()
        queueScroll.visibility = if (selectedSources.isEmpty()) View.GONE else View.VISIBLE
        queueScroll.post { queueScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toggleSourceOnMain(value: String) {
        val url = repositoryUrl(value) ?: return
        val keys = canonicalKeys(url)
        if (installedKeys().any(keys::contains)) return
        val index = selectedSources.indexOfFirst { canonicalKeys(it).any(keys::contains) }
        if (index >= 0) selectedSources.removeAt(index) else selectedSources.add(url)
        refreshQueuedKeys()
        renderQueue()
        refreshPage()
    }

    private fun removeSourceOnMain(url: String) {
        val keys = canonicalKeys(url)
        val index = selectedSources.indexOfFirst { canonicalKeys(it).any(keys::contains) }
        if (index >= 0) selectedSources.removeAt(index)
        refreshQueuedKeys()
        renderQueue()
        refreshPage()
    }

    private fun installedKeys(): Set<String> = runCatching {
        val json = JSONArray(installedKeysJson)
        (0 until json.length()).mapNotNull { json.optString(it).takeIf(String::isNotBlank) }.toSet()
    }.getOrDefault(emptySet())

    private fun refreshQueuedKeys() { queuedKeysJson = keysJson(selectedSources) }
    private fun refreshPage() {
        webView?.evaluateJavascript("window.__npmorpheCatalogRefresh && window.__npmorpheCatalogRefresh();", null)
    }

    private fun handleTopLevel(uri: Uri): Boolean {
        if (uri.scheme.equals("morphe", ignoreCase = true)) {
            val tapped = lastTappedSource.also { lastTappedSource = "" }
            toggleSourceOnMain(tapped.ifBlank {
                uri.getQueryParameter("url")
                    ?: uri.getQueryParameter("github")?.let { "https://github.com/" + it }
                    ?: uri.getQueryParameter("repo")?.let { "https://github.com/" + it }
                    ?: uri.toString()
            })
            return true
        }
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        return !(scheme in setOf("http", "https") &&
            (host == COMMUNITY_HOST || host.endsWith("." + COMMUNITY_HOST)))
    }

    private fun catalogJavascript(): String = """
      (function(){
        function source(v){try{if(!v)return null;v=String(v).trim();if(/^[\w.-]+\/[\w.-]+$/.test(v))return 'https://github.com/'+v;if(v.indexOf('intent://')===0)v='https://'+v.substring(9).split('#Intent;')[0];if(/^morphe:\/\//i.test(v)){var d=new URL(v),r=d.searchParams.get('url')||d.searchParams.get('github')||d.searchParams.get('repo');if(r)v=r.indexOf('://')>=0?r:'https://github.com/'+r.replace(/^\/+|\/+$/g,'');}var u=new URL(v,location.href),h=u.hostname.toLowerCase();if((h==='morphe-patches.software'||h.endsWith('.morphe-patches.software')||h==='morphe.software'||h.endsWith('.morphe.software'))&&u.pathname==='/add-source'){var q=u.searchParams.get('github')||u.searchParams.get('gitlab');if(q)return (u.searchParams.has('gitlab')?'https://gitlab.com/':'https://github.com/')+q.replace(/^\/+|\/+$/g,'');}if(h==='github.com'||h==='raw.githubusercontent.com')return 'https://github.com/'+u.pathname.split('/').filter(Boolean).slice(0,2).join('/');if(h==='api.github.com'&&u.pathname.indexOf('/repos/')===0)return 'https://github.com/'+u.pathname.split('/').filter(Boolean).slice(1,3).join('/');return u.href;}catch(e){return null;}}
        function keys(v){var s=source(v);if(!s)return[];try{var u=new URL(s),h=u.hostname.toLowerCase(),p=u.pathname.replace(/\/+$/,'').replace(/\.git$/i,''),a=['url:'+u.protocol.toLowerCase()+'//'+h+p+u.search],x=p.split('/').filter(Boolean),g=null;if(h==='github.com'||h==='raw.githubusercontent.com')g=x.slice(0,2).join('/');if(h==='api.github.com'&&x[0]==='repos')g=x.slice(1,3).join('/');if(g&&g.indexOf('/')>0)a.push('gh:'+g.toLowerCase().replace(/\.git$/i,''));return a;}catch(e){return[];}}
        function card(e){for(var n=e,i=0;n&&i<7;i++,n=n.parentElement)if(n.matches&&(n.matches('article,li,[data-testid*="card" i]')||/card|bundle/i.test(n.className||'')))return n;return e.parentElement||e;}
        function candidates(e){var c=card(e),v=[];[e,c].forEach(function(n){if(!n||!n.getAttribute)return;['href','data-url','data-source-url','data-github','data-repo','data-source','data-bundle-url'].forEach(function(k){var x=n.getAttribute(k);if(x)v.push(x);});});if(c&&c.querySelectorAll)c.querySelectorAll('a[href],[data-url],[data-source-url],[data-github],[data-repo]').forEach(function(n){['href','data-url','data-source-url','data-github','data-repo'].forEach(function(k){var x=n.getAttribute(k);if(x)v.push(x);});});var t=(c&&c.innerText||e.innerText||'').match(/\b[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\b/g)||[];return v.concat(t);}
        function isAdd(e){var l=(e.innerText||e.textContent||'').trim(),a=(e.getAttribute('aria-label')||'').trim(),h=e.getAttribute('href')||'',add=/^\+?\s*add(\s+to\s+morphe)?\s*$/i;return /morphe:\/\//i.test(h)||add.test(l)||add.test(a)||!!e.dataset.npState&&/^(already added|selected)$/i.test(l||a);}
        function restore(e){if(e.dataset.npHtml)e.innerHTML=e.dataset.npHtml;delete e.dataset.npHtml;delete e.dataset.npState;delete e.dataset.npUrl;e.removeAttribute('aria-disabled');['opacity','outline','border-radius','background-color','pointer-events'].forEach(function(k){e.style.removeProperty(k);});if('disabled'in e)e.disabled=false;}
        function decorate(){var i=[],q=[];try{i=JSON.parse(NPMorpheCatalog.installedKeys());}catch(e){}try{q=JSON.parse(NPMorpheCatalog.queuedKeys());}catch(e){}document.querySelectorAll('button,a,[role="button"]').forEach(function(e){if(!isAdd(e))return;var k=[],s=null;candidates(e).forEach(function(v){var n=keys(v);if(n.length&&(!s||n.some(function(x){return x.indexOf('gh:')===0;})))s=source(v);k=k.concat(n);});var state=k.some(function(x){return i.indexOf(x)>=0;})?'added':(k.some(function(x){return q.indexOf(x)>=0;})?'queued':'new');if(state==='new'){if(e.dataset.npState)restore(e);e.dataset.npUrl=s||'';return;}if(!e.dataset.npHtml)e.dataset.npHtml=e.innerHTML;e.dataset.npState=state;e.dataset.npUrl=s||'';var label=state==='added'?'Already added':'Selected';if((e.textContent||'').trim()!==label)e.textContent=label;if((e.getAttribute('aria-label')||'')!==label)e.setAttribute('aria-label',label);if(state==='added'){e.setAttribute('aria-disabled','true');e.style.opacity='.55';e.style.pointerEvents='none';if('disabled'in e)e.disabled=true;}else{e.style.outline='2px solid #4caf50';e.style.borderRadius='6px';e.style.backgroundColor='rgba(76,175,80,.16)';if('disabled'in e)e.disabled=false;}});}
        var busy=false;function schedule(){if(busy)return;busy=true;requestAnimationFrame(function(){busy=false;decorate();});}window.__npmorpheCatalogRefresh=schedule;
        if(!window.__npmorpheCatalogClick){window.__npmorpheCatalogClick=function(ev){var e=ev.target&&ev.target.closest&&ev.target.closest('button,a,[role="button"]');if(!e||!isAdd(e))return;var s=e.dataset.npUrl||candidates(e).map(source).find(Boolean);if(!s)return;if(e.dataset.npState==='added'){ev.preventDefault();ev.stopImmediatePropagation();return;}if(e.dataset.npState==='queued'){ev.preventDefault();ev.stopImmediatePropagation();NPMorpheCatalog.removeSource(s);setTimeout(schedule,60);return;}NPMorpheCatalog.recordTap(s);};document.addEventListener('click',window.__npmorpheCatalogClick,true);}
        decorate();if(window.__npmorpheCatalogObserver)window.__npmorpheCatalogObserver.disconnect();window.__npmorpheCatalogObserver=new MutationObserver(schedule);window.__npmorpheCatalogObserver.observe(document.documentElement,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:['href','data-url','data-source-url','data-github','data-repo','aria-label']});
      })();
    """.trimIndent()

    private fun finishWithSelection() {
        setResult(RESULT_OK, android.content.Intent().putStringArrayListExtra(EXTRA_SELECTED_URLS, ArrayList(selectedSources)))
        finish()
    }

    private fun disposeWebView(view: WebView) {
        if (destroyed) return
        destroyed = true
        runCatching { view.stopLoading() }
        runCatching { view.removeJavascriptInterface("NPMorpheCatalog") }
        runCatching { view.webChromeClient = null }
        runCatching { view.webViewClient = WebViewClient() }
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.destroy() }
        if (webView === view) webView = null
    }

    override fun onDestroy() {
        webView?.let(::disposeWebView)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_INSTALLED_KEYS = "npmorphe.catalog.installed_keys"
        const val EXTRA_QUEUED_URLS = "npmorphe.catalog.queued_urls"
        const val EXTRA_SELECTED_URLS = "npmorphe.catalog.selected_urls"
        private const val COMMUNITY_HOST = "morphe-patches.software"

        fun intent(context: android.content.Context, installed: List<String>, queued: List<String>) =
            android.content.Intent(context, CommunityCatalogActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_INSTALLED_KEYS, ArrayList(installed.flatMap(::canonicalKeys).distinct().sorted()))
                putStringArrayListExtra(EXTRA_QUEUED_URLS, ArrayList(queued))
            }
    }
}

private fun keysJson(values: List<String>): String =
    JSONArray(values.flatMap(::canonicalKeys).distinct().sorted()).toString()

private fun repositoryUrl(value: String): String? {
    val raw = value.trim()
    if (raw.isBlank()) return null
    val normalized = if (raw.startsWith("intent://")) "https://" + raw.removePrefix("intent://").substringBefore("#Intent;") else raw
    val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if ((host == "morphe-patches.software" || host.endsWith(".morphe-patches.software") || host == "morphe.software" || host.endsWith(".morphe.software")) && uri.path == "/add-source") {
        uri.getQueryParameter("github")?.trim('/')?.takeIf(String::isNotBlank)?.let { return "https://github.com/" + it }
        uri.getQueryParameter("gitlab")?.trim('/')?.takeIf(String::isNotBlank)?.let { return "https://gitlab.com/" + it }
    }
    githubSlug(uri)?.let { return "https://github.com/" + it }
    if (host == "gitlab.com" && uri.pathSegments.size >= 2) return "https://gitlab.com/" + uri.pathSegments.take(2).joinToString("/")
    return normalized.takeIf { uri.scheme == "http" || uri.scheme == "https" }
}

private fun canonicalKeys(value: String): Set<String> {
    val original = runCatching { Uri.parse(value) }.getOrNull() ?: return emptySet()
    val originalHost = original.host?.lowercase(Locale.ROOT).orEmpty()
    val isCommunityAddLink = original.path == "/add-source" &&
        (originalHost == "morphe-patches.software" || originalHost.endsWith(".morphe-patches.software") ||
            originalHost == "morphe.software" || originalHost.endsWith(".morphe.software"))
    val resolved = when {
        original.scheme.equals("morphe", ignoreCase = true) -> {
            val source = original.getQueryParameter("url")
                ?: original.getQueryParameter("github")?.let { "https://github.com/" + it }
                ?: original.getQueryParameter("repo")?.let { "https://github.com/" + it }
            source?.let { repositoryUrl(it) ?: it } ?: value
        }
        value.startsWith("intent://") || isCommunityAddLink ->
            repositoryUrl(value) ?: value
        else -> value
    }
    val uri = runCatching { Uri.parse(resolved) }.getOrNull() ?: return emptySet()
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)
    val path = uri.encodedPath.orEmpty().trimEnd('/').replace(Regex("\\.git$", RegexOption.IGNORE_CASE), "")
    val port = uri.port.takeIf { it > 0 && !((scheme == "https" && it == 443) || (scheme == "http" && it == 80)) }?.let { ":" + it }.orEmpty()
    val slug = githubSlug(uri) ?: githubSlug(original)
    return buildSet {
        if (scheme in setOf("http", "https") && !host.isNullOrBlank()) add("url:" + scheme + "://" + host + port + path + (uri.encodedQuery?.let { "?" + it } ?: ""))
        slug?.let { add("gh:" + it.lowercase(Locale.ROOT).removeSuffix(".git")) }
        Regex("^([\\w.-]+)/([\\w.-]+)$").matchEntire(value.trim())?.let {
            add(("gh:" + it.groupValues[1] + "/" + it.groupValues[2]).lowercase(Locale.ROOT).removeSuffix(".git"))
        }
    }
}

private fun githubSlug(uri: Uri): String? {
    val parts = when (uri.host?.lowercase(Locale.ROOT)) {
        "github.com", "raw.githubusercontent.com" -> uri.pathSegments.take(2)
        "api.github.com" -> if (uri.pathSegments.firstOrNull() == "repos") uri.pathSegments.drop(1).take(2) else emptyList()
        else -> emptyList()
    }
    if (parts.size < 2) return null
    val owner = parts[0].lowercase(Locale.ROOT)
    val repo = parts[1].lowercase(Locale.ROOT).removeSuffix(".git")
    return (owner + "/" + repo).takeIf { owner.isNotBlank() && repo.isNotBlank() }
}
