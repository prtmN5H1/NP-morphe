/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 *
 * Original hard forked code:
 * https://github.com/Jman-Github/Universal-ReVanced-Manager/blob/597b3173a004f5a9aae54326046dd7fd4c5b7777/app/src/main/java/app/revanced/manager/ui/viewmodel/ImportExportViewModel.kt
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.manager.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.os.Process
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morphe.manager.BuildConfig
import app.morphe.manager.R
import app.morphe.manager.domain.backup.AutoBackup
import app.morphe.manager.domain.backup.SettingsExporter
import app.morphe.manager.domain.backup.SettingsExporter.Companion.json
import app.morphe.manager.domain.backup.SettingsExporter.Companion.settingsJson
import app.morphe.manager.util.SourceUrl
import app.morphe.manager.domain.manager.HomeAppButtonPreferences
import app.morphe.manager.domain.manager.KeystoreManager
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.manager.SettingsSection
import app.morphe.manager.domain.manager.SigningKeyInfo
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.domain.repository.PatchOptionsRepository
import app.morphe.manager.domain.repository.PatchSelectionRepository
import app.morphe.manager.domain.repository.SelectionSetRepository
import app.morphe.manager.domain.repository.SourceMuteRepository
import app.morphe.manager.domain.repository.StoredSelectionSet
import app.morphe.manager.ui.screen.shared.UserMessages
import app.morphe.manager.util.*
import app.morphe.manager.worker.ScheduleInterval
import app.morphe.patcher.apk.ApkSigner
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayOutputStream
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteExisting

@Serializable
data class ManagerSettingsExportFile(
    val version: Int = 1,
    val settings: PreferencesManager.SettingsSnapshot,
    // Null in backups written before selections could travel with the settings, and in any that
    // leave them out
    val selections: AllSelectionsExportFile? = null
)

/**
 * Export file format for patch selections and options.
 * This format stores selections (which patches are enabled) and their options (patch configuration).
 */
@Serializable
data class PatchBundleDataExportFile(
    val version: Int = 1,
    val bundleUid: Int,
    val bundleName: String? = null,
    // Normalized endpoint URL - used to remap bundleUid when importing on a different device
    val bundleSource: String? = null,
    val exportDate: String,
    // Map<PackageName, List<PatchName>>
    val selections: Map<String, List<String>>,
    // Map<PackageName, Map<PatchName, Map<OptionKey, OptionValue>>>
    // Deliberately without a default: the field has always been written out, and giving it one
    // would drop it from the file for versions that still require it
    val options: Map<String, Map<String, Map<String, String>>>?,
    // Apps kept from this source. Null in backups written before an app could be, which reads the
    // same as a backup where no app was
    val mutedPackages: List<String>? = null,
    // Apps the source has listed so far, present only when it leaves new apps out. Null in backups
    // written before a source could, which reads as a source that brings them
    val knownApps: List<String>? = null
)

/**
 * Export file format for all selections across all bundles.
 * Wraps a list of [PatchBundleDataExportFile] entries - one per bundle.
 */
@Serializable
data class AllSelectionsExportFile(
    val version: Int = 1,
    val exportDate: String,
    val bundles: List<PatchBundleDataExportFile>,
    // Null in backups written before sets could be kept, which leaves the sets on the device alone
    val sets: List<SelectionSetExportFile>? = null
)

/** A set kept for one app, see [SelectionSetRepository], as a backup carries it. */
@Serializable
data class SelectionSetExportFile(
    val packageName: String,
    val name: String,
    val sources: List<SelectionSetSourceExport>
)

/** What a [SelectionSetExportFile] holds of one source. */
@Serializable
data class SelectionSetSourceExport(
    val bundleUid: Int,
    // Normalized endpoint URL, which the source is found by on another device, as with the
    // bundleSource of a PatchBundleDataExportFile
    val bundleSource: String? = null,
    val patches: List<String>,
    // Map<PatchName, Map<OptionKey, OptionValue>>
    val options: Map<String, Map<String, String>>? = null
)

/**
 * Serializable snapshot of remote patch bundle, used for export/import
 * as part of the manager settings file [ManagerSettingsExportFile].
 */
@Serializable
data class BundleSnapshot(
    val name: String,
    val displayName: String? = null,
    val source: String,
    /** Whether automatic release checks are enabled; older snapshots default to enabled. */
    val autoUpdate: Boolean = true,
    val enabled: Boolean = true,
    val sortOrder: Int,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    /** Original tracking endpoint when [source] is pinned to a GitHub release tag. */
    val pinnedSource: String? = null,
    /** Exact release tag represented by the pinned [source] endpoint. */
    val pinnedVersion: String? = null,
    // Prerelease toggle. Null in backups written before per-source toggles were exported
    val prerelease: Boolean? = null,
    // Experimental versions toggle. Null in backups written before per-source toggles were exported
    val experimentalVersions: Boolean? = null
)

/** Portable source configuration archive; bundle files are stored beside this manifest. */
@Serializable
data class SourceArchiveFile(
    val version: Int = 1,
    val sources: List<BundleSnapshot>,
    val artifacts: List<SourceArchiveArtifact> = emptyList(),
)

@Serializable
data class SourceArchiveArtifact(
    val source: String,
    val path: String,
    val versionSignature: String? = null,
    val signingKey: String? = null,
)

enum class SourceImportStatus { IMPORTED, DUPLICATE, FAILED }
data class SourceImportReportItem(
    val name: String,
    val url: String,
    val status: SourceImportStatus,
    val reason: String? = null
)
data class SourceImportReport(val items: List<SourceImportReportItem>) {
    val imported: Int get() = items.count { it.status == SourceImportStatus.IMPORTED }
    val duplicates: Int get() = items.count { it.status == SourceImportStatus.DUPLICATE }
    val failed: Int get() = items.count { it.status == SourceImportStatus.FAILED }
}

@OptIn(ExperimentalSerializationApi::class)
class ImportExportViewModel(
    private val app: Application,
    private val messages: UserMessages,
    private val keystoreManager: KeystoreManager,
    private val preferencesManager: PreferencesManager,
    private val homeAppButtonPreferences: HomeAppButtonPreferences,
    private val patchSelectionRepository: PatchSelectionRepository,
    private val sourceMuteRepository: SourceMuteRepository,
    private val patchOptionsRepository: PatchOptionsRepository,
    private val selectionSetRepository: SelectionSetRepository,
    private val patchBundleRepository: PatchBundleRepository,
    private val settingsExporter: SettingsExporter,
    private val autoBackup: AutoBackup
) : ViewModel() {
    private val contentResolver = app.contentResolver
    var sourceImportReport by mutableStateOf<SourceImportReport?>(null)
        private set

    fun publishSourceImportReport(items: List<SourceImportReportItem>) {
        val report = SourceImportReport(items)
        sourceImportReport = report
        val toast = if (report.items.isNotEmpty() && report.imported == 0 && report.failed == 0) {
            app.getString(R.string.npmorphe_import_all_duplicates)
        } else {
            app.getString(R.string.npmorphe_import_report_summary, report.imported, report.duplicates, report.failed)
        }
        messages.show(toast)
    }

    fun clearSourceImportReport() { sourceImportReport = null }

    private var keystoreImportPath by mutableStateOf<Path?>(null)
    val showCredentialsDialog by derivedStateOf { keystoreImportPath != null }

    /** The key patching signs with, null until the first patch creates one. */
    var signingKey by mutableStateOf<SigningKeyInfo?>(null)
        private set

    /**
     * Parts of the manager settings a backup moves. One choice serves both directions, so what the
     * user ticked before exporting is what an import on the other device offers to bring back.
     */
    var settingsSections by mutableStateOf(SettingsSection.entries.toSet())
        private set

    fun toggleSettingsSection(section: SettingsSection) {
        settingsSections = if (section in settingsSections) settingsSections - section
        else settingsSections + section
    }

    /** Re-reads [signingKey], which patching and an import both change behind the screen's back. */
    fun refreshSigningKey() = viewModelScope.launch {
        signingKey = keystoreManager.signingKeyInfo()
    }

    fun startKeystoreImport(content: Uri) = viewModelScope.launch {
        messages.reportingFailure(R.string.settings_system_import_keystore_failed, "Failed to import keystore") {
            val path = withContext(Dispatchers.IO) {
                File.createTempFile("signing", "ks", app.cacheDir).toPath().also {
                    Files.copy(
                        contentResolver.openInputStream(content)!!,
                        it,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }

            // No credentials open a file that is no keystore, so none are asked for
            val data = withContext(Dispatchers.IO) { Files.readAllBytes(path) }
            if (ApkSigner.KeyStoreType.of(data) == null) {
                withContext(Dispatchers.IO) { Files.deleteIfExists(path) }
                throw IllegalArgumentException("Unrecognized keystore format")
            }

            for (alias in aliases) {
                for (pass in knownPasswords) {
                    if (tryKeystoreImport(alias, pass, path)) return@launch
                }
            }

            // Auto-import failed - ask the user for credentials
            keystoreImportPath = path
        }
    }

    fun cancelKeystoreImport() {
        keystoreImportPath?.deleteExisting()
        keystoreImportPath = null
    }

    suspend fun tryKeystoreImport(alias: String, pass: String): Boolean =
        tryKeystoreImport(alias, pass, keystoreImportPath!!)

    private suspend fun tryKeystoreImport(alias: String, pass: String, path: Path): Boolean {
        val imported = keystoreManager.import(alias, pass, path.toFile())
        if (imported) {
            messages.show(R.string.settings_system_import_keystore_success)
            // The copy holds the private key, and an automatic import never recorded its path
            withContext(Dispatchers.IO) { Files.deleteIfExists(path) }
            keystoreImportPath = null
            refreshSigningKey()
        }
        return imported
    }

    fun exportKeystore(target: Uri) = viewModelScope.launch {
        messages.reportingFailure(R.string.settings_system_export_keystore_failed, "Failed to export keystore") {
            withContext(Dispatchers.IO) {
                keystoreManager.export(contentResolver.openOutputStream(target)!!)
            }
            messages.show(R.string.settings_system_export_keystore_success)
        }
    }

    fun importManagerSettings(
        source: Uri,
        mode: PatchBundleRepository.ImportMode = PatchBundleRepository.ImportMode.Merge,
    ) = viewModelScope.launch {
        messages.reportingFailure(
            R.string.settings_system_import_manager_settings_fail,
            "Failed to import manager settings"
        ) {
            val exportFile = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(source)!!.use {
                    settingsJson.decodeFromStream<ManagerSettingsExportFile>(it)
                }
            }

            val sections = settingsSections
            val settings = exportFile.settings.restrictedTo(sections)
            preferencesManager.importSettings(settings)

            // The app language and the home app buttons (categories, hidden apps, sort mode, etc.)
            // live outside PreferencesManager, so they're applied here rather than in importSettings
            settings.appLanguage?.let { AppLocale.select(app, it) }
            settings.homeAppButtons?.let(homeAppButtonPreferences::importState)

            // Called whenever sources are taken, so Replace can clear existing custom sources even
            // when the backup has none, and never otherwise, so declining them leaves them alone
            if (SettingsSection.SOURCES in sections) {
                patchBundleRepository.importCustomBundles(settings.customBundles.orEmpty(), mode)
            }

            // After the sources, so selections made against a source the backup brought back
            // find it in place
            if (SettingsSection.PATCH_SELECTIONS in sections) {
                exportFile.selections?.let { importSelectionBundles(it.bundles, it.sets, mode) }
            }

            messages.show(R.string.settings_system_import_manager_settings_success)
        }
    }

    fun importManagerSettingsJson(
        rawJson: String,
        mode: PatchBundleRepository.ImportMode = PatchBundleRepository.ImportMode.Replace,
        sections: Set<SettingsSection> = SettingsSection.entries.toSet()
    ) = viewModelScope.launch {
        messages.reportingFailure(R.string.settings_system_import_manager_settings_fail, "Failed to restore NPmorphe settings") {
            applyManagerSettingsJson(rawJson, mode, sections)
            messages.show(R.string.settings_system_import_manager_settings_success)
        }
    }

    suspend fun applyManagerSettingsJson(
        rawJson: String,
        mode: PatchBundleRepository.ImportMode = PatchBundleRepository.ImportMode.Replace,
        sections: Set<SettingsSection> = SettingsSection.entries.toSet()
    ) {
        val exportFile = withContext(Dispatchers.IO) {
            settingsJson.decodeFromString<ManagerSettingsExportFile>(rawJson)
        }
        val settings = exportFile.settings.restrictedTo(sections)
        preferencesManager.importSettings(settings)
        settings.appLanguage?.let { AppLocale.select(app, it) }
        settings.homeAppButtons?.let(homeAppButtonPreferences::importState)
        if (SettingsSection.SOURCES in sections) {
            patchBundleRepository.importCustomBundles(settings.customBundles.orEmpty(), mode)
        }
        if (SettingsSection.PATCH_SELECTIONS in sections) {
            exportFile.selections?.let { importSelectionBundles(it.bundles, it.sets, mode) }
        }
    }

    fun exportManagerSettings(target: Uri) = viewModelScope.launch {
        messages.reportingFailure(
            R.string.settings_system_export_manager_settings_fail,
            "Failed to export manager settings"
        ) {
            val exportFile = settingsExporter.managerSettings(settingsSections)
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use { output ->
                    settingsJson.encodeToStream(exportFile, output)
                }
            }

            messages.show(R.string.settings_system_export_manager_settings_success)
        }
    }

    /** Exports selected remote sources and the bundle files currently installed for them. */
    fun exportSourcesArchive(uids: Set<Int>, target: Uri) = viewModelScope.launch {
        messages.reportingFailure(
            R.string.sources_archive_export_failed,
            "Failed to export sources"
        ) {
            withContext(Dispatchers.IO) {
                val sources = patchBundleRepository.exportCustomBundles(uids)
                require(sources.isNotEmpty()) { "No exportable remote sources were selected" }
                val artifacts = patchBundleRepository.exportBundleArtifacts(uids)
                contentResolver.openOutputStream(target, "wt")!!.use { output ->
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("sources.json"))
                        zip.write(
                            json.encodeToString(
                                SourceArchiveFile(
                                    sources = sources,
                                    artifacts = artifacts.map {
                                        SourceArchiveArtifact(
                                            source = it.source,
                                            path = it.path,
                                            versionSignature = it.versionSignature,
                                            signingKey = it.signingKey,
                                        )
                                    },
                                )
                            ).toByteArray(Charsets.UTF_8)
                        )
                        zip.closeEntry()

                        artifacts.forEach { artifact ->
                            zip.putNextEntry(ZipEntry(artifact.path))
                            artifact.file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            messages.show(R.string.sources_archive_export_success)
        }
    }

    /** Reads a Morphe source archive directly, without asking the user to extract it first. */
    fun importSourcesArchive(source: Uri) = viewModelScope.launch {
        var temporaryArtifacts: List<File> = emptyList()
        var archiveSources: List<BundleSnapshot> = emptyList()
        val displayName = source.displayName(contentResolver).orEmpty().lowercase(Locale.US)
        if (displayName.endsWith(".txt") || displayName.endsWith(".json") ||
            (displayName.endsWith(".gz") && !displayName.endsWith(".tar.gz"))
        ) {
            messages.reportingFailure(R.string.sources_archive_import_failed, "Failed to import sources") {
                try {
                val imported = withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(source)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_SOURCE_ARCHIVE_TOTAL_ARTIFACT_BYTES) { "Import file is too large" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("Unable to read import file")
                    val contents = if (displayName.endsWith(".gz")) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
                    val text = contents.toString(Charsets.UTF_8)
                    val snapshots = if (displayName.endsWith(".json") || text.trimStart().startsWith("{")) {
                        json.decodeFromString<SourceArchiveFile>(text).sources
                    } else {
                        text.lineSequence().map(String::trim).filter { it.startsWith("https://") || it.startsWith("http://") }
                            .distinct().mapIndexed { index, url ->
                                val parsed = Uri.parse(url)
                                val name = parsed.pathSegments.lastOrNull()?.removeSuffix(".git")?.ifBlank { null }
                                    ?: parsed.host.orEmpty()
                                BundleSnapshot(name = name, displayName = name, source = url, sortOrder = index)
                            }.toList()
                    }
                    require(snapshots.isNotEmpty()) { "No source URLs were found in the file" }
                    require(snapshots.size <= MAX_SOURCE_ARCHIVE_SOURCES) { "Too many sources in import file" }
                    snapshots
                }
                val existing = patchBundleRepository.sources.value.filterIsInstance<app.morphe.manager.domain.bundles.RemotePatchBundle>()
                    .map { normalizeSourceKey(it.endpoint) }.toSet()
                patchBundleRepository.importCustomBundles(imported, PatchBundleRepository.ImportMode.Merge)
                val after = patchBundleRepository.sources.value.filterIsInstance<app.morphe.manager.domain.bundles.RemotePatchBundle>()
                    .map { normalizeSourceKey(it.endpoint) }.toSet()
                publishSourceImportReport(imported.map { snapshot ->
                    val normalized = normalizeSourceKey(snapshot.source)
                    SourceImportReportItem(
                        name = snapshot.displayName ?: snapshot.name,
                        url = snapshot.source,
                        status = when {
                            normalized in existing -> SourceImportStatus.DUPLICATE
                            normalized in after -> SourceImportStatus.IMPORTED
                            else -> SourceImportStatus.FAILED
                        },
                        reason = "Could not add source"
                    )
                })
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    publishSourceImportReport(
                        listOf(
                            SourceImportReportItem(
                                source.displayName(contentResolver).orEmpty().ifBlank { "Source import" },
                                source.toString(),
                                SourceImportStatus.FAILED,
                                error.message ?: "Import failed"
                            )
                        )
                    )
                }
            }
            return@launch
        }
        messages.reportingFailure(
            R.string.sources_archive_import_failed,
            "Failed to import sources"
        ) {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val createdFiles = mutableListOf<File>()
                    try {
                        val tarInput = displayName.endsWith(".tar") || displayName.endsWith(".tgz") || displayName.endsWith(".tar.gz")
                        val tarZip = if (tarInput) {
                            File.createTempFile("npmorphe-source-", ".zip", app.cacheDir).also { converted ->
                                createdFiles += converted
                                convertTarToZip(source, displayName, converted)
                            }
                        } else null
                        val archiveInput = tarZip?.inputStream() ?: contentResolver.openInputStream(source)
                        val result = archiveInput?.use { input ->
                            ZipInputStream(input).use { zip ->
                                require(zip.nextEntry?.name == "sources.json") {
                                    "Source archive must begin with sources.json"
                                }
                                val manifestBytes = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                var manifestSize = 0L
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    manifestSize += count
                                    require(manifestSize <= MAX_SOURCE_ARCHIVE_MANIFEST_BYTES) {
                                        "Source archive manifest is too large"
                                    }
                                    manifestBytes.write(buffer, 0, count)
                                }
                                val archive = json.decodeFromString<SourceArchiveFile>(
                                    manifestBytes.toByteArray().toString(Charsets.UTF_8)
                                )
                                require(archive.version == 1) { "Unsupported source archive version" }
                                require(archive.sources.isNotEmpty()) { "Source archive contains no sources" }
                                require(archive.sources.size <= MAX_SOURCE_ARCHIVE_SOURCES) {
                                    "Too many sources in archive"
                                }
                                require(archive.artifacts.size <= archive.sources.size) {
                                    "Too many bundle artifacts in archive"
                                }
                                require(archive.artifacts.map { it.path }.distinct().size == archive.artifacts.size) {
                                    "Duplicate bundle artifact paths"
                                }
                                require(archive.artifacts.map { it.source.lowercase(Locale.US) }.distinct().size == archive.artifacts.size) {
                                    "Duplicate bundle artifacts for a source"
                                }
                                val sourceNames = archive.sources.map { it.source.lowercase(Locale.US) }.toSet()
                                archive.artifacts.forEach { artifact ->
                                    require(artifact.source.lowercase(Locale.US) in sourceNames) {
                                        "Bundle artifact does not match a source in this archive"
                                    }
                                    require(Regex("bundles/[A-Za-z0-9_-]+\\.jar").matches(artifact.path)) {
                                        "Invalid bundle artifact path"
                                    }
                                }

                                val expected = archive.artifacts.associateBy { it.path }
                                val imported = mutableMapOf<String, PatchBundleRepository.EmbeddedBundleArtifact>()
                                var totalPayloadBytes = 0L
                                var entry = zip.nextEntry
                                while (entry != null) {
                                    val metadata = expected[entry.name]
                                    if (metadata != null) {
                                        val file = File.createTempFile("morphe-source-", ".jar", app.cacheDir)
                                        createdFiles += file
                                        var artifactSize = 0L
                                        file.outputStream().use { output ->
                                            while (true) {
                                                val count = zip.read(buffer)
                                                if (count < 0) break
                                                artifactSize += count
                                                totalPayloadBytes += count
                                                require(artifactSize <= MAX_SOURCE_ARCHIVE_ARTIFACT_BYTES) {
                                                    "Bundle artifact is too large"
                                                }
                                                require(totalPayloadBytes <= MAX_SOURCE_ARCHIVE_TOTAL_ARTIFACT_BYTES) {
                                                    "Source archive artifacts are too large"
                                                }
                                                output.write(buffer, 0, count)
                                            }
                                        }
                                        imported[metadata.source.lowercase(Locale.US)] =
                                            PatchBundleRepository.EmbeddedBundleArtifact(
                                                file = file,
                                                versionSignature = metadata.versionSignature,
                                                signingKey = metadata.signingKey,
                                            )
                                    } else {
                                        while (true) {
                                            val count = zip.read(buffer)
                                            if (count < 0) break
                                            totalPayloadBytes += count
                                            require(totalPayloadBytes <= MAX_SOURCE_ARCHIVE_TOTAL_ARTIFACT_BYTES) {
                                                "Source archive data is too large"
                                            }
                                        }
                                    }
                                    zip.closeEntry()
                                    entry = zip.nextEntry
                                }
                                Triple(archive, imported, createdFiles.toList())
                            }
                        } ?: error("Unable to read source archive")
                        result
                    } catch (throwable: Throwable) {
                        createdFiles.forEach { it.delete() }
                        throw throwable
                    }
                }
                temporaryArtifacts = parsed.third
                archiveSources = parsed.first.sources
                val existing = patchBundleRepository.sources.value.filterIsInstance<app.morphe.manager.domain.bundles.RemotePatchBundle>()
                    .map { normalizeSourceKey(it.endpoint) }.toSet()
                patchBundleRepository.importCustomBundles(
                    parsed.first.sources,
                    PatchBundleRepository.ImportMode.Merge,
                    embeddedArtifacts = parsed.second,
                )
                val after = patchBundleRepository.sources.value.filterIsInstance<app.morphe.manager.domain.bundles.RemotePatchBundle>()
                    .map { normalizeSourceKey(it.endpoint) }.toSet()
                publishSourceImportReport(archiveSources.map { snapshot ->
                    val normalized = normalizeSourceKey(snapshot.source)
                    SourceImportReportItem(
                        name = snapshot.displayName ?: snapshot.name,
                        url = snapshot.source,
                        status = when {
                            normalized in existing -> SourceImportStatus.DUPLICATE
                            normalized in after -> SourceImportStatus.IMPORTED
                            else -> SourceImportStatus.FAILED
                        },
                        reason = "Could not add source"
                    )
                })
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val reason = error.message ?: "Import failed"
                val failedSources = archiveSources.ifEmpty {
                    listOf(BundleSnapshot(name = source.displayName(contentResolver).orEmpty().ifBlank { "Source archive" }, source = source.toString(), sortOrder = 0))
                }
                publishSourceImportReport(failedSources.map {
                    SourceImportReportItem(it.displayName ?: it.name, it.source, SourceImportStatus.FAILED, reason)
                })
            } finally {
                withContext(Dispatchers.IO) { temporaryArtifacts.forEach { it.delete() } }
            }
        }
    }

    /** Newline-separated browser-facing source URLs for the Android share sheet. */
    suspend fun createSourceShareText(uids: Set<Int>, pinInstalledVersions: Boolean): String {
        val sources = withContext(Dispatchers.IO) {
            patchBundleRepository.sourceShareUrls(uids, pinInstalledVersions)
        }
        require(sources.isNotEmpty()) { "No remote sources to share" }
        return sources.joinToString(separator = "\n")
    }

    /** Imports the encoded payload from a shared Morphe source link. */
    fun importSourceSharePayload(payload: String) = viewModelScope.launch {
        messages.reportingFailure(
            R.string.sources_archive_import_failed,
            "Failed to import shared sources"
        ) {
            try {
            val archive = withContext(Dispatchers.IO) {
                require(payload.length <= MAX_SOURCE_SHARE_PAYLOAD_CHARS) { "Source share link is too large" }
                val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                json.decodeFromString<SourceArchiveFile>(bytes.toString(Charsets.UTF_8))
            }
            require(archive.version == 1) { "Unsupported source share version" }
            require(archive.sources.isNotEmpty()) { "No sources in share link" }
            require(archive.sources.size <= MAX_SOURCE_ARCHIVE_SOURCES) { "Too many sources in share link" }
            val existing = patchBundleRepository.sources.value.filterIsInstance<app.morphe.manager.domain.bundles.RemotePatchBundle>()
                .map { normalizeSourceKey(it.endpoint) }.toSet()
            patchBundleRepository.importCustomBundles(archive.sources, PatchBundleRepository.ImportMode.Merge)
            val after = patchBundleRepository.sources.value.filterIsInstance<app.morphe.manager.domain.bundles.RemotePatchBundle>()
                .map { normalizeSourceKey(it.endpoint) }.toSet()
            publishSourceImportReport(archive.sources.map { snapshot ->
                val key = normalizeSourceKey(snapshot.source)
                SourceImportReportItem(
                    snapshot.displayName ?: snapshot.name,
                    snapshot.source,
                    when {
                        key in existing -> SourceImportStatus.DUPLICATE
                        key in after -> SourceImportStatus.IMPORTED
                        else -> SourceImportStatus.FAILED
                    },
                    "Could not add source"
                )
            })
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                publishSourceImportReport(
                listOf(SourceImportReportItem("Shared source link", "morphe://sources/import", SourceImportStatus.FAILED, error.message ?: "Import failed"))
                )
            }
        }
    }

    private fun normalizeSourceKey(url: String): String = runCatching {
        SourceUrl.normalizeBundleUrl(url)
    }.getOrDefault(url).trim().trimEnd('/').lowercase(Locale.US)

    /** Converts a plain TAR or gzip-compressed TAR into a bounded ZIP stream for the normal parser. */
    private fun convertTarToZip(source: Uri, sourceName: String, outputFile: File) {
        val raw = contentResolver.openInputStream(source) ?: error("Unable to read source archive")
        val input = if (sourceName.endsWith(".tgz") || sourceName.endsWith(".tar.gz")) GZIPInputStream(raw) else raw
        input.use { tar ->
            ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
                val header = ByteArray(TAR_BLOCK_SIZE)
                var copiedBytes = 0L
                while (readTarHeader(tar, header)) {
                    if (header.all { it == 0.toByte() }) break
                    val name = tarText(header, 0, 100)
                    val prefix = tarText(header, 345, 155)
                    val entryName = listOf(prefix, name).filter(String::isNotBlank).joinToString("/")
                    val size = tarOctal(header, 124, 12)
                    require(size >= 0L) { "Invalid TAR entry size" }
                    copiedBytes += size
                    require(copiedBytes <= MAX_SOURCE_ARCHIVE_TOTAL_ARTIFACT_BYTES) { "TAR archive is too large" }
                    val type = header[156].toInt().toChar()
                    if (type == '\u0000' || type == '0') {
                        require(entryName.isNotBlank() && !entryName.startsWith('/') && '\\' !in entryName) {
                            "Invalid TAR entry path"
                        }
                        require(entryName.split('/').none { it == ".." || it == "." }) { "Invalid TAR entry path" }
                        require(size <= MAX_SOURCE_ARCHIVE_TOTAL_ARTIFACT_BYTES) { "TAR entry is too large" }
                        zip.putNextEntry(ZipEntry(entryName))
                        copyExactly(tar, zip, size)
                        zip.closeEntry()
                    } else {
                        skipExactly(tar, size)
                    }
                    val padding = (TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)).rem(TAR_BLOCK_SIZE)
                    skipExactly(tar, padding)
                }
            }
        }
    }

    private fun readTarHeader(input: java.io.InputStream, header: ByteArray): Boolean {
        var offset = 0
        while (offset < header.size) {
            val count = input.read(header, offset, header.size - offset)
            if (count < 0) {
                require(offset == 0) { "Truncated TAR header" }
                return false
            }
            offset += count
        }
        return true
    }

    private fun tarText(header: ByteArray, offset: Int, length: Int): String =
        header.copyOfRange(offset, offset + length).toString(Charsets.UTF_8).substringBefore('\u0000').trim()

    private fun tarOctal(header: ByteArray, offset: Int, length: Int): Long {
        val value = tarText(header, offset, length).trim()
        if (value.isBlank()) return 0L
        return value.toLongOrNull(8) ?: error("Invalid TAR entry size")
    }

    private fun copyExactly(input: java.io.InputStream, output: java.io.OutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0L) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) error("Truncated TAR file")
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun skipExactly(input: java.io.InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0L) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) error("Truncated TAR file")
            remaining -= count
        }
    }

    /** Starts automatic backups into [folder], just picked by the user, see [AutoBackup.enable]. */
    fun enableAutoBackup(folder: Uri) = viewModelScope.launch {
        messages.reportingFailure(R.string.settings_system_auto_backup_folder_failed, "Failed to use the backup folder") {
            reportBackup(autoBackup.enable(folder))
        }
    }

    fun disableAutoBackup() = viewModelScope.launch { autoBackup.disable() }

    fun backUpNow() = viewModelScope.launch { reportBackup(autoBackup.backUpNow()) }

    private fun reportBackup(saved: Boolean) = messages.show(
        if (saved) R.string.settings_system_auto_backup_saved else R.string.settings_system_auto_backup_failed
    )

    fun setAutoBackupInterval(interval: ScheduleInterval) = viewModelScope.launch { autoBackup.setInterval(interval) }

    fun setAutoBackupSigningKey(include: Boolean) = viewModelScope.launch { autoBackup.setIncludesSigningKey(include) }

    /**
     * Export patch selections and options for a specific package+bundle combination.
     */
    fun exportPackageBundleData(
        packageName: String,
        bundleUid: Int,
        bundleName: String?,
        target: Uri
    ) = viewModelScope.launch {
        messages.reportingFailure(R.string.settings_system_export_source_data_fail, "Failed to export source data") {
            val (selections, optionsData) = withContext(Dispatchers.IO) {
                val patchList = patchSelectionRepository.exportForPackageAndBundle(
                    packageName,
                    bundleUid
                )

                val rawOptions = patchOptionsRepository.exportOptionsForBundle(
                    packageName = packageName,
                    bundleUid = bundleUid
                )

                val optionsData = if (rawOptions.isNotEmpty()) {
                    mapOf(packageName to rawOptions)
                } else {
                    emptyMap()
                }

                mapOf(packageName to patchList) to optionsData
            }

            val muted = withContext(Dispatchers.IO) {
                bundleUid in sourceMuteRepository.getMutedFor(packageName)
            }

            val exportFile = PatchBundleDataExportFile(
                bundleUid = bundleUid,
                bundleName = bundleName,
                bundleSource = patchBundleRepository.getEndpointForUid(bundleUid),
                exportDate = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now()),
                selections = selections,
                options = optionsData,
                mutedPackages = listOf(packageName).takeIf { muted }
            )

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use { output ->
                    json.encodeToStream(exportFile, output)
                }
            }

            messages.show(R.string.settings_system_export_source_data_success)
        }
    }

    /**
     * Export all patch selections and options across all bundles into a single file.
     */
    fun exportAllSelections(target: Uri) = viewModelScope.launch {
        messages.reportingFailure(R.string.settings_system_export_source_data_fail, "Failed to export selections") {
            val exportFile = settingsExporter.allSelections()

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use { output ->
                    json.encodeToStream(exportFile, output)
                }
            }

            messages.show(R.string.settings_system_export_source_data_success)
        }
    }

    /**
     * Filename for the all-selections export file.
     */
    fun getAllSelectionsExportFileName(): String =
        FilenameUtils.timestamped("morphe_all_selections.json")

    /**
     * Import patch selections and options from a file.
     * Supports both [PatchBundleDataExportFile] (single bundle) and
     * [AllSelectionsExportFile] (all bundles) formats, detected automatically.
     *
     * In [PatchBundleRepository.ImportMode.Merge] mode existing selections are preserved
     * and new ones are added on top. In [PatchBundleRepository.ImportMode.Replace] mode the
     * imported state fully overwrites the current state: bundles absent from the backup have
     * their selections and options cleared.
     */
    fun importAllSelections(
        source: Uri,
        mode: PatchBundleRepository.ImportMode = PatchBundleRepository.ImportMode.Merge,
    ) = viewModelScope.launch {
        messages.reportingFailure(R.string.settings_system_import_source_data_fail, "Failed to import selections") {
            val bytes = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(source)!!.use { it.readBytes() }
            }

            // Detect format by checking for "bundles" key
            val isAllSelections = withContext(Dispatchers.IO) {
                val element = json.decodeFromString<kotlinx.serialization.json.JsonObject>(bytes.decodeToString())
                element.containsKey("bundles")
            }

            // A file of one source's selections carries no sets
            val (bundleFiles, sets) = withContext(Dispatchers.IO) {
                if (isAllSelections) {
                    json.decodeFromString<AllSelectionsExportFile>(bytes.decodeToString()).let { it.bundles to it.sets }
                } else {
                    listOf(json.decodeFromString<PatchBundleDataExportFile>(bytes.decodeToString())) to null
                }
            }

            importSelectionBundles(bundleFiles, sets, mode)

            messages.show(R.string.settings_system_import_source_data_success)
        }
    }

    /**
     * Applies exported selections and [sets] onto this device's sources, the way
     * [importAllSelections] describes for [mode]. Null [sets], from a backup older than sets, leave
     * the sets on the device alone.
     */
    private suspend fun importSelectionBundles(
        bundleFiles: List<PatchBundleDataExportFile>,
        sets: List<SelectionSetExportFile>?,
        mode: PatchBundleRepository.ImportMode
    ) {
        withContext(Dispatchers.IO) {
            // Remap exported UIDs to current-device UIDs. Bundle UIDs are randomly generated
            // per device so the raw exported value only matches on the origin device
            val remapped = bundleFiles.map { file -> file to localUid(file.bundleUid, file.bundleSource) }
            val incomingUids = remapped
                .map { it.second }
                .filter { patchBundleRepository.isUidLoaded(it) }
                .toSet()

            // Replace mode: clear selections/options for bundles that currently have data
            // but are not represented in the backup
            if (mode == PatchBundleRepository.ImportMode.Replace) {
                val existingUids = (
                        patchSelectionRepository.getAllBundleUids() +
                                sourceMuteRepository.getAllBundleUids()
                        ).toSet()
                (existingUids - incomingUids).forEach { uid ->
                    patchSelectionRepository.import(uid, emptyMap())
                    patchOptionsRepository.resetOptionsForPatchBundle(uid)
                    sourceMuteRepository.resetForBundle(uid)
                }
            }

            remapped.forEach { (exportFile, bundleUid) ->
                // Skip if the bundle is not loaded - inserting with an unknown UID would
                // violate the patch_selections FK constraint on patch_bundles.uid
                if (!patchBundleRepository.isUidLoaded(bundleUid)) return@forEach

                when (mode) {
                    PatchBundleRepository.ImportMode.Replace -> {
                        // Full per-bundle replace: reset first, then load from backup
                        patchSelectionRepository.import(bundleUid, exportFile.selections)
                        patchOptionsRepository.resetOptionsForPatchBundle(bundleUid)
                        exportFile.options?.forEach { (packageName, packageOptions) ->
                            patchOptionsRepository.importOptionsForBundle(
                                packageName = packageName,
                                bundleUid = bundleUid,
                                options = packageOptions
                            )
                        }
                        sourceMuteRepository.importForBundle(
                            bundleUid,
                            exportFile.mutedPackages.orEmpty(),
                            exportFile.knownApps
                        )
                    }
                    PatchBundleRepository.ImportMode.Merge -> {
                        exportFile.selections.forEach { (packageName, patchList) ->
                            patchSelectionRepository.importForPackageAndBundle(
                                packageName = packageName,
                                bundleUid = bundleUid,
                                patches = patchList
                            )
                        }
                        exportFile.options?.forEach { (packageName, packageOptions) ->
                            patchOptionsRepository.importOptionsForBundle(
                                packageName = packageName,
                                bundleUid = bundleUid,
                                options = packageOptions
                            )
                        }
                        sourceMuteRepository.mergeForBundle(
                            bundleUid,
                            exportFile.mutedPackages.orEmpty(),
                            exportFile.knownApps
                        )
                    }
                }
            }

            if (sets != null) importSets(sets, mode)
        }
    }

    /**
     * Restores [sets], each on the sources this device has of it. In Replace mode the sets on the
     * device go first. A set none of whose sources are here is left out rather than kept empty.
     */
    private suspend fun importSets(sets: List<SelectionSetExportFile>, mode: PatchBundleRepository.ImportMode) {
        if (mode == PatchBundleRepository.ImportMode.Replace) selectionSetRepository.reset()

        sets.forEach { set ->
            // A set refers to its sources, so one this device does not have cannot be kept
            val sources = set.sources.mapNotNull { source ->
                val uid = localUid(source.bundleUid, source.bundleSource)
                if (patchBundleRepository.isUidLoaded(uid)) uid to source else null
            }
            if (sources.isEmpty() && set.sources.isNotEmpty()) return@forEach

            selectionSetRepository.import(
                StoredSelectionSet(
                    packageName = set.packageName,
                    name = set.name,
                    patches = sources.associate { (uid, source) -> uid to source.patches },
                    options = sources.mapNotNull { (uid, source) -> source.options?.let { uid to it } }.toMap()
                )
            )
        }
    }

    /** The uid on this device of a source exported as [bundleUid] from [bundleSource]. */
    private fun localUid(bundleUid: Int, bundleSource: String?): Int =
        bundleSource?.let { patchBundleRepository.resolveUidForEndpoint(it) } ?: bundleUid

    /**
     * Get filename for package+bundle data export.
     */
    fun getPackageBundleDataExportFileName(packageName: String, bundleUid: Int, bundleName: String?): String {
        val bundle = bundleName?.let { FilenameUtils.sanitize(it).take(20) }?.ifEmpty { null } ?: "bundle_$bundleUid"
        val pkg = packageName.substringAfterLast('.').take(15)
        return FilenameUtils.timestamped("morphe_${bundle}_${pkg}.json")
    }

    val debugLogFileName: String
        get() = FilenameUtils.timestamped("morphe_logcat.log")

    /**
     * Writes the debug log content to [writer]. Returns the logcat exit code.
     * Must be called from within a [Dispatchers.IO] context.
     * Shared by [exportDebugLogs].
     */
    private suspend fun writeDebugLogContent(writer: BufferedWriter): Int = withContext(Dispatchers.IO) {
        writer.write("=== Morphe Manager Debug Log ===\n")
        writer.write("Date       : ${LocalDateTime.now()}\n")
        writer.write("Version    : ${BuildConfig.VERSION_NAME}\n")
        writer.write("App mode   : ${if (Process.is64Bit()) "64-bit" else "32-bit"} (${app.processAbi() ?: "unknown ABI"})\n")

        writer.write("\n--- Device ---\n")
        writer.write("Model      : ${Build.MANUFACTURER} ${Build.MODEL}\n")
        writer.write("Android    : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        writer.write("ABI        : ${Build.SUPPORTED_ABIS.joinToString()}\n")
        writer.write("Locale     : ${Locale.getDefault().toLanguageTag()}\n")

        val stats = app.deviceStats()
        val toMb = ::bytesToMebibytes

        writer.write("\n--- Memory ---\n")
        if (stats == null) {
            writer.write("Unavailable\n")
        } else {
            writer.write("RAM avail  : ${toMb(stats.ramAvailable)} MB / ${toMb(stats.ramTotal)} MB\n")
            writer.write("Low memory : ${stats.lowMemory}\n")
            writer.write("Low mem thr: ${toMb(stats.lowMemoryThreshold)} MB\n")
        }

        writer.write("\n--- Storage ---\n")
        if (stats != null) {
            writer.write("Internal   : ${formatBytesForReport(stats.storageAvailable)} free / ${formatBytesForReport(stats.storageTotal)} total\n")
        }
        var sdCardIndex = 1
        app.externalStorageVolumes().forEach { (isPrimary, root) ->
            if (isPrimary) {
                writer.write("External   : ${formatBytesForReport(root.freeSpace)} free / ${formatBytesForReport(root.totalSpace)} total\n")
            } else {
                writer.write("SD Card $sdCardIndex  : ${formatBytesForReport(root.freeSpace)} free / ${formatBytesForReport(root.totalSpace)} total\n")
                sdCardIndex++
            }
        }

        writer.write("\n--- Environment ---\n")
        // Read without asking, as su would raise the root prompt and wait for it unanswered
        writer.write("Root access: ${Shell.isAppGrantedRoot() ?: "not checked"}\n")

        // A process the system killed logs nothing of its own end, so the system's record is the only trace
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            writer.write("\n--- Process exits ---\n")
            val exits = app.processExits(DEBUG_LOG_PROCESS_EXITS)
            if (exits.isEmpty()) writer.write("None recorded\n")
            exits.forEach { exit ->
                val time = Instant.ofEpochMilli(exit.timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime()
                writer.write("$time ${exit.processName} (pid ${exit.pid}): ${exit.describe()}\n")
            }
        }

        writer.write("\n=== Logcat ===\n\n")

        // Filter logs by current process UID to include only Morphe Manager logs. Logcat before
        // Android 11 has no such filter and refuses the whole call, but shows an app only its own logs anyway
        val uidFilter = "--uid=${app.applicationInfo.uid}".takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.R }
        val logcat = ProcessBuilder(listOfNotNull("logcat", "-d", uidFilter))
            .redirectErrorStream(true)
            .start()
        logcat.inputStream.bufferedReader().useLines { lines -> lines.forEach { writer.write("$it\n") } }
        logcat.waitFor()
    }

    fun exportDebugLogs(target: Uri) = viewModelScope.launch {
        val exitCode = try {
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target)!!.bufferedWriter().use { writer ->
                    writeDebugLogContent(writer)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Got exception while exporting logs", e)
            messages.show(R.string.settings_system_export_debug_logs_export_failed)
            return@launch
        }

        if (exitCode == 0)
            messages.show(R.string.settings_system_export_debug_logs_export_success)
        else {
            messages.show(app.getString(R.string.settings_system_export_debug_logs_export_read_failed).format(exitCode))
        }
    }

    override fun onCleared() {
        cancelKeystoreImport()
    }

    companion object {
        private val knownPasswords = arrayOf("Morphe", "s3cur3p@ssw0rd")
        private val aliases = arrayOf(KeystoreManager.DEFAULT, "alias", "Morphe Key")
        private const val MAX_SOURCE_ARCHIVE_MANIFEST_BYTES = 4L * 1024 * 1024
        private const val MAX_SOURCE_ARCHIVE_SOURCES = 500
        private const val MAX_SOURCE_ARCHIVE_ARTIFACT_BYTES = 128L * 1024 * 1024
        private const val MAX_SOURCE_ARCHIVE_TOTAL_ARTIFACT_BYTES = 256L * 1024 * 1024
        private const val MAX_SOURCE_SHARE_PAYLOAD_CHARS = 48 * 1024
        private const val TAR_BLOCK_SIZE = 512

        // Enough for the end of a run and the restart that followed it, with the run before for comparison
        private const val DEBUG_LOG_PROCESS_EXITS = 6
    }
}
