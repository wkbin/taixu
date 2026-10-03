package top.wkbin.taixu.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import top.wkbin.taixu.feature.chat.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.ModelSwitchEvent
import top.wkbin.taixu.harness.SkillSuggestion
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.checkpoint.RewindScope
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.runtime.WorkspaceProject
import top.wkbin.taixu.ui.components.IconTile
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.wkbin.taixu.ui.components.ScrollFadeOverlay
import androidx.compose.foundation.lazy.LazyListState
import top.wkbin.taixu.core.model.QuickPhrase
import top.wkbin.taixu.core.database.AgentApprovalRequestEntity
import top.wkbin.taixu.core.database.AgentPlanEntity
import top.wkbin.taixu.harness.compaction.CompactionSnapshot
import top.wkbin.taixu.runtime.ProjectType

/**
 * 工具卡片折叠状态 Saver：Map 本身不能存入 Bundle（rememberSaveable 会抛 IllegalArgumentException），
 * 把 entries 序列化为 ArrayList<Pair<String, Boolean>>（两者均可安全保存）。
 */
private val ExpandedOverridesSaver = Saver<Map<String, Boolean>, ArrayList<Pair<String, Boolean>>>(
    save = { state -> ArrayList(state.map { (key, value) -> key to value }) },
    restore = { saved -> saved.toMap() },
)

/**
 * 消息流 LazyColumn：骨架屏、空态引导、压缩横幅、任务看板、消息渲染项与审批卡。
 */
@Composable
internal fun ChatMessageList(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    messages: List<HarnessMessage>,
    toolResults: Map<String, ToolResult>,
    initializing: Boolean,
    running: Boolean,
    imageGenerationModel: Boolean = false,
    status: String?,
    workspace: String,
    workspaceProject: WorkspaceProject?,
    onboardingPrivilege: OnboardingPrivilege?,
    thinkingExpanded: Boolean,
    thinkingAutoTranslate: Boolean = false,
    chatRoundCollapse: Boolean = false,
    thinkingLive: Boolean,
    liveThinkingMessageId: String?,
    lastAssistantMessageId: String?,
    onNavigateToSettings: (() -> Unit)? = null,
    knownMentionNames: List<String>,
    quickPhrases: List<QuickPhrase>,
    onSelectPhrase: (QuickPhrase) -> Unit,
    onSelectCommand: (SlashCommandItem) -> Unit,
    onEditMessage: (UserMessage) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onCreateBranch: (String) -> Unit,
    onRewindMessage: (String, RewindScope) -> Unit = { _, _ -> },
    onRegenerate: () -> Unit,
    onRetryTool: (String) -> Unit,
    onOpenFile: ((String, String) -> Unit)?,
    activeCompaction: CompactionSnapshot?,
    activePlan: AgentPlanEntity?,
    pendingApprovals: List<AgentApprovalRequestEntity>,
    onResolveApproval: (String, Boolean, Boolean) -> Unit,
    onResolveQuestion: (String, String) -> Unit = { _, _ -> },
    onViewSubagentLanes: () -> Unit = {},
    subagentBranches: List<top.wkbin.taixu.harness.session.ConversationBranch> = emptyList(),
    onOpenSubagent: (top.wkbin.taixu.harness.session.ConversationBranch) -> Unit = {},
    hiddenSkillSuggestions: Set<String> = emptySet(),
    onApplySkillSuggestion: (SkillSuggestion, Boolean) -> Unit = { _, _ -> },
    onDismissSkillSuggestion: (String) -> Unit = {},
) {
    // 折叠状态用自定义 Saver：Map 不能直接存入 Bundle（会抛 IllegalArgumentException）
    var expandedOverrides by rememberSaveable(stateSaver = ExpandedOverridesSaver) { mutableStateOf(mapOf<String, Boolean>()) }

    val reveal = rememberRoundRevealState()
    val renderItems = rememberChatRenderItems(messages, toolResults, expandedOverrides, chatRoundCollapse, reveal.limits)
    val waitingForFirstOutput = remember(
        // 只依赖「末条消息的 id + 其内容签名」：流式期间末条内容变化会改变签名（真正需要重算），
        // 而历史消息变化不影响该判定，因此无需依赖整个 messages 列表引用（避免每帧 O(n) 重算）。
        messages.lastOrNull()?.id,
        messages.lastOrNull()?.let { last ->
            when (last) {
                is AssistantText -> "${last.text.length}:${last.reasoning?.length ?: 0}"
                is ToolCall -> "tool:${last.id}"
                else -> "other:${last.id}"
            }
        },
        running,
        imageGenerationModel,
    ) {
        if (!running) false else {
            val lastUserIndex = messages.indexOfLast { it is UserMessage }
            imageGenerationModel && lastUserIndex >= 0 && messages.drop(lastUserIndex + 1).none { message ->
                when (message) {
                    is AssistantText -> message.text.isNotBlank() || !message.reasoning.isNullOrBlank()
                    is ToolCall -> true
                    else -> false
                }
            }
        }
    }
    // 性能：原实现给 LazyColumn 挂 scrollFadingEdge（内部依赖 CompositingStrategy.Offscreen），
    // 使整个列表**滚动期间每帧都要全量离屏合成**——屏幕越大代价越高，
    // 表现为「静止不卡、快速滑动明显掉帧」。现改为在列表之上叠加轻量渐变（零离屏合成）。
    Box(modifier = modifier) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp),
    ) {
        if (initializing) {
            item {
                ChatMessageSkeleton(
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        } else {
            if (messages.isEmpty()) {
                item {
                    EmptyChatGuidance(
                        workspaceProject = workspaceProject,
                        onboardingPrivilege = onboardingPrivilege,
                        quickPhrases = quickPhrases,
                        onSelectPhrase = onSelectPhrase,
                        onSelectCommand = onSelectCommand,
                    )
                }
            }
            // 上下文压缩透明度横幅：真实折叠条数 + 摘要原地展开预览。
            activeCompaction?.let { snapshot ->
                item(key = "session_compaction_banner") {
                    CompactionBanner(
                        snapshot = snapshot,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            itemsIndexed(renderItems, key = { _, item -> item.stableKey }) { index, item ->
                val top = when {
                    item is ChatRenderItem.MessageItem && item.message is UserMessage -> 12.dp
                    // 思考过程、工具调用卡片、能力事件之间的垂直外边距压至紧凑的 2.5dp
                    isThinkingOrActionItem(item) && isThinkingOrActionItem(renderItems.getOrNull(index - 1)) -> 2.5.dp
                    // 思考/工具刚结束紧接的最终回复气泡间距压缩为 4dp
                    item is ChatRenderItem.MessageItem && item.message is AssistantText && isThinkingOrActionItem(renderItems.getOrNull(index - 1)) -> 4.dp
                    else -> 8.dp
                }
                if (index > 0) Spacer(Modifier.height(top))
                when (item) {
                    is ChatRenderItem.CollapseButtonItem -> {
                        RoundCollapseButton(
                            item = item,
                            onToggle = {
                                val wasExpanded = item.isExpanded
                                expandedOverrides = expandedOverrides + (item.roundKey to !wasExpanded)
                                reveal.onToggled(item, wasExpanded, chatRoundCollapse)
                            },
                        )
                    }
                    is ChatRenderItem.MessageItem -> {
                        when (val message = item.message) {
                            is CapabilityEvent -> CapabilityEventCard(message)
                            is SkillSuggestion -> if (message.id !in hiddenSkillSuggestions) {
                                SkillSuggestionCard(
                                    suggestion = message,
                                    onCreate = { onApplySkillSuggestion(message, true) },
                                    onUpdate = { onApplySkillSuggestion(message, false) },
                                    onDismiss = { onDismissSkillSuggestion(message.id) },
                                )
                            }
                            is ModelSwitchEvent -> ModelSwitchCard(message)
                            is UserMessage -> UserBubble(
                                message = message,
                                knownMentionNames = knownMentionNames,
                                onEdit = { onEditMessage(message) },
                                onDelete = { onDeleteMessage(message.id) },
                                onCreateBranch = { onCreateBranch(message.id) },
                                onRewind = { scope -> onRewindMessage(message.id, scope) },
                            )
                            is AssistantText -> AssistantBubble(
                                message = message,
                                defaultExpanded = thinkingExpanded,
                                live = thinkingLive && message.id == liveThinkingMessageId,
                                showRegenerate = message.id == lastAssistantMessageId,
                                onRegenerate = onRegenerate,
                                onCreateBranch = { onCreateBranch(message.id) },
                                autoTranslate = thinkingAutoTranslate,
                                onNavigateToSettings = onNavigateToSettings,
                            )
                            is ToolCall -> {
                                // 原始下标由投影阶段预计算（见 projectChatMessages），组合期 O(1)，
                                // 不再在每个 Lazy 项里对 messages 做 indexOfFirst 的 O(n) 全表扫描。
                                val rawIndex = item.rawIndex
                                if (message.tool == HarnessTool.SUBAGENT) {
                                    SubagentCard(
                                        call = message,
                                        result = toolResults[message.id],
                                        subagentBranches = subagentBranches,
                                        onOpenSubagent = onOpenSubagent,
                                        onViewDetails = onViewSubagentLanes,
                                    )
                                } else {
                                    ToolCard(
                                        call = message,
                                        result = toolResults[message.id],
                                        workspace = workspace,
                                        onOpenFile = onOpenFile,
                                        running = running,
                                        liveStatus = status,
                                        showReasoning = message.reasoning != null &&
                                            !reasoningAlreadyShown(messages, rawIndex, message.reasoning),
                                        defaultExpanded = thinkingExpanded,
                                        onRetry = { onRetryTool(message.id) },
                                    )
                                }
                            }
                            is ToolResult -> Unit
                        }
                    }
                }
            }
            if (waitingForFirstOutput) {
                item(key = "assistant_generation_placeholder") {
                    Spacer(Modifier.height(8.dp))
                    AssistantGenerationPlaceholder()
                }
            }
        }
        item {
            pendingApprovals.firstOrNull()?.let { request ->
                if (request.toolName == top.wkbin.taixu.harness.AskUserQuestions.TOOL_NAME) {
                    AskUserCard(
                        request = request,
                        onAnswer = onResolveQuestion,
                        onSkip = { onResolveApproval(request.id, false, false) },
                    )
                } else {
                    ApprovalRequestCard(
                        request = request,
                        onApprove = { rememberForSession -> onResolveApproval(request.id, true, rememberForSession) },
                        onReject = { onResolveApproval(request.id, false, false) },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
        // 轻量渐隐：叠加在列表之上，不使用离屏合成（scrollFadingEdge 的性能替代）。
        ScrollFadeOverlay(top = 4.dp, bottom = 20.dp)
    }
}

/** Visible while a non-streaming model (notably image generators) is still preparing its result. */
@Composable
private fun AssistantGenerationPlaceholder() {
    RuntimeCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(
                icon = RuntimeIconName.Image,
                size = 44.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.chat_generating_content),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.chat_generating_content_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
        }
    }
}

@Composable
internal fun EmptyChatGuidance(
    workspaceProject: WorkspaceProject? = null,
    onboardingPrivilege: OnboardingPrivilege? = null,
    quickPhrases: List<QuickPhrase> = emptyList(),
    onSelectPhrase: (QuickPhrase) -> Unit = {},
    onSelectCommand: (SlashCommandItem) -> Unit,
) {
    val context = LocalContext.current
    val enabledPhrases = remember(quickPhrases, workspaceProject) {
        val active = quickPhrases.filter { it.isEnabled }
        if (active.isEmpty()) emptyList()
        else {
            val projectType = workspaceProject?.projectType
            val matched = when (projectType) {
                ProjectType.ANDROID -> active.filter { it.targetProjectType == "ANDROID" || it.targetProjectType == null }
                ProjectType.FLUTTER -> active.filter { it.targetProjectType == "FLUTTER" || it.targetProjectType == null }
                ProjectType.REVERSE -> active.filter { it.targetProjectType == "REVERSE" || it.targetProjectType == null }
                else -> active.filter { it.targetProjectType == null }
            }
            if (matched.isNotEmpty()) matched else active.take(4)
        }
    }

    val quickCommands = remember(workspaceProject) {
        when (workspaceProject?.projectType) {
            ProjectType.ANDROID -> listOf(
                SlashCommandItem("/android-check", context.getString(R.string.chat_android_check), context.getString(R.string.chat_android_check_description), context.getString(R.string.chat_android_check_prompt), RuntimeIconName.Check),
                SlashCommandItem("/android-build-install", context.getString(R.string.chat_android_build), context.getString(R.string.chat_android_build_description), context.getString(R.string.chat_android_build_prompt), RuntimeIconName.Play),
                SlashCommandItem("/android-debug", context.getString(R.string.chat_android_debug), context.getString(R.string.chat_android_debug_description), context.getString(R.string.chat_android_debug_prompt), RuntimeIconName.Alert),
            )
            ProjectType.FLUTTER -> listOf(
                SlashCommandItem("/flutter-check", context.getString(R.string.chat_flutter_check), context.getString(R.string.chat_flutter_check_description), context.getString(R.string.chat_flutter_check_prompt), RuntimeIconName.Check),
                SlashCommandItem("/flutter-build-install", context.getString(R.string.chat_flutter_build), context.getString(R.string.chat_flutter_build_description), context.getString(R.string.chat_flutter_build_prompt), RuntimeIconName.Play),
                SlashCommandItem("/flutter-debug", context.getString(R.string.chat_flutter_debug), context.getString(R.string.chat_flutter_debug_description), context.getString(R.string.chat_flutter_debug_prompt), RuntimeIconName.Alert),
            )
            ProjectType.REVERSE -> listOf(
                SlashCommandItem("/reverse-analyze", context.getString(R.string.chat_reverse_analyze), context.getString(R.string.chat_reverse_analyze_description), context.getString(R.string.chat_reverse_analyze_prompt), RuntimeIconName.Search),
                SlashCommandItem("/reverse-decode", context.getString(R.string.chat_reverse_decode), context.getString(R.string.chat_reverse_decode_description), context.getString(R.string.chat_reverse_decode_prompt), RuntimeIconName.Code),
            )
            else -> SlashCommands.presetCommands(context).take(4)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        onboardingPrivilege?.let { privilege ->
            val ready = privilege == OnboardingPrivilege.SHIZUKU_READY || privilege == OnboardingPrivilege.ROOT_READY
            Surface(
                color = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        when (privilege) {
                            OnboardingPrivilege.SANDBOX -> R.string.chat_onboarding_sandbox
                            OnboardingPrivilege.SANDBOX_UNLOCKABLE -> R.string.chat_onboarding_unlockable
                            OnboardingPrivilege.SHIZUKU_READY -> R.string.chat_onboarding_shizuku
                            OnboardingPrivilege.ROOT_READY -> R.string.chat_onboarding_root
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ready) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        Text(
            stringResource(R.string.chat_agent_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.chat_quick_start),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (enabledPhrases.isNotEmpty()) {
                enabledPhrases.forEach { phrase ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPhrase(phrase) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RuntimeIcon(parseIconName(phrase.iconName), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    phrase.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    phrase.description.ifBlank { phrase.content },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            } else {
                quickCommands.forEach { cmd ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectCommand(cmd) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RuntimeIcon(cmd.icon, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    cmd.command,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(cmd.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseIconName(name: String): RuntimeIconName {
    return when (name.lowercase()) {
        "play" -> RuntimeIconName.Play
        "check" -> RuntimeIconName.Check
        "alert" -> RuntimeIconName.Alert
        "code" -> RuntimeIconName.Code
        "plus" -> RuntimeIconName.Plus
        "package" -> RuntimeIconName.Package
        "search" -> RuntimeIconName.Search
        "brain" -> RuntimeIconName.Brain
        "bot" -> RuntimeIconName.Bot
        "chat" -> RuntimeIconName.Chat
        "refresh" -> RuntimeIconName.Refresh
        "terminal" -> RuntimeIconName.Terminal
        "tool", "wrench" -> RuntimeIconName.Wrench
        else -> RuntimeIconName.Play
    }
}

@Composable
internal fun RoundCollapseButton(
    item: ChatRenderItem.CollapseButtonItem,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = if (item.isExpanded) {
        stringResource(R.string.chat_collapse)
    } else {
        val durationSuffix = if (item.hiddenDurationMs > 0) {
            " · " + formatChatDuration(item.hiddenDurationMs)
        } else {
            ""
        }
        stringResource(R.string.chat_expand_more, item.hiddenSteps, item.totalSteps, durationSuffix)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }
}

@Composable
internal fun ChatMessageSkeleton(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        // 用户消息占位（右侧）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                    .background(brush),
            )
        }
        Spacer(Modifier.height(16.dp))
        // 助手消息占位（左侧）
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(brush),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush),
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(brush),
            )
        }
        Spacer(Modifier.height(16.dp))
        // 用户消息占位 2
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                    .background(brush),
            )
        }
    }
}

@Composable
private fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "chatShimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "chatShimmerTranslate",
    )
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.25f),
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim, 0f),
    )
}

