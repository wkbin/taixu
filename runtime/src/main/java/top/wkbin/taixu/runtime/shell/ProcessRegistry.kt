package top.wkbin.taixu.runtime.shell

import top.wkbin.taixu.core.model.StorageMountBinding
import top.wkbin.taixu.runtime.RuntimePathManager
import top.wkbin.taixu.runtime.proot.ProotCommandBuilder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ProcessType {
    TERMINAL,
    SERVICE,
    COMMAND,
    UNKNOWN,
}

data class ManagedProcess(
    val id: String,
    val startedAt: Long,
    val session: LinuxSession,
    val toolId: String? = null,
    val pid: Long? = session.pid,
    val type: ProcessType = ProcessType.UNKNOWN,
)

interface ProcessRegistry {
    suspend fun start(
        id: String,
        command: ShellCommand,
        toolId: String? = null,
        type: ProcessType = ProcessType.SERVICE,
        distroId: String? = null,
        mounts: List<StorageMountBinding> = emptyList(),
    ): ManagedProcess
    suspend fun stop(id: String): Boolean
    suspend fun stopAll()
    suspend fun cleanupDeadProcesses(): Int
    fun list(): List<ManagedProcess>
    fun observeLogs(idOrToolId: String): Flow<List<String>>
    fun getLogs(idOrToolId: String): List<String>
    fun clearLogs(idOrToolId: String)
}

class ProcessRegistryImpl(
    private val pathManager: RuntimePathManager,
    private val prootCommandBuilder: ProotCommandBuilder,
) : ProcessRegistry {
    private val mutex = Mutex()
    private val processes = ConcurrentHashMap<String, ManagedProcess>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logs = ProcessLogStore()

    init {
        scope.launch {
            while (true) {
                delay(60_000)
                logs.prune()
            }
        }
    }

    override suspend fun start(
        id: String,
        command: ShellCommand,
        toolId: String?,
        type: ProcessType,
        distroId: String?,
        mounts: List<StorageMountBinding>,
    ): ManagedProcess = mutex.withLock {
        processes.remove(id)?.session?.close()
        val safeDistro = distroId?.lowercase()?.trim()?.takeIf { it.isNotBlank() }
            ?: pathManager.listInstalledDistroIds().firstOrNull() ?: "ubuntu"
        val session = ProcessLinuxSession(
            prootCommandBuilder.build(
                prootBinary = pathManager.activeProotFile(),
                rootfsDir = pathManager.rootfsDir(safeDistro),
                workspaceDir = pathManager.workspaceDir,
                homeDir = pathManager.homeDir(safeDistro),
                optDir = pathManager.taixuRootDir(safeDistro),
                tmpDir = pathManager.tmpDir,
                attachmentsDir = pathManager.attachmentsDir,
                command = command,
                mounts = mounts,
            ),
            hostEnvironment = pathManager.hostProcessEnvironment(safeDistro),
        )

        val logKey = toolId ?: id
        val logKeys = setOf(logKey, id)
        logKeys.forEach { key ->
            logs.retain(key)
            logs.append(key, "[TaiXu] 正在启动服务进程...")
        }

        scope.launch {
            try {
                session.output.collect { terminalOutput ->
                    terminalOutput.text.lineSequence()
                        .filter { it.isNotBlank() }
                        .forEach { line ->
                            logKeys.forEach { logs.append(it, line) }
                        }
                }
            } catch (_: Exception) {
            } finally {
                val exitNotice = "[TaiXu] 服务进程已停止"
                logKeys.forEach { key ->
                    logs.append(key, exitNotice)
                    logs.release(key)
                }
            }
        }

        ManagedProcess(
            id = id,
            startedAt = System.currentTimeMillis(),
            session = session,
            toolId = toolId,
            pid = session.pid,
            type = type,
        ).also { processes[id] = it }
    }

    override suspend fun stop(id: String): Boolean = mutex.withLock {
        val process = processes[id] ?: return@withLock false
        process.session.close()
        processes.remove(id, process)
        true
    }

    override suspend fun stopAll() = mutex.withLock {
        processes.values.forEach { it.session.close() }
        processes.clear()
    }

    override suspend fun cleanupDeadProcesses(): Int = mutex.withLock {
        val dead = processes.values.filter { !it.session.isAlive }
        dead.forEach { processes.remove(it.id) }
        dead.size
    }

    override fun list(): List<ManagedProcess> = processes.values.toList().sortedBy { it.startedAt }

    override fun observeLogs(idOrToolId: String): Flow<List<String>> =
        logs.observe(idOrToolId)

    override fun getLogs(idOrToolId: String): List<String> =
        logs.get(idOrToolId)

    override fun clearLogs(idOrToolId: String) {
        logs.clear(idOrToolId)
    }
}
