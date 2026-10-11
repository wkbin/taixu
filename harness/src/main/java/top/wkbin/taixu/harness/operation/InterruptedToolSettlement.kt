package top.wkbin.taixu.harness.operation

import java.util.UUID
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.database.HarnessOperationEntity
import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.effects.ToolRecoveryNotice
import top.wkbin.taixu.harness.session.SessionTreeStore

internal data class InterruptedToolSettlement(val result: ToolResult, val round: Int, val toolName: String?)

/** Read durable evidence before detaching an interrupted operation. Never executes a tool. */
internal suspend fun interruptedToolSettlement(
    repository: HarnessRuntimeRepository,
    json: Json,
    operation: HarnessOperationEntity,
): InterruptedToolSettlement? {
    if (operation.phase != OperationPhase.TOOL_INTENT.id) return null
    val snapshot = json.decodeFromString(OperationSnapshot.serializer(), operation.stateJson)
    val callId = operation.pendingEffectId ?: snapshot.effectId ?: error("Pending tool has no call ID")
    val lane = repository.findLane(operation.sessionId, operation.laneName) ?: error("Pending tool lane is missing")
    check(lane.currentOperationId == operation.id) { "Interrupted lane operation changed" }
    val messages = repository.branchTail(operation.sessionId, lane.leafId, SessionTreeStore.MAX_LIVE_ENTRIES)
        .filter { it.entryType == "message" }
        .map { json.decodeFromString(HarnessMessage.serializer(), it.payloadJson) }
    // A truncated or damaged history is not evidence that the effect never happened.
    val call = messages.filterIsInstance<ToolCall>().lastOrNull { it.id == callId }
        ?: error("Pending tool call is missing from durable history")
    val settled = messages.filterIsInstance<ToolResult>().lastOrNull { it.toolCallId == callId }
    if (settled != null && !settled.awaitingApproval) return null
    val notice = if (operation.replayPolicy == ReplayPolicy.SAFE.id) {
        ToolRecoveryNotice.cancelled(userStopped = false)
    } else ToolRecoveryNotice.unknown()
    return InterruptedToolSettlement(
        notice.result(UUID.randomUUID().toString(), System.currentTimeMillis(), callId),
        snapshot.round, call.rawToolName,
    )
}
