package app.morphe.manager.domain.backup

import android.app.Application
import android.net.Uri
import app.morphe.manager.domain.manager.SettingsSection
import app.morphe.manager.domain.repository.BrowserDataRepository
import app.morphe.manager.domain.repository.PatchHistoryEntry
import app.morphe.manager.domain.repository.PatchHistoryRepository
import app.morphe.manager.ui.viewmodel.ManagerSettingsExportFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

data class NpmorpheBackupContents(
    val managerSettingsJson: String,
    val browserDataJson: String,
    val patchHistoryJson: String,
    val sourceCount: Int,
    val historyCount: Int
)

/** Portable NPmorphe settings bundle; private keystore material is never included. */
class NpmorpheBackupManager(
    private val app: Application,
    private val settingsExporter: SettingsExporter,
    private val browserData: BrowserDataRepository,
    private val patchHistory: PatchHistoryRepository
) {
    suspend fun export(target: Uri): String = withContext(Dispatchers.IO) {
        val settings = settingsExporter.managerSettings(SettingsSection.entries.toSet())
        val settingsText = SettingsExporter.settingsJson.encodeToString(settings)
        val browserText = browserData.exportJson()
        val historyText = patchHistory.exportJson()
        val output = app.contentResolver.openOutputStream(target, "wt") ?: error("Unable to create backup file")
        output.use { stream ->
            ZipOutputStream(stream).use { zip ->
                putEntry(zip, "manager-settings.json", settingsText)
                putEntry(zip, "browser-data.json", browserText)
                putEntry(zip, "patch-history.json", historyText)
            }
        }
        defaultBackupFileName()
    }

    suspend fun read(source: Uri): NpmorpheBackupContents = withContext(Dispatchers.IO) {
        val entries = linkedMapOf<String, String>()
        var total = 0L
        val input = app.contentResolver.openInputStream(source) ?: error("Unable to read backup")
        input.use { raw -> ZipInputStream(raw).use { zip ->
            var entry = zip.nextEntry
            val buffer = ByteArray(8192)
            while (entry != null) {
                require(!entry.isDirectory) { "Backup archive contains an unsupported directory entry" }
                require(entry.name in ALLOWED_ENTRIES) { "Backup archive contains an unsupported file" }
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_UNCOMPRESSED_BYTES) { "Backup is too large" }
                    bytes.write(buffer, 0, count)
                }
                entries[entry.name] = bytes.toString(Charsets.UTF_8.name())
                zip.closeEntry()
                entry = zip.nextEntry
            }
        } }
        val settingsText = entries["manager-settings.json"] ?: error("Backup is missing manager settings")
        val browserText = entries["browser-data.json"] ?: "{}"
        val historyText = entries["patch-history.json"] ?: "[]"
        val settings = SettingsExporter.settingsJson.decodeFromString<ManagerSettingsExportFile>(settingsText)
        JSONObject(browserText)
        val history = SettingsExporter.settingsJson.decodeFromString<List<PatchHistoryEntry>>(historyText)
        NpmorpheBackupContents(settingsText, browserText, historyText, settings.settings.customBundles?.size ?: 0, history.size)
    }

    private fun putEntry(zip: ZipOutputStream, name: String, contents: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(contents.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    companion object {
        fun defaultBackupFileName(): String =
            "NPmorphe_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("ddMMyyHHmmss"))}.np"

        const val MAX_UNCOMPRESSED_BYTES = 96L * 1024L * 1024L
        val ALLOWED_ENTRIES = setOf("manager-settings.json", "browser-data.json", "patch-history.json")
    }
}
