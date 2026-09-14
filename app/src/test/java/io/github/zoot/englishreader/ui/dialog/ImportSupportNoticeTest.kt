package io.github.zoot.englishreader.ui.dialog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 导入入口的能力预期说明。
 *
 * ## 为什么这条文案需要判据
 *
 * 整本 EPUB 只覆盖约三分之一的真实公版书（32 本语料 11 本可导入，见 ADR-013），而失败与否
 * 取决于制作方怎么分章 —— 用户对此无从干预。若入口只写「从文件导入（TXT / Markdown / EPUB）」，
 * 用户会合理推断任意 EPUB 都能导入，然后连试几本失败，并在那个时候更倾向于认为 app 坏了
 * 而不是版本不合。
 *
 * 单次失败提示（`ChapterTooLong`）已经准确，但它出现得太晚：预期管理必须在**选择导入方式**
 * 的决策点上，不能只在失败之后。
 *
 * 这个类钉的是「说明确实出现在决策点上」。它不校验措辞，只校验存在与位置 —— 文案会改，
 * 而「入口处有能力边界说明」这个性质不该随文案一起消失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportSupportNoticeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun importDialog_showsEpubLimitationAndRemedyAtTheChoicePoint() {
        composeRule.setContent {
            ImportDialog(onDismiss = {}, onImportFile = {}, onImportPaste = {})
        }

        // 与「从文件导入」按钮同屏：说明必须与它所限定的那个选项一起可见，
        // 分屏或折叠起来就失去了作用。
        composeRule.onNodeWithText("从文件导入").assertIsDisplayed()
        composeRule.onNodeWithText("TXT / Markdown / EPUB").assertIsDisplayed()
        composeRule.onNodeWithText("分章", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("换一个版本", substring = true).assertIsDisplayed()
    }

    @Test
    fun importDialog_choicesDismissThenDispatchOnce() {
        val shown = mutableStateOf(true)
        val events = mutableListOf<String>()
        composeRule.setContent {
            if (shown.value) ImportDialog(
                onDismiss = { events += "dismiss"; shown.value = false },
                onImportFile = { events += "file" },
                onImportPaste = { events += "paste" }
            )
        }

        listOf("file", "paste").forEach { choice ->
            composeRule.runOnIdle { shown.value = true; events.clear() }
            composeRule.onNodeWithTag("import-choice-$choice")
                .performSemanticsAction(SemanticsActions.OnClick) { click -> click(); click() }
            composeRule.runOnIdle { assertEquals(choice, listOf("dismiss", choice), events) }
            composeRule.onNodeWithTag("import-dialog").assertDoesNotExist()
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h400dp")
    fun importDialog_compactLargeText_keepsChoicesNoticeAndCloseReachable() {
        var dismissed = 0
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                ImportDialog(onDismiss = { dismissed++ }, onImportFile = {}, onImportPaste = {})
            }
        }

        composeRule.onNodeWithTag("import-choice-file").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("import-choice-paste").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("分章", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("取消").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }
}
