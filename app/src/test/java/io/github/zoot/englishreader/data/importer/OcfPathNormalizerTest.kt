package io.github.zoot.englishreader.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCF 路径归一化测试。
 *
 * 存在的直接原因：这段逻辑原先内联在 EpubTextExtractor 里，而那个类需要 Android Xml
 * 与真实 ZIP 文件、无法纯 JVM 单测，于是漏过了「校验通过但返回未消解原串」的缺陷。
 * 提出来独立成对象后才有了这批用例。
 */
class OcfPathNormalizerTest {

    private fun failureOf(path: String): ImportFailure {
        val error = runCatching { OcfPathNormalizer.normalize(path) }.exceptionOrNull()
        assertTrue("path=$path: expected ImportException but was $error", error is ImportException)
        return (error as ImportException).failure
    }

    // ---- 归一化：必须返回消解后的路径，而不是原串 ----

    @Test
    fun normalize_pathSegments_returnsContainerRelativePath() {
        // 回归用例：曾经这里返回未消解的 "OEBPS/./content.opf"，
        // 带着 ./ 去 ZipFile.getEntry() 查不到，合法书被误判为结构损坏
        listOf(
            "OEBPS/./content.opf" to "OEBPS/content.opf",
            "a/b/../c" to "a/c",
            "OEBPS/content.opf" to "OEBPS/content.opf",
            "/OEBPS/a.xhtml" to "OEBPS/a.xhtml", // 路径相对容器根。
            "OEBPS\\a.xhtml" to "OEBPS/a.xhtml", // 兼容打包工具的 Windows 分隔符。
            "OEBPS//a.xhtml" to "OEBPS/a.xhtml"
        ).forEach { (path, expected) ->
            assertEquals(path, expected, OcfPathNormalizer.normalize(path))
        }
    }

    // ---- 逃逸校验 ----

    @Test
    fun normalize_invalidPath_isRejected() {
        listOf(
            "root escape" to "../secret.txt",
            "escape after descending" to "a/../../etc/passwd",
            "NUL truncation" to "OEBPS/a\u0000.xhtml",
            "empty after dot resolution" to ".",
            "empty after slash stripping" to "/",
            "empty input" to ""
        ).forEach { (case, path) ->
            assertEquals(case, ImportFailure.InvalidEpub, failureOf(path))
        }
    }

    // ---- resolve：manifest href 相对 OPF 目录 ----

    @Test
    fun resolve_relativeReference_returnsEntryPath() {
        listOf(
            Triple("OEBPS", "text/ch1.xhtml", "OEBPS/text/ch1.xhtml"),
            Triple("", "ch1.xhtml", "ch1.xhtml"), // OPF 在容器根下。
            Triple("OEBPS", "../text/ch1.xhtml", "text/ch1.xhtml"),
            Triple("OEBPS", "ch1.xhtml#section2", "OEBPS/ch1.xhtml"),
            Triple("OEBPS", "chapter%20one.xhtml", "OEBPS/chapter one.xhtml"),
            // 未编码 # 分隔片段，%23 则是文件名中的字面井号。
            Triple("OEBPS", "a%23b.xhtml", "OEBPS/a#b.xhtml"),
            Triple("OEBPS", "a.xhtml#sec1", "OEBPS/a.xhtml"),
            Triple("OEBPS", "ch%20one.xhtml", "OEBPS/ch one.xhtml"),
            Triple("OEBPS", "50%.xhtml", "OEBPS/50%.xhtml")
        ).forEach { (baseDir, href, expected) ->
            assertEquals("base=$baseDir href=$href", expected, OcfPathNormalizer.resolve(baseDir, href))
        }
    }

    @Test
    fun resolve_escapingRoot_isRejected() {
        val error = runCatching {
            OcfPathNormalizer.resolve("OEBPS", "../../outside.xhtml")
        }.exceptionOrNull()

        assertTrue(error is ImportException)
        assertEquals(ImportFailure.InvalidEpub, (error as ImportException).failure)
    }

    @Test
    fun resolve_producesSameFormAsNormalize() {
        // 契约：DRM 检测把 encryption.xml 的路径（走 normalize）与 spine 路径（走 resolve）
        // 直接比对，两者必须归一到同一形式，否则加密的正文会漏判。
        val viaResolve = OcfPathNormalizer.resolve("OEBPS", "./text/ch1.xhtml")
        val viaNormalize = OcfPathNormalizer.normalize("OEBPS/text/ch1.xhtml")

        assertEquals(viaNormalize, viaResolve)
    }

    // ---- percent-decoding：href 是 URI 引用，entry 名是解码后的字面值 ----

    @Test
    fun normalize_percentEscapes_decodeUriPathWithoutFormOrSurrogateCorruption() {
        // 回归用例：清单写 chapter%20one.xhtml，ZIP entry 实际叫 "chapter one.xhtml"，
        // 不解码就 getEntry() 查不到，合法书被拒
        listOf(
            "OEBPS/chapter%20one.xhtml" to "OEBPS/chapter one.xhtml",
            "a%23b.xhtml" to "a#b.xhtml",
            "a%5Bb%5D.xhtml" to "a[b].xhtml",
            "a%2Bb.xhtml" to "a+b.xhtml",
            // URI 路径中的字面 + 不能按 form-urlencoded 规则解成空格。
            "C++%20notes.xhtml" to "C++ notes.xhtml",
            "%E4%B8%AD%E6%96%87.xhtml" to "中文.xhtml",
            // 按 UTF-16 Char 分别编码曾破坏 surrogate pair；同时保留无转义的对照。
            "📚%20book.xhtml" to "📚 book.xhtml",
            "OEBPS/%F0%9D%84%9Emusic.xhtml" to "OEBPS/𝄞music.xhtml",
            "📚book.xhtml" to "📚book.xhtml"
        ).forEach { (path, expected) ->
            assertEquals(path, expected, OcfPathNormalizer.normalize(path))
        }
    }

    @Test
    fun normalize_encodedTraversal_isRejected() {
        // 关键安全用例：解码必须先于逃逸校验，否则 %2e%2e%2f 能绕过整道防线
        assertEquals(ImportFailure.InvalidEpub, failureOf("%2e%2e%2fsecret.txt"))
        assertEquals(ImportFailure.InvalidEpub, failureOf("%2E%2E/secret.txt"))
    }

    @Test
    fun normalize_encodedSeparatorIsTreatedAsSeparator() {
        // %2F 解码后是路径分隔符，须参与消解——否则 a%2F..%2Fb 会绕过逃逸检查
        assertEquals("b.xhtml", OcfPathNormalizer.normalize("a%2F..%2Fb.xhtml"))
        assertEquals(ImportFailure.InvalidEpub, failureOf("..%2F..%2Fx"))
    }

    @Test
    fun normalize_encodedNul_isRejected() {
        assertEquals(ImportFailure.InvalidEpub, failureOf("a%00.xhtml"))
    }

    @Test
    fun normalize_invalidPercentEscape_keepsLiteralFilename() {
        // % 后不足两位十六进制更可能是文件名里的字面百分号，拒绝整本书代价过大
        // toIntOrNull(16) 接受正负号，Character.digit 接受全角；两者都不符合 URI 转义。
        listOf(
            "50%.xhtml", "a%zz.xhtml", "a%+41.xhtml", "a%-1b.xhtml", "a% 4.xhtml",
            "a%ＡＦ.xhtml", "a%０１.xhtml"
        ).forEach { path ->
            assertEquals(path, path, OcfPathNormalizer.normalize(path))
        }
    }

    @Test
    fun normalize_doubleEncodingIsNotDecodedTwice() {
        // 只解一次：重复解码会让 %252e%252e 变成 ..，给多轮编码绕过留空间
        assertEquals("%2e%2e", OcfPathNormalizer.normalize("%252e%252e"))
    }

    @Test
    fun resolve_alreadyDecodedBaseDir_preservesLiteralEscapes() {
        // baseDir 来自 normalize(OPF 路径)，已解码过一次。resolve 拼接后再调 normalize
        // 会对它二次解码：OPF 路径写 %252e%252e 时 opfDir 已是字面 %2e%2e，再解一次就成
        // 真的 ..，于是读到错误章节；而 encryption.xml 那侧只解一次，两边路径形式不一致
        // 会让 DRM 比对漏判。
        listOf(
            "a/%2e%2e" to "a/%2e%2e/ch1.xhtml",
            "dir%20x" to "dir%20x/ch1.xhtml"
        ).forEach { (baseDir, expected) ->
            assertEquals(baseDir, expected, OcfPathNormalizer.resolve(baseDir, "ch1.xhtml"))
        }
    }
}
