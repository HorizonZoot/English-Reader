package io.github.zoot.englishreader.data.repository

import io.github.zoot.englishreader.data.ai.AiClientResult
import io.github.zoot.englishreader.data.ai.AiError
import io.github.zoot.englishreader.data.ai.TimeoutPhase
import io.github.zoot.englishreader.model.AiOperationOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class AiExplanationOperationRegistryTest {

    @Test
    fun attachOrStart_sameKeyWhileRunning_reusesOperation() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        var starts = 0
        try {
            val first = fixture.registry.attachOrStart("secret-key") {
                starts++
                release.await()
                AiClientResult.Success("first")
            }
            runCurrent()
            val second = fixture.registry.attachOrStart("secret-key") {
                starts++
                AiClientResult.Success("duplicate")
            }

            assertEquals(first.ref, second.ref)
            assertEquals(1, starts)
            release.complete(Unit)
            advanceUntilIdle()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun attachOrStart_differentKeys_runIndependently() = runTest {
        val fixture = fixture()
        try {
            val first = fixture.registry.attachOrStart("key-a") {
                AiClientResult.Success("first")
            }
            val second = fixture.registry.attachOrStart("key-b") {
                AiClientResult.Success("second")
            }
            advanceUntilIdle()

            assertNotEquals(first.ref, second.ref)
            assertEquals(
                AiOperationOutcome.Success("first"),
                first.awaitOutcome()
            )
            assertEquals(
                AiOperationOutcome.Success("second"),
                second.awaitOutcome()
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun attachOrStart_eachTypedFailure_preservesAiError() = runTest {
        val fixture = fixture()
        try {
            val errors = listOf(
                AiError.NoActiveProfile,
                AiError.ProfileNotFound,
                AiError.CredentialMissing,
                AiError.CredentialStorageUnavailable,
                AiError.InvalidEndpoint,
                AiError.InputTooLong(actualChars = 8_001, maxChars = 8_000),
                AiError.HttpAuth(401),
                AiError.HttpNotFound(),
                AiError.RequestTimeout(),
                AiError.PayloadTooLarge(),
                AiError.RateLimited(42),
                AiError.Server(500),
                AiError.UnexpectedHttp(418),
                AiError.Timeout(TimeoutPhase.CONNECT),
                AiError.Offline,
                AiError.DnsFailure,
                AiError.TlsFailure,
                AiError.MalformedResponse,
                AiError.NoContent,
                AiError.Unknown
            )

            errors.forEachIndexed { index, error ->
                val handle = fixture.registry.attachOrStart("key-$index") {
                    AiClientResult.Failure(error)
                }
                advanceUntilIdle()

                assertEquals(
                    AiOperationOutcome.Failure(error),
                    handle.awaitOutcome()
                )
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun attachOrStart_unexpectedException_mapsToUnknownWithoutMessage() = runTest {
        val fixture = fixture()
        try {
            val handle = fixture.registry.attachOrStart("key-a") {
                error("private endpoint and prompt")
            }
            advanceUntilIdle()

            val outcome = handle.awaitOutcome()
            assertEquals(AiOperationOutcome.Failure(AiError.Unknown), outcome)
            assertFalse(outcome.toString().contains("private endpoint"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancel_matchingReference_returnsCancelledAndCreatesReplacement() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        try {
            val first = fixture.registry.attachOrStart("key-a") {
                release.await()
                AiClientResult.Success("never")
            }
            runCurrent()

            assertTrue(fixture.registry.cancel(first.ref))
            assertEquals(AiOperationOutcome.Cancelled, first.awaitOutcome())

            val replacement = fixture.registry.attachOrStart("key-a") {
                AiClientResult.Success("replacement")
            }
            advanceUntilIdle()
            assertNotEquals(first.ref.operationId, replacement.ref.operationId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancel_staleReference_doesNotCancelReplacement() = runTest {
        val fixture = fixture()
        val firstRelease = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        try {
            val first = fixture.registry.attachOrStart("key-a") {
                firstRelease.await()
                AiClientResult.Success("first")
            }
            runCurrent()
            assertTrue(fixture.registry.cancel(first.ref))

            val second = fixture.registry.attachOrStart("key-a") {
                secondRelease.await()
                AiClientResult.Success("second")
            }
            runCurrent()

            assertFalse(fixture.registry.cancel(first.ref))
            secondRelease.complete(Unit)
            advanceUntilIdle()
            assertEquals(AiOperationOutcome.Success("second"), second.awaitOutcome())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancel_afterTerminalClaim_returnsFalseAndPreservesOutcome() = runTest {
        val fixture = fixture()
        val beforeReturn = CompletableDeferred<Unit>()
        val allowReturn = CompletableDeferred<Unit>()
        try {
            val handle = fixture.registry.attachOrStart("key-a") { gate ->
                assertTrue(gate.tryBeginCacheCommit())
                gate.completeCacheCommit()
                beforeReturn.complete(Unit)
                allowReturn.await()
                AiClientResult.Success("committed")
            }
            runCurrent()
            beforeReturn.await()

            assertFalse(fixture.registry.cancel(handle.ref))
            allowReturn.complete(Unit)
            advanceUntilIdle()
            assertEquals(AiOperationOutcome.Success("committed"), handle.awaitOutcome())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancel_afterOperationCancellation_returnsFalseAndPreservesCancelledOutcome() = runTest {
        val fixture = fixture()
        try {
            val handle = fixture.registry.attachOrStart("key-a") {
                throw CancellationException("operation cancelled")
            }
            advanceUntilIdle()

            assertFalse(fixture.registry.cancel(handle.ref))
            assertEquals(AiOperationOutcome.Cancelled, handle.awaitOutcome())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun completedRecord_isNotReused() = runTest {
        val fixture = fixture()
        try {
            val first = fixture.registry.attachOrStart("key-a") {
                AiClientResult.Success("first")
            }
            advanceUntilIdle()
            val second = fixture.registry.attachOrStart("key-a") {
                AiClientResult.Success("second")
            }
            advanceUntilIdle()

            assertNotEquals(first.ref.operationId, second.ref.operationId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun completion_oldOperationAfterReplacement_doesNotRemoveReplacement() = runTest {
        val fixture = fixture()
        val secondRelease = CompletableDeferred<Unit>()
        try {
            val first = fixture.registry.attachOrStart("key-a") {
                CompletableDeferred<Unit>().await()
                AiClientResult.Success("never")
            }
            runCurrent()
            assertTrue(fixture.registry.cancel(first.ref))

            val second = fixture.registry.attachOrStart("key-a") {
                secondRelease.await()
                AiClientResult.Success("second")
            }
            runCurrent()
            advanceUntilIdle()

            val third = fixture.registry.attachOrStart("key-a") {
                AiClientResult.Success("unexpected replacement")
            }
            assertEquals(second.ref, third.ref)

            secondRelease.complete(Unit)
            advanceUntilIdle()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedOperation_doesNotCancelSiblingOperation() = runTest {
        val fixture = fixture()
        val siblingRelease = CompletableDeferred<Unit>()
        try {
            val failing = fixture.registry.attachOrStart("key-fail") {
                error("failure")
            }
            val sibling = fixture.registry.attachOrStart("key-ok") {
                siblingRelease.await()
                AiClientResult.Success("survivor")
            }
            runCurrent()
            assertEquals(
                AiOperationOutcome.Failure(AiError.Unknown),
                failing.awaitOutcome()
            )

            siblingRelease.complete(Unit)
            advanceUntilIdle()
            assertEquals(AiOperationOutcome.Success("survivor"), sibling.awaitOutcome())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun attachOrStart_blankCacheKey_rejectsInput() = runTest {
        val fixture = fixture()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    fixture.registry.attachOrStart("   ") {
                        AiClientResult.Success("unused")
                    }
                }
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun operationTypes_toString_redactKeyIdAndContent() = runTest {
        val fixture = fixture()
        try {
            val handle = fixture.registry.attachOrStart("semantic-secret") {
                AiClientResult.Success("explanation-secret")
            }
            advanceUntilIdle()

            val rendered = listOf(handle.ref, handle, handle.awaitOutcome()).joinToString()
            assertFalse(rendered.contains("semantic-secret"))
            assertFalse(rendered.contains(handle.ref.operationId))
            assertFalse(rendered.contains("explanation-secret"))
        } finally {
            fixture.close()
        }
    }

    private fun TestScope.fixture(): Fixture {
        val applicationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return Fixture(applicationScope, AiExplanationOperationRegistry(applicationScope))
    }

    private data class Fixture(
        val applicationScope: CoroutineScope,
        val registry: AiExplanationOperationRegistry
    ) {
        fun close() = applicationScope.cancel()
    }
}
