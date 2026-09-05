package top.wkbin.taixu.ui.developer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.datastore.SettingsDataStore
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.core.model.InstalledRuntime
import top.wkbin.taixu.core.tools.RuntimeManager
import top.wkbin.taixu.core.tools.SignedRegistryRequest
import top.wkbin.taixu.core.tools.ToolManager
import top.wkbin.taixu.core.tools.ToolRegistry
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.RuntimeHealth
import top.wkbin.taixu.runtime.RootfsUpdateInfo
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.ShellCommand
import top.wkbin.taixu.runtime.shell.ManagedProcess
import top.wkbin.taixu.runtime.bridge.adb.EmbeddedAdbManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@HiltViewModel
class DeveloperViewModel @Inject constructor(
    private val linuxRuntime: LinuxRuntime,
    private val runtimeManager: RuntimeManager,
    private val settingsDataStore: SettingsDataStore,
    private val toolRegistry: ToolRegistry,
    private val toolManager: ToolManager,
    private val logger: AppLogger,
    private val embeddedAdbManager: EmbeddedAdbManager,
) : ViewModel() {

    val runtimeState: StateFlow<RuntimeState> = linuxRuntime.state

    private val _commandInput = MutableStateFlow("cat /etc/os-release")
    val commandInput: StateFlow<String> = _commandInput.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _health = MutableStateFlow<RuntimeHealth?>(null)
    val health: StateFlow<RuntimeHealth?> = _health.asStateFlow()

    private val _commandResult = MutableStateFlow<CommandResult?>(null)
    val commandResult: StateFlow<CommandResult?> = _commandResult.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 当前 message 是否为失败结果（类型化标记，避免 UI 用字符串匹配判断样式）。 */
    private val _messageIsError = MutableStateFlow(false)
    val messageIsError: StateFlow<Boolean> = _messageIsError.asStateFlow()

    private fun setMessage(text: String, isError: Boolean = false) {
        _message.value = text
        _messageIsError.value = isError
    }
    private val _unusedRuntimes = MutableStateFlow<List<InstalledRuntime>>(emptyList())
    val unusedRuntimes: StateFlow<List<InstalledRuntime>> = _unusedRuntimes.asStateFlow()
    private val _processes = MutableStateFlow<List<ManagedProcess>>(emptyList())
    val processes: StateFlow<List<ManagedProcess>> = _processes.asStateFlow()
    private val _rootfsVersion = MutableStateFlow<String?>(null)
    val rootfsVersion: StateFlow<String?> = _rootfsVersion.asStateFlow()
    private val _rootfsUpdate = MutableStateFlow<RootfsUpdateInfo?>(null)
    val rootfsUpdate: StateFlow<RootfsUpdateInfo?> = _rootfsUpdate.asStateFlow()
    private var initializationJob: Job? = null

    val adbState: StateFlow<EmbeddedAdbManager.ConnectionState> = embeddedAdbManager.state
    val adbDiscovery: StateFlow<EmbeddedAdbManager.DiscoveryState> = embeddedAdbManager.discovery
    private val _adbBusy = MutableStateFlow(false)
    val adbBusy: StateFlow<Boolean> = _adbBusy.asStateFlow()
    private val _adbMessage = MutableStateFlow<String?>(null)
    val adbMessage: StateFlow<String?> = _adbMessage.asStateFlow()
    private val _logcatOutput = MutableStateFlow("")
    val logcatOutput: StateFlow<String> = _logcatOutput.asStateFlow()

    fun pairWirelessAdb(code: String) {
        if (_adbBusy.value) return
        viewModelScope.launch {
            _adbBusy.value = true
            _adbMessage.value = "正在使用自动发现的配对端口进行安全配对…"
            val result = embeddedAdbManager.pair(code.trim())
            _adbMessage.value = result.fold(
                onSuccess = { "配对并连接成功；密钥已安全保存，后续将自动发现端口并重连。" },
                onFailure = { it.message ?: "无线 ADB 配对失败" },
            )
            _adbBusy.value = false
        }
    }

    fun connectWirelessAdb() {
        if (_adbBusy.value) return
        viewModelScope.launch {
            _adbBusy.value = true
            _adbMessage.value = "正在连接自动发现的无线调试端口…"
            val result = embeddedAdbManager.connect()
            _adbMessage.value = result.fold(
                onSuccess = { "无线 ADB 已连接。" },
                onFailure = { it.message ?: "无线 ADB 连接失败" },
            )
            _adbBusy.value = false
        }
    }

    fun restartAdbDiscovery() {
        embeddedAdbManager.stopDiscovery()
        embeddedAdbManager.startDiscovery()
        _adbMessage.value = "已重新启动 mDNS 端口探测。"
    }

    fun captureLogcat(packageName: String, tag: String, priority: Char, keyword: String, lines: Int) {
        if (_adbBusy.value) return
        viewModelScope.launch {
            _adbBusy.value = true
            _adbMessage.value = "正在抓取日志…"
            runCatching {
                embeddedAdbManager.captureLogcat(
                    EmbeddedAdbManager.LogcatRequest(packageName.trim(), tag.trim(), priority, keyword, lines),
                )
            }.onSuccess { result ->
                _logcatOutput.value = result.output
                _adbMessage.value = if (result.success) "日志抓取完成。" else result.output
            }.onFailure { error ->
                _adbMessage.value = error.message ?: "日志抓取失败"
            }
            _adbBusy.value = false
        }
    }

    fun clearDeviceLogcat() {
        if (_adbBusy.value) return
        viewModelScope.launch {
            _adbBusy.value = true
            val result = embeddedAdbManager.clearLogcat()
            if (result.success) _logcatOutput.value = ""
            _adbMessage.value = if (result.success) "设备 Logcat 缓冲区已清空。" else result.output
            _adbBusy.value = false
        }
    }

    val registryManifestUrl: StateFlow<String> = settingsDataStore.registryManifestUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val registrySignatureUrl: StateFlow<String> = settingsDataStore.registrySignatureUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val registryPublicKey: StateFlow<String> = settingsDataStore.registryPublicKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val _registryStatus = MutableStateFlow<String?>(null)
    val registryStatus: StateFlow<String?> = _registryStatus.asStateFlow()

    /** 工具清单更新是否失败（类型化标记）。 */
    private val _registryStatusIsError = MutableStateFlow(false)
    val registryStatusIsError: StateFlow<Boolean> = _registryStatusIsError.asStateFlow()

    private fun setRegistryStatus(text: String, isError: Boolean = false) {
        _registryStatus.value = text
        _registryStatusIsError.value = isError
    }

    val agentLoggingEnabled: StateFlow<Boolean> = settingsDataStore.agentLoggingEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _agentLogSize = MutableStateFlow(0L)
    val agentLogSize: StateFlow<Long> = _agentLogSize.asStateFlow()
    private val _agentLogLocation = MutableStateFlow("")
    val agentLogLocation: StateFlow<String> = _agentLogLocation.asStateFlow()

    fun setAgentLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setAgentLoggingEnabled(enabled)
            refreshAgentLogSize()
        }
    }

    fun refreshAgentLogSize() {
        _agentLogSize.value = logger.getAgentLogSizeBytes()
        _agentLogLocation.value = logger.getAgentLogLocation()
    }

    fun readAgentLogs(): String = logger.readAgentLogs()

    fun clearAgentLogs() {
        logger.clearAgentLogs()
        refreshAgentLogSize()
        setMessage("智能体日志已清空。")
    }

    fun saveRegistryConfig(manifestUrl: String, signatureUrl: String, publicKey: String) {
        viewModelScope.launch {
            settingsDataStore.setRegistryConfig(manifestUrl, signatureUrl, publicKey)
            setRegistryStatus("工具清单配置已保存。")
        }
    }

    fun updateRegistry() {
        viewModelScope.launch {
            setRegistryStatus("正在下载并验证工具清单…")
            val request = SignedRegistryRequest(
                manifestUrl = registryManifestUrl.value,
                signatureUrl = registrySignatureUrl.value,
                publicKeyBase64 = registryPublicKey.value,
            )
            val result = toolRegistry.updateSigned(request)
            if (result.isSuccess) {
                toolManager.syncRegistry()
                setRegistryStatus("工具清单已更新：${result.getOrNull()} 个工具。")
            } else {
                logger.e("Registry update failed: ${result.errorOrNull()?.message}")
                setRegistryStatus("工具清单更新失败，请检查清单地址、签名与公钥配置", isError = true)
            }
        }
    }

    init {
        refreshUnusedRuntimes()
        refreshProcesses()
        refreshRootfsVersion()
        refreshAgentLogSize()
    }

    fun onCommandInputChanged(value: String) {
        _commandInput.value = value
    }

    fun initialize() {
        if (_busy.value) return
        _message.value = null
        initializationJob = viewModelScope.launch {
            _busy.value = true
            try {
                when (val result = linuxRuntime.initialize()) {
                    is AppResult.Success -> setMessage("初始化完成。")
                    is AppResult.Failure -> {
                        logger.e("Runtime initialize failed: ${result.error.message}")
                        setMessage("初始化失败，请检查网络与存储空间后重试", isError = true)
                    }
                }
            } finally {
                _busy.value = false
                initializationJob = null
            }
        }
    }

    fun cancelInitialization() {
        initializationJob?.cancel()
    }

    fun updateRootfs() {
        if (_busy.value || linuxRuntime.state.value !is RuntimeState.Ready) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            _messageIsError.value = false
            runCatching { linuxRuntime.updateRootfs() }
                .onSuccess { result ->
                    if (result.isSuccess) {
                        setMessage("RootFS 更新完成，用户数据已保留。")
                    } else {
                        logger.e("RootFS update failed: ${result.errorOrNull()?.message}")
                        setMessage("RootFS 更新失败，已自动恢复旧版本；请稍后重试", isError = true)
                    }
                    refreshRootfsVersion()
                }
                .onFailure { throwable ->
                    logger.e("RootFS update failed", throwable)
                    setMessage("RootFS 更新失败，请检查网络后重试", isError = true)
                }
            _busy.value = false
        }
    }

    fun checkRootfsUpdate() {
        if (_busy.value || linuxRuntime.state.value !is RuntimeState.Ready) return
        viewModelScope.launch {
            _busy.value = true
            setMessage("正在检查 RootFS 的 OCI manifest…")
            runCatching { linuxRuntime.checkRootfsUpdate() }
                .onSuccess { result ->
                    if (result.isSuccess) {
                        val info = result.getOrNull()!!
                        _rootfsUpdate.value = info
                        if (info.hasUpdate) {
                            setMessage("检测到 RootFS 新版本，可以更新。")
                        } else {
                            setMessage("RootFS 已是最新版本。")
                        }
                    } else {
                        logger.e("RootFS update check failed: ${result.errorOrNull()?.message}")
                        setMessage("RootFS 更新检查失败，请检查网络后重试", isError = true)
                    }
                }
                .onFailure { throwable ->
                    logger.e("RootFS update check failed", throwable)
                    setMessage("RootFS 更新检查失败，请检查网络后重试", isError = true)
                }
            _busy.value = false
        }
    }

    fun runHealthCheck() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching { linuxRuntime.healthCheck() }
                .onSuccess { _health.value = it }
                .onFailure {
                    logger.e("Health check failed", it)
                    setMessage("健康检查失败，请确认运行时已就绪后重试", isError = true)
                }
            _busy.value = false
        }
    }

    fun refreshUnusedRuntimes() {
        viewModelScope.launch {
            runCatching { runtimeManager.unusedRuntimes() }
                .onSuccess { _unusedRuntimes.value = it }
                .onFailure { logger.e("读取可清理 Runtime 失败", it) }
        }
    }

    fun cleanupRuntime(runtimeId: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching { runtimeManager.cleanup(runtimeId) }
                .onSuccess { result ->
                    if (result.isSuccess) setMessage("共享 Runtime 已清理。") else setMessage("清理失败，请稍后重试", isError = true)
                    refreshUnusedRuntimes()
                }
                .onFailure { logger.e("Runtime cleanup failed", it); setMessage("清理失败，请稍后重试", isError = true) }
            _busy.value = false
        }
    }

    fun refreshProcesses() {
        viewModelScope.launch {
            runCatching {
                linuxRuntime.cleanupDeadBackground()
                linuxRuntime.listBackground()
            }.onSuccess { _processes.value = it }
                .onFailure { logger.e("读取后台进程失败", it) }
        }
    }

    fun stopProcess(processId: String) {
        if (_busy.value) return
        viewModelScope.launch {
            runCatching { linuxRuntime.stopBackground(processId) }
                .onSuccess { refreshProcesses() }
                .onFailure { logger.e("Stop process failed", it); setMessage("停止进程失败，请稍后重试", isError = true) }
        }
    }

    fun refreshRootfsVersion() {
        _rootfsVersion.value = linuxRuntime.rootfsVersion()
    }

    fun resetLinuxEnvironment() {
        if (_busy.value || linuxRuntime.state.value is RuntimeState.Initializing) return
        viewModelScope.launch {
            _busy.value = true
            val distroId = linuxRuntime.activeDistroId.value
            runCatching {
                val result = linuxRuntime.resetSandbox(distroId)
                check(result.isSuccess) { result.errorOrNull()?.message ?: "Linux 环境重置失败" }
                toolManager.resetDistroState(distroId)
                // A factory-style runtime reset intentionally restarts the
                // complete first-run flow: environment download, then model
                // selection/configuration.
                settingsDataStore.setOnboardingCompleted(false)
                result
            }
                .onSuccess { result ->
                    if (result.isSuccess) {
                        setMessage("Linux 环境已恢复初始状态，工作区工程未删除。")
                    } else {
                        logger.e("Linux reset failed: ${result.errorOrNull()?.message}")
                        setMessage("重置失败，请稍后重试", isError = true)
                    }
                }
                .onFailure { logger.e("Linux reset failed", it); setMessage("重置失败，请稍后重试", isError = true) }
            _busy.value = false
        }
    }

    fun runCommand() {
        if (_busy.value) return
        val command = _commandInput.value.trim()
        if (command.isEmpty()) {
            setMessage("命令不能为空。", isError = true)
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching {
                linuxRuntime.execute(ShellCommand(commandLine = command))
            }.onSuccess {
                _commandResult.value = it
            }.onFailure {
                logger.e("Command execution failed", it)
                _commandResult.value = null
                setMessage("命令执行失败，请检查命令语法与运行时状态", isError = true)
            }
            _busy.value = false
        }
    }
}
