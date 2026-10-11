package top.wkbin.taixu.ui.settings

import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import top.wkbin.taixu.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.HorizontalDivider
import top.wkbin.taixu.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import top.wkbin.taixu.ui.components.RuntimeSlider as Slider
import androidx.compose.material3.Surface
import top.wkbin.taixu.ui.components.RuntimeSwitch as Switch
import androidx.compose.material3.SwitchDefaults
import top.wkbin.taixu.ui.settings.LocalizedText as Text
import top.wkbin.taixu.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.core.model.AgentPlugin
import top.wkbin.taixu.harness.ContextWindowPolicy
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.core.model.AgentSubagent
import top.wkbin.taixu.core.model.AgentDepartments
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.components.SectionHeader
import kotlinx.coroutines.delay
import top.wkbin.taixu.core.model.skill.ClawHubMarketItem
import top.wkbin.taixu.ui.settings.skill.SkillSecurityAuditDialog

enum class AgentSettingsCategory { EXECUTION, SUBAGENTS, SKILLS }

@Composable
fun AgentSettingsScreen(
    onBack: () -> Unit,
    category: AgentSettingsCategory = AgentSettingsCategory.EXECUTION,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val thinkingExpanded by viewModel.thinkingExpanded.collectAsStateWithLifecycle()
    val translationModelStatus by viewModel.translationModelStatus.collectAsStateWithLifecycle()
    val thinkingAutoTranslate by viewModel.thinkingAutoTranslate.collectAsStateWithLifecycle()
    val chatRoundCollapse by viewModel.chatRoundCollapse.collectAsStateWithLifecycle()
    val customSystemPromptEnabled by viewModel.customSystemPromptEnabled.collectAsStateWithLifecycle()
    val customSystemPrompt by viewModel.customSystemPrompt.collectAsStateWithLifecycle()
    val agentCharName by viewModel.agentCharName.collectAsStateWithLifecycle()
    val agentUserName by viewModel.agentUserName.collectAsStateWithLifecycle()
    val compactionEnabled by viewModel.contextCompactionEnabled.collectAsStateWithLifecycle()
    val maxToolRounds by viewModel.maxToolRounds.collectAsStateWithLifecycle()
    val roundLimitAutoContinuations by viewModel.roundLimitAutoContinuations.collectAsStateWithLifecycle()
    val autoWorkspaceCwd by viewModel.autoWorkspaceCwd.collectAsStateWithLifecycle()
    val commandOutputCompressionEnabled by viewModel.commandOutputCompressionEnabled.collectAsStateWithLifecycle()
    val baseCommandTimeoutSeconds by viewModel.baseCommandTimeoutSeconds.collectAsStateWithLifecycle()
    val approvalMode by viewModel.approvalMode.collectAsStateWithLifecycle()
    val runMode by viewModel.runMode.collectAsStateWithLifecycle()
    val runningSendMode by viewModel.runningSendMode.collectAsStateWithLifecycle()
    val maxToolsPerRound by viewModel.maxToolsPerRound.collectAsStateWithLifecycle()
    val maxConsecutiveFailures by viewModel.maxConsecutiveFailures.collectAsStateWithLifecycle()
    val contextBudgetTokens by viewModel.contextBudgetTokens.collectAsStateWithLifecycle()
    val effectiveContextBudget by viewModel.effectiveContextBudget.collectAsStateWithLifecycle()
    val activeModelDeclaredTokens by viewModel.activeModelDeclaredTokens.collectAsStateWithLifecycle()
    val contextFoldingRatioPercent by viewModel.contextFoldingRatioPercent.collectAsStateWithLifecycle()
    val skills by viewModel.allSkills.collectAsStateWithLifecycle()
    val subagents by viewModel.allSubagents.collectAsStateWithLifecycle()
    val autoSubagentDelegation by viewModel.autoSubagentDelegationEnabled.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val plugins by viewModel.allPlugins.collectAsStateWithLifecycle()
    val skillArchiveMessage by viewModel.skillArchiveMessage.collectAsStateWithLifecycle()
    val skillArchiveMessageIsError by viewModel.skillArchiveMessageIsError.collectAsStateWithLifecycle()
    val skillEvolutionSuggestions by viewModel.skillEvolutionSuggestions.collectAsStateWithLifecycle()
    val marketSkills by viewModel.clawHubMarketSkills.collectAsStateWithLifecycle()
    val isMarketLoading by viewModel.isMarketLoading.collectAsStateWithLifecycle()
    val isMarketOfflinePreset by viewModel.isMarketOfflinePreset.collectAsStateWithLifecycle()
    val pendingSkillInspection by viewModel.pendingSkillInspection.collectAsStateWithLifecycle()
    val preparingSkillId by viewModel.preparingSkillId.collectAsStateWithLifecycle()
    val isCommittingInstallation by viewModel.isCommittingInstallation.collectAsStateWithLifecycle()

    var showAddSkillDialog by remember { mutableStateOf(false) }
    var viewingSkillPrompt by remember { mutableStateOf<AgentSkill?>(null) }
    var editingSubagent by remember { mutableStateOf<AgentSubagent?>(null) }
    var showSubagentDialog by remember { mutableStateOf(false) }
    var deletingSubagent by remember { mutableStateOf<AgentSubagent?>(null) }
    var deletingSkill by remember { mutableStateOf<AgentSkill?>(null) }
    var subagentQuery by rememberSaveable { mutableStateOf("") }
    var skillQuery by rememberSaveable { mutableStateOf("") }
    var expandedDepartmentIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    val visibleSubagentGroups = remember(subagents, subagentQuery) {
        val query = subagentQuery.trim().lowercase()
        subagents.asSequence()
            .filter { profile ->
                query.isBlank() || profile.name.lowercase().contains(query) ||
                    profile.id.lowercase().contains(query) || profile.description.lowercase().contains(query)
            }
            .groupBy { it.departmentId }
            .entries
            .sortedBy { AgentDepartments.find(it.key).sortOrder }
    }
    val visibleSkills = remember(skills, skillQuery) {
        val query = skillQuery.trim().lowercase()
        if (query.isBlank()) {
            skills
        } else {
            skills.filter { skill ->
                skill.name.lowercase().contains(query) || skill.id.lowercase().contains(query) ||
                    skill.description.lowercase().contains(query) || skill.category.lowercase().contains(query)
            }
        }
    }
    val skillArchivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size == 1) {
            viewModel.inspectLocalSkillZip(uris.first())
        } else if (uris.isNotEmpty()) {
            viewModel.importSkillArchives(uris)
        }
    }
    val skillDirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) viewModel.importSkillsFromTree(uri) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = when (category) {
                    AgentSettingsCategory.EXECUTION -> "Agent 执行与上下文"
                    AgentSettingsCategory.SUBAGENTS -> "子智能体角色"
                    AgentSettingsCategory.SKILLS -> "Skills 与插件"
                },
                statusText = "按领域独立管理 Agent 能力",
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (category == AgentSettingsCategory.EXECUTION) {
            // ---- 模块 1：模型思考与交互表现 ----
            item {
                SectionHeader(
                    title = "思考流与执行表现",
                    subtitle = "控制 DeepSeek 等推理模型的思考过程呈现与工具执行上限",
                )
            }
            item {
                AgentBlockTitle("思考呈现与翻译")
            }
            item {
                AgentSettingsGroup {
                    AgentToggleRow(
                        icon = RuntimeIconName.Brain,
                        title = "默认展开模型思考过程",
                        subtitle = if (thinkingExpanded) "聊天界面中新生成的思考过程将默认展开呈现" else "思考过程（包括生成中内容）默认折叠，点击可展开查看",
                        checked = thinkingExpanded,
                        onCheckedChange = viewModel::setThinkingExpanded,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AgentToggleRow(
                        icon = RuntimeIconName.Globe,
                        title = "展开时自动翻译英文思考为中文",
                        subtitle = if (thinkingAutoTranslate) "展开思考块时若检测为英文将自动调用本地离线模型翻译呈现" else "仅在点击思考块上的翻译按钮时进行翻译",
                        checked = thinkingAutoTranslate,
                        onCheckedChange = viewModel::setThinkingAutoTranslate,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AgentToggleRow(
                        icon = RuntimeIconName.Compress,
                        title = "自动收拢历史轮次的思考与工具过程",
                        subtitle = if (chatRoundCollapse) "已结束的历史轮次中，超过 2 步的思考与工具调用将收拢为「展开更多」摘要，点击即可展开；当前进行中的轮次始终完整呈现" else "关闭时保持自然单行流：全部思考过程与工具调用逐条平铺呈现",
                        checked = chatRoundCollapse,
                        onCheckedChange = viewModel::setChatRoundCollapse,
                    )
                }
            }
            item {
                AgentBlockTitle("思考流离线翻译语种模型")
            }
            item {
                AgentSettingsGroup {
                    TranslationModelCard(
                        status = translationModelStatus,
                        onDownload = { viewModel.downloadTranslationModel() },
                        onDelete = { viewModel.deleteTranslationModel() },
                        onRetry = { viewModel.downloadTranslationModel() },
                    )
                }
            }
            item {
                AgentBlockTitle("执行上下文")
            }
            item {
                AgentSettingsGroup {
                    AgentToggleRow(
                        icon = RuntimeIconName.FolderOpen,
                        title = "自动注入关联工作区路径",
                        subtitle = "当会话关联了工作区时，执行 base 命令默认以该目录为工作路径 (cwd)",
                        checked = autoWorkspaceCwd,
                        onCheckedChange = viewModel::setAutoWorkspaceCwd,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AgentToggleRow(
                        icon = RuntimeIconName.Compress,
                        title = "智能压缩 Agent 命令输出",
                        subtitle = "减少 Agent 读取 git、搜索、测试与构建日志时的上下文消耗；终端与原始文件不受影响",
                        checked = commandOutputCompressionEnabled,
                        onCheckedChange = viewModel::setCommandOutputCompressionEnabled,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ApprovalModeSelectorRow(
                        mode = approvalMode,
                        onModeChange = viewModel::setApprovalMode,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RunModeSelectorRow(
                        mode = runMode,
                        onModeChange = viewModel::setRunMode,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RunningSendModeSelectorRow(
                        mode = runningSendMode,
                        onModeChange = viewModel::setRunningSendMode,
                    )
                }
            }
            item {
                AgentBlockTitle("工具调用限制")
            }
            item {
                AgentSettingsGroup {
                    BaseCommandTimeoutSliderRow(
                        currentValue = baseCommandTimeoutSeconds,
                        onValueChange = viewModel::setBaseCommandTimeoutSeconds,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RoundsSliderRow(
                        currentValue = maxToolRounds,
                        onValueChange = viewModel::setMaxToolRounds,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AutoContinuationSliderRow(
                        currentValue = roundLimitAutoContinuations,
                        roundsPerSegment = maxToolRounds,
                        onValueChange = viewModel::setRoundLimitAutoContinuations,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ToolsPerRoundSliderRow(
                        currentValue = maxToolsPerRound,
                        onValueChange = viewModel::setMaxToolsPerRound,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ConsecutiveFailuresSliderRow(
                        currentValue = maxConsecutiveFailures,
                        onValueChange = viewModel::setMaxConsecutiveFailures,
                    )
                }
            }

            // ---- 模块 2：系统提示词与人设自定义 ----
            item {
                SectionHeader(
                    title = "系统提示词与人设自定义",
                    subtitle = "定制 Agent 初始人设、环境说明与动态宏变量注入",
                )
            }
            item {
                AgentBlockTitle("提示词编辑")
            }
            item {
                SystemPromptCustomCard(
                    enabled = customSystemPromptEnabled,
                    onEnabledChange = viewModel::setCustomSystemPromptEnabled,
                    prompt = customSystemPrompt,
                    onPromptChange = viewModel::setCustomSystemPrompt,
                    charName = agentCharName,
                    onCharNameChange = viewModel::setAgentCharName,
                    userName = agentUserName,
                    onUserNameChange = viewModel::setAgentUserName,
                )
            }

            // ---- 模块 2：上下文记忆与智能压缩 ----
            item {
                SectionHeader(
                    title = "上下文记忆与智能压缩",
                    subtitle = "优化长任务 Token 消耗，防止触及模型上下文窗口上限",
                )
            }
            item {
                AgentBlockTitle("压缩策略")
            }
            item {
                AgentSettingsGroup {
                    AgentToggleRow(
                        icon = RuntimeIconName.Compress,
                        title = "开启上下文智能压缩 (Context Compaction)",
                        subtitle = "历史超过 Token 折叠线时自动生成结构化摘要，保留近期原文、任务状态与关键文件足迹",
                        checked = compactionEnabled,
                        onCheckedChange = viewModel::setContextCompactionEnabled,
                    )
                    if (compactionEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ContextBudgetSliderRow(
                            currentValue = contextBudgetTokens,
                            declaredTokens = activeModelDeclaredTokens,
                            onValueChange = viewModel::setContextBudgetTokens,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ContextFoldingRatioSliderRow(
                            currentValue = contextFoldingRatioPercent,
                            budget = effectiveContextBudget,
                            declaredTokens = activeModelDeclaredTokens,
                            onValueChange = viewModel::setContextFoldingRatioPercent,
                        )
                    }
                }
            }

            }

            if (category == AgentSettingsCategory.SUBAGENTS) {
            // ---- 模块 3：内置子智能体 ----
            item {
                SectionHeader(
                    title = "软件研发 Agent (${subagents.count { it.isEnabled }}/${subagents.size} 已启用)",
                    subtitle = "Agency Agents · 9 个研发部门 · MIT 许可 · 完整提示词离线内置",
                )
            }
            item {
                AgentBlockTitle("自动委派策略")
            }
            item {
                AgentSettingsGroup {
                    AgentToggleRow(
                        icon = RuntimeIconName.Bot,
                        title = "自动判断并拆分任务",
                        subtitle = if (autoSubagentDelegation) {
                            "复杂且可并行的任务将由主智能体自行决定是否派发"
                        } else {
                            "仅在用户明确要求并行或子智能体协同时派发"
                        },
                        checked = autoSubagentDelegation,
                        onCheckedChange = viewModel::setAutoSubagentDelegationEnabled,
                    )
                }
            }
            item {
                AgentBlockTitle("角色列表")
            }
            item {
                OutlinedTextField(
                    value = subagentQuery,
                    onValueChange = { subagentQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索 Agent") },
                    placeholder = { Text("名称、角色标识或职责") },
                    leadingIcon = { RuntimeIcon(RuntimeIconName.Search, Modifier.size(18.dp)) },
                    singleLine = true,
                )
            }
            item {
                Button(
                    onClick = {
                        editingSubagent = null
                        showSubagentDialog = true
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RuntimeIcon(RuntimeIconName.Plus, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("新增子智能体角色", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    }
                }
            }
            visibleSubagentGroups.forEach { (departmentId, profiles) ->
                val expanded = subagentQuery.isNotBlank() || departmentId in expandedDepartmentIds
                item(key = "department:$departmentId") {
                    AgentDepartmentCard(
                        departmentId = departmentId,
                        enabledCount = profiles.count { it.isEnabled },
                        agentCount = profiles.size,
                        expanded = expanded,
                        onClick = {
                            if (subagentQuery.isBlank()) {
                                expandedDepartmentIds = if (expanded) {
                                    expandedDepartmentIds - departmentId
                                } else {
                                    expandedDepartmentIds + departmentId
                                }
                            }
                        },
                    )
                }
                if (expanded) {
                    items(profiles, key = { "subagent:${it.id}" }) { profile ->
                        SubagentProfileCard(
                            profile = profile,
                            modelLabel = profile.defaultModelId?.let { modelId ->
                                models.firstOrNull { it.id == modelId }?.let { model ->
                                    "${model.name} · ${profile.defaultModelVariant ?: model.model.substringBefore(',').trim()}"
                                } ?: "模型档案已删除"
                            } ?: "跟随主智能体",
                            onToggle = { enabled -> viewModel.toggleSubagent(profile.id, enabled) },
                            onEdit = {
                                editingSubagent = profile
                                showSubagentDialog = true
                            },
                            onDelete = { deletingSubagent = profile },
                        )
                    }
                }
            }

            }

            if (category == AgentSettingsCategory.SKILLS) {
            // ---- 模块 4：Skill 专精技能库 ----
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(
                        title = "Skill 专精技能库 (${skills.count { it.isEnabled }}/${skills.size} 已启用)",
                        subtitle = "向 Agent 系统提示词注入领域专业规范与操作指导",
                    )
                }
            }
            item {
                AgentBlockTitle("技能进化")
            }
            item {
                AgentSettingsGroup {
                    AgentToggleRow(
                        icon = RuntimeIconName.Sparkles,
                        title = "对话后建议沉淀或修复技能",
                        subtitle = if (skillEvolutionSuggestions) {
                            "成功完成一轮含工具调用的工作后，可建议创建新技能或修复既有自定义技能"
                        } else {
                            "已关闭：对话结束后不再弹出技能进化建议卡片"
                        },
                        checked = skillEvolutionSuggestions,
                        onCheckedChange = viewModel::setSkillEvolutionSuggestions,
                    )
                }
            }
            item {
                AgentBlockTitle("技能管理")
            }
            item {
                OutlinedButton(onClick = { skillArchivePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RuntimeIcon(RuntimeIconName.FolderDownload, Modifier.size(16.dp))
                        Text("从 ZIP 导入 Skill（支持批量多选）")
                    }
                }
            }
            item {
                OutlinedButton(onClick = viewModel::scanSkillDirectories, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RuntimeIcon(RuntimeIconName.FolderOpen, Modifier.size(16.dp))
                        Text("扫描 Skill 目录批量导入")
                    }
                }
                Text(
                    text = "可将 rikkahub、aicode 等工具的 skills 目录整体复制到 attachments/skills 或工作区 skills 目录，重启应用即自动批量导入；运行中复制可点击上方按钮立即扫描，已导入的不会重复注册",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            }
            item {
                OutlinedButton(onClick = { skillDirPicker.launch(null) }, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RuntimeIcon(RuntimeIconName.Search, Modifier.size(16.dp))
                        Text("从自选目录导入（任意位置）")
                    }
                }
                Text(
                    text = "在文件管理器中任选一个目录（支持任意深度嵌套，含 Skill 目录内再嵌套 Skill 子目录的情况），自动查找其中所有包含 SKILL.md 的技能文件夹并导入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            }
            item {
                Button(
                    onClick = { showAddSkillDialog = true },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RuntimeIcon(RuntimeIconName.Sparkles, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("新增自定义 Skill 技能", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = skillQuery,
                    onValueChange = { skillQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索 Skill") },
                    placeholder = { Text("名称、标识、分类或描述") },
                    leadingIcon = { RuntimeIcon(RuntimeIconName.Search, Modifier.size(18.dp)) },
                    singleLine = true,
                )
            }

            items(visibleSkills, key = { it.id }) { skill ->
                SkillCard(
                    skill = skill,
                    onToggle = { enabled -> viewModel.toggleSkill(skill.id, enabled) },
                    onViewPrompt = { viewingSkillPrompt = skill },
                    onDelete = if (!skill.isBuiltin) { { deletingSkill = skill } } else null,
                )
            }
            if (visibleSkills.isEmpty()) {
                item {
                    Text(
                        text = "没有匹配 \"${skillQuery.trim()}\" 的技能",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // ---- 模块 4.5：ClawHub 技能生态市场 ----
            item {
                SectionHeader(
                    title = "ClawHub 技能生态市场",
                    subtitle = if (isMarketOfflinePreset) {
                        "离线精选模式：以下为内置精选生态包（远端市场暂未接入）；安装前仍由本地引擎执行静态安全与兼容性审查"
                    } else {
                        "精选社区标准生态技能包，下载前由本地引擎执行静态安全与兼容性审查"
                    },
                )
            }

            if (isMarketLoading && marketSkills.isEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            } else {
                items(marketSkills, key = { "clawhub_" + it.id }) { marketItem ->
                    ClawHubMarketSkillCard(
                        item = marketItem,
                        isPreparing = preparingSkillId == marketItem.id,
                        onInstall = { viewModel.prepareInstallMarketSkill(marketItem.id) },
                    )
                }
            }

            // ---- 模块 5：Plugin 插件生态管理 ----
            item {
                SectionHeader(
                    title = "Plugin 插件生态管理 (${plugins.count { it.isEnabled }}/${plugins.size} 运行中)",
                    subtitle = "沙箱运行时探测、高危操作安全拦截与扩展能力",
                )
            }

            item {
                AgentBlockTitle("已安装插件")
            }
            items(plugins, key = { it.id }) { plugin ->
                PluginCard(
                    plugin = plugin,
                    onToggle = { enabled -> viewModel.togglePlugin(plugin.id, enabled) },
                )
            }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    pendingSkillInspection?.let { inspection ->
        SkillSecurityAuditDialog(
            inspection = inspection,
            isCommitting = isCommittingInstallation,
            onConfirmInstall = viewModel::confirmSkillInstallation,
            onDismiss = viewModel::dismissSkillInspection,
        )
    }

    skillArchiveMessage?.let { message ->
        RuntimeAlertDialog(
            onDismissRequest = viewModel::clearSkillArchiveMessage,
            title = { Text(if (!skillArchiveMessageIsError) "Skill 导入完成" else "Skill 导入失败", fontWeight = FontWeight.Bold) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearSkillArchiveMessage) { Text("知道了") } },
        )
    }

    // 查看 Skill 完整提示词弹窗
    viewingSkillPrompt?.let { skill ->
        RuntimeAlertDialog(
            onDismissRequest = { viewingSkillPrompt = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(skill.name, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(skill.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("注入系统的指导提示词 (System Prompt)：", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            skill.systemPrompt,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingSkillPrompt = null }) {
                    Text("关闭")
                }
            },
        )
    }

    // 新增自定义 Skill 弹窗
    if (showAddSkillDialog) {
        AddSkillDialog(
            onDismiss = { showAddSkillDialog = false },
            onConfirm = { name, desc, prompt, cmd ->
                viewModel.addCustomSkill(name, desc, prompt, cmd)
                showAddSkillDialog = false
            },
        )
    }

    if (showSubagentDialog) {
        SubagentEditorDialog(
            profile = editingSubagent,
            models = models,
            existingIds = subagents.mapTo(mutableSetOf()) { it.id },
            onDismiss = {
                showSubagentDialog = false
                editingSubagent = null
            },
            onConfirm = { roleId, name, description, prompt, defaultModelId, defaultModelVariant ->
                viewModel.saveSubagent(
                    editingSubagent,
                    roleId,
                    name,
                    description,
                    prompt,
                    defaultModelId,
                    defaultModelVariant,
                )
                showSubagentDialog = false
                editingSubagent = null
            },
        )
    }

    deletingSkill?.let { skill ->
        RuntimeAlertDialog(
            onDismissRequest = { deletingSkill = null },
            title = { Text("删除 Skill", fontWeight = FontWeight.Bold) },
            text = { Text("确定删除“${skill.name}”吗？其脚本资源目录将一并清理，删除后不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCustomSkill(skill.id)
                        deletingSkill = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSkill = null }) { Text("取消") }
            },
        )
    }

    deletingSubagent?.let { profile ->
        RuntimeAlertDialog(
            onDismissRequest = { deletingSubagent = null },
            title = { Text("删除子智能体", fontWeight = FontWeight.Bold) },
            text = { Text("确定删除“${profile.name}”吗？删除后该角色不会再参与任务委派。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSubagent(profile.id)
                        deletingSubagent = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSubagent = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BaseCommandTimeoutSliderRow(
    currentValue: Int,
    onValueChange: (Int) -> Unit,
) {
    var sliderMinutes by remember(currentValue) {
        mutableFloatStateOf((currentValue / 60f).coerceIn(1f, 60f))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("base 命令默认超时", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                "${sliderMinutes.toInt()} 分钟",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "适用于普通前台命令；模型可为单次命令指定 1–60 分钟，常驻任务应使用 process 工具",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderMinutes,
            onValueChange = { sliderMinutes = it },
            onValueChangeFinished = { onValueChange(sliderMinutes.toInt() * 60) },
            valueRange = 1f..60f,
            steps = 58,
        )
    }
}

@Composable
private fun AgentSettingsGroup(content: @Composable () -> Unit) {
    RuntimeCard(
        Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun AgentBlockTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AgentToggleRow(
    icon: RuntimeIconName,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            RuntimeIcon(icon, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}


@Composable
private fun AgentDepartmentCard(
    departmentId: String,
    enabledCount: Int,
    agentCount: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val department = AgentDepartments.find(departmentId)
    val departmentColor = Color(department.colorArgb)
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = departmentColor.copy(alpha = 0.4f),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(departmentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                RuntimeIcon(RuntimeIconName.Bot, Modifier.size(20.dp), departmentColor)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${department.localizedName} · ${department.name}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    "$enabledCount/$agentCount 已启用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RuntimeIcon(
                if (expanded) RuntimeIconName.ChevronUp else RuntimeIconName.ChevronDown,
                Modifier.size(20.dp),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubagentProfileCard(
    profile: AgentSubagent,
    modelLabel: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (profile.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    RuntimeIcon(RuntimeIconName.Bot, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(profile.id, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary)
                }
                if (profile.isBuiltin) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "Agency",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(profile.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "默认模型：$modelLabel",
                style = MaterialTheme.typography.labelSmall,
                color = if (profile.defaultModelId == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(36.dp),
                    contentDescription = "编辑角色",
                ) {
                    RuntimeIcon(RuntimeIconName.Edit, Modifier.size(17.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(36.dp),
                    contentDescription = "删除角色",
                ) {
                    RuntimeIcon(RuntimeIconName.Trash, Modifier.size(17.dp), MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = profile.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
    }
}



@Composable
private fun RoundsSliderRow(
    currentValue: Int,
    onValueChange: (Int) -> Unit,
) {
    var sliderVal by remember(currentValue) { mutableFloatStateOf(currentValue.toFloat()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("单回合最大工具轮次上限", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                "${sliderVal.toInt()} 轮",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "防止复杂任务中模型陷入死循环；达到轮次后进入下方的自动续跑检查点",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderVal,
            onValueChange = { sliderVal = it },
            onValueChangeFinished = { onValueChange(sliderVal.toInt()) },
            valueRange = 10f..300f,
            steps = 28, // 10, 20, 30 ... 300
        )
    }
}

@Composable
private fun AutoContinuationSliderRow(
    currentValue: Int,
    roundsPerSegment: Int,
    onValueChange: (Int) -> Unit,
) {
    var sliderVal by remember(currentValue) { mutableFloatStateOf(currentValue.toFloat()) }
    val continuations = sliderVal.toInt()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("轮次用尽后自动续跑", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                if (continuations == 0) "关闭" else "$continuations 次",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            if (continuations == 0) {
                "轮次用尽即停下等待用户确认；适合希望逐段把关的场景"
            } else {
                "轮次用尽时先让模型收束并记录进度，再自动续跑，无需用户点击继续；" +
                    "总预算上限 ${roundsPerSegment * (continuations + 1)} 轮"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderVal,
            onValueChange = { sliderVal = it },
            onValueChangeFinished = { onValueChange(sliderVal.toInt()) },
            valueRange = 0f..10f,
            steps = 9, // 0, 1, 2 ... 10
        )
    }
}

@Composable
private fun ContextBudgetSliderRow(
    currentValue: Int,
    declaredTokens: Int?,
    onValueChange: (Int) -> Unit,
) {
    var sliderVal by remember(currentValue) { mutableFloatStateOf(currentValue.toFloat()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("上下文 Token 预算上限", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                "${sliderVal.toInt()} tok",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            if (declaredTokens != null) {
                "当前激活模型已适配上下文上限 ${declaredTokens / 1000}K（显式配置或自动识别），此滑块暂不生效；" +
                    "仅在模型无法识别且未配置 contextTokens 时，本值才作为兜底预算。"
            } else {
                "当前未识别到激活模型上下文上限，本值即实际生效预算；" +
                    "长会话历史超出预算将自动折叠早期内容。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderVal,
            onValueChange = { sliderVal = it },
            onValueChangeFinished = { onValueChange(sliderVal.toInt()) },
            valueRange = 8000f..2000000f,
            steps = 49, // 步长约 4 万 tok
        )
    }
}

/**
 * 「历史折叠线比例」滑块。
 *
 * 让历史在预算的一部分处就开始折叠，而不是等到预算减预留的硬线才动手——
 * 长会话可借此显著降低单次请求的 input token 量（省费用、降首字延迟）。
 */
@Composable
private fun ContextFoldingRatioSliderRow(
    currentValue: Int,
    budget: Int,
    declaredTokens: Int?,
    onValueChange: (Int) -> Unit,
) {
    var sliderVal by remember(currentValue) { mutableFloatStateOf(currentValue.toFloat()) }
    // 实时预览：按当前比例算出的折叠线，让用户直观看到「拖到多少就按多少折叠」。
    // 关键：budget 必须是**实际生效预算**（模型档案 contextTokens 优先，再钳到 MAX_CONTEXT_BUDGET），
    // 并且扣减引擎同款 system/输出/工具 schema 预留，否则设置页会高于真实折叠触发线。
    val previewSystemTokens = ContextWindowPolicy.estimateReservedPromptTokens(
        pureChat = false,
        toolDisabled = false,
    )
    val previewLimit = remember(sliderVal, budget, previewSystemTokens) {
        ContextWindowPolicy.foldingLimitFor(
            budget = budget,
            ratioPercent = sliderVal.toInt(),
            systemTokens = previewSystemTokens,
        ).coerceAtLeast(0)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("历史折叠线比例", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                "${sliderVal.toInt()}%",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "历史在「预算 × 比例」处开始折叠；默认 100%，对齐主流 harness 的" +
                " contextWindow - reserveTokens - toolSchemaReserve - systemTokens。" +
                "调低比例可让历史更早进入摘要、节省 input token；大窗口模型不再被 96K 封顶。" +
                "走 Nginx/中转时请把 client_max_body_size 调到 20m 以上，否则可能 HTTP 413。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "按当前设置，折叠线约为 ${previewLimit / 1000}K tok",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary,
        )
        // 说明这个预览数按什么预算折算，避免用户误以为它基于「上下文预算上限」滑块。
        Text(
            if (declaredTokens != null) {
                "以上按当前生效模型窗口 ${budget / 1000}K 计算" +
                    "（显式配置或自动适配）" +
                    (if (declaredTokens > ContextWindowPolicy.MAX_CONTEXT_BUDGET) {
                        "，引擎钳制到 ${ContextWindowPolicy.MAX_CONTEXT_BUDGET / 1000}K"
                    } else {
                        ""
                    }) +
                    "。预览已扣除系统提示/输出/工具 schema 预留。"
            } else {
                "以上按上方「上下文预算上限」滑块的值计算（当前未识别到模型上下文上限）。" +
                    "预览已扣除系统提示/输出/工具 schema 预留。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderVal,
            onValueChange = { sliderVal = it },
            onValueChangeFinished = { onValueChange(sliderVal.toInt()) },
            // 与 SettingsDataStore.setContextFoldingRatioPercent 的 coerceIn(10, 100) 对齐
            valueRange = 10f..100f,
            steps = 8, // 步长 10%
        )
    }
}

@Composable
private fun ToolsPerRoundSliderRow(
    currentValue: Int,
    onValueChange: (Int) -> Unit,
) {
    var sliderVal by remember(currentValue) { mutableFloatStateOf(currentValue.toFloat()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("单轮最大工具调用数", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                "${sliderVal.toInt()} 个",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "防止模型一次性爆发大量工具调用耗尽上下文或失控循环",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderVal,
            onValueChange = { sliderVal = it },
            onValueChangeFinished = { onValueChange(sliderVal.toInt()) },
            valueRange = 1f..30f,
            steps = 29,
        )
    }
}

@Composable
private fun ConsecutiveFailuresSliderRow(
    currentValue: Int,
    onValueChange: (Int) -> Unit,
) {
    var sliderVal by remember(currentValue) { mutableFloatStateOf(currentValue.toFloat()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("连续失败熔断阈值", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                "${sliderVal.toInt()} 轮",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "连续多轮工具调用全部失败时主动终止，避免陷入死循环空转",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderVal,
            onValueChange = { sliderVal = it },
            onValueChangeFinished = { onValueChange(sliderVal.toInt()) },
            valueRange = 2f..30f,
            steps = 28,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillCard(
    skill: AgentSkill,
    onToggle: (Boolean) -> Unit,
    onViewPrompt: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (skill.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    skill.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        skill.category,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val cmd = skill.triggerCommand
                if (cmd != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            cmd,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Text(skill.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onViewPrompt, contentPadding = PaddingValues(0.dp)) {
                    Text("查看指导词 (Prompt)", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                if (skill.isImmutable) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "系统核心常驻",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                } else {
                    if (onDelete != null) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .size(32.dp),
                            contentDescription = "删除 Skill",
                        ) {
                            RuntimeIcon(RuntimeIconName.Trash, Modifier.size(16.dp), MaterialTheme.colorScheme.error)
                        }
                    }
                    Switch(
                        checked = skill.isEnabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClawHubMarketSkillCard(
    item: ClawHubMarketItem,
    isPreparing: Boolean,
    onInstall: () -> Unit,
) {
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        item.category,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        "v${item.version}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "作者: ${item.author} · ★ ${item.stars}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    isPreparing -> {
                        Button(
                            onClick = {},
                            enabled = false,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                Text("审查中…", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    item.isInstalled -> {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("已安装", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    else -> {
                        Button(
                            onClick = onInstall,
                            enabled = !isPreparing,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RuntimeIcon(name = RuntimeIconName.Shield, modifier = Modifier.size(14.dp))
                                Text("审查并安装", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginCard(
    plugin: AgentPlugin,
    onToggle: (Boolean) -> Unit,
) {
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (plugin.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            plugin.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text("v${plugin.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "作者: ${plugin.author}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = plugin.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }

            Text(plugin.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (plugin.permissions.isNotEmpty()) {
                val visiblePermissions = plugin.permissions.take(4)
                val hiddenCount = plugin.permissions.size - visiblePermissions.size
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "权限:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    visiblePermissions.forEach { perm ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                perm,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp).padding(horizontal = 4.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (hiddenCount > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                "等 ${plugin.permissions.size} 项权限",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String, systemPrompt: String, command: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增自定义 Skill 技能", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("技能名称（如: Rust 编译专家）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("简要描述") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cmd,
                    onValueChange = { cmd = it },
                    label = { Text("触发指令（选填，如 /rust）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("系统提示词规则 (System Prompt)") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, desc, prompt, cmd) },
                enabled = name.isNotBlank() && prompt.isNotBlank(),
            ) {
                Text("添加并启用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun SubagentEditorDialog(
    profile: AgentSubagent?,
    models: List<AiModelEntity>,
    existingIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (
        roleId: String,
        name: String,
        description: String,
        systemPrompt: String,
        defaultModelId: String?,
        defaultModelVariant: String?,
    ) -> Unit,
) {
    var roleId by remember(profile?.id) { mutableStateOf(profile?.id.orEmpty()) }
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var description by remember(profile?.id) { mutableStateOf(profile?.description.orEmpty()) }
    var prompt by remember(profile?.id) { mutableStateOf(profile?.systemPrompt.orEmpty()) }
    var defaultModelId by remember(profile?.id) { mutableStateOf(profile?.defaultModelId) }
    var defaultModelVariant by remember(profile?.id) { mutableStateOf(profile?.defaultModelVariant) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val normalizedId = roleId.trim().lowercase()
        .replace(Regex("[^a-z0-9_-]+"), "_")
        .trim('_')
    val duplicateId = normalizedId.isNotBlank() && normalizedId != profile?.id && normalizedId in existingIds
    val canSave = normalizedId.isNotBlank() && name.isNotBlank() && prompt.isNotBlank() && !duplicateId

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (profile == null) "新增子智能体角色" else "编辑子智能体角色", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = roleId,
                    onValueChange = { roleId = it },
                    label = { Text("角色标识") },
                    supportingText = {
                        Text(if (duplicateId) "该角色标识已存在" else "仅支持英文、数字、下划线和连字符")
                    },
                    isError = duplicateId,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("职责简介") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("角色指导词") },
                    minLines = 5,
                    maxLines = 9,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("默认模型", style = MaterialTheme.typography.labelMedium)
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { modelMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val selected = models.firstOrNull { it.id == defaultModelId }
                            Text(
                                selected?.let { "${it.name} · ${defaultModelVariant ?: it.model.substringBefore(',').trim()}" }
                                    ?: if (defaultModelId == null) "跟随主智能体" else "模型档案已删除",
                                modifier = Modifier.weight(1f),
                            )
                            RuntimeIcon(RuntimeIconName.ChevronDown, Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("跟随主智能体") },
                                onClick = {
                                    defaultModelId = null
                                    defaultModelVariant = null
                                    modelMenuExpanded = false
                                },
                            )
                            models.forEach { model ->
                                model.model.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { variant ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(model.name)
                                                Text(
                                                    "${model.provider} · $variant",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            defaultModelId = model.id
                                            defaultModelVariant = variant
                                            modelMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "任务派发时显式指定的模型仍会优先于此设置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(normalizedId, name, description, prompt, defaultModelId, defaultModelVariant)
                },
                enabled = canSave,
            ) {
                Text(if (profile == null) "添加并启用" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemPromptCustomCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    charName: String,
    onCharNameChange: (String) -> Unit,
    userName: String,
    onUserNameChange: (String) -> Unit,
) {
    // 仅在首次进入时以持久化值初始化缓冲，避免 remember(prompt) 键在每次 Datastore 回写时重置输入；
    // 输入经 400ms 防抖后再写入 Datastore，避免每次按键都落盘
    var textBuffer by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(prompt) }
    androidx.compose.runtime.LaunchedEffect(textBuffer) {
        if (textBuffer != prompt) {
            delay(400)
            onPromptChange(textBuffer)
        }
    }

    // 称呼输入同样走 400ms 防抖落盘；留空即回退默认值
    var charNameBuffer by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(charName) }
    androidx.compose.runtime.LaunchedEffect(charNameBuffer) {
        if (charNameBuffer != charName) {
            delay(400)
            onCharNameChange(charNameBuffer)
        }
    }
    var userNameBuffer by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(userName) }
    androidx.compose.runtime.LaunchedEffect(userNameBuffer) {
        if (userNameBuffer != userName) {
            delay(400)
            onUserNameChange(userNameBuffer)
        }
    }

    var showDefaultPromptDialog by remember { mutableStateOf(false) }
    var defaultPromptAsset by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val androidContext = androidx.compose.ui.platform.LocalContext.current
    if (showDefaultPromptDialog) {
        androidx.compose.runtime.LaunchedEffect(showDefaultPromptDialog) {
            if (defaultPromptAsset.isBlank()) {
                defaultPromptAsset = runCatching {
                    androidContext.assets.open("prompts/system/core.md").bufferedReader().use { it.readText() }
                }.getOrDefault("（默认系统提示词加载失败）")
            }
        }
        RuntimeAlertDialog(
            onDismissRequest = { showDefaultPromptDialog = false },
            title = { Text("默认系统提示词 (core.md)", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        defaultPromptAsset,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDefaultPromptDialog = false }) { Text("知道了") }
            },
        )
    }

    val defaultPromptTemplate = """
You are a helpful and expert AI assistant called {{char}}, based on model {{model_name}}.

## Persona
- You refer to yourself as {{char}} in conversations.
- You address the user as {{user}}.

## System & Device Context
- Time: {{cur_datetime}}
- Locale: {{locale}}
- Timezone: {{timezone}}
- Device: {{device_info}}
- System: {{system_version}}
- Battery: {{battery_level}}

## Instructions
- Always think carefully before answering.
- Follow user instructions precisely and write clean code.
    """.trimIndent()

    val availableVariables = listOf(
        "{{cur_date}}" to "日期",
        "{{cur_time}}" to "时间",
        "{{cur_datetime}}" to "日期时间",
        "{{model_name}}" to "模型名称",
        "{{model_id}}" to "模型ID",
        "{{locale}}" to "语言环境",
        "{{timezone}}" to "时区",
        "{{device_info}}" to "设备信息",
        "{{system_version}}" to "系统版本",
        "{{battery_level}}" to "电池电量",
        "{{char}}" to "助手名称",
        "{{user}}" to "用户名称",
    )

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    RuntimeIcon(RuntimeIconName.Prompt, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("自定义系统提示词 (System Prompt)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("覆盖全局默认 System Prompt，支持动态宏变量注入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            // 称呼配置独立于自定义提示词开关：默认提示词同样生效
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("称呼与人设", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = { showDefaultPromptDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        RuntimeIcon(RuntimeIconName.Visibility, Modifier.size(12.dp))
                        Text("查看默认系统提示词", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = charNameBuffer,
                    onValueChange = { charNameBuffer = it },
                    label = { Text("模型自称") },
                    placeholder = { Text("太墟智枢") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = userNameBuffer,
                    onValueChange = { userNameBuffer = it },
                    label = { Text("对用户的称呼") },
                    placeholder = { Text("用户") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                "修改后立即对所有对话生效（留空恢复默认）；自定义提示词中的 {{char}} / {{user}} 宏变量也将使用这两个值。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (enabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("提示词模板内容：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    TextButton(
                        onClick = {
                            textBuffer = defaultPromptTemplate
                            onPromptChange(defaultPromptTemplate)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(12.dp))
                            Text("重置模板", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                OutlinedTextField(
                    value = textBuffer,
                    onValueChange = { textBuffer = it },
                    placeholder = { Text("在此输入自定义系统提示词，支持 {{cur_datetime}} 等宏变量...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )

                Text("点击快捷插入动态宏变量：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    availableVariables.forEach { (variable, label) ->
                        SuggestionChip(
                            onClick = {
                                val updated = if (textBuffer.isBlank()) variable else "$textBuffer $variable"
                                textBuffer = updated
                                onPromptChange(updated)
                            },
                            label = {
                                Text("$label: $variable", style = MaterialTheme.typography.labelSmall)
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            ),
                        )
                    }
                }
            }
        }
    }
}
