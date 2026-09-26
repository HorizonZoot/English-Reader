package io.github.zoot.englishreader.data.update

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.database.MatrixCursor
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import io.github.zoot.englishreader.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidUpdateDownloadsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val manager = mockk<DownloadManager>(relaxed = true)
    private val packages = mockk<PackageManager>()
    private val record = PendingUpdateDownload(42, "v0.1.4-beta")
    private val packageName = "io.github.zoot.englishreader"
    private lateinit var cached: File
    private lateinit var archive: PackageInfo
    private lateinit var backend: AndroidUpdateDownloads

    @Before
    fun setUp() {
        every { context.cacheDir } returns temporary.root
        every { context.packageName } returns packageName
        every { context.packageManager } returns packages
        every { context.getSystemService(Context.DOWNLOAD_SERVICE) } returns manager
        every { context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) } returns temporary.newFolder("external")
        every { context.getString(R.string.update_download_notification_title, any()) } returns "English Reader update"
        val installed = packageInfo(4, "0.1.3-beta", 1)
        archive = packageInfo(5, "0.1.4-beta", 1)
        every { packages.getPackageInfo(packageName, any<Int>()) } returns installed
        every { packages.getPackageArchiveInfo(any(), any<Int>()) } answers { archive }
        cached = File(temporary.newFolder("updates"), "42-EnglishReader-0.1.4-beta.apk")
        writeApk(cached)
        backend = AndroidUpdateDownloads(context, Dispatchers.Unconfined)
    }

    @Test
    fun preparedPackage_matchingIdentityVersionAndCertificate_isAccepted() = runTest {
        assertEquals(cached, backend.preparedFile(record))
    }

    @Test
    @Config(sdk = [27])
    fun preparedPackage_legacySignatureApi_isAlsoChecked() = runTest {
        assertEquals(cached, backend.preparedFile(record))
        archive = packageInfo(5, "0.1.4-beta", 2)
        assertRejected(UpdateDownloadFailure.WRONG_SIGNATURE)
    }

    @Test
    fun preparedPackage_otherApp_isRejected() = runTest {
        archive.packageName = "other.application"
        assertRejected(UpdateDownloadFailure.INVALID_PACKAGE)
    }

    @Test
    fun preparedPackage_otherSignature_isRejected() = runTest {
        archive = packageInfo(5, "0.1.4-beta", 2)
        assertRejected(UpdateDownloadFailure.WRONG_SIGNATURE)
    }

    @Test
    fun preparedPackage_wrongVersionOrDowngrade_isRejected() = runTest {
        archive = packageInfo(4, "0.1.4-beta", 1)
        assertRejected(UpdateDownloadFailure.WRONG_VERSION)
        archive = packageInfo(5, "0.1.5-beta", 1)
        assertRejected(UpdateDownloadFailure.WRONG_VERSION)
    }

    @Test
    fun preparedPackage_unsupportedAndroidOrAbi_isRejected() = runTest {
        archive.applicationInfo!!.minSdkVersion = 100
        assertRejected(UpdateDownloadFailure.UNSUPPORTED_DEVICE)
        archive.applicationInfo!!.minSdkVersion = 24
        writeApk(cached, "lib/unsupported-abi/libtest.so")
        assertRejected(UpdateDownloadFailure.UNSUPPORTED_DEVICE)
    }

    @Test
    fun preparedPackage_truncatedArchive_isRejected() = runTest {
        cached.writeText("truncated APK")
        assertRejected(UpdateDownloadFailure.INVALID_PACKAGE)
    }

    @Test
    fun completedDownload_isCopiedIntoPrivateCacheBeforeExternalFileIsRemoved() = runTest {
        assertTrue(cached.delete())
        val downloaded = temporary.newFile("download.apk")
        writeApk(downloaded)
        every { manager.query(any()) } answers {
            MatrixCursor(arrayOf(DownloadManager.COLUMN_STATUS)).apply { addRow(arrayOf(DownloadManager.STATUS_SUCCESSFUL)) }
        }
        every { manager.openDownloadedFile(42) } answers {
            ParcelFileDescriptor.open(downloaded, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        assertEquals(cached, backend.prepare(record))
        assertArrayEquals(downloaded.readBytes(), cached.readBytes())
        assertEquals(listOf(cached), cached.parentFile!!.listFiles()!!.toList())
        verify(exactly = 1) { manager.remove(42) }
    }

    @Test
    fun failedValidation_cleansTemporaryCopyAndNeverPublishesReadyFile() = runTest {
        assertTrue(cached.delete())
        val downloaded = temporary.newFile("download.apk")
        writeApk(downloaded)
        archive = packageInfo(5, "0.1.4-beta", 2)
        every { manager.query(any()) } answers {
            MatrixCursor(arrayOf(DownloadManager.COLUMN_STATUS)).apply { addRow(arrayOf(DownloadManager.STATUS_SUCCESSFUL)) }
        }
        every { manager.openDownloadedFile(42) } answers {
            ParcelFileDescriptor.open(downloaded, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        try {
            backend.prepare(record)
            fail("Untrusted file must be rejected")
        } catch (error: UpdateDownloadException) {
            assertEquals(UpdateDownloadFailure.WRONG_SIGNATURE, error.reason)
        }
        assertTrue(cached.parentFile!!.listFiles()!!.isEmpty())
        verify(exactly = 0) { manager.remove(any()) }
    }

    @Test
    fun enqueue_usesVersionPinnedOfficialApkUrl() = runTest {
        val request = slot<DownloadManager.Request>()
        every { manager.enqueue(capture(request)) } returns 43
        assertEquals(43L, backend.enqueue(record.tag))
        assertEquals(
            "https://github.com/HorizonZoot/English-Reader/releases/download/v0.1.4-beta/EnglishReader-0.1.4-beta.apk",
            shadowOf(request.captured).uri.toString()
        )
    }

    private suspend fun assertRejected(reason: UpdateDownloadFailure) {
        try {
            backend.preparedFile(record)
            fail("Expected $reason")
        } catch (error: UpdateDownloadException) {
            assertEquals(reason, error.reason)
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(code: Int, name: String, signer: Int): PackageInfo = PackageInfo().apply {
        packageName = this@AndroidUpdateDownloadsTest.packageName
        versionCode = code
        versionName = name
        applicationInfo = ApplicationInfo().apply { minSdkVersion = 24 }
        val certificates = arrayOf(Signature(byteArrayOf(signer.toByte())))
        if (Build.VERSION.SDK_INT >= 28) {
            signingInfo = mockk<SigningInfo> { every { apkContentsSigners } returns certificates }
        } else signatures = certificates
    }

    private fun writeApk(file: File, nativeLibrary: String? = null) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("test manifest".toByteArray())
            zip.closeEntry()
            if (nativeLibrary != null) {
                zip.putNextEntry(ZipEntry(nativeLibrary))
                zip.closeEntry()
            }
        }
    }
}
