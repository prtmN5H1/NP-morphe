/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.repository

import android.app.Application
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.manager.DownloadAutoDeleteMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One completed build, retained independently of the patched package's installed state. */
@Serializable
data class PatchHistoryEntry(
    val id: String,
    val packageName: String,
    val appName: String,
    val baseVersionName: String?,
    val baseVersionCode: Long?,
    val baseFileName: String,
    val downloadSourceUrl: String? = null,
    val patchSources: List<String>,
    val appliedPatches: List<String>,
    val libraries: List<String> = emptyList(),
    val managerVersion: String,
    val patcherVersion: String,
    val outputVersionName: String?,
    val outputVersionCode: Long?,
    val patchCompletedAtMillis: Long,
    val installConfirmedAtMillis: Long? = null,
    val previousVersionName: String? = null,
    val previousVersionCode: Long? = null,
    val apkSizeBytes: Long? = null,
)

/** Stores patch and install history in manager-owned files, so removing the target app leaves it intact. */
class PatchHistoryRepository(
    private val app: Application,
    private val prefs: PreferencesManager,
    private val apkDownloads: ApkDownloadManager,
) {
    private val historyFile = File(app.filesDir, HISTORY_FILE_NAME)
    private val mutex = Mutex()
    private val _entries = MutableStateFlow(readHistory())

    val entries: StateFlow<List<PatchHistoryEntry>> = _entries.asStateFlow()

    suspend fun record(entry: PatchHistoryEntry) {
        if (prefs.downloadAutoDeleteMode.get() == DownloadAutoDeleteMode.AFTER_PATCH) {
            apkDownloads.deleteCompletedForPackage(entry.packageName)
        }
        if (!prefs.patchHistoryEnabled.get()) return
        if (entry.packageName in prefs.patchHistoryDisabledApps.get()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                persist((_entries.value.filterNot { it.id == entry.id } + entry).sortedByDescending { it.patchCompletedAtMillis })
            }
        }
    }

    /** Marks the newest matching build once Android confirms it was installed. */
    suspend fun markInstalled(
        packageName: String,
        installedVersionName: String?,
        installedVersionCode: Long?,
        previousVersionName: String?,
        previousVersionCode: Long?,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val match = _entries.value
                .filter { entry ->
                    entry.packageName == packageName && entry.installConfirmedAtMillis == null &&
                            System.currentTimeMillis() - entry.patchCompletedAtMillis <= MAX_INSTALL_MATCH_AGE_MILLIS &&
                            (installedVersionCode == null || entry.outputVersionCode == installedVersionCode) &&
                            (installedVersionName.isNullOrBlank() || entry.outputVersionName == installedVersionName)
                }
                .maxByOrNull { it.patchCompletedAtMillis }
                ?: return@withLock
            persist(
                _entries.value.map { entry ->
                    if (entry.id != match.id) entry
                    else entry.copy(
                        installConfirmedAtMillis = System.currentTimeMillis(),
                        previousVersionName = previousVersionName,
                        previousVersionCode = previousVersionCode,
                    )
                }
            )
        }
    }

    suspend fun clear(packageName: String? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val retained = if (packageName == null) emptyList() else _entries.value.filterNot { it.packageName == packageName }
            persist(retained)
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { persist(_entries.value.filterNot { it.id == id }) }
    }

    suspend fun replaceAll(entries: List<PatchHistoryEntry>) = withContext(Dispatchers.IO) {
        mutex.withLock { persist(entries.sortedByDescending { it.patchCompletedAtMillis }) }
    }

    suspend fun replaceAllJson(raw: String) {
        replaceAll(json.decodeFromString<List<PatchHistoryEntry>>(raw))
    }

    fun exportJson(): String = json.encodeToString(_entries.value)

    private fun persist(entries: List<PatchHistoryEntry>) {
        historyFile.parentFile?.mkdirs()
        val staging = File(historyFile.path + ".part")
        try {
            staging.writeText(json.encodeToString(entries))
            Files.move(staging.toPath(), historyFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            _entries.value = entries
        } finally {
            staging.delete()
        }
    }

    private fun readHistory(): List<PatchHistoryEntry> = runCatching {
        if (!historyFile.isFile) emptyList()
        else json.decodeFromString<List<PatchHistoryEntry>>(historyFile.readText())
            .sortedByDescending { it.patchCompletedAtMillis }
    }.getOrDefault(emptyList())

    private companion object {
        const val HISTORY_FILE_NAME = "patch-history.json"
        const val MAX_INSTALL_MATCH_AGE_MILLIS = 24L * 60 * 60 * 1000
        @OptIn(ExperimentalSerializationApi::class)
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
