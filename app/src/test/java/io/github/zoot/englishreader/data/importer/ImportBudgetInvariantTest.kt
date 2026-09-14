package io.github.zoot.englishreader.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImportBudget] 常量之间的关系不变量。
 *
 * 这些常量互相之间有意图，而意图无法从数字本身读出来。本类把那些意图写成判据，让「有人为了
 * 图省事把两个不同来源的上限合成一个」这类改动在测试里可见。
 *
 * 它**不**断言任何上限的具体数值是对的——那需要真机渲染基准
 * （`ChapterRenderBenchmarkAndroidTest`，尚未跑过）。本类只管关系。
 */
class ImportBudgetInvariantTest {

    /**
     * 单章上限与单篇上限必须是**各自独立**的常量，不能一个写成另一个的别名。
     *
     * 两者约束来源不同：单篇上限管用户自己挑的文件（超限可换一篇），单章上限管出版方切好的
     * 章节（用户无从干预，只能换版本）。写成别名时这个差异被抹掉，后果是实测的：32 本语料
     * 只有 11 本可导入，而抬高章节上限需要连带抬高单篇上限，后者被 `AGENTS.md` 的不变量锁住
     * （真机长文章基准未完成前不得超过 40,000）。于是章节覆盖率被一个与它无关的约束挡着。
     *
     * 同值断言本身**抓不到**别名回退——Kotlin 里 `= MAX_IMPORT_CHARS` 与 `= 40_000` 在运行期
     * 完全不可区分。所以解耦这件事由
     * [chapterCeiling_isDeclaredAsItsOwnLiteral] 从源码层面守，本条只守「解耦没有顺手改掉数值」。
     */
    @Test
    fun chapterAndSingleArticleCeilings_areEqualToday() {
        assertEquals(
            "decoupling MAX_CHAPTER_CHARS was meant to be behaviour-preserving; " +
                "if these now differ, that is a real product change and ADR-013, " +
                "context.md and prd.md all quote the old figure",
            ImportBudget.MAX_IMPORT_CHARS,
            ImportBudget.MAX_CHAPTER_CHARS
        )
    }

    /**
     * `MAX_CHAPTER_CHARS` 必须声明为字面量，不能是 `MAX_IMPORT_CHARS` 的别名。
     *
     * 这是解耦唯一的真实守卫。运行期两种写法不可区分，所以只能读源码——本用例因此是本仓库里
     * 少见的、刻意读源文件的测试。代价是它绑定了文件路径；收益是「章节上限可独立校准」这个
     * 性质有了判据，而不是只写在 KDoc 里等人自觉。
     *
     * ## 为什么 `app/build.gradle.kts` 要把这个文件登记成测试任务的显式 input
     *
     * 别名与字面量在两个常量同值时编译出**逐字节相同**的产物（`const val` 在使用处内联）。
     * 所以默认配置下 Gradle 看不到输入变化，把 `testDebugUnitTest` 判为 up-to-date 直接跳过：
     * 改回别名后普通运行报 `failures=0`，只有加 `--rerun-tasks` 才红。
     *
     * 这不是能在测试内部修的问题 —— 它恰恰源于「被检测的改动对编译器不可见」，而那正是需要
     * 读源码的理由。修法在构建脚本里：`tasks.withType<Test>` 把 `ImportBudget.kt` 登记为
     * input，于是改这个文件必然让任务失效。实测普通运行即报红，不需要任何额外参数。
     *
     * 早先这里写的缓解办法是「CI 上跑 `--rerun-tasks`」—— 而这个仓库没有任何 CI workflow，
     * 那条缓解等于不存在。
     *
     * 路径按 `user.dir` 的上一级解析：单测工作目录是 `app/`。
     *
     * ## 判定方式：先剥注释再判值，不要拿整行去匹配
     *
     * 初版用 `Regex("""=\s*\d[\d_]*\s*$""")` 匹配整行，那是**太严**而非太松：它拒绝
     * `= 40_000 // 待真机校准` 和 `= 40_000;` —— 两者都是合法字面量。而 `ImportBudget.kt`
     * 到处是注释，给这一行加一句说明就会让构建报「必须是字面量」，而它本来就是。
     *
     * 现在的做法是剥掉行尾注释与分号，取 `=` 右侧的整个表达式，再要求它**只是**一个数字字面量。
     * 九种写法验过，零错判：三种字面量形式（裸、带注释、带分号）通过；
     * `= MAX_IMPORT_CHARS`、`= MAX_IMPORT_CHARS // 40_000`（注释误导）、
     * `= MAX_IMPORT_CHARS * 2`、`= ImportBudget.MAX_IMPORT_CHARS`、`= 40_000 + 0` 全部拒绝。
     */
    @Test
    fun chapterCeiling_isDeclaredAsItsOwnLiteral() {
        val source = java.io.File(
            System.getProperty("user.dir"),
            "src/main/java/io/github/zoot/englishreader/data/importer/ImportBudget.kt"
        )
        assertTrue("cannot read ${source.path}; did the file move?", source.isFile)

        val declaration = source.readLines()
            .firstOrNull { it.contains("const val MAX_CHAPTER_CHARS") }
            ?: error("MAX_CHAPTER_CHARS declaration not found in ${source.name}")

        // 剥行尾注释与分号，再取 `=` 右侧表达式。
        val value = declaration.substringBefore("//").trimEnd().trimEnd(';')
            .substringAfter('=', missingDelimiterValue = "")
            .trim()

        assertTrue(
            "MAX_CHAPTER_CHARS must be its own literal, not an alias of another budget. " +
                "Found value expression: '$value' in: ${declaration.trim()}. " +
                "Chapter ceilings and single-article ceilings have " +
                "different constraint sources -- a chapter boundary is the publisher's decision " +
                "and the user cannot change it -- so aliasing means raising one silently requires " +
                "raising the other, and MAX_IMPORT_CHARS is locked by an AGENTS.md invariant.",
            Regex("""^\d[\d_]*$""").matches(value)
        )
    }

    /**
     * 章节数上限与 spine 项数上限必须同值。
     *
     * 章节由 linear reading-order item 派生，所以两个数字若不一致，先触发的那个会让另一个
     * 永远不可达——等于埋一个死限制。`EpubBookParser` 里的 `MAX_BOOK_CHAPTERS` 校验正是
     * 因此不可达（preflight 已按 spine 数拦下），属深度防御而非第二道有效闸门。
     */
    @Test
    fun bookChapterCeiling_matchesSpineItemCeiling() {
        assertEquals(
            "MAX_BOOK_CHAPTERS and MAX_SPINE_ITEMS must agree, otherwise one of them is " +
                "permanently unreachable",
            ImportBudget.MAX_SPINE_ITEMS,
            ImportBudget.MAX_BOOK_CHAPTERS
        )
    }

    /**
     * 单段上限必须严格小于单章上限。
     *
     * 段落上限防的是「整章没有空行」的退化输入。若它大于等于单章上限，退化输入会先撞章节闸门，
     * 段落闸门永远不触发。实测语料里 16 段越过 8,000（最长 21,381，《尤利西斯》莫莉独白），
     * 全是真实散文——说明这道闸门确实在工作，而不是理论上的。
     */
    @Test
    fun paragraphCeiling_isStrictlyBelowChapterCeiling() {
        assertTrue(
            "MAX_PARAGRAPH_CHARS (${ImportBudget.MAX_PARAGRAPH_CHARS}) must stay below " +
                "MAX_CHAPTER_CHARS (${ImportBudget.MAX_CHAPTER_CHARS}); at or above it the " +
                "paragraph gate can never fire, because the chapter gate rejects first",
            ImportBudget.MAX_PARAGRAPH_CHARS < ImportBudget.MAX_CHAPTER_CHARS
        )
    }

    /**
     * 全书上限必须显著大于单章上限，否则「按章导入」失去意义。
     *
     * 取 10 倍作为下界：一本书至少要能装下十个满额章节，否则全书闸门会在正常长篇上先触发，
     * 用户看到「整本太大」而真因是「上限设得比一本书还小」。
     */
    @Test
    fun bookTextCeiling_leavesRoomForManyFullChapters() {
        assertTrue(
            "MAX_BOOK_TEXT_CHARS (${ImportBudget.MAX_BOOK_TEXT_CHARS}) must exceed ten full " +
                "chapters (${ImportBudget.MAX_CHAPTER_CHARS * 10}); below that the whole-book " +
                "gate fires on ordinary novels and blames the wrong thing",
            ImportBudget.MAX_BOOK_TEXT_CHARS > ImportBudget.MAX_CHAPTER_CHARS * 10
        )
    }

    /**
     * AI 全文解释上限必须小于单章上限。
     *
     * 它不是导入闸门——超限不拦截导入，只在成功提示里说明全文解释不可用。但若它大于等于单章
     * 上限，那句提示永远不会出现，而 `AiPromptPolicy` 里为它写的分支就成了死代码。
     */
    @Test
    fun fullExplanationCeiling_isBelowChapterCeiling() {
        assertTrue(
            "MAX_FULL_EXPLANATION_CHARS (${ImportBudget.MAX_FULL_EXPLANATION_CHARS}) must stay " +
                "below MAX_CHAPTER_CHARS (${ImportBudget.MAX_CHAPTER_CHARS}), otherwise the " +
                "'article too long for full explanation' path is unreachable",
            ImportBudget.MAX_FULL_EXPLANATION_CHARS < ImportBudget.MAX_CHAPTER_CHARS
        )
    }
}
