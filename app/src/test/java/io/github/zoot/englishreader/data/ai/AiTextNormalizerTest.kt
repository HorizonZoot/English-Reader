package io.github.zoot.englishreader.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiTextNormalizerTest {

    // ---- modelId ----

    /** 厂商 model ID 大小写敏感（`GLM-4` 与 `glm-4` 可能是不同模型），不得折叠。 */
    @Test
    fun modelId_trimsWhitespaceAndPreservesCase() {
        listOf(
            "  deepseek-v4-flash  " to "deepseek-v4-flash",
            "GLM-4-Plus" to "GLM-4-Plus"
        ).forEach { (input, expected) ->
            assertEquals(input, expected, AiTextNormalizer.normalizeModelId(input))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelId_rejectsBlank() {
        AiTextNormalizer.normalizeModelId("   ")
    }

    // ---- input ----

    /**
     * 刻意**不做**全局空白折叠：待解释文本可能是代码、诗句或带缩进的引文，
     * 折叠内部空白会改变语义，也会让两段本不相同的输入命中同一缓存。
     */
    @Test
    fun input_normalizesLineEndingsAndEdgesWhilePreservingIndentation() {
        listOf(
            Triple("CRLF and CR", "a\r\nb\rc", "a\nb\nc"),
            Triple("surrounding whitespace", "  \n hello \t ", "hello"),
            Triple("indentation", "fun main() {\n    println()\n}", "fun main() {\n    println()\n}")
        ).forEach { (case, input, expected) ->
            assertEquals(case, expected, AiTextNormalizer.normalizeInput(input))
        }
    }

    /**
     * NFC 归一：分解形式（`e` + U+0301）与预组合形式（U+00E9）必须产出同一结果。
     *
     * 码点用转义写死而非直接粘贴字符：编辑器或工具链若把源文件归一化，两个字面量在进入
     * 生产代码之前就已相同，即便 NFC 逻辑被删掉测试也会通过。三条前置断言钉住这一点。
     */
    @Test
    fun input_normalizesToNfc() {
        val decomposed = "cafe" + Char(0x0301)
        val precomposed = "caf" + Char(0x00E9)

        assertEquals("前置条件：分解形式应为 5 个 char", 5, decomposed.length)
        assertEquals("前置条件：预组合形式应为 4 个 char", 4, precomposed.length)
        assertNotEquals("前置条件：两个字面量必须不同，否则本测试是空的", precomposed, decomposed)

        assertEquals(
            AiTextNormalizer.normalizeInput(precomposed),
            AiTextNormalizer.normalizeInput(decomposed)
        )
    }

    // ---- languageTag ----

    @Test
    fun languageTag_normalizesUnderscoreAndCase() {
        assertEquals("zh-CN", AiTextNormalizer.normalizeLanguageTag("zh_cn"))
        assertEquals("zh-CN", AiTextNormalizer.normalizeLanguageTag("ZH-CN"))
        assertEquals("zh-CN", AiTextNormalizer.normalizeLanguageTag("  zh-cn  "))
    }

    @Test
    fun languageTag_invalidInput_isRejected() {
        // "!" 无法解析为合法 BCP 47 tag，Locale 会退化为 und
        listOf("blank" to "  ", "undetermined" to "!").forEach { (case, input) ->
            assertThrows(case, IllegalArgumentException::class.java) {
                AiTextNormalizer.normalizeLanguageTag(input)
            }
        }
    }

    // ---- temperature ----

    @Test
    fun temperature_finiteOrAbsentValues_returnsCanonicalRepresentation() {
        // -0.0 必须规范化为 0.0，避免语义相同的温度产生两个缓存键。
        listOf(
            Triple("negative zero", -0.0, "0.0"),
            Triple("finite value", 0.7, "0.7"),
            Triple("omitted", null, null)
        ).forEach { (case, input, expected) ->
            assertEquals(case, expected, AiTextNormalizer.normalizeTemperature(input)?.toString())
        }
    }

    @Test
    fun temperature_nonFiniteValue_isRejected() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            assertThrows(value.toString(), IllegalArgumentException::class.java) {
                AiTextNormalizer.normalizeTemperature(value)
            }
        }
    }
}
