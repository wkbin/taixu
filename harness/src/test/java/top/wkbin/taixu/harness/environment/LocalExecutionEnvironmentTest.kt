package top.wkbin.taixu.harness.environment

import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.wkbin.taixu.harness.HarnessPathResolver
import top.wkbin.taixu.harness.WorkspaceFileAccess
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.environment.LocalTerminalLaunch
import top.wkbin.taixu.runtime.shell.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocalExecutionEnvironmentTest {
    @Test fun `explicit subprocess close revokes ownership and later environment close does not close it again`() = runTest {
        val resources = top.wkbin.taixu.harness.core.SessionResourceScope()
        var closes = 0
        val session = object : LinuxSession by FakeSession(events) {
            override suspend fun close() { closes++ }
        }
        val owned = OwnedLinuxSession.open(resources) { session }
        owned.close()
        resources.close()
        owned.close()
        assertEquals(1, closes)
    }
    @get:Rule val temporary = TemporaryFolder()
    private val distro = MutableStateFlow("ubuntu")
    private val events = mutableListOf<String>()
    private val processes = mutableMapOf<String, ManagedProcess>()
    private val targets = mutableListOf<String?>()
    private fun runtime(root: File): LinuxRuntime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader,
        arrayOf(LinuxRuntime::class.java)) { _, method, args -> when (method.name) {
            "getActiveDistroId" -> distro
            "workspacePath" -> root
            "execute" -> { targets += args[1] as String?; CommandResult(0, "ok", "", 0) }
            "startSession" -> { targets += args[1] as String?; FakeSession(events) }
            "startBackground" -> {
                targets += args[4] as String?
                val id = args[0] as String
                ManagedProcess(id, 1, FakeSession(events)).also { processes[id] = it }
            }
            "stopBackground" -> processes.remove(args[0])?.let { events += "stop:${args[0]}"; true } ?: false
            "listBackground" -> processes.values.toList()
            "getBackgroundLogs" -> listOf("log:${args[0]}")
            "buildInteractiveLaunch" -> {
                targets += args[1] as String?
                InteractiveLaunchSpec("proot", arrayOf("proot"), "/", emptyArray())
            }
            else -> error("unexpected runtime method: ${method.name}")
        } } as LinuxRuntime

    @Test fun `commands subprocesses background and terminal pin distro despite global switch`() = runTest {
        val root = temporary.newFolder()
        val factory = LocalExecutionEnvironmentFactory(runtime(root), WorkspaceFileAccess(root), HarnessPathResolver())
        val world = factory.open("session", "/workspace/project", null)
        distro.value = "debian"
        world.execute(ShellCommand("pwd", world.workingDirectory()))
        world.startBackground("server", ShellCommand("serve", world.workingDirectory()))
        val stdio = world.openSession(SessionConfig())
        val terminal = world.openTerminal(SessionConfig())
        (world as LocalTerminalLaunch).terminalLaunch(SessionConfig())
        assertEquals(List(5) { "ubuntu" }, targets)
        assertFalse(world.supportsSharedLocalServices)
        assertEquals("/workspace/project", world.workingDirectory())
        world.close()
        assertTrue(processes.isEmpty())
        assertFalse(stdio.isAlive)
        assertFalse(terminal.isAlive)
        world.close()
        assertEquals(1, events.count { it.startsWith("stop:") })
    }

    @Test fun `same background name cannot collide across local environment owners`() = runTest {
        val root = temporary.newFolder()
        val factory = LocalExecutionEnvironmentFactory(runtime(root), WorkspaceFileAccess(root), HarnessPathResolver())
        val one = factory.open("one", "", null)
        val two = factory.open("two", "", null)
        one.startBackground("server", ShellCommand("serve"))
        two.startBackground("server", ShellCommand("serve"))
        assertEquals(2, processes.size)
        assertEquals("server", one.listBackground().single().id)
        one.close()
        assertEquals(1, processes.size)
        assertEquals("server", two.listBackground().single().id)
        two.close()
    }

    @Test fun `files and commands cannot bind different workspace roots`() = runTest {
        val factory = LocalExecutionEnvironmentFactory(runtime(temporary.newFolder()),
            WorkspaceFileAccess(temporary.newFolder()), HarnessPathResolver())
        try { factory.open("one", "project", null); fail("mixed world accepted") }
        catch (_: IllegalArgumentException) { }
        assertTrue(targets.isEmpty())
    }

    @Test fun `workspace escape cannot silently bind global workspace`() = runTest {
        val root = temporary.newFolder()
        val factory = LocalExecutionEnvironmentFactory(runtime(root), WorkspaceFileAccess(root), HarnessPathResolver())
        for (path in listOf("../outside", "/root", "/workspace/../other")) {
            try { factory.open("one", path, null); fail("invalid workspace accepted: $path") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `subprocess acquired while close starts is rolled back and close waits for startup`() = runTest {
        val root = temporary.newFolder()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val session = FakeSession(events)
        val runtime = object : LinuxRuntime by runtime(root) {
            override suspend fun startSession(config: SessionConfig, distroId: String?): LinuxSession = withContext(NonCancellable) {
                started.complete(Unit); release.await(); session
            }
        }
        val world = LocalExecutionEnvironmentFactory(runtime, WorkspaceFileAccess(root), HarnessPathResolver()).open("one", "", null)
        val open = launch { try { world.openSession(SessionConfig()) } catch (_: IllegalStateException) { } }
        started.await()
        val close = async { world.close() }
        runCurrent()
        assertFalse(close.isCompleted)
        release.complete(Unit)
        close.await(); open.join()
        assertFalse(session.isAlive)
    }
}
