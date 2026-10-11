package top.wkbin.taixu.harness.environment

import com.termux.terminal.TerminalSession
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import top.wkbin.taixu.core.database.TerminalSessionEntity
import top.wkbin.taixu.core.database.TerminalSessionRepository
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.environment.*
import top.wkbin.taixu.runtime.shell.*
import top.wkbin.taixu.runtime.terminal.*

internal class TerminalLifecycleFixture {
    val repository = Rows()
    val worlds = mutableMapOf<String, FakeWorld>()
    val closeAttempts = mutableMapOf<String, Int>()
    val failCloses = mutableMapOf<String, Int>()
    val processes = mutableListOf<Process>()
    var beforeOpen: suspend (String) -> Unit = {}
    var beforeClose: suspend (String) -> Unit = {}
    var construct: (Process) -> Unit = {}
    val manager = TerminalSessionManager(
        Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) {
                _, method, _ -> error("local fallback: ${method.name}")
        } as LinuxRuntime,
        repository, TerminalSessionClientRouter(),
        ExecutionEnvironmentFactory { owner, _, _ ->
            val id = owner.removePrefix("terminal:")
            beforeOpen(id)
            val world = FakeWorld(ExecutionEnvironmentId("local-proot", "ubuntu", "/workspace", owner))
                .also { worlds[id] = it }
            object : ExecutionEnvironment by world, LocalTerminalLaunch {
                override suspend fun terminalLaunch(config: SessionConfig) =
                    InteractiveLaunchSpec("proot", arrayOf("proot"), config.workingDirectory, emptyArray())
                override suspend fun close() {
                    closeAttempts[id] = closeAttempts.getOrDefault(id, 0) + 1
                    beforeClose(id)
                    if (failCloses.getOrDefault(id, 0) > 0) {
                        failCloses[id] = failCloses.getValue(id) - 1
                        error("environment close $id")
                    }
                    world.close()
                }
            }
        },
        TerminalProcessFactory { _, _ -> Process().also { construct(it); processes += it } },
    )

    class Process : TerminalProcess {
        override val session: TerminalSession get() = error("native renderer must not run in lifecycle tests")
        override var isAlive = true
        var finishes = 0
        var finishFailures = 0
        val inputs = mutableListOf<String>()
        override fun finish() {
            finishes++
            if (finishFailures-- > 0) error("process finish")
            isAlive = false
        }
        override fun write(data: ByteArray) { inputs += data.decodeToString() }
    }

    class Rows : TerminalSessionRepository {
        val contents = mutableMapOf<String, TerminalSessionEntity>()
        var afterUpsert: suspend (TerminalSessionEntity) -> Unit = {}
        var beforeDelete: suspend (String) -> Unit = {}
        var deletes = 0
        override fun observeAll() = MutableStateFlow(contents.values.toList())
        override suspend fun listAll() = contents.values.toList()
        override suspend fun nextOrder() = contents.size + 1
        override suspend fun upsert(session: TerminalSessionEntity) { contents[session.id] = session; afterUpsert(session) }
        override suspend fun delete(id: String) { beforeDelete(id); deletes++; contents.remove(id) }
        override suspend fun deleteAll() { contents.clear() }
    }
}
