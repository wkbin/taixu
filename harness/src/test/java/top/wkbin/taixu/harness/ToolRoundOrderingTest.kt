package top.wkbin.taixu.harness

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ToolRoundOrderingTest {
    @Test fun `reads overlap but mutations wait and following reads see the write`() = runBlocking {
        withTimeout(5_000) {
            val dispatcher = ToolRoundDispatcher()
            val entered = List(2) { CompletableDeferred<Unit>() }
            val release = CompletableDeferred<Unit>()
            val started = mutableListOf<Int>()
            var value = "before"
            val work = async {
                dispatcher.dispatch((0..4).toList(), isParallelSafe = { it != 2 }) { item, _ ->
                    started += item
                    when (item) {
                        0, 1 -> { entered[item].complete(Unit); release.await(); assertEquals("before", value) }
                        2 -> value = "after"
                        else -> assertEquals("after", value)
                    }
                }
            }
            try {
                entered.awaitAll()
                assertEquals(listOf(0, 1), started)
                release.complete(Unit)
                work.await()
                assertEquals(listOf(0, 1, 2, 3, 4), started)
            } finally { release.complete(Unit); work.cancel() }
        }
    }

    @Test fun `parallelism bounds the whole read group and approval skips queued calls and barrier`() = runBlocking {
        withTimeout(5_000) {
            val entered = List(2) { CompletableDeferred<Unit>() }
            val release = CompletableDeferred<Unit>()
            val started = mutableListOf<Int>()
            val work = async {
                ToolRoundDispatcher().dispatch((0..6).toList(), parallelism = 2,
                    isParallelSafe = { it < 5 }) { item, pause ->
                    started += item
                    entered[item].complete(Unit)
                    release.await()
                    pause.abort()
                }
            }
            try {
                entered.awaitAll()
                assertEquals(listOf(0, 1), started)
                release.complete(Unit)
                work.await()
                assertEquals(listOf(0, 1), started)
            } finally { release.complete(Unit); work.cancel() }
        }
    }

    @Test fun `self coordinated tool is a barrier without holding workspace lock`() = runBlocking {
        withTimeout(5_000) {
            val dispatcher = ToolRoundDispatcher()
            val started = mutableListOf<Int>()
            dispatcher.dispatch(listOf(0, 1, 2), mutationScope = "workspace",
                isParallelSafe = { it != 1 }, needsMutationLock = { false }) { item, _ ->
                if (item == 1) {
                    assertEquals(listOf(0), started)
                    // Child mutation must be able to take the parent's scope lock.
                    dispatcher.withMutationLock("workspace") { started += item }
                } else started += item
            }
            assertEquals(listOf(0, 1, 2), started)
            assertEquals(0, dispatcher.retainedMutationScopeCount)
        }
    }

    @Test fun `tool cancellation cancels siblings waiting to publish instead of hanging`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val publication = OrderedToolPublication(2)
            val job = async {
                ToolRoundDispatcher().dispatch(listOf(0, 1), isParallelSafe = { true }) { item, _ ->
                    publication.intent(item) {}
                    if (item == 0) {
                        entered.await()
                        throw CancellationException("checkpoint cancelled")
                    }
                    entered.complete(Unit)
                    publication.result(item) { fail("No result may pass the cancelled call") }
                }
            }
            assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        }
    }

    @Test fun `failed result commit cancels pending publications and skips next mutation`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val publication = OrderedToolPublication(2)
            val published = mutableListOf<Int>()
            var mutationRan = false
            val error = runCatching {
                ToolRoundDispatcher().dispatch(listOf(0, 1, 2), isParallelSafe = { it < 2 }) { item, _ ->
                    if (item == 2) { mutationRan = true; return@dispatch }
                    publication.intent(item) {}
                    if (item == 0) entered.await() else entered.complete(Unit)
                    publication.result(item) {
                        if (item == 0) error("commit unavailable")
                        published += item
                    }
                }
            }.exceptionOrNull()
            assertEquals("commit unavailable", error?.message)
            assertTrue(published.isEmpty())
            assertFalse(mutationRan)
        }
    }
}
