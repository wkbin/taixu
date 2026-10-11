package top.wkbin.taixu.runtime.environment

import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.*

/** Adapter pins distro and namespaces every process to its environment owner. */
class BoundLinuxCommands(
    private val runtime: LinuxRuntime,
    private val distribution: String?,
    private val prefix: String,
) : ExecutionCommands {
    override suspend fun execute(command: ShellCommand) = runtime.execute(command, distribution)
    override suspend fun startBackground(id: String, command: ShellCommand): ManagedProcess =
        runtime.startBackground(prefix + id, command, type = ProcessType.COMMAND, distroId = distribution).copy(id = id)
    override suspend fun stopBackground(id: String) = runtime.stopBackground(prefix + id)
    override fun listBackground() = runtime.listBackground().filter { it.id.startsWith(prefix) }
        .map { it.copy(id = it.id.removePrefix(prefix)) }
    override fun getBackgroundLogs(id: String) = runtime.getBackgroundLogs(prefix + id)
}
