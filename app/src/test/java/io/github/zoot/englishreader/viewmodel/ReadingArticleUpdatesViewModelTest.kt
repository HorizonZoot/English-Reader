package io.github.zoot.englishreader.viewmodel

import app.cash.turbine.test
import io.github.zoot.englishreader.core.SentenceRange
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingEntry
import io.github.zoot.englishreader.model.ReadingPosition
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.model.ReadingTtsPhase
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.github.zoot.englishreader.util.TtsPlaybackResult
import io.github.zoot.englishreader.util.TtsVoiceMode
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingArticleUpdatesViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())
    private val original = ArticleEntity(1, "Title", "First.\n\nSecond.", translation = "一。\n\n二。")
    private val updates = MutableStateFlow<ArticleEntity?>(original)
    private val fixture = ReadingViewModelFixture()

    private fun TestScope.loadedViewModel(): ReadingViewModel {
        coEvery { fixture.articleRepository.getArticleById(1) } returns original
        every { fixture.articleRepository.observeArticle(1) } returns updates
        every { fixture.ttsPlayer.speakReading(any(), any(), any(), any()) } answers {
            arg<(TtsPlaybackResult) -> Unit>(3)(TtsPlaybackResult.Started("current", TtsVoiceMode.LOCAL_MODEL))
        }
        val vm = fixture.create()
        vm.loadArticle(1)
        runCurrent()
        vm.consumePositionTarget(requireNotNull(vm.pendingPositionTarget.value))
        return vm
    }

    @Test
    fun observeArticle_titleTranslationAndReadingTime_doNotResetSelectionPositionOrPlayback() = runTest {
        val vm = loadedViewModel()
        vm.selectSentence(1, 0, SentenceRange(0, "First.", 0, 6))
        vm.playSelectedSentence()
        runCurrent()
        val selection = vm.selectedSentence.value
        val speech = vm.readingTtsState.value
        clearMocks(fixture.ttsPlayer, answers = false)

        updates.value = original.copy(translation = "新译文。\n\n第二段。")
        runCurrent()
        assertEquals("新译文。\n\n第二段。", vm.article.value?.translation)
        updates.value = requireNotNull(updates.value).copy(title = "New title")
        runCurrent()
        assertEquals("New title", vm.article.value?.title)
        updates.value = requireNotNull(updates.value).copy(lastReadAt = 100)
        runCurrent()

        assertEquals(selection, vm.selectedSentence.value)
        assertEquals(speech, vm.readingTtsState.value)
        assertNull(vm.pendingPositionTarget.value)
        verify(exactly = 0) { fixture.ttsPlayer.stop() }
        verify(exactly = 0) { fixture.ttsPlayer.stopBeforeReading() }
    }

    @Test
    fun observeArticle_bodyChanged_resetsOldStateAndRejectsLateOldLayoutPosition() = runTest {
        val vm = loadedViewModel()
        vm.selectSentence(1, 0, SentenceRange(0, "First.", 0, 6))
        vm.playSelectedSentence()
        runCurrent()
        assertEquals(ReadingTtsPhase.PLAYING, vm.readingTtsState.value.phase)
        updates.value = original.copy(content = "Edited.", translation = null)
        runCurrent()

        assertEquals("Edited.", vm.article.value?.content)
        assertNull(vm.article.value?.translation)
        assertNull(vm.selectedSentence.value)
        assertEquals(ReadingTtsPhase.IDLE, vm.readingTtsState.value.phase)
        val reset = requireNotNull(vm.pendingPositionTarget.value)
        assertEquals(ReadingEntry.START, reset.entry)
        assertEquals(ReadingAnchor(textKind = ReadingTextKind.TITLE), reset.position.anchor)
        val position = ReadingPosition(1, ReadingAnchor())
        vm.saveReadingPosition(position, original.content)
        vm.consumePositionTarget(reset)
        vm.saveReadingPosition(position, original.content)
        runCurrent()
        coVerify(exactly = 0) { fixture.articleRepository.saveReadingPosition(any(), any(), any()) }

        vm.saveReadingPosition(position, "Edited.")
        runCurrent()
        coVerify(exactly = 1) { fixture.articleRepository.saveReadingPosition(position, "Edited.", io.github.zoot.englishreader.model.ReadingPublication(null, null)) }
    }

    @Test
    fun loadArticle_newArticle_detachesOldObserverAndIgnoresOldTranslation() = runTest {
        val vm = loadedViewModel()
        val other = ArticleEntity(2, "Other", "Other body.")
        val otherUpdates = MutableStateFlow<ArticleEntity?>(other)
        coEvery { fixture.articleRepository.getArticleById(2) } returns other
        every { fixture.articleRepository.observeArticle(2) } returns otherUpdates
        vm.loadArticle(2)
        runCurrent()
        val target = vm.pendingPositionTarget.value
        assertEquals(0, updates.subscriptionCount.value)

        updates.value = original.copy(translation = "迟到译文。")
        runCurrent()
        assertEquals(other, vm.article.value)
        assertEquals(target, vm.pendingPositionTarget.value)
        otherUpdates.value = other.copy(translation = "当前译文。")
        runCurrent()
        assertEquals("当前译文。", vm.article.value?.translation)
    }

    @Test
    fun loadArticle_failedSwitch_restoresObservationOfStillVisibleArticle() = runTest {
        val vm = loadedViewModel()
        coEvery { fixture.articleRepository.getArticleById(2) } throws IOException()
        vm.readingErrors.test {
            vm.loadArticle(2)
            runCurrent()
            assertEquals(ReadingError.LOAD, awaitItem())
            assertEquals(1L, vm.article.value?.id)
            assertEquals(1, updates.subscriptionCount.value)

            updates.value = original.copy(translation = "切换失败后完成的译文。")
            runCurrent()
            assertEquals("切换失败后完成的译文。", vm.article.value?.translation)
            expectNoEvents()
        }
    }

    @Test
    fun observeArticle_deletedArticle_clearsReadingStateAndRejectsProgress() = runTest {
        val vm = loadedViewModel()
        vm.selectSentence(1, 0, SentenceRange(0, "First.", 0, 6))
        vm.readingErrors.test {
            updates.value = null
            runCurrent()
            assertEquals(ReadingError.LOAD, awaitItem())
            assertNull(vm.article.value)
            assertNull(vm.selectedSentence.value)
            assertNull(vm.pendingPositionTarget.value)
            vm.saveReadingPosition(ReadingPosition(1, ReadingAnchor()), original.content)
            runCurrent()
            coVerify(exactly = 0) { fixture.articleRepository.saveReadingPosition(any(), any(), any()) }
            expectNoEvents()
        }
    }
}
