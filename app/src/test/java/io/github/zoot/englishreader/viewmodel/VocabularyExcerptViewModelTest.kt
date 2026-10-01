package io.github.zoot.englishreader.viewmodel

import app.cash.turbine.test
import io.github.zoot.englishreader.data.entity.ExcerptEntity
import io.github.zoot.englishreader.data.repository.ExcerptRepository
import io.github.zoot.englishreader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyExcerptViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val repository = mockk<ExcerptRepository> {
        every { observeAll() } returns flowOf(emptyList())
    }
    private fun create() = VocabularyViewModel(
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), repository
    )
    private val excerpt = ExcerptEntity(1, 7, 7, "Source", "fp", 0, 0, 5, "Alpha", 10)

    @Test
    fun openExcerpt_withQueuedSnackbar_navigatesIndependently() = runTest {
        val vm = create()
        coEvery { repository.delete(1) } returns true
        coEvery { repository.hasSource(excerpt) } returns true
        vm.excerptToOpen.test {
            vm.deleteExcerpt(excerpt)
            runCurrent()
            vm.openExcerpt(excerpt)
            assertEquals(excerpt, awaitItem())
            vm.uiEvent.test { assertEquals(VocabularyUiEvent.ExcerptDeleted(excerpt), awaitItem()) }
        }
    }

    @Test
    fun openExcerpt_newerRequest_dropsLateNonCooperativeOldResult() = runTest {
        val vm = create()
        val gate = CompletableDeferred<Unit>()
        val newer = excerpt.copy(id = 2)
        coEvery { repository.hasSource(excerpt) } coAnswers { withContext(NonCancellable) { gate.await(); true } }
        coEvery { repository.hasSource(newer) } returns true
        vm.excerptToOpen.test {
            vm.openExcerpt(excerpt)
            runCurrent()
            vm.openExcerpt(newer)
            assertEquals(newer, awaitItem())
            gate.complete(Unit)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun cancelExcerptNavigation_lateResult_doesNotNavigateAfterLeavingTab() = runTest {
        val vm = create()
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.hasSource(excerpt) } coAnswers { withContext(NonCancellable) { gate.await(); true } }
        vm.excerptToOpen.test {
            vm.openExcerpt(excerpt)
            runCurrent()
            vm.cancelExcerptNavigation()
            gate.complete(Unit)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun openExcerpt_missingSource_reportsUnavailableWithoutNavigation() = runTest {
        val vm = create()
        coEvery { repository.hasSource(excerpt) } returns false
        vm.excerptToOpen.test {
            vm.uiEvent.test {
                vm.openExcerpt(excerpt)
                assertEquals(VocabularyUiEvent.ExcerptSourceUnavailable, awaitItem())
            }
            expectNoEvents()
        }
    }

    @Test
    fun deleteExcerpt_repeatedTap_emitsOnlyAfterSingleSuccessfulWrite() = runTest {
        val vm = create()
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.delete(1) } coAnswers { gate.await(); true }
        vm.uiEvent.test {
            vm.deleteExcerpt(excerpt)
            vm.deleteExcerpt(excerpt)
            runCurrent()
            expectNoEvents()
            gate.complete(Unit)
            assertEquals(VocabularyUiEvent.ExcerptDeleted(excerpt), awaitItem())
            coVerify(exactly = 1) { repository.delete(1) }
        }
    }
}
