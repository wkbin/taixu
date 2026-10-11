package top.wkbin.taixu.harness.subagent

import android.content.Context
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.harness.ApiMessage
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.ContextWindowPolicy
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ProviderClient
import top.wkbin.taixu.harness.ToolCallMode
import top.wkbin.taixu.harness.ToolExecutor
import top.wkbin.taixu.harness.ToolRoundDispatcher
import top.wkbin.taixu.harness.TextToolCallCodec
import top.wkbin.taixu.harness.operation.OperationCoordinator
import top.wkbin.taixu.harness.prompt.PromptAssetLoader
import top.wkbin.taixu.harness.session.SessionTreeStore
import top.wkbin.taixu.harness.R
import kotlinx.coroutines.CancellationException
import top.wkbin.taixu.harness.ApiToolCallSpec
import top.wkbin.taixu.harness.ChatResult
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.session.ApiMessageProjector

data class SubagentLaneResult(
    val success: Boolean,
    val summary: String,
    val toolCallCount: Int,
    /** 终止原因。父层据此区分"真完成"与"收场但没做完"，不再只看 [success]。 */
    val termination: SubagentTermination = SubagentTermination.CONCLUDED,
    /** 需要审批因而未执行的调用，必须由主智能体在主会话重发。 */
    val pendingApprovals: List<SubagentApprovalHandoff> = emptyList(),
    /** 被写租约拦截的写入目标。 */
    val blockedWrites: List<String> = emptyList(),
)

/**
 * Headless lane interpreter used by subagents; it shares tree history but owns its operation.
 *
 * [ToolExecutor] 通过函数提供器延迟获取，打断依赖环：
 * ToolExecutor → SubagentOrchestrator → SubagentLaneRunner → ToolExecutor。
 * 工具只在 [run] 执行期才实际取用，构造期延迟解析是安全的。
 */
class SubagentLaneRunner(
    private val context: Context,
    private val providerClient: ProviderClient,
    private val toolExecutor: () -> ToolExecutor,
    private val treeStore: SessionTreeStore,
    private val operations: OperationCoordinator,
    private val settingsDataStore: AgentPreferences,
    private val promptAssets: PromptAssetLoader,
    private val json: Json,
    private val toolRoundDispatcher: ToolRoundDispatcher,
    private val environments: top.wkbin.taixu.harness.environment.SessionExecutionEnvironments? = null,
) {
    suspend fun run(
        sessionId: String,
        laneName: String,
        prompt: String,
        workspace: String,
        modelId: String? = null,
        modelVariant: String? = null,
        modelConfig: ModelConfig? = null,
        /**
         * 子任务声明的写租约。非 null 时启用执行期写闸门：空列表 = 只读任务，
         * write/edit/download 在本 Lane 内被强制拦截。
         *
         * null（默认）= 不启用闸门，保留给不经 [SubagentOrchestrator] 编排的调用方
         * （双智能体执行者 Lane、workflow 推理节点）——它们没有写租约契约，
         * 闸门会把所有写入误拦成"未声明 write_paths 的只读任务"。
         */
        writePaths: List<String>? = null,
    ): SubagentLaneResult {
        val user = UserMessage(UUID.randomUUID().toString(), now(), prompt)
        val operationId = operations.acceptRun(sessionId, user, laneName)
        val finalizer = SubagentLaneFinalizer(operations) { environments?.closeOwner(sessionId, operationId) }
        val toolRoundRunner = SubagentToolRoundRunner(operations, json, toolRoundDispatcher) { call, session, cwd, operation ->
            toolExecutor().execute(call, session, cwd, allowApprovalRequest = false, operationId = operation, resourceOwner = operation)
        }
        var finalText = ""
        // 需要审批而被跳过的调用、被写租约拦截的写入：两者都会让"看起来收场了"实际没做完，
        // 因此必须跨轮累计，并参与最终的完成判定。
        val deferredApprovals = toolRoundRunner.pendingApprovals
        val blockedWrites = toolRoundRunner.blockedWrites
        // 写过但最后一次尝试仍失败的目标（同目标后续写成功会移除）。
        // 这是"声称已落盘、其实没写成"的结构化证据；靠在结论文本里匹配"写入失败"会误伤
        // 只读审计任务（"检查为什么 X 服务写入失败"的合法结论里也有这些词）。
        val failedWrites = toolRoundRunner.failedWrites
        return try {
            val configuredModel = modelConfig ?: providerClient.resolveConfigured(modelId, modelVariant)
            // 子智能体是定向小任务，不能沿用主会话数百轮上限，否则只读审计会漫游到超时。
            val toolRounds = runCatching { settingsDataStore.maxToolRounds.first() }
                .getOrDefault(DEFAULT_MAX_ROUNDS)
                .coerceIn(MIN_MAX_ROUNDS, MAX_MAX_ROUNDS)
            // 收束轮额外追加：最后一轮不向 Provider 暴露工具，若把它算进 toolRounds，
            // 用户配置 N 轮实际只有 N-1 轮能执行工具。
            val totalRounds = toolRounds + 1
            val historyBudgetTokens = laneHistoryBudget(configuredModel)
            repeat(totalRounds) { round ->
                val forceFinalAnswer = shouldForceSubagentFinalAnswer(round, totalRounds)
                val model = if (forceFinalAnswer) configuredModel.copy(pureChatMode = true) else configuredModel
                val responseId = UUID.randomUUID().toString()
                operations.providerIntent(operationId, responseId, round, 1, NETWORK_ATTEMPTS)
                val text = StringBuilder()
                val result = chatWithRetry(
                    model,
                    providerMessages(sessionId, laneName, forceFinalAnswer, configuredModel, historyBudgetTokens),
                    text,
                )
                val rawAssistantText = text.toString().ifBlank { result.content.orEmpty() }
                val textNormalization = TextToolCallCodec.normalize(json, rawAssistantText)
                val roundCalls = if (forceFinalAnswer) {
                    result.toolCalls
                } else {
                    TextToolCallCodec.resolveCalls(result.toolCalls, textNormalization)
                }
                val assistantText = if (textNormalization.hasMarkers) {
                    textNormalization.displayText
                } else {
                    rawAssistantText
                }
                val usageEntity = result.usage.takeIf { it.hasData }?.let {
                    operations.usageEntity(
                        sessionId = sessionId,
                        operationId = operationId,
                        entryId = responseId.takeIf { assistantText.isNotBlank() || result.responsesTurn != null },
                        provider = model.provider,
                        modelId = model.model,
                        usage = it,
                    )
                }
                if (assistantText.isNotBlank() || result.responsesTurn != null) {
                    val assistant = AssistantText(
                        id = responseId,
                        createdAt = now(),
                        text = assistantText,
                        reasoning = result.reasoningContent,
                        responsesTurn = result.responsesTurn,
                        modelId = model.model,
                        providerId = model.provider,
                        promptTokens = result.usage.inputTokens.takeIf { it > 0 }?.toInt(),
                        completionTokens = result.usage.outputTokens.takeIf { it > 0 }?.toInt(),
                        cachedTokens = result.usage.cacheReadTokens.takeIf { it > 0 }?.toInt(),
                    )
                    operations.providerSettled(operationId, assistant, usage = usageEntity, round = round)
                    finalText = assistantText
                } else {
                    operations.providerSettled(operationId, null, usage = usageEntity, round = round)
                }
                if (roundCalls.isEmpty() && !forceFinalAnswer && textNormalization.hasUnresolvedMarkers) {
                    finalizer.finish(sessionId, "failed", details = "无法解析文本工具调用", laneName = laneName)
                    return SubagentLaneResult(
                        success = false,
                        summary = "模型返回了无法解析的文本工具调用，未将其误判为任务完成",
                        toolCallCount = toolRoundRunner.toolCallCount,
                        termination = SubagentTermination.UNPARSEABLE_TOOL_CALL,
                        pendingApprovals = deferredApprovals.toList(),
                        blockedWrites = blockedWrites.toList(),
                    )
                }
                // 收场判定：最后一轮，或普通轮次里模型不再调用工具。
                // 两条路径都必须走同一套判定——"没有工具调用"本身不是完成的证据：
                // 空输出、仍在输出工具协议、自述未完成、有待审批或被拦截的写入，都不算完成。
                if (forceFinalAnswer || roundCalls.isEmpty()) {
                    val verdict = judgeSubagentConclusion(
                        roundText = assistantText,
                        structuredCalls = result.toolCalls,
                        textNormalization = textNormalization,
                        deferredApprovals = deferredApprovals,
                        blockedWrites = blockedWrites,
                        unresolvedWriteFailures = failedWrites.keys.toList(),
                    )
                    finalizer.finish(
                        sessionId,
                        if (verdict.accepted) "completed" else "failed",
                        responseId.takeIf { assistantText.isNotBlank() },
                        details = verdict.reason.takeIf { it.isNotBlank() },
                        laneName = laneName,
                    )
                    return SubagentLaneResult(
                        success = verdict.accepted,
                        summary = laneSummary(verdict, assistantText, deferredApprovals, blockedWrites, failedWrites),
                        toolCallCount = toolRoundRunner.toolCallCount,
                        termination = verdict.termination,
                        pendingApprovals = deferredApprovals.toList(),
                        blockedWrites = blockedWrites.toList(),
                    )
                }

                toolRoundRunner.execute(
                    specs = roundCalls, sessionId = sessionId, workspace = workspace,
                    model = model, operationId = operationId, round = round,
                    reasoning = result.reasoningContent, writePaths = writePaths,
                )
            }
            finalizer.finish(sessionId, "failed", details = "max rounds", laneName = laneName)
            SubagentLaneResult(
                success = false,
                summary = buildString {
                    append("⚠️ 子智能体用尽 $toolRounds 轮工具预算仍未收束，未产出最终结论。")
                    if (finalText.isNotBlank()) {
                        append("\n\n最后阶段输出：\n")
                        append(finalText)
                    }
                },
                toolCallCount = toolRoundRunner.toolCallCount,
                termination = SubagentTermination.MAX_ROUNDS,
                pendingApprovals = deferredApprovals.toList(),
                blockedWrites = blockedWrites.toList(),
            )
        } catch (cancellation: CancellationException) {
            finalizer.interrupted(sessionId, laneName, operationId, "aborted", "已取消")
            throw cancellation
        } catch (throwable: Throwable) {
            val repairOutput = finalizer.interrupted(sessionId, laneName, operationId, "failed", throwable.message)
            SubagentLaneResult(
                success = false,
                summary = listOfNotNull(throwable.message ?: "子智能体执行失败", repairOutput).joinToString("\n\n"),
                toolCallCount = toolRoundRunner.toolCallCount,
                termination = SubagentTermination.FAILED,
                pendingApprovals = deferredApprovals.toList(),
                blockedWrites = blockedWrites.toList(),
            )
        }
    }

    /**
     * Lane 历史的 token 预算。子循环没有主循环那套压缩管线，只做"够用就不动、超了才收紧"，
     * 并给系统提示词、工具 schema 与本轮输出留出余量。
     */
    private suspend fun laneHistoryBudget(model: ModelConfig): Int {
        val budget = ContextWindowPolicy.clampedBudget(
            model.contextTokens,
            runCatching { settingsDataStore.contextBudgetTokens.first() }.getOrDefault(DEFAULT_CONTEXT_BUDGET_TOKENS),
            modelId = model.model,
            providerId = model.provider,
        )
        return (budget * LANE_HISTORY_BUDGET_FRACTION).toInt().coerceAtLeast(MIN_LANE_HISTORY_TOKENS)
    }

    private suspend fun providerMessages(
        sessionId: String,
        laneName: String,
        forceFinalAnswer: Boolean,
        model: ModelConfig,
        historyBudgetTokens: Int,
    ): List<ApiMessage> {
        val toolCallMode = model.effectiveToolCallMode
        return isolatedProviderMessages(
            messages = treeStore.load(sessionId, laneName),
            systemPrompt = laneSystemPrompt(toolCallMode),
            forceFinalAnswer = forceFinalAnswer,
            toolCallMode = toolCallMode,
            historyBudgetTokens = historyBudgetTokens,
        )
    }

    /**
     * JSON 文本模式下工具调用协议不在原生字段里：ProviderClient 只会把工具的 JSON 定义追加到
     * system 消息，输出格式规则来自主循环的 SystemPromptBuilder。子 Lane 不走那条链路，
     * 因此必须自己带上标记格式说明，否则模型拿到工具清单却不知道怎么发起调用。
     */
    private fun laneSystemPrompt(toolCallMode: ToolCallMode): String = buildString {
        append(context.getString(R.string.harness_prompt_subagent_lane_system))
        if (toolCallMode == ToolCallMode.JSON_TEXT) {
            runCatching { promptAssets.render("prompts/tool_call_json.md") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { append("\n\n").append(it) }
        }
    }

    private suspend fun chatWithRetry(
        model: ModelConfig,
        messages: List<ApiMessage>,
        text: StringBuilder,
    ): ChatResult {
        var lastFailure: IOException? = null
        repeat(NETWORK_ATTEMPTS) { attempt ->
            try {
                return providerClient.chatStream(model, messages, onReasoning = {}) { text.append(it) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: IOException) {
                lastFailure = failure
                text.clear()
                if (attempt + 1 < NETWORK_ATTEMPTS) delay(NETWORK_RETRY_DELAY_MS)
            }
        }
        throw requireNotNull(lastFailure)
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val DEFAULT_MAX_ROUNDS = 20
        private const val MIN_MAX_ROUNDS = 5

        /**
         * 工具轮数上限。原为 30，复杂编码类子任务会被强行截断在中途；
         * 真正的兜底是编排层超时，轮数上限只用来拦住"漫游式只读遍历"。
         */
        private const val MAX_MAX_ROUNDS = 60
        private const val NETWORK_ATTEMPTS = 2
        private const val NETWORK_RETRY_DELAY_MS = 1_000L
        private const val DEFAULT_CONTEXT_BUDGET_TOKENS = 128_000
        private const val LANE_HISTORY_BUDGET_FRACTION = 0.55
        private const val MIN_LANE_HISTORY_TOKENS = 4_000
    }
}

/**
 * 未被接受为完成时的汇总正文：先说清为什么没完成，再交出可操作的交接信息，
 * 最后附上子智能体的原始输出。父智能体据此能直接接手，而不是只看到一句"失败"。
 */
internal fun laneSummary(
    verdict: SubagentConclusionVerdict,
    roundText: String,
    deferredApprovals: List<SubagentApprovalHandoff>,
    blockedWrites: List<String>,
    failedWrites: Map<String, String> = emptyMap(),
): String {
    if (verdict.accepted) return roundText
    return buildString {
        appendLine("⚠️ 子任务未确认完成：${verdict.reason}")
        if (deferredApprovals.isNotEmpty()) {
            appendLine()
            appendLine("**需要主智能体在主会话重新发起并完成审批的调用**（后台 Lane 无法暂停等待审批）：")
            deferredApprovals.forEach { handoff ->
                appendLine("- `${handoff.toolName}` 参数：${handoff.argumentsJson}")
            }
        }
        if (blockedWrites.isNotEmpty()) {
            appendLine()
            appendLine("**被写租约拦截、实际未落盘的写入**：${blockedWrites.joinToString("、")}")
            appendLine("重新派发时请为该子任务声明 write_paths，或由主智能体自行写入。")
        }
        if (failedWrites.isNotEmpty()) {
            appendLine()
            appendLine("**未确认成功的写入目标**（可能已部分写入，请按各项说明核验）：")
            failedWrites.forEach { (target, reason) -> appendLine("- $target → $reason") }
        }
        if (roundText.isNotBlank()) {
            appendLine()
            appendLine("子智能体最后输出：")
            append(roundText)
        }
    }.trim()
}

/**
 * Lane 请求消息组装。
 *
 * 与主循环的 ApiContextAssembler 对齐两件事：
 * - JSON_TEXT 模式必须把 tool_call / tool_result 转成文本形态（这类接口不认识 tool 角色，
 *   原生形态会让"首轮正常、执行一次工具后下一轮 400"）；
 * - 历史按 token 预算收紧，避免几次大文件读取就把子循环上下文撑满。
 */
internal fun isolatedProviderMessages(
    messages: List<HarnessMessage>,
    systemPrompt: String,
    forceFinalAnswer: Boolean,
    toolCallMode: ToolCallMode = ToolCallMode.NATIVE,
    historyBudgetTokens: Int = 0,
): List<ApiMessage> = buildList {
    val finalInstruction = if (forceFinalAnswer) {
        "\n这是最后一轮。禁止继续调用工具，请根据已有结果直接输出结论；信息不完整时明确说明限制。"
    } else {
        ""
    }
    add(ApiMessage(role = "system", content = systemPrompt + finalInstruction))
    val taskStart = messages.indexOfLast { it is UserMessage }
        .takeIf { it >= 0 } ?: messages.size
    val task = budgetedLaneMessages(messages.drop(taskStart), historyBudgetTokens)
    addAll(ApiMessageProjector.project(task, toolCallMode, visionEnabled = true))
}

internal fun isDirectSubagentConclusion(
    assistantText: String,
    structuredCalls: List<ApiToolCallSpec>,
    textNormalization: TextToolCallCodec.Normalization,
): Boolean = assistantText.isNotBlank() && structuredCalls.isEmpty() && !textNormalization.hasMarkers

internal fun shouldForceSubagentFinalAnswer(round: Int, maxRounds: Int): Boolean =
    round >= maxRounds.coerceAtLeast(1) - 1
