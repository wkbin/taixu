package top.wkbin.taixu.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.model.AiModelProfileExport
import top.wkbin.taixu.core.tools.AgentProviderDefinition
import top.wkbin.taixu.core.tools.ProviderGroup
import top.wkbin.taixu.ui.components.ProviderBadge
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeFilledTonalButton
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconButton
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton
import top.wkbin.taixu.ui.components.RuntimeSlider
import top.wkbin.taixu.ui.components.RuntimeSwitch
import top.wkbin.taixu.ui.components.RuntimeTopBar

/**
 * 模型编辑与连接测试全屏独立页面
 */
@Composable
fun ModelEditorScreen(
    modelId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val discovered by viewModel.discoveredModels.collectAsStateWithLifecycle()
    val discovering by viewModel.discoveringModels.collectAsStateWithLifecycle()
    val discoveryError by viewModel.modelDiscoveryError.collectAsStateWithLifecycle()
    val testing by viewModel.testingConnection.collectAsStateWithLifecycle()
    val testResult by viewModel.connectionResult.collectAsStateWithLifecycle()
    val existing = models.firstOrNull { it.id == modelId }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var initialApiKey by remember(modelId) { mutableStateOf("") }
    LaunchedEffect(modelId, existing?.secretRef) {
        val secretRef = existing?.secretRef
        if (!secretRef.isNullOrBlank()) {
            initialApiKey = viewModel.readModelApiKey(secretRef)
        }
    }

    var showImportJsonDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = if (existing == null) "新增模型档案" else "编辑模型档案",
                onBack = onBack,
                actions = {
                    RuntimeIconButton(
                        onClick = { showImportJsonDialog = true },
                        contentDescription = "从 JSON 填入",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Code,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        ModelEditorContent(
            modifier = Modifier.padding(padding),
            modelId = modelId,
            existing = existing,
            initialApiKey = initialApiKey,
            providers = viewModel.providerCatalog,
            discovered = discovered,
            discovering = discovering,
            discoveryError = discoveryError,
            testing = testing,
            testResult = testResult,
            discover = { provider, url, key -> viewModel.discoverModels(provider, url, key) },
            test = { url, model, key, respApi, providerId -> viewModel.testConnection(url, model, key, respApi, providerId) },
            save = { name, provider, modelsList, url, key, rpmLimit, temperature, maxTokens, topP, reasoningMode, reasoningEffort, toolCallMode, contextTokens, customHeaders, pureChatMode, visionEnabled, imageGenerationEnabled, responseApiEnabled ->
                viewModel.saveModels(
                    id = modelId,
                    models = modelsList,
                    name = name,
                    provider = provider,
                    baseUrl = url,
                    apiKey = key,
                    requestsPerMinutePerKey = rpmLimit,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    topP = topP,
                    reasoningMode = reasoningMode,
                    reasoningEffort = reasoningEffort,
                    toolCallMode = toolCallMode,
                    contextTokens = contextTokens,
                    customHeaders = customHeaders,
                    pureChatMode = pureChatMode,
                    visionEnabled = visionEnabled,
                    imageGenerationEnabled = imageGenerationEnabled,
                    responseApiEnabled = responseApiEnabled,
                )
                onSaved()
            },
            onFillFromJson = { jsonStr ->
                val result = viewModel.parseProfilesFromJson(jsonStr)
                result.fold(
                    onSuccess = { list ->
                        val item = list.firstOrNull()
                        if (item != null) {
                            Toast.makeText(context, "已成功从 JSON 载入配置", Toast.LENGTH_SHORT).show()
                            item
                        } else {
                            Toast.makeText(context, "未找到模型配置", Toast.LENGTH_SHORT).show()
                            null
                        }
                    },
                    onFailure = { err ->
                        Toast.makeText(context, "解析失败: ${err.message}", Toast.LENGTH_SHORT).show()
                        null
                    }
                )
            },
            showImportDialog = showImportJsonDialog,
            onDismissImportDialog = { showImportJsonDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelEditorContent(
    modifier: Modifier = Modifier,
    modelId: String?,
    existing: AiModelEntity?,
    initialApiKey: String = "",
    providers: List<AgentProviderDefinition>,
    discovered: List<String>,
    discovering: Boolean,
    discoveryError: String?,
    testing: Boolean,
    testResult: String?,
    discover: (String, String, String) -> Unit,
    test: (String, String, String, Boolean, String?) -> Unit,
    save: (String, String, List<String>, String, String, Int, Float?, Int?, Float?, String?, String?, String?, Int?, String, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onFillFromJson: (String) -> AiModelProfileExport?,
    showImportDialog: Boolean,
    onDismissImportDialog: () -> Unit,
) {
    val context = LocalContext.current
    // 全部表单字段迁移 rememberSaveable，旋转后输入内容不丢失
    var providerId by rememberSaveable(modelId) {
        mutableStateOf(providers.firstOrNull { it.name == existing?.provider }?.id ?: providers.first().id)
    }
    val provider = providers.firstOrNull { it.id == providerId } ?: providers.first()
    var showProviderPicker by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable(modelId) { mutableStateOf(existing?.name.orEmpty()) }

    val existingModelList = remember(existing?.model) {
        existing?.model?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    }
    // Set 不能直接进 Bundle：用 listSaver 保存为 List 再还原
    val stringSetSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })
    var selectedModels by rememberSaveable(modelId, providerId, stateSaver = stringSetSaver) {
        mutableStateOf(
            if (existing != null) {
                existingModelList
            } else {
                provider.recommendedModels.take(1).toSet()
            }
        )
    }
    var customModelInput by rememberSaveable(modelId, providerId) { mutableStateOf("") }
    var modelSearchQuery by rememberSaveable(modelId, providerId) { mutableStateOf("") }
    var url by rememberSaveable(modelId) { mutableStateOf(existing?.baseUrl ?: provider.baseUrl) }
    // API Key 列表等复杂结构用 listSaver
    val stringListSaver = listSaver<List<String>, String>(save = { it.toList() }, restore = { it })
    var keyList by rememberSaveable(modelId, initialApiKey, stateSaver = stringListSaver) {
        mutableStateOf(
            initialApiKey.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
                .ifEmpty { listOf("") }
        )
    }
    val intSetSaver = listSaver<Set<Int>, Int>(save = { it.toList() }, restore = { it.toSet() })
    var revealedKeyIndices by rememberSaveable(stateSaver = intSetSaver) { mutableStateOf(setOf<Int>()) }
    var showBatchImportKeysDialog by rememberSaveable { mutableStateOf(false) }
    // Base URL 校验：必须以 http/https 开头
    val urlValid = url.trim().startsWith("http://") || url.trim().startsWith("https://")

    LaunchedEffect(initialApiKey) {
        if (keyList.all { it.isBlank() } && initialApiKey.isNotBlank()) {
            keyList = initialApiKey.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
                .ifEmpty { listOf("") }
        }
    }

    val combinedKey = remember(keyList) {
        keyList.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    val candidateModels = remember(discovered, provider) {
        (discovered + provider.recommendedModels).distinct().filter { it.isNotBlank() }
    }
    val filteredCandidateModels = remember(candidateModels, modelSearchQuery) {
        filterCandidateModels(candidateModels, modelSearchQuery)
    }

    // 高级与推理参数
    var rpmLimitText by rememberSaveable(modelId) {
        mutableStateOf(existing?.requestsPerMinutePerKey?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var temperature by rememberSaveable(modelId) { mutableFloatStateOf(existing?.temperature ?: 0.7f) }
    var maxTokensText by rememberSaveable(modelId) { mutableStateOf(existing?.maxTokens?.toString().orEmpty()) }
    var contextTokensText by rememberSaveable(modelId) { mutableStateOf(existing?.contextTokens?.toString().orEmpty()) }
    var topP by rememberSaveable(modelId) { mutableFloatStateOf(existing?.topP ?: 1.0f) }

    var reasoningModeText by rememberSaveable(modelId) { mutableStateOf(existing?.reasoningMode ?: "auto") }
    var reasoningEffortText by rememberSaveable(modelId) { mutableStateOf(existing?.reasoningEffort.orEmpty()) }
    var reasoningModeMenu by rememberSaveable { mutableStateOf(false) }

    // 功能开关
    var toolCallEnabled by rememberSaveable(modelId) {
        mutableStateOf(existing?.toolCallMode != "disabled")
    }
    var pureChatMode by rememberSaveable(modelId) {
        mutableStateOf(existing?.pureChatMode ?: false)
    }
    var visionEnabled by rememberSaveable(modelId) {
        mutableStateOf(existing?.visionEnabled ?: true)
    }
    var imageGenerationEnabled by rememberSaveable(modelId) {
        mutableStateOf(existing?.imageGenerationEnabled ?: false)
    }
    var responseApiEnabled by rememberSaveable(modelId) {
        mutableStateOf(existing?.responseApiEnabled ?: false)
    }

    var customHeaders by rememberSaveable(modelId) { mutableStateOf(existing?.customHeaders.orEmpty()) }

    // 折叠区域控制
    var reasoningSectionExpanded by rememberSaveable { mutableStateOf(false) }
    var advancedSectionExpanded by rememberSaveable { mutableStateOf(false) }

    // 从 JSON 填充表单逻辑
    fun applyProfile(profile: AiModelProfileExport) {
        val matchedProvider = providers.firstOrNull { it.id.equals(profile.provider, ignoreCase = true) || it.name.equals(profile.provider, ignoreCase = true) }
        if (matchedProvider != null) {
            providerId = matchedProvider.id
        }
        if (profile.name.isNotBlank()) name = profile.name
        if (profile.baseUrl.isNotBlank()) url = profile.baseUrl
        val importedKeys = (profile.apiKeys + listOfNotNull(profile.apiKey))
            .flatMap { it.lineSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (importedKeys.isNotEmpty()) {
            keyList = importedKeys
        }
        val importedModels = profile.model.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (importedModels.isNotEmpty()) {
            selectedModels = importedModels.toSet()
        }
        if (profile.requestsPerMinutePerKey > 0) rpmLimitText = profile.requestsPerMinutePerKey.toString()
        profile.temperature?.let { temperature = it }
        profile.maxTokens?.let { maxTokensText = it.toString() }
        profile.contextTokens?.let { contextTokensText = it.toString() }
        profile.topP?.let { topP = it }
        profile.reasoningMode?.let { reasoningModeText = it }
        profile.reasoningEffort?.let { reasoningEffortText = it }
        toolCallEnabled = profile.toolCallMode != "disabled"
        pureChatMode = profile.pureChatMode
        visionEnabled = profile.visionEnabled
        imageGenerationEnabled = profile.imageGenerationEnabled
        responseApiEnabled = profile.responseApiEnabled
        if (profile.customHeaders.isNotBlank()) customHeaders = profile.customHeaders
    }

    if (showBatchImportKeysDialog) {
        BatchImportKeysDialog(
            onDismiss = { showBatchImportKeysDialog = false },
            onConfirm = { batchText ->
                val existingKeys = keyList.map { it.trim() }.filter { it.isNotEmpty() }
                val importedKeys = batchText.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .filter { it !in existingKeys }
                    .toList()
                if (importedKeys.isEmpty()) {
                    Toast.makeText(context, "没有解析到新的密钥", Toast.LENGTH_SHORT).show()
                } else {
                    keyList = (existingKeys + importedKeys).ifEmpty { listOf("") }
                    revealedKeyIndices = emptySet()
                    Toast.makeText(context, "已导入 ${importedKeys.size} 个密钥", Toast.LENGTH_SHORT).show()
                    showBatchImportKeysDialog = false
                }
            },
        )
    }

    if (showImportDialog) {
        QuickImportJsonDialog(
            onDismiss = onDismissImportDialog,
            onConfirm = { jsonText ->
                val profile = onFillFromJson(jsonText)
                if (profile != null) {
                    applyProfile(profile)
                    onDismissImportDialog()
                }
            },
        )
    }

    if (showProviderPicker) {
        ProviderPickerDialog(
            providers = providers,
            currentProviderId = providerId,
            onDismiss = { showProviderPicker = false },
            onSelect = { option ->
                providerId = option.id
                url = option.baseUrl
                if (existing == null) {
                    selectedModels = option.recommendedModels.take(1).toSet()
                    customModelInput = ""
                }
                showProviderPicker = false
            },
        )
    }

    val compactFieldShape = RoundedCornerShape(12.dp)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- 1. 服务商选择与 Hero 卡片 ----
        item {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                onClick = { showProviderPicker = true },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ProviderBadge(providerIdOrName = provider.id, size = 26.dp)
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = provider.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            ) {
                                Text(
                                    text = when (provider.group) {
                                        ProviderGroup.OFFICIAL -> "官方"
                                        ProviderGroup.CHINA -> "国内"
                                        ProviderGroup.AGGREGATOR -> "聚合"
                                        ProviderGroup.LOCAL -> "本地"
                                        ProviderGroup.CUSTOM -> "自定义"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                        Text(
                            text = if (provider.group == ProviderGroup.LOCAL) "本地沙箱轻量模型端点" else "OpenAI / Claude 兼容 API",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    RuntimeFilledTonalButton(
                        onClick = { showProviderPicker = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("切换预设", style = MaterialTheme.typography.labelMedium)
                            RuntimeIcon(RuntimeIconName.ChevronDown, Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        // ---- 2. 基础连接卡片 (Connection Info) ----
        item {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Globe, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                        Text(
                            text = "接口与基础连接",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    // 档案名称
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("档案名称（可选，留空按模型命名）") },
                        placeholder = { Text(if (selectedModels.isNotEmpty()) selectedModels.first() else provider.name) },
                        singleLine = true,
                        shape = compactFieldShape,
                        colors = fieldColors,
                        leadingIcon = {
                            RuntimeIcon(RuntimeIconName.Model, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                    )

                    // Base URL
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL（接口端点）") },
                        placeholder = {
                            Text(
                                if (provider.protocol == top.wkbin.taixu.core.tools.ProviderProtocol.ANTHROPIC) {
                                    "https://api.anthropic.com/v1 或中转站地址"
                                } else {
                                    "https://api.openai.com/v1"
                                }
                            )
                        },
                        isError = url.isNotBlank() && !urlValid,
                        supportingText = {
                            if (url.isNotBlank() && !urlValid) {
                                Text("接口地址需以 http:// 或 https:// 开头")
                            }
                        },
                        singleLine = true,
                        shape = compactFieldShape,
                        colors = fieldColors,
                        leadingIcon = {
                            RuntimeIcon(RuntimeIconName.Globe, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        trailingIcon = {
                            RuntimeIconButton(
                                onClick = { discover(providerId, url, combinedKey) },
                                enabled = !discovering && url.isNotBlank(),
                            ) {
                                if (discovering) {
                                    RuntimeCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    RuntimeIcon(
                                        name = RuntimeIconName.Refresh,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (url.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }

        // ---- 3. API 凭证与密钥池 ----
        item {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Key, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                            Text(
                                text = "API 密钥池",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        ) {
                            Text(
                                text = "${keyList.count { it.isNotBlank() }} 个已配置",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }

                    // Key 列表
                    keyList.forEachIndexed { index, currentKey ->
                        val isRevealed = revealedKeyIndices.contains(index)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = currentKey,
                                onValueChange = { newVal ->
                                    keyList = keyList.toMutableList().also { it[index] = newVal }
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text("Key ${if (keyList.size > 1) "#${index + 1}" else ""}") },
                                placeholder = { Text("sk-...") },
                                singleLine = true,
                                shape = compactFieldShape,
                                colors = fieldColors,
                                visualTransformation = if (isRevealed) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    if (currentKey.isNotEmpty()) {
                                        RuntimeIconButton(
                                            onClick = {
                                                revealedKeyIndices = if (isRevealed) revealedKeyIndices - index else revealedKeyIndices + index
                                            },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .size(28.dp),
                                            contentDescription = if (isRevealed) "隐藏密钥" else "显示密钥",
                                        ) {
                                            RuntimeIcon(
                                                name = if (isRevealed) RuntimeIconName.VisibilityOff else RuntimeIconName.Visibility,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                            )

                            if (keyList.size > 1) {
                                RuntimeIconButton(
                                    onClick = {
                                        keyList = keyList.toMutableList().also { it.removeAt(index) }
                                        revealedKeyIndices = revealedKeyIndices.mapNotNull {
                                            when {
                                                it < index -> it
                                                it > index -> it - 1
                                                else -> null
                                            }
                                        }.toSet()
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .size(36.dp),
                                    contentDescription = "移除该密钥",
                                ) {
                                    RuntimeIcon(
                                        name = RuntimeIconName.Close,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }

                    // 添加备用 Key / 批量导入
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RuntimeOutlinedButton(
                            onClick = { keyList = keyList + "" },
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RuntimeIcon(RuntimeIconName.Plus, Modifier.size(14.dp), MaterialTheme.colorScheme.primary)
                                Text("+ 备用 Key", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        RuntimeOutlinedButton(
                            onClick = { showBatchImportKeysDialog = true },
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RuntimeIcon(RuntimeIconName.Download, Modifier.size(14.dp), MaterialTheme.colorScheme.primary)
                                Text("批量导入", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    Text(
                        text = "密钥将加密保存在本地 Android Keystore 中，明文不落库。",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- 4. 模型选择与定制 ----
        item {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val totalSelectedCount = (selectedModels + customModelInput.split(",").map { it.trim() }).filter { it.isNotBlank() }.distinct().size
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Brain, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                            Text(
                                text = "模型选择与定制",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        if (totalSelectedCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            ) {
                                Text(
                                    text = "已选 $totalSelectedCount 个模型",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    if (candidateModels.isNotEmpty()) {
                        val candidateScrollState = rememberScrollState()
                        val candidateCount = candidateModels.size
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "候选模型（点击勾选，支持多选）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (candidateCount > 20) {
                                Text(
                                    text = "共 $candidateCount 个",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        OutlinedTextField(
                            value = modelSearchQuery,
                            onValueChange = { modelSearchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索模型名称或 ID") },
                            singleLine = true,
                            shape = compactFieldShape,
                            colors = fieldColors,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            leadingIcon = {
                                RuntimeIcon(
                                    name = RuntimeIconName.Search,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingIcon = {
                                if (modelSearchQuery.isNotEmpty()) {
                                    RuntimeIconButton(
                                        onClick = { modelSearchQuery = "" },
                                        contentDescription = "清空模型搜索",
                                    ) {
                                        RuntimeIcon(
                                            name = RuntimeIconName.Close,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            supportingText = {
                                if (modelSearchQuery.isNotBlank()) {
                                    Text("找到 ${filteredCandidateModels.size} / $candidateCount 个模型")
                                }
                            },
                        )

                        // 流式布局自动横向换行；限制最大高度，模型过多时区域内部滚动，避免撑爆单 item 导致底部黑屏
                        if (filteredCandidateModels.isEmpty()) {
                            Text(
                                text = "没有匹配的模型，可清空搜索或在下方手动填写模型 ID",
                                modifier = Modifier.padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(candidateScrollState),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                filteredCandidateModels.forEach { option ->
                                    val isSelected = selectedModels.contains(option)
                                    Surface(
                                        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        ),
                                        modifier = Modifier.clickable {
                                            selectedModels = if (isSelected) selectedModels - option else selectedModels + option
                                        },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            if (isSelected) {
                                                RuntimeIcon(
                                                    name = RuntimeIconName.Check,
                                                    modifier = Modifier.size(13.dp),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                            Text(
                                                text = option,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                    fontSize = 12.sp,
                                                ),
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 自定义模型输入
                    OutlinedTextField(
                        value = customModelInput,
                        onValueChange = { customModelInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("自定义模型 ID（可选，逗号分隔额外模型）") },
                        placeholder = { Text("例如：gpt-4.5-preview, claude-3-7-sonnet") },
                        singleLine = true,
                        shape = compactFieldShape,
                        colors = fieldColors,
                    )
                }
            }
        }

        // ---- 5. 推理与上下文调优 (Collapsible) ----
        item {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                onClick = { reasoningSectionExpanded = !reasoningSectionExpanded },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Speed, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "推理与上下文调优",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            )
                            Text(
                                "Temperature、Max Tokens、上下文容量与思考模式",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        RuntimeIcon(
                            name = RuntimeIconName.ChevronDown,
                            modifier = Modifier.size(18.dp).rotate(if (reasoningSectionExpanded) 180f else 0f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    AnimatedVisibility(visible = reasoningSectionExpanded) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            // Temperature 滑块
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Temperature (随机性)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)) {
                                        Text(
                                            text = String.format("%.2f", temperature),
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                RuntimeSlider(
                                    value = temperature,
                                    onValueChange = { temperature = it },
                                    valueRange = 0f..2.0f,
                                    steps = 20,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PresetChip("0.2 精准", temperature == 0.2f) { temperature = 0.2f }
                                    PresetChip("0.7 平衡", temperature == 0.7f) { temperature = 0.7f }
                                    PresetChip("1.2 创意", temperature == 1.2f) { temperature = 1.2f }
                                }
                            }

                            // Max Tokens 与 Context Tokens 双列并排
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                OutlinedTextField(
                                    value = maxTokensText,
                                    onValueChange = { maxTokensText = it.filter(Char::isDigit) },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Max Tokens") },
                                    placeholder = { Text("8000") },
                                    singleLine = true,
                                    shape = compactFieldShape,
                                    colors = fieldColors,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                                OutlinedTextField(
                                    value = contextTokensText,
                                    onValueChange = { contextTokensText = it.filter(Char::isDigit) },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("上下文上限") },
                                    placeholder = { Text("128000") },
                                    singleLine = true,
                                    shape = compactFieldShape,
                                    colors = fieldColors,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                            }

                            // Top P 滑块
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Top P (核采样)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)) {
                                        Text(
                                            text = String.format("%.2f", topP),
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                RuntimeSlider(
                                    value = topP,
                                    onValueChange = { topP = it },
                                    valueRange = 0f..1.0f,
                                    steps = 10,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            // 推理思考模式
                            ExposedDropdownMenuBox(
                                expanded = reasoningModeMenu,
                                onExpandedChange = { reasoningModeMenu = !reasoningModeMenu },
                            ) {
                                OutlinedTextField(
                                    value = when (reasoningModeText) {
                                        "enabled" -> "开启深度推理（模型深入思考）"
                                        else -> "跟随模型默认"
                                    },
                                    onValueChange = {},
                                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                    readOnly = true,
                                    label = { Text("推理思考模式") },
                                    shape = compactFieldShape,
                                    colors = fieldColors,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(reasoningModeMenu) },
                                )
                                ExposedDropdownMenu(
                                    expanded = reasoningModeMenu,
                                    onDismissRequest = { reasoningModeMenu = false },
                                ) {
                                    DropdownMenuItem(text = { Text("跟随模型默认") }, onClick = {
                                        reasoningModeText = "auto"
                                        reasoningModeMenu = false
                                    })
                                    DropdownMenuItem(text = { Text("开启深度推理（模型深入思考）") }, onClick = {
                                        reasoningModeText = "enabled"
                                        reasoningModeMenu = false
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- 6. 高级特性与网络配置 (Collapsible) ----
        item {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                onClick = { advancedSectionExpanded = !advancedSectionExpanded },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Shield, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "高级特性与网络配置",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            )
                            Text(
                                "Tool Call、识图、Responses API、RPM 限速与请求头",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        RuntimeIcon(
                            name = RuntimeIconName.ChevronDown,
                            modifier = Modifier.size(18.dp).rotate(if (advancedSectionExpanded) 180f else 0f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    AnimatedVisibility(visible = advancedSectionExpanded) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            // 单 Key 每分钟上限
                            OutlinedTextField(
                                value = rpmLimitText,
                                onValueChange = { rpmLimitText = it.filter(Char::isDigit) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("单 Key 每分钟请求上限 (RPM)") },
                                placeholder = { Text("0 表示不限制") },
                                singleLine = true,
                                shape = compactFieldShape,
                                colors = fieldColors,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )

                            // 特性开关列表
                            EditorToggleRow(
                                title = "支持函数调用 (Tool Call)",
                                subtitle = "支持沙箱命令与工具调用闭环",
                                checked = toolCallEnabled,
                                onCheckedChange = { toolCallEnabled = it },
                            )

                            EditorToggleRow(
                                title = "支持多模态识图 (Vision)",
                                subtitle = "向模型直接发送图片",
                                checked = visionEnabled,
                                onCheckedChange = { visionEnabled = it },
                            )

                            EditorToggleRow(
                                title = "支持图片生成 (Image Generation)",
                                subtitle = "模型响应可直接返回生成图片；仅确认支持时开启",
                                checked = imageGenerationEnabled,
                                onCheckedChange = { imageGenerationEnabled = it },
                            )

                            EditorToggleRow(
                                title = "使用 Responses API",
                                subtitle = "使用 /v1/responses 替代 completions",
                                checked = responseApiEnabled,
                                onCheckedChange = { responseApiEnabled = it },
                            )

                            EditorToggleRow(
                                title = "纯净排查模式",
                                subtitle = "不注入系统提示词与工具定义",
                                checked = pureChatMode,
                                onCheckedChange = { pureChatMode = it },
                            )

                            // 自定义请求头
                            OutlinedTextField(
                                value = customHeaders,
                                onValueChange = { customHeaders = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("自定义 HTTP 请求头（多行 Key: Value）") },
                                placeholder = { Text("X-Custom-Header: value\nHTTP-Referer: https://taixu.app") },
                                minLines = 2,
                                maxLines = 4,
                                shape = compactFieldShape,
                                colors = fieldColors,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }

        // ---- 7. 连通性测试与错误提示 ----
        item {
            val testModelTarget = selectedModels.firstOrNull() ?: customModelInput.trim()

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeOutlinedButton(
                    onClick = { test(url, testModelTarget, combinedKey, responseApiEnabled, providerId) },
                    enabled = !testing && url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (testing) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RuntimeCircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("正在测试…")
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RuntimeIcon(RuntimeIconName.Play, Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                            Text("测试连接")
                        }
                    }
                }

                discoveryError?.let {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Alert, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                testResult?.let { resultMsg ->
                    val isSuccess = resultMsg == "连接成功"
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSuccess) successStatusColor().copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RuntimeIcon(
                                name = if (isSuccess) RuntimeIconName.Check else RuntimeIconName.Alert,
                                modifier = Modifier.size(16.dp),
                                tint = if (isSuccess) successStatusColor() else MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = resultMsg,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (isSuccess) successStatusColor() else MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // ---- 8. 底部保存按钮 ----
        item {
            val effectiveModels = remember(selectedModels, customModelInput) {
                val customList = customModelInput.split(",").map { it.trim() }.filter { it.isNotBlank() }
                (selectedModels + customList).filter { it.isNotBlank() }.distinct()
            }
            val parsedMaxTokens = maxTokensText.trim().toIntOrNull()
            val parsedContextTokens = contextTokensText.trim().toIntOrNull()
            val parsedRpmLimit = rpmLimitText.trim().toIntOrNull() ?: 0

            val buttonText = if (effectiveModels.size > 1) {
                "保存已勾选的 ${effectiveModels.size} 个模型"
            } else {
                "保存模型档案配置"
            }

            RuntimeButton(
                onClick = {
                    save(
                        name.trim(),
                        provider.name,
                        effectiveModels,
                        url.trim(),
                        combinedKey,
                        parsedRpmLimit,
                        temperature,
                        parsedMaxTokens,
                        topP,
                        reasoningModeText.takeIf { it != "auto" },
                        reasoningEffortText.ifBlank { null },
                        if (toolCallEnabled) "native" else "disabled",
                        parsedContextTokens,
                        customHeaders,
                        pureChatMode,
                        visionEnabled,
                        imageGenerationEnabled,
                        responseApiEnabled,
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = effectiveModels.isNotEmpty() && urlValid,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeIcon(RuntimeIconName.Check, Modifier.size(18.dp), MaterialTheme.colorScheme.onPrimary)
                    Text(buttonText, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

internal fun filterCandidateModels(models: List<String>, query: String): List<String> {
    val normalizedQuery = query.trim()
    return if (normalizedQuery.isEmpty()) {
        models
    } else {
        models.filter { it.contains(normalizedQuery, ignoreCase = true) }
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun EditorToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RuntimeSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun BatchImportKeysDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val parsedCount = remember(text) {
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().count()
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Key, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                Text("批量导入密钥", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "粘贴多个 API 密钥，一行一个，遇到换行即为下一个 Key：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    placeholder = { Text("sk-xxxxxxxxxxxxxxxx\nsk-yyyyyyyyyyyyyyyy\nsk-zzzzzzzzzzzzzzzz") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(10.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RuntimeOutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                            if (clipText.isNotBlank()) {
                                text = clipText
                            } else {
                                Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("从剪贴板粘贴", style = MaterialTheme.typography.labelMedium)
                    }
                    if (parsedCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        ) {
                            Text(
                                text = "已识别 $parsedCount 个",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            RuntimeButton(
                onClick = { onConfirm(text) },
                enabled = parsedCount > 0,
            ) {
                Text("导入")
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
private fun QuickImportJsonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Code, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                Text("从 JSON 填入模型配置", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "粘贴太墟导出的模型配置或 OpenAI 兼容 JSON，将自动解析并填入当前表单：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    placeholder = { Text("{\n  \"name\": \"DeepSeek-V4\",\n  \"provider\": \"DeepSeek\",\n  \"baseUrl\": \"https://api.deepseek.com/v1\",\n  \"model\": \"deepseek-v4-flash\"\n}") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(10.dp),
                )
                RuntimeOutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        if (clipText.isNotBlank()) {
                            text = clipText
                        } else {
                            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("从剪贴板粘贴")
                }
            }
        },
        confirmButton = {
            RuntimeButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text("解析并填入")
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
private fun ProviderPickerDialog(
    providers: List<AgentProviderDefinition>,
    currentProviderId: String,
    onDismiss: () -> Unit,
    onSelect: (AgentProviderDefinition) -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedGroup by rememberSaveable { mutableStateOf<ProviderGroup?>(null) }

    val filteredProviders = remember(providers, searchQuery, selectedGroup) {
        providers.filter { item ->
            val matchesGroup = selectedGroup == null || item.group == selectedGroup
            val matchesSearch = searchQuery.isBlank() ||
                item.name.contains(searchQuery, ignoreCase = true) ||
                item.id.contains(searchQuery, ignoreCase = true) ||
                item.baseUrl.contains(searchQuery, ignoreCase = true)
            matchesGroup && matchesSearch
        }
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Sparkles, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                Text("选择服务商预设", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 搜索框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索服务商名称或端点...", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = {
                        RuntimeIcon(RuntimeIconName.Search, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            RuntimeIconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(28.dp),
                            ) {
                                RuntimeIcon(RuntimeIconName.Close, Modifier.size(14.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )

                // 分类 Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProviderFilterChip(
                        label = "全部",
                        selected = selectedGroup == null,
                        onClick = { selectedGroup = null },
                    )
                    ProviderFilterChip(
                        label = "官方",
                        selected = selectedGroup == ProviderGroup.OFFICIAL,
                        onClick = { selectedGroup = ProviderGroup.OFFICIAL },
                    )
                    ProviderFilterChip(
                        label = "国内",
                        selected = selectedGroup == ProviderGroup.CHINA,
                        onClick = { selectedGroup = ProviderGroup.CHINA },
                    )
                    ProviderFilterChip(
                        label = "聚合",
                        selected = selectedGroup == ProviderGroup.AGGREGATOR,
                        onClick = { selectedGroup = ProviderGroup.AGGREGATOR },
                    )
                    ProviderFilterChip(
                        label = "本地",
                        selected = selectedGroup == ProviderGroup.LOCAL,
                        onClick = { selectedGroup = ProviderGroup.LOCAL },
                    )
                    ProviderFilterChip(
                        label = "自定义",
                        selected = selectedGroup == ProviderGroup.CUSTOM,
                        onClick = { selectedGroup = ProviderGroup.CUSTOM },
                    )
                }

                // 列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (filteredProviders.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "未找到匹配的服务商",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(filteredProviders, key = { it.id }) { option ->
                            val isSelected = option.id == currentProviderId
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
                                },
                                border = if (isSelected) {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                } else {
                                    null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelect(option)
                                    },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        ProviderBadge(providerIdOrName = option.id, size = 22.dp)
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = option.name,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                                            ) {
                                                Text(
                                                    text = when (option.group) {
                                                        ProviderGroup.OFFICIAL -> "官方"
                                                        ProviderGroup.CHINA -> "国内"
                                                        ProviderGroup.AGGREGATOR -> "聚合"
                                                        ProviderGroup.LOCAL -> "本地"
                                                        ProviderGroup.CUSTOM -> "自定义"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            text = option.baseUrl.ifBlank { "需自定义 Base URL" },
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    if (isSelected) {
                                        RuntimeIcon(
                                            name = RuntimeIconName.Check,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun ProviderFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 11.sp,
            ),
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
