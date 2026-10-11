package top.wkbin.taixu.harness.environment

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.runtime.environment.*

@OptIn(ExperimentalCoroutinesApi::class)
class EnvironmentCleanupRecoveryTest {
    @Test fun `anonymous owners close across workspaces and cannot reopen after disposal`() = runTest {
        val worlds = mutableListOf<FakeWorld>()
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
            FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner)).also { worlds += it }
        })
        for (workspace in listOf("/workspace/a", "/workspace/b")) {
            registry.activity("", workspace, "lane") { }
        }
        val named = registry.environment("named", "/workspace/a") as FakeWorld
        registry.closeOwner("", "lane")
        assertEquals(listOf(0, 1, 0, 1, 0), worlds.map { it.closes })
        try { registry.activity("", "/workspace/a", "lane") { }; fail("owner reopened") }
        catch (_: IllegalStateException) { }
        registry.closeSession("")
        assertEquals(listOf(1, 1, 1, 1, 0), worlds.map { it.closes })
        try { registry.environment("", "/workspace/c"); fail("anonymous session reopened") }
        catch (_: IllegalStateException) { }
        assertSame(named, registry.environment("named", "/workspace/a"))
        registry.closeSession("named")
    }

    @Test fun `failed extension rollback remains owned and retries only remaining cleanup`() = runTest {
        val world = FakeWorld(ExecutionEnvironmentId("remote", "linux", "/workspace/p", "one"))
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { _, _, _ -> world })
        var attempts = 0
        var listeners = 0
        val original = IllegalArgumentException("extension setup")
        try {
            registry.install("one", "/workspace/p") {
                it.own { attempts++; if (attempts == 1) error("release failed") }
                it.own { listeners++ }
                throw original
            }
            fail("setup succeeded")
        } catch (t: IllegalArgumentException) {
            // Coroutine stack recovery can wrap a copy; the original retains rollback diagnostics.
            assertTrue(generateSequence(t as Throwable) { it.cause }.any { it === original })
            assertEquals("release failed", original.suppressed.single().message)
        }
        assertEquals(1, attempts)
        assertEquals(1, listeners)
        registry.closeSession("one")
        registry.closeSession("one")
        assertEquals(2, attempts)
        assertEquals(1, listeners)
        assertEquals(1, world.closes)
    }

    @Test fun `cancelled extension setup retains failed rollback for later session disposal`() = runTest {
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
            FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner))
        })
        var attempts = 0
        val setup = launch {
            registry.install("one", "/workspace/p") {
                it.own { attempts++; if (attempts == 1) error("close") }
                awaitCancellation()
            }
        }
        runCurrent()
        setup.cancelAndJoin()
        assertEquals(1, attempts)
        registry.closeSession("one")
        assertEquals(2, attempts)
    }

    @Test fun `rejected child environment retains failed rollback until owner disposal`() = runTest {
        val parent = FakeWorld(ExecutionEnvironmentId("remote", "linux", "/workspace/p", "one"))
        val invalid = FakeWorld(parent.id.copy(backend = "other", owner = "one::lane"))
        var attempts = 0
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, _, _ ->
            if (owner == "one") parent else object : ExecutionEnvironment by invalid {
                override suspend fun close() { attempts++; if (attempts == 1) error("rollback"); invalid.close() }
            }
        })
        try { registry.activity("one", "/workspace/p", "lane") { fail("invalid child used") }; fail("setup accepted") }
        catch (t: IllegalArgumentException) {
            val original = generateSequence(t as Throwable) { it.cause }.last()
            assertEquals("rollback", original.suppressed.single().message)
        }
        assertEquals(1, attempts)
        registry.closeOwner("one", "lane")
        registry.closeSession("one")
        assertEquals(2, attempts)
        assertEquals(1, invalid.closes)
        assertEquals(1, parent.closes)
    }

    @Test fun `retry creation cleans rejected environment before opening its replacement`() = runTest {
        var opens = 0
        var attempts = 0
        val parent = FakeWorld(ExecutionEnvironmentId("remote", "linux", "/workspace/p", "one"))
        val replacement = FakeWorld(parent.id.copy(owner = "one::lane"))
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, _, _ ->
            if (owner == "one") parent else {
                opens++
                if (opens == 1) object : ExecutionEnvironment by replacement {
                    override val id = replacement.id.copy(backend = "invalid")
                    override suspend fun close() { attempts++; if (attempts == 1) error("rollback") }
                } else { assertEquals(2, attempts); replacement }
            }
        })
        try { registry.activity("one", "/workspace/p", "lane") { }; fail("setup accepted") }
        catch (_: IllegalArgumentException) { }
        registry.activity("one", "/workspace/p", "lane") {
            assertSame(replacement, registry.environment("one", "/workspace/p"))
        }
        registry.closeSession("one")
        assertEquals(1, replacement.closes)
        assertEquals(1, parent.closes)
    }

    @Test fun `anonymous session disposal closes other roots after one failure and retries failed root`() = runTest {
        val worlds = mutableListOf<FakeWorld>()
        var attempts = 0
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
            val world = FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner)).also { worlds += it }
            object : ExecutionEnvironment by world {
                override suspend fun close() {
                    if (workspace.endsWith("/a") && ++attempts == 1) error("close a")
                    world.close()
                }
            }
        })
        registry.environment("", "/workspace/a"); registry.environment("", "/workspace/b")
        try { registry.closeSession(""); fail("failure swallowed") } catch (_: IllegalStateException) { }
        assertEquals(listOf(0, 1), worlds.map { it.closes })
        registry.closeSession("")
        assertEquals(listOf(1, 1), worlds.map { it.closes })
    }
}
