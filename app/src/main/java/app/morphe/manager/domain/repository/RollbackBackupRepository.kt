/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.repository

import android.app.Application
import android.content.pm.PackageInfo
import android.util.Log
import app.morphe.manager.domain.installer.SessionApkFile
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.service.RollbackBackupPromptNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** The single previous installed version kept for a package. */
data class RollbackBackup(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val backedUpAt: Long,
    val apkFileNames: List<String>,
    val sizeBytes: Long = 0L,
) {
    val isSplitInstall: Boolean get() = apkFileNames.size > 1
}

/** Keeps one durable pre-overwrite APK set per package in Morphe's private files. */
class RollbackBackupRepository(
    private val app: Application,
    private val storageStatsRepository: StorageStatsRepository,
    private val preferences: PreferencesManager
) {
    private val root = File(app.noBackupFilesDir, "rollback-backups")
    private val _backups = MutableStateFlow<List<RollbackBackup>>(emptyList())
    val backups = _backups.asStateFlow()
    private val _cleanupPendingPackages = MutableStateFlow<Set<String>>(emptySet())
    val cleanupPendingPackages: StateFlow<Set<String>> = _cleanupPendingPackages.asStateFlow()
    private val _cleanupBannerPackages = MutableStateFlow<Set<String>>(emptySet())
    val cleanupBannerPackages: StateFlow<Set<String>> = _cleanupBannerPackages.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _backups.value = readBackups()
        _cleanupPendingPackages.value = preferences.rollbackCleanupPendingPackages.get()
        _cleanupBannerPackages.value = preferences.rollbackCleanupBannerPackages.get()
    }

    suspend fun get(packageName: String): RollbackBackup? = withContext(Dispatchers.IO) {
        readBackup(directoryFor(packageName))
    }

    /** Copies the installed base APK and any installed splits, replacing the previous backup. */
    suspend fun backupInstalled(packageInfo: PackageInfo, versionCode: Long): RollbackBackup =
        withContext(Dispatchers.IO) {
            val packageName = packageInfo.packageName
            val applicationInfo = packageInfo.applicationInfo
                ?: error("Installed application info is unavailable for $packageName")
            val sourceFiles = buildList {
                applicationInfo.sourceDir?.let { add("base.apk" to File(it)) }
                applicationInfo.splitSourceDirs.orEmpty().forEachIndexed { index, path ->
                    val splitName = applicationInfo.splitNames?.getOrNull(index)
                        ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        ?.takeIf(String::isNotBlank)
                    add("split_${splitName ?: index}.apk" to File(path))
                }
            }
            require(sourceFiles.isNotEmpty()) { "No installed APK files were found for $packageName" }
            sourceFiles.forEach { (name, file) ->
                require(file.isFile && file.canRead()) { "Cannot read installed APK $name for $packageName" }
            }

            val target = directoryFor(packageName)
            val temp = File(root, ".${safePackageName(packageName)}.tmp")
            val previous = File(root, ".${safePackageName(packageName)}.old")
            temp.deleteRecursively()
            previous.deleteRecursively()
            check(temp.mkdirs() || temp.isDirectory) { "Cannot create rollback backup directory" }

            try {
                sourceFiles.forEach { (name, source) ->
                    FileInputStream(source).use { input ->
                        FileOutputStream(File(temp, name)).use { output -> input.copyTo(output) }
                    }
                }
                val backup = RollbackBackup(
                    packageName = packageName,
                    versionName = packageInfo.versionName,
                    versionCode = versionCode,
                    backedUpAt = System.currentTimeMillis(),
                    apkFileNames = sourceFiles.map { it.first },
                    sizeBytes = sourceFiles.sumOf { (_, file) -> file.length() }
                )
                writeMetadata(File(temp, METADATA_FILE), backup)

                if (target.exists()) check(target.renameTo(previous)) { "Could not replace old rollback backup" }
                if (!temp.renameTo(target)) {
                    previous.takeIf { it.exists() }?.renameTo(target)
                    error("Could not save rollback backup")
                }
                previous.deleteRecursively()
                resolveCleanupPrompt(packageName)
                _backups.value = readBackups()
                storageStatsRepository.refresh()
                backup
            } catch (error: Throwable) {
                temp.deleteRecursively()
                throw error
            }
        }

    suspend fun sessionFiles(packageName: String): List<SessionApkFile> = withContext(Dispatchers.IO) {
        val backup = readBackup(directoryFor(packageName)) ?: return@withContext emptyList()
        backup.apkFileNames.map { name ->
            val file = File(directoryFor(packageName), name)
            require(file.isFile && file.canRead()) { "Rollback APK is missing: $name" }
            SessionApkFile(name = name, file = file)
        }
    }

    suspend fun delete(packageName: String) = withContext(Dispatchers.IO) {
        directoryFor(packageName).deleteRecursively()
        resolveCleanupPrompt(packageName)
        _backups.value = readBackups()
        storageStatsRepository.refresh()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        root.deleteRecursively()
        _cleanupPendingPackages.value.forEach { RollbackBackupPromptNotifier.cancel(app, it) }
        preferences.rollbackCleanupPendingPackages.update(emptySet())
        preferences.rollbackCleanupBannerPackages.update(emptySet())
        _cleanupPendingPackages.value = emptySet()
        _cleanupBannerPackages.value = emptySet()
        _backups.value = emptyList()
        storageStatsRepository.refresh()
    }

    suspend fun registerCleanupPrompt(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (readBackup(directoryFor(packageName)) == null) return@withContext false
        val pending = preferences.rollbackCleanupPendingPackages.get() + packageName
        preferences.rollbackCleanupPendingPackages.update(pending)
        val banners = preferences.rollbackCleanupBannerPackages.get() - packageName
        preferences.rollbackCleanupBannerPackages.update(banners)
        _cleanupPendingPackages.value = pending
        _cleanupBannerPackages.value = banners
        true
    }

    suspend fun markCleanupPromptDismissed(packageName: String) = withContext(Dispatchers.IO) {
        if (packageName !in preferences.rollbackCleanupPendingPackages.get()) return@withContext
        val banners = preferences.rollbackCleanupBannerPackages.get() + packageName
        preferences.rollbackCleanupBannerPackages.update(banners)
        _cleanupBannerPackages.value = banners
    }

    suspend fun keepBackup(packageName: String) = withContext(Dispatchers.IO) {
        resolveCleanupPrompt(packageName)
    }

    private suspend fun resolveCleanupPrompt(packageName: String) {
        val pending = preferences.rollbackCleanupPendingPackages.get() - packageName
        val banners = preferences.rollbackCleanupBannerPackages.get() - packageName
        preferences.rollbackCleanupPendingPackages.update(pending)
        preferences.rollbackCleanupBannerPackages.update(banners)
        _cleanupPendingPackages.value = pending
        _cleanupBannerPackages.value = banners
        RollbackBackupPromptNotifier.cancel(app, packageName)
    }

    private fun readBackups(): List<RollbackBackup> =
        root.listFiles()?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull(::readBackup)
            ?.sortedByDescending(RollbackBackup::backedUpAt)
            ?.toList()
            .orEmpty()

    private fun readBackup(directory: File): RollbackBackup? = runCatching {
        val json = JSONObject(File(directory, METADATA_FILE).readText())
        val names = json.getJSONArray("apkFiles").toStringList()
        if (names.isEmpty() || names.any { File(directory, it).isFile.not() }) return null
        RollbackBackup(
            packageName = json.getString("packageName"),
            versionName = json.optString("versionName").takeIf(String::isNotBlank),
            versionCode = json.getLong("versionCode"),
            backedUpAt = json.getLong("backedUpAt"),
            apkFileNames = names,
            sizeBytes = names.sumOf { File(directory, it).length() }
        )
    }.onFailure { Log.w(TAG, "Could not read rollback metadata from ${directory.name}", it) }.getOrNull()

    private fun writeMetadata(file: File, backup: RollbackBackup) {
        val json = JSONObject()
            .put("packageName", backup.packageName)
            .put("versionName", backup.versionName)
            .put("versionCode", backup.versionCode)
            .put("backedUpAt", backup.backedUpAt)
            .put("apkFiles", JSONArray(backup.apkFileNames))
        file.writeText(json.toString())
    }

    private fun directoryFor(packageName: String) = File(root, safePackageName(packageName))

    private fun safePackageName(packageName: String) =
        packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotBlank) }

    private companion object {
        const val METADATA_FILE = "backup.json"
        const val TAG = "RollbackBackupRepository"
    }
}
