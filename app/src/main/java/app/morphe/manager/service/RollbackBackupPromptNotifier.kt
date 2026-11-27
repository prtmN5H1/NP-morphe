package app.morphe.manager.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.morphe.manager.MainActivity
import app.morphe.manager.R
import app.morphe.manager.domain.repository.RollbackBackupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

object RollbackBackupPromptNotifier {
    private const val CHANNEL_ID = "npmorphe_rollback_cleanup"
    private const val ACTION_DELETE = "app.np.morphe.rollback.DELETE_BACKUP"
    private const val ACTION_KEEP = "app.np.morphe.rollback.KEEP_BACKUP"
    private const val EXTRA_PACKAGE = "package_name"

    fun show(context: Context, packageName: String, appName: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.npmorphe_rollback_notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val requestCode = notificationId(packageName)
        val deleteIntent = actionIntent(context, ACTION_DELETE, packageName, requestCode)
        val keepIntent = actionIntent(context, ACTION_KEEP, packageName, requestCode + 1)
        val openIntent = PendingIntent.getActivity(
            context,
            requestCode + 2,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_default)
            .setContentTitle(context.getString(R.string.npmorphe_rollback_prompt_title))
            .setContentText(context.getString(R.string.npmorphe_rollback_prompt_message, appName))
            .setContentIntent(openIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.npmorphe_rollback_delete_backup), deleteIntent)
            .addAction(0, context.getString(R.string.npmorphe_rollback_keep_backup), keepIntent)
            .build()
        manager.notify(requestCode, notification)
    }

    fun cancel(context: Context, packageName: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(packageName))
    }

    internal fun action(intent: Intent): String? = intent.action
    internal fun packageName(intent: Intent): String? = intent.getStringExtra(EXTRA_PACKAGE)
    internal const val DELETE = ACTION_DELETE
    internal const val KEEP = ACTION_KEEP

    private fun actionIntent(context: Context, action: String, packageName: String, requestCode: Int) =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, RollbackBackupPromptActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_PACKAGE, packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun notificationId(packageName: String) = packageName.hashCode().and(Int.MAX_VALUE).coerceAtLeast(1)
}

class RollbackBackupPromptActionReceiver : BroadcastReceiver() {
    private val backups: RollbackBackupRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = RollbackBackupPromptNotifier.packageName(intent) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (RollbackBackupPromptNotifier.action(intent)) {
                    RollbackBackupPromptNotifier.DELETE -> backups.delete(packageName)
                    RollbackBackupPromptNotifier.KEEP -> backups.keepBackup(packageName)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
