package io.github.zoot.englishreader.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 版本比较矩阵。
 *
 * 用参数化而非一堆 `@Test`：判据完全同构，只有输入不同。失败信息带上 `remote → local`
 * 这样仍能一眼认出是哪一组（见 [name]）。
 */
@RunWith(Parameterized::class)
class AppVersionComparisonTest(
    private val remote: String,
    private val local: String,
    private val expectNewer: Boolean,
    @Suppress("unused") private val reason: String
) {

    @Test
    fun isNewer_matchesExpectation() {
        assertEquals(
            "remote=$remote local=$local（$reason）",
            expectNewer,
            AppVersion.isNewer(remote, local)
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} vs {1} -> {2} ({3})")
        fun cases(): List<Array<Any>> = listOf(
            // ---- 原始 brief 要求的 5 条 ----
            arrayOf("1.1.0", "1.0.0", true, "次版本号递增"),
            arrayOf("1.1.0", "1.1.0", false, "完全相同"),
            arrayOf("1.1.0", "1.2.0", false, "远端更旧"),
            // 字符串比较会判成 "1.10.0" < "1.9.0"，这条就是为挡住那种实现。
            arrayOf("1.10.0", "1.9.0", true, "10 > 9，不能按字典序"),
            arrayOf("v1.6.0", "1.5.0", true, "远端带 v 前缀"),

            // ---- 本项目的真实版本形状（brief 的矩阵一条都覆盖不到）----
            // 已有 tag 是两段数字，当前版本是三段：长度不等必须补零。
            arrayOf("0.1.1-beta", "v0.1-beta", true, "两段 vs 三段，补零后 0.1.0 < 0.1.1"),
            arrayOf("0.1.2-beta", "0.1.1-beta", true, "同后缀，数字段递增"),
            // 只比数字三元组会把这条判成「无更新」，用户永远收不到正式版通知。
            arrayOf("0.2.0", "0.2.0-beta", true, "正式版高于预发布版"),
            arrayOf("0.2.0-beta", "0.2.0", false, "反向：预发布低于正式版"),
            arrayOf("0.1.1-beta", "0.1.1-beta", false, "带后缀且完全相同"),

            // ---- pre-release 标识符内部的比较 ----
            arrayOf("1.0.0-beta.2", "1.0.0-beta.1", true, "后缀内数字段按数值比"),
            arrayOf("1.0.0-beta.10", "1.0.0-beta.9", true, "后缀内 10 > 9，非字典序"),
            arrayOf("1.0.0-rc", "1.0.0-beta", true, "字母标识符按 ASCII 比"),
            arrayOf("1.0.0-beta.1", "1.0.0-beta", true, "前缀相同时标识符多的更高"),
            arrayOf("1.0.0-alpha", "1.0.0-1", true, "数字标识符低于字母标识符"),

            // ---- build metadata 不参与比较 ----
            arrayOf("1.0.0+build9", "1.0.0+build1", false, "build metadata 被忽略"),
            arrayOf("1.0.1+build1", "1.0.0+build9", true, "数字段仍然生效"),
            arrayOf("1.2.0.0", "1.2", false, "不足补零"),
            arrayOf("1.2.0.1", "1.2", true, "四段数字"),
            arrayOf("999999999999999999999", "2", true, "数字段不溢出"),
            arrayOf("1.0.0-999999999999999999999", "1.0.0-alpha", false, "大数字仍低于字母标识符"),
            arrayOf("1.0.0-1000000000000000000000", "1.0.0-999999999999999999999", true, "大数字按数值排序"),
            arrayOf("2.0.0-rc!", "1.0.0", false, "非法后缀字符"),
            arrayOf("2.0.0-beta.01", "1.0.0", false, "数字预发布标识符禁止前导零"),
            arrayOf("vV2.0.0", "1.0.0", false, "只允许单个前缀"),
            arrayOf("2.0.0+", "1.0.0", false, "空构建元数据非法"),
            arrayOf("2.0.0+build..1", "1.0.0", false, "空构建标识符非法"),
            arrayOf("2.01.0", "1.0.0", false, "核心数字禁止前导零"),
            arrayOf("2.0.0-汉字", "1.0.0", false, "预发布标识符限定 ASCII"),

            // ---- 畸形输入一律「无更新」：绝不能因为一个坏 tag 就弹窗 ----
            arrayOf("", "1.0.0", false, "空远端"),
            arrayOf("latest", "1.0.0", false, "非数字 tag"),
            arrayOf("1.0.0", "", false, "空本地"),
            arrayOf("1.x.0", "1.0.0", false, "数字段含字母"),
            arrayOf("1..0", "1.0.0", false, "空数字段"),
            arrayOf("1.0.0-", "1.0.0", false, "空 pre-release"),
            arrayOf("-1.0.0", "1.0.0", false, "负号开头"),
            arrayOf("2.0.0", "garbage", false, "本地畸形时也不提示")
        )
    }
}

/** 解析与边界。与上面的比较矩阵分开：这些判的是 [AppVersion.parse] 本身的契约。 */
class AppVersionParseTest {

    @Test
    fun parse_stripsPrefixAndBuildMetadata() {
        val version = AppVersion.parse("V1.2.3-rc.1+exp.sha.5114f85")

        assertEquals(listOf("1", "2", "3"), version?.numbers)
        // build metadata 按 semver 规定不参与比较，解析阶段就丢掉。
        assertEquals(listOf("rc", "1"), version?.preRelease)
    }

    @Test
    fun parse_acceptsTwoSegmentVersion() {
        // 本仓库真实发过 `v0.1-beta`，两段必须合法。
        val version = AppVersion.parse("v0.1-beta")

        assertEquals(listOf("0", "1"), version?.numbers)
        assertEquals(listOf("beta"), version?.preRelease)
    }

    @Test
    fun parse_keepsHyphenInsidePreRelease() {
        // 只在第一个 `-` 处切分，否则 `beta-1` 会被截成 `beta`。
        assertEquals(listOf("beta-1"), AppVersion.parse("1.0.0-beta-1")?.preRelease)
    }

    @Test
    fun parse_malformedInput_returnsNull() {
        for (raw in listOf(null, "", "   ", "v", "latest", "1.x", "1..2", "1.0.0-", "1.0.0-a..b")) {
            assertNull("应拒绝：$raw", AppVersion.parse(raw))
        }
    }

    @Test
    fun compareTo_isConsistentWithIsNewer() {
        // 比较器与 isNewer 必须同一套语义，否则将来有人直接用 compareTo 会得到另一种结论。
        val older = requireNonNull(AppVersion.parse("0.1-beta"))
        val newer = requireNonNull(AppVersion.parse("0.1.1-beta"))

        assertTrue(newer > older)
        assertFalse(older > newer)
        assertEquals(0, newer.compareTo(requireNonNull(AppVersion.parse("v0.1.1-beta"))))
    }

    private fun <T> requireNonNull(value: T?): T = requireNotNull(value)
}
