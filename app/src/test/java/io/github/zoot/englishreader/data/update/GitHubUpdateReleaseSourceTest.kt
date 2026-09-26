package io.github.zoot.englishreader.data.update

import android.app.Application
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.zoot.englishreader.data.remote.update.GitHubReleaseApiService
import io.github.zoot.englishreader.data.remote.update.GitHubReleaseFeedApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GitHubUpdateReleaseSourceTest {
    private val feedServer = MockWebServer()
    private val apiServer = MockWebServer()
    private var now = 1_000_000L
    private val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()

    private fun source(): GitHubUpdateReleaseSource = GitHubUpdateReleaseSource(
        Retrofit.Builder().baseUrl(feedServer.url("/")).client(client).build()
            .create(GitHubReleaseFeedApiService::class.java),
        Retrofit.Builder().baseUrl(apiServer.url("/")).client(client)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build().create(GitHubReleaseApiService::class.java),
        Dispatchers.IO,
        { now }
    )

    @After
    fun closeServers() {
        feedServer.shutdown()
        apiServer.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun feedSuccess_avoidsRestQuotaAndReturnsLatestVersionWithReadableNotes() = runTest {
        feedServer.enqueue(MockResponse().setBody(atomFeed("v0.1.3-beta", "v0.1.4-beta")))
        apiServer.enqueue(MockResponse().setResponseCode(403).addHeader("X-RateLimit-Remaining", "0"))
        val result = source().getPublishedReleases()
        assertEquals("v0.1.4-beta", result.first().tagName)
        assertEquals("Better updates", result.first().body)
        assertEquals(0, apiServer.requestCount)
        val request = feedServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/HorizonZoot/English-Reader/releases.atom", request.path)
        assertEquals("application/atom+xml", request.getHeader("Accept"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun malformedFeed_fallsBackToRealRetrofitAndMoshiApi() = runTest {
        feedServer.enqueue(MockResponse().setBody("<html>Unavailable</html>"))
        apiServer.enqueue(apiRelease())
        assertEquals("v0.1.4-beta", source().getPublishedReleases().single().tagName)
        val request = apiServer.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/repos/HorizonZoot/English-Reader/releases?per_page=1", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun chunkedOversizedFeed_isBoundedAndUsesFallback() = runTest {
        feedServer.enqueue(MockResponse().setChunkedBody("x".repeat(600_000), 8192))
        apiServer.enqueue(apiRelease())
        assertEquals("v0.1.4-beta", source().getPublishedReleases().single().tagName)
        assertEquals(1, apiServer.requestCount)
    }

    @Test
    fun rateLimitedFallback_respectsResetWhileFeedRemainsAvailable() = runTest {
        repeat(3) { feedServer.enqueue(MockResponse().setResponseCode(503)) }
        apiServer.enqueue(MockResponse().setResponseCode(403)
            .addHeader("X-RateLimit-Remaining", "0").addHeader("X-RateLimit-Reset", "1005"))
        apiServer.enqueue(apiRelease())
        val source = source()
        suspend fun assertLimited() {
            try {
                source.getPublishedReleases()
                fail("Expected rate limit")
            } catch (error: UpdateRateLimitedException) {
                assertEquals(1_005_000L, error.retryAtMillis)
            }
        }
        assertLimited()
        now += 1000
        assertLimited()
        assertEquals(1, apiServer.requestCount)
        now = 1_006_000L
        assertEquals("v0.1.4-beta", source.getPublishedReleases().single().tagName)
        assertEquals(2, apiServer.requestCount)
    }

    @Test
    fun emptyFeed_doesNotNeedFallback() = runTest {
        feedServer.enqueue(MockResponse().setBody(atomFeed()))
        assertTrue(source().getPublishedReleases().isEmpty())
        assertEquals(0, apiServer.requestCount)
    }

    @Test
    fun cancelledFeed_propagatesWithoutCallingFallback() = runTest {
        val cancellation = CancellationException("cancel check")
        var fallbackCalls = 0
        val feed = object : GitHubReleaseFeedApiService {
            override suspend fun getReleaseFeed(): ResponseBody = throw cancellation
        }
        val api = object : GitHubReleaseApiService {
            override suspend fun getPublishedReleases(): List<io.github.zoot.englishreader.data.remote.update.GitHubRelease> {
                fallbackCalls++
                return emptyList()
            }
        }
        try {
            GitHubUpdateReleaseSource(feed, api, Dispatchers.IO, { now }).getPublishedReleases()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual.cause ?: actual)
        }
        assertEquals(0, fallbackCalls)
    }

    private fun apiRelease() = MockResponse().setBody("""[
        {"tag_name":"v0.1.4-beta","name":"Next release","body":"Notes",
         "html_url":"https://github.com/HorizonZoot/English-Reader/releases/tag/v0.1.4-beta"}
    ]""")
}
