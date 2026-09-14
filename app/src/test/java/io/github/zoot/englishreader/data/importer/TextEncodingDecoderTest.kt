package io.github.zoot.englishreader.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 编码检测六类样本：UTF-8 / GBK / 带 BOM / 无 BOM UTF-16 / 截断多字节序列 / 随机二进制。
 */
class TextEncodingDecoderTest {

    private fun expectFailure(bytes: ByteArray): ImportFailure {
        val error = runCatching { TextEncodingDecoder.decode(bytes) }.exceptionOrNull()
        assertTrue("expected ImportException but was $error", error is ImportException)
        return (error as ImportException).failure
    }

    @Test
    fun decode_validUtf8_isNotRejectedByUtf16Heuristics() {
        // P0 回归：CJK 高字节范围也覆盖 ASCII 的 N..z，不能在严格 UTF-8 前拒收普通英文。
        // 同时保留短文本和中英混合样本。
        listOf(
            "Hello 你好世界",
            "ReaderText", "testtest", "newsletter", "ThisText", "abcdefgh",
            "The Reader opens the newsletter and reads.",
            "Hi"
        ).forEach { input ->
            assertEquals(input, input, TextEncodingDecoder.decode(input.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun decode_gbkChineseText_decodesCorrectly() {
        // Windows 记事本默认存的简体中文就是 GBK；改造前硬编码 UTF-8 会整篇乱码且静默入库
        val gbk = Charset.forName("GBK")
        val bytes = "第一段中文内容".toByteArray(gbk)

        assertEquals("第一段中文内容", TextEncodingDecoder.decode(bytes))
    }

    @Test
    fun decode_supportedBom_stripsBomAndDecodesText() {
        listOf(
            Triple("UTF-8", Charsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
            Triple("UTF-16LE", Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
            Triple("UTF-16BE", Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte()))
        ).forEach { (case, charset, bom) ->
            val result = TextEncodingDecoder.decode(bom + "Hello".toByteArray(charset))

            assertEquals(case, "Hello", result)
            assertFalse("$case: BOM 必须被剥离", result.startsWith("\uFEFF"))
        }
    }

    @Test
    fun decode_bomlessUtf16_isRejectedInsteadOfSilentGarbage() {
        // ASCII 的 UTF-16 会夹 NUL；纯中文则可能被误解为 GBK。短中文和带标点输入也须拒绝。
        listOf(
            Triple("ASCII LE", "Hello world, this is plain ASCII.", Charsets.UTF_16LE),
            Triple("ASCII BE", "Hello world, this is plain ASCII.", Charsets.UTF_16BE),
            Triple("short CJK", "中文", Charsets.UTF_16LE),
            Triple("CJK with punctuation", "中文测…试文章", Charsets.UTF_16LE),
            Triple("pure CJK", "中文内容测试", Charsets.UTF_16LE)
        ).forEach { (case, input, charset) ->
            val error = assertThrows(case, ImportException::class.java) {
                TextEncodingDecoder.decode(input.toByteArray(charset))
            }
            assertEquals(case, ImportFailure.UnsupportedEncoding, error.failure)
        }
    }

    @Test
    fun decode_truncatedMultibyteSequence_rejected() {
        // 截断的 UTF-8 序列必须被拒绝，而不是替换成 U+FFFD 静默入库。
        //
        // 样本刻意选 E4 3F 而非 E4 BD：后者虽是截断的 UTF-8（"你" = E4 BD A0 砍掉末字节），
        // 但它同时是**合法的 GBK 双字节字符**（佼），按既定管线 UTF-8 失败后 GBK 会解码成功，
        // 不抛异常——那是正确行为，不能用来验证「拒绝截断序列」。
        // E4 3F 在 UTF-8 与 GBK 下都非法（0x3F 不在 GBK 尾字节范围内），才是有效样本。
        val bytes = byteArrayOf(0xE4.toByte(), 0x3F)

        assertEquals(ImportFailure.UnsupportedEncoding, expectFailure(bytes))
    }

    @Test
    fun decode_truncatedUtf8ThatIsValidGbk_decodesAsGbk() {
        // 契约的另一面：短字节序列的编码归属本就有歧义，管线明示「UTF-8 → GBK」的优先级，
        // 故 E4 BD 会作为 GBK 双字节汉字成功解码。此用例锁定该行为，
        // 防日后误加「一律拒绝短序列」的兜底把合法 GBK 文件挡在门外。
        //
        // 只断言「成功解出一个汉字」而不写死具体字：GBK 码表的映射细节不是本模块的契约。
        val bytes = byteArrayOf(0xE4.toByte(), 0xBD.toByte())

        val result = TextEncodingDecoder.decode(bytes)

        assertEquals(1, result.length)
        assertTrue("应解出一个 CJK 汉字，实际: $result", result[0].code in 0x4E00..0x9FFF)
    }

    @Test
    fun decode_shortBomlessUtf16ThatIsAlsoValidUtf8_prefersUtf8() {
        // 已知限制（编码本身的歧义，非缺陷）：短中文短语的 UTF-16 字节可能同时是合法
        // UTF-8。"中午" 的 UTF-16LE 是 2D 4E 48 53，四字节都 < 0x80，UTF-8 严格解码
        // 直接成功返回 "-NHS"，后面的 UTF-16 中文判据执行不到。
        //
        // 本用例**固定这一行为**：不能靠把判据搬回 UTF-8 之前来「修」它，那会重新引入
        // 拒收 "ReaderText" 类英文的 P0 回归。真正的闭环需要产品侧提供
        // 「按指定编码重新导入」入口。
        val bytes = "中午".toByteArray(Charsets.UTF_16LE)

        assertEquals("-NHS", TextEncodingDecoder.decode(bytes))
    }

    @Test
    fun decode_randomBinary_rejected() {
        // PNG 文件头 + 随机字节：不该被当成文本入库
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
        )

        assertEquals(ImportFailure.UnsupportedEncoding, expectFailure(bytes))
    }

    @Test
    fun decode_crlfAndLoneCr_normalizedToLf() {
        // 段落分隔靠空行判定，换行符不统一会让 Windows 文件分段失败
        val bytes = "First\r\n\r\nSecond\rThird".toByteArray(Charsets.UTF_8)

        assertEquals("First\n\nSecond\nThird", TextEncodingDecoder.decode(bytes))
    }

    @Test
    fun decode_emptyInput_returnsEmptyString() {
        // 空内容的判定归 ImportBudgetValidator，解码器本身不该抛
        assertEquals("", TextEncodingDecoder.decode(ByteArray(0)))
    }


}
