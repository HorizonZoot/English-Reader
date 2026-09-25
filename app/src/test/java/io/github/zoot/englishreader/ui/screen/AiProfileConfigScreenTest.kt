package io.github.zoot.englishreader.ui.screen

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.zoot.englishreader.data.ai.AiClient
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.AiModelDiscoveryDraft
import io.github.zoot.englishreader.data.ai.AiModelDiscoveryResult
import io.github.zoot.englishreader.data.local.AiAuthStrategy
import io.github.zoot.englishreader.data.local.AiProviderProfile
import io.github.zoot.englishreader.data.local.AiProviderTemplate
import io.github.zoot.englishreader.data.local.FontSizeOption
import io.github.zoot.englishreader.data.local.SettingsPreferences
import io.github.zoot.englishreader.data.local.ThemeOption
import io.github.zoot.englishreader.data.repository.AiProfileRepository
import io.github.zoot.englishreader.data.repository.ExplanationCacheRepository
import io.github.zoot.englishreader.data.repository.ProfileMutationResult
import io.github.zoot.englishreader.viewmodel.SettingsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiProfileConfigScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyState_bothAddEntriesOpenTheSharedEditor() {
        val harness = harness()
        setScreen(harness)

        composeTestRule.onNodeWithTag("profile_add_empty").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("新增 AI 配置").assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_display_name").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("取消").performClick()

        composeTestRule.onNodeWithTag("profile_add_top").performClick()
        composeTestRule.onNodeWithText("新增 AI 配置").assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_editor_save").assertIsNotEnabled()
    }

    @Test
    fun editor_connectionFields_haveTheSameSpacingAsProviderFields() {
        setScreen(harness())
        composeTestRule.onNodeWithTag("profile_add_empty").performClick()

        val provider = composeTestRule.onNodeWithTag("profile_provider_selector")
            .getUnclippedBoundsInRoot()
        val name = composeTestRule.onNodeWithTag("profile_display_name")
            .getUnclippedBoundsInRoot()
        val url = composeTestRule.onNodeWithTag("profile_base_url").getUnclippedBoundsInRoot()
        val model = composeTestRule.onNodeWithTag("profile_model_id").getUnclippedBoundsInRoot()
        val key = composeTestRule.onNodeWithTag("profile_api_key").getUnclippedBoundsInRoot()
        val providerGap = (name.top - provider.bottom).value

        assertEquals(12f, providerGap, 0.5f)
        assertEquals(providerGap, (model.top - url.bottom).value, 0.5f)
        assertEquals(providerGap, (key.top - model.bottom).value, 0.5f)
    }

    @Test
    fun providerTemplates_applyCurrentDefaultsAndCompatibleClearsManualIdentity() {
        val harness = harness()
        setScreen(harness)
        composeTestRule.onNodeWithTag("profile_add_empty").performClick()

        composeTestRule.onNodeWithTag("profile_base_url")
            .assertEditableTextEquals("https://api.deepseek.com")
        composeTestRule.onNodeWithTag("profile_model_id")
            .assertEditableTextEquals("deepseek-v4-flash")

        composeTestRule.onNodeWithTag("profile_base_url").performTextClearance()
        composeTestRule.onNodeWithTag("profile_base_url")
            .performTextInput("https://custom.example.com")
        composeTestRule.onNodeWithTag("profile_provider_selector").performClick()
        composeTestRule.onNodeWithTag("profile_provider_option_DEEPSEEK").assertExists()
        composeTestRule.onNodeWithTag("profile_provider_option_KIMI").assertExists()
        composeTestRule.onNodeWithTag("profile_provider_option_ZHIPU").assertExists()
        composeTestRule.onNodeWithTag("profile_provider_option_OPENAI_COMPATIBLE")
            .assertExists()
        composeTestRule.onNodeWithTag("profile_provider_option_KIMI").performClick()
        composeTestRule.onNodeWithTag("profile_base_url")
            .assertEditableTextEquals("https://api.moonshot.ai/v1")
        composeTestRule.onNodeWithTag("profile_model_id").assertEditableTextEquals("kimi-k3")

        composeTestRule.onNodeWithTag("profile_provider_selector").performClick()
        composeTestRule.onNodeWithTag("profile_provider_option_ZHIPU").performClick()
        composeTestRule.onNodeWithTag("profile_base_url")
            .assertEditableTextEquals("https://open.bigmodel.cn/api/paas/v4/")
        composeTestRule.onNodeWithTag("profile_model_id").assertEditableTextEquals("glm-5.2")

        composeTestRule.onNodeWithTag("profile_provider_selector").performClick()
        composeTestRule.onNodeWithTag("profile_provider_option_OPENAI_COMPATIBLE").performClick()
        composeTestRule.onNodeWithTag("profile_base_url").assertEditableTextEquals("")
        composeTestRule.onNodeWithTag("profile_model_id").assertEditableTextEquals("")
    }

    @Test
    fun configuredProfiles_cardSelectsAndEditNeverPrefillsStoredKey() {
        val first = profile("profile-1", "DeepSeek primary", AiProviderTemplate.DEEPSEEK)
        val second = profile("profile-2", "Kimi backup", AiProviderTemplate.KIMI)
        val harness = harness(listOf(first, second), activeProfileId = first.profileId)
        coEvery { harness.repository.selectActiveProfile(second.profileId) } returns
            ProfileMutationResult.Success
        setScreen(harness)

        composeTestRule.onNodeWithTag("profile_card_${second.profileId}").performClick()
        composeTestRule.waitForIdle()
        coVerify(exactly = 1) { harness.repository.selectActiveProfile(second.profileId) }

        composeTestRule.onNodeWithTag("profile_manage_${first.profileId}").performClick()
        composeTestRule.onNodeWithText("编辑配置").performClick()
        composeTestRule.onNodeWithText("编辑 AI 配置").assertIsDisplayed()
        // 唯一的真实判据：编辑器打开时 key 输入框必须是空的。
        //
        // 这里曾经还有一行 `onAllNodesWithText("stored-secret").assertCountEquals(0)`，
        // 它不可能失败：`stored-secret` 从未出现在本测试里，`AiProviderProfile` 根本没有
        // key 字段（见 profile()），repository 是 relaxed mock 只会返回空串。
        // 那行删掉了——它给的是虚假信心，而不是额外保护。
        composeTestRule.onNodeWithTag("profile_api_key").assertEditableTextEquals("")

        composeTestRule.onNodeWithTag("profile_provider_selector").performClick()
        composeTestRule.onNodeWithTag("profile_provider_option_KIMI").performClick()
        composeTestRule.onNodeWithTag("profile_editor_save").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("profile_api_key").performTextInput("replacement-key")
        composeTestRule.onNodeWithTag("profile_editor_save").assertIsNotEnabled()
    }

    @Test
    fun profileManageClick_opensActionsWithoutSelectingCard() {
        val first = profile("profile-1", "Primary")
        val second = profile("profile-2", "Backup")
        val harness = harness(listOf(first, second), activeProfileId = first.profileId)
        setScreen(harness)

        composeTestRule.onNodeWithTag("profile_manage_${second.profileId}").performClick()
        composeTestRule.onNodeWithText("编辑配置").assertIsDisplayed()

        coVerify(exactly = 0) { harness.repository.selectActiveProfile(any()) }
    }

    @Test
    fun profileDelete_requiresConfirmationBeforeMutation() {
        val target = profile("profile-1", "Primary")
        val harness = harness(listOf(target), activeProfileId = target.profileId)
        coEvery { harness.repository.deleteProfile(target.profileId) } returns
            ProfileMutationResult.Success
        setScreen(harness)

        composeTestRule.onNodeWithTag("profile_manage_${target.profileId}").performClick()
        composeTestRule.onNodeWithText("删除").performClick()
        composeTestRule.onNodeWithText("删除此 AI 配置？").assertIsDisplayed()
        coVerify(exactly = 0) { harness.repository.deleteProfile(target.profileId) }

        composeTestRule.onNodeWithText("删除").performClick()
        composeTestRule.waitForIdle()
        coVerify(exactly = 1) { harness.repository.deleteProfile(target.profileId) }
    }

    @Test
    fun globalActions_requireConfirmationBeforeMutation() {
        val harness = harness(listOf(profile("profile-1", "Primary")), "profile-1")
        coEvery { harness.cacheRepository.clearAllCache() } returns 3
        coEvery { harness.repository.deleteAllProfiles() } returns ProfileMutationResult.Success
        setScreen(harness)

        composeTestRule.onNodeWithText("清除全部 AI 缓存").performScrollTo().performClick()
        composeTestRule.onNodeWithText("确认清除全部 AI 缓存？").assertIsDisplayed()
        coVerify(exactly = 0) { harness.cacheRepository.clearAllCache() }
        composeTestRule.onNodeWithText("确认清除").performClick()
        composeTestRule.waitForIdle()
        coVerify(exactly = 1) { harness.cacheRepository.clearAllCache() }

        composeTestRule.onNodeWithText("删除所有 AI 配置").performScrollTo().performClick()
        composeTestRule.onNodeWithText("删除所有 AI 配置？").assertIsDisplayed()
        coVerify(exactly = 0) { harness.repository.deleteAllProfiles() }
        composeTestRule.onNodeWithText("全部删除").performClick()
        composeTestRule.waitForIdle()
        coVerify(exactly = 1) { harness.repository.deleteAllProfiles() }
    }

    @Test
    fun discoverModels_blankModel_loadsCatalogWithoutFeeConfirmationAndKeepsSelections() {
        val harness = harness()
        coEvery { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) } returns
            AiModelDiscoveryResult.Success(listOf("deepseek-v4-flash", "other-model"))
        coEvery { harness.repository.createProfile(any(), any()) } returns ProfileMutationResult.Success
        setScreen(harness)

        composeTestRule.onNodeWithTag("profile_add_empty").performClick()
        composeTestRule.onNodeWithTag("profile_model_id").performTextClearance()
        composeTestRule.onNodeWithTag("profile_api_key").performTextInput("sk-draft")
        composeTestRule.onNodeWithTag("profile_editor_test_connection")
            .performScrollTo()
            .performClick()

        composeTestRule.onNodeWithText("确认测试连接").assertDoesNotExist()
        composeTestRule.onNodeWithTag("profile_model_id").assertEditableTextEquals("")
        composeTestRule.waitForIdle()

        coVerify(exactly = 1) { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) }
        coVerify(exactly = 0) { harness.aiClient.testConnectionDraft(any()) }
        coVerify(exactly = 0) { harness.aiClient.testConnection(any(), any()) }
        coVerify(exactly = 0) { harness.repository.createProfile(any(), any()) }
        composeTestRule.onNodeWithTag("profile_editor_test_result")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("连接成功，已获取 2 个模型").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_editor_save").assertIsEnabled()
        composeTestRule.onNodeWithTag("profile_model_dropdown").performScrollTo().performClick()
        composeTestRule.onNodeWithText("other-model").performClick()
        composeTestRule.onNodeWithTag("profile_model_id").assertEditableTextEquals("other-model")
        composeTestRule.onNodeWithTag("profile_model_dropdown").performClick()
        composeTestRule.onNodeWithText("deepseek-v4-flash").performClick()
        composeTestRule.onNodeWithTag("profile_model_id").assertEditableTextEquals("deepseek-v4-flash")
        composeTestRule.onNodeWithTag("profile_model_id").performTextClearance()
        composeTestRule.onNodeWithTag("profile_model_id").performTextInput("custom-alias")
        composeTestRule.onNodeWithTag("profile_model_dropdown").assertExists()
        composeTestRule.onNodeWithText("未在返回列表中找到，尚未验证其可调用性；仍可手动保存。")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_editor_save").assertIsEnabled()
        composeTestRule.onNodeWithTag("profile_editor_save").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        coVerify(exactly = 1) { harness.repository.createProfile(match { it.modelId == "custom-alias" }, "sk-draft") }
        coVerify(exactly = 1) { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) }
    }

    @Test
    fun discoverModels_keyOrProviderChange_clearsCatalogAndDisablesSave() {
        val harness = harness()
        coEvery { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) } returns
            AiModelDiscoveryResult.Success(listOf("deepseek-v4-flash"))
        setScreen(harness)
        composeTestRule.onNodeWithTag("profile_add_empty").performClick()
        composeTestRule.onNodeWithTag("profile_api_key").performTextInput("sk-draft")
        confirmProbe()

        composeTestRule.onNodeWithTag("profile_api_key").performScrollTo().performTextInput("-changed")
        composeTestRule.onNodeWithTag("profile_model_dropdown").assertDoesNotExist()
        composeTestRule.onNodeWithTag("profile_editor_test_result").assertDoesNotExist()
        composeTestRule.onNodeWithTag("profile_editor_save").assertIsNotEnabled()
        confirmProbe()
        composeTestRule.onNodeWithTag("profile_model_dropdown").assertExists()

        composeTestRule.onNodeWithTag("profile_provider_selector").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("profile_provider_option_KIMI").performClick()
        composeTestRule.onNodeWithTag("profile_model_dropdown").assertDoesNotExist()
        composeTestRule.onNodeWithTag("profile_editor_test_result").assertDoesNotExist()
        composeTestRule.onNodeWithTag("profile_editor_save").assertIsNotEnabled()
        coVerify(exactly = 2) { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) }
    }

    @Test
    fun discoverModels_savedProfileModelChange_keepsCatalogButRequiresReplacementKeyToSave() {
        val target = profile("profile-1", "Primary")
        val harness = harness(listOf(target), activeProfileId = target.profileId)
        coEvery { harness.aiClient.discoverModels(target.profileId) } returns
            AiModelDiscoveryResult.Success(listOf("other-model"))
        coEvery { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) } returns
            AiModelDiscoveryResult.Success(listOf("other-model"))
        coEvery { harness.repository.updateProfile(any(), any()) } returns ProfileMutationResult.Success
        setScreen(harness)
        composeTestRule.onNodeWithTag("profile_manage_${target.profileId}").performClick()
        composeTestRule.onNodeWithText("编辑配置").performClick()
        composeTestRule.onNodeWithTag("profile_model_id").performScrollTo().performTextClearance()
        confirmProbe()

        coVerify(exactly = 1) { harness.aiClient.discoverModels(target.profileId) }
        composeTestRule.onNodeWithTag("profile_model_dropdown").performScrollTo().performClick()
        composeTestRule.onNodeWithText("other-model").performClick()
        composeTestRule.onNodeWithTag("profile_model_dropdown").assertExists()
        composeTestRule.onNodeWithTag("profile_editor_save").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("profile_api_key_error").performScrollTo().assertIsDisplayed()
        coVerify(exactly = 0) { harness.repository.updateProfile(any(), any()) }

        composeTestRule.onNodeWithTag("profile_api_key").performTextInput("replacement-key")
        composeTestRule.onNodeWithTag("profile_editor_save").assertIsNotEnabled()
        confirmProbe()
        composeTestRule.onNodeWithTag("profile_editor_save").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        coVerify(exactly = 1) {
            harness.aiClient.discoverModels(match<AiModelDiscoveryDraft> {
                it.baseUrl == target.baseUrl && it.apiKey == "replacement-key"
            })
        }
        coVerify(exactly = 1) {
            harness.repository.updateProfile(
                match { it.profileId == target.profileId && it.modelId == "other-model" },
                "replacement-key"
            )
        }
    }

    @Test
    fun unsavedDraft_connectionTestFailure_isShownInsideEditor() {
        val harness = harness()
        coEvery { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) } returns
            AiModelDiscoveryResult.Failure(AiError.HttpAuth(401))
        setScreen(harness)

        composeTestRule.onNodeWithTag("profile_add_empty").performClick()
        composeTestRule.onNodeWithTag("profile_api_key").performTextInput("sk-draft")
        confirmProbe()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("profile_api_key_error")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_api_key_hidden").performClick()
        composeTestRule.onNodeWithTag("profile_api_key").assertEditableTextEquals("sk-draft")
        composeTestRule.onNodeWithTag("profile_model_dropdown").assertDoesNotExist()
        composeTestRule.onNodeWithTag("profile_editor_save").assertIsNotEnabled()
    }

    @Test
    fun draftConnectionTest_oldResultDoesNotAppearAfterEditorReopens() {
        val releaseProbe = CompletableDeferred<Unit>()
        val harness = harness()
        coEvery { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) } coAnswers {
            withContext(NonCancellable) { releaseProbe.await() }
            AiModelDiscoveryResult.Success(listOf("deepseek-v4-flash"))
        }
        setScreen(harness)

        composeTestRule.onNodeWithTag("profile_add_empty").performClick()
        composeTestRule.onNodeWithTag("profile_api_key").performTextInput("sk-old")
        confirmProbe()
        coVerify(exactly = 1) { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("取消").performClick()
        composeTestRule.onNodeWithTag("profile_add_empty").performClick()
        releaseProbe.complete(Unit)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("profile_editor_test_result").assertDoesNotExist()
        composeTestRule.onNodeWithText("连接测试成功").assertDoesNotExist()
    }

    @Test
    fun draftConnectionTest_resultIsDiscardedWhenConnectionFieldsChange() {
        val releaseProbe = CompletableDeferred<Unit>()
        val harness = harness()
        coEvery { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) } coAnswers {
            withContext(NonCancellable) { releaseProbe.await() }
            AiModelDiscoveryResult.Success(listOf("deepseek-v4-flash"))
        }
        setScreen(harness)

        composeTestRule.onNodeWithTag("profile_add_empty").performClick()
        composeTestRule.onNodeWithTag("profile_api_key").performTextInput("sk-draft")
        confirmProbe()
        coVerify(exactly = 1) { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("profile_base_url").performTextClearance()
        composeTestRule.onNodeWithTag("profile_base_url")
            .performTextInput("https://changed.example.com")
        releaseProbe.complete(Unit)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("profile_editor_test_result").assertDoesNotExist()
    }

    @Test
    fun savedProfileConnectionTest_oldResultDoesNotAppearAfterSameProfileReopens() {
        val releaseProbe = CompletableDeferred<Unit>()
        val target = profile("profile-1", "Primary")
        val harness = harness(listOf(target), activeProfileId = target.profileId)
        coEvery { harness.aiClient.discoverModels(target.profileId) } coAnswers {
            withContext(NonCancellable) { releaseProbe.await() }
            AiModelDiscoveryResult.Success(listOf("deepseek-v4-flash"))
        }
        setScreen(harness)

        composeTestRule.onNodeWithTag("profile_manage_${target.profileId}").performClick()
        composeTestRule.onNodeWithText("编辑配置").performClick()
        confirmProbe()
        coVerify(exactly = 1) { harness.aiClient.discoverModels(target.profileId) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("取消").performClick()
        composeTestRule.onNodeWithTag("profile_manage_${target.profileId}").performClick()
        composeTestRule.onNodeWithText("编辑配置").performClick()
        releaseProbe.complete(Unit)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("profile_editor_test_result").assertDoesNotExist()
    }

    @Test
    fun profileMutation_locksEditorAndSuccessClosesIt() {
        val releaseMutation = CompletableDeferred<Unit>()
        val harness = harness()
        coEvery { harness.repository.createProfile(any(), any()) } coAnswers {
            releaseMutation.await()
            ProfileMutationResult.Success
        }
        coEvery { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) } returns
            AiModelDiscoveryResult.Success(listOf("deepseek-v4-flash"))
        setScreen(harness)

        composeTestRule.onNodeWithTag("profile_add_empty").performClick()
        composeTestRule.onNodeWithTag("profile_display_name").performTextInput("Primary")
        composeTestRule.onNodeWithTag("profile_api_key").performTextInput("sk-draft")
        confirmProbe()
        composeTestRule.onNodeWithTag("profile_editor_save").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("profile_editor_save").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("profile_base_url").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("profile_editor_test_connection").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("取消").assertIsNotEnabled()

        releaseMutation.complete(Unit)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("新增 AI 配置").assertDoesNotExist()
        coVerify(exactly = 1) { harness.repository.createProfile(any(), "sk-draft") }
    }

    @Test
    fun testConnection_blankFields_showInlineErrorsWithoutSending() {
        val harness = harness()
        setScreen(harness)
        composeTestRule.onNodeWithTag("profile_add_empty").performClick()
        for (tag in listOf("profile_display_name", "profile_base_url", "profile_model_id", "profile_temperature")) {
            composeTestRule.onNodeWithTag(tag).performScrollTo().performTextClearance()
        }
        composeTestRule.onNodeWithTag("profile_editor_test_connection").performScrollTo().performClick()

        composeTestRule.onNodeWithText("确认测试连接").assertDoesNotExist()
        for (tag in listOf("profile_base_url", "profile_api_key")) {
            composeTestRule.onNodeWithTag("${tag}_error").performScrollTo().assertIsDisplayed()
        }
        for (tag in listOf("profile_display_name", "profile_model_id", "profile_temperature")) {
            composeTestRule.onNodeWithTag("${tag}_error").assertDoesNotExist()
        }
        coVerify(exactly = 0) { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) }
        composeTestRule.onNodeWithTag("profile_model_dropdown").assertDoesNotExist()
        coVerify(exactly = 0) { harness.aiClient.testConnectionDraft(any()) }
    }

    @Test
    fun testConnection_invalidEndpoint_blocksDiscoveryButTemperatureDoesNot() {
        val harness = harness()
        coEvery { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) } returns
            AiModelDiscoveryResult.Success(listOf("deepseek-v4-flash"))
        setScreen(harness)
        composeTestRule.onNodeWithTag("profile_add_empty").performClick()
        composeTestRule.onNodeWithTag("profile_api_key").performTextInput("sk-draft")
        composeTestRule.onNodeWithTag("profile_base_url").performTextClearance()
        composeTestRule.onNodeWithTag("profile_base_url").performTextInput("http://example.com")
        composeTestRule.onNodeWithTag("profile_temperature").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("profile_temperature").performTextInput("NaN")
        composeTestRule.onNodeWithTag("profile_editor_test_connection").performScrollTo().performClick()

        composeTestRule.onNodeWithTag("profile_base_url_error").assertExists()
        composeTestRule.onNodeWithTag("profile_temperature_error").assertDoesNotExist()
        coVerify(exactly = 0) { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) }
        composeTestRule.onNodeWithText("确认测试连接").assertDoesNotExist()
        composeTestRule.onNodeWithTag("profile_base_url").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("profile_base_url").performTextInput("https://example.com")
        confirmProbe()
        composeTestRule.onNodeWithTag("profile_base_url_error").assertDoesNotExist()
        composeTestRule.onNodeWithTag("profile_temperature_error").assertDoesNotExist()
        composeTestRule.onNodeWithTag("profile_editor_save").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("profile_temperature_error").assertExists()
        coVerify(exactly = 0) { harness.repository.createProfile(any(), any()) }
        coVerify(exactly = 1) { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) }
    }

    @Test
    fun testConnection_manualModel_isPreservedAndMissingModelBlocksOnlySave() {
        val harness = harness()
        coEvery { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) } returns
            AiModelDiscoveryResult.Success(listOf("different-model"))
        setScreen(harness)
        composeTestRule.onNodeWithTag("profile_add_empty").performClick()
        composeTestRule.onNodeWithTag("profile_api_key").performTextInput("sk-draft")
        confirmProbe()

        composeTestRule.onNodeWithTag("profile_model_id_error").assertDoesNotExist()
        composeTestRule.onNodeWithTag("profile_model_id").assertEditableTextEquals("deepseek-v4-flash")
        composeTestRule.onNodeWithTag("profile_model_id").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("profile_editor_save").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("profile_model_id_error").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_model_dropdown").assertExists()
        coVerify(exactly = 0) { harness.repository.createProfile(any(), any()) }
    }

    @Test
    fun testConnection_emptyCatalog_doesNotEnableSaveOrShowVerifiedArrow() {
        val harness = harness()
        coEvery { harness.aiClient.discoverModels(any<AiModelDiscoveryDraft>()) } returns
            AiModelDiscoveryResult.Failure(AiError.NoContent)
        setScreen(harness)
        composeTestRule.onNodeWithTag("profile_add_empty").performClick()
        composeTestRule.onNodeWithTag("profile_api_key").performTextInput("sk-draft")
        confirmProbe()

        composeTestRule.onNodeWithTag("profile_model_dropdown").assertDoesNotExist()
        composeTestRule.onNodeWithTag("profile_model_verified").assertDoesNotExist()
        composeTestRule.onNodeWithTag("profile_editor_save").assertIsNotEnabled()
        composeTestRule.onNodeWithText("连接测试成功").assertDoesNotExist()
    }

    private fun confirmProbe() {
        composeTestRule.onNodeWithTag("profile_editor_test_connection").performScrollTo().performClick()
        composeTestRule.waitForIdle()
    }

    private fun setScreen(harness: Harness) {
        composeTestRule.setContent {
            AiProfileConfigScreen(onBack = {}, viewModel = harness.viewModel)
        }
        composeTestRule.waitForIdle()
    }

    private fun harness(
        profiles: List<AiProviderProfile> = emptyList(),
        activeProfileId: String? = null
    ): Harness {
        val profileFlow = MutableStateFlow(profiles)
        val activeProfileFlow = MutableStateFlow(activeProfileId)
        val repository = mockk<AiProfileRepository>(relaxed = true) {
            every { this@mockk.profiles } returns profileFlow
            every { this@mockk.activeProfileId } returns activeProfileFlow
        }
        val settingsPreferences = mockk<SettingsPreferences> {
            every { fontSizeOption } returns MutableStateFlow(FontSizeOption.DEFAULT)
            every { themeOption } returns MutableStateFlow(ThemeOption.DEFAULT)
            every { allowNetworkTts } returns MutableStateFlow(false)
        }
        val cacheRepository = mockk<ExplanationCacheRepository>(relaxed = true)
        val aiClient = mockk<AiClient>(relaxed = true) {
            every { inFlightProfileIds } returns MutableStateFlow(emptySet())
        }
        return Harness(
            repository = repository,
            cacheRepository = cacheRepository,
            aiClient = aiClient,
            viewModel = SettingsViewModel(
                settingsPreferences,
                repository,
                cacheRepository,
                aiClient,
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true)
            )
        )
    }

    private fun profile(
        profileId: String,
        displayName: String,
        template: AiProviderTemplate = AiProviderTemplate.DEEPSEEK
    ) = AiProviderProfile(
        profileId = profileId,
        displayName = displayName,
        providerTemplate = template,
        baseUrl = template.defaultBaseUrl ?: "https://example.com/v1",
        modelId = template.defaultModelId ?: "model-id",
        authStrategy = AiAuthStrategy.API_KEY,
        temperature = 0.2
    )

    private fun SemanticsNodeInteraction.assertEditableTextEquals(expected: String) = assert(
        SemanticsMatcher("EditableText = '$expected'") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty() == expected
        }
    )

    private data class Harness(
        val repository: AiProfileRepository,
        val cacheRepository: ExplanationCacheRepository,
        val aiClient: AiClient,
        val viewModel: SettingsViewModel
    )
}
