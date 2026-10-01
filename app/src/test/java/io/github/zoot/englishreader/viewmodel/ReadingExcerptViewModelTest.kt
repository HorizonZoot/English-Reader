package io.github.zoot.englishreader.viewmodel

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.github.zoot.englishreader.core.ExcerptRange
import io.github.zoot.englishreader.data.entity.ArticleEntity
import io.github.zoot.englishreader.data.entity.ExcerptEntity
import io.github.zoot.englishreader.data.repository.ExcerptRepository
import io.github.zoot.englishreader.data.repository.ExcerptSaveResult
import io.github.zoot.englishreader.model.ReadingAnchor
import io.github.zoot.englishreader.model.ReadingTextKind
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingExcerptViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val article = ArticleEntity(7, "Source", "First.\n\nAlpha beta.")
    private val excerpt = ExcerptEntity(
        1, 7, 7, "Source", ExcerptRepository.fingerprint(article.content), 1, 6, 10, "beta", 10
    )

    @Test
    fun openReadingSession_validExcerpt_usesExactSourceAnchor() = runTest {
        val fixture = ReadingViewModelFixture()
        coEvery { fixture.articleRepository.getArticleById(7) } returns article
        coEvery { fixture.excerptRepository.getById(1) } returns excerpt
        val handle = SavedStateHandle(mapOf("excerptId" to 1L))
        val vm = fixture.create(handle)
        vm.openReadingSession(7)
        runCurrent()
        assertEquals(article, vm.article.value)
        assertEquals(ReadingAnchor(1, ReadingTextKind.ORIGINAL, 6), vm.pendingPositionTarget.value?.position?.anchor)
        assertEquals(true, handle.get<Boolean>("excerpt_consumed"))
    }

    @Test
    fun openReadingSession_editedSource_opensArticleWithoutGuessingOffset() = runTest {
        val fixture = ReadingViewModelFixture()
        val changed = article.copy(content = "Changed.\n\nAlpha beta.")
        coEvery { fixture.articleRepository.getArticleById(7) } returns changed
        coEvery { fixture.excerptRepository.getById(1) } returns excerpt
        val vm = fixture.create(SavedStateHandle(mapOf("excerptId" to 1L)))
        vm.readingErrors.test {
            vm.openReadingSession(7)
            assertEquals(ReadingError.EXCERPT_SOURCE_UNAVAILABLE, awaitItem())
            runCurrent()
            assertEquals(changed, vm.article.value)
            assertEquals(ReadingTextKind.TITLE, vm.pendingPositionTarget.value?.position?.anchor?.textKind)
            assertNull(vm.highlightedParagraph.value)
        }
    }

    @Test
    fun saveExcerpt_oldSnapshotAfterSwitch_isIgnored() = runTest {
        val fixture = ReadingViewModelFixture()
        coEvery { fixture.articleRepository.getArticleById(7) } returns article
        coEvery { fixture.articleRepository.getArticleById(8) } returns article.copy(id = 8)
        val vm = fixture.create()
        vm.loadArticle(7)
        runCurrent()
        vm.loadArticle(8)
        runCurrent()
        vm.saveExcerpt(article, 1, ExcerptRange(6, 10))
        runCurrent()
        coVerify(exactly = 0) { fixture.excerptRepository.save(any(), any(), any()) }
    }

    @Test
    fun saveExcerpt_resultAfterSwitch_doesNotNotifyDifferentArticle() = runTest {
        val fixture = ReadingViewModelFixture()
        val gate = CompletableDeferred<Unit>()
        coEvery { fixture.articleRepository.getArticleById(7) } returns article
        coEvery { fixture.articleRepository.getArticleById(8) } returns article.copy(id = 8)
        coEvery { fixture.excerptRepository.save(any(), any(), any()) } coAnswers { gate.await(); ExcerptSaveResult.SAVED }
        val vm = fixture.create()
        vm.loadArticle(7)
        runCurrent()
        vm.excerptSaved.test {
            vm.saveExcerpt(article, 1, ExcerptRange(6, 10))
            runCurrent()
            vm.loadArticle(8)
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            expectNoEvents()
        }
    }
}
