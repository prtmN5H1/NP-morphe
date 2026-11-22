/*
 * Copyright 2026 Morphe.
 */

package app.morphe.manager.ui.screen.home

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.morphe.manager.ui.screen.shared.DialogHostScope
import java.io.File

/** Launches the full-window browser Activity and forwards a completed APK download to its caller. */
@Composable
internal fun DialogHostScope.ApkDownloadBrowserDialog(
    initialUrl: String,
    appName: String? = null,
    packageName: String? = null,
    requestedVersion: String? = null,
    isStandalone: Boolean = false,
    onDismiss: () -> Unit,
    onApkDownloaded: (uri: Uri, url: String, downloadId: Long, permanent: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnApkDownloaded by rememberUpdatedState(onApkDownloaded)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val filePath = data?.getStringExtra(InAppBrowserActivity.EXTRA_RESULT_FILE_PATH)
            val uri = filePath?.let { Uri.fromFile(File(it)) }
                ?: data?.getStringExtra(InAppBrowserActivity.EXTRA_RESULT_URI)?.let(Uri::parse)
            val url = data?.getStringExtra(InAppBrowserActivity.EXTRA_RESULT_URL)
            if (uri != null && !url.isNullOrBlank()) {
                currentOnApkDownloaded(
                    uri,
                    url,
                    data.getLongExtra(InAppBrowserActivity.EXTRA_RESULT_DOWNLOAD_ID, -1L),
                    data.getBooleanExtra(InAppBrowserActivity.EXTRA_RESULT_PERMANENT, false)
                )
            }
        }
        currentOnDismiss()
    }
    var launched by remember(initialUrl, appName, packageName, requestedVersion, isStandalone) {
        mutableStateOf(false)
    }
    LaunchedEffect(initialUrl, appName, packageName, requestedVersion, isStandalone) {
        if (!launched) {
            launched = true
            launcher.launch(
                InAppBrowserActivity.intent(
                    context = context,
                    initialUrl = initialUrl,
                    packageName = packageName,
                    appName = appName,
                    requestedVersion = requestedVersion,
                    isStandalone = isStandalone
                )
            )
        }
    }
}
