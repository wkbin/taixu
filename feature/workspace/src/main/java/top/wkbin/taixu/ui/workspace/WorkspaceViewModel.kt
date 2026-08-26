package top.wkbin.taixu.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.taixu.runtime.WorkspaceFileItem
import top.wkbin.taixu.runtime.WorkspaceManager
import top.wkbin.taixu.runtime.WorkspaceProject
import top.wkbin.taixu.runtime.WorkspaceStorage
import top.wkbin.taixu.template.InstalledProjectTemplate
import top.wkbin.taixu.template.ProjectTemplateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import top.wkbin.taixu.feature.workspace.R
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspaceManager: WorkspaceManager,
    private val buildCoordinator: WorkspaceBuildTaskCoordinator,
    private val toolManager: top.wkbin.taixu.core.tools.ToolManager,
    private val linuxRuntime: top.wkbin.taixu.runtime.LinuxRuntime,
    private val workshopPreferences: top.wkbin.taixu.core.datastore.WorkshopPreferences,
    private val projectTemplateStore: ProjectTemplateStore,
) : ViewModel() {

    private val _projectTemplates = MutableStateFlow<List<InstalledProjectTemplate>>(emptyList())
    val projectTemplates: StateFlow<List<InstalledProjectTemplate>> = _projectTemplates.asStateFlow()
    private val _templateScriptPreview = MutableStateFlow<String?>(null)
    val templateScriptPreview: StateFlow<String?> = _templateScriptPreview.asStateFlow()

    // ==================== 聚合开发套件与子组件状态 ====================
    val pluginBundles: List<top.wkbin.taixu.core.model.PluginBundle> = top.wkbin.taixu.core.model.BuiltinPluginBundles.bundles

    private val _installedComponentIds = MutableStateFlow<Set<String>>(emptySet())
    val installedComponentIds: StateFlow<Set<String>> = _installedComponentIds.asStateFlow()

    /** Linux 沙箱是否已初始化就绪（组件探针依赖它，未就绪时探针结果不可信）。 */
    val runtimeReady: StateFlow<Boolean> = linuxRuntime.state
        .map { it is top.wkbin.taixu.core.model.RuntimeState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _activeBundleForSetup = MutableStateFlow<top.wkbin.taixu.core.model.PluginBundle?>(null)
    val activeBundleForSetup: StateFlow<top.wkbin.taixu.core.model.PluginBundle?> = _activeBundleForSetup.asStateFlow()

    private val _selectedComponents = MutableStateFlow<Set<String>>(emptySet())
    val selectedComponents: StateFlow<Set<String>> = _selectedComponents.asStateFlow()

    private val _isInstallingComponents = MutableStateFlow(false)
    val isInstallingComponents: StateFlow<Boolean> = _isInstallingComponents.asStateFlow()

    private val _suiteInstallProgress = MutableStateFlow<String?>(null)
    val suiteInstallProgress: StateFlow<String?> = _suiteInstallProgress.asStateFlow()

    init {
        refreshInstalledStatus()
        refreshProjectTemplates()
    }

    fun refreshProjectTemplates() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _projectTemplates.value = projectTemplateStore.list()
        }
    }

    fun importProjectTemplate(uri: String) {
        if (_busy.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _busy.value = true
            runCatching {
                val input = requireNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(uri))) {
                    "无法读取模板文件"
                }
                input.use(projectTemplateStore::importZip)
            }.onSuccess { template ->
                refreshProjectTemplates()
                _message.value = "模板已导入：${template.manifest.name}"
            }.onFailure { error ->
                _message.value = error.message ?: "模板导入失败"
            }
            _busy.value = false
        }
    }

    fun exportProjectTemplate(id: String, uri: String) {
        if (_busy.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _busy.value = true
            runCatching {
                val output = requireNotNull(context.contentResolver.openOutputStream(android.net.Uri.parse(uri), "wt")) {
                    "无法创建模板文件"
                }
                output.use { projectTemplateStore.exportZip(id, it) }
            }.onSuccess {
                _message.value = "模板已导出"
            }.onFailure { error ->
                _message.value = error.message ?: "模板导出失败"
            }
            _busy.value = false
        }
    }

    fun deleteProjectTemplate(id: String) {
        if (_busy.value) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _busy.value = true
            runCatching { projectTemplateStore.delete(id) }
                .onSuccess {
                    refreshProjectTemplates()
                    _message.value = "模板已删除"
                }
                .onFailure { error -> _message.value = error.message ?: "模板删除失败" }
            _busy.value = false
        }
    }

    fun showTemplateScripts(id: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _templateScriptPreview.value = runCatching {
                projectTemplateStore.hookScripts(id).joinToString("\n\n") { (path, script) ->
                    "===== $path =====\n$script"
                }.ifBlank { "该模板没有构造脚本" }
            }.getOrElse { error -> "读取脚本失败：${error.message.orEmpty()}" }
        }
    }

    fun dismissTemplateScripts() {
        _templateScriptPreview.value = null
    }

    fun refreshInstalledStatus() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                _installedComponentIds.value = toolManager.probeInstalledComponents()
            } catch (_: Exception) {}
        }
    }

    fun openDevEnvironmentDialog(suggestedSuiteId: String? = null) {
        val targetBundle = if (suggestedSuiteId != null) {
            when (suggestedSuiteId) {
                "android_sdk", "android-suite" -> pluginBundles.firstOrNull { it.id == "android-suite" }
                "flutter", "flutter-suite", "flutter-core" -> pluginBundles.firstOrNull { it.id == "android-suite" }
                else -> pluginBundles.firstOrNull { it.id == suggestedSuiteId }
            } ?: pluginBundles.first()
        } else {
            pluginBundles.first()
        }
        val installed = _installedComponentIds.value
        val initial = targetBundle.components.filter { it.isRequired || it.id in installed }.map { it.id }.toSet()
        _selectedComponents.value = if (initial.isEmpty()) targetBundle.components.map { it.id }.toSet() else initial
        _activeBundleForSetup.value = targetBundle
    }

    fun selectBundleTab(bundle: top.wkbin.taixu.core.model.PluginBundle) {
        val installed = _installedComponentIds.value
        val initial = bundle.components.filter { it.isRequired || it.id in installed }.map { it.id }.toSet()
        _selectedComponents.value = if (initial.isEmpty()) bundle.components.map { it.id }.toSet() else initial
        _activeBundleForSetup.value = bundle
    }

    fun closeDevEnvironmentDialog() {
        if (!_isInstallingComponents.value) {
            _activeBundleForSetup.value = null
        }
    }

    fun toggleComponent(component: top.wkbin.taixu.core.model.PluginComponent) {
        if (component.isRequired) return // 锁定必选
        val current = _selectedComponents.value
        _selectedComponents.value = if (component.id in current) current - component.id else current + component.id
    }

    fun installSelectedComponents() {
        val selected = _selectedComponents.value
        if (selected.isEmpty()) return

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isInstallingComponents.value = true
            try {
                toolManager.batchInstallComponents(selected).collect { event ->
                    if (event is top.wkbin.taixu.runtime.tools.InstallEvent.Progress) {
                        _suiteInstallProgress.value = event.message
                    }
                }
                refreshInstalledStatus()
                _message.value = context.getString(R.string.workspace_suite_installed)
                _activeBundleForSetup.value = null
            } catch (e: Exception) {
                _message.value = context.getString(R.string.workspace_deploy_failed, e.message.orEmpty())
            } finally {
                _isInstallingComponents.value = false
                _suiteInstallProgress.value = null
            }
        }
    }

    // 兼容原 devSuites 接口
    val devSuites: List<top.wkbin.taixu.core.model.PluginBundle> get() = pluginBundles
    val showDevSuiteDialog: StateFlow<Boolean> get() = MutableStateFlow(_activeBundleForSetup.value != null).asStateFlow()
    val selectedDevSuites: StateFlow<Set<String>> get() = _selectedComponents
    val isInstallingSuites: StateFlow<Boolean> get() = _isInstallingComponents

    // ==================== 项目列表状态 ====================
    private val _loadingProjects = MutableStateFlow(true)
    val loadingProjects: StateFlow<Boolean> = _loadingProjects.asStateFlow()

    val projects: StateFlow<List<WorkspaceProject>> = workspaceManager.observeProjects()
        .onEach { if (_loadingProjects.value) _loadingProjects.value = false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // 运行/构建状态
    val buildProgress: StateFlow<top.wkbin.taixu.runtime.build.BuildRunProgress?> = buildCoordinator.state
        .map { it?.progress }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildCoordinator.state.value?.progress)

    val activeBuildingProjectName: StateFlow<String?> = buildCoordinator.state
        .map { state -> state?.takeIf { it.progress.isRunning }?.project?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _isBuildDialogVisible = MutableStateFlow<Boolean>(false)
    val isBuildDialogVisible: StateFlow<Boolean> = _isBuildDialogVisible.asStateFlow()

    /** 工坊已登记的 Android 签名（Release 构建时选择）。 */
    val keystores: StateFlow<List<top.wkbin.taixu.core.datastore.WorkshopKeystore>> = workshopPreferences.keystores
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ==================== 文件浏览器状态 ====================
    private val _selectedProject = MutableStateFlow<String?>(null)
    val selectedProject: StateFlow<String?> = _selectedProject.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _fileItems = MutableStateFlow<List<WorkspaceFileItem>>(emptyList())
    val fileItems: StateFlow<List<WorkspaceFileItem>> = _fileItems.asStateFlow()

    private val _loadingFiles = MutableStateFlow(false)
    val loadingFiles: StateFlow<Boolean> = _loadingFiles.asStateFlow()

    /**
     * 当前浏览的项目位于宿主共享存储（/storage/emulated/0），
     * 且缺少"所有文件访问"权限——此时系统会过滤其他应用的文件（只显示文件夹），
     * 文件浏览器需要展示授权引导横幅。
     */
    private val _sharedStorageAccessLimited = MutableStateFlow(false)
    val sharedStorageAccessLimited: StateFlow<Boolean> = _sharedStorageAccessLimited.asStateFlow()

    // ==================== 代码编辑器状态 ====================
    private val _openedFilePath = MutableStateFlow<String?>(null)
    val openedFilePath: StateFlow<String?> = _openedFilePath.asStateFlow()

    private val _openedFileExtension = MutableStateFlow("")
    val openedFileExtension: StateFlow<String> = _openedFileExtension.asStateFlow()

    private val _fileContent = MutableStateFlow("")
    val fileContent: StateFlow<String> = _fileContent.asStateFlow()

    private var originalContent: String = ""

    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    init {
        refresh()
    }

    fun clearMessage() {
        _message.value = null
    }

    fun showBuildDialog() {
        _isBuildDialogVisible.value = true
    }

    fun hideBuildDialog() {
        _isBuildDialogVisible.value = false
    }

    fun dismissBuildProgress() {
        _isBuildDialogVisible.value = false
        buildCoordinator.dismiss()
    }

    fun refresh() {
        viewModelScope.launch {
            workspaceManager.listProjects()
        }
    }

    // ==================== 项目级操作 ====================

    fun create(
        name: String,
        storage: WorkspaceStorage,
        directoryPath: String,
        template: top.wkbin.taixu.runtime.ProjectTemplate = top.wkbin.taixu.runtime.ProjectTemplate.EMPTY,
        packageName: String = "",
        apkSource: top.wkbin.taixu.runtime.ApkImportSource? = null,
        exportApkToDownload: Boolean = false,
        gitUrl: String = "",
        templateVariables: Map<String, String> = emptyMap(),
        templateId: String = "",
        trustTemplateScripts: Boolean = false,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.createProject(
                name,
                storage,
                directoryPath,
                template,
                packageName,
                apkSource,
                exportApkToDownload,
                gitUrl,
                templateVariables,
                templateId,
                trustTemplateScripts,
            )
            _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_project_created)
            if (result.isSuccess) workspaceManager.listProjects()
            _busy.value = false
        }
    }

    fun importLocalProject(
        name: String,
        directoryPath: String,
        projectType: top.wkbin.taixu.runtime.ProjectType,
        source: top.wkbin.taixu.runtime.ProjectArchiveSource,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.importProjectArchive(name, directoryPath, projectType, source)
            _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_project_imported)
            if (result.isSuccess) workspaceManager.listProjects()
            _busy.value = false
        }
    }

    fun importGithubProject(
        name: String,
        directoryPath: String,
        projectType: top.wkbin.taixu.runtime.ProjectType,
        gitUrl: String,
        transport: top.wkbin.taixu.runtime.GitTransport,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.importGithubProject(name, directoryPath, projectType, gitUrl, transport)
            _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_project_imported)
            if (result.isSuccess) workspaceManager.listProjects()
            _busy.value = false
        }
    }

    fun exportProject(project: WorkspaceProject, targetTreeUri: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.exportProject(project.name, targetTreeUri)
            _message.value = result.errorOrNull()?.message
                ?: context.getString(R.string.workspace_project_exported, result.getOrNull().orEmpty())
            _busy.value = false
        }
    }

    fun runProject(
        project: WorkspaceProject,
        buildType: top.wkbin.taixu.runtime.build.WorkshopBuildType = top.wkbin.taixu.runtime.build.WorkshopBuildType.DEBUG,
        keystore: top.wkbin.taixu.core.datastore.WorkshopKeystore? = null,
    ) {
        if (buildCoordinator.state.value?.progress?.isRunning == true) {
            _isBuildDialogVisible.value = true
            return
        }
        _isBuildDialogVisible.value = true
        buildCoordinator.start(project, buildType, keystore)
    }

    /** 用户主动取消编译任务 */
    fun cancelBuild() {
        buildCoordinator.cancel()
    }

    fun launchInstaller(apkPath: String) {
        buildCoordinator.launchPackageInstaller(apkPath)
    }

    fun delete(name: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.deleteProject(name)
            _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_project_deleted)
            if (result.isSuccess) workspaceManager.listProjects()
            _busy.value = false
        }
    }

    // ==================== 文件浏览器操作 ====================

    fun loadExplorer(projectName: String, relativePath: String = "") {
        _selectedProject.value = projectName
        _currentPath.value = relativePath.trim().removePrefix("/")
        refreshDirectory()
    }

    fun navigateToDirectory(relativePath: String) {
        _currentPath.value = relativePath.trim().removePrefix("/")
        refreshDirectory()
    }

    fun navigateUp() {
        val current = _currentPath.value
        if (current.isBlank()) return
        val parent = current.substringBeforeLast('/', "")
        _currentPath.value = parent
        refreshDirectory()
    }

    fun refreshDirectory() {
        val proj = _selectedProject.value ?: return
        val path = _currentPath.value
        viewModelScope.launch {
            _loadingFiles.value = true
            refreshSharedStorageAccessLimited(proj)
            val result = workspaceManager.listFiles(proj, path)
            if (result.isSuccess) {
                _fileItems.value = result.getOrNull().orEmpty()
            } else {
                _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_read_directory_failed)
            }
            _loadingFiles.value = false
        }
    }

    /** 从系统授权页返回后调用：重新评估权限状态并刷新目录。 */
    fun refreshAfterPermissionReturn() {
        val proj = _selectedProject.value ?: return
        viewModelScope.launch {
            refreshSharedStorageAccessLimited(proj)
            refreshDirectory()
        }
    }

    private suspend fun refreshSharedStorageAccessLimited(projectName: String) {
        _sharedStorageAccessLimited.value =
            workspaceManager.usesSharedStorage(projectName) && !hasSharedStorageAccess()
    }

    private fun hasSharedStorageAccess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    fun createFile(name: String) {
        val proj = _selectedProject.value ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val fullRelative = if (_currentPath.value.isBlank()) trimmed else "${_currentPath.value}/$trimmed"
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.createFile(proj, fullRelative)
            if (result.isSuccess) {
                _message.value = context.getString(R.string.workspace_file_created, trimmed)
                refreshDirectory()
            } else {
                _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_create_file_failed)
            }
            _busy.value = false
        }
    }

    fun createDirectory(name: String) {
        val proj = _selectedProject.value ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val fullRelative = if (_currentPath.value.isBlank()) trimmed else "${_currentPath.value}/$trimmed"
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.createDirectory(proj, fullRelative)
            if (result.isSuccess) {
                _message.value = context.getString(R.string.workspace_directory_created, trimmed)
                refreshDirectory()
            } else {
                _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_create_directory_failed)
            }
            _busy.value = false
        }
    }

    fun renameItem(oldRelativePath: String, newName: String) {
        val proj = _selectedProject.value ?: return
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.renameItem(proj, oldRelativePath, trimmed)
            if (result.isSuccess) {
                _message.value = context.getString(R.string.workspace_renamed, trimmed)
                refreshDirectory()
            } else {
                _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_rename_failed)
            }
            _busy.value = false
        }
    }

    fun deleteItem(relativePath: String) {
        val proj = _selectedProject.value ?: return
        viewModelScope.launch {
            _busy.value = true
            val result = workspaceManager.deleteItem(proj, relativePath)
            if (result.isSuccess) {
                _message.value = context.getString(R.string.workspace_deleted)
                refreshDirectory()
            } else {
                _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_delete_failed)
            }
            _busy.value = false
        }
    }

    // ==================== 编辑器操作 ====================

    fun openFile(projectName: String, relativePath: String) {
        _selectedProject.value = projectName
        _openedFilePath.value = relativePath
        val ext = relativePath.substringAfterLast('.', "")
        _openedFileExtension.value = ext
        viewModelScope.launch {
            _loadingFiles.value = true
            val result = workspaceManager.readFile(projectName, relativePath)
            if (result.isSuccess) {
                val content = result.getOrNull().orEmpty()
                originalContent = content
                _fileContent.value = content
                _isDirty.value = false
            } else {
                _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_open_file_failed)
            }
            _loadingFiles.value = false
        }
    }

    fun onContentChanged(newText: String) {
        _fileContent.value = newText
        _isDirty.value = newText != originalContent
    }

    fun resetContent() {
        _fileContent.value = originalContent
        _isDirty.value = false
    }

    fun saveFile(onSuccess: (() -> Unit)? = null) {
        val proj = _selectedProject.value ?: return
        val path = _openedFilePath.value ?: return
        val text = _fileContent.value
        viewModelScope.launch {
            _isSaving.value = true
            val result = workspaceManager.writeFile(proj, path, text)
            if (result.isSuccess) {
                originalContent = text
                _isDirty.value = false
                _message.value = context.getString(R.string.workspace_file_saved)
                onSuccess?.invoke()
            } else {
                _message.value = result.errorOrNull()?.message ?: context.getString(R.string.workspace_save_failed)
            }
            _isSaving.value = false
        }
    }

    fun closeFile() {
        _openedFilePath.value = null
        _fileContent.value = ""
        originalContent = ""
        _isDirty.value = false
    }
}
