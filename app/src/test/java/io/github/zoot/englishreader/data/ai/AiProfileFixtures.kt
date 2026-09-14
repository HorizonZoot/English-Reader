package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.repository.ResolvedAiProfile

/**
 * [ResolvedAiProfile] 的 canonical 测试装置。
 *
 * 只提供稳定默认值，每个字段都可显式 override。没有条件分支，没有业务语义命名——
 * 不存在 `resolvedAiProfileForKimiBoundary()` 之类把测试意图藏起来的便捷方法。
 *
 * ## 用法边界
 *
 * 适用于「profile 只是走通链路所需的背景」的用例：默认值是 DeepSeek 的一组普通取值，
 * 测试关心的字段在调用点显式写出，例如：
 *
 * ```kotlin
 * resolvedAiProfile(modelId = "kimi-k3")
 * ```
 *
 * 这样 reviewer 在用例现场就能看出 `kimi-k3` 是本条的测试变量。
 *
 * ## 刻意不覆盖的场景
 *
 * 下列测试**不要**改用本装置，它们的字段字面量本身就是判据：
 *
 * - `ExplanationCacheIdentityTest`：7 个字段全部是参数化维度。默认值会把「改一个字段
 *   缓存身份必须随之变化」这条契约藏进装置，测试从此证明不了任何事。
 * - `AiErrorMapperTest`、`AiSensitiveToStringTest`：脱敏用例断言 `sk-secret-key` /
 *   `profile-secret` 这类字面量**不出现**在渲染结果里。若默认值换成别的 secret，
 *   断言搜索的是不存在的旧字符串，会静默变成假绿。
 * - `AiProfileRepositoryTest`：profile 是从被测输入推导出的**期望值**，共享无意义。
 * - `AiExplanationRequestResolverTest`：`modelId = " deepseek-chat "` 的首尾空白就是
 *   测试输入本身。
 */
internal fun resolvedAiProfile(
    profileId: String = "test-profile",
    providerTemplate: AiProviderTemplate = AiProviderTemplate.DEEPSEEK,
    baseUrl: String = "https://api.deepseek.com",
    modelId: String = "deepseek-v4-flash",
    authStrategy: AiAuthStrategy = AiAuthStrategy.API_KEY,
    temperature: Double = 0.2,
    apiKey: String = "sk-test-key"
) = ResolvedAiProfile(
    profileId = profileId,
    providerTemplate = providerTemplate,
    baseUrl = baseUrl,
    modelId = modelId,
    authStrategy = authStrategy,
    temperature = temperature,
    apiKey = apiKey
)
