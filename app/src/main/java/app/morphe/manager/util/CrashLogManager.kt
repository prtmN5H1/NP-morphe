package app.morphe.manager.util

import android.content.Context
import android.os.Build
import android.os.Process
import app.morphe.manager.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/** Small local crash recorder; it never uploads diagnostics. */
object CrashLogManager {
    private const val LOG_FILE_NAME = "npmorphe_crash_logs.txt"
    private const val MAX_RECENT_LINES = 200
    private val lock = Any()
    private val recentLines = ArrayDeque<String>()
    @Volatile private var logFile: File? = null
    @Volatile private var installed = false

    fun install(context: Context) {
        synchronized(lock) {
            if (installed) return
            logFile = File(context.applicationContext.filesDir, LOG_FILE_NAME)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching { saveCrash(thread, throwable) }
                if (previous != null) previous.uncaughtException(thread, throwable)
                else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
            installed = true
        }
    }

    /** Adds a short line to the bounded in-memory context attached to the next crash report. */
    fun record(message: String) {
        val line = "${timestamp()} $message"
        synchronized(lock) {
            recentLines.addLast(line)
            while (recentLines.size > MAX_RECENT_LINES) recentLines.removeFirst()
        }
    }

    fun read(context: Context): String = runCatching {
        File(context.applicationContext.filesDir, LOG_FILE_NAME).takeIf { it.isFile }?.readText().orEmpty()
    }.getOrDefault("")

    fun clear(context: Context) {
        synchronized(lock) {
            recentLines.clear()
            runCatching { File(context.applicationContext.filesDir, LOG_FILE_NAME).delete() }
        }
    }

    private fun saveCrash(thread: Thread, throwable: Throwable) {
        val stackTrace = StringWriter().also { writer -> throwable.printStackTrace(PrintWriter(writer)) }.toString()
        val report = buildString {
            appendLine("=== NPmorphe crash ===")
            appendLine("Timestamp: ${timestamp()}")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android API: ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine("Recent app logs:")
            synchronized(lock) { recentLines.forEach { appendLine(it) } }
            appendLine("Stack trace:")
            appendLine(stackTrace)
            appendLine()
        }
        synchronized(lock) {
            logFile?.let { file ->
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).use { stream ->
                    stream.write(report.toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
            }
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
}
