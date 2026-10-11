package top.wkbin.taixu.harness.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionResourceScopeTest {
    @Test fun `all cleanup failures survive coroutine stack recovery`() = runTest {
        val scope = SessionResourceScope()
        val storage = IllegalArgumentException("storage")
        val connection = IllegalStateException("connection")
        scope.own { throw storage }
        scope.own { yield(); throw connection }
        val failure = async {
            try { scope.close(); null } catch (t: ResourceCleanupException) { t }
        }.await()!!
        assertEquals(listOf(connection, storage), failure.failures)
        assertSame(connection, failure.cause)
        assertSame(storage, failure.suppressed.single())
    }

    @Test fun `closing from nested activity fails promptly instead of deadlocking`() = runTest {
        val outer = SessionResourceScope()
        val inner = SessionResourceScope()
        outer.activity { inner.activity {
            try { outer.close(); fail("self close accepted") } catch (_: IllegalStateException) { }
        } }
        outer.close(); inner.close()
    }
    @Test fun `close rejects admission drains finally and releases in reverse order`() = runTest {
        val scope = SessionResourceScope()
        val events = mutableListOf<String>()
        val drain = CompletableDeferred<Unit>()
        scope.own { events += "storage" }
        scope.own { events += "listener" }
        val activity = launch { scope.activity {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { drain.await(); events += "drained" } }
        } }
        runCurrent()
        val close = async { scope.close() }
        runCurrent()
        assertFalse(close.isCompleted)
        assertTrue(events.isEmpty())
        try { scope.own {}; fail("admission must be closed") } catch (_: IllegalStateException) { }
        drain.complete(Unit)
        close.await()
        activity.join()
        assertEquals(listOf("drained", "listener", "storage"), events)
        scope.close()
        assertEquals(3, events.size)
    }

    @Test fun `caller cancellation cannot remove an activity before its cleanup drains`() = runTest {
        val scope = SessionResourceScope()
        val drain = CompletableDeferred<Unit>()
        var released = false
        scope.own { released = true }
        val activity = launch { scope.activity {
            try { awaitCancellation() } finally { withContext(NonCancellable) { drain.await() } }
        } }
        runCurrent()
        activity.cancel()
        runCurrent()
        val close = async { scope.close() }
        runCurrent()
        assertFalse(released)
        drain.complete(Unit)
        close.await()
        assertTrue(released)
    }

    @Test fun `setup failure rolls back in reverse order and preserves original error`() = runTest {
        val events = mutableListOf<Int>()
        val original = IllegalArgumentException("setup")
        try {
            SessionResourceScope.create<Unit> { scope ->
                scope.own { events += 1 }
                scope.own { events += 2; error("cleanup") }
                throw original
            }
            fail("setup must fail")
        } catch (t: IllegalArgumentException) {
            assertSame(original, t)
            assertEquals(1, t.suppressed.size)
        }
        assertEquals(listOf(2, 1), events)
    }

    @Test fun `cleanup failure does not skip other resources and only failed cleanup retries`() = runTest {
        val scope = SessionResourceScope()
        val events = mutableListOf<String>()
        var fails = true
        scope.own { events += "storage" }
        scope.own { events += "connection"; if (fails) error("close") }
        try { scope.close(); fail("close must report failure") } catch (_: IllegalStateException) { }
        assertEquals(listOf("connection", "storage"), events)
        fails = false
        scope.close()
        assertEquals(listOf("connection", "storage", "connection"), events)
    }

    @Test fun `concurrent closes release exactly once`() = runTest {
        val scope = SessionResourceScope()
        var releases = 0
        scope.own { yield(); releases++ }
        listOf(async { scope.close() }, async { scope.close() }).awaitAll()
        assertEquals(1, releases)
    }
}
