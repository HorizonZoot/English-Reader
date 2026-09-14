package io.github.zoot.englishreader.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class AiConnectionTestRegistryTest {
    @Test
    fun run_sameProfile_concurrentWaitersShareOwnedOperation() = runTest {
        val registry = AiConnectionTestRegistry(this)
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()

        val first = async {
            registry.run("p1") {
                calls.incrementAndGet()
                gate.await()
                AiClientResult.Success("ok")
            }
        }
        runCurrent()
        val second = async {
            registry.run("p1") {
                calls.incrementAndGet()
                AiClientResult.Success("duplicate")
            }
        }
        runCurrent()

        assertEquals(setOf("p1"), registry.inFlightProfileIds.value)
        assertEquals(1, calls.get())
        gate.complete(Unit)
        assertEquals(AiClientResult.Success("ok"), first.await())
        assertEquals(AiClientResult.Success("ok"), second.await())
        advanceUntilIdle()
        assertTrue(registry.inFlightProfileIds.value.isEmpty())
    }

    @Test
    fun run_cancelledWaiter_doesNotCancelOrRemoveOwner() = runTest {
        val registry = AiConnectionTestRegistry(this)
        val gate = CompletableDeferred<Unit>()
        val owner = async {
            registry.run("p1") {
                gate.await()
                AiClientResult.Success("ok")
            }
        }
        runCurrent()
        val waiter = async { registry.run("p1") { AiClientResult.Success("duplicate") } }
        runCurrent()

        waiter.cancel()
        runCurrent()
        assertEquals(setOf("p1"), registry.inFlightProfileIds.value)

        gate.complete(Unit)
        assertEquals(AiClientResult.Success("ok"), owner.await())
        advanceUntilIdle()
        assertTrue(registry.inFlightProfileIds.value.isEmpty())
    }

    @Test
    fun run_differentProfiles_areIndependentAndRepeatable() = runTest {
        val registry = AiConnectionTestRegistry(this)
        val p1Gate = CompletableDeferred<Unit>()
        val p2Gate = CompletableDeferred<Unit>()
        val p1 = async { registry.run("p1") { p1Gate.await(); AiClientResult.Success("one") } }
        val p2 = async { registry.run("p2") { p2Gate.await(); AiClientResult.Success("two") } }
        runCurrent()
        assertEquals(setOf("p1", "p2"), registry.inFlightProfileIds.value)
        p1Gate.complete(Unit)
        p2Gate.complete(Unit)
        p1.await()
        p2.await()
        advanceUntilIdle()

        assertEquals(
            AiClientResult.Success("again"),
            registry.run("p1") { AiClientResult.Success("again") }
        )
    }

    @Test
    fun run_ownerScopeCancellation_cancelsResultAndCleansState() = runTest {
        val ownerScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val registry = AiConnectionTestRegistry(ownerScope)
        val never = CompletableDeferred<Unit>()
        val waiter = async {
            registry.run("p1") {
                never.await()
                AiClientResult.Success("unreachable")
            }
        }
        runCurrent()

        ownerScope.cancel()
        runCurrent()

        assertTrue(runCatching { waiter.await() }.exceptionOrNull() is CancellationException)
        assertTrue(registry.inFlightProfileIds.value.isEmpty())
    }

    @Test
    fun run_completedCall_isAvailableAndImmediatelyRepeatableBeforeWaiterReturns() = runTest {
        val ownerScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler)
        )
        val registry = AiConnectionTestRegistry(ownerScope)
        val calls = AtomicInteger()
        val results = mutableListOf<AiClientResult>()

        val caller = launch(Dispatchers.Unconfined) {
            results += registry.run("p1") {
                calls.incrementAndGet()
                AiClientResult.Success("first")
            }
            assertTrue(registry.inFlightProfileIds.value.isEmpty())
            results += registry.run("p1") {
                calls.incrementAndGet()
                AiClientResult.Success("second")
            }
        }

        advanceUntilIdle()
        caller.join()

        assertEquals(2, calls.get())
        assertEquals(
            listOf(AiClientResult.Success("first"), AiClientResult.Success("second")),
            results
        )
        ownerScope.cancel()
    }
}
