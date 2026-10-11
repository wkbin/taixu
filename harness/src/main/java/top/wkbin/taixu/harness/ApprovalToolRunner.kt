package top.wkbin.taixu.harness

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import top.wkbin.taixu.core.database.AgentApprovalRequestEntity
import top.wkbin.taixu.harness.core.DurableToolRunner
import top.wkbin.taixu.harness.effects.ToolRecoveryNotice
import top.wkbin.taixu.harness.effects.ToolReplayPolicy
import top.wkbin.taixu.harness.operation.OperationCoordinator
import top.wkbin.taixu.harness.operation.ReplayPolicy
import top.wkbin.taixu.harness.projection.LiveMessagePort
import top.wkbin.taixu.harness.session.SessionTreeStore

/** An approved call needs a fresh durable intent, even though its conversation entry already exists. */
internal class ApprovalToolRunner(
    private val dispatcher: ToolRoundDispatcher,
    private val operations: OperationCoordinator,
    private val json: Json,
    private val store: SessionTreeStore,
    private val messages: LiveMessagePort,
    private val execute: suspend (ToolCall, String, String, String) -> ToolResult,
) {
    suspend fun run(request: AgentApprovalRequestEntity): ToolResult {
        val args = json.parseToJsonElement(request.argumentsJson) as? JsonObject
            ?: error("审批参数不是 JSON 对象")
        val tool = HarnessApiMapper.toolByName(request.toolName)
        val call = ToolCall(request.toolCallId, request.createdAt, tool, args, rawToolName = request.toolName)
        val operation = operations.active(request.sessionId) ?: error("审批恢复缺少活动运行")
        return dispatcher.withToolLock(tool, request.workspace) {
            DurableToolRunner.run(
                commitIntent = {
                    operations.toolIntent(operation.id, call, request.argumentsJson,
                        ToolReplayPolicy.forTool(tool, request.toolName), 0, persistMessage = false)
                },
                execute = { execute(call, request.sessionId, request.workspace, operation.id) },
                // An infrastructure exception cannot prove a side effect failed.
                executionFailure = { throw it },
                commitResult = { result ->
                    operations.toolSettled(operation.id, result, 0, toolName = request.toolName)
                    messages.publishPersisted(request.sessionId, result)
                },
            )
        }
    }

    suspend fun repairInterrupted(request: AgentApprovalRequestEntity, userStopped: Boolean) {
        // Durable history is authoritative: publication or approval-status updates may fail after a successful commit.
        val latest = store.loadStrict(request.sessionId).filterIsInstance<ToolResult>()
            .lastOrNull { it.toolCallId == request.toolCallId }
        if (latest != null && !latest.awaitingApproval) return
        val safe = ToolReplayPolicy.forTool(HarnessApiMapper.toolByName(request.toolName), request.toolName) == ReplayPolicy.SAFE
        val notice = if (userStopped && safe) ToolRecoveryNotice.cancelled()
            else ToolRecoveryNotice.unknown(userStopped)
        val result = notice.result(
            UUID.randomUUID().toString(), System.currentTimeMillis(), request.toolCallId,
        )
        val operation = operations.active(request.sessionId)
        if (operation != null) {
            operations.toolSettled(operation.id, result, 0, toolName = request.toolName)
            messages.publishPersisted(request.sessionId, result)
        } else messages.append(request.sessionId, result)
    }
}
