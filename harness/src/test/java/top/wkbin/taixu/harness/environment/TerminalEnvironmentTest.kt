package top.wkbin.taixu.harness.environment

import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.core.database.TerminalSessionEntity
import top.wkbin.taixu.core.database.TerminalSessionRepository
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.environment.*
import top.wkbin.taixu.runtime.terminal.TerminalSessionClientRouter
import top.wkbin.taixu.runtime.terminal.TerminalSessionManager

class TerminalEnvironmentTest {
    @Test fun `unsupported remote terminal rolls back environment without starting local terminal`() = runBlocking {
        val world = FakeWorld(ExecutionEnvironmentId("remote", "linux", "/workspace/p", "terminal"))
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader,
            arrayOf(LinuxRuntime::class.java)) { _, method, _ -> error("local fallback: ${method.name}") } as LinuxRuntime
        var writes = 0
        val repository = object : TerminalSessionRepository {
            override fun observeAll() = MutableStateFlow(emptyList<TerminalSessionEntity>())
            override suspend fun listAll() = emptyList<TerminalSessionEntity>()
            override suspend fun nextOrder() = 0
            override suspend fun upsert(session: TerminalSessionEntity) { writes++ }
            override suspend fun delete(id: String) { writes++ }
            override suspend fun deleteAll() { writes++ }
        }
        val manager = TerminalSessionManager(runtime, repository, TerminalSessionClientRouter(),
            ExecutionEnvironmentFactory { _, _, _ -> world })
        try { manager.createSession(id = "terminal"); fail("remote world fell back to local terminal") }
        catch (t: IllegalStateException) { assertTrue(t.message!!.contains("当前执行环境")) }
        assertEquals(1, world.closes)
        assertEquals(0, writes)
        assertTrue(manager.handles.value.isEmpty())
    }
}
