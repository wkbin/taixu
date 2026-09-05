package top.wkbin.taixu.ui.settings

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.taixu.core.datastore.SettingsDataStore
import top.wkbin.taixu.core.datastore.BrowserPreferences
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.database.McpServerRepository
import top.wkbin.taixu.core.database.StorageMountBindingRepository
import top.wkbin.taixu.core.tools.ProviderRepository
import top.wkbin.taixu.core.tools.ToolManager
import top.wkbin.taixu.core.tools.AiProfileWriter
import top.wkbin.taixu.core.tools.AiProfileBackupCodec
import top.wkbin.taixu.core.tools.AgentModelDiscovery
import top.wkbin.taixu.core.tools.AgentProviderCatalog
import top.wkbin.taixu.core.tools.AgentModelConnectionTester
import top.wkbin.taixu.core.tools.ProviderEndpointPolicy
import top.wkbin.taixu.core.model.ExecutionMode
import top.wkbin.taixu.core.model.McpConnectionState
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.runtime.LinuxEnvironmentManager
import top.wkbin.taixu.runtime.RuntimePathManager
import top.wkbin.taixu.runtime.proot.QemuCompatibilityLayout
import top.wkbin.taixu.runtime.privilege.PhantomProcessLimitStatus
import top.wkbin.taixu.runtime.privilege.PrivilegeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import java.io.BufferedOutputStream
import top.wkbin.taixu.core.model.AiModelProfileExport


@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val application: Application,
    private val logger: top.wkbin.taixu.core.common.logging.AppLogger,
    private val settingsDataStore: SettingsDataStore,
    private val providerRepository: ProviderRepository,
    private val aiModelDao: AiModelRepository,
    private val modelDiscovery: AgentModelDiscovery,
    private val providerCatalogRepository: AgentProviderCatalog,
    private val connectionTester: AgentModelConnectionTester,
    private val privilegeManager: PrivilegeManager,
    private val mcpManager: top.wkbin.taixu.harness.mcp.McpManager,
    private val linuxRuntime: top.wkbin.taixu.runtime.LinuxRuntime,
    private val pathManager: RuntimePathManager,
    private val linuxEnvironmentManager: LinuxEnvironmentManager,
    private val appUpdateManager: top.wkbin.taixu.core.network.AppUpdateManager,
    private val subagentRepository: top.wkbin.taixu.core.database.AgentSubagentRepository,
    private val agentSkillRepository: AgentSkillRepository,
    private val mcpServerRepository: McpServerRepository,
    private val storageMountBindingRepository: StorageMountBindingRepository,
    private val approvalRepository: top.wkbin.taixu.core.database.AgentApprovalRepository,
    private val sessionDao: top.wkbin.taixu.core.database.HarnessSessionRepository,
    private val toolManager: ToolManager,
    private val quickPhraseRepository: top.wkbin.taixu.core.database.QuickPhraseRepository,
    private val profileWriter: AiProfileWriter,
    private val profileBackupCodec: AiProfileBackupCodec,
    private val webChatBridgeServer: top.wkbin.taixu.runtime.webchat.WebChatBridgeServer? = null,
    private val browserPrefs: BrowserPreferences,
) : ViewModel() {
    val installedDistros = linuxRuntime.installedDistros
    val activeDistroId = linuxRuntime.activeDistroId
    val runtimeState = linuxRuntime.state

    val environmentVariables = linuxEnvironmentManager.variables
    val environmentValues = linuxEnvironmentManager.values
    val effectiveEnvironment = linuxEnvironmentManager.effectiveEnvironment

    private val _environmentLoading = MutableStateFlow(false)
    val environmentLoading: StateFlow<Boolean> = _environmentLoading.asStateFlow()

    private val _environmentError = MutableStateFlow<String?>(null)
    val environmentError: StateFlow<String?> = _environmentError.asStateFlow()

    init {
        viewModelScope.launch {
            subagentRepository.ensureInitialized()
            agentSkillRepository.ensureInitialized()
            mcpServerRepository.ensureInitialized()
            quickPhraseRepository.ensureInitialized()
        }
        viewModelScope.launch {
            combine(linuxRuntime.state, linuxRuntime.activeDistroId) { state, distroId ->
                (state is RuntimeState.Ready) to distroId
            }
                .distinctUntilChanged()
                .collectLatest { (ready, distroId) ->
                    if (ready) refreshEnvironmentVariables(distroId)
                }
        }
        viewModelScope.launch {
            combine(
                linuxRuntime.activeDistroId,
                toolManager.installProgress,
                toolManager.localPluginImportState,
            ) { distroId, _, _ ->
                QemuCompatibilityLayout.isReady(pathManager.taixuRootDir(distroId))
            }
                .flowOn(Dispatchers.IO)
                .distinctUntilChanged()
                .collectLatest { ready ->
                    _qemuCompatibilityReady.value = ready
                    if (ready) _qemuCompatibilityMessage.value = null
                }
        }
    }

    val environmentPrivacyMode: StateFlow<Boolean> = settingsDataStore.environmentPrivacyMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setEnvironmentPrivacyMode(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setEnvironmentPrivacyMode(enabled) }
    }

    fun addEnvironmentVariable(key: String, value: String, note: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _environmentLoading.value = true
            val result = linuxEnvironmentManager.add(key, value, note)
            finishEnvironmentOperation(result, onResult)
        }
    }

    fun updateEnvironmentVariable(id: String, key: String, value: String?, note: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _environmentLoading.value = true
            val result = linuxEnvironmentManager.update(id, key, value, note)
            finishEnvironmentOperation(result, onResult)
        }
    }

    fun deleteEnvironmentVariable(id: String) {
        viewModelScope.launch {
            _environmentLoading.value = true
            finishEnvironmentOperation(linuxEnvironmentManager.delete(id))
        }
    }

    fun refreshEnvironmentVariables(distroId: String = linuxRuntime.activeDistroId.value) {
        viewModelScope.launch {
            _environmentLoading.value = true
            finishEnvironmentOperation(linuxEnvironmentManager.refresh(distroId))
        }
    }

    fun clearEnvironmentError() {
        _environmentError.value = null
    }

    private fun finishEnvironmentOperation(result: Result<Unit>, onResult: (Boolean) -> Unit = {}) {
        _environmentLoading.value = false
        _environmentError.value = result.exceptionOrNull()?.message
        onResult(result.isSuccess)
    }

    // ---- 终端外观与显示定制 ----
    val terminalFontSize: StateFlow<Int> = settingsDataStore.terminalFontSize
        .stateIn(viewModelScope, SharingStarted.Eagerly, 13)

    val terminalColorScheme: StateFlow<String> = settingsDataStore.terminalColorScheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "obsidian")

    val terminalHapticsEnabled: StateFlow<Boolean> = settingsDataStore.terminalHapticsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val appFontScale: StateFlow<Float> = settingsDataStore.appFontScale
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    val chengmingBackgroundUri: StateFlow<String?> = settingsDataStore.chengmingBackgroundUri
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setTerminalFontSize(sizeSp: Int) {
        viewModelScope.launch { settingsDataStore.setTerminalFontSize(sizeSp) }
    }

    fun setTerminalColorScheme(scheme: String) {
        viewModelScope.launch { settingsDataStore.setTerminalColorScheme(scheme) }
    }

    fun setTerminalHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setTerminalHapticsEnabled(enabled) }
    }

    fun setAppFontScale(scale: Float) {
        viewModelScope.launch { settingsDataStore.setAppFontScale(scale) }
    }

    fun setChengmingBackgroundUri(uri: String?) {
        viewModelScope.launch { settingsDataStore.setChengmingBackgroundUri(uri) }
    }

    // ---- 应用版本更新机制 ----
    val autoCheckUpdates: StateFlow<Boolean> = settingsDataStore.autoCheckUpdates
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val webChatStatus: StateFlow<top.wkbin.taixu.runtime.webchat.WebChatServerStatus> =
        webChatBridgeServer?.status ?: MutableStateFlow(top.wkbin.taixu.runtime.webchat.WebChatServerStatus()).asStateFlow()

    fun toggleWebChatServer(enabled: Boolean, port: Int = 8899) {
        if (enabled) {
            webChatBridgeServer?.start(port)
        } else {
            webChatBridgeServer?.stop()
        }
    }

    private val _updateCheckState = MutableStateFlow<top.wkbin.taixu.core.model.UpdateCheckState>(top.wkbin.taixu.core.model.UpdateCheckState.Idle)
    val updateCheckState: StateFlow<top.wkbin.taixu.core.model.UpdateCheckState> = _updateCheckState.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setAutoCheckUpdates(enabled) }
    }

    /** 清空全部首次使用引导标记，下次进入相应页面会重新展示引导遮罩。 */
    fun replayFirstUseGuides() {
        viewModelScope.launch { settingsDataStore.clearFirstUseGuides() }
    }

    fun checkForUpdates(currentVersion: String) {
        viewModelScope.launch {
            _updateCheckState.value = top.wkbin.taixu.core.model.UpdateCheckState.Checking
            val res = appUpdateManager.checkUpdate(currentVersion)
            res.onSuccess { info ->
                _updateCheckState.value = top.wkbin.taixu.core.model.UpdateCheckState.Success(info)
            }.onFailure { err ->
                _updateCheckState.value = top.wkbin.taixu.core.model.UpdateCheckState.Error(err.message ?: "检查更新失败，请检查网络")
            }
        }
    }

    fun downloadAndInstall(apkUrl: String) {
        viewModelScope.launch {
            _isDownloading.value = true
            _downloadProgress.value = 0f
            val res = appUpdateManager.downloadApk(apkUrl) { downloaded, total ->
                if (total != null && total > 0) {
                    _downloadProgress.value = downloaded.toFloat() / total.toFloat()
                } else {
                    _downloadProgress.value = null
                }
            }
            _isDownloading.value = false
            res.onSuccess { apkFile ->
                _downloadProgress.value = 1f
                appUpdateManager.installApk(apkFile)
            }.onFailure { err ->
                _downloadProgress.value = null
                _updateCheckState.value = top.wkbin.taixu.core.model.UpdateCheckState.Error("下载更新包失败：${err.message}")
            }
        }
    }

    fun clearUpdateState() {
        _updateCheckState.value = top.wkbin.taixu.core.model.UpdateCheckState.Idle
        _downloadProgress.value = null
        _isDownloading.value = false
    }

    fun switchActiveDistro(distroId: String) {
        viewModelScope.launch {
            linuxRuntime.switchActiveDistro(distroId)
        }
    }

    /** 发行版安装进度（保存在 VM 内，旋转后进度界面可恢复；安装在后台继续进行）。 */
    data class DistroInstallUiState(
        val isInstalling: Boolean = false,
        val progressText: String? = null,
        val progressFraction: Float = 0f,
        val errorMessage: String? = null,
    )

    private val _distroInstallState = MutableStateFlow(DistroInstallUiState())
    val distroInstallState: StateFlow<DistroInstallUiState> = _distroInstallState.asStateFlow()

    fun clearDistroInstallError() {
        _distroInstallState.value = _distroInstallState.value.copy(errorMessage = null)
    }

    fun installDistro(request: top.wkbin.taixu.runtime.RuntimeInstallRequest) {
        if (_distroInstallState.value.isInstalling) return
        viewModelScope.launch {
            _distroInstallState.value = DistroInstallUiState(
                isInstalling = true,
                progressText = "准备拉取镜像...",
                progressFraction = 0.05f,
            )
            val res = linuxRuntime.installDistro(request) { p ->
                _distroInstallState.value = _distroInstallState.value.copy(
                    progressText = if (p.totalMegabytes != null) {
                        "下载中：${p.downloadedMegabytes} / ${p.totalMegabytes} MB"
                    } else {
                        "已下载：${p.downloadedMegabytes} MB"
                    },
                    progressFraction = (p.fraction ?: 0f) * 0.8f + 0.1f,
                )
            }
            if (res is top.wkbin.taixu.core.common.result.AppResult.Success) {
                // 安装成功：重置进度状态，UI 据此关闭安装弹窗
                _distroInstallState.value = DistroInstallUiState()
            } else {
                res.errorOrNull()?.let { logger.e("Distro install failed: ${it.message}", it.cause) }
                _distroInstallState.value = _distroInstallState.value.copy(
                    isInstalling = false,
                    errorMessage = "安装失败，请检查网络与存储空间后重试",
                )
            }
        }
    }

    private val _restoringDistroId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val restoringDistroId: StateFlow<String?> = _restoringDistroId.asStateFlow()

    fun resetDistro(distroId: String, onResult: ((Boolean, String) -> Unit)? = null) {
        if (_restoringDistroId.value != null) return
        _restoringDistroId.value = distroId
        viewModelScope.launch {
            val res = linuxRuntime.resetSandbox(distroId)
            if (res is top.wkbin.taixu.core.common.result.AppResult.Success) {
                toolManager.resetDistroState(distroId)
            }
            _restoringDistroId.value = null
            if (res is top.wkbin.taixu.core.common.result.AppResult.Success) {
                onResult?.invoke(true, "已恢复初始状态")
            } else {
                res.errorOrNull()?.let { logger.e("Distro reset failed: ${it.message}", it.cause) }
                onResult?.invoke(false, "重置沙箱失败，请稍后重试")
            }
        }
    }

    private val _deletingDistroId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val deletingDistroId: StateFlow<String?> = _deletingDistroId.asStateFlow()

    fun uninstallDistro(distroId: String, onResult: ((Boolean, String) -> Unit)? = null) {
        if (_deletingDistroId.value != null) return
        _deletingDistroId.value = distroId
        viewModelScope.launch {
            val res = linuxRuntime.uninstallDistro(distroId)
            _deletingDistroId.value = null
            if (res is top.wkbin.taixu.core.common.result.AppResult.Success) {
                onResult?.invoke(true, "系统已成功删除")
            } else {
                res.errorOrNull()?.let { logger.e("Distro uninstall failed: ${it.message}", it.cause) }
                onResult?.invoke(false, "删除系统失败，请稍后重试")
            }
        }
    }

    val mcpServers: StateFlow<List<top.wkbin.taixu.core.model.McpServerConfig>> = mcpServerRepository.servers
        .stateIn(viewModelScope, SharingStarted.Eagerly, top.wkbin.taixu.core.model.BuiltinMcpPresets.presets)

    /** 各 MCP 服务的实时连通性状态（与 McpManager 共享，设置页与聊天页联动）。 */
    val mcpConnectionStates: StateFlow<Map<String, McpConnectionState>> = mcpManager.connectionStates

    /** 手动/自动触发一次全量 MCP 连通性探测。 */
    fun refreshMcpConnections() {
        viewModelScope.launch { mcpManager.refreshConnections() }
    }

    fun toggleMcpServer(serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            mcpServerRepository.setEnabled(serverId, enabled)
            mcpManager.refreshConnections()
        }
    }

    /** 浏览器 MCP 安全门禁快照（allowRemoteConnect / allowEvalJs / allowHooks / allowCdp）。 */
    val browserGates: StateFlow<BrowserGateState> = combine(
        browserPrefs.allowRemoteConnect(),
        browserPrefs.allowEvalJs(),
        browserPrefs.allowHooks(),
        browserPrefs.allowCdp(),
    ) { remote, evalJs, hooks, cdp ->
        BrowserGateState(remote, evalJs, hooks, cdp)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BrowserGateState())

    fun setBrowserAllowRemoteConnect(enabled: Boolean) {
        viewModelScope.launch { browserPrefs.setAllowRemoteConnect(enabled) }
    }

    fun setBrowserAllowEvalJs(enabled: Boolean) {
        viewModelScope.launch { browserPrefs.setAllowEvalJs(enabled) }
    }

    fun setBrowserAllowHooks(enabled: Boolean) {
        viewModelScope.launch { browserPrefs.setAllowHooks(enabled) }
    }

    fun setBrowserAllowCdp(enabled: Boolean) {
        viewModelScope.launch { browserPrefs.setAllowCdp(enabled) }
    }

    fun saveMcpServer(server: top.wkbin.taixu.core.model.McpServerConfig) {
        viewModelScope.launch {
            mcpServerRepository.save(server)
            mcpManager.refreshConnections()
        }
    }

    fun deleteMcpServer(serverId: String) {
        viewModelScope.launch {
            mcpServerRepository.delete(serverId)
            mcpManager.refreshConnections()
        }
    }

    suspend fun testMcpServer(server: top.wkbin.taixu.core.model.McpServerConfig): Result<List<top.wkbin.taixu.core.model.McpToolInfo>> {
        return mcpManager.testServer(server)
    }

    private val _localMcpDiscovery = MutableStateFlow<LocalMcpDiscoveryState>(LocalMcpDiscoveryState.Idle)
    /** 127.0.0.1 上已完成 MCP 握手的服务；仅用于设置页展示，不会自动写入配置。 */
    val localMcpDiscovery: StateFlow<LocalMcpDiscoveryState> = _localMcpDiscovery.asStateFlow()

    /**
     * 从本机监听 socket 中寻找候选端口，并以 MCP initialize + tools/list 确认真正的服务。
     * 不做全端口扫描：既避免无意义的连接风暴，也不会碰触任何非本机地址。
     */
    fun discoverLocalMcpServers() {
        viewModelScope.launch {
            _localMcpDiscovery.value = LocalMcpDiscoveryState.Scanning
            val existingUrls = mcpServers.value.map { it.serverUrl.trimEnd('/') }.toSet()
            val candidates = withContext(Dispatchers.IO) { localLoopbackMcpCandidates() }
                .filterNot { it.serverUrl.trimEnd('/') in existingUrls }
            try {
                val probeLimiter = Semaphore(4)
                val found = candidates.map { candidate ->
                    async(Dispatchers.IO) {
                        val tools = probeLimiter.withPermit {
                            kotlinx.coroutines.withTimeoutOrNull(6_000) {
                                mcpManager.testServer(candidate.server).getOrNull()
                            }
                        }
                        tools?.let { candidate.copy(toolCount = it.size) }
                    }
                }.awaitAll().filterNotNull().sortedBy { it.serverUrl }
                _localMcpDiscovery.value = LocalMcpDiscoveryState.Results(found)
            } catch (throwable: Throwable) {
                _localMcpDiscovery.value = LocalMcpDiscoveryState.Error(
                    throwable.message ?: "本机 MCP 探测失败",
                )
            }
        }
    }

    /** 用户首选模式；即使本次启动降级也保留，用于下次自动恢复。 */
    val executionMode: StateFlow<ExecutionMode> = settingsDataStore.preferredExecutionMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExecutionMode.PROOT)
    val effectiveExecutionMode: StateFlow<ExecutionMode> = settingsDataStore.effectiveExecutionMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExecutionMode.PROOT)
    val privilegeState = privilegeManager.state

    private val _switchingMode = MutableStateFlow(false)
    val switchingMode: StateFlow<Boolean> = _switchingMode.asStateFlow()

    fun switchExecutionMode(mode: ExecutionMode, onResult: (Boolean, String) -> Unit) {
        if (_switchingMode.value) return
        viewModelScope.launch {
            _switchingMode.value = true
            val result = privilegeManager.switchMode(mode)
            _switchingMode.value = false
            if (result.isSuccess) {
                val authorized = result.getOrNull()
                onResult(true, authorized?.details ?: "已成功切换至 ${mode.title}")
            } else {
                onResult(false, result.errorOrNull()?.message ?: "授权失败")
            }
        }
    }

    private val _phantomProcessStatus = MutableStateFlow<PhantomProcessLimitStatus?>(null)
    val phantomProcessStatus: StateFlow<PhantomProcessLimitStatus?> = _phantomProcessStatus.asStateFlow()

    private val _phantomProcessBusy = MutableStateFlow(false)
    val phantomProcessBusy: StateFlow<Boolean> = _phantomProcessBusy.asStateFlow()

    private val _phantomProcessMessage = MutableStateFlow<String?>(null)
    val phantomProcessMessage: StateFlow<String?> = _phantomProcessMessage.asStateFlow()

    val phantomProcessAdbCommand: String = PrivilegeManager.PHANTOM_PROCESS_ADB_COMMAND

    fun refreshPhantomProcessLimit() {
        if (_phantomProcessBusy.value) return
        viewModelScope.launch {
            _phantomProcessBusy.value = true
            try {
                _phantomProcessStatus.value = privilegeManager.checkPhantomProcessLimit()
            } finally {
                _phantomProcessBusy.value = false
            }
        }
    }

    fun removePhantomProcessLimit() {
        if (_phantomProcessBusy.value) return
        viewModelScope.launch {
            _phantomProcessBusy.value = true
            try {
                val result = privilegeManager.removePhantomProcessLimit()
                _phantomProcessMessage.value = if (result.success) {
                    "解除命令执行成功，已重新读取系统状态。"
                } else {
                    result.stderr.ifBlank { "解除失败（退出码 ${result.exitCode}）" }
                }
                _phantomProcessStatus.value = privilegeManager.checkPhantomProcessLimit()
            } finally {
                _phantomProcessBusy.value = false
            }
        }
    }

    fun clearPhantomProcessMessage() {
        _phantomProcessMessage.value = null
    }

    val providerCatalog = providerCatalogRepository.providers

    val models: StateFlow<List<AiModelEntity>> = aiModelDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val developerMode: StateFlow<Boolean> = settingsDataStore.developerMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val qemuCompatibilityEnabled: StateFlow<Boolean> = settingsDataStore.qemuCompatibilityEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _qemuCompatibilityMessage = MutableStateFlow<String?>(null)
    val qemuCompatibilityMessage: StateFlow<String?> = _qemuCompatibilityMessage.asStateFlow()

    private val _qemuCompatibilityReady = MutableStateFlow(false)
    val qemuCompatibilityReady: StateFlow<Boolean> = _qemuCompatibilityReady.asStateFlow()

    val themeMode: StateFlow<String> = settingsDataStore.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")

    val themeStyle: StateFlow<String> = settingsDataStore.themeStyle
        .stateIn(viewModelScope, SharingStarted.Eagerly, "xuantong")

    fun setThemeStyle(style: String) {
        viewModelScope.launch {
            settingsDataStore.setThemeStyle(style)
        }
    }

    val provider: StateFlow<String> = providerRepository.provider
        .stateIn(viewModelScope, SharingStarted.Eagerly, "OpenAI")
    val apiKeyConfigured: StateFlow<Boolean> = providerRepository.apiKeyConfigured
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val baseUrl: StateFlow<String> = providerRepository.baseUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val model: StateFlow<String> = providerRepository.model
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // ---- Agent 配置与管理 ----
    val thinkingExpanded: StateFlow<Boolean> = settingsDataStore.thinkingExpanded
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val thinkingLanguage: StateFlow<String> = settingsDataStore.thinkingLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "zh")

    fun setThinkingLanguage(lang: String) {
        viewModelScope.launch { settingsDataStore.setThinkingLanguage(lang) }
    }

    val customSystemPromptEnabled: StateFlow<Boolean> = settingsDataStore.customSystemPromptEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setCustomSystemPromptEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setCustomSystemPromptEnabled(enabled) }
    }

    val customSystemPrompt: StateFlow<String> = settingsDataStore.customSystemPrompt
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setCustomSystemPrompt(prompt: String) {
        viewModelScope.launch { settingsDataStore.setCustomSystemPrompt(prompt) }
    }

    val defaultReasoningDepth: StateFlow<String> = settingsDataStore.defaultReasoningDepth
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    val contextCompactionEnabled: StateFlow<Boolean> = settingsDataStore.contextCompactionEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val contextCompactionThreshold: StateFlow<Int> = settingsDataStore.contextCompactionThreshold
        .stateIn(viewModelScope, SharingStarted.Eagerly, 15)

    val maxToolRounds: StateFlow<Int> = settingsDataStore.maxToolRounds
        .stateIn(viewModelScope, SharingStarted.Eagerly, 100)

    val autoWorkspaceCwd: StateFlow<Boolean> = settingsDataStore.autoWorkspaceCwd
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val commandOutputCompressionEnabled: StateFlow<Boolean> = settingsDataStore.commandOutputCompressionEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val baseCommandTimeoutSeconds: StateFlow<Int> = settingsDataStore.baseCommandTimeoutSeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsDataStore.DEFAULT_BASE_COMMAND_TIMEOUT_SECONDS)

    val approvalMode: StateFlow<top.wkbin.taixu.core.model.ApprovalMode> = approvalRepository.mode
        .stateIn(viewModelScope, SharingStarted.Eagerly, top.wkbin.taixu.core.model.ApprovalMode.ASSISTED)

    val contextBudgetTokens: StateFlow<Int> = settingsDataStore.contextBudgetTokens
        .stateIn(viewModelScope, SharingStarted.Eagerly, 128_000)

    val maxToolsPerRound: StateFlow<Int> = settingsDataStore.maxToolsPerRound
        .stateIn(viewModelScope, SharingStarted.Eagerly, 12)

    val maxConsecutiveFailures: StateFlow<Int> = settingsDataStore.maxConsecutiveFailures
        .stateIn(viewModelScope, SharingStarted.Eagerly, 8)

    val allSkills: StateFlow<List<top.wkbin.taixu.core.model.AgentSkill>> = agentSkillRepository.allSkills
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _skillArchiveMessage = MutableStateFlow<String?>(null)
    val skillArchiveMessage: StateFlow<String?> = _skillArchiveMessage.asStateFlow()

    /** Skill 归档导入结果是否失败（类型化标记，避免 UI 用字符串匹配判断样式）。 */
    private val _skillArchiveMessageIsError = MutableStateFlow(false)
    val skillArchiveMessageIsError: StateFlow<Boolean> = _skillArchiveMessageIsError.asStateFlow()

    val autoSubagentDelegationEnabled: StateFlow<Boolean> = subagentRepository.autoDelegationEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val allSubagents: StateFlow<List<top.wkbin.taixu.core.model.AgentSubagent>> = subagentRepository.profiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allPlugins: StateFlow<List<top.wkbin.taixu.core.model.AgentPlugin>> = settingsDataStore.allPlugins
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setThinkingExpanded(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setThinkingExpanded(value) }
    }

    fun setDefaultReasoningDepth(value: String) {
        viewModelScope.launch { settingsDataStore.setDefaultReasoningDepth(value) }
    }

    fun setContextCompactionEnabled(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setContextCompactionEnabled(value) }
    }

    fun setContextCompactionThreshold(value: Int) {
        viewModelScope.launch { settingsDataStore.setContextCompactionThreshold(value) }
    }

    fun setMaxToolRounds(value: Int) {
        viewModelScope.launch { settingsDataStore.setMaxToolRounds(value) }
    }

    fun setAutoWorkspaceCwd(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setAutoWorkspaceCwd(value) }
    }

    fun setCommandOutputCompressionEnabled(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setCommandOutputCompressionEnabled(value) }
    }

    fun setBaseCommandTimeoutSeconds(value: Int) {
        viewModelScope.launch { settingsDataStore.setBaseCommandTimeoutSeconds(value) }
    }

    fun setApprovalMode(mode: top.wkbin.taixu.core.model.ApprovalMode) {
        viewModelScope.launch {
            approvalRepository.setMode(mode)
            // 会话创建时快照了当时的全局模式；全局改动必须传导到已有会话，
            // 否则执行层（ToolExecutor 优先读会话模式）仍按旧模式要求审批。
            sessionDao.setApprovalModeForAll(mode.id, System.currentTimeMillis())
        }
    }

    fun setContextBudgetTokens(value: Int) {
        viewModelScope.launch { settingsDataStore.setContextBudgetTokens(value) }
    }

    fun setMaxToolsPerRound(value: Int) {
        viewModelScope.launch { settingsDataStore.setMaxToolsPerRound(value) }
    }

    fun setMaxConsecutiveFailures(value: Int) {
        viewModelScope.launch { settingsDataStore.setMaxConsecutiveFailures(value) }
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        viewModelScope.launch { agentSkillRepository.setEnabled(skillId, enabled) }
    }

    fun addCustomSkill(name: String, description: String, systemPrompt: String, command: String?) {
        val trimmedName = name.trim()
        val trimmedPrompt = systemPrompt.trim()
        if (trimmedName.isBlank() || trimmedPrompt.isBlank()) return
        val id = "custom_" + java.util.UUID.randomUUID().toString().take(8)
        val skill = top.wkbin.taixu.core.model.AgentSkill(
            id = id,
            name = trimmedName,
            description = description.trim().ifBlank { "自定义技能" },
            systemPrompt = trimmedPrompt,
            triggerCommand = command?.trim()?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("/")) it else "/$it" },
            iconName = "Code",
            isEnabled = true,
            isBuiltin = false,
            category = "自定义",
        )
        viewModelScope.launch { agentSkillRepository.addCustom(skill) }
    }

    fun deleteCustomSkill(skillId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val resourcePath = allSkills.value.firstOrNull { it.id == skillId }?.resourcePath
            agentSkillRepository.deleteCustom(skillId)
            resourcePath?.let(::deleteOwnedSkillDirectory)
        }
    }

    fun importSkillArchives(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val successes = mutableListOf<String>()
            val failures = mutableListOf<String>()
            uris.forEach { uri ->
                runCatching { importSingleSkillArchive(uri) }
                    .onSuccess { successes += it }
                    .onFailure { failures += it.message ?: "Skill 压缩包导入失败" }
            }
            if (failures.isEmpty()) {
                _skillArchiveMessageIsError.value = false
                _skillArchiveMessage.value = if (successes.size == 1) successes.first() else "批量导入完成，共 ${successes.size} 个：\n${successes.joinToString("\n")}"
            } else if (successes.isEmpty()) {
                _skillArchiveMessageIsError.value = true
                _skillArchiveMessage.value = if (failures.size == 1) failures.first() else "批量导入失败，共 ${failures.size} 个：\n${failures.joinToString("\n")}"
            } else {
                _skillArchiveMessageIsError.value = true
                _skillArchiveMessage.value = "批量导入完成：成功 ${successes.size} 个，失败 ${failures.size} 个\n\n成功：\n${successes.joinToString("\n")}\n\n失败：\n${failures.joinToString("\n")}"
            }
        }
    }

    /** 手动批量导入：扫描 attachments/skills 与工作区 skills 目录，把未入库的 Skill 文件夹一次性注册。 */
    fun scanSkillDirectories() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { agentSkillRepository.syncFromDirectories(skillScanRoots()) }
                .onSuccess { imported ->
                    _skillArchiveMessageIsError.value = false
                    _skillArchiveMessage.value = when (imported.size) {
                        0 -> "目录扫描完成，未发现新的 Skill（已导入的不会重复注册）"
                        1 -> "目录扫描完成，导入 1 个：“${imported.first().name}”"
                        else -> "目录扫描完成，共导入 ${imported.size} 个：\n${imported.joinToString("\n") { "· ${it.name}" }}"
                    }
                }
                .onFailure {
                    _skillArchiveMessageIsError.value = true
                    _skillArchiveMessage.value = it.message ?: "Skill 目录扫描失败"
                }
        }
    }

    /**
     * 从用户自选的任意目录（SAF 目录选择器）导入 Skill：递归查找直属含 SKILL.md
     * 的目录，把最顶层的 Skill 目录整体复制到 attachments/skills 下再由扫描入库，
     * 保证资源落在 PRoot 可访问的挂载路径内。嵌套的 Skill 子目录随父目录一并复制，
     * 之后由递归扫描逐个注册。
     */
    fun importSkillsFromTree(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val treeRoot = DocumentFile.fromTreeUri(application, uri) ?: error("无法访问所选目录")
                val targetRoot = File(pathManager.attachmentsDir, "skills").apply { mkdirs() }
                val copied = importDocumentTree(treeRoot, targetRoot)
                check(copied > 0) { "所选目录及子目录中未发现包含 SKILL.md 的 Skill 文件夹" }
                val imported = agentSkillRepository.syncFromDirectories(skillScanRoots())
                "自选目录导入完成，发现并注册 ${imported.size} 个 Skill"
            }.onSuccess {
                _skillArchiveMessageIsError.value = false
                _skillArchiveMessage.value = it
            }.onFailure {
                _skillArchiveMessageIsError.value = true
                _skillArchiveMessage.value = it.message ?: "自选目录导入失败"
            }
        }
    }

    /** 递归遍历 SAF 目录，把顶层 Skill 目录复制到 [targetRoot]；返回复制的目录数。 */
    private fun importDocumentTree(dir: DocumentFile, targetRoot: File): Int {
        var count = 0
        dir.listFiles().filter { it.isDirectory }.forEach { child ->
            if (isDocumentSkillDir(child)) {
                val name = uniqueDirName(targetRoot, child.name ?: "skill")
                copyDocumentTree(child, File(targetRoot, name))
                count++
            } else {
                count += importDocumentTree(child, targetRoot)
            }
        }
        return count
    }

    private fun isDocumentSkillDir(dir: DocumentFile): Boolean =
        dir.listFiles().any { it.isFile && it.name?.lowercase() in AgentSkillRepository.SKILL_PROMPT_FILE_NAMES }

    /** 递归复制 SAF 目录到宿主文件系统；脚本文件标记为可执行。 */
    private fun copyDocumentTree(src: DocumentFile, dest: File) {
        dest.mkdirs()
        src.listFiles().forEach { entry ->
            val name = entry.name ?: return@forEach
            if (entry.isDirectory) {
                copyDocumentTree(entry, File(dest, name))
            } else {
                val out = File(dest, name)
                application.contentResolver.openInputStream(entry.uri)?.use { input ->
                    BufferedOutputStream(out.outputStream()).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (name.substringAfterLast('.', "").lowercase() in setOf("sh", "py", "js")) {
                    out.setExecutable(true, false)
                }
            }
        }
    }

    /** 生成不冲突的目标目录名：已存在则追加序号。 */
    private fun uniqueDirName(targetRoot: File, base: String): String {
        val safe = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "skill" }
        var candidate = safe
        var index = 2
        while (File(targetRoot, candidate).exists()) {
            candidate = "${safe}_$index"
            index++
        }
        return candidate
    }

    private suspend fun importSingleSkillArchive(uri: Uri): String {
        var target: File? = null
        try {
            val id = "custom_" + UUID.randomUUID().toString().take(8)
            target = File(pathManager.attachmentsDir, "skills/$id").apply { mkdirs() }
            var entryCount = 0
            var totalBytes = 0L
            application.contentResolver.openInputStream(uri)?.use { source ->
                ZipInputStream(source).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        check(++entryCount <= MAX_SKILL_ARCHIVE_ENTRIES) { "Skill 压缩包文件数量超过 $MAX_SKILL_ARCHIVE_ENTRIES 个" }
                        val relative = entry.name.replace('\\', '/')
                        check(relative.isNotBlank() && !relative.startsWith('/')) { "Skill 压缩包包含非法路径" }
                        val out = File(target, relative)
                        require(out.canonicalPath.startsWith(target.canonicalPath + File.separator)) { "Skill 压缩包包含非法路径" }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            var entryBytes = 0L
                            BufferedOutputStream(out.outputStream()).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    entryBytes += read
                                    totalBytes += read
                                    check(entryBytes <= MAX_SKILL_ENTRY_BYTES) { "Skill 单个文件不能超过 8 MB" }
                                    check(totalBytes <= MAX_SKILL_ARCHIVE_BYTES) { "Skill 解压后总大小不能超过 32 MB" }
                                    output.write(buffer, 0, read)
                                }
                            }
                            if (relative.startsWith("scripts/") || relative.contains("/scripts/") || out.extension.lowercase() in setOf("sh", "py", "js")) {
                                out.setExecutable(true, false)
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            } ?: error("无法读取 Skill 压缩包")
            val promptFile = target.walkTopDown()
                .maxDepth(3)
                .filter { it.isFile && it.name.lowercase() in setOf("skill.md", "prompt.md") }
                .singleOrNull()
                ?: error("压缩包内未找到 SKILL.md")
            val markdown = promptFile.readText().trim()
            require(markdown.isNotBlank()) { "SKILL.md 为空" }
            val skillName = AgentSkillRepository.extractSkillMetadata(markdown, "name")
                ?: markdown.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
                ?: promptFile.parentFile?.name
                ?: id
            val description = AgentSkillRepository.extractSkillMetadata(markdown, "description") ?: "从压缩包导入的 Skill"
            val guestPath = "/attachments/skills/$id"
            val prompt = markdown + "\n\n【Skill 资源目录】$guestPath\n如需执行该 Skill 附带的脚本，请先检查脚本内容与参数，再从此目录调用。"
            agentSkillRepository.addCustom(top.wkbin.taixu.core.model.AgentSkill(id, skillName, description, prompt, isBuiltin = false, category = "自定义", resourcePath = target.absolutePath))
            return "Skill“$skillName”导入成功，脚本资源位于 $guestPath"
        } catch (error: Throwable) {
            target?.let(::deleteOwnedSkillDirectory)
            throw error
        }
    }

    fun clearSkillArchiveMessage() {
        _skillArchiveMessage.value = null
        _skillArchiveMessageIsError.value = false
    }

    private fun deleteOwnedSkillDirectory(directory: File) {
        val candidate = runCatching { directory.canonicalFile }.getOrNull() ?: return
        val roots = listOf(File(pathManager.attachmentsDir, "skills"), File(application.filesDir, "skills"), File(pathManager.workspaceDir, "skills"))
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        if (roots.any { candidate.path.startsWith(it.path + File.separator) }) candidate.deleteRecursively()
    }

    private fun deleteOwnedSkillDirectory(path: String) = deleteOwnedSkillDirectory(File(path))

    /** Skill 自动发现目录：共享附件区与工作区，guestPrefix 对应 PRoot 沙箱内挂载路径。 */
    private fun skillScanRoots() = listOf(
        top.wkbin.taixu.core.database.SkillScanRoot(File(pathManager.attachmentsDir, "skills"), "/attachments/skills"),
        top.wkbin.taixu.core.database.SkillScanRoot(File(pathManager.workspaceDir, "skills"), "/workspace/skills"),
    )

    private companion object {
        const val MAX_SKILL_ARCHIVE_ENTRIES = 256
        const val MAX_SKILL_ENTRY_BYTES = 8L * 1024 * 1024
        const val MAX_SKILL_ARCHIVE_BYTES = 32L * 1024 * 1024
    }

    fun setAutoSubagentDelegationEnabled(enabled: Boolean) {
        viewModelScope.launch { subagentRepository.setAutoDelegationEnabled(enabled) }
    }

    fun toggleSubagent(profileId: String, enabled: Boolean) {
        viewModelScope.launch { subagentRepository.setEnabled(profileId, enabled) }
    }

    fun saveSubagent(
        previous: top.wkbin.taixu.core.model.AgentSubagent?,
        roleId: String,
        name: String,
        description: String,
        systemPrompt: String,
        defaultModelId: String?,
        defaultModelVariant: String?,
    ) {
        val normalizedId = roleId.trim().lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
        val trimmedName = name.trim()
        val trimmedPrompt = systemPrompt.trim()
        val normalizedDefaultModelId = defaultModelId?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedId.isBlank() || trimmedName.isBlank() || trimmedPrompt.isBlank()) return
        viewModelScope.launch {
            val profile = top.wkbin.taixu.core.model.AgentSubagent(
                id = normalizedId,
                name = trimmedName,
                description = description.trim().ifBlank { "自定义子智能体角色" },
                systemPrompt = trimmedPrompt,
                defaultModelId = normalizedDefaultModelId,
                defaultModelVariant = normalizedDefaultModelId?.let {
                    defaultModelVariant?.trim()?.takeIf { variant -> variant.isNotBlank() }
                },
                departmentId = previous?.departmentId ?: top.wkbin.taixu.core.model.AgentDepartments.CUSTOM_ID,
                isEnabled = previous?.isEnabled ?: true,
                isBuiltin = previous?.isBuiltin ?: false,
                sortOrder = previous?.sortOrder ?: subagentRepository.nextSortOrder(),
            )
            subagentRepository.replace(previous?.id, profile)
        }
    }

    fun deleteSubagent(profileId: String) {
        viewModelScope.launch { subagentRepository.delete(profileId) }
    }

    fun togglePlugin(pluginId: String, enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setPluginEnabled(pluginId, enabled) }
    }

    private val _apiKeyDraft = MutableStateFlow("")
    val apiKeyDraft: StateFlow<String> = _apiKeyDraft.asStateFlow()
    private val _discoveredModels = MutableStateFlow<List<String>>(emptyList())
    val discoveredModels: StateFlow<List<String>> = _discoveredModels.asStateFlow()
    private val _discoveringModels = MutableStateFlow(false)
    val discoveringModels: StateFlow<Boolean> = _discoveringModels.asStateFlow()
    private val _modelDiscoveryError = MutableStateFlow<String?>(null)
    val modelDiscoveryError: StateFlow<String?> = _modelDiscoveryError.asStateFlow()
    private val _testingConnection = MutableStateFlow(false)
    val testingConnection: StateFlow<Boolean> = _testingConnection.asStateFlow()
    private val _connectionResult = MutableStateFlow<String?>(null)
    val connectionResult: StateFlow<String?> = _connectionResult.asStateFlow()

    fun setDeveloperMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setDeveloperMode(enabled)
        }
    }

    fun setQemuCompatibilityEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val distroId = linuxRuntime.activeDistroId.value
                if (!QemuCompatibilityLayout.isReady(pathManager.taixuRootDir(distroId))) {
                    _qemuCompatibilityMessage.value = "未检测到 QEMU x86_64 兼容环境，无法开启。请先在插件中心安装 qemu-x86-64-compat 插件。"
                    return@launch
                }
            }
            _qemuCompatibilityMessage.value = null
            settingsDataStore.setQemuCompatibilityEnabled(enabled)
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            settingsDataStore.setThemeMode(mode)
        }
    }

    fun setProvider(value: String) {
        viewModelScope.launch { providerRepository.setProvider(value) }
    }

    fun setBaseUrl(value: String) {
        viewModelScope.launch { providerRepository.setBaseUrl(value) }
    }

    fun setModel(value: String) {
        viewModelScope.launch { providerRepository.setModel(value) }
    }

    fun onApiKeyChanged(value: String) {
        _apiKeyDraft.value = value
    }

    fun discoverModels(providerId: String, baseUrl: String, apiKey: String = "") {
        val cleanUrl = ProviderEndpointPolicy.normalizeUrl(baseUrl)
        if (!ProviderEndpointPolicy.isSafeBaseUrl(cleanUrl)) return
        val provider = providerCatalogRepository.find(providerId)
        _discoveringModels.value = true
        _modelDiscoveryError.value = null
        _discoveredModels.value = emptyList()
        viewModelScope.launch {
            val discoveryKey = profileWriter.parseApiKeys(apiKey).firstOrNull() ?: providerRepository.readApiKey()
            // 对于要求 API Key 的服务商，空 key 不发请求，直接给出友好提示
            if (!provider.apiKeyOptional && discoveryKey.isNullOrBlank()) {
                _modelDiscoveryError.value = "该服务商需要 API Key 才能获取模型列表，请先填写 API Key 后再刷新"
                _discoveringModels.value = false
                return@launch
            }
            runCatching { modelDiscovery.discover(provider, cleanUrl, discoveryKey) }
                .onSuccess { models ->
                    _discoveredModels.value = models
                    if (models.isEmpty()) _modelDiscoveryError.value = "端点未返回可用的 Agent 模型"
                }
                .onFailure {
                    logger.w("Model discovery failed: ${it.message}", it)
                    _modelDiscoveryError.value = "获取模型列表失败，请检查 Base URL、API Key 与网络后重试"
                }
            _discoveringModels.value = false
        }
    }

    fun clearDiscoveredModels() {
        _discoveredModels.value = emptyList()
        _modelDiscoveryError.value = null
    }

    fun testConnection(
        baseUrl: String,
        model: String,
        apiKey: String,
        useResponsesApi: Boolean = false,
        providerId: String? = null,
    ) {
        val provider = providerId?.let { providerCatalogRepository.find(it) }
        viewModelScope.launch {
            _testingConnection.value = true
            _connectionResult.value = null
            runCatching {
                connectionTester.test(
                    baseUrl = baseUrl,
                    model = model,
                    apiKey = profileWriter.parseApiKeys(apiKey).firstOrNull(),
                    useResponsesApi = useResponsesApi,
                    protocol = provider?.protocol ?: top.wkbin.taixu.core.tools.ProviderProtocol.OPENAI,
                    providerName = provider?.name,
                )
            }
                .onSuccess { _connectionResult.value = "连接成功" }
                .onFailure {
                    logger.w("Connection test failed: ${it.message}", it)
                    _connectionResult.value = "连接失败，请检查接口地址、密钥与网络后重试"
                }
            _testingConnection.value = false
        }
    }

    fun saveApiKey() {
        viewModelScope.launch {
            providerRepository.setApiKey(_apiKeyDraft.value)
            _apiKeyDraft.value = ""
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            providerRepository.setApiKey("")
            _apiKeyDraft.value = ""
        }
    }

    fun saveModel(
        id: String?,
        name: String,
        provider: String,
        model: String,
        baseUrl: String,
        apiKey: String,
        requestsPerMinutePerKey: Int = 0,
        temperature: Float? = null,
        maxTokens: Int? = null,
        topP: Float? = null,
        reasoningMode: String? = null,
        reasoningEffort: String? = null,
        toolCallMode: String? = null,
        contextTokens: Int? = null,
        customHeaders: String = "",
        pureChatMode: Boolean = false,
        visionEnabled: Boolean = true,
        imageGenerationEnabled: Boolean = false,
        responseApiEnabled: Boolean = false,
    ) {
        viewModelScope.launch {
            profileWriter.upsertProfile(
                AiProfileWriter.UpsertRequest(
                    id = id,
                    name = name,
                    provider = provider,
                    model = model,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    requestsPerMinutePerKey = requestsPerMinutePerKey,
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
                ),
            )
        }
    }

    fun saveModels(
        id: String? = null,
        models: List<String>,
        name: String = "",
        provider: String,
        baseUrl: String,
        apiKey: String,
        requestsPerMinutePerKey: Int = 0,
        temperature: Float? = null,
        maxTokens: Int? = null,
        topP: Float? = null,
        reasoningMode: String? = null,
        reasoningEffort: String? = null,
        toolCallMode: String? = null,
        contextTokens: Int? = null,
        customHeaders: String = "",
        pureChatMode: Boolean = false,
        visionEnabled: Boolean = true,
        imageGenerationEnabled: Boolean = false,
        responseApiEnabled: Boolean = false,
    ) {
        viewModelScope.launch {
            val cleanModels = models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val profileName = name.trim().ifBlank {
                if (cleanModels.size == 1) cleanModels.first() else provider.trim()
            }
            profileWriter.upsertProfile(
                AiProfileWriter.UpsertRequest(
                    id = id,
                    name = profileName,
                    provider = provider,
                    model = cleanModels.joinToString(", "),
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    requestsPerMinutePerKey = requestsPerMinutePerKey,
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
                ),
            )
        }
    }

    suspend fun readModelApiKey(secretRef: String): String {
        return providerRepository.readModelApiKeys(secretRef).joinToString("\n")
    }

    fun setActiveModel(id: String) {
        viewModelScope.launch {
            aiModelDao.clearActive()
            aiModelDao.setActive(id)
        }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            profileWriter.deleteProfile(id)
        }
    }

    suspend fun exportAllProfilesJson(includeApiKeys: Boolean): String =
        profileBackupCodec.exportAll(includeApiKeys)

    suspend fun exportSingleProfileJson(modelId: String, includeApiKeys: Boolean): String? =
        profileBackupCodec.exportSingle(modelId, includeApiKeys)

    fun parseProfilesFromJson(rawJson: String): Result<List<AiModelProfileExport>> =
        profileBackupCodec.parseProfiles(rawJson)

    suspend fun importProfilesFromJson(rawJson: String): Result<Int> =
        profileBackupCodec.importProfiles(rawJson)


    // ---- 宿主与沙箱存储挂载配置 (PRoot -b) ----
    val mountDownloadEnabled: StateFlow<Boolean> = settingsDataStore.mountDownloadEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val mountDocumentsEnabled: StateFlow<Boolean> = settingsDataStore.mountDocumentsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val mountSharedStorageEnabled: StateFlow<Boolean> = settingsDataStore.mountSharedStorageEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val customMountBindings: StateFlow<List<top.wkbin.taixu.core.model.StorageMountBinding>> = storageMountBindingRepository.bindings
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setMountDownloadEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setMountDownloadEnabled(enabled) }
    }

    fun setMountDocumentsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setMountDocumentsEnabled(enabled) }
    }

    fun setMountSharedStorageEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setMountSharedStorageEnabled(enabled) }
    }

    fun addCustomMountBinding(name: String, hostPath: String, guestPath: String) {
        val binding = top.wkbin.taixu.core.model.StorageMountBinding(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "自定义挂载" },
            hostPath = hostPath.trim(),
            guestPath = if (guestPath.trim().startsWith("/")) guestPath.trim() else "/${guestPath.trim()}",
            enabled = true,
            isSystemDefault = false,
        )
        viewModelScope.launch { storageMountBindingRepository.add(binding) }
    }

    fun removeCustomMountBinding(bindingId: String) {
        viewModelScope.launch { storageMountBindingRepository.remove(bindingId) }
    }

    fun toggleCustomMountBinding(bindingId: String, enabled: Boolean) {
        viewModelScope.launch { storageMountBindingRepository.setEnabled(bindingId, enabled) }
    }

    // ---- 快捷短语与常用指令 ----
    val quickPhrases: StateFlow<List<top.wkbin.taixu.core.model.QuickPhrase>> = quickPhraseRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun saveQuickPhrase(
        id: String?,
        title: String,
        content: String,
        description: String = "",
        iconName: String = "Play",
        targetProjectType: String? = null,
        isEnabled: Boolean = true,
    ) {
        viewModelScope.launch {
            val existing = if (id != null) quickPhraseRepository.findById(id) else null
            val phrase = top.wkbin.taixu.core.model.QuickPhrase(
                id = id ?: java.util.UUID.randomUUID().toString(),
                title = title.trim(),
                content = content.trim(),
                description = description.trim(),
                iconName = iconName.ifBlank { "Play" },
                targetProjectType = targetProjectType?.ifBlank { null },
                isEnabled = isEnabled,
                sortOrder = existing?.sortOrder ?: 99,
                isBuiltin = existing?.isBuiltin ?: false,
            )
            quickPhraseRepository.upsert(phrase)
        }
    }

    fun deleteQuickPhrase(id: String) {
        viewModelScope.launch {
            quickPhraseRepository.delete(id)
        }
    }

    fun toggleQuickPhrase(id: String, enabled: Boolean) {
        viewModelScope.launch {
            quickPhraseRepository.setEnabled(id, enabled)
        }
    }

    fun resetQuickPhrasesToDefault() {
        viewModelScope.launch {
            quickPhraseRepository.resetToDefault()
        }
    }
}

sealed interface LocalMcpDiscoveryState {
    data object Idle : LocalMcpDiscoveryState
    data object Scanning : LocalMcpDiscoveryState
    data class Results(val servers: List<DetectedLocalMcpServer>) : LocalMcpDiscoveryState
    data class Error(val message: String) : LocalMcpDiscoveryState
}

data class DetectedLocalMcpServer(
    val server: top.wkbin.taixu.core.model.McpServerConfig,
    val toolCount: Int,
) {
    val serverUrl: String get() = server.serverUrl
}

/** 从 Linux proc socket 表提取可由 127.0.0.1 访问的监听端口。 */
private fun localLoopbackMcpCandidates(): List<DetectedLocalMcpServer> {
    val ports = linkedSetOf<Int>()
    listOf("/proc/net/tcp", "/proc/net/tcp6").forEach { path ->
        runCatching { File(path).readLines() }.getOrDefault(emptyList()).drop(1).forEach { line ->
            val fields = line.trim().split(Regex("\\s+"))
            val local = fields.getOrNull(1) ?: return@forEach
            if (fields.getOrNull(3) != "0A") return@forEach // TCP_LISTEN
            val address = local.substringBefore(':')
            val port = local.substringAfter(':', "").toIntOrNull(16) ?: return@forEach
            val loopbackOrAny = address == "0100007F" || address.all { it == '0' } || address.endsWith("00000001")
            if (loopbackOrAny && port in 1..65535) ports += port
        }
    }
    // 某些 Android 版本会限制 /proc 可见性；保留常见开发端口作为小范围回退。
    if (ports.isEmpty()) ports += listOf(3000, 3001, 4000, 5000, 5173, 8000, 8080, 8787, 9000)

    return ports.take(32).flatMap { port ->
        listOf("mcp", "sse").map { path ->
            val url = "http://127.0.0.1:$port/$path"
            DetectedLocalMcpServer(
                server = top.wkbin.taixu.core.model.McpServerConfig(
                    id = "local_probe_${port}_$path",
                    name = "本机 MCP ($port)",
                    description = "在 127.0.0.1:$port 上自动探测到的 MCP 服务",
                    transportType = top.wkbin.taixu.core.model.McpTransportType.SSE,
                    serverUrl = url,
                    isEnabled = true,
                    isBuiltin = false,
                ),
                toolCount = 0,
            )
        }
    }
}

/** 浏览器 MCP 安全门禁状态（默认全部关闭，最小权限）。 */
data class BrowserGateState(
    val allowRemoteConnect: Boolean = false,
    val allowEvalJs: Boolean = false,
    val allowHooks: Boolean = false,
    val allowCdp: Boolean = false,
)
