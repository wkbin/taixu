package top.wkbin.taixu.harness.environment

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.runtime.environment.*

@OptIn(ExperimentalCoroutinesApi::class)
class EnvironmentCreationConcurrencyTest {
    private fun world(owner: String, workspace: String = "/workspace/p") =
        FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner))

    @Test fun `slow creation does not block another session creation or disposal`() = runTest {
        val finishOpen = CompletableDeferred<Unit>()
        val slow = world("slow")
        val fast = world("fast")
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, _, _ ->
            if (owner == "slow") { finishOpen.await(); slow } else fast
        })
        val creating = async { registry.environment("slow", "/workspace/p") }
        runCurrent()
        val independent = async {
            assertSame(fast, registry.environment("fast", "/workspace/p"))
            registry.closeSession("fast")
        }
        runCurrent()
        assertTrue(independent.isCompleted)
        assertFalse(creating.isCompleted)
        assertEquals(1, fast.closes)
        finishOpen.complete(Unit)
        assertSame(slow, creating.await())
        registry.closeSession("slow")
    }

    @Test fun `closing session cancels setup and waits for its finally before completing`() = runTest {
        val drain = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { _, _, _ ->
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { events += "cancelled"; drain.await(); events += "drained" } }
        })
        val creating = async { runCatching { registry.environment("one", "/workspace/p") } }
        runCurrent()
        val closing = async { registry.closeSession("one"); events += "closed" }
        runCurrent()
        assertEquals(listOf("cancelled"), events)
        assertFalse(closing.isCompleted)
        drain.complete(Unit)
        closing.await()
        assertTrue(creating.await().exceptionOrNull() is CancellationException)
        assertEquals(listOf("cancelled", "drained", "closed"), events)
        try { registry.environment("one", "/workspace/p"); fail("disposed session reopened") }
        catch (_: IllegalStateException) { }
    }

    @Test fun `late environment returned after cancellation is released without entering tool body`() = runTest {
        val finishOpen = CompletableDeferred<Unit>()
        val late = world("one")
        var toolRan = false
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { _, _, _ ->
            withContext(NonCancellable) { finishOpen.await(); late }
        })
        val creating = async { runCatching {
            registry.activity("one", "/workspace/p") { toolRan = true }
        } }
        runCurrent()
        val closing = async { registry.closeSession("one") }
        runCurrent()
        assertFalse(closing.isCompleted)
        assertEquals(0, late.closes)
        finishOpen.complete(Unit)
        closing.await()
        assertTrue(creating.await().exceptionOrNull() is CancellationException)
        assertFalse(toolRan)
        assertEquals(1, late.closes)
        registry.closeSession("one")
        assertEquals(1, late.closes)
    }

    @Test fun `same binding shares one setup and cancelling a waiter does not cancel creation`() = runTest {
        val finishOpen = CompletableDeferred<Unit>()
        val environment = world("one")
        var opens = 0
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { _, _, _ ->
            opens++; finishOpen.await(); environment
        })
        val first = async { registry.environment("one", "/workspace/p") }
        runCurrent()
        val cancelled = async { registry.environment("one", "/workspace/p") }
        val second = async { registry.environment("one", "/workspace/p") }
        runCurrent()
        cancelled.cancelAndJoin()
        assertEquals(1, opens)
        assertFalse(first.isCompleted)
        finishOpen.complete(Unit)
        assertSame(environment, first.await())
        assertSame(environment, second.await())
        assertEquals(0, environment.closes)
        registry.closeSession("one")
        assertEquals(1, environment.closes)
    }

    @Test fun `workspace cannot change while its first environment is still opening`() = runTest {
        val finishOpen = CompletableDeferred<Unit>()
        var opens = 0
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
            opens++; finishOpen.await(); world(owner, workspace)
        })
        val first = async { registry.environment("one", "/workspace/a") }
        runCurrent()
        val changed = async { runCatching { registry.environment("one", "/workspace/b") } }
        runCurrent()
        assertTrue(changed.isCompleted)
        assertTrue(changed.await().exceptionOrNull() is IllegalStateException)
        assertEquals(1, opens)
        finishOpen.complete(Unit)
        first.await()
        registry.closeSession("one")
    }

    @Test fun `child setup is independent and owner close drains late child without stopping parent`() = runTest {
        val finishOpen = CompletableDeferred<Unit>()
        val parent = world("one")
        val slow = world("one::slow")
        val fast = world("one::fast")
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, _, _ ->
            when (owner) {
                "one" -> parent
                "one::slow" -> withContext(NonCancellable) { finishOpen.await(); slow }
                else -> fast
            }
        })
        registry.environment("one", "/workspace/p")
        val pending = async { runCatching { registry.activity("one", "/workspace/p", "slow") {
            fail("closed child entered tool body")
        } } }
        runCurrent()
        val independent = async {
            registry.activity("one", "/workspace/p", "fast") {
                assertSame(fast, registry.environment("one", "/workspace/p"))
            }
            registry.closeOwner("one", "fast")
        }
        runCurrent()
        assertTrue(independent.isCompleted)
        assertEquals(1, fast.closes)
        val closing = async { registry.closeOwner("one", "slow") }
        runCurrent()
        assertFalse(closing.isCompleted)
        finishOpen.complete(Unit)
        closing.await()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertEquals(1, slow.closes)
        assertEquals(0, parent.closes)
        assertSame(parent, registry.environment("one", "/workspace/p"))
        registry.closeSession("one")
        assertEquals(1, parent.closes)
        assertEquals(1, slow.closes)
    }

    @Test fun `cancelled creator cleans late result before a waiting request retries setup`() = runTest {
        val finishOpen = CompletableDeferred<Unit>()
        val abandoned = world("one")
        val replacement = world("one")
        var opens = 0
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { _, _, _ ->
            opens++
            if (opens == 1) withContext(NonCancellable) { finishOpen.await(); abandoned }
            else { assertEquals(1, abandoned.closes); replacement }
        })
        val creator = async { runCatching { registry.environment("one", "/workspace/p") } }
        runCurrent()
        val waiter = async { registry.environment("one", "/workspace/p") }
        runCurrent()
        creator.cancel()
        runCurrent()
        finishOpen.complete(Unit)
        creator.join()
        assertSame(replacement, waiter.await())
        assertEquals(2, opens)
        registry.closeSession("one")
        assertEquals(1, abandoned.closes)
        assertEquals(1, replacement.closes)
    }

    @Test fun `closing owner during parent setup prevents a later child acquisition`() = runTest {
        val finishOpen = CompletableDeferred<Unit>()
        val parent = world("one")
        var opens = 0
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, _, _ ->
            opens++; assertEquals("one", owner); finishOpen.await(); parent
        })
        val pending = async { runCatching { registry.activity("one", "/workspace/p", "lane") { } } }
        runCurrent()
        registry.closeOwner("one", "lane")
        finishOpen.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is IllegalStateException)
        assertEquals(1, opens)
        assertEquals(0, parent.closes)
        registry.closeSession("one")
        assertEquals(1, parent.closes)
    }

    @Test fun `already closed owner cannot initiate a parent environment`() = runTest {
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { _, _, _ ->
            error("closed owner acquired parent")
        })
        registry.closeOwner("one", "lane")
        try { registry.activity("one", "/workspace/p", "lane") { }; fail("owner reopened") }
        catch (t: IllegalStateException) { assertTrue(t.message.orEmpty().contains("disposed")) }
    }

    @Test fun `completed setup cannot publish its environment after session disposal`() = runTest {
        val environment = world("one")
        lateinit var registry: SessionExecutionEnvironments
        registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { _, _, _ ->
            backgroundScope.launch { registry.closeSession("one") }
            environment
        })
        val creating = async { runCatching { registry.environment("one", "/workspace/p") } }
        runCurrent()
        assertTrue(creating.await().isFailure)
        assertEquals(1, environment.closes)
    }

    @Test fun `slow failed rollback does not block an unrelated session`() = runTest {
        val finishRollback = CompletableDeferred<Unit>()
        val parent = world("one")
        val invalid = world("one::lane")
        var attempts = 0
        val other = world("other")
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, _, _ ->
            when (owner) {
                "one" -> parent
                "other" -> other
                else -> object : ExecutionEnvironment by invalid {
                    override val id = invalid.id.copy(backend = "invalid")
                    override suspend fun close() {
                        if (++attempts == 1) { finishRollback.await(); error("rollback") }
                        invalid.close()
                    }
                }
            }
        })
        val failed = async { runCatching { registry.activity("one", "/workspace/p", "lane") { } } }
        runCurrent()
        assertEquals(1, attempts)
        val independent = async {
            assertSame(other, registry.environment("other", "/workspace/p"))
            registry.closeSession("other")
        }
        runCurrent()
        assertTrue(independent.isCompleted)
        assertEquals(1, other.closes)
        assertFalse(failed.isCompleted)
        finishRollback.complete(Unit)
        assertTrue(failed.await().isFailure)
        registry.closeOwner("one", "lane")
        registry.closeSession("one")
        assertEquals(2, attempts)
        assertEquals(1, invalid.closes)
    }
}
