package io.github.zoot.englishreader.data.update

import android.app.Application
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.remote.update.GitHubReleaseApiService
import io.github.zoot.englishreader.data.remote.update.GitHubReleaseFeedApiService
import io.github.zoot.englishreader.data.repository.UpdateCheckResult
import io.github.zoot.englishreader.data.repository.UpdateRepository
import io.github.zoot.englishreader.util.NetworkChecker
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GitHubUpdateReleaseSourceCancellationTest {
    private val server = MockWebServer()
    private val firstCall = CompletableDeferred<Call>()
    private val readEntered = CompletableDeferred<Unit>()
    private val bodyClosed = CompletableDeferred<Unit>()
    private val api = mockk<GitHubReleaseApiService>(relaxed = true)
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                firstCall.complete(call)
            }
        })
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = checkNotNull(response.body)
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    readEntered.complete(Unit)
                    return super.read(sink, byteCount)
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        bodyClosed.complete(Unit)
                    }
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        }.build()

    private fun source() = GitHubUpdateReleaseSource(
        Retrofit.Builder().baseUrl(server.url("/")).client(client).build()
            .create(GitHubReleaseFeedApiService::class.java),
        api,
        Dispatchers.IO,
        { 1_000_000L }
    )

    @After
    fun tearDown() {
        client.dispatcher.cancelAll()
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun getPublishedReleases_cancelBeforeHeaders_cancelsTransportWithoutFallback() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val source = source()
        val job = launch(Dispatchers.IO) { source.getPublishedReleases() }
        val call = withTimeout(5_000) { firstCall.await() }
        try {
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            job.cancel()
            withTimeout(2_000) { job.join() }
            assertTrue(call.isCanceled())
            assertTrue(job.isCancelled)
            coVerify(exactly = 0) { api.getPublishedReleases() }
        } finally {
            call.cancel()
            job.cancelAndJoin()
        }
    }

    @Test
    fun check_cancelDuringBodyRead_releasesMutexForPendingManualCheck() = runBlocking {
        // Headers promise bytes that never arrive; no sleep or body-delay timer owns the gate.
        server.enqueue(MockResponse().setBody("").setHeader("Content-Length", "100"))
        server.enqueue(MockResponse().setBody(atomFeed()))
        val preferences = mockk<SettingsPreferences>(relaxed = true)
        every { preferences.lastUpdateCheckAt } returns flowOf(0L)
        val network = mockk<NetworkChecker>()
        every { network.isOnline() } returns true
        val repository = UpdateRepository(source(), preferences, network, "0.1.0", { 1_000_000L })
        val automatic = launch(Dispatchers.IO) { repository.checkOnLaunch() }
        val call = withTimeout(5_000) { firstCall.await() }
        try {
            withTimeout(5_000) { readEntered.await() }
            val manual = async(start = CoroutineStart.UNDISPATCHED) { repository.check(manual = true) }
            try {
                assertFalse(manual.isCompleted)
                assertEquals(1, server.requestCount)
                automatic.cancel()
                withTimeout(2_000) { automatic.join() }
                assertTrue(call.isCanceled())
                withTimeout(2_000) { bodyClosed.await() }
                assertEquals(UpdateCheckResult.UpToDate, withTimeout(5_000) { manual.await() })
                assertEquals(2, server.requestCount)
                coVerify(exactly = 0) { api.getPublishedReleases() }
                coVerify(exactly = 1) { preferences.setLastUpdateCheckAt(any()) }
            } finally {
                manual.cancelAndJoin()
            }
        } finally {
            call.cancel()
            automatic.cancelAndJoin()
        }
    }
}
