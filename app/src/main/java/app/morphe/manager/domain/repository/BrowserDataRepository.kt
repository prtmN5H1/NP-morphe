package app.morphe.manager.domain.repository

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class BrowserSearchEngine(val template: String) {
    GOOGLE("https://www.google.com/search?q={query}"),
    DUCKDUCKGO("https://duckduckgo.com/?q={query}"),
    BRAVE("https://search.brave.com/search?q={query}"),
    BING("https://www.bing.com/search?q={query}"),
    CUSTOM("");

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: GOOGLE
    }
}

enum class UserScriptRunAt { DOCUMENT_START, DOCUMENT_END }

private fun String.matchesGlob(value: String): Boolean = runCatching {
    val expression = buildString {
        append('^')
        this@matchesGlob.forEach { character ->
            when (character) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> {
                    if (character in ".\\+()[]{}^$|") append('\\')
                    append(character)
                }
            }
        }
        append('$')
    }
    Regex(expression, RegexOption.IGNORE_CASE).matches(value)
}.getOrDefault(false)

data class BrowserBookmark(
    val id: String,
    val title: String,
    val url: String,
    val pinned: Boolean = false
)

data class BrowserHistoryEntry(
    val id: String,
    val title: String,
    val url: String,
    val visitedAt: Long
)

data class BrowserUserScript(
    val id: String,
    val name: String,
    val source: String,
    val matches: List<String>,
    val excludes: List<String>,
    val runAt: UserScriptRunAt,
    val enabled: Boolean
) {
    fun matchesUrl(url: String): Boolean = enabled &&
        matches.any { it.matchesGlob(url) } && excludes.none { it.matchesGlob(url) }
}

data class BrowserDataSnapshot(
    val searchEngine: BrowserSearchEngine = BrowserSearchEngine.GOOGLE,
    val customSearchUrl: String = "",
    val adBlockEnabled: Boolean = true,
    val bookmarks: List<BrowserBookmark> = emptyList(),
    val history: List<BrowserHistoryEntry> = emptyList(),
    val userScripts: List<BrowserUserScript> = emptyList()
) {
    fun searchUrl(query: String): String {
        val template = if (searchEngine == BrowserSearchEngine.CUSTOM) customSearchUrl else searchEngine.template
        val encoded = Uri.encode(query)
        return template.replace("{query}", encoded).ifBlank { BrowserSearchEngine.GOOGLE.template.replace("{query}", encoded) }
    }
}

/** Local, small JSON-backed browser state. It contains no web cache or browser engine data. */
class BrowserDataRepository(
    app: Application,
    private val client: OkHttpClient
) {
    private val prefs = app.getSharedPreferences(PREFERENCES, Application.MODE_PRIVATE)
    private val lock = Any()
    private val _state = MutableStateFlow(read())
    val state: StateFlow<BrowserDataSnapshot> = _state.asStateFlow()

    fun setSearchEngine(engine: BrowserSearchEngine, customUrl: String = _state.value.customSearchUrl) = mutate {
        it.copy(searchEngine = engine, customSearchUrl = customUrl.trim())
    }

    fun setAdBlockEnabled(enabled: Boolean) = mutate { it.copy(adBlockEnabled = enabled) }

    fun addBookmark(title: String, url: String, pinned: Boolean = false) {
        val normalized = validWebUrl(url) ?: return
        mutate { current ->
            val bookmark = BrowserBookmark(UUID.randomUUID().toString(), title.ifBlank { Uri.parse(normalized).host.orEmpty() }, normalized, pinned)
            current.copy(bookmarks = (listOf(bookmark) + current.bookmarks.filterNot { it.url == normalized }).take(MAX_BOOKMARKS))
        }
    }

    fun updateBookmark(bookmark: BrowserBookmark) = mutate { current ->
        current.copy(bookmarks = current.bookmarks.map { if (it.id == bookmark.id) bookmark.copy(url = validWebUrl(bookmark.url) ?: it.url) else it })
    }

    fun deleteBookmark(id: String) = mutate { it.copy(bookmarks = it.bookmarks.filterNot { bookmark -> bookmark.id == id }) }

    fun recordVisit(url: String, title: String) {
        val normalized = validWebUrl(url) ?: return
        mutate { current ->
            val entry = BrowserHistoryEntry(UUID.randomUUID().toString(), title.ifBlank { Uri.parse(normalized).host.orEmpty() }, normalized, System.currentTimeMillis())
            current.copy(history = (listOf(entry) + current.history.filterNot { it.url == normalized }).take(MAX_HISTORY))
        }
    }

    fun deleteHistoryEntry(id: String) = mutate { it.copy(history = it.history.filterNot { entry -> entry.id == id }) }
    fun clearHistory() = mutate { it.copy(history = emptyList()) }

    fun addUserScript(raw: String, fallbackName: String = "User script"): BrowserUserScript {
        val script = parseUserScript(raw, fallbackName)
        mutate { current -> current.copy(userScripts = (listOf(script) + current.userScripts.filterNot { it.id == script.id }).take(MAX_USER_SCRIPTS)) }
        return script
    }

    suspend fun installUserScript(url: String): BrowserUserScript = withContext(Dispatchers.IO) {
        val safeUrl = validWebUrl(url) ?: error("Enter a valid http or https URL")
        val request = Request.Builder().url(safeUrl).header("Accept", "text/javascript, application/javascript, */*").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "The script URL returned HTTP ${response.code}" }
            val text = response.body?.string().orEmpty()
            require(text.isNotBlank() && text.length <= MAX_SCRIPT_CHARS) { "The downloaded script is empty or too large" }
            addUserScript(text, Uri.parse(safeUrl).lastPathSegment.orEmpty().ifBlank { "User script" })
        }
    }

    fun setUserScriptEnabled(id: String, enabled: Boolean) = mutate { current ->
        current.copy(userScripts = current.userScripts.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun deleteUserScript(id: String) = mutate { it.copy(userScripts = it.userScripts.filterNot { script -> script.id == id }) }

    fun scriptsFor(url: String, at: UserScriptRunAt): List<BrowserUserScript> =
        _state.value.userScripts.filter { it.runAt == at && it.matchesUrl(url) }

    fun shouldBlockRequest(url: String): Boolean {
        if (!_state.value.adBlockEnabled) return false
        val host = runCatching { Uri.parse(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun exportJson(): String = encode(_state.value).toString()

    fun importJson(raw: String) {
        val parsed = decode(JSONObject(raw))
        synchronized(lock) {
            _state.value = parsed
            prefs.edit().putString(KEY_JSON, encode(parsed).toString()).apply()
        }
    }

    private inline fun mutate(transform: (BrowserDataSnapshot) -> BrowserDataSnapshot) {
        synchronized(lock) {
            val updated = transform(_state.value)
            _state.value = updated
            prefs.edit().putString(KEY_JSON, encode(updated).toString()).apply()
        }
    }

    private fun read(): BrowserDataSnapshot = runCatching {
        prefs.getString(KEY_JSON, null)?.let { decode(JSONObject(it)) } ?: BrowserDataSnapshot()
    }.getOrDefault(BrowserDataSnapshot())

    private fun parseUserScript(raw: String, fallbackName: String): BrowserUserScript {
        require(raw.isNotBlank() && raw.length <= MAX_SCRIPT_CHARS) { "The script is empty or too large" }
        val header = raw.substringAfter("==UserScript==", "").substringBefore("==/UserScript==", "")
        fun values(key: String) = Regex("(?m)^\\s*@${Regex.escape(key)}\\s+(.+?)\\s*$")
            .findAll(header).map { it.groupValues[1].trim() }.toList()
        val name = values("name").firstOrNull().orEmpty().ifBlank { fallbackName }
        val matchRules = (values("match") + values("include")).ifEmpty { listOf("*") }
        val excludes = values("exclude") + values("exclude-match")
        val runAt = if (values("run-at").firstOrNull()?.contains("document-start", true) == true) {
            UserScriptRunAt.DOCUMENT_START
        } else UserScriptRunAt.DOCUMENT_END
        val id = UUID.nameUUIDFromBytes((name + raw.hashCode()).toByteArray()).toString()
        return BrowserUserScript(id, name, raw, matchRules, excludes, runAt, true)
    }

    private fun encode(snapshot: BrowserDataSnapshot) = JSONObject().apply {
        put("version", 1)
        put("searchEngine", snapshot.searchEngine.name)
        put("customSearchUrl", snapshot.customSearchUrl)
        put("adBlockEnabled", snapshot.adBlockEnabled)
        put("bookmarks", JSONArray().apply { snapshot.bookmarks.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url).put("pinned", it.pinned)) } })
        put("history", JSONArray().apply { snapshot.history.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url).put("visitedAt", it.visitedAt)) } })
        put("userScripts", JSONArray().apply { snapshot.userScripts.forEach { script ->
            put(JSONObject().put("id", script.id).put("name", script.name).put("source", script.source)
                .put("matches", JSONArray(script.matches)).put("excludes", JSONArray(script.excludes))
                .put("runAt", script.runAt.name).put("enabled", script.enabled))
        } })
    }

    private fun decode(json: JSONObject) = BrowserDataSnapshot(
        searchEngine = BrowserSearchEngine.parse(json.optString("searchEngine")),
        customSearchUrl = json.optString("customSearchUrl"),
        adBlockEnabled = json.optBoolean("adBlockEnabled", true),
        bookmarks = json.optJSONArray("bookmarks").objects().mapNotNull { obj ->
            val url = validWebUrl(obj.optString("url")) ?: return@mapNotNull null
            BrowserBookmark(obj.optString("id", UUID.randomUUID().toString()), obj.optString("title"), url, obj.optBoolean("pinned"))
        }.take(MAX_BOOKMARKS),
        history = json.optJSONArray("history").objects().mapNotNull { obj ->
            val url = validWebUrl(obj.optString("url")) ?: return@mapNotNull null
            BrowserHistoryEntry(obj.optString("id", UUID.randomUUID().toString()), obj.optString("title"), url, obj.optLong("visitedAt"))
        }.take(MAX_HISTORY),
        userScripts = json.optJSONArray("userScripts").objects().mapNotNull { obj ->
            val source = obj.optString("source")
            if (source.isBlank() || source.length > MAX_SCRIPT_CHARS) return@mapNotNull null
            BrowserUserScript(
                obj.optString("id", UUID.randomUUID().toString()), obj.optString("name", "User script"), source,
                obj.optJSONArray("matches").strings().ifEmpty { listOf("*") },
                obj.optJSONArray("excludes").strings(),
                runCatching { UserScriptRunAt.valueOf(obj.optString("runAt")) }.getOrDefault(UserScriptRunAt.DOCUMENT_END),
                obj.optBoolean("enabled", true)
            )
        }.take(MAX_USER_SCRIPTS)
    )

    private fun JSONArray?.objects(): List<JSONObject> = this?.let { array ->
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }.orEmpty()

    private fun JSONArray?.strings(): List<String> = this?.let { array ->
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.orEmpty()

    private fun validWebUrl(value: String): String? = runCatching {
        Uri.parse(value.trim()).takeIf { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() }?.toString()
    }.getOrNull()

    private companion object {
        const val PREFERENCES = "npmorphe_browser_data"
        const val KEY_JSON = "browser_json_v1"
        const val MAX_BOOKMARKS = 500
        const val MAX_HISTORY = 1000
        const val MAX_USER_SCRIPTS = 100
        const val MAX_SCRIPT_CHARS = 512_000
        val BLOCKED_HOSTS = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "adnxs.com", "adsrvr.org", "taboola.com", "outbrain.com", "scorecardresearch.com",
            "adform.net", "criteo.com", "analytics.tiktok.com", "connect.facebook.net"
        )
    }
}
