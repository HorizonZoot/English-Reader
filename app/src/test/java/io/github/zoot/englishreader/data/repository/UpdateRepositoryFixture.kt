package io.github.zoot.englishreader.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.remote.update.GitHubReleaseApiService
import io.github.zoot.englishreader.util.NetworkChecker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * `UpdateRepository` 测试的共享装置。
 *
 * 用真实的 Retrofit + Moshi 栈打到 MockWebServer 上，而不是 mock `GitHubReleaseApiService`：
 * 本任务要验的恰恰包括「HTTP 状态码如何变成结果」与「畸形 JSON 被收敛」，把 ApiService
 * mock 掉就只剩验证 mock 自己。
 */
internal class UpdateRepositoryFixture {

    val server = MockWebServer()
    val networkChecker: NetworkChecker = mockk(relaxed = true)
    val preferences: SettingsPreferences = mockk(relaxed = true)

    /** 可控时钟。用例改这个值来跨越/不跨越 24h 窗口，不用 `Thread.sleep`。 */
    var now: Long = DAY_MILLIS * 100

    /** 上次检查时刻的上游。用 MutableStateFlow 以便用例随时改。 */
    val lastCheck = MutableStateFlow(0L)

    init {
        every { networkChecker.isOnline() } returns true
        every { preferences.lastUpdateCheckAt } returns lastCheck
        coEvery { preferences.setLastUpdateCheckAt(any()) } coAnswers {
            lastCheck.value = firstArg()
        }
    }

    fun repository(localVersion: String = "0.1.1-beta"): UpdateRepository {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val service = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(
                OkHttpClient.Builder()
                    // 短超时：timeout 用例不必真等 10 秒。
                    .connectTimeout(500, TimeUnit.MILLISECONDS)
                    .readTimeout(500, TimeUnit.MILLISECONDS)
                    .callTimeout(1, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .build()
            )
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GitHubReleaseApiService::class.java)

        return UpdateRepository(
            apiService = service,
            settingsPreferences = preferences,
            networkChecker = networkChecker,
            localVersionName = localVersion,
            currentTimeMillis = { now }
        )
    }

    fun enqueueRelease(
        tag: String = "0.2.0-beta",
        name: String? = "Release 0.2.0-beta",
        body: String? = "## What's New\n- Improved TTS",
        htmlUrl: String = "https://github.com/HorizonZoot/English-Reader/releases/tag/v0.2.0-beta"
    ) {
        val json = buildString {
            append("""{"tag_name":"$tag",""")
            append(if (name == null) """"name":null,""" else """"name":"$name",""")
            append(if (body == null) """"body":null,""" else """"body":${quote(body)},""")
            // 刻意多带几个本功能不声明的字段：Moshi 必须忽略它们而不是报错。
            append(""""draft":false,"prerelease":true,"assets":[{"name":"app.apk"}],""")
            append(""""html_url":"$htmlUrl"}""")
        }
        // 发布列表返回数组，且包含已发布的 prerelease；/latest 不会返回它们。
        server.enqueue(MockResponse().setResponseCode(200).setBody("[$json]"))
    }

    fun enqueueNoReleases() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
    }

    fun enqueueNullRelease() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[null]"))
    }

    fun enqueueStatus(code: Int) {
        server.enqueue(MockResponse().setResponseCode(code).setBody("""{"message":"error"}"""))
    }

    fun enqueueMalformedJson() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"tag_name":"""))
    }

    fun enqueueDisconnect() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
    }

    fun enqueueTimeout() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    }

    fun shutdown() = server.shutdown()

    /** 断言时间戳被记录（成功检查后）。 */
    fun verifyCheckRecorded(times: Int) {
        coVerify(exactly = times) { preferences.setLastUpdateCheckAt(any()) }
    }

    /** 让偏好写入抛错，用于验证「写失败不影响本次结论」。 */
    fun failPreferenceWrite() {
        coEvery { preferences.setLastUpdateCheckAt(any()) } throws RuntimeException("datastore down")
    }

    private fun quote(raw: String): String =
        "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    companion object {
        val DAY_MILLIS: Long = TimeUnit.DAYS.toMillis(1)
    }
}
