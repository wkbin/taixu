package top.wkbin.taixu.harness

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.harness.core.ToolBackend
import top.wkbin.taixu.harness.workflow.WorkflowSignal
import top.wkbin.taixu.harness.workflow.WorkflowSignalBus
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.ProcessType
import top.wkbin.taixu.runtime.shell.ShellCommand
import top.wkbin.taixu.runtime.environment.BoundLinuxCommands
import top.wkbin.taixu.runtime.environment.ExecutionCommands
import top.wkbin.taixu.harness.environment.SessionExecutionEnvironments

data class LinuxCommandRequest(
    val tool: HarnessTool,       // BASE 或 PROCESS
    val args: JsonObject,
    val workspace: String,
    val sessionId: String = "",
)

/**
 * Agent 工具后端：PRoot 沙箱内前台命令（base）与后台进程生命周期管理（process）。
 *
 * 职责边界：
 * - 只负责运行时调用（LinuxRuntime.execute / startBackground / listBackground 等）。
 * - 不持有审批逻辑、脱敏、输出截断等横切关注点——这些由 ToolExecutor 管道层处理。
 */
class LinuxCommandToolBackend(
    private val linuxRuntime: LinuxRuntime,
    private val pathResolver: HarnessPathResolver,
    private val settingsDataStore: AgentPreferences? = null,
    private val workflowSignals: WorkflowSignalBus? = null,
    private val environments: SessionExecutionEnvironments? = null,
) : ToolBackend<LinuxCommandRequest, Pair<Boolean, String>> {

    override suspend fun execute(request: LinuxCommandRequest): Pair<Boolean, String> {
        val environment = environments?.environment(request.sessionId, request.workspace)
        val commands = environment ?: BoundLinuxCommands(linuxRuntime, null, AGENT_PROCESS_PREFIX)
        val directory: (String?) -> String = { explicit -> environment?.workingDirectory(explicit)
            ?: pathResolver.resolveWorkingDirectory(explicit, request.workspace) }
        return when (request.tool) {
            HarnessTool.BASE -> executeBase(request.args, request.workspace, commands, directory)
            HarnessTool.PROCESS -> executeProcess(request.args, commands, directory)
            else -> throw IllegalArgumentException("Unsupported tool: ${request.tool}")
        }
    }

    private suspend fun executeBase(args: JsonObject, workspace: String, commands: ExecutionCommands, directory: (String?) -> String): Pair<Boolean, String> {
        val command = JsonArgs.requireString(args, "command")
        require(command.length <= MAX_COMMAND_LENGTH) { "命令过长（${command.length} 字符，上限 $MAX_COMMAND_LENGTH）" }
        val cwd = directory(args["cwd"]?.jsonPrimitive?.content)
        val commandOutputCompressionEnabled = settingsDataStore?.let { prefs ->
            runCatching { prefs.commandOutputCompressionEnabled.first() }.getOrDefault(true)
        } ?: true
        val preparedCommand = RtkCommandOptimizer.prepare(command, commandOutputCompressionEnabled)
        val configuredTimeoutSeconds = settingsDataStore?.let { prefs ->
            runCatching { prefs.baseCommandTimeoutSeconds.first() }
                .getOrDefault(AgentPreferences.DEFAULT_BASE_COMMAND_TIMEOUT_SECONDS)
        } ?: AgentPreferences.DEFAULT_BASE_COMMAND_TIMEOUT_SECONDS
        val timeoutSeconds = JsonArgs.optionalLong(
            args = args,
            key = "timeout_seconds",
            default = configuredTimeoutSeconds.toLong(),
            min = MIN_BASE_TIMEOUT_SECONDS,
            max = MAX_BASE_TIMEOUT_SECONDS,
        )
        val result = commands.execute(
            ShellCommand(
                commandLine = preparedCommand.commandLine,
                workingDirectory = cwd,
                environment = preparedCommand.environment,
                timeoutMs = timeoutSeconds * 1000L,
            ),
        )
        val stdout = result.stdout.trim()
        val stderr = result.stderr.trim()
        val isSuccess = result.isSuccess

        // 🌟 AI 场景感知：构建失败或 APK 产物生成时主动通知工作流总线
        val projectName = workspace.trim('/').substringAfterLast('/').ifBlank { "workspace" }
        if (!isSuccess && isLikelyBuildCommand(command)) {
            val buildError = (stderr.ifBlank { stdout }).take(4000)
            workflowSignals?.emit(WorkflowSignal.BuildFailed(projectName, cwd, buildError))
        }
        val combinedOutput = stdout + "\n" + stderr
        APK_PATH_REGEX.find(combinedOutput)?.let { match ->
            val apkPath = match.value
            workflowSignals?.emit(WorkflowSignal.ApkGenerated(projectName, cwd, apkPath))
        }

        val body = buildString {
            append("exit ${result.exitCode} · ${result.durationMs} ms")
            if (stdout.isNotEmpty()) append("\n$stdout")
            if (stderr.isNotEmpty()) append("\n$stderr")
            if (!isSuccess && result.exitCode != 0) {
                append("\n\n【执行失败反思与纠错要求】")
                append("\n命令返回非零退出码 (${result.exitCode})。请仔细阅读上述错误信息：")
                append("\n1. 严禁原样重复执行失败命令；")
                append("\n2. 分析是参数拼写错误、路径不存在、沙箱缺少系统依赖还是语法错误；")
                append("\n3. 修正命令、使用包管理器安装依赖或改用其他工具后再继续。")
            }
        }
        return isSuccess to body
    }

    private suspend fun executeProcess(args: JsonObject, commands: ExecutionCommands, directory: (String?) -> String): Pair<Boolean, String> {
        val action = JsonArgs.requireString(args, "action").trim().lowercase()
        return when (action) {
            "start" -> {
                val externalId = requireProcessId(args)
                val command = JsonArgs.requireString(args, "command")
                require(command.length <= MAX_COMMAND_LENGTH) { "命令过长（${command.length} 字符，上限 $MAX_COMMAND_LENGTH）" }
                val cwd = directory(args["cwd"]?.jsonPrimitive?.content)
                val existing = commands.listBackground().firstOrNull { it.id == externalId && it.session.isAlive }
                require(existing == null) { "后台进程 $externalId 已在运行；请先查询状态或停止它" }
                val managed = commands.startBackground(
                    id = externalId,
                    command = ShellCommand(
                        commandLine = command,
                        workingDirectory = cwd,
                        timeoutMs = Long.MAX_VALUE,
                    ),
                )
                true to buildString {
                    append("后台进程已启动：").append(externalId)
                    managed.pid?.let { append("\npid: ").append(it) }
                    append("\n工作目录：").append(cwd)
                    append("\n请用 process(status/logs/stop) 管理；命令应以前台模式运行，不要再套 nohup 或 &。")
                }
            }
            "status" -> {
                val externalId = requireProcessId(args)
                val managed = commands.listBackground().firstOrNull { it.id == externalId }
                    ?: return false to "未找到后台进程：$externalId"
                true to buildString {
                    append("后台进程：").append(externalId)
                    append("\n状态：").append(if (managed.session.isAlive) "运行中" else "已退出")
                    managed.pid?.let { append("\npid: ").append(it) }
                    append("\n已运行：").append((System.currentTimeMillis() - managed.startedAt).coerceAtLeast(0L)).append(" ms")
                }
            }
            "logs" -> {
                val externalId = requireProcessId(args)
                val tailLines = JsonArgs.optionalLong(args, "tail_lines", DEFAULT_PROCESS_LOG_LINES, 1L, MAX_PROCESS_LOG_LINES).toInt()
                val logs = commands.getBackgroundLogs(externalId).takeLast(tailLines)
                true to if (logs.isEmpty()) "后台进程 $externalId 暂无日志" else logs.joinToString("\n")
            }
            "list" -> {
                val managed = commands.listBackground()
                true to if (managed.isEmpty()) {
                    "当前没有 Agent 管理的后台进程"
                } else {
                    managed.joinToString("\n") {
                        val externalId = it.id
                        "$externalId · ${if (it.session.isAlive) "运行中" else "已退出"} · ${(System.currentTimeMillis() - it.startedAt).coerceAtLeast(0L)} ms"
                    }
                }
            }
            "stop" -> {
                val externalId = requireProcessId(args)
                val stopped = commands.stopBackground(externalId)
                stopped to if (stopped) "后台进程已停止：$externalId" else "未找到后台进程：$externalId"
            }
            else -> false to "不支持的 process action：$action；可用 start/status/logs/list/stop"
        }
    }

    private fun requireProcessId(args: JsonObject): String {
        val id = JsonArgs.requireString(args, "id").trim().lowercase()
        require(PROCESS_ID.matches(id)) { "进程 id 仅允许小写字母、数字、点、下划线和连字符，长度 1-64" }
        return id
    }

    companion object {
        const val MIN_BASE_TIMEOUT_SECONDS = 1L
        // 前台单命令上限 15min
        const val MAX_BASE_TIMEOUT_SECONDS = 900L
        const val MAX_COMMAND_LENGTH = 32 * 1024
        const val DEFAULT_PROCESS_LOG_LINES = 120L
        const val MAX_PROCESS_LOG_LINES = 500L
        const val AGENT_PROCESS_PREFIX = "agent-process:"
        val PROCESS_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val APK_PATH_REGEX = Regex("""(?:\/[\w.\-]+)+\.apk""")

        private fun isLikelyBuildCommand(cmd: String): Boolean {
            val lower = cmd.lowercase()
            return lower.contains("gradle") || lower.contains("assemble") || lower.contains("taixu-build") ||
                lower.contains("cargo build") || lower.contains("make") || lower.contains("cmake")
        }
    }
}
