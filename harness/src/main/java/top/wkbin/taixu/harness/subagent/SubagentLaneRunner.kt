package top.wkbin.taixu.harness.subagent

import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import top.wkbin.taixu.harness.ApiMessage
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.HarnessApiMapper
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.ProviderClient
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolExecutor
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.effects.ToolReplayPolicy
import top.wkbin.taixu.harness.operation.OperationCoordinator
import top.wkbin.taixu.harness.session.SessionTreeStore
import top.wkbin.taixu.harness.validation.ToolSchemaValidator

data class SubagentLaneResult(
    val success: Boolean,
    val summary: String,
    val toolCallCount: Int,
)

/**
 * Headless lane interpreter used by subagents; it shares tree history but owns its operation.
 *
 * [ToolExecutor] 以 [Provider] 注入以打断 Hilt 依赖环：
 * ToolExecutor → SubagentOrchestrator → SubagentLaneRunner → ToolExecutor。
 * 工具只在 [run] 执行期才实际取用，构造期延迟解析是安全的。
 */
@Singleton
class SubagentLaneRunner @Inject constructor(
    private val providerClient: ProviderClient,
    private val toolExecutor: Provider<ToolExecutor>,
    private val treeStore: SessionTreeStore,
    private val operations: OperationCoordinator,
    private val json: Json,
) {
    suspend fun run(sessionId: String, laneName: String, prompt: String, workspace: String): SubagentLaneResult {
        val user = top.wkbin.taixu.harness.UserMessage(UUID.randomUUID().toString(), now(), prompt)
        val operationId = operations.acceptRun(sessionId, user, laneName)
        var toolCalls = 0
        var finalText = ""
        return try {
            repeat(MAX_ROUNDS) { round ->
                val model = providerClient.resolveConfigured()
                val responseId = UUID.randomUUID().toString()
                operations.providerIntent(operationId, responseId, round, 1, 1)
                val text = StringBuilder()
                val result = providerClient.chatStream(model, providerMessages(sessionId, laneName), onReasoning = {}) {
                    text.append(it)
                }
                val assistantText = text.toString().ifBlank { result.content.orEmpty() }
                val usageEntity = result.usage.takeIf { it.hasData }?.let {
                    operations.usageEntity(
                        sessionId = sessionId,
                        operationId = operationId,
                        entryId = responseId.takeIf { assistantText.isNotBlank() },
                        provider = model.provider,
                        modelId = model.model,
                        usage = it,
                    )
                }
                if (assistantText.isNotBlank()) {
                    val assistant = AssistantText(
                        id = responseId,
                        createdAt = now(),
                        text = assistantText,
                        reasoning = result.reasoningContent,
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
                if (result.toolCalls.isEmpty()) {
                    operations.finish(sessionId, "completed", responseId, laneName = laneName)
                    return SubagentLaneResult(true, finalText.ifBlank { "子智能体已完成（无文本输出）" }, toolCalls)
                }

                for (spec in result.toolCalls) {
                    toolCalls++
                    val rawName = spec.name.trim()
                    val tool = HarnessApiMapper.toolByName(rawName)
                    val args = runCatching { json.parseToJsonElement(spec.argumentsJson) as JsonObject }.getOrElse {
                        val failed = ToolResult(UUID.randomUUID().toString(), now(), spec.id, false, "工具参数不是 JSON 对象：${it.message}")
                        operations.toolSettled(operationId, failed, round)
                        continue
                    }
                    val call = ToolCall(spec.id, now(), tool, args, result.reasoningContent, rawName)
                    val schemaProblems = ToolSchemaValidator.problemsFor(rawName, args, model.dynamicMcpTools)
                    if (schemaProblems.isNotEmpty()) {
                        operations.toolIntent(operationId, call, spec.argumentsJson, ToolReplayPolicy.forTool(tool, rawName), round)
                        val rejected = ToolResult(
                            UUID.randomUUID().toString(), now(), spec.id, false,
                            "工具参数校验未通过：${schemaProblems.joinToString("；")}。请修正参数后重新调用。",
                        )
                        operations.toolSettled(operationId, rejected, round)
                        continue
                    }
                    operations.toolIntent(operationId, call, spec.argumentsJson, ToolReplayPolicy.forTool(tool, rawName), round)
                    val outcome = if (tool == HarnessTool.SUBAGENT) {
                        ToolResult(UUID.randomUUID().toString(), now(), call.id, false, "子智能体 Lane 禁止再次派发子智能体")
                    } else {
                        toolExecutor.get().execute(call, sessionId, workspace, operationId = operationId)
                    }
                    val settled = if (outcome.awaitingApproval) {
                        outcome.copy(success = false, awaitingApproval = false, output = "子智能体工具需要用户审批，已停止该工具调用")
                    } else outcome
                    operations.toolSettled(operationId, settled, round)
                }
            }
            operations.finish(sessionId, "failed", details = "max rounds", laneName = laneName)
            SubagentLaneResult(false, finalText.ifBlank { "达到子智能体最大工具轮数" }, toolCalls)
        } catch (throwable: Throwable) {
            operations.finish(sessionId, "failed", details = throwable.message, laneName = laneName)
            SubagentLaneResult(false, throwable.message ?: "子智能体执行失败", toolCalls)
        }
    }

    private suspend fun providerMessages(sessionId: String, laneName: String): List<ApiMessage> = buildList {
        add(ApiMessage(role = "system", content = "你是隔离 Lane 中运行的子智能体。聚焦指派任务，不得派发更多子智能体。"))
        treeStore.load(sessionId, laneName).forEach { message ->
            if (message !is CapabilityEvent) add(HarnessApiMapper.toApiMessage(message))
        }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val MAX_ROUNDS = 12
    }
}
