package io.github.zoot.englishreader.data.remote.ai

import io.github.zoot.englishreader.data.local.AiProviderTemplate

/**
 * 按 provider 与 model 裁剪请求参数。
 *
 * 只做一件窄事：决定本次请求能否携带 `temperature`。刻意**不**建立通用的「模型能力表」
 * ——那需要维护一份随厂商变动的清单，属过度设计。
 */
object AiRequestParameterPolicy {

    /** `moonshot-v1-` 家族前缀。尾部连字符是家族边界，不可省。 */
    private const val MOONSHOT_V1_FAMILY_PREFIX = "moonshot-v1-"

    /**
     * @return 应写入请求体的 temperature；返回 null 表示 JSON 中**完全不出现**该键
     *
     * Kimi 侧是 allowlist 而非 `kimi-k3` denylist：其 OpenAPI 按模型家族拆分请求 schema，
     * 只有 `moonshot-v1-*` 声明了 `temperature`；`kimi-k3` 提供 `reasoning_effort` 而无该
     * 字段，`kimi-k2.5` / `kimi-k2.6` 同样没有。denylist 会让每个未来的 K 系列模型默认带上
     * 非法参数，而 allowlist 对无法识别的模型安全退化为「省略」。
     *
     * provider 身份一律取自 [AiChatRequestConfig.providerTemplate]，绝不解析 baseUrl 主机名
     * 反推——用户选了 `OPENAI_COMPATIBLE`，就按通用兼容协议处理，这正是该选项的含义。
     */
    fun temperatureFor(config: AiChatRequestConfig): Double? =
        when (config.providerTemplate) {
            AiProviderTemplate.KIMI ->
                config.temperature.takeIf {
                    config.modelId.startsWith(MOONSHOT_V1_FAMILY_PREFIX, ignoreCase = true)
                }

            AiProviderTemplate.DEEPSEEK,
            AiProviderTemplate.ZHIPU,
            AiProviderTemplate.OPENAI_COMPATIBLE -> config.temperature
        }
}
