package top.wkbin.taixu.harness.subagent

import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.effects.ToolRecoveryNotice
import top.wkbin.taixu.harness.effects.ToolReplayPolicy
import top.wkbin.taixu.harness.operation.ReplayPolicy

/** Keep uncertainty visible even when a later unrelated failure becomes the digest's last step. */
internal fun subagentOutcomeUncertainty(transcript: List<HarnessMessage>): String {
    val latestResults = transcript.filterIsInstance<ToolResult>().associateBy { it.toolCallId }
    val uncertain = transcript.filterIsInstance<ToolCall>().any { call ->
        val result = latestResults[call.id]
        result?.errorCode == ToolRecoveryNotice.OUTCOME_UNKNOWN ||
            (result == null && ToolReplayPolicy.forTool(call.tool, call.rawToolName) != ReplayPolicy.SAFE)
    }
    return if (uncertain) "\n\n${ToolRecoveryNotice.unknown().output}" else ""
}
