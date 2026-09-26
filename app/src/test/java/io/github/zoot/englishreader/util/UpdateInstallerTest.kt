package io.github.zoot.englishreader.util

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateInstallerTest {
    private lateinit var packages: PackageManager
    private lateinit var context: ContextWrapper
    private lateinit var file: File
    private val intents = mutableListOf<Intent>()
    private var allowed = false
    private var unavailable = false

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        packages = spyk(application.packageManager)
        if (Build.VERSION.SDK_INT >= 26) every { packages.canRequestPackageInstalls() } answers { allowed }
        context = object : ContextWrapper(application) {
            override fun getPackageManager(): PackageManager = packages
            override fun startActivity(intent: Intent) {
                if (unavailable) throw ActivityNotFoundException()
                intents += intent
            }
        }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        file = File(directory, "test-${UUID.randomUUID()}.apk").apply { writeText("validated by download layer") }
    }

    @After
    fun cleanUp() {
        file.delete()
    }

    @Test
    fun missingPermission_onlyOpensThisApplicationsInstallSourceSettings() {
        assertEquals(UpdateInstallLaunchResult.PERMISSION_REQUIRED, openUpdateInstaller(context, file))
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intents.single().action)
        assertEquals("package:${context.packageName}", intents.single().dataString)
        assertNull(intents.single().type)
    }

    @Test
    fun permittedInstall_onlySharesReadOnlyContentUriForThePrivateUpdateFile() {
        allowed = true
        assertEquals(UpdateInstallLaunchResult.STARTED, openUpdateInstaller(context, file))
        val intent = intents.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.update-files", intent.data!!.authority)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun filesOutsideUpdateDirectory_andMissingFiles_doNotLaunchAnything() {
        val outside = File(context.cacheDir, "unrelated-${UUID.randomUUID()}.apk").apply { writeText("other file") }
        try {
            assertEquals(UpdateInstallLaunchResult.UNAVAILABLE, openUpdateInstaller(context, outside))
            assertTrue(file.delete())
            assertEquals(UpdateInstallLaunchResult.UNAVAILABLE, openUpdateInstaller(context, file))
            assertTrue(intents.isEmpty())
            verify(exactly = 0) { packages.canRequestPackageInstalls() }
        } finally {
            outside.delete()
        }
    }

    @Test
    fun unavailableSystemHandler_returnsFailureInsteadOfCrashing() {
        unavailable = true
        assertEquals(UpdateInstallLaunchResult.UNAVAILABLE, openUpdateInstaller(context, file))
        assertTrue(intents.isEmpty())
    }

    @Test
    @Config(sdk = [25])
    fun preOreo_doesNotRequestTheUnsupportedPerSourcePermission() {
        assertEquals(UpdateInstallLaunchResult.STARTED, openUpdateInstaller(context, file))
        assertEquals(Intent.ACTION_VIEW, intents.single().action)
    }
}
