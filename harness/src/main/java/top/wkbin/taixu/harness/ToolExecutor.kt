package top.wkbin.taixu.harness

import top.wkbin.taixu.core.database.AgentApprovalRepository
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.harness.approval.SessionApprovalGrants
import top.wkbin.taixu.harness.checkpoint.CheckpointStore
import top.wkbin.taixu.harness.core.ToolCheckpoints
import top.wkbin.taixu.harness.directory.CapabilityToolGateway
import top.wkbin.taixu.harness.directory.ScriptCallInterrupted
import top.wkbin.taixu.harness.directory.ScriptCapabilityDispatcher
import top.wkbin.taixu.harness.directory.annotationsForCall
import top.wkbin.taixu.harness.events.HarnessEvent
import top.wkbin.taixu.harness.events.HarnessEventBus
import top.wkbin.taixu.harness.mcp.McpManager
import top.wkbin.taixu.harness.validation.ToolSchemaValidator
import top.wkbin.taixu.core.security.SecretRedactor
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.model.ApprovalMode
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.runtime.LinuxEnvironmentManager
import top.wkbin.taixu.harness.effects.OutputRetention
import top.wkbin.taixu.harness.effects.ToolOutputRetention
import top.wkbin.taixu.harness.effects.foldOverlongLines
import top.wkbin.taixu.harness.effects.keepHeadWholeLines
import top.wkbin.taixu.harness.effects.keepTailWholeLines
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/**
 * Harness 工具执行器：把 LLM 发出的 ToolCall 翻译成受控操作。
 *
 * 本类是「统一切面管道 + 领域能力插槽」的管道编排层：持有 Schema 归一化、
 * PLAN 拦截、审批门控、输出脱敏与截断/落盘等横切关注点；具体能力执行委托给
 * 领域 [ToolBackend]：
 * - read / write / edit → [WorkspaceToolBackend]（工作区路径安全层）
 * - base / process → [LinuxCommandToolBackend]（PRoot 沙箱命令与后台进程）
 * - host → [HostCapabilityToolBackend]（宿主 Android 特权通道）
 * - download → [DownloadToolBackend]（HTTPS 下载）
 * - history_search / history_read / compress → [ContextMemoryToolBackend]（上下文记忆管理）
 * - ask_user → [AskUserToolBackend]（提问生命周期）
 * - load_skill / load_rule → [PromptAssetToolBackend]（按需提示资产加载）
 * - memory / plan / scratchpad / subagent / build_script / render_surface → [HarnessServiceToolBackend]（内建服务转发）
 * - use_capability / 直连 mcp__ → [CapabilityToolGateway]（能力统一网关）
 *
 * 普通执行失败以 [ToolResult] 返回；取消传播，持久化屏障由调用层负责。
 */
class ToolExecutor(
    private val fileAccess: WorkspaceFileAccess,
    private val pathResolver: HarnessPathResolver,
    private val approvalPolicyEngine: ApprovalPolicyEngine,
    private val secretRedactor: SecretRedactor,
    private val hostToolBackend: HostCapabilityToolBackend,
    private val linuxCommandToolBackend: LinuxCommandToolBackend,
    private val downloadToolBackend: DownloadToolBackend,
    private val contextMemoryToolBackend: ContextMemoryToolBackend,
    private val askUserToolBackend: AskUserToolBackend,
    private val promptAssetToolBackend: PromptAssetToolBackend,
    private val harnessServiceToolBackend: HarnessServiceToolBackend,
    private val capabilityToolGateway: CapabilityToolGateway,
    private val linuxEnvironmentManager: LinuxEnvironmentManager? = null,
    private val approvalRepository: AgentApprovalRepository? = null,
    private val sessionDao: HarnessSessionRepository? = null,
    private val mcpManager: McpManager? = null,
    private val eventBus: HarnessEventBus? = null,
    private val checkpointStore: CheckpointStore? = null,
    private val sessionApprovalGrants: SessionApprovalGrants? = null,
    private val settingsDataStore: AgentPreferences? = null,
    private val toolCheckpoints: ToolCheckpoints<ToolExecutionRequest, ToolResult> = ToolCheckpoints(),
    workspaceToolBackend: WorkspaceToolBackend? = null,
    private val environments: top.wkbin.taixu.harness.environment.SessionExecutionEnvironments? = null,
) {
    private val mutationSnapshots = WorkspaceMutationSnapshots(checkpointStore, eventBus)
    private val workspaceTools = workspaceToolBackend ?: WorkspaceToolBackend(
        operationsFor = { workspace -> if (workspace.isNotBlank()) fileAccess.withBase(workspace) else fileAccess },
        snapshots = mutationSnapshots,
    )

    /** 领域能力插槽路由表：把工具分派从分支逻辑降为一次查表（新增工具零改动管道层）。 */
    private val routing = ToolRouting(
        workspaceTools = workspaceTools,
        linuxCommandToolBackend = linuxCommandToolBackend,
        hostToolBackend = hostToolBackend,
        downloadToolBackend = downloadToolBackend,
        harnessServiceToolBackend = harnessServiceToolBackend,
        contextMemoryToolBackend = contextMemoryToolBackend,
        promptAssetToolBackend = promptAssetToolBackend,
        capabilityToolGateway = capabilityToolGateway,
    )

    private val executionBoundary = ToolExecutionBoundary(toolCheckpoints) { request, output ->
        val redacted = secretRedactor.redact(
            output, top.wkbin.taixu.harness.environment.executionOutputSecrets(environments, linuxEnvironmentManager, request.sessionId, request.workspace),
            privacyMode = settingsDataStore?.environmentPrivacyMode?.first() ?: true,
        )
        truncateOutput(redacted, request.call.rawToolName ?: HarnessApiMapper.apiName(request.call.tool),
            if (environments != null) environments.environment(request.sessionId, request.workspace).artifacts
            else if (request.workspace.isNotBlank()) top.wkbin.taixu.harness.environment.LocalExecutionArtifacts(fileAccess.withBase(request.workspace)) else null)
    }

    suspend fun execute(
        toolCall: ToolCall,
        sessionId: String = "",
        workspace: String = "",
        bypassApproval: Boolean = false,
        allowApprovalRequest: Boolean = true,
        progressReporter: (suspend (String) -> Unit)? = null,
        operationId: String? = null,
        resourceOwner: String? = null,
    ): ToolResult = if (environments != null) environments.activity(sessionId, workspace, resourceOwner) {
        executeBound(toolCall, sessionId, workspace, bypassApproval, allowApprovalRequest, progressReporter, operationId)
    } else executeBound(toolCall, sessionId, workspace, bypassApproval, allowApprovalRequest, progressReporter, operationId)

    private suspend fun executeBound(toolCall: ToolCall, sessionId: String, workspace: String, bypassApproval: Boolean,
        allowApprovalRequest: Boolean, progressReporter: (suspend (String) -> Unit)?, operationId: String?): ToolResult {
        val result = executionBoundary.execute(ToolExecutionRequest(toolCall, sessionId, workspace, operationId)) {
            executeWithPolicy(toolCall, sessionId, workspace, bypassApproval, allowApprovalRequest, progressReporter, operationId)
        }
        return if (bypassApproval) ScriptCapabilityDispatcher.replayResult(toolCall, result) else result
    }

    private suspend fun executeWithPolicy(
        toolCall: ToolCall,
        sessionId: String = "",
        workspace: String = "",
        bypassApproval: Boolean = false,
        allowApprovalRequest: Boolean = true,
        progressReporter: (suspend (String) -> Unit)? = null,
        operationId: String? = null,
    ): ToolResult {
        val now = System.currentTimeMillis()
        // ask_user 是向用户提问的动作本身，不走审批门控（策略引擎对它亦豁免）：
        // 委托 AskUserToolBackend 走提问生命周期——落一条 pending 请求（复用审批请求的
        // 暂停/恢复管道）并返回 awaitingApproval 让整轮暂停；UI 渲染问题卡，答案由
        // HarnessLoop.resolveQuestion 直接作为工具结果落库并续跑，无需重执行。
        if (toolCall.tool == HarnessTool.ASK_USER) {
            return askUserToolBackend.execute(
                AskUserRequest(toolCall, sessionId, workspace, operationId, allowApprovalRequest, now),
            )
        }
        val toolMetadata = mutableMapOf<String, String>()
        val outcome = try {
            if (!bypassApproval && sessionId.isNotBlank()) {
                val repository = approvalRepository
                // 一次查询同时取审批模式与运行意图（两级：会话级优先，回落全局默认）。
                val session = sessionDao?.findById(sessionId)
                val sessionMode = session?.approvalMode?.let(ApprovalMode::fromId)
                val mode = sessionMode ?: repository?.currentMode() ?: ApprovalMode.FULL_ACCESS
                val runMode = session?.runMode?.let(RunMode::fromId) ?: repository?.currentRunMode() ?: RunMode.BUILD
                // PLAN 只读模式与审批模式正交：命中即硬拒绝，不进审批队列（用户没打算执行，
                // 弹审批卡只是噪音）。子智能体 Lane 复用父会话 id（见 SubagentLaneRunner），
                // 因此这里同样约束后台并行子智能体，无需在 Lane 内重复判定。
                if (runMode == RunMode.PLAN) {
                    approvalPolicyEngine.planBlock(toolCall.tool, toolCall.args, toolCall.rawToolName)?.let { blocked ->
                        val blockedName = toolCall.rawToolName ?: toolCall.tool.name.lowercase()
                        return ToolResult(
                            id = UUID.randomUUID().toString(),
                            createdAt = now,
                            toolCallId = toolCall.id,
                            success = false,
                            output = buildString {
                                append("⛔ 只读规划模式（PLAN）已拦截本次调用：").append(blockedName).append('\n')
                                append("原因：").append(blocked).append('\n')
                                append("本次调用未执行，也未进入审批队列。\n")
                                append("请不要重试同一调用，也不要声称该操作已完成：")
                                append("把该动作写进规划结论的待办中，由用户切换到 BUILD（构建）模式后再执行。")
                            },
                        )
                    }
                }
                val decision = approvalPolicyEngine.decide(
                    mode, toolCall.tool, toolCall.args, workspace, toolCall.rawToolName,
                    // MCP 注解升级（escalation-only）：只采信服务端显式声明抬高审批等级，
                    // 未缓存/非 MCP 调用为 null，行为与旧版完全一致。
                    annotations = if (toolCall.tool == HarnessTool.MCP) {
                        mcpManager.annotationsForCall(toolCall.args, toolCall.rawToolName)
                    } else {
                        null
                    },
                )
                // 「本会话内记住」授权表豁免：用户此前对该操作类别批准过并勾选了记住，
                // 同类后续操作免审批直接执行。表只存内存、随会话销毁，无永久授权；
                // 只豁免本条 required 判定，策略引擎的其余约束不受影响。
                val grantedBySession = decision.required &&
                    sessionApprovalGrants != null &&
                    sessionApprovalGrants.isGranted(
                        sessionId = sessionId,
                        toolName = toolCall.rawToolName ?: toolCall.tool.name.lowercase(),
                        argumentsJson = toolCall.args.toString(),
                        riskLevel = decision.riskLevel,
                    )
                if (decision.required && !grantedBySession) {
                    if (!allowApprovalRequest) {
                        // 后台 Lane 没有可暂停的审批 UI，只能结构化交接：标记 approvalDeferred，
                        // 由 Lane 收集成待办上交父智能体。若只回一句失败文字，模型下一轮会输出
                        // "已交由主智能体"，而那句话曾被当成完成结论。
                        return ToolResult(
                            id = UUID.randomUUID().toString(),
                            createdAt = now,
                            toolCallId = toolCall.id,
                            success = false,
                            output = buildString {
                                append("该工具需要用户审批（${decision.summary}），子智能体后台 Lane 不支持暂停审批，本次调用未执行。")
                                append("\n原因：").append(decision.reason)
                                append("\n请不要重试同一调用，也不要声称已完成：把该操作作为待办写进结论，")
                                append("由主智能体在主会话重新发起并等待用户批准。")
                            },
                            approvalDeferred = true,
                        )
                    }
                    checkNotNull(repository) { "审批仓库未初始化" }
                    val request = approvalPolicyEngine.createRequest(sessionId, toolCall, workspace, decision, operationId)
                    repository.create(request)
                    eventBus?.emit(
                        HarnessEvent.ApprovalRequested(
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
            executeTool(toolCall.tool, toolCall.args, toolCall.rawToolName, toolCall.id, sessionId, workspace, progressReporter, operationId, toolMetadata, allowApprovalRequest)
        } catch (interrupted: ScriptCallInterrupted) {
            return interrupted.result.copy(toolCallId = toolCall.id)
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
        // 先对全量输出脱敏，再截断/落盘：落盘引流文件必须与结果正文同一脱敏口径
        val redactedOutput = secretRedactor.redact(
            value = finalOutput,
            secretValues = top.wkbin.taixu.harness.environment.executionOutputSecrets(environments, linuxEnvironmentManager, sessionId, workspace),
            privacyMode = settingsDataStore?.let { prefs ->
                runCatching { prefs.environmentPrivacyMode.first() }.getOrDefault(true)
            } ?: true,
        )
        val imagePayload = toolMetadata.remove("image_payload")
        return ToolResult(
            id = UUID.randomUUID().toString(),
            createdAt = now,
            toolCallId = toolCall.id,
            success = success,
            output = truncateOutput(
                redactedOutput,
                toolCall.rawToolName ?: HarnessApiMapper.apiName(toolCall.tool),
                if (environments != null) environments.environment(sessionId, workspace).artifacts
                else if (workspace.isNotBlank()) top.wkbin.taixu.harness.environment.LocalExecutionArtifacts(fileAccess.withBase(workspace)) else null,
            ),
            metadata = toolMetadata.toMap(),
            imageDataUrl = imagePayload,
        )
    }

    /**
     * 输出超限时带元数据截断（pi 的 truncate 设计）：保留头部，并明确告知模型
     * 完整输出的规模与截断事实。截断之上叠加落盘引流（对齐 opencode Truncate）：
     * 全量输出写入工作区 `.taixu-outputs/` 并在正文附路径，模型可用 read 分页回读，
     * 从「有损截断 + 引导重新取数」升级为「无损引流」。
     */
    private suspend fun truncateOutput(
        output: String,
        toolName: String?,
        fileAccess: top.wkbin.taixu.runtime.environment.ExecutionArtifacts?,
    ): String {
        // 超长单行先折叠（混淆/压缩文件）：否则单行预算退化成保留 60k 字符的整行，
        // 一条 tool result 就可能超出单条消息的传输上限导致连接被重置。落盘仍用原始全量。
        val folded = foldOverlongLines(output)
        if (folded.length <= MAX_OUTPUT_LENGTH) return folded
        val spillPath = fileAccess?.let { ToolOutputSpillStore.spill(it, toolName, output) }
        // 差异化截断：命令/构建/日志保留尾部（报错在末尾），读取/搜索保留头部。
        val retention = ToolOutputRetention.forTool(toolName)
        val kept = when (retention) {
            OutputRetention.TAIL -> keepTailWholeLines(folded, TRUNCATE_KEEP_LENGTH)
            OutputRetention.HEAD -> keepHeadWholeLines(folded, TRUNCATE_KEEP_LENGTH)
        }
        val totalLines = output.count { it == '\n' } + 1
        val keptLines = kept.count { it == '\n' } + 1
        val recoverHint = if (spillPath != null) {
            "完整输出已保存至 $spillPath，可用 read 工具（offset/limit 分页）查看其余部分。"
        } else {
            "需要其余部分请用 grep 过滤关键字、head/tail 取首尾、或 sed -n 'N,Mp' 取指定行段，不要原样重复执行同一命令。"
        }
        return buildString {
            when (retention) {
                OutputRetention.TAIL -> {
                    append("[输出已截断：完整输出共 ")
                    append(totalLines)
                    append(" 行 / ")
                    append(output.length)
                    append(" 字符，以下仅显示末尾 ")
                    append(keptLines)
                    append(" 行（命令报错/断言通常位于末尾）。")
                    append(recoverHint)
                    append("]\n")
                    append(kept)
                }
                OutputRetention.HEAD -> {
                    append(kept)
                    append("\n\n[输出已截断：完整输出共 ")
                    append(totalLines)
                    append(" 行 / ")
                    append(output.length)
                    append(" 字符，以上仅显示前 ")
                    append(keptLines)
                    append(" 行。")
                    append(recoverHint)
                    append("]")
                }
            }
        }
    }

    private suspend fun executeTool(
        tool: HarnessTool,
        rawArgs: JsonObject,
        rawToolName: String?,
        parentToolCallId: String,
        sessionId: String,
        workspace: String,
        progressReporter: (suspend (String) -> Unit)?,
        operationId: String?,
        metadata: MutableMap<String, String>,
        allowApprovalRequest: Boolean,
    ): Pair<Boolean, String> {
        // MCP 工具的参数名由远端 schema 定义，跳过单键解包/扁平键还原与内置别名，
        // 否则名为 input 的单参数或含 __ / . 的合法参数名会被错误改写。
        val args = ToolSchemaValidator.normalizeArgs(
            rawArgs, applyAliases = tool != HarnessTool.MCP, isMcpTool = tool == HarnessTool.MCP,
        )
        return routing.dispatch(
            ToolInvocationContext(
                tool = tool,
                args = args,
                rawToolName = rawToolName,
                parentToolCallId = parentToolCallId,
                sessionId = sessionId,
                workspace = workspace,
                progressReporter = progressReporter,
                operationId = operationId,
                metadata = metadata,
                reenter = { inner ->
                    execute(inner, sessionId, workspace, allowApprovalRequest = allowApprovalRequest, operationId = operationId)
                },
            ),
        )
    }

    companion object {
        /** virtual_screen_* 的默认会话 ID（PhoneAgentServices 与 HostCapabilityToolBackend 共用）。 */
        const val VIRTUAL_SCREEN_DEFAULT_SESSION = "default"
        const val MAX_OUTPUT_LENGTH = 64 * 1024
        const val TRUNCATE_KEEP_LENGTH = 60 * 1024

        /**
         * host 侧工具输出的字符硬上限（与 runtime EmbeddedAdbManager 的 ADB 路径保持同值）。
         * 200K 字符 ≈ 0.4-0.8MB 堆：足够容纳正常 logcat/dumpsys 片段，又能挡住数 MB 的
         * 节点树/全量 dump 把 Java 堆（256MB，未开 largeHeap 前）拖爆。
         */
        const val MAX_HOST_OUTPUT_CHARS = 200_000
    }
}

/**
 * host 侧输出截断（[ToolExecutor.MAX_HOST_OUTPUT_CHARS]）：超限时保留前缀并附重取指引。
 * [HostActionNodeExecutor] 等其他宿主输出出口共用，保证口径一致。
 */
internal fun capHostOutput(output: String): String =
    if (output.length <= ToolExecutor.MAX_HOST_OUTPUT_CHARS) {
        output
    } else {
        output.take(ToolExecutor.MAX_HOST_OUTPUT_CHARS) +
            "\n[host 输出超限已截断：原文 ${output.length} 字符，仅保留前 ${ToolExecutor.MAX_HOST_OUTPUT_CHARS}。" +
            "需要完整内容请缩小范围重取：logcat 用更精确的 tag/keyword 与更少 tail_lines，" +
            "dumpsys 指定子服务（如 dumpsys activity），避免全量输出]"
    }
