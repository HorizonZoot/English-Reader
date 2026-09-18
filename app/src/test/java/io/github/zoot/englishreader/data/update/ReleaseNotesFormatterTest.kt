package io.github.zoot.englishreader.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesFormatterTest {

    @Test
    fun underscoreEmphasis_keepsIdentifierUnderscores() {
        assertEquals("italic and snake_case", ReleaseNotesFormatter.format("_italic_ and snake_case"))
    }

    @Test
    fun onlyMarkdownMarkers_returnsEmptyForUiFallback() {
        assertEquals("", ReleaseNotesFormatter.format("##\n\n***\n---\n___"))
    }

    @Test
    fun plainText_isKeptAsIs() {
        val raw = "Improved TTS playback\nOptimized article import"

        assertEquals(raw, ReleaseNotesFormatter.format(raw))
    }

    @Test
    fun headings_loseTheirMarkersButKeepTheText() {
        val formatted = ReleaseNotesFormatter.format("## What's New\n### Details")

        assertEquals("What's New\nDetails", formatted)
        assertFalse("不得残留 # 标记", formatted.contains('#'))
    }

    @Test
    fun bullets_areNormalisedToASingleSymbol() {
        // 原文里 -、*、+ 混用很常见，混着显示像是有语义差别。
        val formatted = ReleaseNotesFormatter.format("- one\n* two\n+ three")

        assertEquals("• one\n• two\n• three", formatted)
    }

    @Test
    fun nestedBullets_keepTheirIndentation() {
        val formatted = ReleaseNotesFormatter.format("- top\n  - nested")

        assertEquals("• top\n  • nested", formatted)
    }

    @Test
    fun emphasisAndInlineCode_loseMarkersButKeepText() {
        val formatted = ReleaseNotesFormatter.format("**bold** and __also__ and *italic* and `code`")

        assertEquals("bold and also and italic and code", formatted)
    }

    /**
     * 单个下划线**不**按强调处理。
     *
     * `snake_case` 这类写法在 Release Notes 里比 `_强调_` 常见得多，按强调剥掉会把标识符
     * 改坏——那比留一个下划线糟得多。
     */
    @Test
    fun singleUnderscore_isLeftAloneSoIdentifiersSurvive() {
        val formatted = ReleaseNotesFormatter.format("修复 snake_case 与 MAX_CHAPTER_CHARS")

        assertEquals("修复 snake_case 与 MAX_CHAPTER_CHARS", formatted)
    }

    /** `#1` 是 issue 号，不是 heading——`#` 后必须跟空白才算标记。 */
    @Test
    fun hashWithoutSpace_isNotTreatedAsHeading() {
        assertEquals("#1 已修复", ReleaseNotesFormatter.format("#1 已修复"))
    }

    @Test
    fun excessiveBlankLines_areCollapsed() {
        val formatted = ReleaseNotesFormatter.format("first\n\n\n\n\nsecond")

        assertEquals("first\n\nsecond", formatted)
    }

    @Test
    fun surroundingWhitespace_isTrimmed() {
        assertEquals("body", ReleaseNotesFormatter.format("\n\n  body  \n\n"))
    }

    @Test
    fun emptyOrBlankBody_returnsEmptyString() {
        for (raw in listOf(null, "", "   ", "\n\n\t")) {
            assertEquals("应返回空串：${raw?.let { "\"$it\"" }}", "", ReleaseNotesFormatter.format(raw))
        }
    }

    @Test
    fun longBody_isNeitherTruncatedNorThrowing() {
        // 超长 body 不截断：截断会在滚动区里制造一个看不出原因的断点。
        val raw = (1..2_000).joinToString("\n") { "- 修复第 $it 个问题" }

        val formatted = ReleaseNotesFormatter.format(raw)

        assertEquals(2_000, formatted.lines().size)
        assertTrue(formatted.startsWith("• 修复第 1 个问题"))
        assertTrue(formatted.endsWith("• 修复第 2000 个问题"))
    }

    @Test
    fun inlineCode_preservesLiteralMarkersAndSurroundingEmphasis() {
        val cases = listOf(
            "Fix `a*b*c` rendering" to "Fix a*b*c rendering",
            "Keep `__init__` literal" to "Keep __init__ literal",
            "**Use `a*b*c` now**" to "Use a*b*c now",
            "_Use `__init__` now_" to "Use __init__ now",
            "Use ``a`b*c`` and `__init__`" to "Use a`b*c and __init__",
            "Keep `unclosed **bold**" to "Keep `unclosed bold",
            "Keep ${0.toChar()}0${0.toChar()} and `a*b*c`" to
                "Keep ${0.toChar()}0${0.toChar()} and a*b*c"
        )
        for ((raw, expected) in cases) {
            assertEquals(raw, expected, ReleaseNotesFormatter.format(raw))
        }
    }

    @Test
    fun fencedCode_preservesContentsAndResumesCleaningAfterClosingFence() {
        for (fence in listOf("```", "~~~~")) {
            val code = listOf(
                "${fence}text", "# config comment", "- argument  ", "---",
                "", "", "", "__init__ a*b*c", fence
            ).joinToString("\n")
            assertEquals(
                "$code\nAfter\n• item",
                ReleaseNotesFormatter.format("$code\n## After ##\n- **item**")
            )
        }
    }

    @Test
    fun fencedCode_onlyMatchingClosingFenceEndsProtection() {
        val code = listOf(
            "  ````text", "```", "~~~~", "```` not a closer", "# still code",
            "  `````  "
        ).joinToString("\n")
        assertEquals(
            "Before\n$code\nAfter",
            ReleaseNotesFormatter.format("## Before\n$code\n## After")
        )
        val unclosed = "```text\n# code\n- argument\n__init__"
        assertEquals(unclosed, ReleaseNotesFormatter.format(unclosed))
    }

    @Test
    fun headings_removeOnlyWhitespaceSeparatedClosingHashes() {
        val cases = listOf(
            "## Changes ##" to "Changes",
            "### Changes ###   " to "Changes",
            "## C#" to "C#",
            "## issue #123" to "issue #123",
            "## Changes##" to "Changes##",
            "## #" to "",
            "## `literal ##` ###" to "literal ##"
        )
        for ((raw, expected) in cases) {
            assertEquals(raw, expected, ReleaseNotesFormatter.format(raw))
        }
    }

    @Test
    fun longSingleLineRules_doNotOverflowOrConsumeOrdinaryText() {
        for (marker in listOf('-', '*', '_')) {
            assertEquals("", ReleaseNotesFormatter.format(marker.toString().repeat(64_000)))
            assertEquals("", ReleaseNotesFormatter.format("$marker ".repeat(16_000)))
        }
        val plain = "a".repeat(64_000)
        assertEquals(plain, ReleaseNotesFormatter.format(plain))
        assertEquals("--x", ReleaseNotesFormatter.format("--x"))
        assertEquals("--", ReleaseNotesFormatter.format("--"))
    }

    @Test
    fun markdownNoise_isCleanedInOnePass() {
        val raw = """
            ## What's New

            - Improved **TTS** playback
            - Optimized `article` import


            ### Fixes
            * Fixed several bugs
        """.trimIndent()

        assertEquals(
            "What's New\n\n• Improved TTS playback\n• Optimized article import\n\nFixes\n• Fixed several bugs",
            ReleaseNotesFormatter.format(raw)
        )
    }

    /** 不做完整 Markdown：链接、代码块、引用原样保留仍然可读。 */
    @Test
    fun unhandledConstructs_arePassedThroughUnchanged() {
        val raw = "> quoted\n[link](https://example.com)\n| a | b |"

        assertEquals(raw, ReleaseNotesFormatter.format(raw))
    }
}
