package top.wkbin.taixu.runtime.environment

import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.runtime.shell.*

/** One immutable execution world. Paths are guest paths, never host java.io.File paths. */
data class ExecutionEnvironmentId(val backend: String, val distribution: String, val workspace: String, val owner: String)

data class WorkspaceEditOutcome(val strategy: String, val replacements: Int, val diff: String?)

interface ExecutionFiles {
    suspend fun read(path: String, offset: Int? = null, limit: Int? = null): AppResult<String>
    suspend fun readRawBytes(path: String): AppResult<ByteArray>
    suspend fun write(path: String, content: String): AppResult<Unit>
    suspend fun editDetailed(path: String, oldText: String, newText: String): AppResult<WorkspaceEditOutcome>
    suspend fun previewOrNull(path: String): String?
    suspend fun fileSizeOrNull(path: String): Long?
    /** Delete a file within this world; absent files succeed, directories and escapes fail. */
    suspend fun delete(path: String): Boolean
}

interface ExecutionCommands {
    suspend fun execute(command: ShellCommand): CommandResult
    suspend fun startBackground(id: String, command: ShellCommand): ManagedProcess
    suspend fun stopBackground(id: String): Boolean
    fun listBackground(): List<ManagedProcess>
    fun getBackgroundLogs(id: String): List<String>
}

interface ExecutionEnvironment : ExecutionCommands {
    val id: ExecutionEnvironmentId
    val files: ExecutionFiles
    val artifacts: ExecutionArtifacts? get() = null
    val downloads: ExecutionDownloads? get() = null
    val buildBindings: ExecutionBuildBindings? get() = null
    /** Known environment values used only for output redaction, never exposed to model results. */
    suspend fun outputSecrets(): Collection<String> = emptyList()
    /** Compatibility gate for services still bound to the shared local runtime (build/subagent). */
    val supportsSharedLocalServices: Boolean get() = false
    fun workingDirectory(explicit: String? = null): String
    /** Protocol subprocess: cancellation/close belongs to the returned session. */
    suspend fun openSession(config: SessionConfig): LinuxSession
    /** Interactive PTY: remote implementations must implement resize, input, interrupt and close. */
    suspend fun openTerminal(config: SessionConfig): LinuxSession
    /** Stop owned subprocesses/PTYs; never shut down a shared runtime. Idempotent. */
    suspend fun close()
}

fun interface ExecutionEnvironmentFactory {
    /** Failed/cancelled setup must roll back allocations before throwing; success transfers ownership. */
    suspend fun open(owner: String, workspace: String, distribution: String?): ExecutionEnvironment
}

/** Optional Android Termux renderer adapter. Remote worlds must not fabricate host argv. */
interface LocalTerminalLaunch {
    suspend fun terminalLaunch(config: SessionConfig): InteractiveLaunchSpec
}
