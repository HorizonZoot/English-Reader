package io.github.zoot.englishreader.viewmodel

import io.github.zoot.englishreader.data.ai.AiClientResult
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.repository.AiExplanationOperationRegistry
import io.github.zoot.englishreader.model.AiOperationOutcome
import io.github.zoot.englishreader.model.AiExplanationTarget
import io.github.zoot.englishreader.model.AiSheetState
import io.github.zoot.englishreader.model.AiSheetRequestToken
import io.github.zoot.englishreader.model.SelectedSentenceSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiSheetCoordinatorTest {

    @Test
    fun state_initially_isHidden() = runTest {
        val fixture = fixture()
        try {
            assertSame(AiSheetState.Hidden, fixture.coordinator.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun begin_publishesLoadingAndInvalidatesOlderToken() = runTest {
        val fixture = fixture()
        try {
            val first = fixture.coordinator.begin()
            val second = fixture.coordinator.begin()

            assertNotEquals(first, second)
            assertEquals(second, fixture.coordinator.state.value.requireLoading().requestToken)
            assertFalse(fixture.coordinator.reject(first, AiError.NoActiveProfile))
            assertEquals(second, fixture.coordinator.state.value.requireLoading().requestToken)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun begin_withTarget_publishesTargetAndAttachKeepsAtomicAssociation() = runTest {
        val fixture = fixture()
        try {
            val snapshot = TestSentenceSnapshot()
            val target = AiExplanationTarget.Sentence(snapshot)
            val token = fixture.coordinator.begin(target)

            val loading = fixture.coordinator.state.value.requireLoading()
            assertEquals(target, loading.target)
            assertEquals(token, loading.requestToken)

            val handle = fixture.registry.attachOrStart("target-key") {
                AiClientResult.Success("target-result")
            }
            assertTrue(fixture.coordinator.attach(token, handle))
            advanceUntilIdle()

            val visible = fixture.coordinator.state.value.requireVisible()
            assertEquals(target, visible.target)
            assertEquals(handle.ref, visible.attachment.operationRef)
            assertFalse(target.toString().contains(snapshot.rawText))
            assertFalse(visible.toString().contains(snapshot.rawText))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun attach_sameTokenTwice_secondAttachCannotReplaceOriginalTargetOrOperation() = runTest {
        val fixture = fixture()
        val firstRelease = CompletableDeferred<Unit>()
        try {
            val target = AiExplanationTarget.Article(41)
            val token = fixture.coordinator.begin(target)
            val first = fixture.registry.attachOrStart("first-key") {
                firstRelease.await()
                AiClientResult.Success("first")
            }
            val second = fixture.registry.attachOrStart("second-key") {
                AiClientResult.Success("second")
            }

            assertTrue(fixture.coordinator.attach(token, first))
            assertFalse(fixture.coordinator.attach(token, second))
            val visible = fixture.coordinator.state.value.requireVisible()
            assertEquals(target, visible.target)
            assertEquals(first.ref, visible.attachment.operationRef)

            firstRelease.complete(Unit)
            advanceUntilIdle()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun attach_currentToken_publishesTypedOutcome() = runTest {
        val fixture = fixture()
        try {
            val handle = fixture.registry.attachOrStart("key-a") {
                AiClientResult.Success("explanation")
            }
            val token = fixture.coordinator.begin()

            assertTrue(fixture.coordinator.attach(token, handle))
            advanceUntilIdle()

            val visible = fixture.coordinator.state.value.requireVisible()
            assertEquals(handle.ref, visible.attachment.operationRef)
            assertEquals(AiOperationOutcome.Success("explanation"), visible.outcome)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun replacement_followedByStaleAttach_doesNotOverwriteNewestLoading() = runTest {
        val fixture = fixture()
        try {
            val staleHandle = fixture.registry.attachOrStart("key-a") {
                AiClientResult.Success("stale")
            }
            val staleToken = fixture.coordinator.begin()
            val currentToken = fixture.coordinator.begin()

            assertFalse(fixture.coordinator.attach(staleToken, staleHandle))
            assertEquals(
                currentToken,
                fixture.coordinator.state.value.requireLoading().requestToken
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancelBeforeAttach_currentTokenSettlesHiddenWithoutPermanentLoading() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        try {
            val handle = fixture.registry.attachOrStart("key-a") {
                release.await()
                AiClientResult.Success("never")
            }
            runCurrent()
            assertTrue(fixture.registry.cancel(handle.ref))
            val token = fixture.coordinator.begin()

            assertTrue(fixture.coordinator.attach(token, handle))
            advanceUntilIdle()

            assertSame(AiSheetState.Hidden, fixture.coordinator.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun invalidate_afterAttach_cannotHideVisibleRegisteredOperation() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        try {
            val target = AiExplanationTarget.Article(9)
            val token = fixture.coordinator.begin(target)
            val handle = fixture.registry.attachOrStart("visible-key") {
                release.await()
                AiClientResult.Success("done")
            }
            assertTrue(fixture.coordinator.attach(token, handle))
            runCurrent()

            assertFalse(fixture.coordinator.invalidate(token))
            assertEquals(target, fixture.coordinator.state.value.requireVisible().target)

            release.complete(Unit)
            advanceUntilIdle()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun reject_afterAttach_cannotOverwriteVisibleRegisteredOperation() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        try {
            val target = AiExplanationTarget.Article(10)
            val token = fixture.coordinator.begin(target)
            val handle = fixture.registry.attachOrStart("reject-visible-key") {
                release.await()
                AiClientResult.Success("done")
            }
            assertTrue(fixture.coordinator.attach(token, handle))
            runCurrent()

            assertFalse(fixture.coordinator.reject(token, AiError.NoActiveProfile))
            assertEquals(target, fixture.coordinator.state.value.requireVisible().target)

            release.complete(Unit)
            advanceUntilIdle()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun attachBeforeCancel_exactCancelHidesCurrentSheet() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        try {
            val handle = fixture.registry.attachOrStart("key-a") {
                release.await()
                AiClientResult.Success("never")
            }
            val token = fixture.coordinator.begin()
            fixture.coordinator.attach(token, handle)
            runCurrent()

            assertTrue(fixture.coordinator.cancel(handle.ref))
            runCurrent()

            assertSame(AiSheetState.Hidden, fixture.coordinator.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun dismiss_runningOperation_detachesObserverWithoutCancellingOperation() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        try {
            val handle = fixture.registry.attachOrStart("key-a") {
                release.await()
                AiClientResult.Success("survived")
            }
            val token = fixture.coordinator.begin()
            fixture.coordinator.attach(token, handle)
            runCurrent()

            fixture.coordinator.dismiss()
            runCurrent()
            assertSame(AiSheetState.Hidden, fixture.coordinator.state.value)
            val reused = fixture.registry.attachOrStart("key-a") {
                AiClientResult.Success("duplicate")
            }
            assertEquals(handle.ref, reused.ref)

            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(AiOperationOutcome.Success("survived"), reused.awaitOutcome())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun reopen_runningOperation_usesNewGenerationAndSameOperationId() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        try {
            val handle = fixture.registry.attachOrStart("key-a") {
                release.await()
                AiClientResult.Success("survived")
            }
            fixture.coordinator.attach(fixture.coordinator.begin(), handle)
            runCurrent()
            val first = fixture.coordinator.state.value.requireVisible()

            fixture.coordinator.dismiss()
            val reused = fixture.registry.attachOrStart("key-a") {
                AiClientResult.Success("duplicate")
            }
            fixture.coordinator.attach(fixture.coordinator.begin(), reused)
            runCurrent()
            val reopened = fixture.coordinator.state.value.requireVisible()

            assertEquals(first.attachment.operationRef, reopened.attachment.operationRef)
            assertEquals(first.attachment.generation + 1, reopened.attachment.generation)

            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                AiOperationOutcome.Success("survived"),
                fixture.coordinator.state.value.requireVisible().outcome
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun completion_staleHandleAfterSwitch_doesNotOverwriteCurrentSheet() = runTest {
        val fixture = fixture()
        val staleRelease = CompletableDeferred<Unit>()
        val currentRelease = CompletableDeferred<Unit>()
        try {
            val stale = fixture.registry.attachOrStart("key-a") {
                staleRelease.await()
                AiClientResult.Success("stale")
            }
            fixture.coordinator.attach(fixture.coordinator.begin(), stale)
            runCurrent()

            val current = fixture.registry.attachOrStart("key-b") {
                currentRelease.await()
                AiClientResult.Success("current")
            }
            fixture.coordinator.attach(fixture.coordinator.begin(), current)
            runCurrent()
            val currentLoading = fixture.coordinator.state.value.requireVisible()

            staleRelease.complete(Unit)
            advanceUntilIdle()
            val afterStaleCompletion = fixture.coordinator.state.value.requireVisible()
            assertEquals(currentLoading.attachment, afterStaleCompletion.attachment)
            assertNull(afterStaleCompletion.outcome)

            currentRelease.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                AiOperationOutcome.Success("current"),
                fixture.coordinator.state.value.requireVisible().outcome
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancel_staleReferenceAfterReplacement_doesNotAffectCurrentSheet() = runTest {
        val fixture = fixture()
        val firstRelease = CompletableDeferred<Unit>()
        val replacementRelease = CompletableDeferred<Unit>()
        try {
            val first = fixture.registry.attachOrStart("key-a") {
                firstRelease.await()
                AiClientResult.Success("first")
            }
            fixture.coordinator.attach(fixture.coordinator.begin(), first)
            runCurrent()
            assertTrue(fixture.coordinator.cancel(first.ref))

            val replacement = fixture.registry.attachOrStart("key-a") {
                replacementRelease.await()
                AiClientResult.Success("replacement")
            }
            fixture.coordinator.attach(fixture.coordinator.begin(), replacement)
            runCurrent()

            assertFalse(fixture.coordinator.cancel(first.ref))
            assertEquals(
                replacement.ref,
                fixture.coordinator.state.value.requireVisible().attachment.operationRef
            )

            replacementRelease.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                AiOperationOutcome.Success("replacement"),
                fixture.coordinator.state.value.requireVisible().outcome
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun completedHandleAttach_publishesRetainedOutcome() = runTest {
        val fixture = fixture()
        try {
            val handle = fixture.registry.attachOrStart("key-a") {
                AiClientResult.Failure(AiError.Offline)
            }
            advanceUntilIdle()
            val token = fixture.coordinator.begin()

            fixture.coordinator.attach(token, handle)
            advanceUntilIdle()

            assertEquals(
                AiOperationOutcome.Failure(AiError.Offline),
                fixture.coordinator.state.value.requireVisible().outcome
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failureOutcome_doesNotCancelObserverScopeSibling() = runTest {
        val fixture = fixture()
        val siblingRelease = CompletableDeferred<Unit>()
        try {
            val sibling = fixture.observerScope.async {
                siblingRelease.await()
                "sibling survived"
            }
            val failed = fixture.registry.attachOrStart("key-fail") {
                throw IllegalStateException("private detail")
            }

            fixture.coordinator.attach(fixture.coordinator.begin(), failed)
            advanceUntilIdle()
            assertEquals(
                AiOperationOutcome.Failure(AiError.Unknown),
                fixture.coordinator.state.value.requireVisible().outcome
            )

            siblingRelease.complete(Unit)
            advanceUntilIdle()
            assertEquals("sibling survived", sibling.await())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun reject_currentTokenPublishesTypedErrorAndStaleRejectIsIgnored() = runTest {
        val fixture = fixture()
        try {
            val stale = fixture.coordinator.begin()
            val current = fixture.coordinator.begin()

            assertFalse(fixture.coordinator.reject(stale, AiError.Unknown))
            assertTrue(fixture.coordinator.reject(current, AiError.NoActiveProfile))
            assertEquals(
                AiSheetState.Rejected(
                    error = AiError.NoActiveProfile,
                    target = AiExplanationTarget.Article(1)
                ),
                fixture.coordinator.state.value
            )
            val lateHandle = fixture.registry.attachOrStart("late-key") {
                AiClientResult.Success("late")
            }
            assertFalse(fixture.coordinator.attach(current, lateHandle))
            assertEquals(
                AiSheetState.Rejected(
                    error = AiError.NoActiveProfile,
                    target = AiExplanationTarget.Article(1)
                ),
                fixture.coordinator.state.value
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun beginAndDismiss_cancelPreviousObserverOnly() = runTest {
        val fixture = fixture()
        val firstRelease = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        try {
            val first = fixture.registry.attachOrStart("key-a") {
                firstRelease.await()
                AiClientResult.Success("first")
            }
            fixture.coordinator.attach(fixture.coordinator.begin(), first)
            runCurrent()
            assertEquals(1, fixture.activeObserverCount())

            val second = fixture.registry.attachOrStart("key-b") {
                secondRelease.await()
                AiClientResult.Success("second")
            }
            fixture.coordinator.attach(fixture.coordinator.begin(), second)
            runCurrent()
            assertEquals(1, fixture.activeObserverCount())

            fixture.coordinator.dismiss()
            runCurrent()
            assertEquals(0, fixture.activeObserverCount())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun stateToString_recursivelyRedactsTokenRefKeyAndExplanation() = runTest {
        val fixture = fixture()
        try {
            val handle = fixture.registry.attachOrStart("semantic-secret") {
                AiClientResult.Success("explanation-secret")
            }
            fixture.coordinator.attach(fixture.coordinator.begin(), handle)
            advanceUntilIdle()

            val rendered = fixture.coordinator.state.value.toString()
            assertFalse(rendered.contains("semantic-secret"))
            assertFalse(rendered.contains(handle.ref.operationId))
            assertFalse(rendered.contains("explanation-secret"))
        } finally {
            fixture.close()
        }
    }

    private fun TestScope.fixture(): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val observerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val registry = AiExplanationOperationRegistry(applicationScope)
        return Fixture(
            applicationScope,
            observerScope,
            registry,
            AiSheetCoordinator(registry, observerScope)
        )
    }

    private fun AiSheetState.requireLoading(): AiSheetState.Loading {
        assertTrue(this is AiSheetState.Loading)
        return this as AiSheetState.Loading
    }

    private fun AiSheetState.requireVisible(): AiSheetState.Visible {
        assertTrue(this is AiSheetState.Visible)
        return this as AiSheetState.Visible
    }

    private suspend fun AiSheetCoordinator.begin(): AiSheetRequestToken =
        begin(AiExplanationTarget.Article(1))

    private data class Fixture(
        val applicationScope: CoroutineScope,
        val observerScope: CoroutineScope,
        val registry: AiExplanationOperationRegistry,
        val coordinator: AiSheetCoordinator
    ) {
        fun activeObserverCount(): Int =
            observerScope.coroutineContext[Job]!!.children.count { it.isActive }

        fun close() {
            observerScope.cancel()
            applicationScope.cancel()
        }
    }

    private data class TestSentenceSnapshot(
        override val articleId: Long = 7,
        override val sentenceIndex: Int = 2,
        override val rawText: String = "private sentence",
        override val normalizedText: String = "private sentence",
        override val startOffset: Int = 0,
        override val endOffset: Int = 17
    ) : SelectedSentenceSnapshot
}
