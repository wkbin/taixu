package top.wkbin.taixu.harness

import java.util.concurrent.ConcurrentHashMap
import top.wkbin.taixu.core.database.AgentContextRepository
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.model.AgentSubagent
import top.wkbin.taixu.core.model.AgentSubagentIndexEntry
import top.wkbin.taixu.core.model.SubagentTaskSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.taixu.harness.session.LaneManager
import top.wkbin.taixu.harness.subagent.SubagentApprovalHandoff
import top.wkbin.taixu.harness.subagent.SubagentClaimAdjudication
import top.wkbin.taixu.harness.subagent.SubagentLaneRunner
import top.wkbin.taixu.harness.subagent.SubagentTermination
import top.wkbin.taixu.harness.subagent.adjudicateSubagentClaim
import top.wkbin.taixu.harness.subagent.buildSubagentTimeoutSummary
import top.wkbin.taixu.harness.subagent.declaresWriteIntent
import top.wkbin.taixu.harness.subagent.detectSuspectedShellWrites
import top.wkbin.taixu.harness.subagent.extractSubagentReceipts
import top.wkbin.taixu.harness.subagent.parseSubagentClaim
import top.wkbin.taixu.harness.subagent.renderSubagentClaimAdjudication
import top.wkbin.taixu.harness.subagent.stripSubagentClaimBlock
import top.wkbin.taixu.harness.prompt.PromptAssetLoader
import top.wkbin.taixu.harness.WorkspaceFileAccess
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.database.AgentSubagentRepository

internal class SubagentConcurrencyGate(
    maxParallelism: Int = DEFAULT_MAX_CONCURRENT_SUBAGENTS,
) {
    private val permits = Semaphore(maxParallelism.coerceAtLeast(1))

    suspend fun <T> withPermit(block: suspend () -> T): T = permits.withPermit { block() }
}

internal const val DEFAULT_MAX_CONCURRENT_SUBAGENTS = 3

/**
 * Subagent 子智能体任务编排器：
 * 负责解析主智能体的 invoke_subagent 请求，动态创建隔离的子会话，
 * 并发调度子智能体执行研究、编写或测试任务，最终汇聚输出结构化 Markdown。
 */
class SubagentOrchestrator(
    private val sessionDao: HarnessSessionRepository,
    private val laneManager: LaneManager,
    private val laneRunner: SubagentLaneRunner,
    private val subagentRepository: AgentSubagentRepository,
    private val promptAssets: PromptAssetLoader,
    private val agentContextRepo: AgentContextRepository,
    private val fileAccess: WorkspaceFileAccess,
    private val providerClient: ProviderClient,
    private val logger: AppLogger,
    private val summaryPublisher: SubagentSummaryPublisher? = null,
) {
    /**
     * Application-wide budget: a three-agent fan-out should actually run three lanes at once,
     * while larger batches still queue to protect the shared API/PRoot/Room resources.
     */
    private val globalParallelism = SubagentConcurrencyGate()
    private val resumeLaneLocks = ConcurrentHashMap<String, Mutex>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun executeSubagents(
        args: JsonObject,
        parentSessionId: String,
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val specs = SubagentArgsParser.parse(args)
        if (specs.isEmpty()) {
            return@withContext false to "未解析到有效的 subagents 任务列表，请检查参数"
        }
        val background = args["background"]?.jsonPrimitive?.booleanOrNull == true
        if (background) {
            return@withContext false to
                "background=true 暂不可用：移动端后台子任务尚未接入持久批次恢复与前台服务保活。" +
                "为避免应用退后台后任务静默丢失，本次没有启动任何子任务；请移除 background 并以前台模式重试。"
        }
        runBatch(specs, parentSessionId)
    }

    private suspend fun runBatch(
        specs: List<SubagentTaskSpec>,
        parentSessionId: String,
    ): Pair<Boolean, String> {
        return supervisorScope {
        val parentSession = sessionDao.findById(parentSessionId)
        val workspace = parentSession?.workspace.orEmpty()
        val modelId = parentSession?.modelId
        val modelVariant = parentSession?.modelVariant
        val profileIndex = subagentRepository.enabledIndex()
        if (profileIndex.isEmpty()) {
            return@supervisorScope false to "当前没有启用的子智能体角色，请先在 Agent 设置中添加或启用角色"
        }
        val parentLeaf = laneManager.get(parentSessionId, "main")?.leafId

        // 写租约协调：把任务按 write_paths 冲突切成若干"写不冲突"的波。
        // 同一波内的写路径互不相交 → 可并行（受 globalParallelism 约束）；跨波顺序执行。
        // 未声明 writePaths 的任务为只读任务，可彼此并行；["*"] 才表示整工作区独占写租约。
        val results = mutableListOf<SubagentExecutionOutcome>()
        val waves = buildWriteCleanWaves(specs)
        logger.logAgent(
            parentSessionId,
            "SubagentSchedule",
            "tasks=${specs.size}, waves=${waves.size}, maxParallelism=$DEFAULT_MAX_CONCURRENT_SUBAGENTS, " +
                "waveSizes=${waves.joinToString(prefix = "[", postfix = "]") { it.size.toString() }}",
        )
        for ((waveIndex, wave) in waves.withIndex()) {
            val waveStartedAt = System.nanoTime()
            logger.logAgent(
                parentSessionId,
                "SubagentWave",
                "start=${waveIndex + 1}/${waves.size}, tasks=${wave.joinToString { it.taskName }}; " +
                    "lease wave=${waveLeaseDescription(wave)}; tasks in this wave may run concurrently, later waves wait",
            )
            val waveResults = wave.map { spec ->
                async {
                    globalParallelism.withPermit {
                        runSubagentSafely(spec, parentSessionId, parentLeaf, workspace, modelId, modelVariant, profileIndex)
                    }
                }
            }.awaitAll()
            results += waveResults
            logger.logAgent(
                parentSessionId,
                "SubagentWave",
                "complete=${waveIndex + 1}/${waves.size}, elapsedMs=${(System.nanoTime() - waveStartedAt) / 1_000_000}, " +
                    "results=${waveResults.joinToString { "${it.spec.taskName}:${it.termination}" }}",
            )
        }

        // Completion order and lease waves must not change the user-requested presentation order.
        val orderedResults = results.sortedBy { outcome -> specs.indexOf(outcome.spec) }
        val summaryMarkdown = paginateSummary(orderedResults, parentSessionId, workspace)
        logger.logAgent(
            parentSessionId,
            "SubagentBatch",
            orderedResults.joinToString("; ") { outcome ->
                "task=${outcome.spec.taskName}, success=${outcome.isSuccess}, termination=${outcome.termination}, " +
                    "toolCalls=${outcome.toolCallCount}, pendingApprovals=${outcome.pendingApprovals.size}, " +
                    "blockedWrites=${outcome.blockedWrites.size}, " +
                    "readOnlyWriteIntent=${outcome.readOnlyWriteIntent}, " +
                    "suspectedShellWrites=${outcome.suspectedShellWrites.joinToString("|")}"
            },
        )
        // 失败原因必须与上面的批次日志同等、无门控地落盘：子智能体的终止原因原先只经
        // AgentEventLogger（受 agentLoggingEnabled 门控，默认关闭），批次行里也只有一句
        // termination=FAILED，于是"派发的子智能体总是全部失败"既拿不到具体异常，也无法
        // 区分不可重试 4xx / 限流耗尽 / 上下文超限 / 超时 / 轮数耗尽。摘要是唯一的线索来源。
        orderedResults.filterNot { it.isSuccess }.forEach { outcome ->
            logger.logAgent(
                parentSessionId,
                "SubagentFailure",
                "task=${outcome.spec.taskName}, termination=${outcome.termination}, " +
                    "toolCalls=${outcome.toolCallCount}, reason=" +
                    outcome.summary.lineSequence().firstOrNull()?.take(300).orEmpty(),
            )
        }
        // 整批工具结果只有在每个子任务都确认完成时才算成功。用 anySuccess 会让"1 成功 5 失败"
        // 在父会话里显示为成功工具调用，模型据此继续往下走，正文里的部分失败说明形同虚设。
        val allSucceeded = orderedResults.all { it.isSuccess }
        allSucceeded to summaryMarkdown
        }
    }

    private suspend fun runSubagentSafely(
        spec: SubagentTaskSpec,
        parentSessionId: String,
        parentLeaf: String?,
        workspace: String,
        modelId: String?,
        modelVariant: String?,
        profileIndex: List<AgentSubagentIndexEntry>,
    ): SubagentExecutionOutcome = try {
        runSubagent(spec, parentSessionId, parentLeaf, workspace, modelId, modelVariant, profileIndex)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        SubagentExecutionOutcome(
            spec = spec,
            subSessionId = spec.taskId.orEmpty(),
            isSuccess = false,
            summary = "子任务执行异常（${failure::class.simpleName ?: "Throwable"}）：" +
                (failure.message ?: "无错误消息"),
            toolCallCount = 0,
            termination = SubagentTermination.FAILED,
        )
    }

    private suspend fun runSubagent(
        spec: SubagentTaskSpec,
        parentSessionId: String,
        parentLeaf: String?,
        workspace: String,
        modelId: String?,
        modelVariant: String?,
        profileIndex: List<AgentSubagentIndexEntry>,
    ): SubagentExecutionOutcome {
        // task_id 续跑（对齐 opencode task 工具）：跳过 create，直接在同名 Lane 已有
        // 对话上追加本轮指令；角色取自 lane 名内嵌的 profileId，忽略本次派发参数。
        val resumeLaneName = spec.taskId?.trim()?.takeIf { it.isNotBlank() }
        val laneName: String
        val profile: AgentSubagent
        if (resumeLaneName != null) {
            val existing = laneManager.get(parentSessionId, resumeLaneName)
            if (existing == null || !existing.name.startsWith("subagent:")) {
                return SubagentExecutionOutcome(
                    spec = spec,
                    subSessionId = resumeLaneName,
                    isSuccess = false,
                    summary = "task_id=$resumeLaneName 不存在或不是本会话的子任务 Lane，无法续跑。" +
                        "请从最近一次 invoke_subagent 汇总中原样复制 task_id；Lane 已删除时改为派发全新任务。",
                    toolCallCount = 0,
                    termination = SubagentTermination.INCOMPLETE,
                )
            }
            val resumedProfile = subagentRepository.findEnabledProfile(
                resumeLaneName.removePrefix("subagent:").substringBeforeLast(':'),
            )
            if (resumedProfile == null) {
                return SubagentExecutionOutcome(
                    spec = spec,
                    subSessionId = resumeLaneName,
                    isSuccess = false,
                    summary = "续跑 Lane 的角色已禁用或删除，无法续跑；请改为派发全新任务。",
                    toolCallCount = 0,
                    termination = SubagentTermination.FAILED,
                )
            }
            profile = resumedProfile
            laneName = resumeLaneName
        } else {
            val resolved = resolveProfile(spec, profileIndex)
            if (resolved == null) {
                return SubagentExecutionOutcome(
                    spec = spec,
                    subSessionId = "",
                    isSuccess = false,
                    summary = routingFailure(spec),
                    toolCallCount = 0,
                    termination = SubagentTermination.FAILED,
                )
            }
            profile = resolved
            laneName = "subagent:${profile.id}:${java.util.UUID.randomUUID()}"
            laneManager.create(parentSessionId, laneName, parentLeaf)
        }
        val readOnlyWriteIntent = spec.writePaths.isEmpty() && declaresWriteIntent(spec.prompt)
        val prompt = if (resumeLaneName != null) {
            buildSubagentResumePrompt(spec, workspace, parentSessionId, readOnlyWriteIntent)
        } else {
            buildSubagentPrompt(spec, profile, workspace, parentSessionId, readOnlyWriteIntent)
        }
        if (readOnlyWriteIntent) {
            logger.logAgent(
                parentSessionId,
                "SubagentWriteScope",
                "task=${spec.taskName} 任务文字要求落盘但未声明 write_paths，本 Lane 按只读执行",
            )
        }
        val targetModel = runCatching {
            when (val route = selectSubagentModel(spec.model, profile.defaultModelId, profile.defaultModelVariant)) {
                SubagentModelRoute.Inherit -> providerClient.resolveRequestedModel(null, modelId, modelVariant)
                is SubagentModelRoute.Requested -> providerClient.resolveRequestedModel(route.selection, modelId, modelVariant)
                is SubagentModelRoute.RoleDefault -> providerClient.resolveSavedModelProfile(route.profileId, route.variant)
            }
        }.getOrElse { failure ->
            return SubagentExecutionOutcome(
                spec = spec,
                subSessionId = resumeLaneName ?: "",
                isSuccess = false,
                summary = failure.message ?: "无法解析子智能体模型",
                toolCallCount = 0,
                resolvedProfileId = profile.id,
                resolvedProfileName = profile.name,
                termination = SubagentTermination.FAILED,
            )
        }
        val laneLockKey = "$parentSessionId::$laneName"
        val laneLock = resumeLaneLocks.getOrPut(laneLockKey) { Mutex() }
        if (!laneLock.tryLock()) {
            return SubagentExecutionOutcome(
                spec = spec,
                subSessionId = laneName,
                isSuccess = false,
                summary = "task_id=$laneName 正在运行，拒绝并发续跑；请等待当前执行完成后再提交新指令。",
                toolCallCount = 0,
                resolvedProfileId = profile.id,
                resolvedProfileName = profile.name,
                termination = SubagentTermination.INCOMPLETE,
            )
        }
        val laneResult = try {
            withTimeoutOrNull(SUBAGENT_TIMEOUT_MS) {
                laneRunner.run(
                    parentSessionId,
                    laneName,
                    prompt,
                    workspace,
                    modelConfig = targetModel,
                    writePaths = spec.writePaths,
                )
            }
        } finally {
            laneLock.unlock()
        }

        // 超时取消时 withTimeoutOrNull 返回 null，若直接 ?: 0 会把子智能体在超时窗口内
        // 真实执行的工具调用全部归零，汇总里出现"明明在跑却显示 0 次工具调用"。
        // lane 的 tool_call entry 在执行期已逐条落库，从 transcript 恢复真实计数。
        val transcript = runCatching { laneManager.subagentTranscript(parentSessionId, laneName) }
            .getOrElse { runCatching { laneManager.transcript(parentSessionId, laneName) }.getOrDefault(emptyList()) }
        val toolCallCount = resolveSubagentToolCallCount(laneResult?.toolCallCount, transcript)

        var outcome = SubagentExecutionOutcome(
            spec = spec,
            subSessionId = laneName,
            isSuccess = laneResult?.success == true,
            // 超时不再只回一句"执行超时"：已查到的证据、改过的文件、最后进度都从 transcript
            // 还原成续跑摘要，否则父智能体只能创建全新 Lane 从头重复同样的工作。
            summary = laneResult?.summary
                ?: buildSubagentTimeoutSummary(SUBAGENT_TIMEOUT_MS, toolCallCount, transcript, laneName),
            toolCallCount = toolCallCount,
            resolvedProfileId = profile.id,
            resolvedProfileName = profile.name,
            resolvedModel = "${targetModel.provider}/${targetModel.model}",
            termination = laneResult?.termination ?: SubagentTermination.TIMEOUT,
            pendingApprovals = laneResult?.pendingApprovals.orEmpty(),
            blockedWrites = laneResult?.blockedWrites.orEmpty(),
            readOnlyWriteIntent = readOnlyWriteIntent,
            tokenUsage = SubagentTokenUsage.extractFrom(transcript),
            suspectedShellWrites = detectSuspectedShellWrites(transcript, spec.writePaths),
        )
        // 完成 claim 的 host 裁定：只对 host 已判 CONCLUDED 的结论生效，且只降级不升格。
        // claim 缺失/解析失败 = 旧行为原样保留（fail-open）。
        if (outcome.isSuccess && laneResult != null) {
            val claim = parseSubagentClaim(laneResult.summary)
            if (claim != null) {
                val adjudication = adjudicateSubagentClaim(claim, extractSubagentReceipts(transcript))
                // 剔除协议块后正文可能为空（整条结论只有一个 claim 块）：回退 claim.summary，
                // 再不行保留原文——父汇总的「子任务输出」不能是空串。
                val strippedSummary = stripSubagentClaimBlock(laneResult.summary, claim)
                    .ifBlank { claim.summaryText.ifBlank { laneResult.summary } }
                outcome = outcome.copy(
                    // 只有 host 最终裁定 complete 才算成功；partial/failed 不得升格。
                    isSuccess = adjudication.adjudicatedStatus == "complete",
                    termination = when {
                        adjudication.downgraded -> SubagentTermination.CLAIM_DOWNGRADED
                        adjudication.adjudicatedStatus == "partial" -> SubagentTermination.INCOMPLETE
                        adjudication.adjudicatedStatus == "failed" -> SubagentTermination.FAILED
                        else -> outcome.termination
                    },
                    // 原始 claim JSON 不进父上下文：正文剔除协议块，裁定结论进状态头。
                    summary = strippedSummary,
                    claimAdjudication = adjudication,
                )
            }
        }
        return outcome
    }


    private suspend fun resolveProfile(
        spec: SubagentTaskSpec,
        profileIndex: List<AgentSubagentIndexEntry>,
    ): AgentSubagent? {
        val selected = if (spec.role.isNotBlank()) {
            profileIndex.firstOrNull { entry ->
                entry.id.equals(spec.role, ignoreCase = true) ||
                    entry.name.equals(spec.role, ignoreCase = true)
            }
        } else {
            SubagentProfileMatcher.match(profileIndex, spec.department, spec.agentQuery)
        } ?: return null
        return subagentRepository.findEnabledProfile(selected.id)
    }

    private fun routingFailure(spec: SubagentTaskSpec): String = if (spec.role.isNotBlank()) {
        "精确角色 ${spec.role} 未配置或未启用。可改用 department + agentQuery 让本地索引派发。"
    } else {
        "部门 ${spec.department} 中没有匹配 agentQuery=\"${spec.agentQuery}\" 的已启用角色。" +
            "请保留部门并改用 2–5 个更具体的英文专业关键词。"
    }

    private suspend fun buildSubagentPrompt(
        spec: SubagentTaskSpec,
        profile: AgentSubagent,
        workspace: String,
        parentSessionId: String,
        readOnlyWriteIntent: Boolean,
    ): String {
        val factsPack = buildParentFactsPack(parentSessionId, workspace)
        val writeLine = buildWriteLeaseLine(spec, readOnlyWriteIntent)
        return promptAssets.render(
            "prompts/subagent_task.md",
            mapOf(
                "ROLE_NAME" to profile.name,
                "ROLE_ID" to profile.id,
                "TASK_NAME" to spec.taskName,
                "WORKSPACE_LINE" to workspace.takeIf { it.isNotBlank() }?.let { "工作区：$it" }.orEmpty(),
                "ROLE_PROMPT" to profile.systemPrompt.trim(),
                "TASK_PROMPT" to spec.prompt,
                "WRITE_LINE" to writeLine,
                "FACTS_PACK" to factsPack,
            ),
        )
    }

    /** 写租约约束文案：全新派发与续跑共用同一份语义（空=只读、精确路径=局部租约、["*"]=独占）。 */
    private fun buildWriteLeaseLine(spec: SubagentTaskSpec, readOnlyWriteIntent: Boolean): String = when {
        spec.writePaths.isEmpty() -> buildString {
            append("本任务为只读任务：禁止调用 write/edit/download，禁止执行会修改工作区的命令；只返回分析或数据。")
            append("write/edit/download 在本 Lane 内会被强制拦截；base 命令不受该闸门约束，")
            append("因此绝不允许用 shell 重定向、sed -i、mv、rm 等方式绕过。")
            if (readOnlyWriteIntent) {
                // 任务文字要求落盘却没有写租约：必须显式指出冲突，否则子智能体会在
                // "要写"与"不许写"之间自行猜测，并可能把"没法写"当成完成。
                append("\n注意：本任务文字提到了落盘/写入，但主智能体未声明 write_paths。")
                append("请把需要落盘的完整内容直接放进结论正文（含目标路径与完整文件内容），")
                append("由主智能体写入；不要声称文件已生成。")
            }
        }
        spec.writePaths.any(::isWholeWorkspaceWritePath) ->
            "本任务持有整工作区独占写租约；仅修改任务确实需要的文件，避免无关改动。"
        else -> buildString {
            append("限定写入范围（write/edit/download 会强制校验，越界写入直接拦截）：")
            append(spec.writePaths.joinToString("、"))
            append("。base 命令不受该闸门约束，也必须遵守同一范围。")
        }
    }

    /**
     * 续跑指令：Lane 已持有原任务的完整上下文（角色提示词、任务原文、前序工具记录），
     * 这里只注入新指令与写租约提醒，不再重放整份任务模板。
     */
    private suspend fun buildSubagentResumePrompt(
        spec: SubagentTaskSpec,
        workspace: String,
        parentSessionId: String,
        readOnlyWriteIntent: Boolean,
    ): String {
        val factsPack = buildParentFactsPack(parentSessionId, workspace)
        return buildString {
            append("【任务续跑】原任务「${spec.taskName}」未完结，主智能体要求在本 Lane 已有工作基础上继续：\n")
            append(spec.prompt.trim())
            append("\n\n")
            append(buildWriteLeaseLine(spec, readOnlyWriteIntent))
            if (factsPack.isNotBlank()) append("\n\n$factsPack")
        }
    }

    /** 父级 facts pack：把父会话已 pin 的长期指令/事实浓缩为一段低体积背景，而非整段父 transcript。 */
    private suspend fun buildParentFactsPack(parentSessionId: String, workspace: String): String {
        val pinned = runCatching {
            agentContextRepo.getPinnedMemories(
                projectOwnerId = workspace.trim().trimEnd('/'),
                sessionId = parentSessionId,
            )
        }.getOrDefault(emptyList())
        if (pinned.isEmpty()) return ""
        return "## 父级上下文事实包（父会话已 pin 的指令/事实，作为本子任务的背景参考）\n" +
            pinned.joinToString("\n") { "- [${it.scope}/${it.kind}] ${it.key}: ${it.value}" }
    }

    private companion object {
        // 15 分钟：移动端复杂分析任务（大文件读取 + 多轮工具调用 + 长文本产出）
        // 实测 6 分钟不够用，超时前往往仍在正常执行中途。
        const val SUBAGENT_TIMEOUT_MS = 15 * 60 * 1000L
    }

    /**
     * 结果分页读取：汇总注入父上下文前先做预算控制。
     * 总量 ≤ [SUMMARY_INLINE_BUDGET] 字符 → 原样注入（保持现状，不破坏小批次体验）；
     * 超限 → 每个子任务输出截断为 [PER_TASK_INLINE_BUDGET] 字符，
     * 生产完整结果通过当前环境的产物接口保存（工作区相对路径），
     * 模型可用 read 工具按 offset/limit 分页读取。
     */
    private suspend fun paginateSummary(outcomes: List<SubagentExecutionOutcome>, session: String, workspace: String): String =
        (summaryPublisher ?: SubagentSummaryPublisher(fileAccess)).publish(outcomes, session, workspace)

    internal data class SubagentExecutionOutcome(
        val spec: SubagentTaskSpec,
        val subSessionId: String,
        val isSuccess: Boolean,
        val summary: String,
        val toolCallCount: Int,
        val resolvedProfileId: String? = null,
        val resolvedProfileName: String? = null,
        val resolvedModel: String? = null,
        val termination: SubagentTermination = SubagentTermination.CONCLUDED,
        val pendingApprovals: List<SubagentApprovalHandoff> = emptyList(),
        val blockedWrites: List<String> = emptyList(),
        /** 任务文字要求落盘但未声明 write_paths，本次按只读执行。 */
        val readOnlyWriteIntent: Boolean = false,
        /**
         * 完成 claim 的 host 裁定。null = 模型未提交结构化 claim（旧行为不变）；
         * 非 null 时 [SubagentClaimAdjudication.adjudicatedStatus] 是交给父智能体的唯一可信状态。
         */
        val claimAdjudication: SubagentClaimAdjudication? = null,
        /** 委托经济学：本 lane 的 token 用量（含缓存命中），从 transcript 的 AssistantText 聚合。 */
        val tokenUsage: SubagentTokenUsage? = null,
        /** 疑似租约外 shell 写命令（软警告，无法静态核验范围）；整工作区租约恒为空。 */
        val suspectedShellWrites: List<String> = emptyList(),
    )
}

/**
 * 单个子代理 lane 的 token 用量（对齐 Reasonix 的委托经济学口径）：
 * 缓存命中单列，让"委派是否划算"有数据可看，而不是只看轮数。
 */
internal data class SubagentTokenUsage(
    val promptTokens: Long,
    val cachedTokens: Long,
    val completionTokens: Long,
) {
    /** 缓存命中率（0–100）；无输入时为 0。 */
    val cacheHitPercent: Int
        get() = if (promptTokens > 0) (cachedTokens * 100 / promptTokens).toInt() else 0

    companion object {
        /** 从 lane transcript 聚合；整条 lane 无用量记录（旧数据/失败早终止）返回 null。 */
        internal fun extractFrom(transcript: List<HarnessMessage>): SubagentTokenUsage? {
            var prompt = 0L
            var cached = 0L
            var completion = 0L
            transcript.filterIsInstance<AssistantText>().forEach { text ->
                prompt += text.promptTokens ?: 0
                cached += text.cachedTokens ?: 0
                completion += text.completionTokens ?: 0
            }
            if (prompt <= 0L && completion <= 0L) return null
            return SubagentTokenUsage(prompt, cached, completion)
        }
    }
}

internal sealed interface SubagentModelRoute {
    data object Inherit : SubagentModelRoute
    data class Requested(val selection: String) : SubagentModelRoute
    data class RoleDefault(val profileId: String, val variant: String?) : SubagentModelRoute
}

/** Explicit `inherit` bypasses a role default; an omitted task selection uses that default. */
internal fun selectSubagentModel(
    taskSelection: String?,
    roleDefaultModelId: String?,
    roleDefaultModelVariant: String?,
): SubagentModelRoute {
    val requested = taskSelection?.trim().orEmpty()
    if (requested.equals("inherit", ignoreCase = true)) return SubagentModelRoute.Inherit
    if (requested.isNotBlank()) return SubagentModelRoute.Requested(requested)
    val profileId = roleDefaultModelId?.trim().orEmpty()
    return if (profileId.isNotBlank()) {
        SubagentModelRoute.RoleDefault(profileId, roleDefaultModelVariant?.trim()?.takeIf { it.isNotBlank() })
    } else {
        SubagentModelRoute.Inherit
    }
}


/** background=true 时立即返回给模型的启动确认：明确"不要轮询"（对齐 opencode task 工具的输出约定）。 */
internal fun backgroundStartedText(specs: List<SubagentTaskSpec>): String = buildString {
    append("已启动 ${specs.size} 个后台子任务：${specs.joinToString("、") { it.taskName }}。\n")
    append("任务在后台执行，完成后结果会自动注入本会话并触发后续推理。")
    append("不要等待、轮询进度或重复派发相同任务；可先处理其他独立工作，或直接结束本轮回复。")
}

/** 后台批次完成后的注入文本：汇总 + 续跑指引；由 HarnessLoop.send 以用户消息触发新轮推理。 */
internal fun backgroundCompletionText(allSucceeded: Boolean, summaryMarkdown: String): String = buildString {
    append("【后台子任务已完成】\n\n")
    append(summaryMarkdown.trim())
    append("\n\n---\n以上为后台子任务结果，请基于它继续推进原任务。")
    if (!allSucceeded) {
        append("失败子任务可在新的 invoke_subagent 调用中用其 task_id 续跑（附新指令），或重新派发全新任务。")
    }
}

/**
 * 将任务按 write_paths 冲突切成若干互不相交的"波"。
 * 同一波内所有任务的写路径集合互不重叠 → 可安全并行执行。
 * 空 writePaths 是只读任务，可与其他只读任务并行；["*"] 是整工作区独占写租约。
 * 为避免读到写入中间态，只读波与任何写入波分离；局部写路径互不冲突时可并行。
 */
internal fun buildWriteCleanWaves(specs: List<SubagentTaskSpec>): List<List<SubagentTaskSpec>> {
    val waves = mutableListOf<MutableList<SubagentTaskSpec>>()
    val waveKinds = mutableListOf<SubagentWaveKind>()
    val wavePaths = mutableListOf<MutableSet<String>>() // 该波已占用的写路径
    for (spec in specs) {
        val paths = spec.writePaths.mapTo(hashSetOf()) { normalizeWritePath(it) }
        val kind = when {
            paths.isEmpty() -> SubagentWaveKind.READ_ONLY
            paths.any { it in WHOLE_WORKSPACE_SCOPES } -> SubagentWaveKind.WHOLE_WORKSPACE
            else -> SubagentWaveKind.SCOPED_WRITE
        }
        // 只读任务统一并入首个只读波（SubagentWritePathTest 编码了该语义：只读先行并行、
        // 与写入波隔离避免读到中间态）；声明在写任务之后的只读任务也会前置，
        // 需要校验写入结果的场景应由上层任务拆分时显式声明写路径。
        val joinIndex = waves.indices.firstOrNull { i ->
            when (kind) {
                SubagentWaveKind.READ_ONLY -> waveKinds[i] == SubagentWaveKind.READ_ONLY
                SubagentWaveKind.WHOLE_WORKSPACE -> false
                SubagentWaveKind.SCOPED_WRITE ->
                    waveKinds[i] == SubagentWaveKind.SCOPED_WRITE &&
                        wavePaths[i].none { existing -> paths.any { candidate -> writePathsConflict(existing, candidate) } }
            }
        }
        if (joinIndex != null) {
            waves[joinIndex].add(spec)
            wavePaths[joinIndex].addAll(paths)
        } else {
            waves.add(mutableListOf(spec))
            waveKinds.add(kind)
            wavePaths.add(paths)
        }
    }
    return waves
}

/**
 * 规范化写路径：统一分隔符、按段消解 `.` 与 `..`、去掉首尾斜杠，保证跨子任务的路径比较稳定。
 *
 * 必须按段消解 `..`：只做字符串裁剪时 `docs` 与 `docs/../src` 会被判为互不冲突而排进同一波，
 * 写租约的冲突检测形同虚设；同理租约校验里 `docs/../x` 会因前缀匹配被误放行。
 * （真正写文件时 [WorkspaceFileAccess] 还会拒绝含 `..` 的路径，这里是让租约层自身即可判定。）
 * 逃逸出顶层的 `..` 原样保留，由调用方按越界处理。
 */
internal fun normalizeWritePath(path: String): String {
    val segments = ArrayDeque<String>()
    var escaped = 0
    path.trim().replace('\\', '/').split('/').forEach { segment ->
        when {
            segment.isEmpty() || segment == "." -> Unit
            segment == ".." -> if (segments.isEmpty()) escaped++ else segments.removeLast()
            else -> segments.addLast(segment)
        }
    }
    return "../".repeat(escaped) + segments.joinToString("/")
}

private enum class SubagentWaveKind { READ_ONLY, SCOPED_WRITE, WHOLE_WORKSPACE }

private fun waveLeaseDescription(wave: List<SubagentTaskSpec>): String = when {
    wave.all { it.writePaths.isEmpty() } -> "read-only"
    wave.any { it.writePaths.any(::isWholeWorkspaceWritePath) } -> "whole-workspace exclusive"
    else -> "scoped disjoint write paths"
}

/**
 * 是否为整工作区租约。`*` 与 `.` 都表示整个工作区；`.` 归一化后为空串，
 * 空串同样是"工作区根"，不能当成未声明。
 */
internal fun isWholeWorkspaceWritePath(path: String): Boolean = normalizeWritePath(path) in WHOLE_WORKSPACE_SCOPES

private val WHOLE_WORKSPACE_SCOPES = setOf("*", "")

internal fun writePathsConflict(left: String, right: String): Boolean {
    val a = normalizeWritePath(left)
    val b = normalizeWritePath(right)
    if (a in WHOLE_WORKSPACE_SCOPES || b in WHOLE_WORKSPACE_SCOPES) return true
    return a == b || a.startsWith("$b/") || b.startsWith("$a/")
}

/**
 * 超时取消时 laneRunner 协程被中止，其内存中的工具调用统计随返回值一起丢失；
 * 此时从 lane 已落库的 transcript（tool_call entry）恢复真实计数，
 * 避免汇总里出现"子智能体明明在干活却显示 0 次工具调用"。
 */
internal fun resolveSubagentToolCallCount(
    laneResultToolCalls: Int?,
    persistedTranscript: List<HarnessMessage>,
): Int = laneResultToolCalls ?: persistedTranscript.count { it is ToolCall }

/** 汇总注入父上下文的字符预算；超出即走截断+落盘分页。 */
internal const val SUMMARY_INLINE_BUDGET = 12_000

/** 超限时分任务的截断保留长度。 */
internal const val PER_TASK_INLINE_BUDGET = 3_000

/** 子智能体汇总 Markdown：状态行 + 每任务的输出。 */
internal fun renderSummaryMarkdown(outcomes: List<SubagentOrchestrator.SubagentExecutionOutcome>): String =
    buildString {
        append(subagentBatchHeader(outcomes))
        outcomes.forEachIndexed { index, outcome ->
            append(subagentOutcomeHeader(outcome, index, includeModelBadge = true))
            append("- **子任务输出**：\n")
            append(outcome.summary.trim())
            append("\n\n")
        }
    }

internal fun subagentBatchHeader(outcomes: List<SubagentOrchestrator.SubagentExecutionOutcome>): String {
    val succeeded = outcomes.count { it.isSuccess }
    val batchStatus = when (succeeded) {
        outcomes.size -> "全部成功"
        0 -> "全部失败"
        else -> "部分成功"
    }
    return buildString {
        append("### 🤖 子智能体协同执行完成 · $batchStatus ($succeeded/${outcomes.size})\n\n")
        if (succeeded in 1 until outcomes.size) {
            // 整批 ToolResult 标失败（一个没做完就不能算成功），但重发时不该把已完成的再跑一遍。
            append("（本批部分成功：需要处理的只有下方标 ⚠️ 的子任务。重新派发时只提交这些任务，")
            append("不要重复标 ✅ 的工作；它们的产出已在本汇总中。）\n\n")
        }
    }
}

/**
 * 每个子任务的状态头。
 *
 * 状态不再只有 ✅/⚠️ 两态：未完成时必须写出终止原因、待审批交接与被拦截的写入，
 * 否则父智能体（和用户）无法区分"做完了"与"收场了但没做完"。
 */
internal fun subagentOutcomeHeader(
    outcome: SubagentOrchestrator.SubagentExecutionOutcome,
    index: Int,
    includeModelBadge: Boolean,
): String = buildString {
    val statusIcon = if (outcome.isSuccess) "✅" else "⚠️"
    val resolvedRole = if (outcome.resolvedProfileId != null) {
        "${outcome.resolvedProfileName} · ${outcome.resolvedProfileId}"
    } else {
        outcome.spec.role.ifBlank { "${outcome.spec.department} / ${outcome.spec.agentQuery}" }
    }
    val modelBadge = if (includeModelBadge) outcome.resolvedModel?.let { " · 专用模型: $it" } ?: "" else ""
    append("#### ${index + 1}. $statusIcon 【${outcome.spec.taskName}】(角色: $resolvedRole$modelBadge)\n")
    append("- **工具调用次数**：${outcome.toolCallCount} 次\n")
    // task_id 暴露给父智能体：未完结的子任务可凭它在同一 Lane 上续跑（对齐 opencode task 工具）。
    if (outcome.subSessionId.isNotBlank()) {
        append("- **task_id**（续跑用，原样复制到 invoke_subagent 的 task_id 字段）: ${outcome.subSessionId}\n")
    }
    // 委托经济学：把成本摊开给父智能体与用户看——缓存命中率低的大输入是委派不划算的主要信号。
    outcome.tokenUsage?.let { usage ->
        append(
            "- **Token 用量**：输入 ${usage.promptTokens}（缓存命中 ${usage.cachedTokens}，" +
                "${usage.cacheHitPercent}%）· 输出 ${usage.completionTokens}\n",
        )
    }
    if (!outcome.isSuccess) {
        append("- **终止原因**：${subagentTerminationLabel(outcome.termination)}\n")
    }
    // 完成 claim 裁定：无论最终状态如何都展示——✅ 也让父智能体看到"凭据背书了什么"。
    if (outcome.claimAdjudication != null) {
        append(renderSubagentClaimAdjudication(outcome.claimAdjudication))
    }
    if (outcome.pendingApprovals.isNotEmpty()) {
        append("- **待主智能体重新发起并审批**：")
        append(outcome.pendingApprovals.joinToString("、") { it.toolName })
        append("\n")
    }
    if (outcome.blockedWrites.isNotEmpty()) {
        append("- **被写租约拦截、未落盘**：${outcome.blockedWrites.joinToString("、")}\n")
    }
    if (outcome.readOnlyWriteIntent) {
        append("- **写租约缺失**：任务要求落盘但未声明 write_paths，本次按只读执行；产物在正文中，未写入文件\n")
    }
    if (outcome.suspectedShellWrites.isNotEmpty()) {
        append("- **疑似 shell 写入（仅提示，未自动判定越界）**：")
        append(outcome.suspectedShellWrites.joinToString("；"))
        append("；请由主智能体复核实际产物与写租约范围。\n")
    }
}

private fun subagentTerminationLabel(termination: SubagentTermination): String = when (termination) {
    SubagentTermination.CONCLUDED -> "已产出结论"
    SubagentTermination.INCOMPLETE -> "收场但未产出可信结论"
    SubagentTermination.NEEDS_APPROVAL -> "有需要审批的操作未执行"
    SubagentTermination.WRITE_SCOPE_BLOCKED -> "写入被写租约拦截"
    SubagentTermination.WRITE_FAILED -> "写入未确认成功，需核验实际状态"
    SubagentTermination.UNPARSEABLE_TOOL_CALL -> "文本工具调用无法解析"
    SubagentTermination.MAX_ROUNDS -> "用尽工具轮数预算"
    SubagentTermination.FAILED -> "执行异常"
    SubagentTermination.TIMEOUT -> "执行超时"
    SubagentTermination.CLAIM_DOWNGRADED -> "自报 complete 未被 host 凭据完全背书，降级为 partial"
}
