package io.github.zoot.englishreader.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.zoot.englishreader.data.update.AndroidUpdateDownloads
import java.io.File
import java.io.IOException

enum class UpdateInstallLaunchResult { STARTED, PERMISSION_REQUIRED, UNAVAILABLE }

fun openUpdateInstaller(context: Context, file: File): UpdateInstallLaunchResult {
    return try {
        if (!file.isFile || file.canonicalFile.parentFile != File(context.cacheDir, "updates").canonicalFile) {
            return UpdateInstallLaunchResult.UNAVAILABLE
        }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            UpdateInstallLaunchResult.PERMISSION_REQUIRED
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-files", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, AndroidUpdateDownloads.APK_MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            UpdateInstallLaunchResult.STARTED
        }
    } catch (_: ActivityNotFoundException) {
        UpdateInstallLaunchResult.UNAVAILABLE
    } catch (_: SecurityException) {
        UpdateInstallLaunchResult.UNAVAILABLE
    } catch (_: IllegalArgumentException) {
        UpdateInstallLaunchResult.UNAVAILABLE
    } catch (_: IOException) {
        UpdateInstallLaunchResult.UNAVAILABLE
    }
}
