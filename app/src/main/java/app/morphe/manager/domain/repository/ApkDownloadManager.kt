package app.morphe.manager.domain.repository

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

enum class ApkDownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

data class ApkDownloadRecord(
    val id: String,
    val fileName: String,
    val filePath: String,
    val packageName: String?,
    val versionName: String?,
    val sourceUrl: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: ApkDownloadStatus,
    val timestamp: Long,
    val userAgent: String,
    val cookie: String?,
    val referer: String?,
    val error: String? = null,
    val bytesPerSecond: Long = 0L
)

/** Application-scoped private APK vault and resumable, three-slot HTTP downloader. */
class ApkDownloadManager(
    app: Application,
    private val client: OkHttpClient
) {
    private val root = File(app.filesDir, "downloaded_apks").apply { mkdirs() }
    private val legacyBaseApkRoot = File(app.filesDir, "downloaded_base_apks")
    private val metadataFile = File(root, "downloads.json")
    private val gate = Any()
    private val slots = Semaphore(3)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, okhttp3.Call>()
    private val pausing = ConcurrentHashMap<String, AtomicBoolean>()
    private val _downloads = MutableStateFlow(readAndReconcile())
    val downloads: StateFlow<List<ApkDownloadRecord>> = _downloads.asStateFlow()

    init {
        publish(_downloads.value)
    }

    fun enqueue(
        url: String,
        fileName: String,
        packageName: String? = null,
        versionName: String? = null,
        userAgent: String,
        cookie: String? = null,
        referer: String? = null,
        id: String = UUID.randomUUID().toString()
    ): String {
        val name = safeName(fileName)
        val uniqueName = uniqueDownloadName(name, id)
        val record = ApkDownloadRecord(
            id, uniqueName, File(root, uniqueName).absolutePath, packageName, versionName, url,
            0L, -1L, ApkDownloadStatus.QUEUED, System.currentTimeMillis(), userAgent, cookie, referer
        )
        synchronized(gate) { publish(_downloads.value + record) }
        launch(record)
        return id
    }

    fun pause(id: String) {
        pausing[id]?.set(true)
        update(id) { it.copy(status = ApkDownloadStatus.PAUSED) }
        calls[id]?.cancel()
        jobs[id]?.cancel()
    }

    fun resume(id: String) {
        val record = _downloads.value.firstOrNull { it.id == id } ?: return
        if (record.status !in setOf(ApkDownloadStatus.PAUSED, ApkDownloadStatus.FAILED)) return
        val queued = record.copy(status = ApkDownloadStatus.QUEUED, error = null)
        update(id) { queued }
        val previous = jobs[id]
        scope.launch {
            previous?.join()
            if (_downloads.value.any { it.id == id && it.status == ApkDownloadStatus.QUEUED }) launch(queued)
        }
    }

    fun retry(id: String) = resume(id)

    fun cancel(id: String, deleteFile: Boolean = true) {
        pausing.remove(id)
        calls[id]?.cancel()
        val job = jobs[id]
        if (deleteFile) job?.invokeOnCompletion { File(root, "$id.part").delete() }
        job?.cancel()
        val record = _downloads.value.firstOrNull { it.id == id } ?: return
        File(root, "$id.part").delete()
        if (deleteFile) File(record.filePath).delete()
        synchronized(gate) { publish(_downloads.value.filterNot { it.id == id }) }
    }

    fun delete(id: String) = cancel(id, deleteFile = true)

    fun deleteByPath(path: String) {
        val target = runCatching { File(path).canonicalPath }.getOrNull() ?: return
        val record = _downloads.value.firstOrNull { runCatching { File(it.filePath).canonicalPath == target }.getOrDefault(false) }
        if (record != null) delete(record.id) else {
            val allowedRoots = listOf(root, legacyBaseApkRoot).mapNotNull { runCatching { it.canonicalPath + File.separator }.getOrNull() }
            File(target).takeIf { file -> allowedRoots.any { allowedRoot -> file.canonicalPath.startsWith(allowedRoot) } }?.delete()
        }
    }

    fun clearCompleted() {
        _downloads.value.filter { it.status == ApkDownloadStatus.COMPLETED }.forEach { delete(it.id) }
    }

    fun clearAll() {
        _downloads.value.toList().forEach { cancel(it.id, deleteFile = true) }
        root.listFiles().orEmpty().filterNot { it == metadataFile }.forEach { it.deleteRecursively() }
        synchronized(gate) { publish(emptyList()) }
    }

    fun completedForPackage(packageName: String): List<ApkDownloadRecord> =
        _downloads.value.filter { it.status == ApkDownloadStatus.COMPLETED && it.packageName == packageName && File(it.filePath).isFile }

    fun deleteCompletedForPackage(packageName: String) {
        completedForPackage(packageName).forEach { delete(it.id) }
    }

    fun completedBytes(): Long = _downloads.value
        .filter { it.status == ApkDownloadStatus.COMPLETED }
        .sumOf { File(it.filePath).takeIf(File::isFile)?.length() ?: 0L }

    fun activeCount(): Int = _downloads.value.count {
        it.status == ApkDownloadStatus.QUEUED || it.status == ApkDownloadStatus.DOWNLOADING
    }

    private fun launch(record: ApkDownloadRecord) {
        if (jobs[record.id]?.isActive == true) return
        pausing[record.id] = AtomicBoolean(false)
        jobs[record.id] = scope.launch {
            try {
                slots.withPermit { download(record) }
            } catch (cancelled: CancellationException) {
                if (pausing[record.id]?.get() == true) {
                    if (_downloads.value.any { it.id == record.id && it.status != ApkDownloadStatus.QUEUED }) {
                        update(record.id) { old -> old.copy(status = ApkDownloadStatus.PAUSED, error = null) }
                    }
                } else if (_downloads.value.any { it.id == record.id }) {
                    update(record.id) { old -> old.copy(status = ApkDownloadStatus.FAILED, error = "Download cancelled") }
                }
            } catch (error: Exception) {
                if (pausing[record.id]?.get() == true && !coroutineContext.isActive) {
                    if (_downloads.value.any { it.id == record.id && it.status != ApkDownloadStatus.QUEUED }) {
                        update(record.id) { old -> old.copy(status = ApkDownloadStatus.PAUSED, error = null) }
                    }
                } else {
                    update(record.id) { old -> old.copy(status = ApkDownloadStatus.FAILED, error = error.message ?: "Download failed") }
                }
            } finally {
                calls.remove(record.id)
                jobs.remove(record.id)
                pausing.remove(record.id)
                if (_downloads.value.none { it.id == record.id }) File(root, "${record.id}.part").delete()
            }
        }
    }

    private suspend fun download(initial: ApkDownloadRecord) {
        val part = File(root, "${initial.id}.part")
        var offset = part.length()
        val builder = Request.Builder().url(initial.sourceUrl).get()
            .header("User-Agent", initial.userAgent)
            .header("Accept", "*/*")
        initial.cookie?.takeIf(String::isNotBlank)?.let { builder.header("Cookie", it) }
        initial.referer?.takeIf(String::isNotBlank)?.let { builder.header("Referer", it) }
        if (offset > 0L) builder.header("Range", "bytes=$offset-")
        val call = client.newCall(builder.build())
        calls[initial.id] = call
        update(initial.id) { it.copy(status = ApkDownloadStatus.DOWNLOADING, downloadedBytes = offset) }
        call.execute().use { response ->
            coroutineContext.ensureActive()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("The server returned no file")
            if (body.contentType()?.subtype?.contains("html", ignoreCase = true) == true) {
                error("The server returned a web page instead of an APK")
            }
            val append = offset > 0L && response.code == 206
            if (!append) {
                offset = 0L
                part.delete()
            }
            val bodyLength = body.contentLength()
            val total = if (append) {
                response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: (offset + bodyLength).takeIf { bodyLength >= 0L } ?: -1L
            } else bodyLength
            val disposition = response.header("Content-Disposition").orEmpty()
            val downloadedName = contentName(disposition) ?: Uri.parse(response.request.url.toString()).lastPathSegment
            val finalName = uniqueDownloadName(
                safeName(downloadedName?.takeIf(String::isNotBlank) ?: initial.fileName),
                initial.id
            )
            val destination = File(root, finalName)
            var count = offset
            var lastAt = System.nanoTime()
            var lastCount = count
            body.byteStream().use { input ->
                FileOutputStream(part, append).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        count += read
                        val now = System.nanoTime()
                        if (now - lastAt >= 350_000_000L) {
                            val speed = ((count - lastCount) * 1_000_000_000L / (now - lastAt)).coerceAtLeast(0L)
                            update(initial.id) { it.copy(fileName = finalName, filePath = destination.absolutePath, downloadedBytes = count, totalBytes = total, bytesPerSecond = speed, status = ApkDownloadStatus.DOWNLOADING) }
                            lastAt = now
                            lastCount = count
                        }
                    }
                }
            }
            coroutineContext.ensureActive()
            if (count <= 0L) error("The downloaded file is empty")
            if (!part.hasZipHeader()) error("The downloaded file is not a valid APK or Android app bundle")
            if (!part.renameTo(destination)) error("Could not save the downloaded file")
            update(initial.id) {
                it.copy(fileName = finalName, filePath = destination.absolutePath, downloadedBytes = count,
                    totalBytes = if (total > 0L) total else count, status = ApkDownloadStatus.COMPLETED,
                    error = null, bytesPerSecond = 0L)
            }
        }
    }

    private fun update(id: String, transform: (ApkDownloadRecord) -> ApkDownloadRecord) {
        synchronized(gate) {
            publish(_downloads.value.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun publish(records: List<ApkDownloadRecord>) {
        _downloads.value = records.sortedByDescending(ApkDownloadRecord::timestamp)
        runCatching { metadataFile.writeText(JSONArray().apply { _downloads.value.forEach { put(it.toJson()) } }.toString()) }
    }

    private fun readAndReconcile(): List<ApkDownloadRecord> = runCatching {
        val array = JSONArray(metadataFile.takeIf(File::isFile)?.readText().orEmpty())
        val records = (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val record = json.toRecord() ?: return@mapNotNull null
            val file = File(record.filePath)
            if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) return@mapNotNull null
            val partial = File(root, "${record.id}.part")
            when {
                record.status == ApkDownloadStatus.COMPLETED && !file.isFile -> null
                record.status in setOf(ApkDownloadStatus.DOWNLOADING, ApkDownloadStatus.QUEUED) -> record.copy(
                    status = ApkDownloadStatus.PAUSED,
                    downloadedBytes = partial.length(),
                    error = null
                )
                else -> record
            }
        }
        val retainedFiles = records.filter { it.status == ApkDownloadStatus.COMPLETED }.map { File(it.filePath).canonicalPath }.toSet()
        val retainedPartIds = records.filter { it.status != ApkDownloadStatus.COMPLETED }.map(ApkDownloadRecord::id).toSet()
        root.listFiles().orEmpty().filter { it != metadataFile }.forEach { file ->
            val keepCompleted = file.isFile && file.canonicalPath in retainedFiles
            val keepPartial = file.isFile && file.name.endsWith(".part") && file.name.removeSuffix(".part") in retainedPartIds
            if (!keepCompleted && !keepPartial) file.deleteRecursively()
        }
        records
    }.getOrDefault(emptyList())

    private fun ApkDownloadRecord.toJson() = JSONObject().apply {
        put("id", id); put("fileName", fileName); put("filePath", filePath); put("packageName", packageName)
        put("versionName", versionName); put("sourceUrl", sourceUrl); put("downloadedBytes", downloadedBytes)
        put("totalBytes", totalBytes); put("status", status.name); put("timestamp", timestamp)
        put("userAgent", userAgent); put("cookie", cookie); put("referer", referer); put("error", error)
        put("bytesPerSecond", bytesPerSecond)
    }

    private fun JSONObject.toRecord(): ApkDownloadRecord? = runCatching {
        ApkDownloadRecord(
            getString("id"), getString("fileName"), getString("filePath"), optString("packageName").takeIf(String::isNotBlank),
            optString("versionName").takeIf(String::isNotBlank), getString("sourceUrl"), optLong("downloadedBytes"),
            optLong("totalBytes", -1L), ApkDownloadStatus.valueOf(getString("status")), optLong("timestamp"),
            optString("userAgent"), optString("cookie").takeIf(String::isNotBlank), optString("referer").takeIf(String::isNotBlank),
            optString("error").takeIf(String::isNotBlank), optLong("bytesPerSecond")
        )
    }.getOrNull()

    private fun contentName(disposition: String): String? =
            Regex("filename\\*?=(?:UTF-8''|\\\")?([^;\\\"]+)", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)?.trim()?.let { Uri.decode(it) }

    private fun safeName(value: String): String {
        val clean = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(140)
        return clean.takeIf(String::isNotBlank) ?: "download.apk"
    }

    private fun uniqueDownloadName(name: String, id: String): String {
        val extension = name.substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        val stem = if (extension.isBlank()) name else name.removeSuffix(extension)
        return safeName("$stem-${id.take(8)}$extension")
    }

    private fun File.hasZipHeader(): Boolean = runCatching {
        inputStream().use { input ->
            input.read() == 0x50 && input.read() == 0x4B && input.read() in setOf(0x03, 0x05, 0x07)
        }
    }.getOrDefault(false)
}
