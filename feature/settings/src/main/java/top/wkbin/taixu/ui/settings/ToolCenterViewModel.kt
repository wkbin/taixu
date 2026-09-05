package top.wkbin.taixu.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.datastore.SettingsDataStore
import top.wkbin.taixu.core.database.InstallLogEntity
import top.wkbin.taixu.core.database.ToolEntity
import top.wkbin.taixu.core.tools.ToolInstallProgress
import top.wkbin.taixu.core.tools.ToolManager
import top.wkbin.taixu.core.tools.ToolVerification
import top.wkbin.taixu.runtime.LinuxRuntime
import javax.inject.Inject
import android.net.Uri

@HiltViewModel
class ToolCenterViewModel @Inject constructor(
    private val toolManager: ToolManager,
    private val linuxRuntime: LinuxRuntime,
    private val settingsDataStore: SettingsDataStore,
    private val logger: AppLogger,
) : ViewModel() {

    val tools: StateFlow<List<ToolEntity>> = toolManager.observeTools()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val installProgress: StateFlow<Map<String, ToolInstallProgress>> = toolManager.installProgress
    val verifications: StateFlow<Map<String, ToolVerification>> = toolManager.verifications

    private val _selectedCategory = MutableStateFlow("ALL")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _viewingLogsToolId = MutableStateFlow<String?>(null)
    val viewingLogsToolId: StateFlow<String?> = _viewingLogsToolId.asStateFlow()

    private val _toolLogs = MutableStateFlow<List<InstallLogEntity>>(emptyList())
    val toolLogs: StateFlow<List<InstallLogEntity>> = _toolLogs.asStateFlow()

    /** 刷新插件目录进行中标记（顶栏刷新按钮显示进度并防重复点击）。 */
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /** 操作失败的可观察错误（卸载失败 / 插件目录同步失败），UI 以横幅展示。 */
    private val _operationError = MutableStateFlow<String?>(null)
    val operationError: StateFlow<String?> = _operationError.asStateFlow()

    fun consumeOperationError() {
        _operationError.value = null
    }

    val localPluginImport: StateFlow<top.wkbin.taixu.core.tools.LocalPluginImportState> = toolManager.localPluginImportState

    /** 首次进入插件中心的离线包导入引导：false 表示尚未看过，需要展示遮罩引导。 */
    val importGuideShown: StateFlow<Boolean> = settingsDataStore.firstUseGuidesShown
        .map { it.contains(GUIDE_IMPORT_OFFLINE) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun markImportGuideShown() {
        viewModelScope.launch {
            settingsDataStore.markFirstUseGuideShown(GUIDE_IMPORT_OFFLINE)
        }
    }

    fun syncRegistry() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                toolManager.syncRegistry()
            } catch (e: Exception) {
                logger.w("Failed to sync tool registry: ${e.message}", e)
                _operationError.value = "刷新插件目录失败，请检查网络后重试"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun importLocalPlugin(uri: Uri) {
        val fileName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() }
            ?: "本地插件包"
        toolManager.startLocalPluginImport(uri, fileName)
    }

    fun clearLocalPluginImportState() {
        toolManager.clearLocalPluginImportState()
    }

    fun confirmLocalPluginImport() {
        toolManager.confirmLocalPluginImport()
    }

    fun cancelLocalPluginImport() {
        toolManager.cancelLocalPluginImport()
    }

    fun setCategory(category: String) {
        _selectedCategory.value = category
    }

    fun toolSource(toolId: String): String = toolManager.manifest(toolId)?.source ?: "REMOTE"

    fun installTool(toolId: String) {
        toolManager.startInstall(toolId)
    }

    fun updateTool(toolId: String) {
        toolManager.startUpdate(toolId)
    }

    fun uninstallTool(toolId: String) {
        viewModelScope.launch {
            try {
                toolManager.uninstall(toolId)
            } catch (e: Exception) {
                logger.w("Tool uninstall failed: $toolId, ${e.message}", e)
                _operationError.value = "卸载「$toolId」失败，请稍后重试；可查看日志了解详情"
            }
        }
    }

    /** 读取指定工具的全部安装日志（供「AI 自愈」入口与日志弹窗使用同一份全量数据）。 */
    fun fullToolLogs(toolId: String, onReady: (List<String>) -> Unit) {
        viewModelScope.launch {
            val logs = try {
                toolManager.observeInstallLogs(toolId).first().map { "[${it.event}] ${it.message}" }
            } catch (e: Exception) {
                logger.w("Failed to read tool logs for $toolId: ${e.message}", e)
                emptyList()
            }
            onReady(logs)
        }
    }

    fun verifyTool(toolId: String) {
        viewModelScope.launch {
            try {
                toolManager.verify(toolId)
            } catch (e: Exception) {
                logger.w("Tool verification failed: $toolId, ${e.message}", e)
            }
        }
    }

    fun clearLogs(toolId: String) {
        viewModelScope.launch {
            try {
                toolManager.clearLogs(toolId)
                _toolLogs.value = emptyList()
            } catch (e: Exception) {
                logger.w("Failed to clear logs for $toolId: ${e.message}", e)
            }
        }
    }

    fun viewLogs(toolId: String?) {
        _viewingLogsToolId.value = toolId
        if (toolId != null) {
            viewModelScope.launch {
                toolManager.observeInstallLogs(toolId).collect { logs ->
                    _toolLogs.value = logs
                }
            }
        }
    }

    init {
        syncRegistry()
        refreshInstalledStatus()
    }

    // ==================== 聚合大插件套件与子组件状态管理 ====================
    val pluginBundles: List<top.wkbin.taixu.core.model.PluginBundle> = top.wkbin.taixu.core.model.BuiltinPluginBundles.bundles

    private val _installedComponentIds = MutableStateFlow<Set<String>>(emptySet())
    val installedComponentIds: StateFlow<Set<String>> = _installedComponentIds.asStateFlow()

    private val _activeBundle = MutableStateFlow<top.wkbin.taixu.core.model.PluginBundle?>(null)
    val activeBundle: StateFlow<top.wkbin.taixu.core.model.PluginBundle?> = _activeBundle.asStateFlow()

    private val _selectedComponents = MutableStateFlow<Set<String>>(emptySet())
    val selectedComponents: StateFlow<Set<String>> = _selectedComponents.asStateFlow()

    val isInstallingComponents: StateFlow<Boolean> = toolManager.isBatchInstalling
    val componentInstallProgress: StateFlow<String?> = toolManager.bundleInstallState
    val componentInstallLog: StateFlow<List<String>> = toolManager.bundleInstallLog

    fun refreshInstalledStatus() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val installed = toolManager.probeInstalledComponents()
                _installedComponentIds.value = installed
            } catch (e: Exception) {
                logger.w("Failed to probe installed components: ${e.message}", e)
            }
        }
    }

    fun openBundleSetup(bundle: top.wkbin.taixu.core.model.PluginBundle) {
        val installed = _installedComponentIds.value
        // 只默认勾选尚未安装的必选基座；已安装的组件不再勾选
        val uninstalledRequired = bundle.components.filter { it.id !in installed && it.isRequired }.map { it.id }.toSet()
        _selectedComponents.value = uninstalledRequired
        _activeBundle.value = bundle
    }

    fun closeBundleSetup() {
        _activeBundle.value = null
    }

    fun toggleComponent(component: top.wkbin.taixu.core.model.PluginComponent) {
        val installed = _installedComponentIds.value
        if (component.id in installed) return // 已安装组件无需重复勾选
        val isUninstalledRequired = component.isRequired && component.id !in installed
        if (isUninstalledRequired) return // 尚未安装的必选基座锁定勾选
        val current = _selectedComponents.value
        _selectedComponents.value = if (component.id in current) current - component.id else current + component.id
    }

    fun installActiveBundleComponents() {
        val selected = _selectedComponents.value
        if (selected.isEmpty()) return

        // 立即关闭装配弹窗，后台静默装配并发送系统通知栏进度
        _activeBundle.value = null

        toolManager.startBackgroundBatchInstall(selected) {
            refreshInstalledStatus()
            syncRegistry()
        }
    }

    // 兼容原 devSuites 接口
    val devSuites: List<top.wkbin.taixu.core.model.PluginBundle> get() = pluginBundles
    val showSuiteDialog: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    val selectedSuites: StateFlow<Set<String>> = MutableStateFlow(emptySet<String>()).asStateFlow()
    val isInstallingSuites: StateFlow<Boolean> get() = isInstallingComponents
    val suiteInstallProgress: StateFlow<String?> get() = componentInstallProgress
    fun openSuiteDialog() {
        pluginBundles.firstOrNull()?.let { openBundleSetup(it) }
    }
    fun closeSuiteDialog() = closeBundleSetup()
    fun toggleSuite(id: String) {}
    fun installSelectedSuites() {}

    companion object {
        /** 首次引导登记 ID：插件中心「导入离线插件包」入口。 */
        const val GUIDE_IMPORT_OFFLINE = "tool_center_import_offline"
    }
}
