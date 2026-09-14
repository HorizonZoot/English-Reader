package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.entity.ExplanationCacheEntity
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.remote.ai.AiChatRequestConfig
import io.github.zoot.englishreader.data.remote.ai.AiChatTransport
import io.github.zoot.englishreader.data.remote.ai.AiChatTransportResult
import io.github.zoot.englishreader.data.remote.ai.AiRequestParameterPolicy
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ExplanationCacheRepository
import io.github.zoot.englishreader.data.repository.ProfileResolutionResult
import io.github.zoot.englishreader.data.repository.ResolvedAiProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 完整链路组合测试：`DefaultAiClient → CachedAiExecutor → RemoteAiExecutor → transport`。
 *
 * 各层单测都绿也可能整链有病：分层测试各自 mock 掉了邻居，无法发现「缓存身份用一个值、
 * 真实请求用另一个值」这类跨层漂移。本文件只装配真实实现，仅 mock 最外两端
 * （profile repository、cache repository、transport）。
 */
class AiClientIntegrationTest {

    private val profileRepository: AiProfileRepository = mockk()
    private val cacheRepository: ExplanationCacheRepository = mockk()
    private val transport: AiChatTransport = mockk()
    private val remoteExecutor = RemoteAiExecutor(transport)

    private val client: AiClient = DefaultAiClient(
        profileRepository = profileRepository,
        executor = CachedAiExecutor(
            delegate = remoteExecutor,
            cacheRepository = cacheRepository
        ),
        connectionExecutor = remoteExecutor,
        connectionRegistry = AiConnectionTestRegistry(),
        errorMapper = AiErrorMapper(isOnline = { true })
    )

    @Test
    fun cacheMiss_goesToTransportAndWritesResult() = runTest {
        givenProfile()
        coEvery { cacheRepository.getCachedExplanation(any()) } returns null
        coEvery { transport.complete(any(), any()) } returns
            AiChatTransportResult.Content("This sentence means...")
        coEvery { cacheRepository.insertCache(any()) } returns Unit

        val result = client.explain("p1", "Hello world.")

        assertEquals(AiClientResult.Success("This sentence means..."), result)
        coVerify(exactly = 1) { transport.complete(any(), any()) }
        coVerify(exactly = 1) { cacheRepository.insertCache(any()) }
    }

    @Test
    fun cacheHit_doesNotTouchTransport() = runTest {
        givenProfile()
        coEvery { cacheRepository.getCachedExplanation(any()) } returns
            ExplanationCacheEntity(cacheKey = "k", explanation = "cached explanation")

        val result = client.explain("p1", "Hello world.")

        assertEquals(AiClientResult.Success("cached explanation"), result)
        coVerify(exactly = 0) { transport.complete(any(), any()) }
    }

    /**
     * 本任务的跨层核心不变量：**缓存身份判定 temperature 用的 model ID，与真实请求发送的
     * 必须是同一个值**。
     *
     * 曾经的实现里 identity 用 `profile.modelId` 原始值、remote 先 trim 再发送。带首尾空白的
     * `"  moonshot-v1-8k  "` 于是不匹配 Kimi 家族前缀：identity 记为「省略 temperature」，
     * 真实请求却发送了 `temperature`。
     *
     * 抓法必须是「同一 model ID 的两种写法产出同一缓存键」——因为漂移只改变 temperature 一项，
     * 而 identity 的 `modelId` 字段两侧都是规范化值。若改成对比两个不同 model ID，
     * `modelId` 字段本身就不同，键必然不同，测试无论有无漂移都会通过。
     */
    @Test
    fun kimiModelIdWithWhitespace_keepsIdentityAndRequestInSync() = runTest {
        val padded = requestOutcomeFor(
            template = AiProviderTemplate.KIMI,
            modelId = "  moonshot-v1-8k  ",
            temperature = 0.7
        )
        val clean = requestOutcomeFor(
            template = AiProviderTemplate.KIMI,
            modelId = "moonshot-v1-8k",
            temperature = 0.7
        )

        // 两侧的真实请求都应发送 temperature（trim 后匹配 moonshot-v1- 家族）
        assertEquals(0.7, padded.sentTemperature!!, 0.0)
        assertEquals(0.7, clean.sentTemperature!!, 0.0)

        // 请求语义一致，故缓存键必须一致——漂移会让 padded 侧记为 omitted 而使两键不同
        assertEquals(
            "identity 的 temperature 判据必须与真实请求一致",
            clean.cacheKey,
            padded.cacheKey
        )
    }

    /**
     * Kimi 家族边界仍然生效：真正省略 temperature 的模型不能与发送 temperature 的共享键。
     *
     * 这条与上面互补——上面防「该同的不同」，这条防「该不同的同了」。
     */
    @Test
    fun kimiOmittedTemperature_doesNotShareKeyWithExplicitTemperature() = runTest {
        val omitted = requestOutcomeFor(
            template = AiProviderTemplate.KIMI,
            modelId = "kimi-k3",
            temperature = 0.7
        )
        val explicit = requestOutcomeFor(
            template = AiProviderTemplate.KIMI,
            modelId = "moonshot-v1-8k",
            temperature = 0.7
        )

        assertNull("kimi-k3 的请求体不得含 temperature", omitted.sentTemperature)
        assertEquals(0.7, explicit.sentTemperature!!, 0.0)
        assertNotEquals(omitted.cacheKey, explicit.cacheKey)
    }

    /**
     * 反向：带空白与不带空白的同一个 model ID 必须命中**同一个**缓存键。
     *
     * 否则用户在设置里误敲一个空格，就会让已有缓存全部失效并重新付费。
     */
    @Test
    fun modelIdWhitespaceDoesNotChangeCacheKey() = runTest {
        val padded = requestOutcomeFor(modelId = "  deepseek-v4-flash  ")
        val clean = requestOutcomeFor(modelId = "deepseek-v4-flash")
        assertEquals("model ID 的首尾空白不得改变缓存身份", clean.cacheKey, padded.cacheKey)
    }

    /** 输入文本的首尾空白与换行形式同理不得改变缓存身份。 */
    @Test
    fun inputWhitespaceDoesNotChangeCacheKey() = runTest {
        val padded = requestOutcomeFor(sentence = "  Hello world.\r\n")
        val clean = requestOutcomeFor(sentence = "Hello world.")
        assertEquals("输入的首尾空白与 CRLF 不得改变缓存身份", clean.cacheKey, padded.cacheKey)
    }

    // ---- 辅助方法 ----

    private fun givenProfile(
        template: AiProviderTemplate = AiProviderTemplate.DEEPSEEK,
        modelId: String = "deepseek-v4-flash",
        temperature: Double = 0.2
    ) {
        coEvery { profileRepository.resolveValidatedProfile(any(), any()) } returns
            ProfileResolutionResult.Available(
                ResolvedAiProfile(
                    profileId = "test-profile",
                    providerTemplate = template,
                    baseUrl = "https://api.moonshot.ai/v1",
                    modelId = modelId,
                    authStrategy = AiAuthStrategy.API_KEY,
                    apiKey = "sk-test-key",
                    temperature = temperature
                )
            )
    }

    /** 一次完整链路调用的可观察结果：写入的缓存键，以及真实请求体里的 temperature。 */
    private data class RequestOutcome(
        val cacheKey: String,
        val sentTemperature: Double?
    )

    /**
     * 跑一次完整链路，同时捕获缓存键与真实请求参数。
     *
     * 两者一起返回是刻意的：identity 与真实请求的一致性只能通过**并排比对**验证，
     * 分别断言各自「看起来对」是抓不到漂移的。
     *
     * `sentTemperature` 取 `temperatureFor(capturedConfig)` 而非 `capturedConfig.temperature`：
     * 裁剪发生在 transport 内部构造 DTO 时（见 `RetrofitAiChatTransport`），config 里带的
     * 仍是 profile 原始值。这里调用同一个生产函数，得到的就是真实会发出的值。
     */
    private suspend fun requestOutcomeFor(
        template: AiProviderTemplate = AiProviderTemplate.DEEPSEEK,
        modelId: String = "deepseek-v4-flash",
        temperature: Double = 0.2,
        sentence: String = "Hello world."
    ): RequestOutcome {
        val profileRepo: AiProfileRepository = mockk()
        val cacheRepo: ExplanationCacheRepository = mockk()
        val localTransport: AiChatTransport = mockk()

        coEvery { profileRepo.resolveValidatedProfile(any(), any()) } returns
            ProfileResolutionResult.Available(
                ResolvedAiProfile(
                    profileId = "test-profile",
                    providerTemplate = template,
                    baseUrl = "https://api.moonshot.ai/v1",
                    modelId = modelId,
                    authStrategy = AiAuthStrategy.API_KEY,
                    apiKey = "sk-test-key",
                    temperature = temperature
                )
            )
        coEvery { cacheRepo.getCachedExplanation(any()) } returns null
        coEvery { localTransport.complete(any(), any()) } returns AiChatTransportResult.Content("ok")
        coEvery { cacheRepo.insertCache(any()) } returns Unit

        val localRemoteExecutor = RemoteAiExecutor(localTransport)
        val localClient: AiClient = DefaultAiClient(
            profileRepository = profileRepo,
            executor = CachedAiExecutor(localRemoteExecutor, cacheRepo),
            connectionExecutor = localRemoteExecutor,
            connectionRegistry = AiConnectionTestRegistry(),
            errorMapper = AiErrorMapper(isOnline = { true })
        )
        localClient.explain("p1", sentence)

        val written = slot<ExplanationCacheEntity>()
        val config = slot<AiChatRequestConfig>()
        coVerify { cacheRepo.insertCache(capture(written)) }
        coVerify { localTransport.complete(capture(config), any()) }

        return RequestOutcome(
            cacheKey = written.captured.cacheKey,
            sentTemperature = AiRequestParameterPolicy.temperatureFor(config.captured)
        )
    }
}
