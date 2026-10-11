package top.wkbin.taixu.harness.subagent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.wkbin.taixu.harness.ApiToolCallSpec
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.ContextWindowPolicy
import top.wkbin.taixu.harness.HarnessApiMapper
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.TextToolCallCodec
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.isWholeWorkspaceWritePath
import top.wkbin.taixu.harness.normalizeWritePath

/**
 * 子智能体 Lane 的终止原因。
 *
 * 之前 Lane 只返回 `success: Boolean`，父智能体与日志都无法区分"真的做完了"与
 * "没工具调用就收场"，于是未完成的任务被渲染成 ✅。终止原因是这些判定的唯一凭据。
 */
enum class SubagentTermination {
    /** 产出了可交付的纯文本结论，且没有被拦截的待办。 */
    CONCLUDED,

    /** 收场了但没有可信结论（空输出、仍在调工具、或自述未完成）。 */
    INCOMPLETE,

    /** 有工具因需要审批而未执行，必须由主智能体重新发起。 */
    NEEDS_APPROVAL,

    /** 有写入被写租约拦截，产物并未真正落盘。 */
    WRITE_SCOPE_BLOCKED,

    /** 写入尝试过但最终失败，声称的产物并不存在。 */
    WRITE_FAILED,

    /** 文本工具调用无法解析，本轮既没执行工具也没有结论。 */
    UNPARSEABLE_TOOL_CALL,

    /** 用尽轮数预算仍未收束。 */
    MAX_ROUNDS,

    /** Lane 自身抛错。 */
    FAILED,

    /** 编排层超时取消。 */
    TIMEOUT,

    /**
     * 模型自报 complete，但完成 claim 的验收标准未被 host 凭据完全背书，
     * 已按规则降级为 partial（见 SubagentClaim.kt）。host 永不升格，只降级。
     */
    CLAIM_DOWNGRADED,
}

/**
 * 需要审批、因此在后台 Lane 中未执行的工具调用。
 * 结构化上交父智能体，让它能原样重发并在主会话里完成审批，而不是只看到一句失败文字。
 */
data class SubagentApprovalHandoff(
    val toolName: String,
    val argumentsJson: String,
    val reason: String,
)

/** 结论判定结果：是否接受为"已完成"，以及不接受的原因（会进 operation details 与父汇总）。 */
data class SubagentConclusionVerdict(
    val accepted: Boolean,
    val termination: SubagentTermination,
    val reason: String,
)

/**
 * 子智能体自述未完成的措辞。命中即拒绝判定为成功——模型说"尚未完成""交由主智能体"时，
 * 任务事实上没做完，不能因为"这一轮没有工具调用"就渲染成 ✅。
 *
 * 只收录**第一人称交接/未完成**语义。刻意不收录"写入失败""无法写入""需要审批"这类
 * 现象描述词：只读审计任务的合法结论里（"检查为什么 X 服务写入失败"）它们描述的是被审计
 * 对象，按字面判 INCOMPLETE 属于误伤。写入真的失败/被拦截/待审批时，
 * [judgeSubagentConclusion] 已有 [SubagentApprovalHandoff]、blockedWrites 和
 * unresolvedWriteFailures 三路**结构化证据**，比关键词可靠得多。
 */
internal val SUBAGENT_INCOMPLETE_MARKERS: List<String> = listOf(
    "尚未完成",
    "未能完成",
    "无法完成",
    "没有完成",
    "交由主智能体",
    "请主智能体",
    "由主智能体完成",
    "unable to complete",
    "could not complete",
    "was not completed",
)

/**
 * 完成判定的唯一入口。
 *
 * 要判成功必须同时满足：
 * 1. 本轮（不是历史某轮）产出了非空纯文本，且不再携带任何工具协议；
 * 2. 没有因审批被跳过的工具；
 * 3. 没有被写租约拦截的写入；
 * 4. 没有最终仍失败的写入（[unresolvedWriteFailures]：写过但最后一次尝试失败且未被后续成功覆盖）；
 * 5. 结论文本没有自述未完成。
 *
 * 第 4 条是"声称已落盘、其实没写成"的主要防线，且不依赖关键词。
 *
 * [roundText] 必须是当轮文本。沿用上一轮的过程性文字会让"中途停摆"看起来像结论。
 */
internal fun judgeSubagentConclusion(
    roundText: String,
    structuredCalls: List<ApiToolCallSpec>,
    textNormalization: TextToolCallCodec.Normalization,
    deferredApprovals: List<SubagentApprovalHandoff> = emptyList(),
    blockedWrites: List<String> = emptyList(),
    unresolvedWriteFailures: List<String> = emptyList(),
): SubagentConclusionVerdict {
    if (!isDirectSubagentConclusion(roundText, structuredCalls, textNormalization)) {
        return SubagentConclusionVerdict(
            accepted = false,
            termination = SubagentTermination.INCOMPLETE,
            reason = if (roundText.isBlank()) {
                "本轮没有输出任何结论文本，不能据此判定任务完成"
            } else {
                "本轮仍在输出工具调用协议，没有生成可交付的纯文本结论"
            },
        )
    }
    if (deferredApprovals.isNotEmpty()) {
        return SubagentConclusionVerdict(
            accepted = false,
            termination = SubagentTermination.NEEDS_APPROVAL,
            reason = "${deferredApprovals.size} 个需要审批的工具未执行：" +
                deferredApprovals.joinToString("、") { it.toolName },
        )
    }
    if (blockedWrites.isNotEmpty()) {
        return SubagentConclusionVerdict(
            accepted = false,
            termination = SubagentTermination.WRITE_SCOPE_BLOCKED,
            reason = "${blockedWrites.size} 次写入被写租约拦截：${blockedWrites.take(5).joinToString("、")}",
        )
    }
    if (unresolvedWriteFailures.isNotEmpty()) {
        return SubagentConclusionVerdict(
            accepted = false,
            termination = SubagentTermination.WRITE_FAILED,
            reason = "${unresolvedWriteFailures.size} 个写入目标最终仍未成功落盘：" +
                unresolvedWriteFailures.take(5).joinToString("、"),
        )
    }
    val marker = SUBAGENT_INCOMPLETE_MARKERS.firstOrNull { roundText.contains(it, ignoreCase = true) }
    if (marker != null) {
        return SubagentConclusionVerdict(
            accepted = false,
            termination = SubagentTermination.INCOMPLETE,
            reason = "结论文本自述任务未完成（命中「$marker」）",
        )
    }
    return SubagentConclusionVerdict(accepted = true, termination = SubagentTermination.CONCLUDED, reason = "")
}

/**
 * 写租约的执行期强制校验。提示词只能建议，模型忽略提示时必须有闸门，
 * 否则并行子任务之间的写隔离只是纸面约定。
 *
 * - `writePaths` 为空 → 只读任务，任何写工具都拒绝；
 * - `["*"]` / `["."]` → 整工作区租约，放行；
 * - 其他 → 目标路径必须落在声明的文件或目录之下。
 *
 * 目标与租约都经 [normalizeWritePath] 按段消解 `..` 后再比较，否则租约 `docs` 下
 * `docs/../secret.txt` 会因前缀匹配被放行；消解后仍逃出工作区顶层的路径直接判越界。
 *
 * 闸门只覆盖结构化写工具（write/edit/download）。`base` 执行的 shell 写
 * （重定向、`sed -i`、`mv`/`rm`）无法可靠静态判定，仍只由提示词约束——
 * 提示词与 tools.md 必须如实说明这条边界，否则模型会对 base 写产生错误信任。
 *
 * @return null 表示放行；非 null 为回写给模型的拒绝原因。
 */
internal fun subagentWriteScopeRejection(
    tool: HarnessTool,
    args: JsonObject,
    writePaths: List<String>,
): String? {
    val action = args.stringOrNull("action")?.lowercase()
    if (tool == HarnessTool.MCP && args.stringOrNull("action")?.lowercase() == "call") {
        return if (writePaths.isEmpty()) {
            "本子任务未声明 write_paths，按只读任务执行，禁止调用可能改变外部状态的 MCP 能力。请改为只读能力，或让主智能体在重新派发时声明具体写租约。"
        } else null
    }
    if (tool == HarnessTool.BUILD_SCRIPT && action in setOf("create", "update", "delete", "bind", "unbind")) {
        return if (writePaths.isEmpty()) {
            "本子任务未声明 write_paths，按只读任务执行，禁止执行 build_script 的 $action 落盘/绑定动作。请改为 list/get，或重新派发并声明写租约。"
        } else null
    }
    val pathKey = when (tool) {
        HarnessTool.WRITE, HarnessTool.EDIT -> "path"
        HarnessTool.DOWNLOAD -> "destination"
        else -> return null
    }
    if (writePaths.isEmpty()) {
        return "本子任务未声明 write_paths，按只读任务执行，禁止写入工作区。" +
            "请把需要落盘的完整内容作为结论正文返回，由主智能体写入，" +
            "或让主智能体在重新派发时声明 write_paths。"
    }
    val scopes = writePaths.map(::normalizeWritePath)
    // 归一化后的空串等价于工作区根（`.` 的消解结果），与 `*` 一样是整工作区租约，
    // 不能当作"未声明"而降级为只读。
    if (scopes.any { it == "*" || it.isBlank() }) return null
    val rawTarget = args.stringOrNull(pathKey).orEmpty()
    val target = normalizeWritePath(rawTarget)
    // 参数缺失或为空交由执行层按 schema 报错，这里不抢先拦截。
    if (target.isBlank()) return null
    if (target.startsWith("../")) {
        return "路径 $rawTarget 经 ../ 消解后逃出工作区顶层，已拦截。请使用工作区内的相对路径。"
    }
    val allowed = scopes.any { scope -> target == scope || target.startsWith("$scope/") }
    return if (allowed) {
        null
    } else {
        "路径 $target 超出本子任务的写租约范围（${scopes.joinToString("、")}），已拦截以保持并行子任务之间的写隔离。" +
            "请只在租约范围内写入，或把内容作为结论返回由主智能体处理。"
    }
}

/**
 * 结构化写工具：写租约闸门与"写入最终失败"追踪共用的作用域。
 * `base` 的 shell 写不在其中——见 [subagentWriteScopeRejection] 的边界说明。
 */
internal val LANE_WRITE_TOOLS = setOf(HarnessTool.WRITE, HarnessTool.EDIT, HarnessTool.DOWNLOAD)

/** 被拦截写入的可读描述，用于汇总与完成判定。 */
internal fun subagentWriteTargetLabel(rawToolName: String, args: JsonObject): String {
    val target = args.stringOrNull("path") ?: args.stringOrNull("destination")
    return listOf(rawToolName, target.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * 任务文字是否要求落盘。主智能体漏传 write_paths 时，只读提示会与任务目标直接冲突，
 * 必须在 Lane 提示词与父汇总里都说清楚，而不是让子智能体在"要写"和"不许写"之间自行猜测。
 *
 * 判定刻意保守：这里命中会向子任务注入"任务要落盘但没有写租约"的警示，误触发等于给纯分析
 * 任务塞误导性提示词。因此只有两种情况算写意图：
 * 1. 命中无歧义的落盘短语（"落盘""写入文件"…）；
 * 2. 命中"写入/保存到"这类高频动词，**且**文中同时出现文件名样式的 token。
 *
 * "修改代码""修改文件"等在纯分析任务（"给出修改代码的建议"）里过于高频，一律不算写意图。
 */
internal fun declaresWriteIntent(taskPrompt: String): Boolean {
    if (EXPLICIT_WRITE_INTENT_MARKERS.any { taskPrompt.contains(it, ignoreCase = true) }) return true
    return AMBIGUOUS_WRITE_INTENT_MARKERS.any { taskPrompt.contains(it, ignoreCase = true) } &&
        FILE_NAME_HINT.containsMatchIn(taskPrompt)
}

private val EXPLICIT_WRITE_INTENT_MARKERS = listOf(
    "落盘",
    "写入文件",
    "写到文件",
    "写文件",
    "生成文件",
    "创建文件",
    "新建文件",
    "输出文件",
    "保存文件",
    "write the file",
    "write to file",
    "save to file",
)

private val AMBIGUOUS_WRITE_INTENT_MARKERS = listOf(
    "写入",
    "写到",
    "写进",
    "保存到",
    "存到",
    "输出到",
    "save to",
)

/** `report.md`、`Main.kt` 这类"名字.扩展名"token，用来给高频动词加上落盘目标的佐证。 */
private val FILE_NAME_HINT = Regex("""[\w./\\-]+\.[A-Za-z0-9]{1,8}(?![\w.])""")

/**
 * Lane 上下文预算治理。
 *
 * 主循环有压缩与预算，子循环原本每轮直接把全部任务历史发出去，几次大文件读取就能提前撑满
 * 上下文。这里按预算收紧历史工具输出，但**绝不丢消息**：NATIVE 协议下丢掉 tool_call 或
 * tool_result 会产生非法 transcript，比超预算更致命。
 *
 * 两级降级：先按工具类型做语义压缩，仍超预算再对旧结果硬截断。硬截断档同时给"最近结果"
 * 设一个宽松上限——最后一轮 read 一个几百 KB 的大文件正是最需要兜底的场景，
 * 若让保护条目完全豁免截断，这一档就等于不生效。
 */
internal fun budgetedLaneMessages(
    messages: List<HarnessMessage>,
    budgetTokens: Int,
    keepRecentResults: Int = KEEP_RECENT_TOOL_RESULTS,
): List<HarnessMessage> {
    if (budgetTokens <= 0 || messages.isEmpty()) return messages
    if (estimateLaneTokens(messages) <= budgetTokens) return messages

    val calls = messages.filterIsInstance<ToolCall>().associateBy { it.id }
    val protectedResultIds = messages.filterIsInstance<ToolResult>()
        .takeLast(keepRecentResults.coerceAtLeast(0))
        .mapTo(mutableSetOf()) { it.id }

    fun mapResults(transform: (ToolResult) -> String): List<HarnessMessage> = messages.map { message ->
        if (message is ToolResult && message.id !in protectedResultIds) {
            message.copy(output = transform(message))
        } else {
            message
        }
    }

    val compacted = mapResults { result ->
        val call = calls[result.toolCallId]
        val name = call?.let { it.rawToolName ?: HarnessApiMapper.apiName(it.tool) }
        ContextWindowPolicy.compactToolOutput(name, call?.args, result.output, result.success)
    }
    if (estimateLaneTokens(compacted) <= budgetTokens) return compacted

    return messages.map { message ->
        if (message !is ToolResult) return@map message
        val limit = if (message.id in protectedResultIds) {
            PROTECTED_RESULT_MAX_CHARS
        } else {
            HARD_TRUNCATED_RESULT_CHARS
        }
        if (message.output.length <= limit) message else message.copy(output = message.output.take(limit) + TRUNCATION_NOTE)
    }
}

private const val TRUNCATION_NOTE =
    "\n…[子智能体上下文预算超限，工具输出已强制截断；需要细节请用更精确的参数（offset/limit、更窄的匹配）重新读取]"

private fun estimateLaneTokens(messages: List<HarnessMessage>): Int = messages.sumOf { message ->
    when (message) {
        is UserMessage -> ContextWindowPolicy.estimateTokens(message.text)
        is AssistantText -> ContextWindowPolicy.estimateTokens(message.text)
        is ToolCall -> ContextWindowPolicy.estimateTokens(message.args.toString())
        is ToolResult -> ContextWindowPolicy.estimateTokens(message.output)
        else -> 0
    }
}

/**
 * 超时/中断后的阶段成果摘要。
 *
 * 只回一句"执行超时"会让父智能体重新派发一个全新 Lane 从头重复同样的工作。
 * 这里从已落库的 lane transcript 还原：做过什么、改过哪些文件、最后卡在哪里。
 */
internal fun renderSubagentProgressDigest(transcript: List<HarnessMessage>): String {
    if (transcript.isEmpty()) return ""
    val calls = transcript.filterIsInstance<ToolCall>().associateBy { it.id }
    val steps = transcript.filterIsInstance<ToolResult>().mapNotNull { result ->
        val call = calls[result.toolCallId] ?: return@mapNotNull null
        val name = call.rawToolName ?: HarnessApiMapper.apiName(call.tool)
        // base 的 command 常是多行脚本，整段塞进摘要不可读；只取首行做标识。
        val target = call.args.stringOrNull("path")
            ?: call.args.stringOrNull("destination")
            ?: call.args.stringOrNull("command")?.lineSequence()?.firstOrNull { it.isNotBlank() }
        LaneStep(
            toolName = name,
            target = target?.trim()?.take(160).orEmpty(),
            success = result.success,
            output = result.output,
            mutating = call.tool in MUTATING_TOOLS,
        )
    }
    if (steps.isEmpty() && transcript.none { it is AssistantText }) return ""

    val succeeded = steps.count { it.success }
    val mutatedTargets = steps.filter { it.mutating && it.success && it.target.isNotBlank() }
        .map { it.target }
        .distinct()
    val lastFailure = steps.lastOrNull { !it.success }
    val lastProgress = transcript.filterIsInstance<AssistantText>().lastOrNull()?.text

    return buildString {
        appendLine("**阶段成果（超时前已完成的工作，续跑时请据此接续，不要从头重复）**")
        appendLine("- 已执行工具 ${steps.size} 次：成功 $succeeded / 失败 ${steps.size - succeeded}")
        if (mutatedTargets.isNotEmpty()) {
            appendLine("- 已改动目标：${mutatedTargets.take(12).joinToString("、")}")
        }
        val readTargets = steps.filter { !it.mutating && it.success && it.target.isNotBlank() }
            .map { it.target }
            .distinct()
        if (readTargets.isNotEmpty()) {
            appendLine("- 已查证目标：${readTargets.takeLast(12).joinToString("、")}")
        }
        if (lastFailure != null) {
            appendLine(
                "- 最近失败：${lastFailure.toolName} ${lastFailure.target} → " +
                    lastFailure.output.replace('\n', ' ').take(240),
            )
        }
        if (!lastProgress.isNullOrBlank()) {
            appendLine("- 最后进度说明：${lastProgress.replace('\n', ' ').take(600)}")
        }
    }.trim()
}

internal fun buildSubagentTimeoutSummary(
    timeoutMs: Long,
    toolCallCount: Int,
    transcript: List<HarnessMessage>,
    laneName: String,
): String = buildString {
    append("⚠️ 子任务执行超时（${timeoutMs / 60_000} 分钟，已执行 $toolCallCount 次工具调用），未产出最终结论。")
    append(subagentOutcomeUncertainty(transcript))
    val digest = renderSubagentProgressDigest(transcript)
    if (digest.isNotBlank()) {
        append("\n\n")
        append(digest)
        // 「上述阶段成果」只在真的还原出阶段成果时才提；空 transcript 时说这句话没有指代。
        append("\n\n续跑建议：重新派发时把上述阶段成果写进 prompt，并把任务切小（如只处理剩余文件）。")
    }
    if (laneName.isNotBlank()) {
        append("\n\n本次 Lane 标识：`").append(laneName).append("`。")
    }
}

private data class LaneStep(
    val toolName: String,
    val target: String,
    val success: Boolean,
    val output: String,
    val mutating: Boolean,
)

private val MUTATING_TOOLS = setOf(
    HarnessTool.WRITE,
    HarnessTool.EDIT,
    HarnessTool.DOWNLOAD,
    HarnessTool.BASE,
    HarnessTool.PROCESS,
    HarnessTool.HOST,
)

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** 最近若干条工具结果不做语义压缩：模型正是靠它们决定下一步。 */
private const val KEEP_RECENT_TOOL_RESULTS = 2

private const val HARD_TRUNCATED_RESULT_CHARS = 800

/**
 * 最近结果在硬截断档的上限。取值远大于 [HARD_TRUNCATED_RESULT_CHARS]：既保留足够的决策信息，
 * 又不让单条大文件读取独占整个 Lane 预算。
 */
private const val PROTECTED_RESULT_MAX_CHARS = 16_000

/**
 * 疑似 shell 写命令检测（对齐 Reasonix 的"运行后 host 对比凭据与租约"）：
 * 结构化写（write/edit/download）已有租约闸门，唯一盲区是 base/process 的 shell 写
 * （重定向、sed -i、mv/rm 等无法静态判定路径范围）。这里做**保守的软检测**——
 * 只为父智能体提供可见性（⚠️ 提示复核产物），绝不据此拦截或判失败。
 *
 * @return 命中写特征的命令首行列表（去重）。
 */
internal fun detectSuspectedShellWrites(
    transcript: List<HarnessMessage>,
    writePaths: List<String>,
): List<String> {
    // 整工作区租约的 lane，shell 写本来就在租约范围内，无需检测
    if (writePaths.any(::isWholeWorkspaceWritePath)) return emptyList()
    val succeededCallIds = transcript.filterIsInstance<ToolResult>()
        .filter { it.success }
        .mapTo(mutableSetOf()) { it.toolCallId }
    val hits = mutableListOf<String>()
    transcript.filterIsInstance<ToolCall>()
        .filter { (it.tool == HarnessTool.BASE || it.tool == HarnessTool.PROCESS) && it.id in succeededCallIds }
        .forEach { call ->
            val command = call.args.stringOrNull("command") ?: return@forEach
            if (looksLikeShellWrite(command)) {
                val label = command.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160).orEmpty()
                if (label.isNotBlank() && label !in hits) hits += label
            }
        }
    return hits
}

/** 命令是否带有写入特征。刻意高召回：软警告的代价远小于漏报一次越界写。 */
internal fun looksLikeShellWrite(command: String): Boolean {
    val normalized = " $command "
    if (SHELL_WRITE_HINTS.any { normalized.contains(it) }) return true
    REDIRECT_TARGET.findAll(command).forEach { match ->
        val target = match.groupValues[2].trim()
        // /dev/null 是错误流抑制的惯用目标，不算写
        if (target.isNotBlank() && !target.startsWith("/dev/null")) return true
    }
    return false
}

// 常见写命令特征（两侧加空格避免子串误命中，如 "firm" 命中 "rm"）
private val SHELL_WRITE_HINTS = listOf(
    " >> ", "sed -i", " tee ", " mv ", " rm ", " cp ", " touch ", " mkdir ",
    " wget ", " dd ", " chmod ", " chown ", " patch ", " truncate ", " rsync ",
)

// 重定向：> 或 >>，排除 2>/&>（错误流抑制）；目标是否 /dev/null 由调用方判读
private val REDIRECT_TARGET = Regex("""(^|[^0-9>&])>{1,2}\s*([^\s;&|]+)""")
