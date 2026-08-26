package top.wkbin.taixu.harness.operation

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
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

/** Owns all durable operation transitions and their transaction boundaries. */
@Singleton
class OperationCoordinator @Inject constructor(
    private val repository: HarnessRuntimeRepository,
    private val json: Json,
    private val eventBus: HarnessEventBus,
) {
    suspend fun acceptRun(sessionId: String, userMessage: HarnessMessage, laneName: String = SessionTreeStore.MAIN_LANE): String {
        val lane = reclaimInterruptedLane(sessionId, laneName)
        check(lane.currentOperationId == null) { "Lane ${lane.name} is busy" }
        val now = System.currentTimeMillis()
        val operationId = UUID.randomUUID().toString()
        val operation = newOperation(operationId, sessionId, lane, now)
        val entry = messageEntry(sessionId, lane.leafId, userMessage)
        repository.acceptOperation(
            entry = entry,
            lane = lane.copy(leafId = entry.id, currentOperationId = operationId, updatedAt = now),
            operation = operation,
        )
        return operationId
    }

    suspend fun acceptQueuedRun(sessionId: String, queueItemId: String, userMessage: HarnessMessage): String {
        val lane = reclaimInterruptedLane(sessionId, SessionTreeStore.MAIN_LANE)
        check(lane.currentOperationId == null) { "Lane ${lane.name} is busy" }
        val now = System.currentTimeMillis()
        val operationId = UUID.randomUUID().toString()
        val operation = newOperation(operationId, sessionId, lane, now)
        val entry = messageEntry(sessionId, lane.leafId, userMessage)
        repository.acceptQueuedOperation(
            queueItemId = queueItemId,
            entry = entry,
            lane = lane.copy(leafId = entry.id, currentOperationId = operationId, updatedAt = now),
            operation = operation,
        )
        return operationId
    }

    suspend fun beginRun(sessionId: String, laneName: String = SessionTreeStore.MAIN_LANE): String {
        val lane = repository.ensureLane(sessionId, laneName)
        lane.currentOperationId?.let { return it }
        val now = System.currentTimeMillis()
        val operationId = UUID.randomUUID().toString()
        repository.beginOperation(
            lane.copy(currentOperationId = operationId, updatedAt = now),
            newOperation(operationId, sessionId, lane, now),
        )
        return operationId
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

    suspend fun toolIntent(operationId: String, message: HarnessMessage, payloadJson: String, replay: ReplayPolicy, round: Int) {
        val snapshot = OperationSnapshot(
            phase = OperationPhase.TOOL_INTENT.id,
            round = round,
            effectKind = "tool",
            effectId = message.id,
            effectPayloadJson = payloadJson,
            replayPolicy = replay.id,
        )
        settle(operationId, message, null, snapshot, replay)
        emitFor(operationId) { sessionId, timestamp, _ ->
            val toolCall = message as? top.wkbin.taixu.harness.ToolCall
            HarnessEvent.ToolCallStarted(sessionId, timestamp, operationId, message.id, toolCall?.rawToolName ?: "tool")
        }
    }

    suspend fun toolSettled(operationId: String, message: HarnessMessage, round: Int) {
        settle(
            operationId = operationId,
            message = message,
            usage = null,
            snapshot = OperationSnapshot(phase = OperationPhase.TOOL_SETTLED.id, round = round),
        )
        emitFor(operationId) { sessionId, timestamp, _ ->
            val result = message as? top.wkbin.taixu.harness.ToolResult
            HarnessEvent.ToolCallSettled(
                sessionId, timestamp, operationId,
                toolCallId = result?.toolCallId ?: message.id,
                toolName = result?.toolCallId ?: "tool",
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
        val snapshot = decode(current).copy(lastError = reason)
        transition(operationId, OperationStatus.SUSPENDED, snapshot, current.replayPolicy?.let(::replayPolicy))
    }

    suspend fun finish(sessionId: String, outcome: String, finalEntryId: String? = null, details: String? = null, laneName: String = SessionTreeStore.MAIN_LANE) {
        val lane = repository.ensureLane(sessionId, laneName)
        val operationId = lane.currentOperationId ?: return
        val now = System.currentTimeMillis()
        repository.finishOperation(
            HarnessLaneResultEntity(sessionId, lane.name, operationId, outcome, finalEntryId, details, now),
            lane.copy(currentOperationId = null, updatedAt = now),
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

    /**
     * A lane whose current operation is no longer attached to a live in-process run is a
     * leftover: the process died mid-run (recovery suspended it), or its approval wait was
     * abandoned. Finish it as aborted so the next send can start a new operation instead of
     * failing with "lane busy" and silently dropping the message.
     *
     * In normal operation a waiting-approval lane is never reached here: the run state gate
     * routes sends to the queue while an approval is pending.
     */
    private suspend fun reclaimInterruptedLane(sessionId: String, laneName: String): HarnessLaneEntity {
        val lane = repository.ensureLane(sessionId, laneName)
        val staleOperationId = lane.currentOperationId ?: return lane
        val staleOperation = repository.findOperation(staleOperationId)
        return when {
            // Dangling pointer without a live operation row: just clear it.
            staleOperation == null -> {
                repository.clearLaneOperation(sessionId, laneName)
                lane.copy(currentOperationId = null)
            }
            else -> {
                finish(
                    sessionId,
                    "aborted",
                    details = "上次运行未完成（进程中断或审批等待失效），已被新请求接管",
                    laneName = laneName,
                )
                repository.ensureLane(sessionId, laneName)
            }
        }
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
            status = OperationStatus.RUNNING.id,
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
    is top.wkbin.taixu.harness.UserMessage -> "user"
    is top.wkbin.taixu.harness.AssistantText -> "assistant"
    is top.wkbin.taixu.harness.ToolCall -> "tool_call"
    is top.wkbin.taixu.harness.ToolResult -> "tool_result"
    is top.wkbin.taixu.harness.CapabilityEvent -> "capability_event"
}
