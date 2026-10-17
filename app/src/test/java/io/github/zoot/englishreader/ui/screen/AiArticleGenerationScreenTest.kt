package io.github.zoot.englishreader.ui.screen

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import io.github.zoot.englishreader.data.ai.AiArticleDifficulty
import io.github.zoot.englishreader.data.ai.AiArticleGenerationFailure
import io.github.zoot.englishreader.data.ai.AiArticleGenerationParameters
import io.github.zoot.englishreader.data.ai.AiArticleLength
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiGeneratedArticle
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.repository.AiArticleGenerationDraft
import io.github.zoot.englishreader.data.repository.AiArticleGenerationState
import io.github.zoot.englishreader.data.repository.AiArticleGenerationStatus
import io.github.zoot.englishreader.data.repository.AiArticlePreview
import io.github.zoot.englishreader.data.repository.AiArticleSaveStatus
import io.github.zoot.englishreader.ui.theme.ArticleUiTheme
import io.github.zoot.englishreader.viewmodel.AiArticleGenerationUiState
import io.github.zoot.englishreader.viewmodel.AiArticleGenerationViewModel
import io.github.zoot.englishreader.viewmodel.AiArticleReadRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiArticleGenerationScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val state = mutableStateOf(readyState())
    private var generated = 0
    private var settingsOpened = 0
    private var backed = 0
    private val saved = mutableListOf<String>()
    private val discarded = mutableListOf<String>()

    @Test
    fun form_missingProfile_blocksGenerationAndOffersSettings() {
        state.value = AiArticleGenerationUiState(profilesLoaded = true)
        render()
        composeRule.onNodeWithText("管理 AI 配置").performScrollTo().performClick()
        formTag("ai_article_generate").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, settingsOpened); assertEquals(0, generated) }
    }

    @Test
    fun form_editParametersAndTopic_updatesDraftAndEnforcesBudgetWithoutClipping() {
        render()
        formTag("ai_article_difficulty_ADVANCED").performClick()
        formTag("ai_article_length_LONG").performClick()
        composeRule.onNodeWithText("旅行").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(AiArticleGenerationParameters(AiArticleDifficulty.ADVANCED, AiArticleLength.LONG, "旅行"), state.value.session.draft.parameters)
        }
        formTag("ai_article_topic").performTextReplacement("x".repeat(501))
        formTag("ai_article_generate").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(501, state.value.session.draft.parameters.topic.length) }
        composeRule.onNodeWithText("清空主题").performScrollTo().performClick()
        formTag("ai_article_generate").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("", state.value.session.draft.parameters.topic); assertEquals(1, generated) }
    }

    @Test
    fun preview_editedDraft_keepsOriginalParametersAndReadOnlyBodyWithDeviationNotice() {
        state.value = readyState().copy(session = readyState().session.copy(
            draft = AiArticleGenerationDraft(AiArticleGenerationParameters(AiArticleDifficulty.ADVANCED, AiArticleLength.LONG, "New topic"), "p"),
            preview = preview()
        ))
        render()
        previewTag("ai_article_preview_parameters")
            .assertTextContains("初级", substring = true)
            .assertTextContains("Original topic", substring = true)
        composeRule.onNodeWithText("正文实际英文词数：7").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("±30%", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_article_content").performScrollToNode(hasTestTag("ai_article_save"))
        composeRule.onNodeWithTag("ai_article_save").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(listOf("result"), saved); assertEquals(0, generated) }
    }

    @Test
    fun regenerate_existingPreview_requiresExplicitCostAndReplacementConfirmation() {
        state.value = readyState().copy(session = readyState().session.copy(preview = preview()))
        render()
        formTag("ai_article_generate").performClick()
        composeRule.onNodeWithText("成功后新文章替换当前预览", substring = true).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, generated) }
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle { assertEquals(0, generated) }
        formTag("ai_article_generate").performClick()
        composeRule.onNodeWithTag("ai_article_confirm_regenerate").performClick()
        composeRule.onNodeWithTag("ai_article_confirm_regenerate").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, generated) }
    }

    @Test
    fun runningAndSaving_lockMutationsButKeepBackAvailable() {
        state.value = readyState().copy(session = readyState().session.copy(
            status = AiArticleGenerationStatus.Running("request"), preview = preview()
        ))
        render()
        listOf("ai_article_profile", "ai_article_difficulty_BEGINNER", "ai_article_length_SHORT", "ai_article_topic", "ai_article_generate")
            .forEach { formTag(it).assertIsNotEnabled() }
        previewTag("ai_article_save").assertIsNotEnabled()
        composeRule.onNodeWithTag("ai_article_discard").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.runOnIdle {
            assertEquals(1, backed)
            state.value = state.value.copy(session = state.value.session.copy(
                status = AiArticleGenerationStatus.Idle, preview = preview().copy(saveStatus = AiArticleSaveStatus.Saving)
            ))
        }
        formTag("ai_article_generate").assertIsNotEnabled()
        previewTag("ai_article_save").assertIsNotEnabled()
        composeRule.onNodeWithText("正在保存…").assertIsDisplayed()
    }

    @Test
    fun failures_showManualRetryCostAndLocalOnlySaveRetry() {
        state.value = readyState().copy(session = readyState().session.copy(
            status = AiArticleGenerationStatus.Failed("request", AiArticleGenerationFailure.Request(AiError.NoContent)),
            preview = preview().copy(saveStatus = AiArticleSaveStatus.Failed)
        ))
        render()
        composeRule.onNodeWithText("可能再次产生费用", substring = true).performScrollTo().assertIsDisplayed()
        previewTag("ai_article_save").assertIsEnabled()
        composeRule.onNodeWithText("重试保存并阅读").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("不会再次调用 AI", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf("result"), saved); assertEquals(0, generated) }
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun compactLargeText_keepsAllActionsReachableAndSavedStateDoesNotNavigate() {
        state.value = readyState().copy(session = readyState().session.copy(
            preview = preview().copy(saveStatus = AiArticleSaveStatus.Saved(42L))
        ))
        render(fontScale = 1.8f)
        formTag("ai_article_topic").assertIsDisplayed()
        formTag("ai_article_generate").assertIsDisplayed()
        previewTag("ai_article_save").assertIsDisplayed()
        composeRule.onNodeWithText("打开已保存文章").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), saved) }
        composeRule.onNodeWithTag("ai_article_discard").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf("result"), discarded) }
    }

    @Test
    fun profileSelection_existingProfiles_updatesOnlyTheFormSelection() {
        val original = readyState()
        state.value = original.copy(profiles = original.profiles + original.profiles.single().copy(
            profileId = "another", displayName = "Another profile"
        ))
        render()
        formTag("ai_article_profile").performClick()
        composeRule.onNodeWithText("Another profile").performClick()
        formTag("ai_article_profile").assertTextContains("Another profile")
        composeRule.runOnIdle {
            assertEquals("another", state.value.session.draft.profileId)
            assertEquals(0, generated)
        }
    }

    @Test
    fun readRequest_navigationFails_showsSafeRetryAndDoesNotDispatchAnotherSave() {
        val requests = MutableSharedFlow<AiArticleReadRequest>(extraBufferCapacity = 1)
        val viewModel = mockk<AiArticleGenerationViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(readyState())
        every { viewModel.events } returns requests
        every { viewModel.consumeReadRequest(any()) } returns 42L
        val opened = mutableListOf<Long>()
        composeRule.setContent {
            ArticleUiTheme {
                AiArticleGenerationScreen(
                    onBack = {}, onOpenAiProfile = {}, viewModel = viewModel,
                    onReadArticle = { id ->
                        opened += id
                        if (opened.size == 1) error("private navigation detail")
                    }
                )
            }
        }
        composeRule.runOnIdle {
            verify { viewModel.setScreenActive(true) }
            requests.tryEmit(AiArticleReadRequest("result", 42L))
        }
        composeRule.onNodeWithText("文章已保存，但未能打开阅读页。请再次点击打开，不会重复入库。").assertIsDisplayed()
        composeRule.onNodeWithText("private navigation detail").assertDoesNotExist()
        composeRule.runOnIdle { requests.tryEmit(AiArticleReadRequest("result", 42L)) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(listOf(42L, 42L), opened)
            verify(exactly = 0) { viewModel.saveAndRead(any()) }
        }
    }

    private fun render(fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ArticleUiTheme {
                    AiArticleGenerationContent(
                        state = state.value,
                        onBack = { backed++ }, onOpenAiProfile = { settingsOpened++ }, onReloadProfiles = {},
                        onSelectProfile = { id -> state.value = state.value.copy(session = state.value.session.copy(draft = state.value.session.draft.copy(profileId = id))) },
                        onTopicChange = { updateParameters { copy(topic = it) } },
                        onDifficultyChange = { updateParameters { copy(difficulty = it) } },
                        onLengthChange = { updateParameters { copy(length = it) } },
                        onGenerate = { generated++ }, onSaveAndRead = { saved += it }, onDiscard = { discarded += it }
                    )
                }
            }
        }
    }

    private fun updateParameters(update: AiArticleGenerationParameters.() -> AiArticleGenerationParameters) {
        val current = state.value
        state.value = current.copy(session = current.session.copy(draft = current.session.draft.copy(parameters = current.session.draft.parameters.update())))
    }

    private fun formTag(tag: String) = composeRule.onNodeWithTag("ai_article_content")
        .performScrollToIndex(0).let { composeRule.onNodeWithTag(tag).performScrollTo() }

    private fun previewTag(tag: String) = composeRule.onNodeWithTag("ai_article_content")
        .performScrollToNode(hasTestTag(tag)).let { composeRule.onNodeWithTag(tag).performScrollTo() }

    private fun readyState() = AiArticleGenerationUiState(
        session = AiArticleGenerationState(draft = AiArticleGenerationDraft(profileId = "p")),
        profiles = listOf(AiProviderProfile("p", "Test profile", AiProviderTemplate.OPENAI_COMPATIBLE, "https://example.com", "model", AiAuthStrategy.API_KEY, 0.7)),
        profilesLoaded = true
    )

    private fun preview() = AiArticlePreview(
        resultId = "result", profileId = "p",
        article = AiGeneratedArticle("A walk", "A short walk in the park.", AiArticleGenerationParameters(topic = "Original topic"), 7)
    )
}
