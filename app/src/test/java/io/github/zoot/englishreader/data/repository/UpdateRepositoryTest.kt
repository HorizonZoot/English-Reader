package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.repository.UpdateRepositoryFixture.Companion.DAY_MILLIS
import io.mockk.every
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * HTTP 错误矩阵。
 *
 * 参数化：这些用例除了状态码完全同构。失败信息带上状态码（见 `name`），参数化后仍能
 * 一眼认出是哪一组。
 */
@RunWith(Parameterized::class)
class UpdateRepositoryHttpErrorTest(private val statusCode: Int) {

    private val fixture = UpdateRepositoryFixture()

    @After
    fun tearDown() = fixture.shutdown()

    @Test
    fun httpError_convergesToFailedWithoutThrowing() = runTest {
        fixture.enqueueStatus(statusCode)

        val result = fixture.repository().check(manual = true)

        assertEquals("HTTP $statusCode", UpdateCheckResult.Failed, result)
        // 失败不得推进节流时间戳：否则一次 429 会把自动检查压制 24 小时。
        fixture.verifyCheckRecorded(0)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "HTTP {0} -> Failed")
        fun codes(): List<Array<Any>> = listOf(
            arrayOf(404), arrayOf(403), arrayOf(429), arrayOf(500), arrayOf(502), arrayOf(503)
        )
    }
}

/** 成功路径、传输层失败、解析失败与时间戳写入时机。 */
class UpdateRepositoryTest {

    private val fixture = UpdateRepositoryFixture()

    @After
    fun tearDown() = fixture.shutdown()

    @Test
    fun check_request_usesConfirmedRepositoryPathAndGitHubAcceptHeader() = runTest {
        fixture.enqueueRelease()
        fixture.repository().check(manual = true)
        val request = fixture.server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("/repos/HorizonZoot/English-Reader/releases?per_page=1", request?.path)
        assertEquals("application/vnd.github+json", request?.getHeader("Accept"))
    }

    @Test
    fun check_samePublishedPrerelease_returnsUpToDate() = runTest {
        fixture.enqueueRelease(tag = "v0.1.1-beta")

        val result = fixture.repository(localVersion = "0.1.1-beta").check(manual = true)

        assertEquals(UpdateCheckResult.UpToDate, result)
        assertEquals(1, fixture.server.requestCount)
        fixture.verifyCheckRecorded(1)
    }

    @Test
    fun check_noPublishedReleases_returnsUpToDateAndRecordsSuccess() = runTest {
        fixture.enqueueNoReleases()

        val result = fixture.repository().check(manual = false)

        assertEquals(UpdateCheckResult.UpToDate, result)
        assertEquals(1, fixture.server.requestCount)
        fixture.verifyCheckRecorded(1)
        assertEquals(fixture.now, fixture.lastCheck.value)
    }

    @Test
    fun check_nullPublishedRelease_returnsFailedWithoutRecordingSuccess() = runTest {
        fixture.enqueueNullRelease()

        val result = fixture.repository().check(manual = true)

        assertEquals(UpdateCheckResult.Failed, result)
        assertEquals(1, fixture.server.requestCount)
        fixture.verifyCheckRecorded(0)
        assertEquals(0L, fixture.lastCheck.value)
    }

    @Test
    fun check_preferenceReadFails_returnsFailedWithoutNetwork() = runTest {
        every { fixture.preferences.lastUpdateCheckAt } returns kotlinx.coroutines.flow.flow {
            throw java.io.IOException("storage unavailable")
        }
        assertEquals(UpdateCheckResult.Failed, fixture.repository().check(manual = false))
        assertEquals(0, fixture.server.requestCount)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun check_cancelledRequest_propagatesCancellationAndDoesNotRecordSuccess() = runTest {
        fixture.enqueueTimeout()
        val repository = fixture.repository()
        var returnedResult: UpdateCheckResult? = null
        val request = async {
            repository.check(manual = false).also { returnedResult = it }
        }
        runCurrent()
        assertTrue(withContext(kotlinx.coroutines.Dispatchers.IO) {
            fixture.server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) != null
        })
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertNull("Cancelled check must not return a normal result", returnedResult)
        fixture.verifyCheckRecorded(0)
        fixture.enqueueRelease()
        assertTrue(repository.check(manual = true) is UpdateCheckResult.UpdateAvailable)
    }

    @Test
    fun check_upstreamCancellation_propagatesSameExceptionWithoutRecordingSuccess() = runTest {
        val cancellation = CancellationException("preference read cancelled")
        every { fixture.preferences.lastUpdateCheckAt } returns flow { throw cancellation }
        val repository = fixture.repository()

        try {
            repository.check(manual = false)
            fail("CancellationException must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertTrue(coroutineContext.isActive)
        assertEquals(0, fixture.server.requestCount)
        fixture.verifyCheckRecorded(0)
    }

    @Test
    fun remoteNewer_returnsUpdateAvailableWithAllDialogFields() = runTest {
        fixture.enqueueRelease(
            tag = "0.2.0-beta",
            name = "0.2.0 beta",
            body = "## What's New\n- Improved TTS",
            htmlUrl = "https://example.com/releases/tag/v0.2.0-beta"
        )

        val result = fixture.repository(localVersion = "0.1.1-beta").check(manual = false)

        val available = result as UpdateCheckResult.UpdateAvailable
        assertEquals("0.2.0-beta", available.versionName)
        assertEquals("0.2.0 beta", available.releaseTitle)
        assertEquals("## What's New\n- Improved TTS", available.releaseNotes)
        assertEquals("https://example.com/releases/tag/v0.2.0-beta", available.releaseUrl)
        // 成功检查后必须推进节流时间戳。
        fixture.verifyCheckRecorded(1)
    }

    @Test
    fun remoteSameOrOlder_returnsUpToDate() = runTest {
        fixture.enqueueRelease(tag = "v0.1-beta")

        val result = fixture.repository(localVersion = "0.1.1-beta").check(manual = true)

        assertEquals(UpdateCheckResult.UpToDate, result)
        fixture.verifyCheckRecorded(1)
    }

    /**
     * 远端 tag 无法解析时是 `UpToDate` 而**不是** `Failed`。
     *
     * 那不是错误，只是没有可比的新版本。报成 Failed 会让手动检查对着一个格式不规范的 tag
     * 弹「检查失败」，而真正的要求是：解析不了就当没有更新，绝不弹窗。
     */
    @Test
    fun malformedRemoteTag_isTreatedAsNoUpdateNotFailure() = runTest {
        fixture.enqueueRelease(tag = "latest")

        val result = fixture.repository(localVersion = "0.1.1-beta").check(manual = true)

        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    @Test
    fun nullBodyAndName_stillReportsUpdate() = runTest {
        // GitHub 对 name/body 都可能返回 null（未填标题、无正文的 release）。
        fixture.enqueueRelease(tag = "0.2.0", name = null, body = null)

        val result = fixture.repository(localVersion = "0.1.1-beta").check(manual = true)

        val available = result as UpdateCheckResult.UpdateAvailable
        assertEquals(null, available.releaseTitle)
        assertEquals(null, available.releaseNotes)
    }

    @Test
    fun malformedJson_convergesToFailed() = runTest {
        fixture.enqueueMalformedJson()

        assertEquals(UpdateCheckResult.Failed, fixture.repository().check(manual = true))
        fixture.verifyCheckRecorded(0)
    }

    @Test
    fun connectionDropped_convergesToFailed() = runTest {
        fixture.enqueueDisconnect()

        assertEquals(UpdateCheckResult.Failed, fixture.repository().check(manual = true))
    }

    @Test
    fun readTimeout_convergesToFailed() = runTest {
        fixture.enqueueTimeout()

        assertEquals(UpdateCheckResult.Failed, fixture.repository().check(manual = true))
    }

    @Test
    fun offlineAutomaticCheck_isSkippedSilentlyWithoutRequest() = runTest {
        every { fixture.networkChecker.isOnline() } returns false

        val result = fixture.repository().check(manual = false)

        assertEquals(UpdateCheckResult.Skipped, result)
        assertEquals("离线时不该发出请求", 0, fixture.server.requestCount)
    }

    /** 手动检查时离线要报失败：用户主动点了，有权知道为什么没有结果。 */
    @Test
    fun offlineManualCheck_reportsFailure() = runTest {
        every { fixture.networkChecker.isOnline() } returns false

        val result = fixture.repository().check(manual = true)

        assertEquals(UpdateCheckResult.Failed, result)
        assertEquals(0, fixture.server.requestCount)
    }

    /**
     * 偏好写入失败不得把一次成功的检查判成失败。
     *
     * release 已经取到了，此时报失败只会让用户白白错过一个真实存在的新版本。
     */
    @Test
    fun preferenceWriteFailure_doesNotInvalidateTheResult() = runTest {
        fixture.enqueueRelease(tag = "0.2.0")
        fixture.failPreferenceWrite()

        val result = fixture.repository(localVersion = "0.1.1-beta").check(manual = true)

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
    }

    /**
     * 设备时钟被往回调过（elapsed 为负）时照常检查。
     *
     * 若把负数也当成「在节流窗口内」，自动检查会被压制到真实时间追上那个未来的时间戳为止。
     */
    @Test
    fun clockMovedBackwards_stillChecks() = runTest {
        fixture.lastCheck.value = fixture.now + DAY_MILLIS * 10
        fixture.enqueueRelease(tag = "0.2.0")

        val result = fixture.repository(localVersion = "0.1.1-beta").check(manual = false)

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        assertEquals(1, fixture.server.requestCount)
    }
}
