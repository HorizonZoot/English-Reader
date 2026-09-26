package io.github.zoot.englishreader.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.zoot.englishreader.R
import io.github.zoot.englishreader.di.UpdateIo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import javax.inject.Inject

class AndroidUpdateDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
    @UpdateIo private val ioDispatcher: CoroutineDispatcher
) : UpdateDownloadBackend {
    private val manager: DownloadManager
        get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: throw UpdateDownloadException(UpdateDownloadFailure.UNAVAILABLE)

    override suspend fun enqueue(tag: String): Long = withContext(ioDispatcher) {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw UpdateDownloadException(UpdateDownloadFailure.STORAGE)
        ensureDirectory(File(directory, "updates"))
        val request = DownloadManager.Request(Uri.parse(UpdateReleasePolicy.downloadUrl(tag)))
            .setTitle(context.getString(R.string.update_download_notification_title, UpdateReleasePolicy.versionName(tag)))
            .setMimeType(APK_MIME_TYPE)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS,
                "updates/${UUID.randomUUID()}-${UpdateReleasePolicy.fileName(tag)}"
            )
        manager.enqueue(request).also { if (it <= 0) throw IOException("Unable to enqueue update") }
    }

    override suspend fun status(id: Long): PlatformUpdateDownload = withContext(ioDispatcher) {
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) {
                return@withContext PlatformUpdateDownload.Failed(UpdateDownloadFailure.UNAVAILABLE)
            }
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> PlatformUpdateDownload.Complete
                DownloadManager.STATUS_FAILED -> {
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    PlatformUpdateDownload.Failed(when (reason) {
                        DownloadManager.ERROR_INSUFFICIENT_SPACE, DownloadManager.ERROR_FILE_ERROR,
                        DownloadManager.ERROR_DEVICE_NOT_FOUND -> UpdateDownloadFailure.STORAGE
                        else -> UpdateDownloadFailure.NETWORK
                    })
                }
                else -> PlatformUpdateDownload.Progress(
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_RUNNING
                )
            }
        }
    }

    override suspend fun preparedFile(record: PendingUpdateDownload): File? = withContext(ioDispatcher) {
        targetFile(record).takeIf { it.isFile }?.also { validate(it, record.tag) }
    }

    override suspend fun prepare(record: PendingUpdateDownload): File = withContext(ioDispatcher) {
        val target = targetFile(record)
        if (target.isFile) {
            validate(target, record.tag)
            return@withContext target
        }
        if (status(record.id) != PlatformUpdateDownload.Complete) {
            throw UpdateDownloadException(UpdateDownloadFailure.UNAVAILABLE)
        }
        val temp = File.createTempFile("download-", ".apk", target.parentFile)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(record.id)).use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > UpdateReleasePolicy.MAX_APK_BYTES) {
                            throw UpdateDownloadException(UpdateDownloadFailure.INVALID_PACKAGE)
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            validate(temp, record.tag)
            currentCoroutineContext().ensureActive()
            if (!temp.renameTo(target)) throw IOException("Unable to retain update package")
            manager.remove(record.id)
            target
        } finally {
            if (temp.exists() && !temp.delete()) Log.w(TAG, "Update temporary file cleanup failed")
        }
    }

    override suspend fun remove(record: PendingUpdateDownload) = withContext(ioDispatcher) {
        manager.remove(record.id)
        val target = targetFile(record)
        if (target.exists() && !target.delete()) throw IOException("Unable to remove update package")
    }

    private fun targetFile(record: PendingUpdateDownload): File {
        require(record.id > 0)
        val directory = File(context.cacheDir, "updates")
        ensureDirectory(directory)
        return File(directory, "${record.id}-${UpdateReleasePolicy.fileName(record.tag)}")
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Update directory unavailable")
    }

    @Suppress("DEPRECATION")
    private fun validate(file: File, tag: String) {
        if (file.length() !in 1..UpdateReleasePolicy.MAX_APK_BYTES) {
            throw UpdateDownloadException(UpdateDownloadFailure.INVALID_PACKAGE)
        }
        val supportedAbis = try {
            ZipFile(file).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") == null) {
                    throw UpdateDownloadException(UpdateDownloadFailure.INVALID_PACKAGE)
                }
                zip.entries().asSequence().map { it.name }
                    .filter { it.startsWith("lib/") && it.endsWith(".so") }
                    .map { it.substringAfter("lib/").substringBefore('/') }.toSet()
            }
        } catch (_: IOException) {
            throw UpdateDownloadException(UpdateDownloadFailure.INVALID_PACKAGE)
        }
        if (supportedAbis.isNotEmpty() && supportedAbis.none { it in Build.SUPPORTED_ABIS }) {
            throw UpdateDownloadException(UpdateDownloadFailure.UNSUPPORTED_DEVICE)
        }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateDownloadException(UpdateDownloadFailure.INVALID_PACKAGE)
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        if (archive.packageName != context.packageName) {
            throw UpdateDownloadException(UpdateDownloadFailure.INVALID_PACKAGE)
        }
        if (archive.versionName != UpdateReleasePolicy.versionName(tag) || archiveCode <= installedCode) {
            throw UpdateDownloadException(UpdateDownloadFailure.WRONG_VERSION)
        }
        if ((archive.applicationInfo?.minSdkVersion ?: 0) > Build.VERSION.SDK_INT) {
            throw UpdateDownloadException(UpdateDownloadFailure.UNSUPPORTED_DEVICE)
        }
        val expected = certificateDigests(installed)
        if (expected.isEmpty() || certificateDigests(archive) != expected) {
            throw UpdateDownloadException(UpdateDownloadFailure.WRONG_SIGNATURE)
        }
    }

    @Suppress("DEPRECATION")
    private fun certificateDigests(info: PackageInfo): Set<List<Byte>> {
        val certificates = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else info.signatures.orEmpty()
        return certificates.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toList() }.toSet()
    }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val TAG = "UpdateDownload"
    }
}
