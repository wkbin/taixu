package top.wkbin.taixu.harness.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedResourceTest {
    @Test fun `closed scope rejects allocation before calling the factory`() = runTest {
        val scope = SessionResourceScope()
        scope.close()
        var opens = 0
        try { scope.acquire({ opens++; Any() }) {}; fail("allocation accepted") }
        catch (_: IllegalStateException) { }
        assertEquals(0, opens)
    }

    @Test fun `failed allocation preserves primary failure and has nothing to release`() = runTest {
        val scope = SessionResourceScope()
        val primary = IllegalArgumentException("startup")
        var releases = 0
        try { scope.acquire<Any>({ throw primary }) { releases++ }; fail("failure lost") }
        catch (t: IllegalArgumentException) { assertEquals("startup", t.message) }
        scope.close()
        assertEquals(0, releases)
    }

    @Test fun `concurrent explicit close and scope close release a resource once`() = runTest {
        val scope = SessionResourceScope()
        var releases = 0
        val owned = scope.acquire({ Any() }) { releases++; yield() }
        awaitAll(async { owned.close() }, async { scope.close() })
        owned.close()
        assertEquals(1, releases)
    }

    @Test fun `failed explicit close remains owned for disposal retry`() = runTest {
        val scope = SessionResourceScope()
        var releases = 0
        val owned = scope.acquire({ Any() }) { if (++releases == 1) error("release") }
        try { owned.close(); fail("release failure lost") } catch (_: IllegalStateException) { }
        scope.close(); owned.close()
        assertEquals(2, releases)
    }

    @Test fun `caller cancellation releases a late factory result`() = runTest {
        val scope = SessionResourceScope()
        val gate = CompletableDeferred<Unit>()
        var releases = 0
        val opening = async {
            scope.acquire({ withContext(NonCancellable) { gate.await(); Any() } }) { releases++ }
        }
        runCurrent(); opening.cancel(); gate.complete(Unit); opening.join()
        scope.close()
        assertEquals(1, releases)
    }

    @Test fun `late allocation rollback failure remains reachable after close drains creation`() = runTest {
        val scope = SessionResourceScope()
        val gate = CompletableDeferred<Unit>()
        var releases = 0
        val opening = async {
            scope.acquire({ withContext(NonCancellable) { gate.await(); Any() } }) {
                if (++releases <= 2) error("release $releases")
            }
        }
        runCurrent()
        val closing = async { runCatching { scope.close() }.exceptionOrNull() }
        runCurrent(); assertFalse(closing.isCompleted)
        gate.complete(Unit)
        assertTrue(closing.await() is ResourceCleanupException)
        opening.join(); scope.close(); scope.close()
        assertEquals(3, releases)
    }
}
