package top.wkbin.taixu.harness.subagent

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import top.wkbin.taixu.harness.ApiToolCallSpec
import top.wkbin.taixu.harness.HarnessApiMapper
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolCallIdNormalizer
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.ToolRoundDispatcher
import top.wkbin.taixu.harness.ToolCallContract
import top.wkbin.taixu.harness.core.DurableToolRunner
import top.wkbin.taixu.harness.effects.ToolReplayPolicy
import top.wkbin.taixu.harness.effects.toolExecutionFailureResult
import top.wkbin.taixu.harness.operation.OperationCoordinator
import top.wkbin.taixu.harness.validation.ToolCallLoopDetector
import top.wkbin.taixu.harness.validation.ToolSchemaValidator

/** Per-run lane effects and completion evidence; shares the main runner's commit barriers. */
internal class SubagentToolRoundRunner(
    private val operations: OperationCoordinator,
    private val json: Json,
    private val dispatcher: ToolRoundDispatcher,
    private val executeTool: suspend (ToolCall, String, String, String) -> ToolResult,
) {
    var toolCallCount = 0
        private set
    val pendingApprovals = mutableListOf<SubagentApprovalHandoff>()
    val blockedWrites = mutableListOf<String>()
    val failedWrites = linkedMapOf<String, String>()
    private val loopDetector = ToolCallLoopDetector()

    suspend fun execute(
        specs: List<ApiToolCallSpec>,
        sessionId: String,
        workspace: String,
        model: ModelConfig,
        operationId: String,
        round: Int,
        reasoning: String?,
        writePaths: List<String>?,
    ) {
        for (spec in specs) {
            toolCallCount++
            val callId = ToolCallIdNormalizer.normalize(spec.id)
            val rawName = spec.name.trim()
            val tool = HarnessApiMapper.toolByName(rawName)
            if (!ToolCallContract.isKnownName(rawName)) {
                reject(operationId, round, ToolCall(callId, now(), tool, JsonObject(emptyMap()), reasoning, rawName),
                    spec.argumentsJson, ToolCallContract.unknownGuidance(rawName, model))
                continue
            }
            val args = try {
                ToolCallContract.parseArguments(json, spec.argumentsJson)
            } catch (failure: IllegalArgumentException) {
                reject(
                    operationId, round,
                    ToolCall(callId, now(), tool, JsonObject(emptyMap()), reasoning, rawName),
                    spec.argumentsJson, "工具参数不是 JSON 对象：${failure.message}",
                )
                continue
            }
            val call = ToolCall(callId, now(), tool, args, reasoning, rawName)
            val schemaProblems = ToolSchemaValidator.problemsFor(rawName, args, model.dynamicMcpTools)
            if (schemaProblems.isNotEmpty()) {
                reject(operationId, round, call, spec.argumentsJson,
                    "工具参数校验未通过：${schemaProblems.joinToString("；")}。请修正参数后重新调用。")
                continue
            }
            val loopVerdict = loopDetector.evaluate(rawName, args)
            if (loopVerdict is ToolCallLoopDetector.LoopVerdict.Block) {
                reject(operationId, round, call, spec.argumentsJson, "${loopVerdict.reason}\n\n${loopVerdict.guidance}")
                continue
            }
            // Inspect exactly the argument view used by ToolExecutor, but keep the original call
            // unchanged: normalizing twice can unwrap more levels or rewrite legitimate MCP keys.
            val executionArgs = ToolSchemaValidator.normalizeArgs(
                args, applyAliases = tool != HarnessTool.MCP, isMcpTool = tool == HarnessTool.MCP,
            )
            val writeRejection = writePaths?.let { subagentWriteScopeRejection(tool, executionArgs, it) }
            dispatcher.withToolLock(tool, workspace) { DurableToolRunner.run(
                commitIntent = {
                    operations.toolIntent(operationId, call, spec.argumentsJson, ToolReplayPolicy.forTool(tool, rawName), round)
                    loopDetector.recordIntent(rawName, args)
                },
                execute = {
                    when {
                        tool == HarnessTool.SUBAGENT -> failure(call, "子智能体 Lane 禁止再次派发子智能体")
                        writeRejection != null -> failure(call, writeRejection)
                        else -> executeTool(call, sessionId, workspace, operationId)
                    }
                },
                executionFailure = { throwable -> toolExecutionFailureResult(call,
                    "工具执行异常：${throwable.message?.take(200) ?: throwable::class.simpleName}") },
                commitResult = { outcome ->
                    operations.toolSettled(operationId, outcome, round, toolName = rawName)
                    loopDetector.recordSettled(rawName, args, outcome.success, output = outcome.output)
                    if (writeRejection != null) blockedWrites += subagentWriteTargetLabel(rawName, executionArgs)
                    if (outcome.approvalDeferred) {
                        pendingApprovals += SubagentApprovalHandoff(
                            toolName = rawName,
                            argumentsJson = spec.argumentsJson.take(MAX_HANDOFF_ARGS_CHARS),
                            reason = outcome.output.lineSequence().firstOrNull()?.take(200).orEmpty(),
                        )
                    } else if (writeRejection == null && tool in LANE_WRITE_TOOLS) {
                        val target = subagentWriteTargetLabel(rawName, executionArgs)
                        if (outcome.success) failedWrites.remove(target)
                        else failedWrites[target] = outcome.output.lineSequence().firstOrNull()?.take(200).orEmpty()
                    }
                },
            ) }
        }
    }

    private suspend fun reject(operationId: String, round: Int, call: ToolCall, arguments: String, message: String) {
        operations.toolIntent(operationId, call, arguments, ToolReplayPolicy.forTool(call.tool, call.rawToolName.orEmpty()), round)
        operations.toolSettled(operationId, failure(call, message), round, toolName = call.rawToolName)
    }

    private fun failure(call: ToolCall, message: String) = ToolResult(
        UUID.randomUUID().toString(), now(), call.id, false, message,
    )
    private fun now() = System.currentTimeMillis()

    companion object {
        private const val MAX_HANDOFF_ARGS_CHARS = 2_000
    }
}
