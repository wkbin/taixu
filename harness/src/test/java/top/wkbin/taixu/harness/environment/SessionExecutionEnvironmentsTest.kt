package top.wkbin.taixu.harness.environment

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.harness.*
import top.wkbin.taixu.harness.core.*
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.environment.*
import top.wkbin.taixu.runtime.shell.*
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class SessionExecutionEnvironmentsTest {
    private val worlds = mutableListOf<FakeWorld>()
    private fun registry() = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
        FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner)).also { worlds += it }
    })
    private fun args(json: String) = Json.parseToJsonElement(json) as JsonObject
    private fun unusedRuntime() = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader,
        arrayOf(LinuxRuntime::class.java)) { _, method, _ -> error("Local runtime called: ${method.name}") } as LinuxRuntime

    @Test fun `file and command tools use the same remote world without touching local fallback`() = runTest {
        val registry = registry()
        val files = WorkspaceToolBackend({ error("local files") }, environments = registry)
        val commands = LinuxCommandToolBackend(unusedRuntime(), HarnessPathResolver(), environments = registry)
        val written = files.execute(WorkspaceToolRequest(HarnessTool.WRITE,
            args("""{"path":"a.txt","content":"remote content"}"""), "session", "/workspace/project"))
        val command = commands.execute(LinuxCommandRequest(HarnessTool.BASE,
            args("""{"command":"cat a.txt"}"""), "/workspace/project", "session"))
        assertTrue(written.success)
        assertTrue(command.first)
        assertTrue(command.second.contains("remote content"))
        assertEquals(1, worlds.size)
        assertEquals(listOf("write:a.txt", "command:/workspace/project"), worlds.single().events)
    }

    @Test fun `identical process names are isolated by session and cleanup leaves other session alive`() = runTest {
        val registry = registry()
        val commands = LinuxCommandToolBackend(unusedRuntime(), HarnessPathResolver(), environments = registry)
        suspend fun start(session: String) = registry.activity(session, "/workspace/p") {
            commands.execute(LinuxCommandRequest(HarnessTool.PROCESS,
                args("""{"action":"start","id":"server","command":"serve"}"""), "/workspace/p", session))
        }
        start("one"); start("two")
        assertEquals(2, worlds.size)
        registry.closeSession("one")
        assertTrue(worlds[0].processes.isEmpty())
        assertTrue(worlds[1].processes["server"]!!.session.isAlive)
        registry.closeSession("one")
        assertEquals(1, worlds[0].closes)
    }

    @Test fun `disposed session cannot recreate its environment and binding cannot silently change`() = runTest {
        val registry = registry()
        registry.environment("one", "/workspace/p")
        try { registry.environment("one", "/workspace/q"); fail("binding changed") } catch (_: IllegalStateException) { }
        registry.closeSession("one")
        try { registry.environment("one", "/workspace/p"); fail("disposed session reopened") } catch (_: IllegalStateException) { }
        assertEquals(1, worlds.size)
    }

    @Test fun `child resources close before lane finish without stopping parent processes`() = runTest {
        val registry = registry()
        val parent = registry.environment("parent", "/workspace/p") as FakeWorld
        parent.startBackground("server", ShellCommand("serve"))
        lateinit var child: FakeWorld
        registry.activity("parent", "/workspace/p", "lane-operation") {
            child = registry.environment("parent", "/workspace/p") as FakeWorld
            child.startBackground("server", ShellCommand("serve"))
        }
        assertEquals(parent.id.distribution, child.id.distribution)
        registry.closeOwner("parent", "lane-operation")
        assertEquals(1, child.closes)
        assertTrue(parent.processes["server"]!!.session.isAlive)
        registry.closeSession("parent")
        assertEquals(1, child.closes)
        assertEquals(1, parent.closes)
    }

    @Test fun `parent deletion cascades to live child resources`() = runTest {
        val registry = registry()
        lateinit var child: FakeWorld
        registry.activity("parent", "/workspace/p", "lane-operation") {
            child = registry.environment("parent", "/workspace/p") as FakeWorld
            child.startBackground("server", ShellCommand("serve"))
        }
        registry.closeSession("parent")
        assertEquals(1, child.closes)
        assertTrue(child.processes.isEmpty())
    }

    @Test fun `session close drains tool execution before unregistering listeners and closing environment`() = runTest {
        val registry = registry()
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        val drain = CompletableDeferred<Unit>()
        registry.own("one", "/workspace/p") { world.events += "listener-close" }
        val tool = launch { registry.activity("one", "/workspace/p") {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { drain.await(); world.events += "tool-drained" } }
        } }
        runCurrent()
        val close = async { registry.closeSession("one") }
        runCurrent()
        assertEquals(0, world.closes)
        drain.complete(Unit)
        close.await(); tool.join()
        assertEquals(listOf("tool-drained", "listener-close", "environment-close"), world.events)
    }

    @Test fun `extension creation failure rolls back already registered resources`() = runTest {
        val registry = registry()
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        try { registry.install("one", "/workspace/p") {
            it.own { world.events += "unregister" }; error("setup failed")
        }; fail("setup succeeded") } catch (_: IllegalStateException) { }
        assertEquals(listOf("unregister"), world.events)
        registry.closeSession("one")
        assertEquals(1, world.events.count { it == "unregister" })
    }

    @Test fun `session checkpoint registration only applies to owner and unloads on deletion`() = runTest {
        val registry = registry()
        val checkpoints = ToolCheckpoints<ToolExecutionRequest, ToolResult>()
        val call = ToolCall("call", 1, HarnessTool.READ, args("""{"path":"a"}"""))
        val hook = object : ToolCheckpoint<ToolExecutionRequest, ToolResult> {
            override val id = "owner-guard"
            override suspend fun before(request: ToolExecutionRequest) = ToolGateDecision.Block("blocked")
        }
        registry.installCheckpoint("one", "/workspace/p", checkpoints, hook)
        assertNotNull(checkpoints.before(ToolExecutionRequest(call, "one", "/workspace/p", null)))
        assertNull(checkpoints.before(ToolExecutionRequest(call, "two", "/workspace/p", null)))
        registry.installCheckpoint("two", "/workspace/p", checkpoints, hook)
        registry.closeSession("one")
        assertNull(checkpoints.before(ToolExecutionRequest(call, "one", "/workspace/p", null)))
        assertNotNull(checkpoints.before(ToolExecutionRequest(call, "two", "/workspace/p", null)))
        registry.closeSession("two")
    }

    @Test fun `remote subprocess and PTY provide their own input resize interrupt lifecycle`() = runTest {
        val registry = registry()
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        val stdio = world.openSession(SessionConfig(workingDirectory = world.workingDirectory(), commandLine = "mcp"))
        val pty = world.openTerminal(SessionConfig(workingDirectory = world.workingDirectory()))
        pty.write("x".toByteArray()); pty.resize(100, 30); pty.interrupt(); pty.close(); stdio.close()
        assertEquals(listOf("stdio:/workspace/p", "pty:/workspace/p", "input:x", "resize:100:30",
            "interrupt", "session-close", "session-close"), world.events)
        assertFalse(world.supportsSharedLocalServices)
        assertFalse((world as ExecutionEnvironment) is LocalTerminalLaunch)
    }
}
