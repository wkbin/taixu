package top.wkbin.taixu.harness.subagent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.normalizeWritePath
import kotlinx.serialization.json.JsonObject

/**
 * 子智能体完成 claim 的 host 裁定（对齐 Reasonix 的 complete_subtask 语义）。
 *
 * 模型提交的 status 是**主张**，不是事实。交给父智能体之前，host 用自己落库的
 * lane transcript receipts 逐条核验 acceptance_criteria：
 * - `verification` 必须对应 host 真实记录过的成功命令执行；
 * - `files` 必须对应 host 观察到的成功结构化写入（write/edit/download）；
 * - `manual` 永远不能自证（只有用户能验证）。
 * 凭据不背书的条目降级为 unsatisfied；持有 unsatisfied 条目的 complete 主张整体降级为 partial。
 * **host 永远不升格状态**：partial/failed 只原样保留。
 *
 * 协议是文本协议（与 PlannerProtocolParser 同风格）：结论文末的 ```json 块，解析失败
 * 或缺失 = 无 claim，行为与旧版完全一致（fail-open）；解析成功才启用裁定。
 */

@Serializable
internal data class SubagentClaimCriterionDto(
    val type: String = "",
    val claim: String = "",
    val command: String? = null,
    val paths: List<String> = emptyList(),
    @SerialName("acceptance") val acceptance: String? = null,
)

@Serializable
internal data class SubagentClaimDto(
    val status: String = "",
    val summary: String = "",
    @SerialName("acceptance_criteria")
    val acceptanceCriteria: List<SubagentClaimCriterionDto> = emptyList(),
)

/** 解析并规范化后的完成主张。 */
internal data class SubagentClaim(
    val status: String,
    /** claim 块内模型自述的一句话摘要；正文剔除协议块后为空时的回退。 */
    val summaryText: String,
    val criteria: List<SubagentClaimCriterion>,
    /** claim JSON 块在原文中的区间，渲染父汇总时剔除。 */
    val blockRange: IntRange,
)

internal data class SubagentClaimCriterion(
    /** Unknown types are retained so they become an explicit unendorsed criterion. */
    val type: String,
    val claim: String,
    val command: String,
    val paths: List<String>,
)

/** host 从 lane transcript 提取的执行凭据。 */
internal data class SubagentHostReceipts(
    /** 成功执行的 base/process 命令（归一化后）。 */
    val commands: List<String>,
    /** 成功落盘的结构化写目标（归一化后）。 */
    val writtenPaths: List<String>,
)

internal data class SubagentCriterionVerdict(
    val criterion: SubagentClaimCriterion,
    val backed: Boolean,
    val reason: String,
)

internal data class SubagentClaimAdjudication(
    val claimedStatus: String,
    val adjudicatedStatus: String,
    val verdicts: List<SubagentCriterionVerdict>,
    val receipts: SubagentHostReceipts,
) {
    val downgraded: Boolean get() = adjudicatedStatus != claimedStatus
}

private val claimJson = Json { ignoreUnknownKeys = true; isLenient = true }

private val CLAIM_BLOCK = Regex("```json\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

/** 可接受的 status 词表：complete 的近似拼写归一化为 complete。 */
private val STATUS_ALIASES = mapOf(
    "complete" to "complete",
    "completed" to "complete",
    "done" to "complete",
    "partial" to "partial",
    "failed" to "failed",
)

/**
 * 从结论文本解析完成 claim；缺失/非法返回 null（调用方保持旧行为）。
 * 取**最后一个**合法块——多轮修正后以最终提交为准。
 */
internal fun parseSubagentClaim(finalText: String): SubagentClaim? {
    val matches = CLAIM_BLOCK.findAll(finalText).toList()
    for (match in matches.asReversed()) {
        val dto = runCatching { claimJson.decodeFromString<SubagentClaimDto>(match.groupValues[1]) }.getOrNull()
            ?: continue
        if (dto.status.isBlank()) continue
        val status = STATUS_ALIASES[dto.status.trim().lowercase()] ?: continue
        val criteria = dto.acceptanceCriteria.map { criterion ->
            // Keep unknown criterion types: silently dropping them would turn an
            // unendorsed claim into an apparently backed complete result.
            val type = criterion.type.trim().lowercase().ifBlank { "unknown" }
            SubagentClaimCriterion(
                type = type,
                claim = (criterion.claim.ifBlank { criterion.acceptance.orEmpty() }).trim(),
                command = criterion.command?.trim().orEmpty(),
                paths = criterion.paths.map { it.trim() }.filter { it.isNotBlank() },
            )
        }
        return SubagentClaim(
            status = status,
            summaryText = dto.summary.trim(),
            criteria = criteria,
            blockRange = match.range,
        )
    }
    return null
}

/** 从 lane transcript 提取 host 执行凭据：只统计**成功**的调用。 */
internal fun extractSubagentReceipts(transcript: List<HarnessMessage>): SubagentHostReceipts {
    val calls = transcript.filterIsInstance<ToolCall>().associateBy { it.id }
    val successByCallId = transcript.filterIsInstance<ToolResult>()
        .filter { it.success }
        .mapTo(mutableSetOf()) { it.toolCallId }
    val commands = mutableListOf<String>()
    val writtenPaths = mutableListOf<String>()
    for ((id, call) in calls) {
        if (call.id !in successByCallId) continue
        // MCP 参数由远端 schema 定义，只读取能提供对应凭据的内置工具。
        if (call.tool in setOf(HarnessTool.BASE, HarnessTool.PROCESS)) {
            call.args.stringArg("command")?.let { commands += normalizeCommand(it) }
        }
        if (call.tool in LANE_WRITE_TOOLS) {
            val path = call.args.stringArg("path") ?: call.args.stringArg("destination")
            path?.let { writtenPaths += normalizeWritePath(it) }
        }
    }
    return SubagentHostReceipts(
        commands = commands.distinct(),
        writtenPaths = writtenPaths.distinct(),
    )
}

/**
 * 核验 claim。规则：verification 凭成功命令、files 凭成功结构化写入、manual 永不背书；
 * 任一 unsatisfied 的 complete 主张降级为 partial；host 永不升格。
 */
internal fun adjudicateSubagentClaim(
    claim: SubagentClaim,
    receipts: SubagentHostReceipts,
): SubagentClaimAdjudication {
    val verdicts = claim.criteria.map { criterion ->
        when (criterion.type) {
            "verification" -> {
                val claimed = normalizeCommand(criterion.command)
                when {
                    claimed.isBlank() -> SubagentCriterionVerdict(
                        criterion, false, "未提供可核验的命令文本，host 无法凭据背书",
                    )
                    // 精确相等任意长度可背书；子串匹配要求足够长（"test" 这类短串会误匹配 echo test）。
                    receipts.commands.any { it == claimed || (claimed.length >= 8 && it.contains(claimed)) } ->
                        SubagentCriterionVerdict(criterion, true, "host 记录到匹配命令的成功执行")
                    else -> SubagentCriterionVerdict(
                        criterion, false, "host 未记录到该命令的成功执行（成功命令 ${receipts.commands.size} 条）",
                    )
                }
            }
            "files" -> {
                if (criterion.paths.isEmpty()) {
                    SubagentCriterionVerdict(criterion, false, "未提供可核验的路径")
                } else {
                    val unbacked = criterion.paths.filter { path ->
                        normalizeWritePath(path) !in receipts.writtenPaths
                    }
                    if (unbacked.isEmpty()) {
                        SubagentCriterionVerdict(criterion, true, "host 观察到全部路径的成功写入")
                    } else {
                        SubagentCriterionVerdict(
                            criterion, false,
                            "host 未观察到以下路径的成功写入：${unbacked.joinToString("、")}" +
                                if (receipts.writtenPaths.isEmpty()) "（本 Lane 没有任何成功写入记录）" else "",
                        )
                    }
                }
            }
            "manual" -> SubagentCriterionVerdict(
                criterion, false, "manual 条目无法由 host 凭据背书，只能由用户验证",
            )
            else -> SubagentCriterionVerdict(
                criterion, false, "未知验收标准类型「${criterion.type}」，host 不予背书",
            )
        }
    }
    // A complete claim with no criteria is not a host-backed completion: there is
    // nothing the host can independently endorse. Unknown criteria are retained
    // above and arrive here as explicitly unbacked rather than disappearing.
    val criteriaUnbacked = claim.criteria.isEmpty() || verdicts.any { !it.backed }
    val adjudicated = when {
        claim.status == "complete" && criteriaUnbacked -> "partial"
        else -> claim.status
    }
    return SubagentClaimAdjudication(
        claimedStatus = claim.status,
        adjudicatedStatus = adjudicated,
        verdicts = verdicts,
        receipts = receipts,
    )
}

/** 剔除 claim JSON 块后的结论文本（父汇总与落盘文件展示用，原始 JSON 不进父上下文）。 */
internal fun stripSubagentClaimBlock(finalText: String, claim: SubagentClaim): String =
    finalText.removeRange(claim.blockRange).trim()

/** 父汇总中的裁定段：状态、逐条核验、host 凭据摘要。 */
internal fun renderSubagentClaimAdjudication(adjudication: SubagentClaimAdjudication): String = buildString {
    append("- **完成主张核验**：自报 ${statusLabel(adjudication.claimedStatus)} → host 裁定 ")
    append("**${statusLabel(adjudication.adjudicatedStatus)}**")
    if (adjudication.downgraded) append("（凭据不背书，已降级）")
    append("\n")
    adjudication.verdicts.forEach { verdict ->
        val icon = if (verdict.backed) "✓" else "✗"
        append("  - $icon [${verdict.criterion.type}] ${verdict.criterion.claim.ifBlank { "（未描述）" }}")
        if (!verdict.backed) append(" — ${verdict.reason}")
        append("\n")
    }
}

internal fun statusLabel(status: String): String = when (status) {
    "complete" -> "complete"
    "partial" -> "partial"
    "failed" -> "failed"
    else -> status
}

private fun normalizeCommand(command: String): String =
    command.replace(Regex("\\s+"), " ").trim().removePrefix("./").trim()

// 凭据提取是历史记录的容错读取；结构化值和 JSON null 均不构成凭据。
private fun JsonObject.stringArg(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
