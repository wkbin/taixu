package top.wkbin.taixu.harness.environment

import kotlinx.coroutines.flow.emptyFlow
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.runtime.environment.*
import top.wkbin.taixu.runtime.shell.*

internal class FakeWorld(override val id: ExecutionEnvironmentId) : ExecutionEnvironment {
    val contents = mutableMapOf<String, String>()
    val failedDeletes = mutableSetOf<String>()
    val secretValues = mutableListOf<String>()
    val events = mutableListOf<String>()
    var closes = 0
    val processes = mutableMapOf<String, ManagedProcess>()
    override var downloads: ExecutionDownloads? = null
    override var buildBindings: ExecutionBuildBindings? = null
    override suspend fun outputSecrets() = secretValues.toList()
    override val files = object : ExecutionFiles {
        override suspend fun delete(path: String): Boolean {
            if (path in failedDeletes) return false
            contents.remove(path); events += "delete:$path"; return true
        }
        override suspend fun read(path: String, offset: Int?, limit: Int?) = AppResult.Success(contents[path].orEmpty())
        override suspend fun readRawBytes(path: String) = AppResult.Success(contents[path].orEmpty().toByteArray())
        override suspend fun write(path: String, content: String): AppResult<Unit> {
            contents[path] = content; events += "write:$path"; return AppResult.Success(Unit)
        }
        override suspend fun editDetailed(path: String, oldText: String, newText: String): AppResult<WorkspaceEditOutcome> {
            contents[path] = contents[path].orEmpty().replace(oldText, newText)
            return AppResult.Success(WorkspaceEditOutcome("remote", 1, null))
        }
        override suspend fun previewOrNull(path: String) = contents[path]
        override suspend fun fileSizeOrNull(path: String) = contents[path]?.length?.toLong()
    }
    override val artifacts = object : ExecutionArtifacts {
        override val storageKey = "${id.backend}:${id.distribution}:${id.workspace}"
        override suspend fun list() = AppResult.Success(contents.filterKeys {
            it.startsWith("${ExecutionArtifacts.DIRECTORY}/")
        }.map { (path, content) -> ExecutionArtifact(path.substringAfterLast('/'), content.toByteArray().size.toLong()) })
        override suspend fun write(name: String, content: String) = files.write("${ExecutionArtifacts.DIRECTORY}/$name", content)
        override suspend fun delete(name: String) = files.delete("${ExecutionArtifacts.DIRECTORY}/$name")
    }
    override fun workingDirectory(explicit: String?) = explicit ?: id.workspace
    override suspend fun execute(command: ShellCommand): CommandResult {
        events += "command:${command.workingDirectory}"
        return CommandResult(0, contents[command.commandLine.removePrefix("cat ")].orEmpty(), "", 0)
    }
    override suspend fun startBackground(id: String, command: ShellCommand): ManagedProcess =
        ManagedProcess(id, 1, FakeSession(events)).also { processes[id] = it; events += "start:$id" }
    override suspend fun stopBackground(id: String) = processes.remove(id)?.let { it.session.close(); true } ?: false
    override fun listBackground() = processes.values.toList()
    override fun getBackgroundLogs(id: String) = listOf("remote log:$id")
    override suspend fun openSession(config: SessionConfig): LinuxSession {
        events += "stdio:${config.workingDirectory}"; return FakeSession(events)
    }
    override suspend fun openTerminal(config: SessionConfig): LinuxSession {
        events += "pty:${config.workingDirectory}"; return FakeSession(events)
    }
    override suspend fun close() { closes++; processes.keys.toList().forEach { stopBackground(it) }; events += "environment-close" }
}

internal class FakeSession(private val events: MutableList<String>) : LinuxSession {
    override var isAlive = true
    override val output = emptyFlow<TerminalOutput>()
    override suspend fun write(data: ByteArray) { events += "input:${data.decodeToString()}" }
    override suspend fun resize(columns: Int, rows: Int) { events += "resize:$columns:$rows" }
    override suspend fun interrupt() { events += "interrupt" }
    override suspend fun close() { if (isAlive) events += "session-close"; isAlive = false }
}
