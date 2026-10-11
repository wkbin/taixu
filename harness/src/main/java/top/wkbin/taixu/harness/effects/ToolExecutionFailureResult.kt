package top.wkbin.taixu.harness.effects

import java.util.UUID
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.operation.ReplayPolicy

/** A thrown executor exception cannot prove that a mutation had no effects. */
internal fun toolExecutionFailureResult(call: ToolCall, safeFailureOutput: String): ToolResult {
    val id = UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    return if (ToolReplayPolicy.forTool(call.tool, call.rawToolName) == ReplayPolicy.SAFE) {
        ToolResult(id, now, call.id, false, safeFailureOutput)
    } else ToolRecoveryNotice.unknown().result(id, now, call.id)
}
