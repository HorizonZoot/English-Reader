package io.github.zoot.englishreader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReleasePageTest {
    private val url = "https://github.com/HorizonZoot/English-Reader/releases/tag/v0.2.0"

    @Test
    fun openReleasePage_validPage_dispatchesViewIntentAndHandlesMissingBrowser() {
        val intent = slot<Intent>()
        val context = mockk<Context>()
        every { context.startActivity(capture(intent)) } returns Unit
        assertTrue(openReleasePage(context, url))
        assertEquals(Intent.ACTION_VIEW, intent.captured.action)
        assertEquals(url, intent.captured.dataString)
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            every { context.startActivity(any()) } throws failure
            assertFalse(openReleasePage(context, url))
        }
    }

    @Test
    fun openReleasePage_nonReleaseOrUnsafeUrl_neverStartsActivity() {
        val context = mockk<Context>(relaxed = true)
        for (invalid in listOf(
            "", "not a url", "http://github.com/a/b/releases/tag/v2", "file:///tmp/update.apk",
            "intent://install", "https://github.com.evil.test/a/b/releases/tag/v2",
            "https://evil.test/a/b/releases/tag/v2", "https://user@github.com/a/b/releases/tag/v2",
            "https://github.com/a/b/releases/download/v2/app.apk"
        )) {
            assertFalse(invalid, openReleasePage(context, invalid))
        }
        verify(exactly = 0) { context.startActivity(any()) }
    }
}
