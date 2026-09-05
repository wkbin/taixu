package top.wkbin.taixu.harness

import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.harness.session.SessionTreeStore
import top.wkbin.taixu.core.security.SecretRedactor
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.datastore.SettingsDataStore
import top.wkbin.taixu.core.model.ApprovalMode
import top.wkbin.taixu.core.network.DownloadEvent
import top.wkbin.taixu.core.network.DownloadRequest
import top.wkbin.taixu.core.network.FileDownloader
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.LinuxEnvironmentManager
import top.wkbin.taixu.runtime.shell.ShellCommand
import top.wkbin.taixu.runtime.shell.ProcessType
import top.wkbin.taixu.runtime.privilege.BinderOutcome
import top.wkbin.taixu.runtime.privilege.PrivilegeManager
import top.wkbin.taixu.runtime.privilege.ShizukuSystemApis
import top.wkbin.taixu.runtime.apps.AndroidAppManager
import top.wkbin.taixu.runtime.bridge.adb.EmbeddedAdbManager
import top.wkbin.taixu.core.database.AndroidAppRepository
import top.wkbin.taixu.core.model.ExecutionMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Harness 工具执行器：把 LLM 发出的 ToolCall 翻译成受控操作。
 *
 * - read / write / edit → [WorkspaceFileAccess]（工作区路径安全层）
 * - base → [LinuxRuntime.execute]（PRoot 沙箱内执行命令，带超时与输出截断）
 *
 * 任何工具失败都不会抛异常，而是以结构化的 [ToolResult] 返回给 HarnessLoop，
 * 由模型决定下一步（自我纠正）。
 */
@Singleton
class ToolExecutor @Inject constructor(
    private val fileAccess: WorkspaceFileAccess,
    private val linuxRuntime: LinuxRuntime,
    private val pathResolver: HarnessPathResolver,
    private val approvalPolicyEngine: ApprovalPolicyEngine,
    private val secretRedactor: SecretRedactor,
    private val fileDownloader: FileDownloader,
    private val linuxEnvironmentManager: LinuxEnvironmentManager? = null,
    private val approvalRepository: top.wkbin.taixu.core.database.AgentApprovalRepository? = null,
    private val sessionDao: HarnessSessionRepository? = null,
    private val subagentOrchestrator: SubagentOrchestrator? = null,
    private val mcpManager: top.wkbin.taixu.harness.mcp.McpManager? = null,
    private val contextExecutor: AgentContextExecutor? = null,
    private val messageStore: SessionTreeStore? = null,
    private val eventBus: top.wkbin.taixu.harness.events.HarnessEventBus? = null,
    private val privilegeManager: PrivilegeManager? = null,
    private val androidAppManager: AndroidAppManager? = null,
    private val androidAppRepository: AndroidAppRepository? = null,
    private val shizukuApis: ShizukuSystemApis? = null,
    private val hostGuiController: top.wkbin.taixu.runtime.gui.HostGuiController? = null,
    private val buildScriptToolExecutor: BuildScriptToolExecutor? = null,
    private val promptRouter: top.wkbin.taixu.harness.prompt.PromptRouter? = null,
    private val checkpointStore: top.wkbin.taixu.harness.checkpoint.CheckpointStore? = null,
    private val dualAgentCoordinator: top.wkbin.taixu.harness.dual.DualAgentCoordinator? = null,
    private val embeddedAdbManager: EmbeddedAdbManager? = null,
) {
    @Inject
    lateinit var settingsDataStore: AgentPreferences

    suspend fun execute(
        toolCall: ToolCall,
        sessionId: String = "",
        workspace: String = "",
        bypassApproval: Boolean = false,
        allowApprovalRequest: Boolean = true,
        progressReporter: (suspend (String) -> Unit)? = null,
        operationId: String? = null,
    ): ToolResult {
        val now = System.currentTimeMillis()
        val outcome = try {
            if (!bypassApproval && sessionId.isNotBlank()) {
                val repository = approvalRepository
                val sessionMode = sessionDao?.findById(sessionId)?.approvalMode?.let(ApprovalMode::fromId)
                val mode = sessionMode ?: repository?.currentMode() ?: ApprovalMode.FULL_ACCESS
                val decision = approvalPolicyEngine.decide(mode, toolCall.tool, toolCall.args, workspace, toolCall.rawToolName)
                if (decision.required) {
                    if (!allowApprovalRequest) {
                        return ToolResult(
                            id = UUID.randomUUID().toString(),
                            createdAt = now,
                            toolCallId = toolCall.id,
                            success = false,
                            output = "该工具需要用户审批，子智能体后台 Lane 不支持暂停审批；请交由主智能体调用。",
                        )
                    }
                    checkNotNull(repository) { "审批仓库未初始化" }
                    val request = approvalPolicyEngine.createRequest(sessionId, toolCall, workspace, decision, operationId)
                    repository.create(request)
                    eventBus?.emit(
                        top.wkbin.taixu.harness.events.HarnessEvent.ApprovalRequested(
                            sessionId = sessionId,
                            timestamp = now,
                            operationId = operationId,
                            approvalRequestId = request.id,
                            toolName = request.toolName,
                            riskLevel = request.riskLevel,
                        ),
                    )
                    return ToolResult(
                        id = UUID.randomUUID().toString(),
                        createdAt = now,
                        toolCallId = toolCall.id,
                        success = false,
                        output = "等待用户批准：${decision.summary}\n${decision.reason}",
                        awaitingApproval = true,
                        approvalRequestId = request.id,
                    )
                }
            }
            executeTool(toolCall.tool, toolCall.args, toolCall.rawToolName, sessionId, workspace, progressReporter, operationId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            false to "工具执行异常：${throwable.message ?: throwable::class.simpleName}"
        }
        val (success, rawOutput) = outcome
        val finalOutput = if (!success && !rawOutput.contains("【太墟") && !rawOutput.contains("【已强制拦截")) {
            rawOutput + "\n\n【太墟 Harness 调试提示】：本次工具调用未成功。请仔细阅读上方错误信息，分析具体原因并在下一步中调整策略，严禁使用相同参数盲目重试。"
        } else {
            rawOutput
        }
        linuxEnvironmentManager?.refreshIfNeeded()
        return ToolResult(
            id = UUID.randomUUID().toString(),
            createdAt = now,
            toolCallId = toolCall.id,
            success = success,
            output = secretRedactor.redact(
                value = truncateOutput(finalOutput),
                secretValues = linuxEnvironmentManager?.values?.value?.values.orEmpty(),
                privacyMode = if (::settingsDataStore.isInitialized) runCatching { settingsDataStore.environmentPrivacyMode.first() }.getOrDefault(true) else true,
            ),
        )
    }

    /**
     * 输出超限时带元数据截断（pi 的 truncate 设计）：保留头部，并明确告知模型
     * 完整输出的规模与截断事实，引导其用 grep/head/tail 精准取段，
     * 而不是对静默截断的内容得出片面结论。
     */
    private fun truncateOutput(output: String): String {
        if (output.length <= MAX_OUTPUT_LENGTH) return output
        val kept = output.take(TRUNCATE_KEEP_LENGTH)
        val totalLines = output.count { it == '\n' } + 1
        val keptLines = kept.count { it == '\n' } + 1
        return buildString {
            append(kept)
            append("\n\n[输出已截断：完整输出共 ")
            append(totalLines)
            append(" 行 / ")
            append(output.length)
            append(" 字符，以上仅显示前 ")
            append(keptLines)
            append(" 行。需要其余部分请用 grep 过滤关键字、head/tail 取首尾、或 sed -n 'N,Mp' 取指定行段，不要原样重复执行同一命令。]")
        }
    }

    private suspend fun executeTool(
        tool: HarnessTool,
        rawArgs: JsonObject,
        rawToolName: String?,
        sessionId: String,
        workspace: String,
        progressReporter: (suspend (String) -> Unit)?,
        operationId: String?,
    ): Pair<Boolean, String> {
        val args = top.wkbin.taixu.harness.validation.ToolSchemaValidator.normalizeArgs(rawArgs)
        val activeFileAccess = if (workspace.isNotBlank()) fileAccess.withBase(workspace) else fileAccess
        return when (tool) {
            HarnessTool.READ -> {
                val path = requireString(args, "path")
                val offset = args["offset"]?.jsonPrimitive?.content?.trim()?.toIntOrNull()
                val limit = args["limit"]?.jsonPrimitive?.content?.trim()?.toIntOrNull()
                activeFileAccess.read(path, offset, limit).toToolOutput(actionName = "read")
            }
            HarnessTool.WRITE -> {
                val path = requireString(args, "path")
                val content = requireString(args, "content")
                captureBeforeWrite(sessionId, activeFileAccess, path)
                val linesAdded = content.lines().size
                activeFileAccess.write(path, content)
                    .toToolOutput("已写入 $path\nDIFF_STAT: +$linesAdded -0", actionName = "write")
            }
            HarnessTool.EDIT -> {
                val path = requireString(args, "path")
                val oldText = requireString(args, "oldText")
                val newText = requireString(args, "newText")
                captureBeforeWrite(sessionId, activeFileAccess, path)
                val linesAdded = newText.lines().size
                val linesDeleted = oldText.lines().size
                activeFileAccess.edit(path, oldText, newText)
                    .toToolOutput("已修改 $path\nDIFF_STAT: +$linesAdded -$linesDeleted", actionName = "edit")
            }
            HarnessTool.BASE -> executeBase(args, workspace)
            HarnessTool.PROCESS -> executeProcess(args, workspace)
            HarnessTool.HOST -> executeHost(args, operationId, sessionId)
            HarnessTool.DOWNLOAD -> executeDownload(args, activeFileAccess, progressReporter)
            HarnessTool.MEMORY -> contextExecutor?.executeMemory(args, sessionId, workspace) ?: (false to "未初始化记忆执行器")
            HarnessTool.PLAN -> contextExecutor?.executePlan(args, sessionId) ?: (false to "未初始化计划执行器")
            HarnessTool.SCRATCHPAD -> contextExecutor?.executeScratchpad(args, sessionId) ?: (false to "未初始化草稿执行器")
            HarnessTool.HISTORY_SEARCH -> executeHistorySearch(args, sessionId)
            HarnessTool.HISTORY_READ -> executeHistoryRead(args, sessionId)
            HarnessTool.BUILD_SCRIPT -> buildScriptToolExecutor?.execute(args, workspace) ?: (false to "未初始化构建脚本管理器")
            HarnessTool.SUBAGENT -> if (rawToolName.equals("invoke_dual_agent", ignoreCase = true)) {
                dualAgentCoordinator?.executeFromTool(args, sessionId, workspace) ?: (false to "未初始化双智能体编排器")
            } else {
                subagentOrchestrator?.executeSubagents(args, sessionId) ?: (false to "未初始化子智能体编排器")
            }
            HarnessTool.MCP -> mcpManager?.executeTool(rawToolName ?: "mcp", args) ?: (false to "未初始化 MCP 管理器")
            HarnessTool.LOAD_RULE -> {
                val rule = requireString(args, "rule")
                val content = promptRouter?.loadRule(rule)
                if (content != null) {
                    true to "【规则块：$rule】\n$content"
                } else {
                    false to "未知规则块：$rule。可用：workflow / code-navigation / security / memory / environment-proot / tools"
                }
            }
        }
    }

    /** 宿主 Android 特权通道；权限在每次执行前实时复核，不能仅依赖启动时快照。 */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun executeHost(args: JsonObject, operationId: String?, sessionId: String): Pair<Boolean, String> {
        val action = requireString(args, "action").trim().lowercase()

        // Logcat 优先走内置无线 ADB，不依赖 Shizuku/Root；不可用时再回退原特权通道。
        if (action == "logcat" && embeddedAdbManager != null) {
            val explicitPort = args["port"]?.jsonPrimitive?.content?.trim()?.toIntOrNull()
            val adbResult = embeddedAdbManager.captureLogcat(
                EmbeddedAdbManager.LogcatRequest(
                    packageName = args["package"]?.jsonPrimitive?.content?.trim().orEmpty(),
                    tag = args["tag"]?.jsonPrimitive?.content?.trim().orEmpty(),
                    priority = args["priority"]?.jsonPrimitive?.content?.trim()?.uppercase()?.firstOrNull() ?: 'V',
                    keyword = args["keyword"]?.jsonPrimitive?.content?.trim().orEmpty(),
                    lines = optionalLong(args, "tail_lines", 200L, 1L, 2_000L).toInt(),
                ),
                explicitPort = explicitPort,
            )
            if (adbResult.success) {
                return true to "mode wireless-adb · exit ${adbResult.exitCode ?: 0}\n${adbResult.output.trim()}"
            }
            val fallbackManager = privilegeManager ?: return false to adbResult.output.ifBlank {
                "无线 ADB 未连接；请在开发者控制台开启无线调试并完成一次配对。"
            }
            val info = fallbackManager.getPrivilegeInfo()
            if (info.mode == ExecutionMode.PROOT || !info.modeActive) {
                return false to adbResult.output.ifBlank {
                    "无线 ADB 未连接；请在开发者控制台开启无线调试并完成一次配对。"
                }
            }
        }
        val manager = privilegeManager ?: return false to "未初始化宿主权限执行器"

        // settings_put system 命名空间优先走 Android ContentResolver API（需 WRITE_SETTINGS），
        // 避免 Shizuku shell 在部分国产 ROM 上被 SettingsProvider 静默拒绝（exit 22）。
        // secure/global 命名空间需 WRITE_SECURE_SETTINGS（第三方应用不可得），仍走 shell。
        if (action == "settings_put") {
            val namespace = requireSettingsNamespace(args)
            val key = requireHostIdentifier(args, "key", SETTINGS_KEY)
            val value = requireString(args, "value")
            if (namespace == "system") {
                val apiOk = manager.writeSystemSetting(key, value)
                if (apiOk) {
                    android.util.Log.i(
                        "TaiXu-Host",
                        secretRedactor.redact("action=settings_put via API success: system.$key=$value"),
                    )
                    return true to "mode api · exit 0\n[Android API] settings put system $key = $value"
                }
                // API 写入失败（通常是未授权 WRITE_SETTINGS），发事件引导用户授权，然后回退 shell
                eventBus?.emit(
                    top.wkbin.taixu.harness.events.HarnessEvent.PermissionRequired(
                        sessionId = sessionId.ifBlank { "unknown" },
                        timestamp = System.currentTimeMillis(),
                        permission = "WRITE_SETTINGS",
                        reason = "修改系统设置（如亮度）需要授权「修改系统设置」权限",
                    )
                )
            }
        }

        return when (action) {
            "status" -> {
                val info = manager.getPrivilegeInfo()
                true to buildString {
                    append("当前生效模式：").append(info.mode.title)
                    append("\n权限状态：").append(if (info.modeActive) "已授权" else "未授权")
                    append("\nShizuku：").append(if (info.shizukuAvailable) "可用 (shell UID 2000)" else "不可用")
                    append("\nRoot：").append(if (info.rootAvailable) "可用 (UID 0)" else "不可用或未选择")
                }
            }
            "app_list" -> executeCachedApps(args)
            "screen_observe" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val onlyInteractive = args["only_interactive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                val res = gui.observeScreen(onlyInteractive)
                res.fold(
                    onSuccess = { obs -> true to obs.toAgentSummary() },
                    onFailure = { err -> false to "感知屏幕失败：${err.message}" }
                )
            }
            "screen_click" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val x = requireInt(args, "x")
                val y = requireInt(args, "y")
                val res = gui.click(x, y)
                res.fold(
                    onSuccess = { msg -> true to msg },
                    onFailure = { err -> false to err.message.orEmpty() }
                )
            }
            "screen_swipe" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val x1 = requireInt(args, "x1")
                val y1 = requireInt(args, "y1")
                val x2 = requireInt(args, "x2")
                val y2 = requireInt(args, "y2")
                val durationMs = optionalLong(args, "duration_ms", 300L, 50L, 3000L)
                val res = gui.swipe(x1, y1, x2, y2, durationMs)
                res.fold(
                    onSuccess = { msg -> true to msg },
                    onFailure = { err -> false to err.message.orEmpty() }
                )
            }
            "screen_input_text" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val text = requireString(args, "text")
                val res = gui.inputText(text)
                res.fold(
                    onSuccess = { msg -> true to msg },
                    onFailure = { err -> false to err.message.orEmpty() }
                )
            }
            "screen_key" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val key = requireString(args, "key")
                val res = gui.sendKey(key)
                res.fold(
                    onSuccess = { msg -> true to msg },
                    onFailure = { err -> false to err.message.orEmpty() }
                )
            }
            "app_launch" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val packageName = requireHostIdentifier(args, "package", PACKAGE_NAME)
                val res = gui.launchApp(packageName)
                res.fold(
                    onSuccess = { msg -> true to msg },
                    onFailure = { err -> false to err.message.orEmpty() }
                )
            }
            "screen_capture" -> {
                val gui = hostGuiController ?: return false to "未初始化 GUI 控制器"
                val targetPath = requireString(args, "path")
                val res = gui.captureScreenshot(targetPath)
                res.fold(
                    onSuccess = { msg -> true to msg },
                    onFailure = { err -> false to err.message.orEmpty() }
                )
            }
            else -> {
                val packageName = if (action in APP_DATABASE_GUARDED_ACTIONS || action == "app_grant_permission") {
                    requireHostIdentifier(args, "package", PACKAGE_NAME)
                } else ""
                if (action in APP_DATABASE_GUARDED_ACTIONS) {
                    (androidAppManager ?: return false to "未初始化应用管理器").requireInitialized(packageName)
                }
                val info = manager.getPrivilegeInfo()
                require(info.mode != ExecutionMode.PROOT && info.modeActive) {
                    "权限不足：冻结、启用、卸载或授权应用前，请先在设置中授权并切换到 Shizuku 或 Root 模式。"
                }

                // Shizuku 生效时优先 Binder 直调：免 shell 转义、异常结构化。
                // 仅通道不可用时才回退 shell；远端明确拒绝则直接报告不重试。
                if (info.mode == ExecutionMode.SHIZUKU && shizukuApis != null) {
                    val userId = optionalLong(args, "user", 0L, 0L, 999L).toInt()
                    val binderOutcome = when (action) {
                        "app_grant_permission" -> {
                            val permission = requireHostIdentifier(args, "permission", ANDROID_PERMISSION)
                            shizukuApis.grantRuntimePermission(packageName, permission, userId)
                        }
                        "app_freeze", "package_disable" ->
                            shizukuApis.setApplicationEnabledSetting(packageName, enabled = false, userId = userId)
                        "app_unfreeze", "package_enable" ->
                            shizukuApis.setApplicationEnabledSetting(packageName, enabled = true, userId = userId)
                        else -> null
                    }
                    when (binderOutcome) {
                        is BinderOutcome.Success -> {
                            android.util.Log.i("TaiXu-Host", "action=$action via binder success pkg=$packageName")
                            if (action in APP_DATABASE_GUARDED_ACTIONS) androidAppManager?.synchronize()
                            return true to buildString {
                                append("mode shizuku-api · exit 0")
                                append("\n[Android Binder] $action $packageName 成功")
                                if (action == "app_grant_permission") {
                                    append(" 权限=").append(requireHostIdentifier(args, "permission", ANDROID_PERMISSION))
                                }
                            }
                        }
                        is BinderOutcome.Failed ->
                            return false to "宿主侧拒绝该操作：${binderOutcome.message}（模式=${info.mode.shortLabel}）。请核对包名/权限名后重试。"
                        else -> Unit
                    }
                }

                val command = buildHostCommand(action, args)
                require(command.length <= MAX_COMMAND_LENGTH) { "命令过长（${command.length} 字符，上限 $MAX_COMMAND_LENGTH）" }
                val hostOperationId = operationId?.takeIf { it.isNotBlank() } ?: "host-${UUID.randomUUID()}"
                val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true) { cause ->
                    if (cause is CancellationException) manager.cancelShellCommand(hostOperationId)
                }
                val result = try {
                    manager.executeShellCommand(command, hostOperationId)
                } finally {
                    cancelHandle?.dispose()
                }
                android.util.Log.i(
                    "TaiXu-Host",
                    secretRedactor.redact(
                        "action=$action exit=${result.exitCode} success=${result.success}\n" +
                            "cmd=$command\nstdout=${result.stdout.take(500)}\nstderr=${result.stderr.take(300)}",
                    ),
                )
                val body = buildString {
                    append("mode ").append(info.mode.shortLabel).append(" · exit ").append(result.exitCode)
                    if (result.stdout.isNotBlank()) append("\n").append(result.stdout.trim())
                    if (result.stderr.isNotBlank()) append("\n").append(result.stderr.trim())
                }
                if (result.success && action in APP_DATABASE_GUARDED_ACTIONS) {
                    // Keep the agent's next app_list read coherent with the mutation it just made.
                    androidAppManager?.synchronize()
                }
                result.success to body
            }
        }
    }

    private suspend fun executeCachedApps(args: JsonObject): Pair<Boolean, String> {
        val repository = androidAppRepository ?: return false to "未初始化应用数据库"
        if (repository.count() == 0) return false to "应用数据库尚未初始化；请先到设置 → 应用管理完成初始化和同步。"
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        val limit = optionalLong(args, "limit", 50L, 1L, 200L).toInt()
        val includeSystem = args["include_system"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val apps = repository.search(query, if (includeSystem) limit else 200)
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .take(limit)
            .toList()
        if (apps.isEmpty()) return true to "应用数据库中未找到：${query.ifBlank { "全部应用" }}"
        return true to apps.joinToString("\n") { app ->
            buildString {
                append(app.label).append(" | ").append(app.packageName)
                append(" | ").append(if (app.isSystemApp) "系统" else "用户")
                append(" | ").append(if (app.isEnabled) "启用" else "禁用")
                if (app.isSuspended) append(" | 冻结")
                if (app.isNetworkRestricted) append(" | 后台联网受限")
            }
        }
    }

    private fun buildHostCommand(action: String, args: JsonObject): String = when (action) {
        "exec" -> requireString(args, "command")
        "settings_get" -> {
            val namespace = requireSettingsNamespace(args)
            val key = requireHostIdentifier(args, "key", SETTINGS_KEY)
            "/system/bin/settings get $namespace ${shellQuote(key)}"
        }
        "settings_put" -> {
            val namespace = requireSettingsNamespace(args)
            val key = requireHostIdentifier(args, "key", SETTINGS_KEY)
            val value = requireString(args, "value")
            // 屏幕亮度写入：自适应亮度开启时系统会忽略手动值，先切到手动模式；
            // 写入后回读验证，因为 Shizuku UserService 进程若 UID 非 shell，
            // WRITE_SETTINGS 会被 SettingsProvider 静默拒绝（exit 0 但值不变）。
            if (namespace == "system" && key == "screen_brightness") {
                val quoted = shellQuote(value)
                buildString {
                    append("/system/bin/settings put system screen_brightness_mode 0; ")
                    append("/system/bin/settings put system screen_brightness $quoted; ")
                    append("echo \"uid=$(id -u) mode=$(/system/bin/settings get system screen_brightness_mode) ")
                    append("requested=$quoted actual=$(/system/bin/settings get system screen_brightness)\"")
                }
            } else {
                "/system/bin/settings put $namespace ${shellQuote(key)} ${shellQuote(value)}"
            }
        }
        "package_list" -> {
            val filter = args["filter"]?.jsonPrimitive?.content?.trim().orEmpty()
            "/system/bin/pm list packages" + if (filter.isBlank()) "" else " | /system/bin/grep -F -- ${shellQuote(filter)}"
        }
        "package_disable", "package_enable", "package_uninstall_user", "app_freeze", "app_unfreeze", "app_grant_permission" -> {
            val packageName = requireHostIdentifier(args, "package", PACKAGE_NAME)
            val user = optionalLong(args, "user", 0L, 0L, 999L)
            when (action) {
                "package_disable", "app_freeze" -> "/system/bin/pm disable-user --user $user ${shellQuote(packageName)}"
                "package_enable", "app_unfreeze" -> "/system/bin/pm enable --user $user ${shellQuote(packageName)}"
                "app_grant_permission" -> {
                    val permission = requireHostIdentifier(args, "permission", ANDROID_PERMISSION)
                    "/system/bin/pm grant ${shellQuote(packageName)} ${shellQuote(permission)}"
                }
                else -> "/system/bin/pm uninstall --user $user ${shellQuote(packageName)}"
            }
        }
        "device_status" ->
            "echo '[battery]'; dumpsys battery | grep -E 'level|status|temperature'" +
                "; echo '[network]'; dumpsys connectivity | head -30" +
                "; echo '[foreground]'; dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -6" +
                "; echo '[storage]'; df -h /data | tail -2"
        "logcat" -> {
            val lines = optionalLong(args, "tail_lines", 200L, 1L, 2_000L)
            val tag = args["tag"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (tag.isBlank()) "/system/bin/logcat -d -t $lines"
            else {
                require(LOGCAT_TAG.matches(tag)) { "logcat tag 格式不合法" }
                "/system/bin/logcat -d -t $lines -s ${shellQuote("$tag:*")}"
            }
        }
        else -> throw IllegalArgumentException(
            "不支持的 host action：$action；可用 status/exec/settings_get/settings_put/package_list/package_disable/package_enable/package_uninstall_user/app_list/app_freeze/app_unfreeze/app_grant_permission/logcat",
        )
    }

    private fun requireSettingsNamespace(args: JsonObject): String {
        val namespace = requireString(args, "namespace").trim().lowercase()
        require(namespace in setOf("system", "secure", "global")) { "namespace 仅支持 system/secure/global" }
        return namespace
    }

    private fun requireHostIdentifier(args: JsonObject, key: String, pattern: Regex): String {
        val value = requireString(args, key).trim()
        require(pattern.matches(value)) { "$key 格式不合法" }
        return value
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private suspend fun executeHistorySearch(args: JsonObject, sessionId: String): Pair<Boolean, String> {
        val query = requireString(args, "query")
        val limit = optionalLong(args, "limit", 8L, 1L, 20L).toInt()
        val matches = messageStore?.search(sessionId, query, limit).orEmpty()
        if (matches.isEmpty()) return true to "未找到匹配历史：$query"
        return true to matches.mapIndexed { index, message ->
            "[$index] id=${message.id} time=${message.createdAt} ${historyLabel(message)}"
        }.joinToString("\n")
    }

    private suspend fun executeHistoryRead(args: JsonObject, sessionId: String): Pair<Boolean, String> {
        val messageId = args["message_id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
        val index = args["index"]?.jsonPrimitive?.content?.trim()?.toIntOrNull()
        require(messageId != null || index != null) { "history.read 需要 message_id 或 index" }
        val message = messageStore?.read(sessionId, messageId, index)
            ?: return false to "未找到指定历史消息"
        return true to historyLabel(message, full = true).take(MAX_HISTORY_READ_OUTPUT)
    }

    private fun historyLabel(message: HarnessMessage, full: Boolean = false): String = when (message) {
        is CapabilityEvent -> "能力事件 ${message.name}: ${message.details}"
        is UserMessage -> "用户：${message.text.take(if (full) MAX_HISTORY_READ_OUTPUT else 240)}"
        is AssistantText -> "助手：${message.text.take(if (full) MAX_HISTORY_READ_OUTPUT else 240)}" +
            if (full && !message.reasoning.isNullOrBlank()) "\nreasoning:\n${message.reasoning.take(MAX_HISTORY_READ_OUTPUT)}" else ""
        is ToolCall -> "工具调用 ${message.rawToolName ?: message.tool}: ${message.args}" +
            if (full) "\nreasoning:\n${message.reasoning.orEmpty().take(MAX_HISTORY_READ_OUTPUT)}" else ""
        is ToolResult -> "工具结果：${message.output.take(if (full) MAX_HISTORY_READ_OUTPUT else 240)}"
    }

    private suspend fun executeBase(args: JsonObject, workspace: String): Pair<Boolean, String> {
        val command = requireString(args, "command")
        require(command.length <= MAX_COMMAND_LENGTH) { "命令过长（${command.length} 字符，上限 $MAX_COMMAND_LENGTH）" }
        val cwd = pathResolver.resolveWorkingDirectory(args["cwd"]?.jsonPrimitive?.content, workspace)
        val commandOutputCompressionEnabled = if (::settingsDataStore.isInitialized) {
            runCatching { settingsDataStore.commandOutputCompressionEnabled.first() }.getOrDefault(true)
        } else {
            true
        }
        val preparedCommand = RtkCommandOptimizer.prepare(command, commandOutputCompressionEnabled)
        val configuredTimeoutSeconds = if (::settingsDataStore.isInitialized) {
            runCatching { settingsDataStore.baseCommandTimeoutSeconds.first() }
                .getOrDefault(SettingsDataStore.DEFAULT_BASE_COMMAND_TIMEOUT_SECONDS)
        } else {
            SettingsDataStore.DEFAULT_BASE_COMMAND_TIMEOUT_SECONDS
        }
        val timeoutSeconds = optionalLong(
            args = args,
            key = "timeout_seconds",
            default = configuredTimeoutSeconds.toLong(),
            min = MIN_BASE_TIMEOUT_SECONDS,
            max = MAX_BASE_TIMEOUT_SECONDS,
        )
        val result = linuxRuntime.execute(
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

    private suspend fun executeProcess(args: JsonObject, workspace: String): Pair<Boolean, String> {
        val action = requireString(args, "action").trim().lowercase()
        return when (action) {
            "start" -> {
                val externalId = requireProcessId(args)
                val internalId = processId(externalId)
                val command = requireString(args, "command")
                require(command.length <= MAX_COMMAND_LENGTH) { "命令过长（${command.length} 字符，上限 $MAX_COMMAND_LENGTH）" }
                val cwd = pathResolver.resolveWorkingDirectory(args["cwd"]?.jsonPrimitive?.content, workspace)
                val existing = linuxRuntime.listBackground().firstOrNull { it.id == internalId && it.session.isAlive }
                require(existing == null) { "后台进程 $externalId 已在运行；请先查询状态或停止它" }
                val managed = linuxRuntime.startBackground(
                    id = internalId,
                    command = ShellCommand(
                        commandLine = command,
                        workingDirectory = cwd,
                        timeoutMs = Long.MAX_VALUE,
                    ),
                    type = ProcessType.COMMAND,
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
                val managed = linuxRuntime.listBackground().firstOrNull { it.id == processId(externalId) }
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
                val tailLines = optionalLong(args, "tail_lines", DEFAULT_PROCESS_LOG_LINES, 1L, MAX_PROCESS_LOG_LINES).toInt()
                val logs = linuxRuntime.getBackgroundLogs(processId(externalId)).takeLast(tailLines)
                true to if (logs.isEmpty()) "后台进程 $externalId 暂无日志" else logs.joinToString("\n")
            }
            "list" -> {
                val managed = linuxRuntime.listBackground().filter { it.id.startsWith(AGENT_PROCESS_PREFIX) }
                true to if (managed.isEmpty()) {
                    "当前没有 Agent 管理的后台进程"
                } else {
                    managed.joinToString("\n") {
                        val externalId = it.id.removePrefix(AGENT_PROCESS_PREFIX)
                        "$externalId · ${if (it.session.isAlive) "运行中" else "已退出"} · ${(System.currentTimeMillis() - it.startedAt).coerceAtLeast(0L)} ms"
                    }
                }
            }
            "stop" -> {
                val externalId = requireProcessId(args)
                val stopped = linuxRuntime.stopBackground(processId(externalId))
                stopped to if (stopped) "后台进程已停止：$externalId" else "未找到后台进程：$externalId"
            }
            else -> false to "不支持的 process action：$action；可用 start/status/logs/list/stop"
        }
    }


    private fun requireProcessId(args: JsonObject): String {
        val id = requireString(args, "id").trim().lowercase()
        require(PROCESS_ID.matches(id)) { "进程 id 仅允许小写字母、数字、点、下划线和连字符，长度 1-64" }
        return id
    }

    private fun processId(externalId: String): String = AGENT_PROCESS_PREFIX + externalId

    private suspend fun executeDownload(
        args: JsonObject,
        activeFileAccess: WorkspaceFileAccess,
        progressReporter: (suspend (String) -> Unit)?,
    ): Pair<Boolean, String> {
        val url = requireString(args, "url")
        require(url.startsWith("https://", ignoreCase = true)) { "下载地址必须使用 HTTPS" }
        val destinationPath = requireString(args, "destination")
        val destination = activeFileAccess.resolveDownloadDestination(destinationPath)
        val sha256 = args["sha256"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val maxAttempts = optionalLong(args, "max_attempts", DEFAULT_DOWNLOAD_ATTEMPTS, 1L, MAX_DOWNLOAD_ATTEMPTS).toInt()
        val maxBytes = optionalLong(args, "max_bytes", DEFAULT_DOWNLOAD_MAX_BYTES, 1L, MAX_DOWNLOAD_MAX_BYTES)
        var latestProgress: DownloadEvent.Progress? = null
        var verified = false
        var completedFile: java.io.File? = null
        val startedAt = System.currentTimeMillis()
        var lastReportedAt = 0L
        fileDownloader.download(
            DownloadRequest(
                url = url,
                destination = destination,
                sha256 = sha256,
                maxAttempts = maxAttempts,
                maxBytes = maxBytes,
            ),
        ).collect { event ->
            when (event) {
                is DownloadEvent.Progress -> {
                    latestProgress = event
                    val now = System.currentTimeMillis()
                    if (progressReporter != null && (now - lastReportedAt >= PROGRESS_REPORT_INTERVAL_MS || event.totalBytes != null && event.downloadedBytes == event.totalBytes)) {
                        lastReportedAt = now
                        progressReporter(formatDownloadProgress(event, startedAt))
                    }
                }
                DownloadEvent.Verifying -> {
                    verified = true
                    progressReporter?.invoke("正在校验下载文件 SHA-256…")
                }
                is DownloadEvent.Completed -> completedFile = event.file
                DownloadEvent.Started -> Unit
            }
        }
        val file = completedFile ?: destination
        val size = file.length()
        if (hasImageExtension(destination) && !isImageMagic(destination)) {
            return false to buildString {
                append("下载失败：目标应为图片，但内容不是有效的图片格式（JPEG/PNG/GIF/WebP/BMP）。")
                append("\n来源：").append(url)
                append("\n大小：").append(size).append(" bytes")
                append("\n常见原因：图床反爬/防盗链返回了占位图或错误页，或链接已过期。")
                append("\n建议：更换图源（换站点/换域名）或稍后重试，不要用相同 URL 原样重试。")
            }
        }
        val duplicateOf = findDuplicateDownload(destination)
        val body = buildString {
            append("下载完成：").append(destinationPath)
            append("\n大小：").append(size).append(" bytes")
            latestProgress?.totalBytes?.let { append(" / ").append(it).append(" bytes") }
            append("\n特性：HTTPS、HTTP Range 断点续传、自动重试（最多 ").append(maxAttempts).append(" 次）")
            if (verified) append("\nSHA-256：已校验")
            append("\n说明：当前下载器是单连接续传，不是多线程分片下载。")
            if (duplicateOf != null) {
                append("\n\n警告：本次下载内容与工作区已有文件 ").append(duplicateOf)
                append(" 完全相同（SHA-256 一致），疑似图床反爬占位图。请立即更换图源（换站点/换域名），")
                append("不要继续从同一图床高频下载，也不要重复下载相同的 URL。")
            }
        }
        return true to body
    }

    private fun hasImageExtension(file: java.io.File): Boolean =
        file.extension.lowercase() in IMAGE_DOWNLOAD_EXTENSIONS

    private fun isImageMagic(file: java.io.File): Boolean {
        val head = ByteArray(16)
        val read = try {
            java.io.FileInputStream(file).use { it.read(head) }
        } catch (_: java.io.IOException) {
            return true
        }
        if (read < 3) return false
        val jpeg = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        val png = read >= 8 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
        val gif = head[0] == 0x47.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte()
        val bmp = head[0] == 0x42.toByte() && head[1] == 0x4D.toByte()
        val webp = read >= 12 && head[0] == 0x52.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte() && head[3] == 0x46.toByte() &&
            head[8] == 0x57.toByte() && head[9] == 0x45.toByte() && head[10] == 0x42.toByte() && head[11] == 0x50.toByte()
        return jpeg || png || gif || bmp || webp
    }

    /** 检测同目录下是否已有字节完全相同的文件（反爬占位图的典型特征：不同 URL 下载结果一模一样）。 */
    private fun findDuplicateDownload(file: java.io.File): String? {
        if (file.length() <= 0L || file.length() > 32L * 1024 * 1024) return null
        val parent = file.parentFile ?: return null
        val candidates = parent.listFiles { f -> f.isFile && f.name != file.name && f.length() == file.length() }
            ?: return null
        val digest = try {
            sha256Of(file)
        } catch (_: Exception) {
            return null
        } ?: return null
        for (candidate in candidates) {
            if (sha256Of(candidate) == digest) return candidate.name
        }
        return null
    }

    private fun sha256Of(file: java.io.File): String? = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    private fun formatDownloadProgress(event: DownloadEvent.Progress, startedAt: Long): String {
        val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        val speed = event.downloadedBytes * 1000L / elapsedMs
        val downloaded = formatBytes(event.downloadedBytes)
        val total = event.totalBytes?.let(::formatBytes)
        val percent = event.totalBytes?.takeIf { it > 0L }?.let { event.downloadedBytes * 100 / it }
        return buildString {
            append("下载中：").append(downloaded)
            if (total != null) {
                append(" / ").append(total)
                percent?.let { append(" (").append(it.coerceIn(0L, 100L)).append("%)") }
            }
            append(" · ").append(formatBytes(speed)).append("/s")
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index += 1
        }
        return if (value >= 100 || value % 1.0 == 0.0) "${value.toInt()} ${units[index]}" else "${"%.1f".format(java.util.Locale.US, value)} ${units[index]}"
    }


    /** 写工具触碰前，捕获该路径的轮初内容进 checkpoint（同轮同路径去重）。 */
    private suspend fun captureBeforeWrite(
        sessionId: String,
        activeFileAccess: WorkspaceFileAccess,
        path: String,
    ) {
        val normalized = path.trim().trimStart('/')
        // 超过快照上限的文件整体跳过：避免大文件整读入堆内存（OOM 风险），
        // 且 commit 恢复走 write（同样受 1MiB 上限约束），快照了也恢复不回去。
        // 注意不能返回 null 充当"文件不存在"快照——那会让 rewind 误删该文件。
        val size = activeFileAccess.fileSizeOrNull(normalized)
        if (size != null && size > CHECKPOINT_SNAPSHOT_MAX_BYTES) return
        checkpointStore?.capture(sessionId, normalized, activeFileAccess.previewOrNull(normalized))
    }

    private fun AppResult<Any>.toToolOutput(successMessage: String = "", actionName: String = ""): Pair<Boolean, String> = when (this) {
        is AppResult.Success -> true to successMessage.ifBlank { data.toString() }
        is AppResult.Failure -> {
            val baseError = error.message
            val reflectionHint = when (actionName) {
                "edit" -> "\n\n【文本替换失败反思与纠错要求】\n未在目标文件中找到唯一匹配的 oldText。请立即调用 read 查看该文件的最新真实内容与行号，获取精确匹配的内容后再发起 edit，严禁盲目猜测或重复相同内容！"
                "read" -> "\n\n【文件读取失败反思与纠错要求】\n无法读取指定路径文件。请调用 base 执行 ls 或 find 确定文件的真实存在路径，切勿盲目重复错误路径！"
                else -> ""
            }
            false to (baseError + reflectionHint)
        }
    }

    private fun requireString(args: JsonObject, key: String): String {
        val value = args[key]?.jsonPrimitive?.content
        require(!value.isNullOrBlank()) { "缺少参数：$key" }
        require(value.length <= MAX_ARG_LENGTH) { "参数 $key 过长（${value.length} 字符，上限 $MAX_ARG_LENGTH）" }
        return value
    }

    private fun requireInt(args: JsonObject, key: String): Int {
        val raw = args[key]?.jsonPrimitive?.content?.trim()
        require(!raw.isNullOrBlank()) { "缺少参数：$key" }
        return raw.toIntOrNull() ?: throw IllegalArgumentException("参数 $key 必须是整数")
    }

    private fun optionalLong(args: JsonObject, key: String, default: Long, min: Long, max: Long): Long {
        val raw = args[key]?.jsonPrimitive?.content?.trim() ?: return default
        val value = raw.toLongOrNull() ?: throw IllegalArgumentException("参数 $key 必须是整数")
        require(value in min..max) { "参数 $key 必须在 $min-$max 之间" }
        return value
    }

    companion object {
        const val MIN_BASE_TIMEOUT_SECONDS = 1L
        const val MAX_BASE_TIMEOUT_SECONDS = 60L * 60L
        const val MAX_OUTPUT_LENGTH = 64 * 1024
        const val TRUNCATE_KEEP_LENGTH = 60 * 1024
        const val MAX_COMMAND_LENGTH = 32 * 1024
        const val MAX_ARG_LENGTH = 1024 * 1024
        const val MAX_HISTORY_READ_OUTPUT = 48 * 1024
        const val DEFAULT_CWD = "/root"
        const val DEFAULT_DOWNLOAD_ATTEMPTS = 3L
        const val MAX_DOWNLOAD_ATTEMPTS = 10L
        private val SETTINGS_KEY = Regex("^[A-Za-z0-9._-]{1,160}$")
        private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        private val ANDROID_PERMISSION = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        private val APP_DATABASE_GUARDED_ACTIONS = setOf(
            "package_disable", "package_enable", "package_uninstall_user", "app_freeze", "app_unfreeze", "app_grant_permission",
        )
        private val LOGCAT_TAG = Regex("^[A-Za-z0-9_.-]{1,80}$")
        const val DEFAULT_DOWNLOAD_MAX_BYTES = 1024L * 1024L * 1024L
        const val MAX_DOWNLOAD_MAX_BYTES = 4L * 1024L * 1024L * 1024L

        /** checkpoint 单文件快照上限：与 WorkspaceFileAccess.MAX_WRITE_BYTES 对齐（超限跳过快照，恢复本也走 write）。 */
        const val CHECKPOINT_SNAPSHOT_MAX_BYTES = 1L * 1024L * 1024L
        private val IMAGE_DOWNLOAD_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        const val PROGRESS_REPORT_INTERVAL_MS = 250L
        const val DEFAULT_PROCESS_LOG_LINES = 120L
        const val MAX_PROCESS_LOG_LINES = 500L
        const val AGENT_PROCESS_PREFIX = "agent-process:"
        val PROCESS_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    }
}
