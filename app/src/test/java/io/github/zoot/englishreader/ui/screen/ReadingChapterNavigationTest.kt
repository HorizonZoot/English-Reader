package io.github.zoot.englishreader.ui.screen

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.model.SelectedSentence
import io.github.zoot.englishreader.viewmodel.ChapterContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 阅读页的章节导航栏。
 *
 * 这条栏是书和单篇文章在阅读页唯一的行为差异，所以两件事必须钉住：
 *  - 单篇文章绝不出现这条栏（否则一篇普通文章底部会多出两个无意义的箭头）；
 *  - 首末章按钮**禁用而非消失**。按钮位置固定，用户不会因为按钮消失而误触另一个；
 *    若改成隐藏，末章时「上一章」会滑到「下一章」原来的位置。
 *
 * 章号显示 +1 也在这里验证：内部 0 基，界面 1 基。少加这个 1，用户在第一章看到「第 0 章」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingChapterNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val article = ArticleEntity(
        id = 20L,
        title = "Chapter Two",
        content = "Body text.",
        translation = null
    )

    @Test
    fun standaloneArticle_hasNoChapterNavigationBar() {
        render(chapterContext = null)

        composeRule.onAllNodesWithContentDescription("上一章").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("下一章").assertCountEquals(0)
        composeRule.onAllNodesWithText("章 / 共", substring = true).assertCountEquals(0)
    }

    @Test
    fun middleChapter_bothDirectionsEnabledAndReportOneBasedPosition() {
        render(
            chapterContext = ChapterContext(
                bookId = 1L,
                chapterIndex = 1,
                chapterCount = 3,
                navigationTitle = "Chapter Two",
                previousArticleId = 10L,
                nextArticleId = 30L
            )
        )

        composeRule.onNodeWithContentDescription("上一章").assertIsEnabled()
        composeRule.onNodeWithContentDescription("下一章").assertIsEnabled()
        // 0 基的 chapterIndex=1 必须显示成「第 2 章」
        composeRule.onNodeWithText("第 2 章 / 共 3 章").assertIsDisplayed()
    }

    @Test
    fun firstChapter_disablesPreviousButKeepsItPresent() {
        render(
            chapterContext = ChapterContext(
                bookId = 1L,
                chapterIndex = 0,
                chapterCount = 3,
                navigationTitle = null,
                previousArticleId = null,
                nextArticleId = 30L
            )
        )

        // 存在但不可点：位置占住了，下一章按钮不会因此左移
        composeRule.onNodeWithContentDescription("上一章").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("下一章").assertIsEnabled()
        composeRule.onNodeWithText("第 1 章 / 共 3 章").assertIsDisplayed()
    }

    @Test
    fun lastChapter_disablesNextButKeepsItPresent() {
        render(
            chapterContext = ChapterContext(
                bookId = 1L,
                chapterIndex = 2,
                chapterCount = 3,
                navigationTitle = null,
                previousArticleId = 10L,
                nextArticleId = null
            )
        )

        composeRule.onNodeWithContentDescription("上一章").assertIsEnabled()
        composeRule.onNodeWithContentDescription("下一章").assertIsNotEnabled()
    }

    @Test
    fun navigation_emitsTargetArticleIdNotChapterIndex() {
        // 回调必须携带目标章节的 articleId：导航是按 articleId 走 reading/{articleId}
        // 路由的，传 chapterIndex 会打开一篇编号恰好相同的无关文章。
        var navigated: Long? = null
        render(
            chapterContext = ChapterContext(
                bookId = 1L,
                chapterIndex = 1,
                chapterCount = 3,
                navigationTitle = null,
                previousArticleId = 10L,
                nextArticleId = 30L
            ),
            onNavigateChapter = { navigated = it }
        )

        composeRule.onNodeWithContentDescription("下一章").performClick()
        composeRule.runOnIdle { assertEquals(30L, navigated) }

        composeRule.onNodeWithContentDescription("上一章").performClick()
        composeRule.runOnIdle { assertEquals(10L, navigated) }
    }

    @Test
    fun disabledEdgeButton_doesNotEmitNavigation() {
        var navigated: Long? = null
        render(
            chapterContext = ChapterContext(
                bookId = 1L,
                chapterIndex = 0,
                chapterCount = 2,
                navigationTitle = null,
                previousArticleId = null,
                nextArticleId = 30L
            ),
            onNavigateChapter = { navigated = it }
        )

        composeRule.onNodeWithContentDescription("上一章").performClick()
        composeRule.runOnIdle { assertNull(navigated) }
    }

    /**
     * 「第 N/M 章」必须是打开目录的入口。
     *
     * 这条对应一个真机反馈：用户点那个位置**没有任何反应**。它此前是纯 `Text` —— 不是回调
     * 坏了，是从来没接过。而在任何电子书阅读器里那个位置都是章节选择器，所以「点了没反应」
     * 读起来像功能坏了。目录页 `BookTocScreen` 一直存在，只是阅读页没有通往它的入口。
     *
     * 断言传的是 `ChapterContext.bookId`（7L）而不是 articleId：目录路由是 `book/{bookId}`，
     * 传错 id 会打开另一本书的目录，而那种错误在界面上看起来「有反应」，很容易被当成正常。
     */
    @Test
    fun chapterPosition_tapOpensTocForTheOwningBook() {
        var openedBookId: Long? = null
        render(
            chapterContext = ChapterContext(
                bookId = 7L,
                chapterIndex = 1,
                chapterCount = 5,
                navigationTitle = null,
                previousArticleId = 19L,
                nextArticleId = 21L
            ),
            onOpenToc = { openedBookId = it }
        )

        composeRule.onNodeWithText("第 2 章 / 共 5 章").performClick()

        composeRule.runOnIdle {
            assertEquals(7L, openedBookId)
        }
    }

    private fun render(
        chapterContext: ChapterContext?,
        onNavigateChapter: (Long) -> Unit = {},
        onOpenToc: (Long) -> Unit = {}
    ) {
        composeRule.setContent {
            ReadingScreenContent(
                article = article,
                selectedSentence = null as SelectedSentence?,
                selectedWord = null,
                isLoadingDefinition = false,
                isLoading = false,
                fontSizeOption = FontSizeOption.DEFAULT,
                showTranslation = false,
                snackbarHostState = remember { SnackbarHostState() },
                onToggleTranslation = {},
                onBack = {},
                onExplainSentence = {},
                onSentenceSelected = { _, _, _ -> },
                onWordLongPress = {},
                onClearSelection = {},
                chapterContext = chapterContext,
                onNavigateChapter = onNavigateChapter,
                onOpenToc = onOpenToc
            )
        }
    }
}
