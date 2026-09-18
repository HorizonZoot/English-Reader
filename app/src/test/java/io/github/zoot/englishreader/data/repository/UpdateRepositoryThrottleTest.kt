package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.repository.UpdateRepositoryFixture.Companion.DAY_MILLIS
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 24h 节流。
 *
 * **判据一律落在 `server.requestCount`，不是返回值。** 只断言返回 `Skipped` 的话，一个
 * 「读了时间戳但照样发请求、然后丢弃响应」的实现同样能绿——而那恰恰一点流量都没省下，
 * 节流的全部意义就没了。
 */
class UpdateRepositoryThrottleTest {

    private val fixture = UpdateRepositoryFixture()

    @After
    fun tearDown() = fixture.shutdown()

    @Test
    fun firstCheck_hasNoTimestamp_sendsRequest() = runTest {
        fixture.lastCheck.value = 0L
        fixture.enqueueRelease(tag = "v0.1-beta")

        val result = fixture.repository().check(manual = false)

        assertEquals(1, fixture.server.requestCount)
        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    @Test
    fun automaticCheckWithinTwentyFourHours_sendsNoRequest() = runTest {
        // 距上次检查 23 小时：仍在窗口内。
        fixture.lastCheck.value = fixture.now - DAY_MILLIS + ONE_HOUR

        val result = fixture.repository().check(manual = false)

        assertEquals("节流命中时必须一个请求都不发", 0, fixture.server.requestCount)
        assertEquals(UpdateCheckResult.Skipped, result)
    }

    @Test
    fun automaticCheckAfterTwentyFourHours_sendsRequest() = runTest {
        fixture.lastCheck.value = fixture.now - DAY_MILLIS - ONE_HOUR
        fixture.enqueueRelease(tag = "v0.1-beta")

        val result = fixture.repository().check(manual = false)

        assertEquals(1, fixture.server.requestCount)
        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    /** 边界：恰好 24 小时应当允许检查（`elapsed in 0 until THROTTLE`）。 */
    @Test
    fun automaticCheckExactlyAtBoundary_sendsRequest() = runTest {
        fixture.lastCheck.value = fixture.now - DAY_MILLIS
        fixture.enqueueRelease(tag = "v0.1-beta")

        fixture.repository().check(manual = false)

        assertEquals(1, fixture.server.requestCount)
    }

    @Test
    fun manualCheckWithinTwentyFourHours_ignoresThrottleAndSendsRequest() = runTest {
        fixture.lastCheck.value = fixture.now - ONE_HOUR
        fixture.enqueueRelease(tag = "v0.1-beta")

        val result = fixture.repository().check(manual = true)

        assertEquals("手动检查必须无视节流", 1, fixture.server.requestCount)
        // 手动路径不应返回 Skipped——那会让用户拿到一个从未验证过的结论。
        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    /**
     * 失败不推进时间戳：紧接着的自动检查仍会发请求。
     *
     * 判据必须是「第二次也发了请求」而不是「时间戳没被写」——后者可以靠 mock 验证，
     * 但前者才是用户真正在意的行为（断网失败不该把自动检查压制 24 小时）。
     */
    @Test
    fun failedCheck_doesNotSuppressTheNextAutomaticCheck() = runTest {
        fixture.lastCheck.value = 0L
        fixture.enqueueStatus(500)
        fixture.enqueueRelease(tag = "v0.1-beta")
        val repository = fixture.repository()

        assertEquals(UpdateCheckResult.Failed, repository.check(manual = false))
        // 时间戳没被写，所以 lastCheck 仍是 0，第二次自动检查照常发出。
        assertEquals(UpdateCheckResult.UpToDate, repository.check(manual = false))

        assertEquals(2, fixture.server.requestCount)
        fixture.verifyCheckRecorded(1) // 只有成功那次记了
    }

    @Test
    fun successfulCheck_suppressesAutomaticRequestUntilBoundaryButNotManual() = runTest {
        val repository = fixture.repository()
        fixture.enqueueRelease()
        repository.check(manual = false)
        assertEquals(fixture.now, fixture.lastCheck.value)
        fixture.now += ONE_HOUR
        assertEquals(UpdateCheckResult.Skipped, repository.check(manual = false))
        assertEquals(1, fixture.server.requestCount)
        fixture.enqueueRelease()
        repository.check(manual = true)
        assertEquals(2, fixture.server.requestCount)
        fixture.now += DAY_MILLIS
        fixture.enqueueRelease()
        repository.check(manual = false)
        assertEquals(3, fixture.server.requestCount)
    }

    @Test
    fun launchCheck_runsOncePerRepositoryLifetime_evenAfterFailure() = runTest {
        val repository = fixture.repository()
        fixture.enqueueStatus(500)
        assertEquals(UpdateCheckResult.Failed, repository.checkOnLaunch())
        assertEquals(UpdateCheckResult.Skipped, repository.checkOnLaunch())
        assertEquals(1, fixture.server.requestCount)
        fixture.enqueueRelease()
        repository.check(manual = true)
        assertEquals(2, fixture.server.requestCount)
    }

    private companion object {
        const val ONE_HOUR = 60L * 60 * 1000
    }
}
