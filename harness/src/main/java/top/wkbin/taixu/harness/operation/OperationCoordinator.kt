package top.wkbin.taixu.harness.operation

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.database.HarnessEntryEntity
import top.wkbin.taixu.core.database.HarnessLaneEntity
import top.wkbin.taixu.core.database.HarnessLaneResultEntity
import top.wkbin.taixu.core.database.HarnessOperationEntity
import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.core.database.HarnessUsageEntity
import top.wkbin.taixu.harness.ChatUsage
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.events.HarnessEvent
import top.wkbin.taixu.harness.events.HarnessEventBus
import top.wkbin.taixu.harness.session.SessionTreeStore
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.ModelSwitchEvent
import top.wkbin.taixu.harness.SkillSuggestion
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage

/** Owns all durable operation transitions and their transaction boundaries. */
class OperationCoordinator(
    private val repository: HarnessRuntimeRepository,
    private val json: Json,
    private val eventBus: HarnessEventBus,
) {
    /**
     * 串行化 accept 类入口的 check-then-act：并发 accept 同一 lane 时，
     * “检查 currentOperationId == null → 写入”之间无保护会产生孤儿 RUNNING 操作。
     * 用 Mutex 而非 synchronized：临界区内含 suspend 的 repository 调用，不能阻塞线程。
     */
    private val acceptMutex = Mutex()

    suspend fun acceptRun(sessionId: String, userMessage: HarnessMessage, laneName: String = SessionTreeStore.MAIN_LANE,
        taskId: String? = null): String = acceptMutex.withLock {
        acceptInput(sessionId, userMessage, laneName, taskId, queueItemId = null)
    }

    suspend fun acceptQueuedRun(sessionId: String, queueItemId: String, userMessage: HarnessMessage,
        taskId: String? = null): String = acceptMutex.withLock {
        acceptInput(sessionId, userMessage, SessionTreeStore.MAIN_LANE, taskId, queueItemId)
    }

    /** Caller has gated live jobs/approvals; the remaining operation is an interrupted leftover. */
    private suspend fun acceptInput(sessionId: String, userMessage: HarnessMessage?, laneName: String,
        taskId: String?, queueItemId: String?, reuseActive: Boolean = false): String {
        val initial = repository.ensureLane(sessionId, laneName)
        val previous = initial.currentOperationId?.let { repository.findOperation(it) }
        if (reuseActive && previous != null && previous.status != OperationStatus.SUSPENDED.id) return previous.id
        if (previous != null) settleInterruptedTool(previous.id)
        val lane = repository.ensureLane(sessionId, laneName)
        check(lane.currentOperationId == initial.currentOperationId) { "Lane changed during input takeover" }
        val now = System.currentTimeMillis()
        val operationId = UUID.randomUUID().toString()
        val operation = newOperation(operationId, sessionId, lane, now)
        val entry = userMessage?.let { messageEntry(sessionId, lane.leafId, it) }
        val acceptedLane = lane.copy(leafId = entry?.id ?: lane.leafId, currentOperationId = operationId, updatedAt = now, faulted = false)
        val result = previous?.let { HarnessLaneResultEntity(sessionId, lane.name, it.id, "aborted", null,
            "上次运行未完成（进程中断或审批等待失效），已被新请求接管", now) }
        repository.acceptRunTakeover(queueItemId, entry, acceptedLane, operation, lane, result, taskId)
        if (result != null) eventBus.emit(HarnessEvent.OperationFinished(sessionId, now, result.operationId,
            lane.name, result.outcome, result.detailsJson))
        eventBus.emit(HarnessEvent.OperationStarted(sessionId, now, operationId, laneName))
        return operationId
    }

    suspend fun beginRun(sessionId: String, laneName: String = SessionTreeStore.MAIN_LANE): String = acceptMutex.withLock {
        acceptInput(sessionId, null, laneName, taskId = null, queueItemId = null, reuseActive = true)
    }

    suspend fun providerIntent(operationId: String, effectId: String, round: Int, attempt: Int, maxAttempts: Int) {
        transition(
            operationId,
            OperationStatus.RUNNING,
            OperationSnapshot(
                phase = OperationPhase.PROVIDER_INTENT.id,
                round = round,
                effectKind = "provider",
                effectId = effectId,
                reservedEntryId = effectId,
                attempt = attempt,
                maxAttempts = maxAttempts,
            ),
            ReplayPolicy.NEVER,
        )
        emitFor(operationId) { sessionId, timestamp, _ ->
            HarnessEvent.ProviderRoundStarted(sessionId, timestamp, operationId, round, attempt)
        }
    }

    suspend fun providerSettled(operationId: String, message: HarnessMessage?, usage: HarnessUsageEntity? = null, round: Int) {
        settle(
            operationId = operationId,
            message = message,
            usage = usage,
            snapshot = OperationSnapshot(phase = OperationPhase.PROVIDER_SETTLED.id, round = round),
        )
        emitFor(operationId) { sessionId, timestamp, _ ->
            HarnessEvent.ProviderRoundSettled(
                sessionId, timestamp, operationId, round,
                entryId = message?.id,
                inputTokens = usage?.inputTokens ?: 0,
                outputTokens = usage?.outputTokens ?: 0,
            )
        }
    }

    suspend fun toolIntent(
        operationId: String, message: HarnessMessage, payloadJson: String, replay: ReplayPolicy, round: Int,
        persistMessage: Boolean = true,
    ) {
        val snapshot = OperationSnapshot(
            phase = OperationPhase.TOOL_INTENT.id,
            round = round,
            effectKind = "tool",
            effectId = message.id,
            effectPayloadJson = payloadJson,
            replayPolicy = replay.id,
        )
        // Approval replay reuses the frozen call; persist its new execution intent without duplicating the entry.
        settle(operationId, message.takeIf { persistMessage }, null, snapshot, replay)
        emitFor(operationId) { sessionId, timestamp, _ ->
            val toolCall = message as? ToolCall
            HarnessEvent.ToolCallStarted(
                sessionId, timestamp, operationId,
                toolCallId = message.id,
                toolName = toolCall?.rawToolName ?: "tool",
            )
        }
    }

    suspend fun toolSettled(operationId: String, message: HarnessMessage, round: Int, toolName: String? = null) {
        settle(
            operationId = operationId,
            message = message,
            usage = null,
            snapshot = OperationSnapshot(phase = OperationPhase.TOOL_SETTLED.id, round = round),
        )
        emitFor(operationId) { sessionId, timestamp, _ ->
            val result = message as? ToolResult
            HarnessEvent.ToolCallSettled(
                sessionId, timestamp, operationId,
                toolCallId = result?.toolCallId ?: message.id,
                toolName = toolName ?: "tool",
                success = result?.success ?: true,
                durationMs = result?.durationMs,
            )
        }
    }

    suspend fun waitingApproval(operationId: String) {
        val current = requireOperation(operationId)
        val snapshot = decode(current).copy(phase = OperationPhase.WAITING_APPROVAL.id)
        transition(operationId, OperationStatus.WAITING_APPROVAL, snapshot, current.replayPolicy?.let(::replayPolicy))
    }

    suspend fun suspendOperation(operationId: String, reason: String) {
        val current = requireOperation(operationId)
        // 状态 JSON 可能已损坏（半截写入）；损坏时保留原 stateJson，只更新 lastError 不可行
        // （snapshot 序列化字段固定），因此降级为携带 reason 的最小快照，保证挂起事务总能落盘。
        val snapshot = runCatching { decode(current) }
            .getOrElse { OperationSnapshot(phase = current.phase, lastError = reason) }
            .copy(lastError = reason)
        transition(operationId, OperationStatus.SUSPENDED, snapshot, current.replayPolicy?.let(::replayPolicy))
    }

    suspend fun finish(sessionId: String, outcome: String, finalEntryId: String? = null, details: String? = null, laneName: String = SessionTreeStore.MAIN_LANE) {
        val lane = repository.ensureLane(sessionId, laneName)
        val operationId = lane.currentOperationId ?: return
        val now = System.currentTimeMillis()
        // faulted 必须在这里落盘：LaneManager.branches() 用它给子智能体 lane 的圆点着色
        // （红=中断 / 绿=正常），而此前全仓库没有写入点，字段恒为 false，于是"批次汇总判失败、
        // 每个子任务圆点却全是绿色"。每次收尾都按本轮结果整体覆盖，成功即自动清除上一轮的标记。
        // 只把 "failed" 视为中断："aborted" 同时被用户主动停止与进程中断复用，
        // 计入会把"用户点了停止"的主线也标成故障。
        repository.finishOperation(
            HarnessLaneResultEntity(sessionId, lane.name, operationId, outcome, finalEntryId, details, now),
            lane.copy(currentOperationId = null, updatedAt = now, faulted = outcome == "failed"),
        )
        eventBus.emit(HarnessEvent.OperationFinished(sessionId, now, operationId, laneName, outcome, details))
    }

    suspend fun active(sessionId: String, laneName: String = SessionTreeStore.MAIN_LANE): HarnessOperationEntity? {
        // lane 指针是权威来源：优先取 currentOperationId 指向的操作，
        // 避免历史遗留的活动行（如等待审批期间被接管的旧操作）抢占判定。
        val lane = repository.findLane(sessionId, laneName) ?: return null
        lane.currentOperationId?.let { id -> repository.findOperation(id)?.let { return it } }
        return repository.listActiveOperations(sessionId).firstOrNull { it.laneName == laneName }
    }

    suspend fun operationExists(operationId: String): Boolean = repository.findOperation(operationId) != null

    /** Must complete before a takeover/finish can clear the lane's recovery pointer. */
    internal suspend fun settleInterruptedTool(operationId: String): String? {
        val repair = interruptedToolSettlement(repository, json, requireOperation(operationId)) ?: return null
        toolSettled(operationId, repair.result, repair.round, toolName = repair.toolName)
        return repair.result.output
    }

    /** Builds an append-only ledger row from provider-reported usage. */
    fun usageEntity(
        sessionId: String,
        operationId: String,
        entryId: String?,
        provider: String?,
        modelId: String?,
        usage: ChatUsage,
    ): HarnessUsageEntity = HarnessUsageEntity(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        operationId = operationId,
        entryId = entryId,
        provider = provider,
        modelId = modelId,
        inputTokens = usage.inputTokens,
        outputTokens = usage.outputTokens,
        reasoningTokens = usage.reasoningTokens,
        cacheReadTokens = usage.cacheReadTokens,
        cacheWriteTokens = usage.cacheWriteTokens,
        createdAt = System.currentTimeMillis(),
    )

    private suspend fun settle(
        operationId: String,
        message: HarnessMessage?,
        usage: HarnessUsageEntity?,
        snapshot: OperationSnapshot,
        replay: ReplayPolicy? = null,
    ) {
        val current = requireOperation(operationId)
        val lane = repository.findLane(current.sessionId, current.laneName) ?: error("Missing lane ${current.laneName}")
        val entry = message?.let { messageEntry(current.sessionId, lane.leafId, it) }
        val now = System.currentTimeMillis()
        val next = current.copy(
            status = if (current.status == OperationStatus.SUSPENDED.id && snapshot.phase == OperationPhase.TOOL_SETTLED.id)
                current.status else OperationStatus.RUNNING.id,
            phase = snapshot.phase,
            updatedAt = now,
            stateJson = json.encodeToString(OperationSnapshot.serializer(), snapshot),
            pendingEffectKind = snapshot.effectKind,
            pendingEffectId = snapshot.effectId,
            replayPolicy = replay?.id,
            attempt = snapshot.attempt,
        )
        repository.settleEffect(entry, usage, next, lane.copy(leafId = entry?.id ?: lane.leafId, updatedAt = now))
    }

    private suspend fun transition(operationId: String, status: OperationStatus, snapshot: OperationSnapshot, replay: ReplayPolicy?) {
        val current = requireOperation(operationId)
        repository.saveOperation(
            current.copy(
                status = status.id,
                phase = snapshot.phase,
                updatedAt = System.currentTimeMillis(),
                stateJson = json.encodeToString(OperationSnapshot.serializer(), snapshot),
                pendingEffectKind = snapshot.effectKind,
                pendingEffectId = snapshot.effectId,
                replayPolicy = replay?.id,
                attempt = snapshot.attempt,
            ),
        )
    }

    private suspend fun requireOperation(operationId: String) =
        repository.findOperation(operationId) ?: error("Missing harness operation $operationId")

    /** 从 operation 行补齐事件路由键；operation 已被清理时静默跳过（事件尽力投递）。 */
    private suspend fun emitFor(operationId: String, build: (sessionId: String, timestamp: Long, laneName: String) -> HarnessEvent) {
        val operation = repository.findOperation(operationId) ?: return
        eventBus.emit(build(operation.sessionId, System.currentTimeMillis(), operation.laneName))
    }

    private fun newOperation(id: String, sessionId: String, lane: HarnessLaneEntity, now: Long): HarnessOperationEntity {
        val snapshot = OperationSnapshot(phase = OperationPhase.CHECKPOINT.id)
        return HarnessOperationEntity(
            id = id,
            sessionId = sessionId,
            laneName = lane.name,
            kind = OperationKind.RUN.id,
            status = OperationStatus.RUNNING.id,
            phase = snapshot.phase,
            startedAt = now,
            updatedAt = now,
            startLeafId = lane.leafId,
            stateJson = json.encodeToString(OperationSnapshot.serializer(), snapshot),
        )
    }

    private fun messageEntry(sessionId: String, parentId: String?, message: HarnessMessage) = HarnessEntryEntity(
        id = message.id,
        sessionId = sessionId,
        parentId = parentId,
        createdAt = message.createdAt,
        entryType = "message",
        customType = message.serialType(),
        payloadJson = json.encodeToString(HarnessMessage.serializer(), message),
    )

    private fun decode(operation: HarnessOperationEntity): OperationSnapshot =
        json.decodeFromString(OperationSnapshot.serializer(), operation.stateJson)

    private fun replayPolicy(id: String): ReplayPolicy = ReplayPolicy.entries.first { it.id == id }
}

private fun HarnessMessage.serialType(): String = when (this) {
    is UserMessage -> "user"
    is AssistantText -> "assistant"
    is ToolCall -> "tool_call"
    is ToolResult -> "tool_result"
    is CapabilityEvent -> "capability_event"
    is SkillSuggestion -> "skill_suggestion"
    is ModelSwitchEvent -> "model_switch"
}
