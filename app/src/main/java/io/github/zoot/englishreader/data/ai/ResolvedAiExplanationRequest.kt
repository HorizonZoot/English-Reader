package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.data.repository.ResolvedAiProfile
import io.github.zoot.englishreader.data.remote.ai.AiChatMessage

/**
 * 一次解释请求的完整快照，profile 已解析、文本已规范化。
 *
 * 存在的理由是「解析只能发生一次」：公开契约只带 profile ID 与 typed input，
 * 不足以构造缓存身份（缺 endpoint / model / 有效参数）。若缓存层自己解析一次、
 * 远端层再解析一次，两次之间用户改了 endpoint 或 model，就会把请求 B 的结果写进身份 A 的键下
 * ——这正是 6.4 要消除的串味。
 *
 * 因此 [DefaultAiClient] 解析恰好一次并构造本对象，缓存身份与真实请求都从这**同一个**
 * 快照派生。
 *
 * [normalizedModelId] 与 [normalizedInput] 是**已规范化**的值，作为字段存在而非让下游各自
 * 规范化：identity 与 remote 若各调一次 `normalizeModelId`，任一侧漏调就会让身份记录的
 * model 与实际发送的不同——例如 `"  moonshot-v1-8k  "` 未 trim 时不匹配 Kimi 家族前缀，
 * identity 记为「省略 temperature」而请求实际发送了它，两个不同温度于是共享同一缓存键。
 * `preparedMessages` 与这些元数据由同一次 [AiPromptPolicy.prepare] 产生，remote 不得重新
 * 拼接 prompt。
 */
internal data class ResolvedAiExplanationRequest(
    val profile: ResolvedAiProfile,
    val normalizedModelId: String,
    val normalizedInput: String,
    val outputLanguageTag: String,
    val explanationType: ExplanationType,
    val promptVersion: String,
    val preparedMessages: List<AiChatMessage>
) {
    /**
     * 脱敏：endpoint 可能是私有代理主机，输入是用户正在读的文章内容，
     * profile 里还带明文 API key。ADR-010 禁止记录完整 URL。
     */
    override fun toString(): String =
        "ResolvedAiExplanationRequest(" +
            "endpoint=[REDACTED], " +
            "normalizedModelId=[REDACTED], " +
            "normalizedInput=[REDACTED], " +
            "explanationType=$explanationType, " +
            "promptVersion=$promptVersion, " +
            "outputLanguageTag=$outputLanguageTag, " +
            "messageCount=${preparedMessages.size})"
}
