package app.morphe.manager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.morphe.manager.R
import app.morphe.manager.domain.repository.ApkDownloadManager
import app.morphe.manager.domain.repository.ApkDownloadRecord
import app.morphe.manager.domain.repository.ApkDownloadStatus
import app.morphe.manager.ui.screen.home.DownloadedApksActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Locale
import java.util.UUID

/** Keeps private-vault downloads alive when the browser Activity is closed or backgrounded. */
class ApkDownloadService : Service() {
    private val manager: ApkDownloadManager by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var receivedStart = false
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
        serviceScope.launch {
            manager.downloads.collectLatest { records ->
                val active = records.filter { it.status == ApkDownloadStatus.QUEUED || it.status == ApkDownloadStatus.DOWNLOADING }
                if (active.isNotEmpty()) {
                    updateNotification(active.first())
                } else if (receivedStart) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(latestStartId)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        receivedStart = true
        latestStartId = startId
        startForegroundNow(null)
        when (intent?.action) {
            ACTION_ENQUEUE -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isNotBlank()) manager.enqueue(
                    url = url,
                    fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty(),
                    packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME),
                    versionName = intent.getStringExtra(EXTRA_VERSION_NAME),
                    userAgent = intent.getStringExtra(EXTRA_USER_AGENT).orEmpty().ifBlank { DEFAULT_USER_AGENT },
                    cookie = intent.getStringExtra(EXTRA_COOKIE),
                    referer = intent.getStringExtra(EXTRA_REFERER),
                    id = intent.getStringExtra(EXTRA_ID) ?: UUID.randomUUID().toString()
                )
            }
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let(manager::pause)
            ACTION_RESUME -> intent.getStringExtra(EXTRA_ID)?.let(manager::resume)
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let(manager::cancel)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun updateNotification(record: ApkDownloadRecord) = startForegroundNow(record)

    private fun startForegroundNow(record: ApkDownloadRecord?) {
        val notification = buildNotification(record)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(record: ApkDownloadRecord?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, DownloadedApksActivity.intent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_default)
            .setContentTitle(record?.fileName ?: getString(R.string.npmorphe_downloads_title))
            .setContentIntent(open)
            .setOngoing(record != null)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (record == null) {
            builder.setContentText(getString(R.string.npmorphe_downloads_preparing)).setProgress(0, 0, true)
        } else {
            val known = record.totalBytes > 0L
            val progress = if (known) ((record.downloadedBytes * 100L) / record.totalBytes).toInt().coerceIn(0, 100) else 0
            val speed = record.bytesPerSecond.takeIf { it > 0L }?.let { " · ${formatBytes(it)}/s" }.orEmpty()
            builder.setContentText("${formatBytes(record.downloadedBytes)}${if (known) " / ${formatBytes(record.totalBytes)} · $progress%" else ""}$speed")
                .setProgress(100, progress, !known)
        }
        return builder.build()
    }

    private fun formatBytes(bytes: Long) = String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.npmorphe_downloads_title), NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL_ID = "npmorphe_apk_downloads"
        private const val NOTIFICATION_ID = 7812
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val ACTION_ENQUEUE = "app.np.morphe.download.ENQUEUE"
        private const val ACTION_PAUSE = "app.np.morphe.download.PAUSE"
        private const val ACTION_RESUME = "app.np.morphe.download.RESUME"
        private const val ACTION_CANCEL = "app.np.morphe.download.CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_PACKAGE_NAME = "package_name"
        private const val EXTRA_VERSION_NAME = "version_name"
        private const val EXTRA_USER_AGENT = "user_agent"
        private const val EXTRA_COOKIE = "cookie"
        private const val EXTRA_REFERER = "referer"
        private const val EXTRA_ID = "id"

        fun enqueue(context: Context, url: String, fileName: String, packageName: String?, versionName: String?, userAgent: String, cookie: String?, referer: String?): String {
            val id = UUID.randomUUID().toString()
            start(context, Intent(context, ApkDownloadService::class.java).setAction(ACTION_ENQUEUE)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_FILE_NAME, fileName)
                .putExtra(EXTRA_PACKAGE_NAME, packageName).putExtra(EXTRA_VERSION_NAME, versionName)
                .putExtra(EXTRA_USER_AGENT, userAgent).putExtra(EXTRA_COOKIE, cookie).putExtra(EXTRA_REFERER, referer).putExtra(EXTRA_ID, id))
            return id
        }

        fun control(context: Context, id: String, action: String) = start(context,
            Intent(context, ApkDownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id))

        fun pause(context: Context, id: String) = control(context, id, ACTION_PAUSE)
        fun resume(context: Context, id: String) = control(context, id, ACTION_RESUME)
        fun cancel(context: Context, id: String) = control(context, id, ACTION_CANCEL)

        private fun start(context: Context, intent: Intent) {
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
