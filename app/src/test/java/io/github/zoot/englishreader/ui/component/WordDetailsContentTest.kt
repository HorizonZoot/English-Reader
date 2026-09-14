package io.github.zoot.englishreader.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.zoot.englishreader.viewmodel.AlternateDefinition
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordDetailsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun wordDetails_mainAndAlternates_dispatchIndependentSaveCallbacks() {
        val savedWords = mutableStateOf(emptySet<String>())
        val saves = mutableListOf<String>()
        render(
            isInVocabulary = { it in savedWords.value },
            onAddToVocabulary = { word ->
                saves += word
                savedWords.value += word
            }
        )

        composeRule.onNodeWithTag("word-details-add-main")
            .assertIsDisplayed().assertIsEnabled()
            .assertContentDescriptionEquals("将“axe”添加到生词本")
            .performClick().assertIsNotEnabled()
            .assertContentDescriptionEquals("“axe”已在生词本")
        composeRule.onNodeWithTag("word-details-add-alternate-0")
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag("word-details-add-alternate-1")
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            .assertIsNotEnabled()

        composeRule.runOnIdle {
            assertEquals(listOf("axe", "ax", "axis"), saves)
            assertEquals(setOf("axe", "ax", "axis"), savedWords.value)
        }
    }

    @Test
    fun wordDetails_savedForms_disableOnlyMatchingSaveActions() {
        val saves = mutableListOf<String>()
        render(
            isInVocabulary = { it in setOf("axe", "axis") },
            onAddToVocabulary = { saves += it }
        )

        composeRule.onNodeWithTag("word-details-add-main")
            .assertIsDisplayed().assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag("word-details-add-alternate-1")
            .performScrollTo().assertIsDisplayed().assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag("word-details-add-alternate-0")
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals(listOf("ax"), saves) }
    }

    @Test
    fun wordDetails_inflectedFormAndBilingualAlternates_remainReachable() {
        render()

        listOf(
            "axes 的原形",
            "n. 斧头",
            "A tool used for chopping wood.",
            "ax",
            "/æks/",
            "斧子的另一种拼写",
            "Another spelling of axe.",
            "axis",
            "/ˈæksɪs/",
            "n. 轴",
            "A line around which an object turns."
        ).forEach { text ->
            composeRule.onNodeWithText(text)
                .performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun wordDetails_longContentAndLargeFont_keepsActionsAndFinalDefinitionsReachable() {
        val longWord = "pneumonoultramicroscopicsilicovolcanoconiosis"
        val chineseDefinitions = List(12) { "中文释义 ${it + 1}，保留完整内容供用户阅读。" }
        val englishDefinitions = List(12) { "English definition ${it + 1}, shown in full." }
        val saves = mutableListOf<String>()
        var plays = 0
        var dismisses = 0
        render(
            word = longWord,
            phonetic = "/ˌnjuːmənoʊˌʌltrəˌmaɪkrəˈskɒpɪkˌsɪlɪkoʊvɒlˌkeɪnoʊˌkoʊniˈoʊsɪs/",
            chineseDefinitions = chineseDefinitions,
            englishDefinitions = englishDefinitions,
            fontScale = 1.8f,
            onAddToVocabulary = { saves += it },
            onPlayAudio = { plays++ },
            onDismiss = { dismisses++ }
        )

        composeRule.onNodeWithContentDescription("播放发音")
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("word-details-add-main")
            .assertIsDisplayed().performClick()
        composeRule.onNodeWithText(chineseDefinitions.last())
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(englishDefinitions.last())
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("A line around which an object turns.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("word-details-add-alternate-1")
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("关闭")
            .performScrollTo().assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(longWord, "axis"), saves)
            assertEquals(1, plays)
            assertEquals(1, dismisses)
        }
    }

    @Test
    fun wordDetails_shortSheet_reportsVisibleTopAfterOpening() {
        var reportedTop: Float? = null
        composeRule.setContent {
            MaterialTheme {
                WordDetailsBottomSheet(
                    word = "axe",
                    phonetic = null,
                    chineseDefinitions = emptyList(),
                    englishDefinitions = emptyList(),
                    onDismiss = {},
                    onAddToVocabulary = {},
                    onPlayAudio = {},
                    onVisibleTopChanged = { reportedTop = it }
                )
            }
        }

        composeRule.onNodeWithText("未找到释义").assertIsDisplayed()
        val handle = composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss),
            useUnmergedTree = true
        ).fetchSemanticsNode()
        val location = IntArray(2)
        (handle.root as ViewRootForTest).view.getLocationOnScreen(location)
        val visibleTop = handle.positionInRoot.y + location[1]
        composeRule.runOnIdle {
            assertNotNull(reportedTop)
            assertTrue(reportedTop!! > 0f)
            assertEquals(visibleTop, reportedTop!!, 1f)
        }
    }

    @Test
    fun idleState_showsPlayIconAndIsClickable() {
        val plays = AtomicInteger()
        renderAudioSheet(isLoadingAudio = false, onPlayAudio = { plays.incrementAndGet() })

        composeRule.onNodeWithContentDescription("播放发音").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(1, plays.get()) }
    }

    @Test
    fun loadingState_showsAccessibleProgressAndPreventsReplay() {
        val plays = AtomicInteger()
        renderAudioSheet(isLoadingAudio = true, onPlayAudio = { plays.incrementAndGet() })

        // 转圈替换播放图标时仍须有无障碍文案，并且不能重复发起播放请求。
        composeRule.onNodeWithContentDescription("正在加载发音").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("播放发音").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("正在加载发音").performClick()

        // Disabled 语义在父按钮上；通过回调观察真实的禁止重复播放契约。
        composeRule.runOnIdle {
            assertEquals("a loading button must not fire its callback", 0, plays.get())
        }
    }

    private fun renderAudioSheet(isLoadingAudio: Boolean, onPlayAudio: () -> Unit) {
        composeRule.setContent {
            WordDetailsBottomSheet(
                word = "handwriting",
                phonetic = "/ˈhændraɪtɪŋ/",
                chineseDefinitions = listOf("n. 笔迹"),
                englishDefinitions = listOf("writing done with a pen"),
                onDismiss = {},
                onAddToVocabulary = {},
                onPlayAudio = onPlayAudio,
                isLoadingAudio = isLoadingAudio
            )
        }
    }

    private fun render(
        word: String = "axe",
        phonetic: String? = null,
        chineseDefinitions: List<String> = listOf("n. 斧头"),
        englishDefinitions: List<String> = listOf("A tool used for chopping wood."),
        fontScale: Float = 1f,
        isInVocabulary: (String) -> Boolean = { false },
        onAddToVocabulary: (String) -> Unit = {},
        onPlayAudio: () -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale)
            ) {
                MaterialTheme {
                    Box(modifier = Modifier.size(width = 320.dp, height = 420.dp)) {
                        WordDetailsContent(
                            word = word,
                            phonetic = phonetic,
                            chineseDefinitions = chineseDefinitions,
                            englishDefinitions = englishDefinitions,
                            inflectedForm = "axes",
                            alternates = listOf(
                                AlternateDefinition(
                                    word = "ax",
                                    phonetic = "/æks/",
                                    chineseDefinitions = listOf("斧子的另一种拼写"),
                                    englishDefinitions = listOf("Another spelling of axe.")
                                ),
                                AlternateDefinition(
                                    word = "axis",
                                    phonetic = "/ˈæksɪs/",
                                    chineseDefinitions = listOf("n. 轴"),
                                    englishDefinitions = listOf("A line around which an object turns.")
                                )
                            ),
                            isInVocabulary = isInVocabulary,
                            onAddToVocabulary = onAddToVocabulary,
                            onPlayAudio = onPlayAudio,
                            onDismiss = onDismiss
                        )
                    }
                }
            }
        }
    }
}
