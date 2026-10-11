package top.wkbin.taixu.harness.effects

import top.wkbin.taixu.harness.ToolResult

/** A missing result is not evidence that a side effect failed or never happened. */
data class ToolRecoveryNotice(val code: String, val output: String) {
    fun result(id: String, createdAt: Long, toolCallId: String) = ToolResult(
        id = id,
        createdAt = createdAt,
        toolCallId = toolCallId,
        success = false,
        output = output,
        errorCode = code,
    )

    companion object {
        const val OUTCOME_UNKNOWN = "TOOL_OUTCOME_UNKNOWN"
        const val INTERRUPTED = "TOOL_INTERRUPTED"

        fun unknown(userStopped: Boolean = false) = ToolRecoveryNotice(
            OUTCOME_UNKNOWN,
            "[$OUTCOME_UNKNOWN] " +
                (if (userStopped) "用户停止了本次执行。" else "本次执行中断，工具结果未完整落盘。") +
                "工具执行结果未知，可能已产生全部或部分副作用；这不代表执行失败或尚未执行。" +
                "该工具不可自动重放，未再次执行。请先通过只读查询核验文件、进程或外部系统的实际状态；" +
                "无法核验时先向用户说明不确定性并取得确认，禁止直接重新发起有副作用的操作。",
        )

        fun cancelled(userStopped: Boolean = true) = ToolRecoveryNotice(
            INTERRUPTED,
            "[$INTERRUPTED] " + (if (userStopped) "用户停止了本次执行。" else "本次执行中断。") +
                "只读工具被中断，未自动重放。",
        )
    }
}
