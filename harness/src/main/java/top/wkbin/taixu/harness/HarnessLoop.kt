package top.wkbin.taixu.harness

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.HarnessSessionEntity
import top.wkbin.taixu.core.model.SessionRunState
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.wkbin.taixu.harness.validation.ToolSchemaValidator

import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.harness.session.SessionTreeStore
import top.wkbin.taixu.harness.effects.ToolReplayPolicy
import top.wkbin.taixu.harness.effects.RetryPolicy
import top.wkbin.taixu.harness.operation.OperationCoordinator
import top.wkbin.taixu.harness.recovery.RecoveryManager
import top.wkbin.taixu.harness.recovery.RecoveryOutcome
import top.wkbin.taixu.harness.queue.PromptQueue
import top.wkbin.taixu.harness.queue.PromptQueueManager
import top.wkbin.taixu.harness.compaction.CompactionManager

/** Agent 单次运行的结构化结果，外层据此设置会话状态，避免内部失败被误标为 COMPLETED。 */
private sealed interface RunResult {
    data object Completed : RunResult
    data object WaitingApproval : RunResult
    data object Cancelled : RunResult
    data class Failed(val message: String) : RunResult
}

/**
 * Harness 多智能体会话并发引擎：
 * 支持多会话后台并行运行、实时状态机追踪（就绪/运行中/完成/失败）、
 * 独立的流式消息队列与前台服务多通知分发。
 */
@Singleton
class HarnessLoop @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providerClient: ProviderClient,
    private val toolExecutor: ToolExecutor,
    private val messageStore: SessionTreeStore,
    private val sessionDao: HarnessSessionRepository,
    private val toolRepository: top.wkbin.taixu.core.tools.ToolRepository,
    private val agentContextDao: top.wkbin.taixu.core.database.AgentContextRepository,
    private val settingsDataStore: AgentPreferences,
    private val fileAccess: WorkspaceFileAccess,
    private val json: Json,
    private val logger: AppLogger,
    private val subagentRepository: top.wkbin.taixu.core.database.AgentSubagentRepository,
    private val skillRepository: AgentSkillRepository,
    private val mcpServerRepository: top.wkbin.taixu.core.database.McpServerRepository,
    private val approvalRepository: top.wkbin.taixu.core.database.AgentApprovalRepository,
    private val operationCoordinator: OperationCoordinator,
    private val recoveryManager: RecoveryManager,
    private val promptQueueManager: PromptQueueManager,
    private val compactionManager: CompactionManager,
) {
    private val loopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _currentSessionId = MutableStateFlow("")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    private val sessionJobs = ConcurrentHashMap<String, Job>()
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()
    /** Sessions being deleted; reject new runs and skip pending drainage. */
    private val tombstonedSessions = ConcurrentHashMap.newKeySet<String>()
    private val recoveredSessions = ConcurrentHashMap.newKeySet<String>()

    private val _sessionRunStates = MutableStateFlow<Map<String, SessionRunState>>(emptyMap())
    /** 全局所有会话的运行状态映射（供会话抽屉、状态点等观察） */
    val sessionRunStates: StateFlow<Map<String, SessionRunState>> = _sessionRunStates.asStateFlow()

    private val _sessionStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 全局各会话当前的动作描述状态 */
    val sessionStatuses: StateFlow<Map<String, String>> = _sessionStatuses.asStateFlow()

    private val _sessionLiveMessages = ConcurrentHashMap<String, MutableStateFlow<List<HarnessMessage>>>()
    private val _sessionPendingMessages = ConcurrentHashMap<String, MutableStateFlow<List<PendingMessage>>>()
    private val _sessionThinkingLives = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()
    private val _sessionErrors = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    private val sessionThinkingModes = ConcurrentHashMap<String, Boolean>()

    // ---- 当前前台聚焦会话的响应式状态镜像 ----
    private val _messages = MutableStateFlow<List<HarnessMessage>>(emptyList())
    val messages: StateFlow<List<HarnessMessage>> = _messages.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _workspace = MutableStateFlow("")
    /** 当前会话关联的工作区 Linux 路径（"" = 未关联）。 */
    val workspace: StateFlow<String> = _workspace.asStateFlow()

    private val _projectType = MutableStateFlow("")
    /** 当前会话显式选择的工程类型；空值表示由工作区内容自动识别。 */
    val projectType: StateFlow<String> = _projectType.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    /** 当前执行状态（供 UI / 后台通知显示进度）。运行结束或出错时置空。 */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _thinkingLive = MutableStateFlow(false)
    /** 推理模型思考中（reasoning 正在流式上屏）。开始思考置 true，本回合结束时置 false。 */
    val thinkingLive: StateFlow<Boolean> = _thinkingLive.asStateFlow()

    private val _pendingMessages = MutableStateFlow<List<PendingMessage>>(emptyList())
    /**
     * 运行中排队等待发送的用户消息。当前任务结束后自动按序接续执行；
     * 用户点"停止"时清空。UI 可观察此列表展示排队状态。
     */
    val pendingMessages: StateFlow<List<PendingMessage>> = _pendingMessages.asStateFlow()

    private fun getOrCreateLiveMessages(sessId: String): MutableStateFlow<List<HarnessMessage>> {
        return _sessionLiveMessages.getOrPut(sessId) {
            val flow = MutableStateFlow<List<HarnessMessage>>(emptyList())
            loopScope.launch(Dispatchers.IO) {
                val history = readHistory(sessId)
                // Merge history with messages appended while the asynchronous read was running,
                // instead of discarding the whole history when the flow is no longer empty.
                flow.update { current ->
                    if (current.isEmpty()) {
                        history
                    } else {
                        val historyIds = history.mapTo(mutableSetOf()) { it.id }
                        (history + current.filter { it.id !in historyIds })
                            .sortedBy { it.createdAt }
                    }
                }
                if (sessId == _currentSessionId.value) {
                    _messages.value = flow.value
                }
            }
            flow
        }
    }

    private suspend fun readHistory(sessId: String): List<HarnessMessage> =
        withContext(Dispatchers.IO) { messageStore.load(sessId) }

    private fun getOrCreatePendingFlow(sessId: String): MutableStateFlow<List<PendingMessage>> {
        return _sessionPendingMessages.getOrPut(sessId) { MutableStateFlow(emptyList()) }
    }

    private fun getOrCreateThinkingLiveFlow(sessId: String): MutableStateFlow<Boolean> {
        return _sessionThinkingLives.getOrPut(sessId) { MutableStateFlow(false) }
    }

    private fun getOrCreateErrorFlow(sessId: String): MutableStateFlow<String?> {
        return _sessionErrors.getOrPut(sessId) { MutableStateFlow(null) }
    }

    private fun setStatus(sessId: String, statusText: String?) {
        _sessionStatuses.update { map ->
            if (statusText.isNullOrBlank()) map - sessId else map + (sessId to statusText)
        }
        if (sessId == _currentSessionId.value) {
            _status.value = statusText
        }
    }

    private fun setError(sessId: String, errorText: String?) {
        getOrCreateErrorFlow(sessId).value = errorText
        if (sessId == _currentSessionId.value) {
            _error.value = errorText
        }
    }

    private fun setSessionState(sessId: String, state: SessionRunState) {
        _sessionRunStates.update { it + (sessId to state) }
        if (sessId == _currentSessionId.value) {
            _running.value = (state == SessionRunState.RUNNING)
        }
    }

    private fun isSessionBusy(sessId: String): Boolean =
        sessionJobs[sessId]?.isActive == true ||
            _sessionRunStates.value[sessId] == SessionRunState.WAITING_APPROVAL

    /** 新建会话。workspace 为关联的工作区 Linux 路径（如 /workspace/proj），空串表示不关联。 */
    suspend fun newSession(title: String, workspace: String = "", projectType: String = ""): String {
        val id = UUID.randomUUID().toString()
        tombstonedSessions.remove(id)
        _currentSessionId.value = id
        _workspace.value = workspace
        _projectType.value = projectType
        sessionDao.upsert(
            HarnessSessionEntity(
                id = id,
                title = title.ifBlank { "新会话" },
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                modelId = null,
                workspace = workspace,
                projectType = projectType,
                approvalMode = approvalRepository.currentMode().id,
            ),
        )
        _sessionLiveMessages[id] = MutableStateFlow(emptyList())
        _sessionPendingMessages[id] = MutableStateFlow(emptyList())
        _sessionThinkingLives[id] = MutableStateFlow(false)
        _sessionErrors[id] = MutableStateFlow(null)
        setSessionState(id, SessionRunState.IDLE)
        setStatus(id, null)

        _messages.value = emptyList()
        _running.value = false
        _error.value = null
        _status.value = null
        _thinkingLive.value = false
        _pendingMessages.value = emptyList()
        return id
    }

    /** 恢复已有会话的历史消息与工作区关联，不中断正在后台运行的任何会话。 */
    suspend fun loadSession(id: String) {
        _currentSessionId.value = id
        val sessionEntity = withContext(Dispatchers.IO) { sessionDao.findById(id) }
        _workspace.value = sessionEntity?.workspace.orEmpty()
        _projectType.value = sessionEntity?.projectType.orEmpty()

        val liveFlow = _sessionLiveMessages[id] ?: run {
            val history = readHistory(id)
            MutableStateFlow(history).also { created ->
                _sessionLiveMessages.putIfAbsent(id, created)
            }
        }

        _messages.value = liveFlow.value
        _running.value = sessionJobs[id]?.isActive == true
        _error.value = _sessionErrors[id]?.value
        _status.value = _sessionStatuses.value[id]?.takeIf { it.isNotBlank() }
        _thinkingLive.value = _sessionThinkingLives[id]?.value ?: false
        val persistedPending = promptQueueManager.list(id, PromptQueue.NEXT_RUN).map { it.second }
        getOrCreatePendingFlow(id).value = persistedPending
        _pendingMessages.value = persistedPending

        if (withContext(Dispatchers.IO) { approvalRepository.pendingNow(id).isNotEmpty() }) {
            setSessionState(id, SessionRunState.WAITING_APPROVAL)
            setStatus(id, "等待用户批准")
        }

        sessionThinkingModes[id] = liveFlow.value.any { message ->
            (message as? AssistantText)?.reasoning != null || (message as? ToolCall)?.reasoning != null
        }
        if (sessionJobs[id]?.isActive != true && recoveredSessions.add(id)) {
            when (val recovery = recoveryManager.recoverSession(id)) {
                RecoveryOutcome.Clean -> Unit
                RecoveryOutcome.WaitingApproval -> {
                    setSessionState(id, SessionRunState.WAITING_APPROVAL)
                    setStatus(id, "等待用户批准")
                }
                is RecoveryOutcome.ToolInterrupted -> {
                    val restored = readHistory(id)
                    liveFlow.value = restored
                    if (id == _currentSessionId.value) _messages.value = restored
                    setSessionState(id, SessionRunState.IDLE)
                    setStatus(id, "上次工具执行被中断，发送消息即可继续")
                }
                is RecoveryOutcome.Suspended -> {
                    setSessionState(id, SessionRunState.IDLE)
                    setStatus(id, "上次运行已暂停（${recovery.reason}），发送消息即可重新开始")
                }
            }
        }
    }

    suspend fun renameSession(id: String, title: String) {
        sessionDao.rename(id, title, System.currentTimeMillis())
    }

    suspend fun deleteSession(id: String) {
        // Mark tombstoned first so finishRun on the dying job cannot drain pending
        // messages and start a fresh run after we have already begun cleanup.
        tombstonedSessions.add(id)
        _sessionPendingMessages[id]?.value = emptyList()
        sessionJobs[id]?.cancelAndJoin()
        _sessionLiveMessages.remove(id)
        _sessionPendingMessages.remove(id)
        _sessionThinkingLives.remove(id)
        _sessionErrors.remove(id)
        sessionMutexes.remove(id)
        _sessionRunStates.update { it - id }
        _sessionStatuses.update { it - id }

        messageStore.deleteSession(id)
        approvalRepository.deleteForSession(id)
        sessionDao.deleteSession(id)
        tombstonedSessions.remove(id)
        if (_currentSessionId.value == id) {
            val remaining = sessionDao.observeAll().first()
            val nextSession = remaining.firstOrNull { it.id != id }
            if (nextSession != null) {
                loadSession(nextSession.id)
            } else {
                newSession("新会话")
            }
        }
    }

    fun send(text: String, targetSessionId: String? = null, imageUrls: List<String> = emptyList()) {
        val trimmed = text.trim()
        val sessId = targetSessionId?.ifBlank { null } ?: _currentSessionId.value
        if (trimmed.isEmpty() && imageUrls.isEmpty()) return
        if (sessId.isBlank()) return

        val pending = PendingMessage(text = trimmed, imageUrls = imageUrls)
        startSessionRun(sessId, enqueueOnBusy = pending) {
            runLoop(sessId, pending.text, pending.imageUrls)
        }
        startForegroundServiceSafe()
    }

    fun steer(text: String, targetSessionId: String? = null, imageUrls: List<String> = emptyList()) {
        enqueueExplicit(PromptQueue.STEER, text, targetSessionId, imageUrls)
    }

    fun followUp(text: String, targetSessionId: String? = null, imageUrls: List<String> = emptyList()) {
        enqueueExplicit(PromptQueue.FOLLOW_UP, text, targetSessionId, imageUrls)
    }

    private fun enqueueExplicit(queue: PromptQueue, text: String, targetSessionId: String?, imageUrls: List<String>) {
        val sessId = targetSessionId?.ifBlank { null } ?: _currentSessionId.value
        val trimmed = text.trim()
        if (sessId.isBlank() || (trimmed.isBlank() && imageUrls.isEmpty())) return
        if (!isSessionBusy(sessId)) {
            send(trimmed, sessId, imageUrls)
            return
        }
        loopScope.launch {
            promptQueueManager.enqueue(sessId, queue, PendingMessage(trimmed, imageUrls))
        }
    }

    /**
     * 重新生成最后一次回复
     */
    fun regenerateLast(targetSessionId: String? = null) {
        val sessId = targetSessionId?.ifBlank { null } ?: _currentSessionId.value
        if (sessId.isBlank()) return

        startSessionRun(sessId) {
            val current = getOrCreateLiveMessages(sessId).value
            val lastUserIndex = current.indexOfLast { it is UserMessage }
            if (lastUserIndex < 0) return@startSessionRun RunResult.Completed
            val lastUserMessage = current[lastUserIndex] as UserMessage
            val toKeep = current.subList(0, lastUserIndex + 1)
            val liveFlow = getOrCreateLiveMessages(sessId)
            liveFlow.value = toKeep
            if (sessId == _currentSessionId.value) {
                _messages.value = toKeep
            }
            messageStore.moveTo(sessId, lastUserMessage.id)
            runLoopInternal(sessId, startedAt = now())
        }
        startForegroundServiceSafe()
    }

    /**
     * 编辑并重发指定用户消息
     */
    fun truncateAndResend(userMessageId: String, newText: String, targetSessionId: String? = null) {
        val trimmed = newText.trim()
        val sessId = targetSessionId?.ifBlank { null } ?: _currentSessionId.value
        if (trimmed.isEmpty() || sessId.isBlank()) return

        startSessionRun(sessId) {
            val current = getOrCreateLiveMessages(sessId).value
            val targetIndex = current.indexOfFirst { it.id == userMessageId }
            if (targetIndex < 0) {
                return@startSessionRun runLoop(sessId, trimmed)
            }
            val targetMessage = current[targetIndex]
            val toKeep = current.subList(0, targetIndex)
            val liveFlow = getOrCreateLiveMessages(sessId)
            liveFlow.value = toKeep
            if (sessId == _currentSessionId.value) {
                _messages.value = toKeep
            }
            messageStore.rewindBefore(sessId, targetMessage.id)
            runLoop(sessId, trimmed)
        }
        startForegroundServiceSafe()
    }

    /** Navigate the active branch to immediately before this message. */
    suspend fun deleteMessage(messageId: String, targetSessionId: String? = null) {
        val sessId = targetSessionId?.ifBlank { null } ?: _currentSessionId.value
        if (isSessionBusy(sessId) || sessId.isBlank()) return
        val liveFlow = getOrCreateLiveMessages(sessId)
        val current = liveFlow.value
        val target = current.firstOrNull { it.id == messageId } ?: return
        val targetIndex = current.indexOf(target)
        val updated = current.take(targetIndex)
        liveFlow.value = updated
        if (sessId == _currentSessionId.value) {
            _messages.value = updated
        }
        messageStore.rewindBefore(sessId, target.id)
    }

    /**
     * 为所有尚无 ToolResult 的 ToolCall 补写一条中断占位结果并持久化
     */
    private suspend fun repairDanglingToolCalls(sessId: String, interrupted: Boolean) {
        val liveFlow = getOrCreateLiveMessages(sessId)
        val msgs = liveFlow.value
        val answeredIds = msgs.filterIsInstance<ToolResult>().mapTo(mutableSetOf()) { it.toolCallId }
        val dangling = msgs.filterIsInstance<ToolCall>().filter { it.id !in answeredIds }
        if (dangling.isEmpty()) return
        val note = if (interrupted) "用户停止了本次执行，工具被中断。" else "工具结果缺失（历史中断），已补占位结果以继续会话。"
        dangling.forEach { call ->
            val result = ToolResult(
                id = newId(),
                createdAt = now(),
                toolCallId = call.id,
                success = false,
                output = note,
            )
            append(sessId, result)
        }
    }

    fun cancel(targetSessionId: String? = null) {
        val sessId = targetSessionId?.ifBlank { null } ?: _currentSessionId.value
        _sessionPendingMessages[sessId]?.value = emptyList()
        loopScope.launch {
            PromptQueue.entries.forEach { promptQueueManager.clear(sessId, it) }
            refreshPendingProjection(sessId)
        }
        setStatus(sessId, "正在停止…")
        // Do NOT remove the job from the map here: cancellation is asynchronous, and
        // removing by key would let a dying job's finally later delete a *new* job.
        // finishRun removes only its own job via sessionJobs.remove(sessId, selfJob).
        sessionJobs[sessId]?.cancel()
        setSessionState(sessId, SessionRunState.IDLE)
        if (sessId == _currentSessionId.value) {
            _pendingMessages.value = emptyList()
            _status.value = "正在停止…"
            _running.value = false
        }
    }

    /** 移除某会话排队中的消息 */
    fun removePendingMessage(index: Int, targetSessionId: String? = null) {
        val sessId = targetSessionId?.ifBlank { null } ?: _currentSessionId.value
        loopScope.launch {
            promptQueueManager.cancel(sessId, PromptQueue.NEXT_RUN, index)
            refreshPendingProjection(sessId)
        }
    }

    /** 清空某会话全部排队消息 */
    fun clearPendingMessages(targetSessionId: String? = null) {
        val sessId = targetSessionId?.ifBlank { null } ?: _currentSessionId.value
        loopScope.launch {
            promptQueueManager.clear(sessId, PromptQueue.NEXT_RUN)
            refreshPendingProjection(sessId)
        }
    }

    private suspend fun enqueuePending(sessId: String, pending: PendingMessage) {
        promptQueueManager.enqueue(sessId, PromptQueue.NEXT_RUN, pending)
        refreshPendingProjection(sessId)
    }

    private suspend fun refreshPendingProjection(sessId: String) {
        val pending = promptQueueManager.list(sessId, PromptQueue.NEXT_RUN).map { it.second }
        getOrCreatePendingFlow(sessId).value = pending
        if (sessId == _currentSessionId.value) _pendingMessages.value = pending
    }

    private suspend fun finishRun(sessId: String, job: Job) {
        // Only remove ourselves; never clobber a newer job that started after cancel().
        sessionJobs.remove(sessId, job)
        _sessionThinkingLives[sessId]?.value = false
        val waitingApproval = _sessionRunStates.value[sessId] == SessionRunState.WAITING_APPROVAL
        if (!waitingApproval) setStatus(sessId, null)
        if (sessId == _currentSessionId.value) {
            _running.value = false
            if (!waitingApproval) _status.value = null
            _thinkingLive.value = false
        }
        if (waitingApproval) return
        if (tombstonedSessions.contains(sessId)) return
        val (queueItemId, next) = promptQueueManager.first(sessId, PromptQueue.NEXT_RUN) ?: return
        val userMessage = UserMessage(newId(), now(), next.text, next.imageUrls)
        val operationId = operationCoordinator.acceptQueuedRun(sessId, queueItemId, userMessage)
        publishPersistedMessage(sessId, userMessage)
        refreshPendingProjection(sessId)
        startSessionRun(sessId) { runLoopInternal(sessId, now(), operationId) }
    }

    fun clearError(targetSessionId: String? = null) {
        val sessId = targetSessionId?.ifBlank { null } ?: _currentSessionId.value
        setError(sessId, null)
    }

    /**
     * Atomically check-and-occupy the session slot under a per-session Mutex,
     * then run [block] as the single active run. If the session is busy the
     * optional [enqueueOnBusy] message is queued for ordered execution.
     */
    private fun startSessionRun(
        sessId: String,
        enqueueOnBusy: PendingMessage? = null,
        block: suspend () -> RunResult,
    ) {
        if (tombstonedSessions.contains(sessId)) return
        loopScope.launch {
            val mutex = sessionMutexes.getOrPut(sessId) { Mutex() }
            var queueAfterUnlock: PendingMessage? = null
            mutex.withLock {
                if (tombstonedSessions.contains(sessId)) return@withLock
                if (isSessionBusy(sessId)) {
                    queueAfterUnlock = enqueueOnBusy
                    return@withLock
                }
                val job = launch(start = CoroutineStart.LAZY) {
                    executeSessionRun(sessId, block)
                }
                sessionJobs[sessId] = job
                job.start()
            }
            queueAfterUnlock?.let { enqueuePending(sessId, it) }
        }
    }

    /**
     * Claim the session slot unconditionally. Used by approval resumption, which
     * already holds an exclusive claim via claimPending() and is the legitimate
     * successor to a WAITING_APPROVAL run (which still reports busy).
     */
    private fun startClaimedSessionRun(sessId: String, block: suspend () -> RunResult) {
        if (tombstonedSessions.contains(sessId)) return
        loopScope.launch {
            val mutex = sessionMutexes.getOrPut(sessId) { Mutex() }
            mutex.withLock {
                if (tombstonedSessions.contains(sessId)) return@withLock
                val job = launch(start = CoroutineStart.LAZY) {
                    executeSessionRun(sessId, block)
                }
                sessionJobs[sessId] = job
                job.start()
            }
        }
    }

    private suspend fun executeSessionRun(sessId: String, block: suspend () -> RunResult) {
        val selfJob = currentCoroutineContext()[Job]!!
        setSessionState(sessId, SessionRunState.RUNNING)
        setError(sessId, null)
        try {
            when (val result = block()) {
                RunResult.Completed -> {
                    operationCoordinator.finish(sessId, "completed", getOrCreateLiveMessages(sessId).value.lastOrNull()?.id)
                    setSessionState(sessId, SessionRunState.COMPLETED)
                }
                RunResult.WaitingApproval -> setSessionState(sessId, SessionRunState.WAITING_APPROVAL)
                RunResult.Cancelled -> {
                    operationCoordinator.finish(sessId, "aborted")
                    setSessionState(sessId, SessionRunState.IDLE)
                }
                is RunResult.Failed -> {
                    operationCoordinator.finish(sessId, "failed", details = result.message)
                    setError(sessId, result.message)
                    setSessionState(sessId, SessionRunState.FAILED)
                }
            }
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                repairDanglingToolCalls(sessId, interrupted = true)
                operationCoordinator.finish(sessId, "aborted", details = "cancelled")
            }
            logger.i("Harness loop cancelled for session $sessId")
            setSessionState(sessId, SessionRunState.IDLE)
        } catch (_: ApprovalPauseException) {
            setSessionState(sessId, SessionRunState.WAITING_APPROVAL)
        } catch (throwable: Throwable) {
            logger.e("Harness loop failed for session $sessId", throwable)
            setError(sessId, throwable.message ?: "执行失败")
            runCatching { operationCoordinator.finish(sessId, "failed", details = throwable.message) }
            setSessionState(sessId, SessionRunState.FAILED)
        } finally {
            finishRun(sessId, selfJob)
        }
    }

    private suspend fun runLoop(sessId: String, userText: String, imageUrls: List<String> = emptyList()): RunResult {
        logAgentEvent(sessId, "UserPrompt", userText)
        val userMessage = UserMessage(id = newId(), createdAt = now(), text = userText, imageUrls = imageUrls)
        val operationId = operationCoordinator.acceptRun(sessId, userMessage)
        publishPersistedMessage(sessId, userMessage)
        return runLoopInternal(sessId, startedAt = now(), operationId = operationId)
    }

    private suspend fun runLoopInternal(sessId: String, startedAt: Long, operationId: String? = null): RunResult {
        val activeOperationId = operationId ?: operationCoordinator.beginRun(sessId)
        repairDanglingToolCalls(sessId, interrupted = false)
        val maxRounds = runCatching { settingsDataStore.maxToolRounds.first() }.getOrDefault(MAX_ROUNDS)
        val autoCwd = runCatching { settingsDataStore.autoWorkspaceCwd.first() }.getOrDefault(true)
        val sessionEntity = sessionDao.findById(sessId)
        val sessionWorkspace = sessionEntity?.workspace.orEmpty()

        val maxToolsPerRound = runCatching { settingsDataStore.maxToolsPerRound.first() }.getOrDefault(12)
        val maxConsecutiveFailures = runCatching { settingsDataStore.maxConsecutiveFailures.first() }.getOrDefault(8)
        val retryPolicy = RetryPolicy.NETWORK_DEFAULT
        var consecutiveFailures = 0

        var round = 0
        while (round < maxRounds) {
            drainSteeringMessages(sessId)
            setStatus(sessId, "思考中")
            val model = try {
                providerClient.resolveConfigured()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                logAgentEvent(sessId, "ModelResolveError", "无法获取模型配置", throwable)
                appendFatal(sessId, "无法获取模型配置：${friendly(throwable)}", now() - startedAt)
                return RunResult.Failed("无法获取模型配置：${friendly(throwable)}")
            }
            logAgentEvent(sessId, "ModelRequest", "Round=$round, Model=${model.name}, Provider=${model.provider}")
            val msgs = getOrCreateLiveMessages(sessId).value
            val latestUserMessage = msgs.filterIsInstance<UserMessage>().lastOrNull()
            val latestUserText = latestUserMessage?.text.orEmpty()
            val mentionedNames = extractMentionedNames(latestUserText)
            val effectiveModel = if (mentionedNames.isNotEmpty()) {
                val matchedTools = model.dynamicMcpTools.filter { tool ->
                    val sName = tool.serverName.lowercase()
                    val sId = tool.serverId.lowercase()
                    val tName = tool.name.lowercase()
                    sName in mentionedNames || sId in mentionedNames || tName in mentionedNames
                }
                if (matchedTools.isNotEmpty()) model.copy(dynamicMcpTools = matchedTools) else model
            } else {
                model
            }
            appendCapabilityEvents(sessId, latestUserMessage?.id.orEmpty(), mentionedNames, effectiveModel)
            val assistantId = newId()
            val assistantAt = now()
            val streamText = StringBuilder()
            val streamReasoning = StringBuilder()
            var streamed: ChatResult? = null
            var netRetry = 0
            var lastReasoningFlushTime = 0L
            var lastTextFlushTime = 0L
            while (streamed == null) {
                try {
                    operationCoordinator.providerIntent(
                        operationId = activeOperationId,
                        effectId = assistantId,
                        round = round,
                        attempt = netRetry + 1,
                        maxAttempts = retryPolicy.maxAttempts,
                    )
                    streamed = providerClient.chatStream(
                        effectiveModel,
                        apiMessages(sessId, effectiveModel),
                        onReasoning = { chunk ->
                            streamReasoning.append(chunk)
                            sessionThinkingModes[sessId] = true
                            getOrCreateThinkingLiveFlow(sessId).value = true
                            if (sessId == _currentSessionId.value) {
                                _thinkingLive.value = true
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastReasoningFlushTime >= STREAM_FLUSH_INTERVAL_MS) {
                                lastReasoningFlushTime = now
                                streamAssistantReasoning(sessId, assistantId, assistantAt, streamReasoning.toString())
                            }
                        },
                    ) { chunk ->
                        setStatus(sessId, "回复中")
                        streamText.append(chunk)
                        val now = System.currentTimeMillis()
                        if (now - lastTextFlushTime >= STREAM_FLUSH_INTERVAL_MS) {
                            lastTextFlushTime = now
                            streamAssistant(sessId, assistantId, assistantAt, streamText.toString())
                        }
                    }
                    // 流式传输完毕，无条件刷新一次完整内容
                    if (streamReasoning.isNotEmpty()) {
                        streamAssistantReasoning(sessId, assistantId, assistantAt, streamReasoning.toString())
                    }
                    if (streamText.isNotEmpty()) {
                        streamAssistant(sessId, assistantId, assistantAt, streamText.toString())
                    }
                } catch (cancellation: CancellationException) {
                    logAgentEvent(sessId, "Cancelled", "用户主动取消执行")
                    throw cancellation
                } catch (rateLimit: LlmRateLimitException) {
                    currentCoroutineContext().ensureActive()
                    if (rateLimit.quotaExhausted) {
                        getOrCreateThinkingLiveFlow(sessId).value = false
                        if (sessId == _currentSessionId.value) _thinkingLive.value = false
                        logAgentEvent(sessId, "QuotaExhausted", rateLimit.message.orEmpty(), rateLimit)
                        appendFatal(
                            sessId,
                            "模型服务商额度已耗尽，无法继续执行。请充值、切换可用模型或更新 API Key。\n\n${rateLimit.message}",
                            now() - startedAt,
                        )
                        return RunResult.Failed("模型服务商额度已耗尽，无法继续执行。")
                    }
                    netRetry++
                    if (netRetry > retryPolicy.maxRetries) throw rateLimit
                    getOrCreateThinkingLiveFlow(sessId).value = false
                    if (sessId == _currentSessionId.value) _thinkingLive.value = false
                    val waitSeconds = rateLimit.retryAfterSeconds ?: (netRetry * RETRY_BACKOFF_SEC).coerceAtMost(60L)
                    setStatus(sessId, "请求受限，${waitSeconds} 秒后自动重试（$netRetry/$MAX_STREAM_RETRIES）")
                    logAgentEvent(sessId, "RateLimitRetry", "限流退避 ${waitSeconds}s，重试 $netRetry/$MAX_STREAM_RETRIES", rateLimit)
                    streamText.clear()
                    streamReasoning.clear()
                    streamAssistant(sessId, assistantId, assistantAt, "")
                    for (remaining in waitSeconds downTo 1L) {
                        currentCoroutineContext().ensureActive()
                        setStatus(sessId, "请求受限，${remaining} 秒后自动重试（$netRetry/$MAX_STREAM_RETRIES）")
                        delay(1000L)
                    }
                } catch (io: IOException) {
                    currentCoroutineContext().ensureActive()
                    netRetry++
                    logAgentEvent(sessId, "NetworkRetry", "网络中断重试 $netRetry/$MAX_STREAM_RETRIES: ${io.message}", io)
                    if (netRetry > retryPolicy.maxRetries) throw io
                    getOrCreateThinkingLiveFlow(sessId).value = false
                    if (sessId == _currentSessionId.value) {
                        _thinkingLive.value = false
                    }
                    setStatus(sessId, "网络中断，重试中（$netRetry/$MAX_STREAM_RETRIES）")
                    streamText.clear()
                    streamReasoning.clear()
                    streamAssistant(sessId, assistantId, assistantAt, "")
                    delay(retryPolicy.delayForRetry(netRetry))
                } catch (throwable: Throwable) {
                    getOrCreateThinkingLiveFlow(sessId).value = false
                    if (sessId == _currentSessionId.value) {
                        _thinkingLive.value = false
                    }
                    logAgentEvent(sessId, "ModelError", "LLM 调用失败: ${throwable.message}", throwable)
                    if (streamText.isNotEmpty()) {
                        persistAssistant(
                            sessId,
                            assistantId,
                            assistantAt,
                            streamText.toString(),
                            streamReasoning.toString().ifBlank { null },
                            totalMs = now() - startedAt,
                            operationId = activeOperationId,
                            round = round,
                        )
                    } else {
                        appendFatal(sessId, "执行遇到问题，已中断：${friendly(throwable)}", now() - startedAt)
                    }
                    return RunResult.Failed(friendly(throwable))
                }
            }
            val result = streamed
            val jsonMode = model.toolCallMode == ToolCallMode.JSON_TEXT
            val rawText = streamText.toString()
            // JSON 文本模式：从回复文本中解析 [[tool_call]]{...}[[/tool_call]] 标记，
            // 展示给用户与持久化时剥离标记（标记仅作为模型↔引擎的调用协议）
            val jsonCalls = if (jsonMode) extractJsonToolCalls(rawText) else emptyList()
            val displayText = if (jsonMode) stripToolCallMarkers(rawText) else rawText
            logAgentEvent(
                sessId,
                "ModelResponse",
                "TextLength=${rawText.length}, ReasoningLength=${result.reasoningContent?.length ?: 0}, ToolCallsCount=${result.toolCalls.size}, JsonTextCalls=${jsonCalls.size}",
            )
            if (displayText.isNotEmpty()) {
                persistAssistant(
                    sessId,
                    assistantId,
                    assistantAt,
                    displayText,
                    result.reasoningContent,
                    totalMs = if (result.toolCalls.isEmpty() && jsonCalls.isEmpty()) now() - startedAt else null,
                    operationId = activeOperationId,
                    round = round,
                    usage = result.usage,
                    model = effectiveModel,
                )
            } else {
                val usageEntity = result.usage.takeIf { it.hasData }?.let {
                    operationCoordinator.usageEntity(
                        sessionId = sessId,
                        operationId = activeOperationId,
                        entryId = null,
                        provider = effectiveModel.provider,
                        modelId = effectiveModel.model,
                        usage = it,
                    )
                }
                operationCoordinator.providerSettled(activeOperationId, null, usage = usageEntity, round = round)
            }
            getOrCreateThinkingLiveFlow(sessId).value = false
            if (sessId == _currentSessionId.value) {
                _thinkingLive.value = false
            }
            val allCalls = result.toolCalls + jsonCalls
            if (allCalls.isEmpty()) {
                val followUps = promptQueueManager.consume(sessId, PromptQueue.FOLLOW_UP)
                followUps.forEach { publishPersistedMessage(sessId, it) }
                if (followUps.isEmpty()) return RunResult.Completed
                round++
                continue
            }
            // 单轮工具数上限：超出部分回填空结果并提示模型，避免一次性爆发失控。
            val effectiveCalls = if (allCalls.size > maxToolsPerRound) {
                val dropped = allCalls.size - maxToolsPerRound
                allCalls.drop(maxToolsPerRound).forEach { spec ->
                    append(
                        sessId,
                        ToolCall(
                            id = spec.id,
                            createdAt = now(),
                            tool = HarnessApiMapper.toolByName(spec.name),
                            args = buildJsonObject {},
                            reasoning = result.reasoningContent,
                            rawToolName = spec.name.trim(),
                        ),
                    )
                    append(
                        sessId,
                        ToolResult(
                            id = newId(),
                            createdAt = now(),
                            toolCallId = spec.id,
                            success = false,
                            output = "本回合工具调用数量（${allCalls.size}）超过单轮上限（$maxToolsPerRound），已跳过本次多余的 $dropped 个调用。" +
                                "请拆分任务、分步调用工具，避免一次性发起过多工具请求。",
                        ),
                    )
                }
                allCalls.take(maxToolsPerRound)
            } else {
                allCalls
            }
            var roundHadSuccess = false
            effectiveCalls.forEach { spec ->
                val parsedArgs = try {
                    json.parseToJsonElement(spec.argumentsJson) as? JsonObject
                        ?: throw IllegalArgumentException("参数不是 JSON 对象")
                } catch (parseError: Throwable) {
                    append(
                        sessId,
                        ToolCall(
                            id = spec.id,
                            createdAt = now(),
                            tool = HarnessApiMapper.toolByName(spec.name),
                            args = buildJsonObject {},
                            reasoning = result.reasoningContent,
                        ),
                    )
                    append(
                        sessId,
                        ToolResult(
                            id = newId(),
                            createdAt = now(),
                            toolCallId = spec.id,
                            success = false,
                            output = "工具参数 JSON 解析失败（${friendly(parseError)}），参数可能被截断。" +
                                "请重新发起完整的工具调用，参数必须是合法的 JSON 对象。",
                        ),
                    )
                    return@forEach
                }
                val toolNameTrimmed = spec.name.trim()
                if (toolNameTrimmed.lowercase() !in KNOWN_TOOL_NAMES && !toolNameTrimmed.startsWith("mcp__")) {
                    append(
                        sessId,
                        ToolCall(
                            id = spec.id,
                            createdAt = now(),
                            tool = HarnessApiMapper.toolByName(spec.name),
                            args = buildJsonObject {},
                            reasoning = result.reasoningContent,
                            rawToolName = toolNameTrimmed,
                        ),
                    )
                    append(
                        sessId,
                        ToolResult(
                            id = newId(),
                            createdAt = now(),
                            toolCallId = spec.id,
                            success = false,
                            output = "未知工具：${spec.name}。可用工具包含 read / write / edit / base / process / invoke_subagent 以及已启用的 MCP 插件工具。",
                        ),
                    )
                    return@forEach
                }
                val tool = HarnessApiMapper.toolByName(spec.name)
                var args = parsedArgs
                if (tool == HarnessTool.BASE && autoCwd && sessionWorkspace.isNotBlank() && args["cwd"] == null) {
                    args = buildJsonObject {
                        put("cwd", sessionWorkspace)
                        args.forEach { (key, value) -> put(key, value) }
                    }
                }
                // 执行前 JSON Schema 校验：必填/枚举/范围/格式/组合约束。
                // 失败时写回可读问题清单，让模型按 schema 自我纠正，而不是带着坏参数进入执行层。
                val schemaProblems = ToolSchemaValidator.problemsFor(toolNameTrimmed, args, effectiveModel.dynamicMcpTools)
                if (schemaProblems.isNotEmpty()) {
                    append(
                        sessId,
                        ToolCall(
                            id = spec.id,
                            createdAt = now(),
                            tool = tool,
                            args = args,
                            reasoning = result.reasoningContent,
                            rawToolName = toolNameTrimmed,
                        ),
                    )
                    append(
                        sessId,
                        ToolResult(
                            id = newId(),
                            createdAt = now(),
                            toolCallId = spec.id,
                            success = false,
                            output = "工具参数校验未通过：${schemaProblems.joinToString("；")}。" +
                                "请按工具定义修正参数后重新调用，必填字段不可省略。",
                        ),
                    )
                    return@forEach
                }
                val toolCall = ToolCall(
                    // Preserve the provider protocol id across execution, approval,
                    // persistence and the subsequent tool result.
                    id = spec.id,
                    createdAt = now(),
                    tool = tool,
                    args = args,
                    reasoning = result.reasoningContent,
                    rawToolName = toolNameTrimmed,
                )
                logAgentEvent(sessId, "ToolCall", "Tool=${tool.name}, RawName=$toolNameTrimmed, Args=$args")
                operationCoordinator.toolIntent(
                    operationId = activeOperationId,
                    message = toolCall,
                    payloadJson = spec.argumentsJson,
                    replay = ToolReplayPolicy.forTool(tool, toolNameTrimmed),
                    round = round,
                )
                publishPersistedMessage(sessId, toolCall)
                setStatus(sessId, describeToolCall(tool, args, toolNameTrimmed))
                val toolStart = now()
                val outcome = try {
                    toolExecutor.execute(
                        toolCall,
                        sessId,
                        sessionWorkspace,
                        progressReporter = { progress -> setStatus(sessId, progress) },
                        operationId = activeOperationId,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    ToolResult(
                        id = newId(),
                        createdAt = now(),
                        toolCallId = toolCall.id,
                        success = false,
                        output = "工具执行异常：${friendly(throwable)}",
                    )
                }
                val duration = now() - toolStart
                logAgentEvent(sessId, "ToolResult", "Tool=${tool.name}, Success=${outcome.success}, Duration=${duration}ms, Output=${outcome.output.take(300)}")
                if (outcome.awaitingApproval) {
                    operationCoordinator.waitingApproval(activeOperationId)
                    setStatus(sessId, "等待用户批准")
                    throw ApprovalPauseException()
                }
                val settledOutcome = outcome.copy(durationMs = duration)
                operationCoordinator.toolSettled(activeOperationId, settledOutcome, round)
                publishPersistedMessage(sessId, settledOutcome)
                if (outcome.success) roundHadSuccess = true
                touchSession(sessId)
            }
            // 连续失败熔断：当一轮内所有工具调用均失败时计数，连续超过阈值则主动终止，
            // 避免模型在"调用→失败→再调用"中死循环空转，浪费资源且无法自拔。
            if (effectiveCalls.isNotEmpty() && !roundHadSuccess) {
                consecutiveFailures++
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    append(
                        sessId,
                        AssistantText(
                            id = newId(),
                            createdAt = now(),
                            text = "连续 $consecutiveFailures 轮工具调用均失败，已主动停止以避免陷入死循环。" +
                                "请检查：命令是否正确、工作区路径是否存在、依赖是否已安装，或简化任务后重试。",
                            totalMs = now() - startedAt,
                        ),
                    )
                    return RunResult.Failed("连续 $consecutiveFailures 轮工具调用均失败，已主动停止")
                }
            } else {
                consecutiveFailures = 0
            }
            round++
        }
        append(
            sessId,
            AssistantText(
                id = newId(),
                createdAt = now(),
                text = "已达到最大工具轮数（$maxRounds），请简化任务或分步进行。",
                totalMs = now() - startedAt,
            ),
        )
        return RunResult.Completed
    }

    /** JSON 文本模式专用调用标记：模型在回复中输出 [[tool_call]]{JSON}[[/tool_call]]。 */
    private fun extractJsonToolCalls(text: String): List<ApiToolCallSpec> {
        if (text.isBlank()) return emptyList()
        val regex = Regex("""\[\[tool_call\]\](.*?)\[\[/tool_call\]\]""", RegexOption.DOT_MATCHES_ALL)
        return regex.findAll(text).mapNotNull { match ->
            val payload = match.groupValues[1].trim()
            runCatching {
                val obj = json.parseToJsonElement(payload) as? JsonObject ?: return@runCatching null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (name.isBlank()) return@runCatching null
                ApiToolCallSpec(
                    id = "json-" + UUID.randomUUID().toString(),
                    name = name,
                    argumentsJson = obj["arguments"]?.toString() ?: "{}",
                )
            }.getOrNull()
        }.toList()
    }

    /** 从展示文本中剥离工具调用标记，只留下模型真正写给用户看的内容。 */
    private fun stripToolCallMarkers(text: String): String =
        text.replace(Regex("""\[\[tool_call\]\].*?\[\[/tool_call\]\]""", RegexOption.DOT_MATCHES_ALL), "").trim()

    private fun describeToolCall(tool: HarnessTool, args: JsonObject, rawToolName: String? = null): String {
        fun arg(name: String): String? =
            runCatching { args[name]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
        return when (tool) {
            HarnessTool.BASE -> {
                val command = arg("command")?.lineSequence()?.first()?.trim().orEmpty()
                if (command.isEmpty()) "执行命令" else "执行命令：${command.take(MAX_STATUS_ARG_LENGTH)}"
            }
            HarnessTool.PROCESS -> "管理后台进程：${arg("action") ?: "process"}${arg("id")?.let { " · ${it.take(MAX_STATUS_ARG_LENGTH)}" }.orEmpty()}"
            HarnessTool.DOWNLOAD -> arg("destination")?.let { "下载文件：${it.takeLast(MAX_STATUS_ARG_LENGTH)}" } ?: "下载文件"
            HarnessTool.READ -> arg("path")?.let { "读取文件：${it.takeLast(MAX_STATUS_ARG_LENGTH)}" } ?: "读取文件"
            HarnessTool.WRITE -> arg("path")?.let { "写入文件：${it.takeLast(MAX_STATUS_ARG_LENGTH)}" } ?: "写入文件"
            HarnessTool.EDIT -> arg("path")?.let { "编辑文件：${it.takeLast(MAX_STATUS_ARG_LENGTH)}" } ?: "编辑文件"
            HarnessTool.MEMORY -> "正在存取长期记忆：${arg("key") ?: arg("action") ?: "memory"}"
            HarnessTool.PLAN -> "正在更新任务执行规划：${arg("goal") ?: arg("action") ?: "plan"}"
            HarnessTool.SCRATCHPAD -> "正在记录工作草稿便签：${arg("key") ?: arg("action") ?: "scratchpad"}"
            HarnessTool.HISTORY_SEARCH -> "正在检索历史消息：${arg("query")?.take(MAX_STATUS_ARG_LENGTH).orEmpty()}"
            HarnessTool.HISTORY_READ -> "正在读取历史消息：${arg("message_id") ?: arg("index") ?: "history"}"
            HarnessTool.SUBAGENT -> "正在派发并执行子智能体协同任务…"
            HarnessTool.MCP -> "正在调用 MCP 插件工具：${rawToolName ?: "mcp"}…"
        }
    }

    private suspend fun logAgentEvent(sessId: String, tag: String, message: String, throwable: Throwable? = null) {
        val enabled = runCatching { settingsDataStore.agentLoggingEnabled.first() }.getOrDefault(false)
        if (enabled) {
            logger.logAgent(sessId, tag, message, throwable)
        }
    }

    private suspend fun drainSteeringMessages(sessId: String) {
        val queued = promptQueueManager.consume(sessId, PromptQueue.STEER)
        queued.forEach { message ->
            logAgentEvent(sessId, "SteeringMessage", message.text)
            publishPersistedMessage(sessId, message)
        }
    }

    private suspend fun appendFatal(sessId: String, text: String, totalMs: Long? = null) {
        append(sessId, AssistantText(id = newId(), createdAt = now(), text = text, totalMs = totalMs))
    }

    private fun friendly(throwable: Throwable): String =
        throwable.message?.take(200) ?: throwable::class.simpleName.orEmpty()

    private suspend fun buildSystemPrompt(
        workspacePath: String,
        toolCallMode: ToolCallMode = ToolCallMode.NATIVE,
        mentionedNames: Set<String> = emptySet(),
        sessionId: String = "",
    ): String {
        val distroId = runCatching { settingsDataStore.selectedDistribution.first() }.getOrDefault("debian")
        val distroName = when (distroId.lowercase()) {
            "ubuntu" -> "Ubuntu 24.04 (Noble Numbat)"
            "debian" -> "Debian 12 (Bookworm)"
            "alpine" -> "Alpine Linux 3.19"
            "archlinux", "arch" -> "Arch Linux"
            "fedora" -> "Fedora 40"
            "void" -> "Void Linux"
            else -> "$distroId Linux"
        }
        val pkgManager = when (distroId.lowercase()) {
            "alpine" -> "apk add <package>"
            "archlinux", "arch" -> "pacman -S <package>"
            "fedora" -> "dnf install -y <package>"
            "void" -> "xbps-install -S -y <package>"
            else -> "apt-get install -y <package>"
        }
        val customPromptEnabled = runCatching { settingsDataStore.customSystemPromptEnabled.first() }.getOrDefault(false)
        val customPrompt = runCatching { settingsDataStore.customSystemPrompt.first() }.getOrDefault("")
        val baseRawTemplate = if (customPromptEnabled && customPrompt.isNotBlank()) {
            customPrompt
        } else {
            runCatching {
                context.assets.open("prompts/agent_system.md").bufferedReader().use { it.readText() }
            }.getOrDefault(FALLBACK_SYSTEM_PROMPT)
        }

        val providerModelId = runCatching { settingsDataStore.providerModel.first() }.getOrDefault("")
        val resolvedTemplate = top.wkbin.taixu.harness.prompt.PromptVariableResolver.resolve(
            template = baseRawTemplate,
            context = context,
            modelId = providerModelId,
            modelName = providerModelId,
            charName = "太墟智枢",
            userName = "用户",
        )

        val allSkills = runCatching { skillRepository.allSkills.first() }.getOrDefault(emptyList())
        val selectedSkills = if (mentionedNames.isNotEmpty()) {
            val matched = allSkills.filter { skill ->
                val nameLower = skill.name.lowercase()
                val idLower = skill.id.lowercase()
                val cmdLower = skill.triggerCommand?.removePrefix("/")?.lowercase().orEmpty()
                nameLower in mentionedNames || idLower in mentionedNames || (cmdLower.isNotEmpty() && cmdLower in mentionedNames)
            }
            if (matched.isNotEmpty()) matched else allSkills.filter { it.isEnabled }
        } else {
            allSkills.filter { it.isEnabled }
        }

        val skillSection = if (selectedSkills.isNotEmpty()) {
            "## 当前生效的专精技能指导规则 (Active Skills)\n\n" + selectedSkills.joinToString("\n\n") { skill ->
                "### [专精技能] " + skill.name + " (" + skill.category + ")\n" + skill.systemPrompt.trim()
            }
        } else ""

        val installedTools = runCatching { toolRepository.getForDistro(distroId).filter { it.state == top.wkbin.taixu.core.model.ToolState.INSTALLED.name } }
            .getOrDefault(emptyList())
        val installedToolsSection = if (installedTools.isNotEmpty()) {
            "\n\n## 当前 Linux 沙箱已就绪的开发套件与工具环境（已安装就绪，直接调用即可，切勿重复下载或重新安装）：\n" +
                installedTools.joinToString("\n") { tool ->
                    val ver = tool.installedVersion?.let { " (v$it)" } ?: ""
                    "- ${tool.name}$ver: ${tool.description}"
                }
        } else ""

        val memories = runCatching { agentContextDao.getMemoriesByScopes(listOf("global", "project", "session")) }.getOrDefault(emptyList())
        val memorySection = if (memories.isNotEmpty()) {
            "\n\n## 长期事实与偏好记忆 (Long-Term Memory)\n" +
                memories.joinToString("\n") { "- [${it.scope}/${it.kind}] ${it.key}: ${it.value}" }
        } else ""

        val activePlan = runCatching { agentContextDao.getActivePlan(sessionId) }.getOrNull()
        val planSection = if (activePlan != null && activePlan.status == "active") {
            "\n\n## 当前任务多步骤执行规划与进度看板 (Active Plan)\n目标：${activePlan.goal}\n步骤与状态：\n${activePlan.stepsJson}"
        } else ""

        val subagentSection = buildSubagentGuidance(toolCallMode)

        val projectContext = loadProjectContext(workspacePath)
        val workspaceSection = if (workspacePath.isNotBlank()) {
            "\n\n当前工作区：" + workspacePath + "（base 命令默认在此目录执行；read/write/edit 的相对路径以此为根）"
        } else ""
        val workspaceGuidance = buildWorkspaceGuidance(
            workspacePath = workspacePath,
            projectTypeOverride = sessionId.let { id -> sessionDao.findById(id)?.projectType.orEmpty() },
            distroName = distroName,
            toolCallMode = toolCallMode,
            installedTools = installedTools,
        )

        val toolCallSection = when (toolCallMode) {
            ToolCallMode.JSON_TEXT -> """
                |
                |## 工具调用方式（重要：当前使用 JSON 文本模式）
                |你无法使用系统级 function calling，必须用**文本标记**调用工具：
                |- 当需要调用工具时，在回复中直接输出（独占一行，前后不要有解释文字）：
                |  [[tool_call]]{"name":"工具名","arguments":{...}}[[/tool_call]]
                |- 参数必须与上方"可用工具 JSON 定义"中的参数一致，缺一不可；
                |- 一次可连续输出多个 [[tool_call]] 标记，引擎会逐个执行并返回结果；
                |- 标记后的结果会以【工具 xxx 执行结果】形式出现在后续对话中，你据此继续；
                |- 不调用工具时，不要输出任何 [[tool_call]] 标记。
            """.trimMargin()
            ToolCallMode.DISABLED -> """
                |
                |## 工具禁用说明
                |当前会话已禁用工具调用：请直接回答用户问题，不要尝试调用任何工具（read / write / edit / base 等均不可用）。
            """.trimMargin()
            ToolCallMode.NATIVE -> ""
        }

        val thinkingLang = runCatching { settingsDataStore.thinkingLanguage.first() }.getOrDefault("zh")
        val thinkingLanguageSection = when (thinkingLang) {
            "zh" -> "\n\n## 思考与推理语言强约束 (Thinking Language Policy)\n重要：你的内部思考推导过程（Thinking / Reasoning Process / CoT）必须全程严格使用【中文】进行分析与推导，禁止在思考过程中输出非必要的英文！"
            "en" -> "\n\n## Thinking Language Policy\nImportant: Your internal reasoning and thinking process must be conducted strictly in English."
            else -> ""
        }

        return resolvedTemplate
            .replace("{{DISTRO_NAME}}", distroName)
            .replace("{{PKG_MANAGER}}", pkgManager)
            .replace("{{ACTIVE_SKILLS}}", skillSection)
            .trim() + installedToolsSection + memorySection + planSection + subagentSection + toolCallSection + workspaceSection + workspaceGuidance + projectContext + thinkingLanguageSection
    }

    private suspend fun buildSubagentGuidance(toolCallMode: ToolCallMode): String {
        if (toolCallMode == ToolCallMode.DISABLED) return ""
        val profiles = runCatching { subagentRepository.enabledProfiles() }.getOrDefault(emptyList())
        if (profiles.isEmpty()) {
            return "\n\n## 子智能体委派\n当前没有启用的子智能体角色，不要调用 invoke_subagent。"
        }
        val autoEnabled = runCatching { subagentRepository.autoDelegationEnabled.first() }.getOrDefault(true)
        val roleList = profiles.joinToString("\n") { profile ->
            "- role=\"${profile.id}\"：${profile.name}。${profile.description}"
        }
        val triggerPolicy = if (autoEnabled) {
            """
                你必须自行判断当前任务是否值得拆分，用户不需要知道工具名称或编写特殊提示词。
                当任务至少包含两个彼此独立、可以真正并行推进的子任务时，主动调用 invoke_subagent；例如跨模块调研、实现与独立验证、多方案并行评估。
                简单问答、单点小改动、存在严格前后依赖的步骤不要拆分。不要为了展示能力而委派，也不要把主智能体自己必须完成的整体验收责任完全转交出去。
            """.trimIndent()
        } else {
            "仅当用户明确要求并行处理、分头检查或使用子智能体时调用 invoke_subagent；否则由主智能体直接完成。"
        }
        return """

            ## 子智能体自主委派策略
            $triggerPolicy
            - 每次最多派发 6 个目标清晰、输出边界明确的子任务。
            - role 参数必须严格使用下列已启用角色标识，不得自行编造：
            $roleList
            - 收到以“【子智能体任务指派】”开头的任务时，你已经是子智能体，严禁再次调用 invoke_subagent，必须直接执行被分配的任务。
            - 子任务返回后由主智能体整合、解决冲突并继续完成最终交付。
        """.trimIndent()
    }

    private fun extractMentionedNames(text: String): Set<String> {
        if (!text.contains("@")) return emptySet()
        val regex = Regex("""@([^\s@,，:：\n]+)""")
        return regex.findAll(text).map { it.groupValues[1].trim().lowercase() }.toSet()
    }

    private suspend fun loadProjectContext(workspacePath: String): String {
        if (workspacePath.isBlank()) return ""
        val sections = buildList {
            for (name in listOf("AGENTS.md", "CLAUDE.md", "README.md")) {
                val content = runCatching {
                    // WorkspaceFileAccess understands the canonical /workspace/... form.
                    fileAccess.read("$workspacePath/$name").getOrNull()
                }.getOrNull() ?: continue
                val trimmed = content.take(PROJECT_CONTEXT_MAX_BYTES)
                add(
                    "<project_instructions path=\"" + name + "\">\n" + trimmed +
                        (if (content.length > PROJECT_CONTEXT_MAX_BYTES) "\n…（文件过长已截断）" else "") +
                        "\n</project_instructions>",
                )
            }
        }
        if (sections.isEmpty()) return ""
        return "\n\n<project_context>\n当前工作区的项目说明与约定（自动加载，编码时务必遵守）：\n\n" +
            sections.joinToString("\n\n") + "\n</project_context>"
    }

    /**
     * Workspace context is deliberately injected independently of user skills.
     * A linked project must remain actionable even when the user has disabled
     * optional skills or never mentions them in the first message.
     */
    private suspend fun buildWorkspaceGuidance(
        workspacePath: String,
        projectTypeOverride: String = "",
        distroName: String,
        toolCallMode: ToolCallMode,
        installedTools: List<top.wkbin.taixu.core.database.ToolEntity>,
    ): String {
        if (workspacePath.isBlank()) return ""
        val entries = fileAccess.list(workspacePath).getOrNull().orEmpty().map { it.name }.toSet()
        val appEntries = if ("app" in entries) {
            fileAccess.list("$workspacePath/app").getOrNull().orEmpty().map { it.name }.toSet()
        } else {
            emptySet()
        }
        val detectedProjectType = when {
            "pubspec.yaml" in entries -> "Flutter"
            "settings.gradle.kts" in entries || "settings.gradle" in entries ||
                "build.gradle.kts" in entries || "build.gradle" in entries ||
                ("app" in entries && appEntries.any { it == "build.gradle" || it == "build.gradle.kts" }) -> "Android"
            "apk-info.properties" in entries || entries.any { it.endsWith(".apk", ignoreCase = true) } -> "Android APK 逆向"
            else -> "通用工程"
        }
        val projectType = when (projectTypeOverride.trim().uppercase()) {
            "ANDROID" -> "Android"
            "FLUTTER" -> "Flutter"
            "REVERSE" -> "Android APK 逆向"
            "GENERAL" -> "通用工程"
            else -> detectedProjectType
        }
        val markerText = entries.sorted().joinToString(", ").ifBlank { "（目录为空或暂时不可读）" }
        val toolNames = if (toolCallMode == ToolCallMode.DISABLED) {
            "工具调用已禁用"
        } else {
            "read, write, edit, base, memory, plan, scratchpad, history.search, history.read, invoke_subagent" +
                if (toolCallMode == ToolCallMode.JSON_TEXT) "（JSON 文本调用模式）" else ""
        }
        val installedToolNames = installedTools.joinToString(", ") { it.name }.ifBlank { "暂无已安装套件记录" }
        val typeGuidance = when (projectType) {
            "Android" -> """
                ### Android 工程操作规约
                - 当前工程类型：Android；根目录标记：$markerText。
                - 修改代码或构建配置时，先用 read 查看 `settings.gradle(.kts)`、`app/build.gradle(.kts)`、`app/src/main/AndroidManifest.xml` 和入口源码；局部修改优先用 edit，需要新文件才用 write。
                - 首选构建入口：`/opt/taixu/scripts/build_android.sh "$workspacePath" assembleDebug`；也可在工程根目录执行 `./gradlew assembleDebug`。不要调用已移除的 android CLI。
                - 需要安装到手机时，先确认 APK 真实存在并完成构建，再复制到 `/sdcard/Download/`，然后执行 `taixu-host install-apk /sdcard/Download/<项目名>.apk` 调起宿主安装器；若 `adb devices` 有设备，优先 `adb install -r <apk>`。
                - 构建失败必须读取完整 Gradle/AAPT2 错误并编辑对应脚本或工程文件修复，不能只汇报“编译失败”。
            """.trimIndent()
            "Flutter" -> """
                ### Flutter 工程操作规约
                - 当前工程类型：Flutter；根目录标记：$markerText。
                - 修改前先 read `pubspec.yaml`、`lib/` 和 `android/` 的 Gradle 配置；Dart 代码用 edit/write 修改。
                - 依赖优先执行 `flutter pub get`；构建入口：`/opt/taixu/scripts/build_flutter.sh "$workspacePath" "apk --debug"`，或 `flutter build apk --debug`。
                - 安装到手机时，确认 `build/app/outputs/flutter-apk/*.apk` 完整后复制到 `/sdcard/Download/`，再执行 `taixu-host install-apk <apk路径>`；检测到 ADB 后可执行 `adb install -r <apk>`。
                - 遇到 Android Gradle/AAPT2 错误，检查 `android/gradle.properties`、Android 核心环境和 ARM64 AAPT2，不要反复全量下载 Flutter SDK。
            """.trimIndent()
            "Android APK 逆向" -> """
                ### Android 逆向工程操作规约
                - 当前工程类型：APK 逆向；根目录标记：$markerText。
                - 原始 APK 和 `unpacked/` 是分析输入，先 read `apk-info.properties` 与 `REVERSE.md`，不要覆盖原始 APK。
                - 优先使用 `jadx` 反编译 Java 源码，使用 `apktool` 处理资源/Smali；修改后再用既有脚本回编译、签名并验证。
            """.trimIndent()
            else -> """
                ### 通用工作区操作规约
                - 当前工程类型：通用工程；根目录标记：$markerText。
                - 先识别入口文件和项目说明，再选择对应构建命令；修改已有文件优先 edit，新文件使用 write，并在完成后执行最小验证。
            """.trimIndent()
        }
        return "\n\n## 关联工作区会话环境与项目指导（自动注入，不依赖 Skill 开关）\n" +
            "- 工作区路径：$workspacePath\n" +
            "- 当前 Linux 环境：$distroName，aarch64，Android 私有用户态 PRoot；没有真正的 root/systemd。\n" +
            "- 当前可用 Harness 工具：$toolNames。\n" +
            "- 已登记的开发套件：$installedToolNames。已就绪的工具直接调用，避免重复安装。\n" +
            typeGuidance
    }

    private suspend fun apiMessages(sessId: String, model: ModelConfig): List<ApiMessage> {
        val compactionEnabled = runCatching { settingsDataStore.contextCompactionEnabled.first() }.getOrDefault(true)
        val budgetTokens = model.contextTokens
            ?: runCatching { settingsDataStore.contextBudgetTokens.first() }.getOrDefault(128_000)
        val sessionEntity = sessionDao.findById(sessId)
        val sessionWorkspace = sessionEntity?.workspace.orEmpty()
        val thinkingMode = sessionThinkingModes[sessId] ?: false
        val toolCallMode = if (model.pureChatMode) ToolCallMode.DISABLED else model.toolCallMode

        var compactedContext = compactionManager.project(sessId)
        var msgs = compactedContext.messages
        val latestUserText = msgs.filterIsInstance<UserMessage>().lastOrNull()?.text.orEmpty()
        val mentionedNames = extractMentionedNames(latestUserText)

        val systemPrompt = if (!model.pureChatMode) {
            buildSystemPrompt(sessionWorkspace, toolCallMode, mentionedNames, sessId)
        } else {
            ""
        }
        return buildList {
            if (systemPrompt.isNotEmpty()) {
                add(ApiMessage(role = "system", content = systemPrompt))
            }
            val answeredIds = msgs.filterIsInstance<ToolResult>().mapTo(mutableSetOf()) { it.toolCallId }
            val toolCallDetails = msgs.filterIsInstance<ToolCall>().associate {
                it.id to ((it.rawToolName ?: HarnessApiMapper.apiName(it.tool)) to it.args)
            }

            // 预算驱动的滑动窗口：从最近一轮往回累加 token，超出预算则更早的历史进入压缩态。
            // 是否裁剪原文只由真实 token 预算决定，不再按用户轮次阈值强制折叠。
            val computedKeepFromIndex = if (compactionEnabled) {
                ContextWindowPolicy.computeKeepFromIndex(
                    msgs,
                    budgetTokens,
                    ContextWindowPolicy.estimateTokens(systemPrompt),
                )
            } else {
                0
            }
            if (computedKeepFromIndex > 0) {
                compactedContext = compactionManager.compact(sessId, compactedContext, computedKeepFromIndex)
                msgs = compactedContext.messages
            }
            val shouldCompact = !compactedContext.summary.isNullOrBlank()
            val recentTurnCutoffIndex = 0

            if (shouldCompact) {
                add(
                    ApiMessage(
                        role = "system",
                        content = compactedContext.summary,
                    ),
                )
            }

            // JSON 文本模式：工具调用以文本表达，tool 消息需转成 user 文本（API 不认识 tool 角色）
            val toolNames = toolCallDetails.mapValuesTo(mutableMapOf()) { it.value.first }

            var i = 0
            fun apiToolCall(tc: ToolCall) = ApiToolCall(
                id = tc.id,
                function = ApiFunctionCall(name = HarnessApiMapper.apiName(tc.tool), arguments = tc.args.toString()),
            )
            val isCollapsed = { index: Int -> shouldCompact && index < recentTurnCutoffIndex }
            while (i < msgs.size) {
                val message = msgs[i]
                if (isCollapsed(i)) {
                    i++
                    continue
                }
                if (message is CapabilityEvent) {
                    i++
                    continue
                }
                if (toolCallMode == ToolCallMode.JSON_TEXT) {
                    when (message) {
                        is ToolCall -> {
                            toolNames[message.id] = message.rawToolName ?: HarnessApiMapper.apiName(message.tool)
                            i++
                        }
                        is ToolResult -> {
                            val name = toolNames[message.toolCallId] ?: "工具"
                            val status = if (message.success) "成功" else "失败"
                            val args = toolCallDetails[message.toolCallId]?.second
                            val content = if (isCollapsed(i) && message.output.length > ContextWindowPolicy.compactThresholdFor(name)) {
                                ContextWindowPolicy.compactToolOutput(name, args, message.output, message.success)
                            } else {
                                "【工具 $name 执行结果·$status】\n${message.output}"
                            }
                            add(ApiMessage(role = "user", content = content))
                            i++
                        }
                        else -> {
                            val mapped = when {
                                isCollapsed(i) && message is AssistantText && message.text.length > 120 ->
                                    ApiMessage(
                                        role = "assistant",
                                        content = ContextWindowPolicy.foldMessageText("助手", message.text),
                                        reasoning_content = null,
                                    )
                                isCollapsed(i) && message is UserMessage && message.text.length > 120 ->
                                    ApiMessage(
                                        role = "user",
                                        content = ContextWindowPolicy.foldMessageText("用户", message.text),
                                        imageUrls = message.imageUrls,
                                    )
                                isCollapsed(i) && message is AssistantText ->
                                    HarnessApiMapper.toApiMessage(message).copy(reasoning_content = null)
                                else -> HarnessApiMapper.toApiMessage(message)
                            }
                            add(if (message is UserMessage && !model.visionEnabled) mapped.copy(imageUrls = emptyList()) else mapped)
                            i++
                        }
                    }
                    continue
                }
                if (message is AssistantText || message is ToolCall) {
                    if (message is ToolCall && message.id !in answeredIds) {
                        i++
                        continue
                    }
                    // 预算折叠态：早期 assistant 文本压缩为一行占位，避免撑爆上下文。
                    val text = if (isCollapsed(i) && message is AssistantText && message.text.length > 120) {
                        ContextWindowPolicy.foldMessageText("助手", message.text)
                    } else {
                        (message as? AssistantText)?.text
                    }
                    // collapsed 历史不携带 reasoning：它是执行过程数据，不应成为长期上下文负担。
                    val reasoning = if (isCollapsed(i)) null else when (message) {
                        is AssistantText -> message.reasoning
                        is ToolCall -> message.reasoning
                    }
                    val toolCalls = mutableListOf<ApiToolCall>()
                    if (message is ToolCall) toolCalls.add(apiToolCall(message))
                    var j = i + 1
                    while (j < msgs.size && msgs[j] is ToolCall) {
                        val tc = msgs[j] as ToolCall
                        if (tc.id in answeredIds) toolCalls.add(apiToolCall(tc))
                        j++
                    }
                    add(
                        ApiMessage(
                            role = "assistant",
                            content = text,
                            reasoning_content = when {
                                isCollapsed(i) -> null
                                reasoning != null -> reasoning
                                thinkingMode -> ""
                                else -> null
                            },
                            tool_calls = toolCalls.takeIf { it.isNotEmpty() },
                        ),
                    )
                    i = j
                } else if (message is ToolResult) {
                    val detail = toolCallDetails[message.toolCallId]
                    val content = if (isCollapsed(i) && message.output.length > ContextWindowPolicy.compactThresholdFor(detail?.first)) {
                        ContextWindowPolicy.compactToolOutput(detail?.first, detail?.second, message.output, message.success)
                    } else {
                        message.output
                    }
                    add(
                        ApiMessage(
                            role = "tool",
                            content = content,
                            tool_call_id = message.toolCallId,
                        ),
                    )
                    i++
                } else {
                    // 用户消息在早期历史中同样折叠，仅保留极简占位。
                    val folded = if (isCollapsed(i) && message is UserMessage && message.text.length > 120) {
                        ContextWindowPolicy.foldMessageText("用户", message.text)
                    } else {
                        null
                    }
                    if (folded != null) {
                        add(ApiMessage(role = "user", content = folded))
                    } else {
                        val mapped = HarnessApiMapper.toApiMessage(message)
                        add(if (message is UserMessage && !model.visionEnabled) mapped.copy(imageUrls = emptyList()) else mapped)
                    }
                    i++
                }
            }
        }
    }

    private suspend fun append(sessId: String, message: HarnessMessage) {
        messageStore.append(sessId, message)
        publishPersistedMessage(sessId, message)
    }

    /** Publish a message already committed by an atomic operation transaction. */
    private fun publishPersistedMessage(sessId: String, message: HarnessMessage) {
        val liveFlow = getOrCreateLiveMessages(sessId)
        liveFlow.update { current ->
            if (current.any { it.id == message.id }) current.map { if (it.id == message.id) message else it }
            else current + message
        }
        if (sessId == _currentSessionId.value) _messages.value = liveFlow.value
    }

    private fun streamAssistant(sessId: String, id: String, createdAt: Long, text: String) {
        val liveFlow = getOrCreateLiveMessages(sessId)
        liveFlow.update { current ->
            val existing = current.firstOrNull { it.id == id }
            val message = AssistantText(
                id = id,
                createdAt = createdAt,
                text = text,
                reasoning = (existing as? AssistantText)?.reasoning,
            )
            if (existing != null) current.map { if (it.id == id) message else it } else current + message
        }
        if (sessId == _currentSessionId.value) {
            _messages.value = liveFlow.value
        }
    }

    private fun streamAssistantReasoning(sessId: String, id: String, createdAt: Long, reasoning: String) {
        val liveFlow = getOrCreateLiveMessages(sessId)
        liveFlow.update { current ->
            val idx = current.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val existing = current[idx]
                (existing as? AssistantText)?.let {
                    current.toMutableList().apply { this[idx] = it.copy(reasoning = reasoning) }
                } ?: (current + AssistantText(id = id, createdAt = createdAt, text = "", reasoning = reasoning))
            } else {
                current + AssistantText(id = id, createdAt = createdAt, text = "", reasoning = reasoning)
            }
        }
        if (sessId == _currentSessionId.value) {
            _messages.value = liveFlow.value
        }
    }

    private suspend fun persistAssistant(
        sessId: String,
        id: String,
        createdAt: Long,
        text: String,
        reasoning: String? = null,
        totalMs: Long? = null,
        operationId: String? = null,
        round: Int = 0,
        usage: ChatUsage? = null,
        model: ModelConfig? = null,
    ) {
        val message = AssistantText(
            id = id,
            createdAt = createdAt,
            text = text,
            reasoning = reasoning,
            totalMs = totalMs,
            modelId = model?.model,
            providerId = model?.provider,
            promptTokens = usage?.inputTokens?.takeIf { it > 0 }?.toInt(),
            completionTokens = usage?.outputTokens?.takeIf { it > 0 }?.toInt(),
            cachedTokens = usage?.cacheReadTokens?.takeIf { it > 0 }?.toInt(),
        )
        if (operationId != null) {
            val usageEntity = usage?.takeIf { it.hasData }?.let {
                operationCoordinator.usageEntity(
                    sessionId = sessId,
                    operationId = operationId,
                    entryId = id,
                    provider = model?.provider,
                    modelId = model?.model,
                    usage = it,
                )
            }
            operationCoordinator.providerSettled(operationId, message, usage = usageEntity, round = round)
        } else {
            messageStore.append(sessId, message)
        }
        publishPersistedMessage(sessId, message)
    }

    private suspend fun touchSession(sessId: String) {
        sessionDao.touch(sessId, System.currentTimeMillis())
    }

    private fun startForegroundServiceSafe() {
        runCatching {
            val intent = Intent(context, Class.forName("top.wkbin.taixu.service.AgentForegroundService"))
                .setAction("top.wkbin.taixu.action.AGENT_START")
            context.startForegroundService(intent)
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()
    private fun now(): Long = System.currentTimeMillis()

    /** Approve or reject a frozen tool call, then resume the same Agent session. */
    fun resolveApproval(requestId: String, approved: Boolean) {
        loopScope.launch {
            val request = approvalRepository.find(requestId) ?: return@launch
            val sessId = request.sessionId
            if (request.status != top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_PENDING) return@launch
            // The original loop may still be unwinding after it persisted the request.
            // Wait for it before claiming the session slot.
            sessionJobs[sessId]?.takeIf { it.isActive }?.join()

            // —— 审批有效性校验：过期 / 参数摘要 / 工作区 / operation 归属 ——
            // 防止“用户批准的是旧参数、旧环境下的请求，实际执行的却是别的东西”。
            val invalidation = approvalInvalidation(request)
            val claimedStatus = when {
                invalidation != null && request.expiresAt <= now() ->
                    top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_EXPIRED
                invalidation != null ->
                    top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_FAILED
                approved ->
                    top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_APPROVED
                else ->
                    top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_REJECTED
            }
            if (!approvalRepository.claimPending(request.id, claimedStatus)) return@launch

            // Approval resumption is the legitimate successor to a WAITING_APPROVAL run;
            // claim the slot unconditionally (that state still reports busy to senders).
            startClaimedSessionRun(sessId) {
                var approvalResultPersisted = false
                try {
                    val result = if (invalidation != null) {
                        ToolResult(
                            id = newId(),
                            createdAt = now(),
                            toolCallId = request.toolCallId,
                            success = false,
                            output = "$invalidation。该工具调用未执行；如仍需要，请重新发起。",
                        )
                    } else if (approved) {
                        val args = json.parseToJsonElement(request.argumentsJson) as? JsonObject
                            ?: error("审批参数不是 JSON 对象")
                        val tool = HarnessApiMapper.toolByName(request.toolName)
                        toolExecutor.execute(
                            ToolCall(request.toolCallId, request.createdAt, tool, args, rawToolName = request.toolName),
                            sessId,
                            request.workspace,
                            bypassApproval = true,
                        )
                    } else {
                        ToolResult(
                            id = newId(),
                            createdAt = now(),
                            toolCallId = request.toolCallId,
                            success = false,
                            output = "用户拒绝了该工具操作。请尊重用户决定，并选择不需要该权限的替代方案。",
                        )
                    }
                    val activeOperation = operationCoordinator.active(sessId)
                    if (activeOperation != null) {
                        operationCoordinator.toolSettled(activeOperation.id, result, round = 0)
                        publishPersistedMessage(sessId, result)
                    } else {
                        append(sessId, result)
                    }
                    if (invalidation == null) {
                        approvalRepository.mark(
                            request.id,
                            if (approved && result.success) top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_EXECUTED
                            else if (approved) top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_FAILED
                            else top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_REJECTED,
                        )
                    }
                    approvalResultPersisted = true
                    runLoopInternal(sessId, startedAt = now())
                } catch (cancellation: CancellationException) {
                    if (!approvalResultPersisted) {
                        withContext(NonCancellable) {
                            approvalRepository.mark(
                                request.id,
                                if (approved) top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_FAILED
                                else top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_REJECTED,
                            )
                            repairDanglingToolCalls(sessId, interrupted = true)
                        }
                    }
                    throw cancellation
                } catch (throwable: Throwable) {
                    if (!approvalResultPersisted) {
                        approvalRepository.mark(request.id, top.wkbin.taixu.core.database.AgentApprovalRequestEntity.STATUS_FAILED)
                        append(
                            sessId,
                            ToolResult(
                                id = newId(),
                                createdAt = now(),
                                toolCallId = request.toolCallId,
                                success = false,
                                output = "批准操作执行失败：${friendly(throwable)}",
                            ),
                        )
                    }
                    RunResult.Failed(throwable.message ?: "审批操作执行失败")
                }
            }
        }
    }

    /**
     * 审批恢复执行前的四重校验；返回 null 表示有效，非 null 为拒绝原因
     * （会作为 ToolResult 写回，让模型知晓未执行的理由并重新发起）。
     */
    private suspend fun approvalInvalidation(
        request: top.wkbin.taixu.core.database.AgentApprovalRequestEntity,
    ): String? {
        if (request.expiresAt <= now()) {
            val ttlMinutes = ApprovalPolicyEngine.APPROVAL_TTL_MS / 60_000L
            return "该审批已过期（等待超过 $ttlMinutes 分钟）"
        }
        if (request.argsHash.isNotBlank() && request.argsHash != ApprovalPolicyEngine.argsHash(request.argumentsJson)) {
            return "审批记录的参数摘要校验不一致，审批可能已损坏"
        }
        val currentWorkspace = sessionDao.findById(request.sessionId)?.workspace.orEmpty()
        if (currentWorkspace != request.workspace) {
            return "会话工作区已变更（审批时：${request.workspace.ifBlank { "无" }}，当前：${currentWorkspace.ifBlank { "无" }}）"
        }
        val boundOperationId = request.operationId
        if (boundOperationId != null && !operationCoordinator.operationExists(boundOperationId)) {
            return "该审批所属的运行已结束或被新运行接管"
        }
        return null
    }

    private suspend fun appendCapabilityEvents(
        sessId: String,
        userMessageId: String,
        mentionedNames: Set<String>,
        model: ModelConfig,
    ) {
        if (mentionedNames.isEmpty()) return
        val messages = getOrCreateLiveMessages(sessId).value
        if (userMessageId.isBlank()) return
        val skills = runCatching { skillRepository.allSkills.first() }.getOrDefault(emptyList())
            .filter { skill ->
                val names = setOf(skill.name.lowercase(), skill.id.lowercase(), skill.triggerCommand?.removePrefix("/")?.lowercase().orEmpty())
                names.any { it.isNotBlank() && it in mentionedNames }
            }
        skills.forEach { skill ->
            val key = "skill:$userMessageId:${skill.id}"
            if (messages.filterIsInstance<CapabilityEvent>().none { it.id == key }) {
                append(
                    sessId,
                    CapabilityEvent(key, now(), CapabilityEvent.Kind.SKILL, skill.name, skill.description),
                )
            }
        }
        val configuredMcp = runCatching { mcpServerRepository.servers.first() }
            .getOrDefault(emptyList())
            .map { it.id to it.name }
        val discoveredMcp = model.dynamicMcpTools.map { it.serverId to it.serverName }
        (configuredMcp + discoveredMcp)
            .distinctBy { it.first }
            .filter { (serverId, serverName) -> serverId.lowercase() in mentionedNames || serverName.lowercase() in mentionedNames }
            .forEach { (serverId, serverName) ->
                val key = "mcp:$userMessageId:$serverId"
                if (messages.filterIsInstance<CapabilityEvent>().none { it.id == key }) {
                    append(
                        sessId,
                        CapabilityEvent(
                            key,
                            now(),
                            CapabilityEvent.Kind.MCP,
                            serverName,
                            if (model.dynamicMcpTools.any { it.serverId == serverId }) {
                                "MCP 工具已挂载，模型可按需调用"
                            } else {
                                "已选择 MCP 服务，正在尝试发现工具；若服务离线，后续会显示连接错误"
                            },
                        ),
                    )
                }
            }
    }

    companion object {
        const val STREAM_FLUSH_INTERVAL_MS = 80L
        const val MAX_ROUNDS = 200
        val KNOWN_TOOL_NAMES: Set<String> = HarnessTool.entries
            .map { HarnessApiMapper.apiName(it) }
            .toSet() + "subagent"
        const val PROJECT_CONTEXT_MAX_BYTES = 16 * 1024
        const val MAX_STREAM_RETRIES = 5
        const val RETRY_BACKOFF_MS = 1_000L
        const val RETRY_BACKOFF_SEC = 2L
        const val MAX_STATUS_ARG_LENGTH = 60

        val FALLBACK_SYSTEM_PROMPT = """
            你是太墟（TaiXu）内置的 Agent Harness——一个运行在 Android 私有 Linux 沙箱（Debian via PRoot）中的 AI 助手。你通过调用工具完成任务：读写用户工作区的文件、在 Linux 环境执行命令、安装软件、排查问题。

            可用工具与使用指南：

            1. read —— 读取文件内容
               用途：检查文件、查看当前状态、确认现状。
               指南：优先用 read 而不是 cat / sed。读取路径可用相对路径或以 /workspace/ 开头。若文件不存在或读取失败，用 base 的 ls / find 定位后再读。

            2. write —— 创建或完全覆盖文件
               用途：写新文件、整体重写。
               指南：只用于新文件或完整重写；若只想改其中一段，请用 edit。会自动创建父目录。

            3. edit —— 精确文本替换
               用途：修改已有文件的局部内容。
               指南：oldText 必须与文件原文逐字精确匹配且唯一。一次调用可传多个替换，但每个 oldText 都不能重叠或嵌套。oldText 尽量短而唯一；若匹配多处会失败——先 read 确认内容，或提供更多上下文再改。对尚未存在的新文件完全不适用，用 write。

            4. base —— 在 Debian Linux 沙箱中执行 shell 命令
               用途：安装软件（apt-get / npm / pip install）、运行脚本、查看系统状态（文件、进程、网络）、执行任意 bash。
               返回退出码、stdout、stderr。默认超时由用户设置，可用 timeout_seconds 为单次命令指定 1-3600 秒。若执行前需要某个目录，用参数 cwd 指定；当前会话关联了工作区时，默认在工作区目录执行。

            5. process —— 托管跨工具调用持续运行的后台进程
               start 时提供稳定 id 和前台运行命令；随后用 status/logs/list/stop 管理。不要在命令中使用 nohup、& 或自行 daemonize，PRoot 子进程必须由 TaiXu 生命周期注册表持有。

            运行环境约束（PRoot 沙箱，务必遵守，不要浪费时间在注定失败的操作上）：
            - 你运行在 Android 设备上的 PRoot Debian 沙箱中：没有真正的 root 权限。chown/chgrp 改属主、mount、insmod、sysctl 大部分参数、设置 capabilities 等内核级操作会被静默忽略或失败——不要尝试，也不要因为命令返回成功就误以为生效。
            - 文件权限与属主由 PRoot 模拟。perl 等程序可能因“幽灵”硬链接报错：遇到时改用符号链接（ln -s）替代。锁文件（*.lock、groupadd 的锁机制）在沙箱里可能异常，必要时直接写配置文件或清理残留锁。
            - dpkg 升级含 setuid 文件的包（util-linux 的 su/mount/umount、login 的 newgrp 等）会卡死在 "unable to securely remove *.dpkg-tmp"：PRoot 下无法删除 setuid 的解包残留。已验证的解法：先 rm 所有 .dpkg-tmp 残留，再 chmod u-s,g-s 降级现存的 setuid 目标文件，然后 dpkg -i 重装。装完后文件会恢复 setuid 标记，下次大版本升级可能再卡，同样处理即可——不要反复重试 dpkg，也不要试图让 setuid 真正生效。
            - 没有 systemd：服务不会自启，systemctl 不可用。需要常驻进程时必须使用 process 工具托管，并让命令保持前台运行；普通 base 中的 nohup 或 & 无法跨 PRoot 会话存活。
            - /proc、/sys 部分内容反映的是宿主 Android 系统，不要据此判断 Debian 的状态。
            - 设备 CPU/IO 弱于服务器：编译、apt upgrade 等操作耗时长属正常现象；重操作前先告知用户预计耗时。
            - 遇到奇怪的错误（Bad substitution、dpkg -V 报缺文档、权限异常）优先怀疑是沙箱差异而非系统损坏；确认无实际影响后继续，不要反复重试同一命令，也不要试图“修复”沙箱本身。
            - 工具输出可能被截断：需要完整输出时用 grep/head/tail 截取关键部分，而不是重复执行。

            工作方式（行动优先，直接交付，绝不墨迹）：
            - 直接行动与代码交付：当用户要求创建项目、编写代码、修改逻辑或配置环境时，直接调用 write / edit 工具创建或修改完整工程文件，立即交付成果！严禁在无必要时反复执行环境探测命令，严禁在未受阻碍时反复向用户询问多余的确认问题。
            - 敏捷思考：内部推理与思考（thinking 内容）一律使用中文，必须精炼敏捷、直奔关键决策，严禁冗长铺垫与自言自语；理清第一步后立即调用工具行动。
            - 需要信息时先 read / base 获取事实，不要凭空猜测或编造内容。
            - 失败时读取真实错误输出并自我纠正（换路径、装依赖、重试）。
            - 尽量一次完成用户要求：安装或编写完成后汇报真实结果。
            - 用简洁中文汇报；不空话客套；绝不复述或暴露 API Key / Token 等机密。
            - 仅在涉及不可逆破坏性操作（如 rm -rf / 等）时才向用户确认，常规开发任务直接执行！
        """.trimIndent()
    }
}

private class ApprovalPauseException : RuntimeException()
