package top.wkbin.taixu.harness.environment

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
import top.wkbin.taixu.runtime.shell.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocalAcquisitionRetryTest {
    @get:Rule val temporary = TemporaryFolder()

    private suspend fun verify(background: Boolean) = coroutineScope {
        val root = temporary.newFolder()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var closes = 0
        val session = object : LinuxSession by FakeSession(mutableListOf()) {
            override suspend fun close() { if (++closes <= 2) error("close $closes") }
        }
        val base = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) {
                _, method, _ -> when (method.name) {
            "getActiveDistroId" -> MutableStateFlow("ubuntu")
            "workspacePath" -> root
            "listBackground" -> emptyList<ManagedProcess>()
            else -> error("unexpected ${method.name}")
        } } as LinuxRuntime
        val runtime = object : LinuxRuntime by base {
            override suspend fun startSession(config: SessionConfig, distroId: String?): LinuxSession =
                withContext(NonCancellable) { started.complete(Unit); gate.await(); session }
            override suspend fun startBackground(id: String, command: ShellCommand, toolId: String?, type: ProcessType,
                distroId: String?): ManagedProcess = withContext(NonCancellable) {
                started.complete(Unit); gate.await(); ManagedProcess(id, 1, session)
            }
            override suspend fun stopBackground(id: String): Boolean { session.close(); return true }
        }
        val world = LocalExecutionEnvironmentFactory(runtime, WorkspaceFileAccess(root), HarnessPathResolver())
            .open("one", "", null)
        val opening = async {
            if (background) world.startBackground("server", ShellCommand("serve"))
            else world.openSession(SessionConfig())
        }
        started.await()
        val closing = async { runCatching { world.close() }.exceptionOrNull() }
        yield(); gate.complete(Unit)
        assertNotNull(closing.await())
        opening.join(); world.close(); world.close()
        assertEquals(3, closes)
    }

    @Test fun `subprocess failed rollback remains owned after startup drains`() = runTest { verify(false) }
    @Test fun `background failed rollback remains owned after startup drains`() = runTest { verify(true) }
}
