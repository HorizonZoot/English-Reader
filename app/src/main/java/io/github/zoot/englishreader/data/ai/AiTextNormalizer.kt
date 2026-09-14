package io.github.zoot.englishreader.data.ai

import io.github.zoot.englishreader.model.AiExplanationTextNormalizer
import java.util.Locale

/**
 * 缓存身份与真实请求共用的文本规范化。
 *
 * 两侧必须调用同一份实现：若缓存按规范化文本命中、却把另一份原始文本发给网络，
 * 缓存内容与请求语义就会脱钩。
 */
internal object AiTextNormalizer {

    /**
     * @throws IllegalArgumentException model ID 去空白后为空
     *
     * 只去首尾空白，**保留大小写与内部字符**：厂商的 model ID 大小写敏感
     * （`GLM-4` 与 `glm-4` 可能是不同模型），不能擅自折叠。
     */
    fun normalizeModelId(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Model ID must not be blank" }
        return trimmed
    }

    /**
     * NFC 归一 + 换行统一 + 去首尾空白。
     *
     * 刻意**不做**全局空白折叠（`\s+` → `" "`）：待解释的文本可能是代码、诗句或带缩进的
     * 引文，折叠内部空白会改变语义，也会让两段本不相同的输入命中同一缓存。
     */
    fun normalizeInput(raw: String): String = AiExplanationTextNormalizer.normalize(raw)

    /**
     * 规范为 BCP 47 tag。`zh_cn` / `ZH-CN` 都收敛到 `zh-CN`。
     *
     * @throws IllegalArgumentException 空值，或 `Locale` 无法识别（`toLanguageTag()` 返回 `und`）
     *
     * 拒绝 `und` 而非放行：未定语言会让缓存身份失去区分度，且说明上游传入了无效值。
     */
    fun normalizeLanguageTag(raw: String): String {
        val cleaned = raw.trim().replace('_', '-')
        require(cleaned.isNotEmpty()) { "Output language tag must not be blank" }

        val normalized = Locale.forLanguageTag(cleaned).toLanguageTag()
        require(normalized != "und") { "Output language tag is not a valid BCP 47 tag: $raw" }
        return normalized
    }

    /**
     * @throws IllegalArgumentException 非有限值（`NaN` / `Infinity`）
     *
     * 把 `-0.0` 规范成 `0.0`：两者 `==` 相等但 `toString()` 不同，若直接进哈希会产生
     * 两个缓存键。null 表示该参数在请求中**完全省略**，由调用方转成固定 token。
     */
    fun normalizeTemperature(value: Double?): Double? {
        if (value == null) return null
        require(value.isFinite()) { "Temperature must be a finite value" }
        // 加 0.0 把 -0.0 变成 0.0，其余值不变；比 `if (value == 0.0)` 更直白，
        // 因为 -0.0 == 0.0 成立，那个写法看起来像恒等式。
        return value + 0.0
    }
}
