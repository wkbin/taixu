package top.wkbin.taixu.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.taixu.core.tools.AiProfileWriter
import top.wkbin.taixu.core.model.ExecutionMode
import top.wkbin.taixu.core.model.McpConnectionState
import top.wkbin.taixu.core.model.ApprovalMode
import top.wkbin.taixu.core.model.RunMode
import top.wkbin.taixu.core.model.RunningSendMode
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.HarnessSessionEntity
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.database.McpServerRepository
import top.wkbin.taixu.core.database.AgentApprovalRepository
import top.wkbin.taixu.core.database.AgentApprovalRequestEntity
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.harness.session.InteractiveSessionControl
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.SkillSuggestion
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.PendingMessage
import top.wkbin.taixu.harness.QueuedPrompt
import top.wkbin.taixu.harness.ContextWindowPolicy
import top.wkbin.taixu.harness.compaction.CompactedContext
import top.wkbin.taixu.harness.ToolCallMode
import top.wkbin.taixu.harness.prompt.SystemPromptBuilder
import top.wkbin.taixu.harness.events.HarnessEvent
import top.wkbin.taixu.harness.events.HarnessEventBus
import top.wkbin.taixu.harness.workflow.ProactiveWorkflowAdvisor
import top.wkbin.taixu.harness.workflow.ProactiveWorkflowSuggestion
import top.wkbin.taixu.harness.mcp.McpManager
import top.wkbin.taixu.harness.session.ConversationBranch
import top.wkbin.taixu.harness.session.ConversationBranchKind
import top.wkbin.taixu.harness.session.LaneManager
import top.wkbin.taixu.runtime.WorkspaceManager
import top.wkbin.taixu.runtime.WorkspaceProject
import top.wkbin.taixu.core.tools.AgentModelDiscovery
import top.wkbin.taixu.core.tools.AgentProviderCatalog
import top.wkbin.taixu.core.tools.ProviderEndpointPolicy
import top.wkbin.taixu.core.tools.ProviderRepository
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import top.wkbin.taixu.feature.chat.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import top.wkbin.taixu.runtime.terminal.TerminalSessionManager
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import top.wkbin.taixu.core.common.navigation.AppNavigationTarget
import top.wkbin.taixu.core.common.navigation.GlobalNavigationBus
import top.wkbin.taixu.core.common.translation.TranslationManager
import top.wkbin.taixu.core.database.AgentContextRepository
import top.wkbin.taixu.core.database.AgentMemoryEntity
import top.wkbin.taixu.core.database.AgentPlanEntity
import top.wkbin.taixu.core.database.AgentScratchpadEntity
import top.wkbin.taixu.core.database.QuickPhraseRepository
import top.wkbin.taixu.core.database.WorkflowRepository
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.core.model.InstalledDistro
import top.wkbin.taixu.core.model.McpServerConfig
import top.wkbin.taixu.core.model.QuickPhrase
import top.wkbin.taixu.core.model.SessionRunState
import top.wkbin.taixu.harness.MentionExtractor
import top.wkbin.taixu.harness.checkpoint.RewindScope
import top.wkbin.taixu.harness.compaction.CompactionManager
import top.wkbin.taixu.harness.compaction.CompactionSnapshot
import top.wkbin.taixu.harness.diagnostics.RequestDiagnosticsStore
import top.wkbin.taixu.harness.mcp.McpWorkspaceRecommender
import top.wkbin.taixu.harness.session.SessionModelSwitcher
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.RuntimePathManager
import top.wkbin.taixu.runtime.privilege.PrivilegeManager
import top.wkbin.taixu.ui.components.RuntimeIconName

private const val MAX_RUNTIME_EVENTS = 160
private const val TAG = "ChatViewModel"
private const val KEY_INPUT_DRAFT = "chat_input_draft"

data class SubagentResultUiState(
    val sessionId: String,
    val branch: ConversationBranch,
    val messages: List<HarnessMessage> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

data class WorkflowLaunchRequest(
    val workflowId: String?,
    val projectName: String,
    val initialVariables: Map<String, String> = emptyMap(),
)

/** 空会话首屏的权限感知引导档位；决定开场提示卡的文案与色调。 */
enum class OnboardingPrivilege { SANDBOX, SANDBOX_UNLOCKABLE, SHIZUKU_READY, ROOT_READY }

class ChatViewModel(
    private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val harnessLoop: InteractiveSessionControl,
    private val requestDiagnostics: RequestDiagnosticsStore,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val sessionDao: HarnessSessionRepository,
    private val aiModelDao: AiModelRepository,
    private val workspaceManager: WorkspaceManager,
    private val settingsDataStore: AgentPreferences,
    private val linuxRuntime: LinuxRuntime,
    private val terminalSessionManager: TerminalSessionManager,
    private val mcpManager: McpManager,
    private val agentSkillRepository: AgentSkillRepository,
    private val mcpServerRepository: McpServerRepository,
    private val approvalRepository: AgentApprovalRepository,
    private val agentContextDao: AgentContextRepository,
    private val compactionManager: CompactionManager,
    private val sessionModelSwitcher: SessionModelSwitcher,
    private val quickPhraseRepository: QuickPhraseRepository,
    private val laneManager: LaneManager,

    private val eventBus: HarnessEventBus,
    private val proactiveWorkflowAdvisor: ProactiveWorkflowAdvisor,
    private val modelDiscovery: AgentModelDiscovery,
    private val providerCatalog: AgentProviderCatalog,
    private val providerRepository: ProviderRepository,
    private val profileWriter: AiProfileWriter,
    private val privilegeManager: PrivilegeManager,
    private val pathManager: RuntimePathManager,
    private val workflowRepository: WorkflowRepository,
    val translationManager: TranslationManager? = null,
    val globalNavigationBus: GlobalNavigationBus? = null,
) : ViewModel() {
    private val _workflowLaunchRequests = MutableSharedFlow<WorkflowLaunchRequest>(extraBufferCapacity = 2)
    val workflowLaunchRequests: SharedFlow<WorkflowLaunchRequest> = _workflowLaunchRequests
    private val _workflowSuggestions = MutableStateFlow<List<ProactiveWorkflowSuggestion>>(emptyList())
    val workflowSuggestions: StateFlow<List<ProactiveWorkflowSuggestion>> = _workflowSuggestions.asStateFlow()

    /** 模型回复里引用的沙箱绝对路径（如 /workspace/xxx.jpg）到宿主真实目录的映射，供聊天媒体渲染把 PRoot 内路径翻译成 Android 可读文件。 */
    val sandboxHostRoots: Map<String, File> = mapOf(
        "workspace" to pathManager.workspaceDir,
        "attachments" to pathManager.attachmentsDir,
    )

    /** 空会话首屏权限感知引导：按实际特权状态给出不同玩法提示。 */
    val privilegeOnboarding: StateFlow<OnboardingPrivilege?> =
        flow { emit(privilegeManager.getPrivilegeInfo()) }
            .map { info ->
                when {
                    info.mode == ExecutionMode.ROOT && info.modeActive -> OnboardingPrivilege.ROOT_READY
                    info.mode == ExecutionMode.SHIZUKU && info.modeActive -> OnboardingPrivilege.SHIZUKU_READY
                    info.shizukuAvailable || info.rootAvailable -> OnboardingPrivilege.SANDBOX_UNLOCKABLE
                    else -> OnboardingPrivilege.SANDBOX
                }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _eventHistory = MutableStateFlow<Map<String, List<HarnessEvent>>>(emptyMap())
    private val _permissionRequests = MutableSharedFlow<HarnessEvent.PermissionRequired>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val permissionRequests: SharedFlow<HarnessEvent.PermissionRequired> = _permissionRequests

    init {
        A2uiChatBridge.bind(harnessLoop, context)
        viewModelScope.launch {
            quickPhraseRepository.ensureInitialized()
            workflowRepository.ensureBuiltins()
        }
        viewModelScope.launch {
            eventBus.events.collect { event ->
                _eventHistory.value = _eventHistory.value.toMutableMap().apply {
                    this[event.sessionId] = (this[event.sessionId].orEmpty() + event).takeLast(MAX_RUNTIME_EVENTS)
                }
                if (event is HarnessEvent.PermissionRequired) {
                    _permissionRequests.tryEmit(event)
                }
            }
        }
        viewModelScope.launch {
            proactiveWorkflowAdvisor.observeSuggestions().collect { suggestion ->
                _workflowSuggestions.update { current ->
                    (listOf(suggestion) + current.filterNot { it.workflowId == suggestion.workflowId }).take(3)
                }
            }
        }
    }

    val quickPhrases: StateFlow<List<QuickPhrase>> = quickPhraseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeDistroId: StateFlow<String> = linuxRuntime.activeDistroId
    val installedDistros: StateFlow<List<InstalledDistro>> = linuxRuntime.installedDistros

    fun switchDistro(distroId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 先关闭所有旧系统 PTY 会话，再切换发行版
            terminalSessionManager.closeAllSessions()
            linuxRuntime.switchActiveDistro(distroId)
        }
    }
    private val sessionDeletionFeedback = SessionDeletionFeedback()
    val messages: StateFlow<List<HarnessMessage>> = harnessLoop.messages
    val running: StateFlow<Boolean> = harnessLoop.running
    val error: StateFlow<String?> = sessionDeletionFeedback.errors(harnessLoop.error, viewModelScope)
    val status: StateFlow<String?> = harnessLoop.status
    val thinkingLive: StateFlow<Boolean> = harnessLoop.thinkingLive
    val workspace: StateFlow<String> = harnessLoop.workspace
    val projectType: StateFlow<String> = harnessLoop.projectType
    /** 基于当前工作区内容自动推荐的 MCP 预设（已启用的已过滤），仅提示不自动启用。 */
    val mcpRecommendations: StateFlow<List<McpWorkspaceRecommender.Recommendation>> =
        harnessLoop.mcpRecommendations

    fun enableMcpRecommendation(presetId: String) = harnessLoop.enableRecommendedMcp(presetId)
    fun dismissMcpRecommendation(presetId: String) = harnessLoop.dismissMcpRecommendation(presetId)
    fun dismissWorkflowSuggestion(workflowId: String) {
        _workflowSuggestions.update { suggestions -> suggestions.filterNot { it.workflowId == workflowId } }
    }

    private val _localHiddenSkillSuggestions = MutableStateFlow<Set<String>>(emptySet())
    /** 已应用或忽略的技能进化建议 id；卡片从列表隐藏，转写消息本身保留。通过 DataStore 持久化防杀进程重复展示。 */
    val hiddenSkillSuggestions: StateFlow<Set<String>> = combine(
        settingsDataStore.dismissedSkillSuggestions,
        _localHiddenSkillSuggestions,
        agentSkillRepository.allSkills,
        harnessLoop.messages,
    ) { persisted, local, skills, messages ->
        SkillSuggestionActions.computeHiddenSuggestionIds(persisted, local, skills, messages)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptySet(),
    )

    fun dismissSkillSuggestion(id: String) {
        val suggestionId = id.trim()
        if (suggestionId.isEmpty()) return
        _localHiddenSkillSuggestions.update { it + suggestionId }
        viewModelScope.launch {
            settingsDataStore.dismissSkillSuggestion(suggestionId)
        }
    }

    /** 应用技能进化建议：创建新技能或更新既有自定义技能（内置技能不可覆盖，降级为新建）；[createNew] 为 true 时始终新建（对应卡片「创建技能 / 另存为新技能」）。 */
    fun applySkillSuggestion(suggestion: SkillSuggestion, createNew: Boolean) {
        val trimmedName = suggestion.skillName.trim()
        val trimmedPrompt = suggestion.systemPrompt.trim()
        if (trimmedName.isBlank() || trimmedPrompt.isBlank()) {
            dismissSkillSuggestion(suggestion.id)
            return
        }
        viewModelScope.launch {
            try {
                val skills = agentSkillRepository.allSkills.first()
                val existing = SkillSuggestionActions.resolveExisting(suggestion, createNew, skills)
                val skill = SkillSuggestionActions.toCustomSkill(suggestion, existing)
                agentSkillRepository.addCustom(skill)
                dismissSkillSuggestion(suggestion.id)
                _notice.value = if (existing != null) {
                    context.getString(R.string.chat_skill_suggestion_updated, skill.name)
                } else {
                    context.getString(R.string.chat_skill_suggestion_created, skill.name)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "applySkillSuggestion failed", t)
                _notice.value = context.getString(
                    R.string.chat_skill_suggestion_apply_failed,
                    t.message ?: "unknown",
                )
            }
        }
    }

    fun launchWorkflowSuggestion(suggestion: ProactiveWorkflowSuggestion) {
        dismissWorkflowSuggestion(suggestion.workflowId)
        _workflowLaunchRequests.tryEmit(
            WorkflowLaunchRequest(suggestion.workflowId, suggestion.projectName, suggestion.initialVariables),
        )
    }
    /** 运行中排队的待发送消息（当前任务结束后自动接续）。 */
    val pendingMessages: StateFlow<List<PendingMessage>> = harnessLoop.pendingMessages
    val queuedPrompts: StateFlow<List<QueuedPrompt>> = harnessLoop.queuedPrompts

    /** 发送键语义：运行中跟随「运行中发送方式」偏好，空闲恒为 NEXT_RUN（空闲发送即发起新一轮，无插队语境）。 */
    private val runningSendModePref: StateFlow<RunningSendMode> = settingsDataStore.runningSendMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, RunningSendMode.QUEUE)
    val sendMode: StateFlow<ComposerSendMode> = combine(harnessLoop.running, runningSendModePref) { running, mode ->
        if (running && mode == RunningSendMode.STEER) ComposerSendMode.STEER else ComposerSendMode.NEXT_RUN
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ComposerSendMode.NEXT_RUN)

    val runtimeEvents: StateFlow<List<HarnessEvent>> = combine(
        harnessLoop.currentSessionId,
        _eventHistory,
        // 把「消息列表」打包成 (revision, list) 再 distinctUntilChanged by revision：
        // revision = 数量 + 末条 id，流式 token 增量不会改变它，因此上游发射被压缩为
        // 「真正新增/替换了一条消息」才触发，避免每帧全量重合成上千条事件（聊久了变卡的主因）。
        // 用 Pair 一起传下去，避免在 lambda 里读 messages.value 拿到过期值的时序问题。
        messages.map { list -> (list.size to list.lastOrNull()?.id) to list }
            .distinctUntilChanged { a, b -> a.first == b.first },
    ) { sessionId, history, revisionAndList ->
        val live = history[sessionId].orEmpty()
        mergeHistoricalAndLiveEvents(sessionId, revisionAndList.second, live)
    }
        // 首屏卡顿修复（P1）：合成历史事件（synthesizeHistoricalEvents）是 O(n) 遍历，
        // stateIn 默认在 Main 上跑，首订阅时会整段压在主线程。移到 Default 执行。
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _branchRefresh = MutableStateFlow(0)
    private val branchMessageRevision = messages.map { list -> list.size to list.lastOrNull()?.id }.distinctUntilChanged()
    // 子智能体在独立 lane 中执行时主会话消息不变，用运行事件驱动分支重投影，
    // 这样运行中也能在协同卡片里点进子 lane 看实时进展。
    private val branchEventRevision = runtimeEvents.map { events ->
        events.size to (events.lastOrNull()?.hashCode() ?: 0)
    }.distinctUntilChanged()
    @OptIn(ExperimentalCoroutinesApi::class)
    val branches: StateFlow<List<ConversationBranch>> = combine(
        harnessLoop.currentSessionId,
        branchMessageRevision,
        _branchRefresh,
        branchEventRevision,
    ) { sessionId, messageRevision, refresh, eventRevision ->
        // 关键：把四个流压缩成一个「可比较的重投影信号」，而不是丢弃后三个。
        // 此前写法为 `{ sessionId, _, _, _ -> sessionId }`，后三个流的变化被完全吞掉，
        // 导致 _branchRefresh++ 与运行事件（子智能体进展）都无法触发重投影——
        // 注释声称「用运行事件驱动分支重投影」，实现却做不到（注释与实现两张皮）。
        // 这里显式纳入 messageRevision / refresh / eventRevision，任一变化都会重新拉取分支。
        ProjectionKey(
            sessionId = sessionId,
            messageRevision = messageRevision,
            refresh = refresh,
            eventRevision = eventRevision,
        )
    }.distinctUntilChanged().mapLatest { key ->
        val sessionId = key.sessionId
        if (sessionId.isBlank()) emptyList() else runCatching { laneManager.branches(sessionId) }.getOrDefault(emptyList())
    }
        // 首屏卡顿修复（P0）：laneManager.branches() 内部会读取该会话**全部** entry
        // （HarnessRuntimeDao.listEntries 无 LIMIT）并逐个 leaf 做路径回溯 + payload 解码，
        // 属 O(entries) 的重活。stateIn(viewModelScope) 默认跑在 Dispatchers.Main，
        // 而这条链此前没有任何 flowOn —— 于是「刚进入聊天界面」首次订阅时，
        // 全量投影直接压在主线程上，与首帧布局争抢，表现为进入即卡。
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前选中的会话 ID */
    val currentSessionId: StateFlow<String> = harnessLoop.currentSessionId
    /** 所有会话的多 Agent 并发运行状态映射 (IDLE / RUNNING / COMPLETED / FAILED) */
    val sessionRunStates: StateFlow<Map<String, SessionRunState>> = harnessLoop.sessionRunStates
    @OptIn(ExperimentalCoroutinesApi::class)
    val pendingApprovals: StateFlow<List<AgentApprovalRequestEntity>> = harnessLoop.currentSessionId.flatMapLatest { sessionId ->
        if (sessionId.isBlank()) flowOf(emptyList()) else approvalRepository.pendingForSession(sessionId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前会话的活跃结构化任务规划（模型通过 plan 工具写入的 AgentPlanEntity）。以 currentSessionId + 运行状态为键重新读取：一轮执行内状态多次变化，借此近似实时刷新看板进度；无规划或非活跃时为 null。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activePlan: StateFlow<AgentPlanEntity?> =
        combine(harnessLoop.currentSessionId, harnessLoop.status) { sessionId, status ->
            // 原写法 lambda 输出恒等于 sessionId，配合下游 distinctUntilChanged 去重后，
            // status 的变化被完全吞掉；而一轮执行内状态多次变化（工具往返、压缩、等待审批），
            // 看板进度因此无法实时刷新。这里让 status 真正参与，状态每变一次即重读。
            sessionId to status
        }
            .distinctUntilChanged()
            .flatMapLatest { (sessionId, _) ->
                flow {
                    emit(if (sessionId.isBlank()) null else agentContextDao.getActivePlan(sessionId)?.takeIf { it.status == "active" })
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前会话最近一次上下文压缩的快照（折叠条数 + 摘要预览）；会话从未压缩时为 null——UI 据此隐藏提示横幅。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeCompaction: StateFlow<CompactionSnapshot?> =
        // 原写法 combine(currentSessionId, messages) { sessionId, _ -> sessionId }
        // 的 lambda 输出恒等于 sessionId：messages 变化虽会触发重跑，但输出值不变，
        // 若上游加了 distinctUntilChanged 则变化被吞。上下文压缩恰好伴随状态切换
        // （运行 → 压缩 → 运行），以 (sessionId, status) 作为重投影信号更贴合语义，
        // 状态每变一次即重读快照。
        combine(harnessLoop.currentSessionId, harnessLoop.status) { sessionId, status ->
            sessionId to status
        }
            .distinctUntilChanged()
            .flatMapLatest { (sessionId, _) ->
                flow {
                    emit(if (sessionId.isBlank()) null else compactionManager.latestSnapshot(sessionId))
                }
            }
            // 首屏修复（P1）：latestSnapshot 会读 DB（lane 查询 + entry 解码），
            // 首订阅时不应压在主线程，与 branches/runtimeEvents 一并移到 Default。
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 全量长期记忆（memory 工具写入），供记忆抽屉管理与模型上下文核对。 */
    val memories: StateFlow<List<AgentMemoryEntity>> =
        agentContextDao.observeAllMemories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val scratchpadRefresh = MutableStateFlow(0)

    /** 当前会话的草稿便签；随运行状态变化与手动刷新重建。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val scratchpads: StateFlow<List<AgentScratchpadEntity>> =
        combine(
            harnessLoop.currentSessionId,
            harnessLoop.status,
            scratchpadRefresh,
        ) { sessionId, status, refresh -> Triple(sessionId, status, refresh) }
            .distinctUntilChanged()
            .flatMapLatest { (sessionId, _, _) ->
                flow {
                    emit(if (sessionId.isBlank()) emptyList() else agentContextDao.listScratchpads(sessionId))
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteMemory(id: String) {
        viewModelScope.launch { agentContextDao.deleteMemoryById(id) }
    }

    fun deleteScratchpad(key: String) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            agentContextDao.deleteScratchpad(sessionId, key)
            scratchpadRefresh.value++
        }
    }

    fun clearScratchpads() {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            agentContextDao.clearScratchpads(sessionId)
            scratchpadRefresh.value++
        }
    }

    fun resolveApproval(requestId: String, approved: Boolean, rememberForSession: Boolean = false) {
        harnessLoop.resolveApproval(requestId, approved, rememberForSession)
    }

    /** 提交 ask_user 问题卡的回答；答案作为该工具调用的结果落库并续跑会话。 */
    fun resolveQuestion(requestId: String, answersJson: String) {
        harnessLoop.resolveQuestion(requestId, answersJson)
    }

    val sessions: StateFlow<List<HarnessSessionEntity>> = sessionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setCurrentSessionApprovalMode(mode: ApprovalMode) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            sessionDao.setApprovalMode(sessionId, mode.id, System.currentTimeMillis())
        }
    }

    /** 切换当前会话的运行意图（BUILD / PLAN）；下次工具调用即按新模式门禁。 */
    fun setCurrentSessionRunMode(mode: RunMode) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            sessionDao.setRunMode(sessionId, mode.id, System.currentTimeMillis())
        }
    }

    val models: StateFlow<List<AiModelEntity>> = aiModelDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前会话绑定的模型档案。占用圆环 / 压缩预算必须跟会话走，不能回落到创建会话时的全局 isActive 默认模型。 */
    private val sessionBoundModel: StateFlow<AiModelEntity?> = combine(
        models,
        sessions,
        currentSessionId,
    ) { currentModels, currentSessions, sessionId ->
        val session = currentSessions.firstOrNull { it.id == sessionId }
        session?.modelId?.let { id -> currentModels.firstOrNull { it.id == id } }
            ?: currentModels.firstOrNull { it.isActive }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val workspaces: StateFlow<List<WorkspaceProject>> = workspaceManager.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 思考过程块是否默认展开（持久化，重启后保留）。 */
    val thinkingExpanded: StateFlow<Boolean> = settingsDataStore.thinkingExpanded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setThinkingExpanded(value: Boolean) { viewModelScope.launch { settingsDataStore.setThinkingExpanded(value) } }
    /** 思考过程是否在展开时自动翻译为中文。 */
    val thinkingAutoTranslate: StateFlow<Boolean> = settingsDataStore.thinkingAutoTranslate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    /** 历史轮次中间过程自动折叠（默认关闭；仅用户主动开启后生效）。 */
    val chatRoundCollapse: StateFlow<Boolean> = settingsDataStore.chatRoundCollapse
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun navigateToAgentSettings() {
        globalNavigationBus?.navigateTo(AppNavigationTarget.AgentSettings)
    }

    // 输入草稿：同步写入 SavedStateHandle，进程重建 / 旋转后可恢复
    private val _input = MutableStateFlow(savedStateHandle.get<String>(KEY_INPUT_DRAFT) ?: "")
    val input: StateFlow<String> = _input.asStateFlow()

    /** 统一输入写入入口：StateFlow 供组合使用，SavedStateHandle 供状态恢复使用 */
    private fun setInput(value: String) {
        _input.value = value
        savedStateHandle[KEY_INPUT_DRAFT] = value
    }

    // 系统分享入口：预填文本 + 快捷指令行（UI 见 ChatShareSupport）
    private val _pendingShare = MutableStateFlow<String?>(null)

    /** 分享快捷指令行状态（null=隐藏）；分享文本只预填输入框，不自动发送。 */
    val shareActions: StateFlow<ShareQuickActionsState?> = _pendingShare.map { text ->
        text?.let { ShareQuickActionsState(it, ::resolveShareAction, ::dismissPendingShare) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 系统分享进入：预填输入框并展示快捷指令行。 */
    fun onSharedTextReceived(text: String) {
        if (text.isBlank()) return
        setInput(text)
        _pendingShare.value = text
    }

    private fun resolveShareAction(action: ShareQuickAction) {
        val text = _pendingShare.value ?: return
        _pendingShare.value = null
        send(wrapSharePrompt(action, text))
    }

    /** 关闭快捷指令行，保留输入框中已预填的文本。 */
    fun dismissPendingShare() {
        _pendingShare.value = null
    }

    val activeSkills: StateFlow<List<AgentSkill>> = agentSkillRepository.activeSkills
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allSkills: StateFlow<List<AgentSkill>> = agentSkillRepository.allSkills
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val mcpServers: StateFlow<List<McpServerConfig>> = mcpServerRepository.servers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 真实压缩投影缓存（与引擎 ApiContextAssembler 同源）：按 (sessionId, compactionRevision) 从树读取 `CompactionManager.project()`。只在会话切换或某次压缩落库后才重读，避免流式期间（每 token 一次）对 DAO 的频繁查询。键必须同时含 revision：只发 sessionId 会被 distinctUntilChanged 吞掉压缩信号，面板就只有重进应用（ViewModel 重建）才能看到压缩后的用量。已折叠历史以摘要层形式计 token，不再重复计入对话体积。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val compactedContext: StateFlow<CompactedContext?> =
        combine(harnessLoop.currentSessionId, compactionManager.compactionRevision) { sessionId, revision ->
            sessionId to revision
        }
            .distinctUntilChanged()
            .mapLatest { (sessionId, _) -> compactionManager.project(sessionId) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前会话上下文用量的 UI 估算。Harness 发请求时会用同一字符/token 近似值再做最终压缩，因此这里明确是预估值，而不是 provider 返回的精确 tokenizer 计数。 */
    val contextUsage: StateFlow<ContextUsage> = combine(
        // 用 revision（消息数量 + 末条 id + 末条内容长度）压缩上游：
        // contextUsage 的计算含 estimateEffectiveUsage（遍历全部消息）与两次 filterIsInstance 求和，
        // 都是 O(n)。流式期间 messages 每个 token 块都换新引用，若不压缩会每帧全量重算，
        // 叠加列表渲染开销后表现为「聊久了明显变卡」。
        // 末条内容长度必须计入：流式时末条长度持续变化，是 contextUsage 真正需要更新的信号。
        messages.map { list ->
            Triple(
                list.size,
                list.lastOrNull()?.id,
                list.lastOrNull()?.let { m ->
                    when (m) {
                        is AssistantText -> m.text.length
                        is ToolResult -> m.output.length
                        is UserMessage -> m.text.length
                        else -> 0
                    }
                },
            ) to list
        }.distinctUntilChanged { a, b -> a.first == b.first },
        sessionBoundModel,
        settingsDataStore.contextBudgetTokens,
    ) { revisionAndMessages, boundModel, defaultBudget ->
        ContextUsageInputs(
            currentMessages = revisionAndMessages.second,
            // 与顶栏同源：会话绑定模型优先，回退全局 isActive（详见 sessionBoundModel）。
            activeModel = boundModel,
            defaultBudget = defaultBudget,
        )
    }.combine(currentSessionId) { inputs, sessionId ->
        inputs to sessionId
    }.combine(settingsDataStore.contextCompactionEnabled) { (inputs, sessionId), compactionEnabled ->
        Triple(inputs, sessionId, compactionEnabled)
    }.combine(settingsDataStore.contextFoldingRatioPercent) { (inputs, sessionId, compactionEnabled), foldingRatioPercent ->
        ContextUsageCalculation(inputs, sessionId, compactionEnabled, foldingRatioPercent)
    }.combine(systemPromptBuilder.usageSnapshots) { calculation, snapshots ->
        calculation to snapshots
    }.combine(compactedContext) { (calculation, snapshots), compacted ->
        val (inputs, sessionId, compactionEnabled, foldingRatioPercent) = calculation
        val activeModel = inputs.activeModel
        val pureChat = activeModel?.pureChatMode == true
        val toolDisabled = pureChat || activeModel?.toolCallMode.equals("disabled", ignoreCase = true)
        val toolCallMode = when {
            toolDisabled -> ToolCallMode.DISABLED
            activeModel?.toolCallMode.equals("json", ignoreCase = true) -> ToolCallMode.JSON_TEXT
            else -> ToolCallMode.NATIVE
        }
        val snapshot = snapshots[sessionId]?.takeIf { it.toolCallMode == toolCallMode }

        val systemPromptTokens = if (pureChat) 0 else snapshot?.systemTokens ?: ContextWindowPolicy.DEFAULT_SYSTEM_PROMPT_TOKENS
        // NATIVE 的 tools 数组与 JSON_TEXT 注入 system 的 schema 都占真实上下文，
        // 只有纯聊天 / 工具禁用（toolDisabled）才为 0；按 toolCallMode != NATIVE 归零会漏算 JSON_TEXT 的 5.5k。
        val toolDefinitionTokens = if (toolDisabled) 0 else
            snapshot?.toolDefinitionTokens ?: ContextWindowPolicy.DEFAULT_NATIVE_TOOL_TOKENS
        val rulesTokens = if (pureChat) 0 else snapshot?.rulesTokens ?: ContextWindowPolicy.DEFAULT_RULES_TOKENS
        // 技能正文仅在被 @ 提及后注入。尚无请求快照时不把所有已启用技能误算为常驻正文。
        val skillTokens = if (pureChat) 0 else snapshot?.skillsTokens ?: 0
        val mcpTokens = if (pureChat) 0 else snapshot?.mcpTokens ?: 0
        val subagentTokens = if (toolDisabled) 0 else snapshot?.subagentTokens ?: ContextWindowPolicy.DEFAULT_SUBAGENT_TOKENS

        val totalSystemTokens = systemPromptTokens + toolDefinitionTokens + rulesTokens + skillTokens + mcpTokens + subagentTokens
        // 折叠线只扣除持续占据上下文且不在 messages 内的真实提示开销；
        // toolDefinitionTokens 已由 foldingLimitFor 内部的工具 schema 预留支付（NATIVE/JSON_TEXT 才预留，
        // 纯聊天与工具禁用时为 0），不能在这里再扣一次（旧实现双重扣减，会把 1M 模型错误压到 67K 左右）。
        val promptOverheadTokens = systemPromptTokens + rulesTokens + skillTokens + mcpTokens + subagentTokens
        // 与引擎 ApiContextAssembler 同口径按模式取工具 schema 预留
        val toolSchemaReserveTokens = ContextWindowPolicy.toolSchemaReserveTokensFor(pureChat, toolDisabled)
        val declaredTokens = activeModel?.contextTokens
        val effectiveContextWindow = ContextWindowPolicy.resolveContextWindow(
            declaredContextTokens = declaredTokens,
            modelId = activeModel?.model,
            providerId = activeModel?.provider,
        )
        // 与引擎 ApiContextAssembler 同源：占用判定与折叠都走 clampedBudget。
        val budget = ContextWindowPolicy.clampedBudget(
            declaredTokens,
            inputs.defaultBudget,
            modelId = activeModel?.model,
            providerId = activeModel?.provider,
        )

        // 与引擎同源（ApiContextAssembler）：把消息投影成「实际会发送的那份」再估算。
        // 引擎在压缩判定前会截断老轮次工具结果（浏览器快照、长 read 等大输出），面板此前漏了这一步，
        // 导致已用量虚高（实测 457.8K vs 实际发送 ~141K，约 3 倍）。收敛到 ContextWindowPolicy.projectForUsage。
        //
        // 关键修复：投影必须基于「真实压缩投影」compacted（CompactionManager.project()）而不是
        // 全量转写 inputs.currentMessages。压缩是树状折叠，UI 转写（SessionMessageProjector）只增不删，
        // 旧实现拿全量转写每次重新做预算折叠，导致手动/系统自动压缩成功后面板用量纹丝不动。
        // compacted.messages 即引擎发送形态（summary 层 + retained + healed + after）。
        val projectedMessages = ContextWindowPolicy.projectForUsage(
            messages = compacted?.messages ?: inputs.currentMessages,
            compactionEnabled = compactionEnabled,
        )

        // 与引擎同源（ApiContextAssembler 折叠线）：真实摘要层与召回后缀都计为固定系统开销，
        // 从历史预算中扣除，否则面板的折叠触发线会比引擎实际行为偏高。
        // summaryTokens 同时计入「已折叠对话」细分——estimateEffectiveUsage 的 summarizedTokens
        // 只反映预算驱动的再折叠，压缩后保留窗不再越过预算线 → 恒为 0，若不补真值，压缩这种
        // 最大的体积削减会从面板上完全消失。
        val summaryTokens = if (compactionEnabled) {
            ContextWindowPolicy.estimateTokens(compacted?.summaryLayer.orEmpty())
        } else 0
        val recallTokens = if (compactionEnabled) {
            ContextWindowPolicy.estimateTokens(compacted?.recallBlocks?.values.orEmpty().joinToString("\n"))
        } else 0
        val foldingOverheadTokens = promptOverheadTokens + summaryTokens + recallTokens

        val effectiveUsage = ContextWindowPolicy.estimateEffectiveUsage(
            messages = projectedMessages,
            budget = budget,
            systemTokens = foldingOverheadTokens,
            compactionEnabled = compactionEnabled,
            systemPromptTokens = systemPromptTokens,
            toolDefinitionTokens = toolDefinitionTokens,
            rulesTokens = rulesTokens,
            skillsTokens = skillTokens,
            mcpTokens = mcpTokens,
            subagentTokens = subagentTokens,
            foldingRatioPercent = foldingRatioPercent,
            toolSchemaReserveTokens = toolSchemaReserveTokens,
        )
        val totalPromptTokens = inputs.currentMessages.filterIsInstance<AssistantText>().mapNotNull { it.promptTokens?.toLong() }.sum()
        val totalCachedTokens = inputs.currentMessages.filterIsInstance<AssistantText>().mapNotNull { it.cachedTokens?.toLong() }.sum()
        val cacheHitPct = if (totalPromptTokens > 0L && totalCachedTokens > 0L) {
            ((totalCachedTokens * 100L) / totalPromptTokens).toInt().coerceIn(1, 100)
        } else null

        val compactionThresholdTokens = ContextWindowPolicy.foldingLimitFor(
            budget = budget,
            ratioPercent = foldingRatioPercent,
            systemTokens = foldingOverheadTokens,
            toolSchemaReserveTokens = toolSchemaReserveTokens,
        ).coerceAtLeast(1)

        // 引擎会把召回后缀追加到 user 消息上随请求发送，因此「已用」总量与对话细分都应计入
        // recall——否则 usedTokens 相对引擎实际发送量系统性偏低（召回只影响过触发线、没进总量）。
        val foldedBreakdown = effectiveUsage.breakdown.copy(
            summarizedTokens = effectiveUsage.breakdown.summarizedTokens + summaryTokens,
            conversationTokens = effectiveUsage.breakdown.conversationTokens + recallTokens,
        )
        ContextUsage(
            usedTokens = foldedBreakdown.totalTokens,
            limitTokens = budget,
            compactionThresholdTokens = compactionThresholdTokens,
            declaredTokens = effectiveContextWindow ?: budget,
            systemTokens = totalSystemTokens,
            toolTokens = effectiveUsage.toolTokens,
            conversationTokens = effectiveUsage.conversationTokens + recallTokens,
            compacted = summaryTokens > 0 || effectiveUsage.keepFromIndex > 0,
            cachedTokens = totalCachedTokens,
            cacheHitRatePercent = cacheHitPct,
            foldingRatioPercent = foldingRatioPercent,
            breakdown = foldedBreakdown,
        )

    }
        // 首屏卡顿修复（P1）：estimateEffectiveUsage 与两次 filterIsInstance 求和都是 O(消息数)，
        // stateIn 默认在 Main 执行；首订阅（空列表 → 全量）时会整段压在主线程，与首帧布局争抢。
        .flowOn(Dispatchers.Default)
        .combine(combine(currentSessionId, requestDiagnostics.snapshots, requestDiagnostics.archiveStatus) { id, snapshots, status -> snapshots[id].orEmpty() to status }) { usage, diagnostics ->
            usage.copy(requests = diagnostics.first, requestArchiveStatus = diagnostics.second)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContextUsage())

    /** 各 MCP 服务的实时连通性状态（与 McpManager 共享，聊天挂载面板 / 设置页联动）。 */
    val mcpConnectionStates: StateFlow<Map<String, McpConnectionState>> = mcpManager.connectionStates

    fun refreshMcpConnections() {
        viewModelScope.launch { mcpManager.refreshConnections() }
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean) {
        viewModelScope.launch {
            agentSkillRepository.setEnabled(skillId, enabled)
        }
    }

    fun setMcpServerEnabled(serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            mcpServerRepository.setEnabled(serverId, enabled)
            mcpManager.refreshConnections()
        }
    }

    /** 斜杠指令建议列表（当输入以 / 开头时实时过滤展示，自动合并已激活的专精技能与可用工作流）。 */
    val matchingCommands: StateFlow<List<SlashCommandItem>> = combine(
        _input,
        agentSkillRepository.activeSkills,
        workflowRepository.observeDefinitions(),
    ) { text, skills, workflows ->
        if (text.startsWith("/")) SlashCommands.filterCommands(context, text, skills, workflows)
        else emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun skillToMentionItem(skill: AgentSkill): MentionItem = MentionItem(
        id = skill.id,
        name = skill.name,
        description = skill.description,
        category = context.getString(R.string.chat_skill_category),
        type = MentionType.SKILL,
        icon = RuntimeIconName.Brain,
    )

    private fun mcpToMentionItem(
        mcp: McpServerConfig,
        description: String = mcp.description,
    ): MentionItem = MentionItem(
        id = mcp.id,
        name = mcp.name,
        description = description,
        category = context.getString(R.string.chat_mcp_category),
        type = MentionType.MCP_SERVER,
        icon = RuntimeIconName.Cpu,
    )

    /** @ 艾特唤醒建议列表（当输入包含 @ 时实时过滤技能与 MCP 插件）。 */
    val matchingMentions: StateFlow<List<MentionItem>> = combine(
        _input,
        agentSkillRepository.allSkills,
        mcpServerRepository.servers,
    ) { text, skills, mcps ->
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0) return@combine emptyList()
        val mentionToken = text.substring(atIndex + 1)
        if (mentionToken.any { it.isWhitespace() }) return@combine emptyList()
        val query = mentionToken.lowercase()

        val skillMentions = skills.filter { it.isEnabled }.map(::skillToMentionItem)
        val mcpMentions = mcps.filter { it.isEnabled && !it.isBuiltin }.map { mcp ->
            mcpToMentionItem(mcp, context.getString(R.string.chat_mcp_service_description, mcp.transportType))
        }
        val all = skillMentions + mcpMentions
        if (query.isEmpty()) all
        else all.filter { it.name.lowercase().contains(query) || it.description.lowercase().contains(query) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前会话中明确被用户手动钉选常驻的技能与 MCP ID 集合（默认为空，不默认常驻）。 */
    private val _pinnedMentionIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedMentionIds: StateFlow<Set<String>> = _pinnedMentionIds.asStateFlow()

    /** 当前会话中已钉选常驻的技能与 MCP 列表（默认为空，仅在用户显式钉选后常驻展示并生效）。 */
    val pinnedCapabilities: StateFlow<List<MentionItem>> = combine(
        _pinnedMentionIds,
        agentSkillRepository.allSkills,
        mcpServerRepository.servers,
    ) { pinnedIds, skills, mcps ->
        if (pinnedIds.isEmpty()) return@combine emptyList()
        val skillItems = skills
            .filter { it.isEnabled && (it.id in pinnedIds || it.name.lowercase() in pinnedIds) }
            .map(::skillToMentionItem)
        val mcpItems = mcps
            .filter { it.isEnabled && !it.isBuiltin && (it.id in pinnedIds || it.name.lowercase() in pinnedIds) }
            .map(::mcpToMentionItem)
        skillItems + mcpItems
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun togglePinMention(id: String) {
        val target = id.trim().lowercase()
        _pinnedMentionIds.update { current ->
            if (target in current || current.any { it.equals(id, ignoreCase = true) }) {
                current.filterNot { it.equals(id, ignoreCase = true) || it == target }.toSet()
            } else {
                current + target
            }
        }
    }

    fun unpinMention(id: String) {
        val target = id.trim().lowercase()
        _pinnedMentionIds.update { current ->
            current.filterNot { it.equals(id, ignoreCase = true) || it == target }.toSet()
        }
    }

    fun pinMention(id: String) {
        val target = id.trim().lowercase()
        _pinnedMentionIds.update { it + target }
    }

    /** 当前输入框中已挂载的技能与 MCP 标签列表（用于输入框顶部展示高亮双排 Chips）。 */
    val attachedMentions: StateFlow<List<MentionItem>> = combine(
        _input,
        agentSkillRepository.allSkills,
        mcpServerRepository.servers,
    ) { text, skills, mcps ->
        if (!text.contains("@")) return@combine emptyList()
        val regex = Regex("""@([^\s@,，:：\n]+)""")
        val matchedNames = regex.findAll(text).map { it.groupValues[1].trim().lowercase() }.toSet()
        if (matchedNames.isEmpty()) return@combine emptyList()

        val matchedSkills = skills
            .filter { skill ->
                skill.isEnabled && (
                    skill.name.lowercase() in matchedNames || skill.id.lowercase() in matchedNames
                    )
            }
            .map(::skillToMentionItem)

        val matchedMcps = mcps
            .filter { mcp ->
                mcp.isEnabled && !mcp.isBuiltin && (
                    mcp.name.lowercase() in matchedNames || mcp.id.lowercase() in matchedNames
                    )
            }
            .map(::mcpToMentionItem)

        matchedSkills + matchedMcps
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _initializing = MutableStateFlow(true)
    val initializing: StateFlow<Boolean> = _initializing.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // 恢复最近会话；没有则新建
            val latest = sessionDao.observeAll().first().firstOrNull()
            if (latest != null) {
                harnessLoop.loadSession(latest.id)
            } else {
                harnessLoop.newSession(context.getString(R.string.chat_new_session))
            }
            _initializing.value = false
        }
    }

    fun onInputChanged(value: String) {
        setInput(value)
    }

    fun applySlashCommand(command: SlashCommandItem) {
        if (command.command == "/clear") {
            createSession(context.getString(R.string.chat_new_session))
            setInput("")
            notifyNewSessionCreated()
        } else {
            setInput(command.template)
        }
    }

    fun applyQuickPhrase(phrase: QuickPhrase) {
        if (phrase.content.trim() == "/clear") {
            createSession(context.getString(R.string.chat_new_session))
            setInput("")
            notifyNewSessionCreated()
        } else {
            setInput(phrase.content)
        }
    }

    /** /clear 会静默重建会话，用 Toast 明确告知用户上下文已重置 */
    private fun notifyNewSessionCreated() {
        Toast.makeText(context, context.getString(R.string.chat_new_session_created), Toast.LENGTH_SHORT).show()
    }

    fun applyMention(item: MentionItem) {
        val text = _input.value
        val atIndex = text.lastIndexOf('@')
        val prefix = if (atIndex >= 0) text.substring(0, atIndex) else text
        // Persist the stable id so names containing spaces or punctuation cannot be
        // truncated by the mention parser; the attached chip still shows the friendly name.
        setInput("${prefix}@${item.id} ")
    }

    /** 从输入框中整块移除某个已挂载的 @能力 标签 */
    fun removeMention(item: MentionItem) {
        val current = _input.value
        // 正则替换 @name 及其后可能跟随的空格
        val updated = current.replace(Regex("""@${Regex.escape(item.name)}\s*"""), "")
            .replace(Regex("""@${Regex.escape(item.id)}\s*"""), "")
            .trimStart()
        setInput(updated)
    }

    fun triggerMentionInput() {
        val current = _input.value
        if (!current.endsWith("@")) {
            setInput(if (current.isBlank()) "@" else "$current @")
        }
    }

    private val _pendingAttachments = MutableStateFlow<List<ChatAttachment>>(emptyList())

    /** 待发送附件；处理（复制/压缩/编码）在 IO 线程完成 */
    val pendingAttachments: StateFlow<List<ChatAttachment>> = _pendingAttachments.asStateFlow()

    /** 附件处理中（复制/压缩/编码期间为 true，UI 据此展示加载指示） */
    private val _attachmentsProcessing = MutableStateFlow(false)
    val attachmentsProcessing: StateFlow<Boolean> = _attachmentsProcessing.asStateFlow()

    /** 需要以 Toast 提示用户的轻量通知（附件失败、模型档案已存在等），展示后调用 clearNotice() */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    fun onAttachmentsPicked(uris: List<Uri>, isImage: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _attachmentsProcessing.value = true
            val items = uris.mapNotNull { AttachmentHelper.processUri(context, it, isImage) }
            val failed = uris.size - items.size
            if (failed > 0) {
                _notice.value = context.getString(R.string.chat_attachment_process_failed, failed)
            }
            _pendingAttachments.update { it + items }
            _attachmentsProcessing.value = false
        }
    }

    fun removeAttachment(attachment: ChatAttachment) {
        _pendingAttachments.update { list -> list.filter { it.id != attachment.id } }
    }

    /** 组装附件挂载说明并委托 send() 发送；View 只需在输入框非空或有附件时触发 */
    fun sendFromComposer() {
        val trimmedInput = _input.value.trim()
        val attachments = _pendingAttachments.value
        if (trimmedInput.isBlank() && attachments.isEmpty()) return
        val imageUrls = attachments.mapNotNull { it.base64DataUrl }
        val nonImageFiles = attachments.filter { !it.isImage }
        val fullMessage = buildString {
            if (trimmedInput.isNotBlank()) {
                append(trimmedInput)
            } else if (nonImageFiles.isNotEmpty()) {
                append(context.getString(R.string.chat_attachment_files_prompt))
            } else if (imageUrls.isNotEmpty()) {
                append(context.getString(R.string.chat_attachment_images_prompt))
            }
            if (attachments.isNotEmpty()) {
                append(context.getString(R.string.chat_attachment_mount_header))
                attachments.forEachIndexed { i, att ->
                    val guestPath = att.guestFilePath ?: "/attachments/${att.name}"
                    val kind = context.getString(if (att.isImage) R.string.chat_attachment_image else R.string.chat_attachment_file)
                    append(context.getString(R.string.chat_attachment_line, i + 1, kind, att.name, AttachmentHelper.formatFileSize(att.sizeBytes), guestPath))
                }
                append(context.getString(R.string.chat_attachment_access_hint))
            }
        }
        _pendingAttachments.value = emptyList()
        send(fullMessage, imageUrls)
    }

    fun send(customText: String? = null, imageUrls: List<String> = emptyList()) {
        val rawText = (customText ?: _input.value).trim()
        if (rawText.isBlank() && imageUrls.isEmpty()) return
        WORKFLOW_COMMAND.matchEntire(rawText)?.let { match ->
            setInput("")
            val projectName = workspace.value.trim('/').removePrefix("workspace/").substringBefore('/').takeIf(String::isNotBlank).orEmpty()
            _workflowLaunchRequests.tryEmit(WorkflowLaunchRequest(match.groupValues[1].takeIf(String::isNotBlank), projectName))
            return
        }
        setInput("")

        val pinnedIds = _pinnedMentionIds.value
        val effectiveText = if (pinnedIds.isNotEmpty()) {
            val existingMentions = MentionExtractor.parse(rawText)
            val missingPins = pinnedIds.filter { pin ->
                pin.lowercase() !in existingMentions
            }
            if (missingPins.isNotEmpty()) {
                rawText + missingPins.joinToString(prefix = " ", separator = " ") { "@$it" }
            } else {
                rawText
            }
        } else {
            rawText
        }

        if (!running.value) {
            harnessLoop.send(effectiveText, imageUrls = imageUrls)
        } else {
            // 运行中投递语义跟随「运行中发送方式」偏好；send() 非挂起函数，故读 stateIn 快照。
            // STEER 在当前这批工具调用完成后、下一轮推理开始前注入，不打断正在执行的工具调用。
            when (runningSendModePref.value) {
                RunningSendMode.STEER -> harnessLoop.steer(effectiveText, imageUrls = imageUrls)
                RunningSendMode.QUEUE -> harnessLoop.send(effectiveText, imageUrls = imageUrls)
            }
        }
    }

    private companion object {
        val WORKFLOW_COMMAND = Regex("^/wf(?:\\s+([A-Za-z0-9_.-]+))?$")
    }

    /** 创建针对工具安装或沙箱异常的专属自愈会话并立即启动诊断 */
    fun startHealingTask(title: String, prompt: String) {
        viewModelScope.launch {
            harnessLoop.newSession(title = title)
            setInput("")
            harnessLoop.send(prompt)
        }
    }

    /** 重新生成最后一次回复 */
    fun regenerateLast() {
        if (running.value) return
        harnessLoop.regenerateLast()
    }

    fun retryToolCall(toolCallId: String) {
        if (running.value) return
        harnessLoop.retryToolCall(toolCallId)
    }

    fun createBranch(messageId: String, displayName: String) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank() || running.value) return
        viewModelScope.launch {
            runCatching {
                laneManager.createConversationBranch(sessionId, displayName, messageId)
                harnessLoop.activateBranch(messageId, sessionId)
            }
            _branchRefresh.value++
        }
    }

    fun switchBranch(branch: ConversationBranch) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank() || running.value) return
        viewModelScope.launch {
            harnessLoop.activateBranch(branch.leafId, sessionId)
            _branchRefresh.value++
        }
    }

    /** 编辑并重新发送某条用户消息 */
    fun editAndResend(userMessageId: String, newText: String) {
        if (running.value || newText.isBlank()) return
        harnessLoop.truncateAndResend(userMessageId, newText)
    }

    /** 删除单条消息 */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            harnessLoop.deleteMessage(messageId)
        }
    }

    fun stop() = harnessLoop.cancel()

    fun removePendingMessage(index: Int) = harnessLoop.removePendingMessage(index)

    fun removeQueuedPrompt(prompt: QueuedPrompt) {
        val sameQueue = queuedPrompts.value.filter { it.queue == prompt.queue }
        val index = sameQueue.indexOfFirst { it.id == prompt.id }
        if (index >= 0) harnessLoop.removeQueuedPrompt(prompt.queue, index)
    }

    /** 将排队消息转为即时修正（Steer）指令并插入下一轮 */
    fun convertQueuedPromptToSteer(prompt: QueuedPrompt) {
        removeQueuedPrompt(prompt)
        harnessLoop.steer(prompt.message.text, imageUrls = prompt.message.imageUrls)
    }

    /** 编辑排队消息：先移出队列（不发送），把原文回显到输入框，修改后由用户手动发送。 */
    fun editQueuedPrompt(prompt: QueuedPrompt) {
        removeQueuedPrompt(prompt)
        setInput(prompt.message.text)
    }

    fun clearPendingMessages() = harnessLoop.clearPendingMessages()

    fun clearError() { sessionDeletionFeedback.clear(); harnessLoop.clearError() }

    /** 新建会话（支持自定义标题并关联工作区）。 */
    fun createSession(title: String = "", workspace: String = "", projectType: String = "") {
        viewModelScope.launch {
            harnessLoop.newSession(title.trim().ifBlank { context.getString(R.string.chat_new_session) }, workspace, projectType)
        }
    }

    fun switchSession(id: String) {
        viewModelScope.launch { harnessLoop.loadSession(id) }
    }

    fun deleteSession(id: String) {
        sessionDeletionFeedback.delete(viewModelScope, harnessLoop, id, context.getString(R.string.chat_request_archive_delete_error))
    }

    fun renameSession(id: String, title: String) {
        viewModelScope.launch { harnessLoop.renameSession(id, title) }
    }

    // ---- 撤回到此轮（Checkpoint Rewind） ----

    /** 撤回到 [messageId] 所在用户轮：按 checkpoint 锚点定位轮次，prepare/commit 两段式执行；CONVERSATION/BOTH 会派生回退分支并切换过去。 */
    fun rewindToMessage(messageId: String, scope: RewindScope) {
        viewModelScope.launch(Dispatchers.IO) {
            val sessionId = harnessLoop.currentSessionId.value
            val target = harnessLoop.sessionCheckpoints(sessionId)
                .firstOrNull { it.anchorMessageId == messageId }
            if (target == null) {
                _notice.value = context.getString(R.string.chat_rewind_no_checkpoint)
                return@launch
            }
            runCatching {
                val plan = harnessLoop.prepareRewind(sessionId, target.turn, scope)
                val result = harnessLoop.commitRewind(plan, workspace.value)
                val forkedSessionId = result.forkedSessionId
                if (forkedSessionId != null) {
                    harnessLoop.loadSession(forkedSessionId)
                }
                // 成功的 rewind 走带「撤销回滚」动作的 Snackbar（Toast 无法承载动作）；
                // 失败/无锚点仍走 _notice Toast。
                _rewindCompletedEvents.tryEmit(
                    buildString {
                        append(context.getString(R.string.chat_rewind_done, result.filesRestored, result.filesDeleted))
                        if (forkedSessionId != null) append(" · ").append(context.getString(R.string.chat_rewind_switched))
                        result.note?.let { append("\n").append(it) }
                    },
                )
            }.onFailure { throwable ->
                _notice.value = context.getString(R.string.chat_rewind_failed, throwable.message ?: "unknown")
            }
        }
    }

    private val _rewindCompletedEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** rewind 成功完成的事件（消息正文）；UI 以 Snackbar 呈现并附「撤销回滚」动作。 */
    val rewindCompletedEvents: SharedFlow<String> = _rewindCompletedEvents

    /** 撤销最近一次 rewind：文件还原到 rewind 前状态（对话侧切回原会话即可）。 */
    fun undoLastRewind() {
        viewModelScope.launch(Dispatchers.IO) {
            val sessionId = harnessLoop.currentSessionId.value
            runCatching { harnessLoop.undoRewind(sessionId, workspace.value) }.getOrNull()
                ?.let { result ->
                    _notice.value = buildString {
                        append(context.getString(R.string.chat_rewind_undone, result.filesRestored, result.filesDeleted))
                        result.note?.let { append("\n").append(it) }
                    }
                }
                ?: run { _notice.value = context.getString(R.string.chat_rewind_no_undo) }
        }
    }

    // ---- 模型管理 ----

    private val _providerModelIds = MutableStateFlow<List<String>>(emptyList())
    val providerModelIds: StateFlow<List<String>> = _providerModelIds.asStateFlow()

    private val _discoveringProviderModels = MutableStateFlow(false)
    val discoveringProviderModels: StateFlow<Boolean> = _discoveringProviderModels.asStateFlow()

    private val _providerModelDiscoveryError = MutableStateFlow<String?>(null)
    val providerModelDiscoveryError: StateFlow<String?> = _providerModelDiscoveryError.asStateFlow()

    private val _modelPickerProfileId = MutableStateFlow<String?>(null)
    val modelPickerProfileId: StateFlow<String?> = _modelPickerProfileId.asStateFlow()

    fun addModel(name: String, provider: String, model: String, baseUrl: String) {
        val trimmedName = name.trim().ifBlank { model }
        val trimmedModel = model.trim()
        if (trimmedModel.isBlank()) return
        viewModelScope.launch {
            val id = "${provider.trim().lowercase()}-${trimmedModel.lowercase()}"
                .replace(Regex("[^a-z0-9-]"), "-")
            // 派生 id 与既有档案冲突时提示而非静默覆盖
            if (aiModelDao.findById(id) != null) {
                _notice.value = context.getString(R.string.chat_model_profile_exists)
                return@launch
            }
            profileWriter.upsertProfile(
                AiProfileWriter.UpsertRequest(
                    id = id,
                    name = trimmedName,
                    provider = provider.trim().ifBlank { trimmedModel },
                    model = trimmedModel,
                    baseUrl = baseUrl.trim(),
                ),
            )
        }
    }

    fun setActiveModel(id: String) {
        selectModel(id)
    }

    private val _subagentResult = MutableStateFlow<SubagentResultUiState?>(null)
    val subagentResult: StateFlow<SubagentResultUiState?> = _subagentResult.asStateFlow()

    fun openSubagentResult(branch: ConversationBranch) {
        if (branch.kind != ConversationBranchKind.SUBAGENT || branch.laneName.isNullOrBlank()) return
        val sessionId = currentSessionId.value.takeIf { it.isNotBlank() } ?: return
        _subagentResult.value = SubagentResultUiState(sessionId = sessionId, branch = branch)
        loadSubagentResult(sessionId, branch)
    }

    fun refreshSubagentResult() {
        val current = _subagentResult.value ?: return
        _subagentResult.value = current.copy(loading = true, error = null)
        loadSubagentResult(current.sessionId, current.branch)
    }

    fun closeSubagentResult() {
        _subagentResult.value = null
    }

    private fun loadSubagentResult(sessionId: String, branch: ConversationBranch) {
        val laneName = branch.laneName ?: return
        viewModelScope.launch {
            runCatching {
                val latestBranch = laneManager.branches(sessionId)
                    .firstOrNull { it.kind == ConversationBranchKind.SUBAGENT && it.laneName == laneName }
                    ?: branch
                latestBranch to laneManager.subagentTranscript(sessionId, laneName)
            }.onSuccess { (latestBranch, transcript) ->
                val current = _subagentResult.value
                if (current?.sessionId == sessionId && current.branch.laneName == laneName) {
                    _subagentResult.value = current.copy(
                        branch = latestBranch,
                        messages = transcript,
                        loading = false,
                        error = null,
                    )
                }
            }.onFailure { throwable ->
                val current = _subagentResult.value
                if (current?.sessionId == sessionId && current.branch.laneName == laneName) {
                    _subagentResult.value = current.copy(
                        loading = false,
                        error = throwable.message ?: "无法读取子智能体成果",
                    )
                }
            }
        }
    }

    fun selectModel(id: String, subModel: String? = null) {
        viewModelScope.launch {
            val sessionId = currentSessionId.value.takeIf { it.isNotBlank() } ?: return@launch
            sessionModelSwitcher.switchModel(
                sessionId = sessionId,
                profileId = id,
                variant = subModel,
                compactIfNeeded = !running.value,
            )
        }
    }

    /** 打开某供应商档案后，通过 v1/models 拉取同端点可用模型列表。 */
    fun openProviderModelPicker(profileId: String) {
        _modelPickerProfileId.value = profileId
        discoverProviderModels(profileId)
    }

    fun closeProviderModelPicker() {
        _modelPickerProfileId.value = null
        _providerModelIds.value = emptyList()
        _providerModelDiscoveryError.value = null
        _discoveringProviderModels.value = false
    }

    fun discoverProviderModels(profileId: String) {
        viewModelScope.launch {
            val profile = aiModelDao.findById(profileId) ?: return@launch
            _discoveringProviderModels.value = true
            _providerModelDiscoveryError.value = null
            _providerModelIds.value = emptyList()
            val provider = providerCatalog.find(profile.provider)
            val baseUrl = profile.baseUrl.ifBlank { provider.baseUrl }
            val cleanUrl = ProviderEndpointPolicy.normalizeUrl(baseUrl)
            if (!ProviderEndpointPolicy.isSafeBaseUrl(cleanUrl)) {
                _providerModelDiscoveryError.value = context.getString(R.string.chat_model_discovery_bad_url)
                _discoveringProviderModels.value = false
                return@launch
            }
            val apiKey = profile.secretRef.takeIf { it.isNotBlank() }
                ?.let { providerRepository.readModelApiKeys(it).firstOrNull() }
                ?: providerRepository.readApiKey()
            runCatching { modelDiscovery.discover(provider, cleanUrl, apiKey) }
                .onSuccess { ids ->
                    _providerModelIds.value = ids
                    if (ids.isEmpty()) {
                        _providerModelDiscoveryError.value = context.getString(R.string.chat_model_discovery_empty)
                    }
                }
                .onFailure {
                    Log.w(TAG, "模型发现失败 profile=$profileId", it)
                    _providerModelDiscoveryError.value = context.getString(R.string.chat_model_discovery_failed)
                }
            _discoveringProviderModels.value = false
        }
    }

    /** 在同一供应商档案内为当前会话选择具体模型 ID（复用 baseUrl / Key / 推理参数）；档案本身保持不变，避免其他会话被连带切换。 */
    fun switchModelInProfile(profileId: String, modelId: String) {
        val trimmed = modelId.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val sessionId = currentSessionId.value.takeIf { it.isNotBlank() } ?: return@launch
            sessionModelSwitcher.switchModel(
                sessionId = sessionId,
                profileId = profileId,
                variant = trimmed,
                compactIfNeeded = !running.value,
            )
            closeProviderModelPicker()
        }
    }

    fun updateActiveModelReasoning(mode: String?, effort: String?) {
        viewModelScope.launch {
            val sessionModelId = currentSessionId.value.takeIf { it.isNotBlank() }
                ?.let { sessionDao.findById(it)?.modelId }
            val profile = sessionModelId?.let { aiModelDao.findById(it) } ?: aiModelDao.activeModel() ?: return@launch
            aiModelDao.updateReasoning(profile.id, mode, effort)
        }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            profileWriter.deleteProfile(id)
        }
    }
}

internal fun mergeHistoricalAndLiveEvents(
    sessionId: String,
    messages: List<HarnessMessage>,
    live: List<HarnessEvent>,
): List<HarnessEvent> {
    if (sessionId.isBlank()) return emptyList()
    if (live.isEmpty()) return synthesizeHistoricalEvents(sessionId, messages)
    if (messages.isEmpty()) return live

    val liveEntryIds = mutableSetOf<String>()
    live.forEach { event ->
        when (event) {
            is HarnessEvent.ProviderRoundSettled -> event.entryId?.let { liveEntryIds.add(it) }
            is HarnessEvent.ToolCallStarted -> liveEntryIds.add(event.toolCallId)
            is HarnessEvent.ToolCallSettled -> liveEntryIds.add(event.toolCallId)
            else -> Unit
        }
    }

    val liveMinTimestamp = live.minOfOrNull { it.timestamp } ?: Long.MAX_VALUE
    val priorMessages = messages.filter { msg ->
        msg.createdAt < liveMinTimestamp && !liveEntryIds.contains(msg.id)
    }

    if (priorMessages.isEmpty()) return live

    val priorEvents = synthesizeHistoricalEvents(sessionId, priorMessages)
    return priorEvents + live
}

private fun synthesizeHistoricalEvents(sessionId: String, messages: List<HarnessMessage>): List<HarnessEvent> {
    if (sessionId.isBlank() || messages.isEmpty()) return emptyList()
    val events = mutableListOf<HarnessEvent>()
    var currentRound = 0
    var opId = "hist-$sessionId"
    val toolCallMap = messages.filterIsInstance<ToolCall>().associateBy { it.id }

    messages.forEach { msg ->
        when (msg) {
            is UserMessage -> {
                currentRound++
                opId = "hist-${msg.id}"
                events.add(
                    HarnessEvent.OperationStarted(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        laneName = "main",
                    )
                )
            }
            is AssistantText -> {
                events.add(
                    HarnessEvent.ProviderRoundStarted(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        round = currentRound,
                        attempt = 1,
                        modelId = null,
                    )
                )
                events.add(
                    HarnessEvent.ProviderRoundSettled(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        round = currentRound,
                        entryId = msg.id,
                        inputTokens = 0L,
                        outputTokens = msg.text.length.toLong(),
                    )
                )
            }
            is ToolCall -> {
                events.add(
                    HarnessEvent.ToolCallStarted(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        toolCallId = msg.id,
                        toolName = msg.tool.name.lowercase(),
                    )
                )
            }
            is ToolResult -> {
                val call = toolCallMap[msg.toolCallId]
                val toolName = call?.tool?.name?.lowercase() ?: "tool"
                events.add(
                    HarnessEvent.ToolCallSettled(
                        sessionId = sessionId,
                        timestamp = msg.createdAt,
                        operationId = opId,
                        toolCallId = msg.toolCallId,
                        toolName = toolName,
                        success = msg.success,
                        durationMs = msg.durationMs,
                    )
                )
            }
            else -> Unit
        }
    }
    return events
}

/** 聊天发送键语义：仅驱动发送键配色。实际投递行为由「运行中发送方式」偏好（RunningSendMode）决定。 */
enum class ComposerSendMode { STEER, NEXT_RUN }

private data class ContextUsageInputs(
    val currentMessages: List<HarnessMessage>,
    val activeModel: AiModelEntity?,
    val defaultBudget: Int,
)

private data class ContextUsageCalculation(
    val inputs: ContextUsageInputs,
    val sessionId: String,
    val compactionEnabled: Boolean,
    val foldingRatioPercent: Int,
)

data class MentionItem(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val type: MentionType,
    val icon: RuntimeIconName,
)

enum class MentionType {
    SKILL, MCP_SERVER
}

/**
 * 分支列表重投影的触发键。
 *
 * 用于把 combine 的多个上游流压缩成一个「可比较信号」，任一上游变化都会产生不同的 key，
 * 从而触发重新投影；配合 distinctUntilChanged 避免同一 key 重复拉取。
 * 取代此前 `{ sessionId, _, _, _ -> sessionId }` 丢弃上游的做法。
 */
private data class ProjectionKey(
    val sessionId: String,
    val messageRevision: Pair<Int, String?>,
    val refresh: Int,
    val eventRevision: Pair<Int, Int>,
)
