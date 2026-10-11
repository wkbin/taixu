package top.wkbin.taixu.harness

import kotlinx.coroutines.CancellationException
import top.wkbin.taixu.harness.checkpoint.CheckpointStore
import top.wkbin.taixu.runtime.environment.ExecutionArtifacts
import top.wkbin.taixu.runtime.environment.ExecutionFiles

/** Bounded parent summaries; the supplied file and artifact capabilities must share a world. */
internal suspend fun paginateSubagentSummary(
    outcomes: List<SubagentOrchestrator.SubagentExecutionOutcome>,
    workspace: String,
    fileAccess: ExecutionFiles,
    artifacts: ExecutionArtifacts? = null,
): String {
    val full = renderSummaryMarkdown(outcomes)
    if (full.length <= SUMMARY_INLINE_BUDGET || workspace.isBlank()) return full

    val overflowTasks = outcomes.filter { it.summary.length > PER_TASK_INLINE_BUDGET }
    val spillDir = ".taixu-subagent"
    val spilled = mutableMapOf<String, String>() // laneName -> 相对路径
    overflowTasks.forEach { outcome ->
        val fileName = outcome.subSessionId
            .filter { it.isLetterOrDigit() || it == '-' || it == ':' }
            .replace(':', '-')
            .takeLast(80) + ".md"
        val relativePath = "$spillDir/$fileName"
        // Saving is optional, but cancellation must still terminate the batch.
        val saved = try {
            if (artifacts != null) ToolOutputSpillStore.spill(artifacts, "subagent", outcome.summary)
            else if (outcome.summary.toByteArray().size <= CheckpointStore.SNAPSHOT_MAX_BYTES &&
                fileAccess.write(relativePath, outcome.summary).isSuccess) relativePath else null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (saved != null) spilled[outcome.subSessionId] = saved

    }

    return buildString {
        append(subagentBatchHeader(outcomes))
        append("（本批输出总量超出注入预算，超长子任务已截断；完整结果可用 read 工具按 offset/limit 分页读取）\n\n")
        outcomes.forEachIndexed { index, outcome ->
            append(subagentOutcomeHeader(outcome, index, includeModelBadge = false))
            append("- **子任务输出**：\n")
            val spillPath = spilled[outcome.subSessionId]
            if (spillPath != null) {
                append(outcome.summary.take(PER_TASK_INLINE_BUDGET))
                append("\n\n…（截断，共 ${outcome.summary.length} 字符。完整结果：read 路径 `$spillPath`）\n\n")
            } else {
                append(outcome.summary.take(PER_TASK_INLINE_BUDGET).trim())
                if (outcome.summary.length > PER_TASK_INLINE_BUDGET) append("\n\n…（截断；完整结果未能保存，请缩小任务范围或输出预算）")
                append("\n\n")
            }
        }
    }
}
