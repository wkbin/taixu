package top.wkbin.taixu.harness.environment

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.core.database.TerminalSessionEntity

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalLifecycleTest {
    @Test fun `late environment after creator cancellation is closed without starting a terminal`() = runTest {
        val fixture = TerminalLifecycleFixture()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeOpen = { withContext(NonCancellable) { gate.await() } }
        val creating = async { fixture.manager.createSession(id = "one") }
        runCurrent(); creating.cancel(); gate.complete(Unit); creating.join()
        assertTrue(fixture.processes.isEmpty())
        assertEquals(1, fixture.worlds.getValue("one").closes)
        assertTrue(fixture.manager.handles.value.isEmpty())
        assertTrue(fixture.repository.contents.isEmpty())
    }

    @Test fun `single close cancels creation drains late environment and does not create fallback`() = runTest {
        val fixture = TerminalLifecycleFixture()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeOpen = { withContext(NonCancellable) { gate.await() } }
        val creating = async { runCatching { fixture.manager.createSession(id = "one") } }
        runCurrent()
        val closing = async { fixture.manager.closeSession("one") }
        runCurrent(); assertFalse(closing.isCompleted)
        gate.complete(Unit); closing.await()
        assertTrue(creating.await().exceptionOrNull() is CancellationException)
        assertEquals(1, fixture.worlds.size)
        assertEquals(1, fixture.worlds.getValue("one").closes)
        assertTrue(fixture.manager.handles.value.isEmpty())
    }

    @Test fun `failed startup keeps failed cleanup reachable and refuses to overwrite its owner`() = runTest {
        val fixture = TerminalLifecycleFixture()
        fixture.construct = { error("constructor failed") }
        fixture.failCloses["one"] = 1
        try { fixture.manager.createSession(id = "one"); fail("constructor succeeded") }
        catch (t: IllegalStateException) { assertEquals("constructor failed", t.message) }
        assertEquals(1, fixture.closeAttempts["one"])
        try { fixture.manager.createSession(id = "one"); fail("cleanup owner overwritten") }
        catch (_: IllegalArgumentException) { }
        fixture.manager.closeSession("one")
        assertEquals(2, fixture.closeAttempts["one"])
        assertEquals(1, fixture.worlds.getValue("one").closes)
        assertTrue(fixture.processes.isEmpty())
    }

    @Test fun `failed persistence restores previous row and retries only failed process release`() = runTest {
        val fixture = TerminalLifecycleFixture()
        val old = TerminalSessionEntity("one", "old", "/old", 1, 7, "debian")
        fixture.repository.contents["one"] = old
        fixture.repository.afterUpsert = { if (it.label != "old") error("save failed") }
        fixture.construct = { it.finishFailures = 1 }
        try { fixture.manager.createSession(id = "one"); fail("save succeeded") }
        catch (t: IllegalStateException) { assertEquals("save failed", t.message) }
        assertEquals(old, fixture.repository.contents["one"])
        assertEquals(1, fixture.processes.single().finishes)
        assertEquals(1, fixture.worlds.getValue("one").closes)
        fixture.manager.closeSession("one")
        assertEquals(2, fixture.processes.single().finishes)
        assertEquals(1, fixture.worlds.getValue("one").closes)
        assertEquals(old, fixture.repository.contents["one"])
    }

    @Test fun `failed row rollback remains retryable after native resources are already closed`() = runTest {
        val fixture = TerminalLifecycleFixture()
        fixture.repository.afterUpsert = { error("save") }
        var failures = 1
        fixture.repository.beforeDelete = { if (failures-- > 0) error("delete") }
        try { fixture.manager.createSession(id = "one"); fail("save succeeded") }
        catch (_: IllegalStateException) { }
        assertTrue(fixture.repository.contents.containsKey("one"))
        fixture.manager.closeSession("one")
        assertTrue(fixture.repository.contents.isEmpty())
        assertEquals(1, fixture.processes.single().finishes)
        assertEquals(1, fixture.worlds.getValue("one").closes)
    }

    @Test fun `bulk close attempts all terminals and retries failed environment without finishing process twice`() = runTest {
        val fixture = TerminalLifecycleFixture()
        fixture.manager.createSession(id = "one"); fixture.manager.createSession(id = "two")
        fixture.failCloses["one"] = 1
        try { fixture.manager.closeAllSessions(); fail("failure swallowed") }
        catch (_: IllegalStateException) { }
        assertEquals(listOf(1, 1), fixture.processes.map { it.finishes })
        assertEquals(1, fixture.worlds.getValue("two").closes)
        assertEquals(listOf("one"), fixture.manager.handles.value.map { it.id })
        fixture.manager.write("one", "blocked".toByteArray())
        assertTrue(fixture.processes[0].inputs.isEmpty())
        fixture.manager.closeAllSessions()
        assertEquals(listOf(1, 1), fixture.processes.map { it.finishes })
        assertEquals(1, fixture.worlds.getValue("one").closes)
        assertTrue(fixture.repository.contents.isEmpty())
        assertTrue(fixture.manager.handles.value.isEmpty())
    }

    @Test fun `bulk close drains pending creation and rejects acquisition until cleanup finishes`() = runTest {
        val fixture = TerminalLifecycleFixture()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeOpen = { withContext(NonCancellable) { gate.await() } }
        val creating = async { runCatching { fixture.manager.createSession(id = "one") } }
        runCurrent()
        val closing = async { fixture.manager.closeAllSessions() }
        runCurrent()
        try { fixture.manager.createSession(id = "two"); fail("creation admitted during close") }
        catch (_: IllegalStateException) { }
        assertFalse(closing.isCompleted)
        gate.complete(Unit); closing.await(); creating.await()
        assertEquals(1, fixture.worlds.getValue("one").closes)
        assertTrue(fixture.processes.isEmpty())
    }

    @Test fun `concurrent close of last terminal creates one fallback`() = runTest {
        val fixture = TerminalLifecycleFixture()
        fixture.manager.createSession(id = "one")
        val gate = CompletableDeferred<Unit>()
        fixture.beforeClose = { if (it == "one") gate.await() }
        val a = async { fixture.manager.closeSession("one") }
        val b = async { fixture.manager.closeSession("one") }
        runCurrent(); gate.complete(Unit); awaitAll(a, b)
        assertEquals(1, fixture.manager.handles.value.size)
        assertEquals("主终端", fixture.manager.handles.value.single().label)
        assertEquals(1, fixture.processes[0].finishes)
        fixture.manager.closeAllSessions()
    }

    @Test fun `bulk close supersedes single close without creating a new fallback afterward`() = runTest {
        val fixture = TerminalLifecycleFixture()
        fixture.manager.createSession(id = "one")
        val gate = CompletableDeferred<Unit>()
        fixture.beforeClose = { gate.await() }
        val single = async { fixture.manager.closeSession("one") }
        runCurrent()
        val all = async { fixture.manager.closeAllSessions() }
        runCurrent(); gate.complete(Unit); awaitAll(single, all)
        assertTrue(fixture.manager.handles.value.isEmpty())
        assertEquals(1, fixture.processes.size)
        assertEquals(1, fixture.processes.single().finishes)
    }

    @Test fun `cancelled persistence cannot publish a terminal after the write has completed`() = runTest {
        val fixture = TerminalLifecycleFixture()
        val gate = CompletableDeferred<Unit>()
        fixture.repository.afterUpsert = { withContext(NonCancellable) { gate.await() } }
        val creating = async { fixture.manager.createSession(id = "one") }
        runCurrent(); creating.cancel(); gate.complete(Unit); creating.join()
        assertTrue(fixture.repository.contents.isEmpty())
        assertTrue(fixture.manager.handles.value.isEmpty())
        assertEquals(1, fixture.processes.single().finishes)
        assertEquals(1, fixture.worlds.getValue("one").closes)
    }

    @Test fun `close from a creation callback rejects self join without cancelling creation`() = runTest {
        val fixture = TerminalLifecycleFixture()
        fixture.repository.afterUpsert = { row -> withContext(CoroutineName("creation callback")) {
            try { fixture.manager.closeSession(row.id); fail("self close accepted") }
            catch (t: IllegalStateException) { assertTrue(t.message.orEmpty().contains("own creation")) }
            try { fixture.manager.closeAllSessions(); fail("self bulk close accepted") }
            catch (t: IllegalStateException) { assertTrue(t.message.orEmpty().contains("own creation")) }
        } }
        fixture.manager.createSession(id = "one")
        assertEquals(1, fixture.manager.handles.value.size)
        fixture.manager.closeAllSessions()
    }

    @Test fun `an old concurrent close cannot delete a replacement terminal with the same id`() = runTest {
        val fixture = TerminalLifecycleFixture()
        fixture.manager.createSession(id = "one")
        val firstDelete = CompletableDeferred<Unit>()
        val staleDelete = CompletableDeferred<Unit>()
        var attempts = 0
        fixture.repository.beforeDelete = {
            if (++attempts == 1) firstDelete.await() else staleDelete.await()
        }
        val first = async { fixture.manager.closeSession("one") }
        runCurrent()
        val second = async { fixture.manager.closeSession("one") }
        runCurrent()
        firstDelete.complete(Unit); first.await()
        fixture.manager.createSession(id = "one", label = "replacement")
        staleDelete.complete(Unit); second.await()
        assertEquals(1, attempts)
        assertEquals("replacement", fixture.repository.contents.getValue("one").label)
        assertEquals("replacement", fixture.manager.handles.value.first { it.id == "one" }.label)
        fixture.repository.beforeDelete = {}
        fixture.manager.closeAllSessions()
    }
}
