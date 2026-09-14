package io.github.zoot.englishreader.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextExtractorTest {

    @Test
    fun extract_headingMarkers_preservesLiteralHashes() {
        // CommonMark 闭合井号前必须有空白；C# / F# 中的井号曾被 trimEnd('#') 误删。
        listOf(
            "# Chapter One" to "Chapter One",
            "### Sub Title ###" to "Sub Title",
            "I write C#" to "I write C#",
            "# Learning C#" to "Learning C#",
            "## Why F#" to "Why F#",
            "## Title ##" to "Title",
            "### Deep ######" to "Deep"
        ).forEach { (input, expected) ->
            assertEquals(input, expected, MarkdownTextExtractor.extract(input))
        }
    }

    @Test
    fun extract_emphasisDelimiters_respectFlankingAndRunLength() {
        // 标点和词内下划线影响 flanking；长 run 必须完整剥离；双反斜杠不转义闭合标记。
        listOf(
            "a**!**b" to "a**!**b",
            "a*!*b" to "a*!*b",
            "Call snake_case_name here." to "Call snake_case_name here.",
            "_italic_ text" to "italic text",
            "_foo_bar" to "_foo_bar",
            "***bold italic***" to "bold italic",
            "___strong em___" to "strong em",
            "**bold** and *italic* and ~~struck~~" to "bold and italic and struck",
            "**x\\\\**" to "x\\"
        ).forEach { (input, expected) ->
            assertEquals(input, expected, MarkdownTextExtractor.extract(input))
        }
    }

    @Test
    fun extract_onlyRemovableContent_throwsEmptyContent() {
        // 行尾直接结束的 ### 是空标题；整篇仅含标题或代码时不得作为正文导入。
        listOf("empty ATX heading" to "###", "code block only" to "```\njust code\n```").forEach { (case, input) ->
            val error = assertThrows(case, ImportException::class.java) {
                MarkdownTextExtractor.extract(input)
            }
            assertEquals(case, ImportFailure.EmptyContent, error.failure)
        }
    }

    @Test
    fun extract_emptyHeading_preservesSurroundingParagraphs() {
        listOf(
            "Intro.\n\n###\n\nBody." to listOf("Intro.", "Body."),
            "###\n\nBody text." to listOf("Body text.")
        ).forEach { (input, paragraphs) ->
            val result = MarkdownTextExtractor.extract(input)

            assertFalse("$input: 空标题不得留下字面井号，实际: $result", result.contains("#"))
            paragraphs.forEach { paragraph ->
                assertTrue("$input: 后续正文不得丢失，实际: $result", result.contains(paragraph))
            }
        }
    }

    @Test
    fun extract_headingWithTrailingHashesOnly_keepsFollowingParagraph() {
        val result = MarkdownTextExtractor.extract("# #\n\nBody text.")

        assertTrue("后续正文不得丢失，实际: $result", result.contains("Body text."))
    }

    @Test
    fun extract_inlineCode_preservesContentInsideMatchingDelimiters() {
        // 先去 HTML 标签会吞掉 <T>；双反引号内部的单反引号应是正文。
        listOf(
            "Use `List<T>` and call **map**." to "Use List<T> and call map.",
            "Use ``a`b`` here" to "Use a`b here"
        ).forEach { (input, expected) ->
            assertEquals(input, expected, MarkdownTextExtractor.extract(input))
        }
    }

    @Test
    fun extract_backslashEscapes_preservesLiteralCharacters() {
        // 只转义标点；\! 仅关闭图片语法，后续 [alt](url) 仍作为链接保留 label。
        listOf(
            "This is \\*not emphasis\\*." to "This is *not emphasis*.",
            "Open C:\\notes\\draft.txt now." to "Open C:\\notes\\draft.txt now.",
            "Literal \\![alt](img.png) here." to "Literal !alt here."
        ).forEach { (input, expected) ->
            assertEquals(input, expected, MarkdownTextExtractor.extract(input))
        }
    }

    @Test
    fun extract_invalidFenceCloser_keepsCodeHiddenAndFollowingText() {
        // 关闭符须独占一行、最多缩进 3 个 ASCII 空格；tab / U+2003 不能被 trim 成关闭符。
        listOf(
            Triple("trailing content", "```not-a-close", "\nAfter."),
            Triple("four spaces", "    ```", "\n\nAfter."),
            Triple("tab indentation", "\t```", "\n\nAfter."),
            Triple("Unicode indentation", "\u2003```", "\n\nAfter.")
        ).forEach { (case, invalidCloser, suffix) ->
            val input = "Before.\n\n```\nsecret\n$invalidCloser\nLEAK\n```$suffix"
            val result = MarkdownTextExtractor.extract(input)

            assertFalse("$case: 围栏内容不得泄漏，实际: $result", result.contains("LEAK"))
            assertFalse("$case: secret", result.contains("secret"))
            assertTrue("$case: Before", result.contains("Before."))
            assertTrue("$case: 后续正文不得被吞，实际: $result", result.contains("After."))
        }
    }

    @Test
    fun extract_validFenceCloser_removesCodeAndPreservesSurroundingText() {
        listOf(
            "longer delimiter run" to "`````",
            "three-space indentation" to "   ```",
            "trailing ASCII spaces" to "```   "
        ).forEach { (case, closer) ->
            val result = MarkdownTextExtractor.extract("A.\n\n```\ncode\n$closer\n\nB.")

            assertFalse("$case: code", result.contains("code"))
            assertTrue("$case: A", result.contains("A."))
            assertTrue("$case: B", result.contains("B."))
        }
    }

    @Test
    fun extract_inlineCodeNotClosedByLongerTickRun() {
        // ``code``` 里三反引号不能闭合双反引号——CommonMark 要求长度完全相同。
        // 朴素的 indexOf("``") 会命中前两个，留下一个游离反引号。
        val md = "Before ``code``` after"

        val result = MarkdownTextExtractor.extract(md)

        // 未找到等长闭合符 → 整串反引号按普通字符原样保留，正文不丢
        assertTrue("正文不得丢失，实际: $result", result.contains("code"))
        assertTrue(result.contains("Before"))
        assertTrue(result.contains("after"))
    }

    @Test
    fun extract_escapedLinkIsPreserved() {
        // 转义的起始标记是字面文本，剥掉方括号会连 URL 一起丢失
        val md = "This is \\[not a link](https://example.com)."

        val result = MarkdownTextExtractor.extract(md)

        assertTrue("转义链接须原样保留，实际: $result", result.contains("[not a link]"))
        assertTrue("URL 不得丢失，实际: $result", result.contains("https://example.com"))
    }

    @Test
    fun extract_realLinkAfterDoubleBackslashIsStillStripped() {
        // \\ 是转义的反斜杠，其后的 [ 未被转义 → 仍是真链接
        val md = "Path C:\\\\ then [docs](url) here."

        val result = MarkdownTextExtractor.extract(md)

        assertTrue("真链接应被剥离为 label，实际: $result", result.contains("docs"))
        assertFalse(result.contains("[docs]"))
    }

    @Test
    fun extract_privateUseCharInContentDoesNotCollideWithPlaceholder() {
        // 占位符哨兵若固定，正文里本就含该私有区字符时会被误认成占位符，
        // 替换成代码内容 → 正文被篡改。哨兵须按输入动态选取。
        val collide = "\uE0000"
        val md = "Text $collide and `realCode` end."

        val result = MarkdownTextExtractor.extract(md)

        assertTrue("原有私有区字符须保留，实际: $result", result.contains(collide))
        assertTrue("行内代码须正常恢复，实际: $result", result.contains("realCode"))
    }

    @Test
    fun extract_validFenceOpener_removesInfoStringAndCode() {
        data class Case(
            val name: String,
            val input: String,
            val excluded: List<String>,
            val visible: List<String> = listOf("Before.", "After.")
        )
        val cases = listOf(
            Case("indented backticks", "Before.\n\n   ```kotlin\nval leaked = 1\n   ```\n\nAfter.", listOf("val leaked", "kotlin")),
            Case("tilde with backtick", "Before.\n\n~~~foo`bar\nsecret\n~~~\n\nAfter.", listOf("secret")),
            Case("plain info string", "Before.\n\n```kotlin\nval x = 1\n```\n\nAfter.", listOf("val x = 1")),
            Case("multi-line code", "Before text.\n\n```kotlin\nval x = 1\nfun main() {}\n```\n\nAfter text.",
                listOf("val x = 1"), listOf("Before text.", "After text."))
        )
        cases.forEach { (name, input, excluded, visible) ->
            val result = MarkdownTextExtractor.extract(input)

            excluded.forEach { text -> assertFalse("$name: $text 不得泄漏，实际: $result", result.contains(text)) }
            visible.forEach { text -> assertTrue("$name: $text 不得丢失，实际: $result", result.contains(text)) }
        }
    }

    @Test
    fun extract_backtickInBacktickFenceInfo_doesNotOpenFence() {
        // 反引号围栏的 info string 不得含反引号，缩进也不能改变此规则。
        listOf(
            "indented" to "Before.\n\n   ```foo`bar\nThis is normal text.\n\nAfter.",
            "unindented" to "Before.\n\n```foo`bar\nThis is normal text.\nAfter.\n"
        ).forEach { (case, input) ->
            val result = MarkdownTextExtractor.extract(input)

            assertTrue("$case: 正文不得被吞，实际: $result", result.contains("This is normal text."))
            assertTrue("$case: After", result.contains("After."))
            assertTrue("$case: Before", result.contains("Before."))
        }
    }

    @Test
    fun extract_unpairedOrEscapedEmphasis_keepsLiteralMarker() {
        // 紧邻空白的定界符不能开合；单反斜杠转义闭合标记；未配对的乘号仍是正文。
        listOf(
            "**x\\**" to "**",
            "see * foo* here" to "*",
            "see *foo * here" to "*",
            "2 * 3 = 6" to "*"
        ).forEach { (input, marker) ->
            val result = MarkdownTextExtractor.extract(input)

            assertTrue("$input: 未闭合的标记必须保留，实际: $result", result.contains(marker))
        }
    }

    @Test
    fun extract_fenceNotClosedByRunWithUnicodeSpaceSuffix() {
        // 尾部同理：关闭符后只允许 ASCII space/tab
        val md = "A.\n\n```\ncode\n```\u00A0\nLEAK\n```\n\nB."

        val result = MarkdownTextExtractor.extract(md)

        assertFalse("NBSP 结尾的 ``` 不是关闭符，实际: $result", result.contains("LEAK"))
        assertTrue(result.contains("B."))
    }

    @Test
    fun extract_paragraphSeparators_survivePlainTextAndFenceRemoval() {
        // ParagraphAligner 按空行分段；去掉围栏不能把相邻段落粘连。
        listOf(
            "First paragraph.\n\nSecond paragraph.",
            "Para one.\n\n```\ncode\n```\n\nPara two."
        ).forEach { input ->
            val result = MarkdownTextExtractor.extract(input)

            assertTrue("$input: 段落分隔的空行必须保留，实际: $result", result.contains("\n\n"))
            assertEquals(input, 2, result.split(Regex("\\n\\s*\\n")).size)
        }
    }

    @Test
    fun extract_linkKeepsLabelImageKeepsAlt() {
        assertEquals(
            "See the docs for details.",
            MarkdownTextExtractor.extract("See the [docs](https://example.com) for details.")
        )
        assertEquals(
            "A cat photo",
            MarkdownTextExtractor.extract("![A cat photo](cat.png)")
        )
    }

    @Test
    fun extract_listMarkersRemovedItemsRemainSeparateLines() {
        val md = "- first item\n- second item\n- third item"

        val result = MarkdownTextExtractor.extract(md)

        assertFalse(result.contains("- "))
        assertTrue(result.contains("first item"))
        assertTrue(result.contains("second item"))
    }

    @Test
    fun extract_blockquoteMarkerRemoved() {
        assertEquals("quoted text", MarkdownTextExtractor.extract("> quoted text"))
    }

    @Test
    fun extract_unknownSyntaxPreservedVerbatim() {
        // 承诺：未识别语法一律原样保留，不做破坏性猜测
        val md = "Footnote ref[^1] and {{template}} and <custom-tag attr=\"v\">"

        val result = MarkdownTextExtractor.extract(md)

        assertTrue(result.contains("[^1]"))
        assertTrue(result.contains("{{template}}"))
        assertTrue(result.contains("<custom-tag"))
    }

    @Test
    fun extract_unclosedBacktickIsPlainText() {
        val result = MarkdownTextExtractor.extract("a ` b c")

        assertTrue("未闭合反引号是普通字符，实际: $result", result.contains("`"))
    }
}
