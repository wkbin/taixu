package top.wkbin.taixu.runtime

import android.os.Build
import android.os.StatFs
import top.wkbin.taixu.runtime.environment.boundLocalDistribution
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.core.database.StorageMountBindingRepository
import top.wkbin.taixu.core.datastore.RuntimePreferences
import top.wkbin.taixu.core.model.CpuArch
import top.wkbin.taixu.core.model.InstalledDistro
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.core.model.StorageMountBinding
import top.wkbin.taixu.runtime.proot.ProotCommandBuilder
import top.wkbin.taixu.runtime.proot.QemuCompatibilityLayout
import top.wkbin.taixu.runtime.proot.syncGuestGroups
import top.wkbin.taixu.runtime.bridge.HostBridge
import top.wkbin.taixu.runtime.pty.PtyManager
import top.wkbin.taixu.runtime.proot.ProotInstaller
import top.wkbin.taixu.runtime.rootfs.RootfsInstaller
import top.wkbin.taixu.runtime.terminal.terminalBanner
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.InteractiveLaunchSpec
import top.wkbin.taixu.runtime.shell.LinuxSession
import top.wkbin.taixu.runtime.shell.ManagedProcess
import top.wkbin.taixu.runtime.shell.ProcessType
import top.wkbin.taixu.runtime.shell.ProcessRegistry
import top.wkbin.taixu.runtime.shell.SessionConfig
import top.wkbin.taixu.runtime.shell.ShellCommand
import top.wkbin.taixu.runtime.shell.ShellExecutor
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LinuxRuntimeImpl(
    private val pathManager: RuntimePathManager,
    private val prootInstaller: ProotInstaller,
    private val rootfsInstaller: RootfsInstaller,
    private val prootCommandBuilder: ProotCommandBuilder,
    private val ptyManager: PtyManager,
    private val shellExecutor: ShellExecutor,
    private val healthChecker: RuntimeHealthChecker,
    private val processRegistry: ProcessRegistry,
    private val settingsDataStore: RuntimePreferences,
    private val storageMountBindingRepository: StorageMountBindingRepository,
    private val hostBridge: HostBridge,
    private val distroConfigurator: DistroConfigurator,
    private val logger: AppLogger,
) : LinuxRuntime {

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.NotInitialized)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val _activeDistroId = MutableStateFlow("ubuntu")
    override val activeDistroId: StateFlow<String> = _activeDistroId.asStateFlow()

    private val _installedDistros = MutableStateFlow<List<InstalledDistro>>(emptyList())
    override val installedDistros: StateFlow<List<InstalledDistro>> = _installedDistros.asStateFlow()

    private val initializeMutex = Mutex()
    private val storageActivities = StorageActivityGate()
    private val interactiveSessions = ConcurrentHashMap<LinuxSession, String>()

    override fun refreshInstalledDistros() {
        pathManager.invalidateInstalledDistrosCache()
        val ids = pathManager.listInstalledDistroIds()
        val active = _activeDistroId.value
        val list = ids.map { id ->
            val spec = DistributionCatalog.require(id)
            val size = pathManager.distroSizeBytes(id)
            val marker = pathManager.rootfsInstalledMarker(id)
            val installedTime = if (marker.exists()) marker.lastModified() else System.currentTimeMillis()
            InstalledDistro(
                id = id,
                displayName = spec.displayName,
                sizeBytes = size,
                installedAt = installedTime,
                isActive = id.equals(active, ignoreCase = true),
                packageManager = when (id.lowercase()) {
                    "alpine" -> "apk"
                    "arch" -> "pacman"
                    "fedora", "almalinux" -> "dnf"
                    "opensuse" -> "zypper"
                    else -> "apt"
                },
                statusText = if (id.equals(active, ignoreCase = true)) "当前主系统" else "已就绪",
            )
        }
        _installedDistros.value = list
    }

    override suspend fun switchActiveDistro(distroId: String): AppResult<Unit> = initializeMutex.withLock {
        val safeId = distroId.lowercase().trim()
        if (!pathManager.isDistroInstalled(safeId)) {
            return@withLock AppResult.Failure(
                AppError(ErrorCode.RUNTIME_NOT_INITIALIZED, "系统未安装或文件不完整：$safeId"),
            )
        }
        pathManager.ensureDistroDirectories(safeId)
        _activeDistroId.value = safeId
        settingsDataStore.setSelectedDistribution(safeId)
        refreshInstalledDistros()
        logger.i("Switched active Linux distro to $safeId")
        AppResult.Success(Unit)
    }

    override suspend fun installDistro(
        request: RuntimeInstallRequest,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            val distroId = request.distributionId.lowercase().trim()
            val distribution = DistributionCatalog.require(distroId)
            logger.i("Installing new distro: ${distribution.displayName} ($distroId)")
            val result = rootfsInstaller.installOci(distribution, request.registryRoute, onProgress)
            val error = result.errorOrNull()
            if (error != null) {
                return@withContext AppResult.Failure(error)
            }
            distroConfigurator.configureRootfs(distroId)
            distroConfigurator.configureDns(distroId)
            distroConfigurator.configureEnvironment(distroId)
            refreshInstalledDistros()
            logger.i("Distro $distroId installed successfully")
            AppResult.Success(Unit)
        }
    }

    override suspend fun importDistro(
        request: RuntimeInstallRequest,
        archive: File,
    ): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                updateInitializing("校验设备环境", 0.05f)
                val architecture = detectArchitecture()
                if (architecture != CpuArch.ARM64) {
                    val error = AppError(ErrorCode.UNSUPPORTED_ARCHITECTURE, "手动导入仅支持 ARM64 RootFS")
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }
                checkStorage()
                updateInitializing("导入 Linux RootFS", 0.2f, archive.name)
                pathManager.ensureDirectories()
                val prootResult = prootInstaller.install()
                prootResult.errorOrNull()?.let { error ->
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }
                val distroId = request.distributionId.lowercase().trim()
                val result = rootfsInstaller.importArchive(distroId, archive)
                result.errorOrNull()?.let { error ->
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }
                updateInitializing("配置 Linux 系统", 0.65f)
                distroConfigurator.configureRootfs(distroId)
                distroConfigurator.configureDns(distroId)
                distroConfigurator.configureEnvironment(distroId)
                createWorkspace()
                val health = healthChecker.check()
                if (!health.isHealthy) {
                    val error = AppError(ErrorCode.INSTALLATION_FAILED, "导入后的 Linux 环境健康检查失败：${health.detail.orEmpty()}")
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }
                _activeDistroId.value = distroId
                settingsDataStore.setSelectedDistribution(distroId)
                refreshInstalledDistros()
                hostBridge.start()
                _state.value = RuntimeState.Ready
                AppResult.Success(Unit)
            } catch (cancellation: CancellationException) {
                _state.value = RuntimeState.NotInitialized
                throw cancellation
            } catch (throwable: Throwable) {
                failInitialization(AppError(ErrorCode.INSTALLATION_FAILED, throwable.message ?: "导入失败", throwable))
                AppResult.Failure(AppError(ErrorCode.INSTALLATION_FAILED, throwable.message ?: "导入失败", throwable))
            }
        }
    }

    override suspend fun uninstallDistro(distroId: String): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            val safeId = distroId.lowercase().trim()
            val installed = pathManager.listInstalledDistroIds()
            if (installed.size <= 1) {
                return@withContext AppResult.Failure(
                    AppError(ErrorCode.INSTALLATION_FAILED, "不能卸载唯一的 Linux 系统，请先安装其他系统后再卸载"),
                )
            }
            closeInteractiveSessions(safeId)
            processRegistry.stopAll()
            val wasActive = _activeDistroId.value == safeId
            if (wasActive) {
                hostBridge.stop()
            }
            val res = rootfsInstaller.uninstallDistro(safeId)
            if (res is AppResult.Success) {
                if (wasActive) {
                    val nextDistro = installed.firstOrNull { it != safeId } ?: "ubuntu"
                    _activeDistroId.value = nextDistro
                    settingsDataStore.setSelectedDistribution(nextDistro)
                }
                refreshInstalledDistros()
            }
            if (wasActive && _state.value is RuntimeState.Ready && !hostBridge.isRunning.value) {
                hostBridge.start()
            }
            res
        }
    }

    override suspend fun resetSandbox(distroId: String?): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            val safeId = (distroId ?: _activeDistroId.value).lowercase().trim()
            val wasActive = _activeDistroId.value == safeId
            try {
                closeInteractiveSessions(safeId)
                processRegistry.stopAll()
                if (wasActive) {
                    hostBridge.stop()
                }
                val distribution = DistributionCatalog.require(safeId)
                val result = rootfsInstaller.resetDistro(distribution)
                if (result is AppResult.Success) {
                    distroConfigurator.configureRootfs(safeId)
                    distroConfigurator.configureDns(safeId)
                    distroConfigurator.configureEnvironment(safeId)
                    refreshInstalledDistros()
                    if (wasActive) {
                        if (!hostBridge.isRunning.value) {
                            hostBridge.start()
                        }
                        _state.value = RuntimeState.Ready
                    }
                    logger.i("Reset sandbox distro $safeId successfully")
                    AppResult.Success(Unit)
                } else {
                    val error = result.errorOrNull()
                    if (wasActive) {
                        _state.value = RuntimeState.Error(
                            error?.cause ?: IllegalStateException(error?.message ?: "Linux 环境重置失败"),
                        )
                    }
                    AppResult.Failure(error ?: AppError(ErrorCode.INSTALLATION_FAILED, "重置 Linux 环境失败"))
                }
            } catch (throwable: Throwable) {
                logger.e("Failed to reset sandbox distro $safeId", throwable)
                AppResult.Failure(AppError(ErrorCode.IO, "重置 Linux 环境失败：${throwable.message}", throwable))
            }
        }
    }

    override suspend fun initialize(request: RuntimeInstallRequest): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (_state.value is RuntimeState.Ready) {
                return@withContext AppResult.Success(Unit)
            }

            try {
                updateInitializing("detectArchitecture", 0f)
                val architecture = detectArchitecture()
                if (architecture != CpuArch.ARM64) {
                    val message = "Unsupported CPU architecture: $architecture. Phase 1 supports ARM64 only."
                    logger.e(message)
                    val error = AppError(ErrorCode.UNSUPPORTED_ARCHITECTURE, message)
                    _state.value = RuntimeState.Error(RuntimeArchitectureException(message))
                    return@withContext AppResult.Failure(error)
                }

                updateInitializing("checkStorage", 0.05f)
                checkStorage()

                updateInitializing("createDirectories", 0.1f)
                pathManager.ensureDirectories()
                pathManager.cleanupStalePtyMarkers(request.distributionId)

                updateInitializing("校验运行引擎", 0.15f, "校验 PRoot 主程序、外置 loader 与 ARM64 架构")
                val prootResult = prootInstaller.install()
                val prootError = prootResult.errorOrNull()
                if (prootError != null) {
                    failInitialization(prootError)
                    return@withContext AppResult.Failure(prootError)
                }

                val distroId = request.distributionId.lowercase().trim()
                val distribution = DistributionCatalog.require(distroId)
                updateInitializing("下载 ${distribution.displayName}", 0.2f, "通过 proot-distro 5.9.0 OCI 机制下载 linux/arm64 镜像")
                val rootfsResult = rootfsInstaller.installOci(
                    distribution,
                    request.registryRoute,
                ) { progress -> updateDownloadProgress("下载 ${distribution.displayName}", 0.2f, 0.55f, progress) }
                val rootfsError = rootfsResult.errorOrNull()
                if (rootfsError != null) {
                    failInitialization(rootfsError)
                    return@withContext AppResult.Failure(rootfsError)
                }

                updateInitializing("configureRootfs", 0.75f)
                distroConfigurator.configureRootfs(distroId)

                updateInitializing("configureDns", 0.8f)
                distroConfigurator.configureDns(distroId)

                updateInitializing("configureEnvironment", 0.85f)
                distroConfigurator.configureEnvironment(distroId)

                updateInitializing("createWorkspace", 0.9f)
                createWorkspace()

                updateInitializing("runHealthCheck", 0.95f)
                val health = healthChecker.check()
                if (!health.isHealthy) {
                    val message = "Runtime health check failed after initialization: ${health.detail.orEmpty()}"
                    logger.e(message)
                    val error = AppError(ErrorCode.INSTALLATION_FAILED, message)
                    failInitialization(error)
                    return@withContext AppResult.Failure(error)
                }

                _activeDistroId.value = distroId
                settingsDataStore.setSelectedDistribution(distroId)
                refreshInstalledDistros()

                hostBridge.start()
                _state.value = RuntimeState.Ready
                logger.i("Linux runtime initialized and ready with distro: $distroId")
                AppResult.Success(Unit)
            } catch (cancellation: CancellationException) {
                _state.value = RuntimeState.NotInitialized
                throw cancellation
            } catch (throwable: Throwable) {
                logger.e("Linux runtime initialization failed", throwable)
                _state.value = RuntimeState.Error(throwable)
                AppResult.Failure(
                    AppError(
                        code = when (throwable) {
                            is InsufficientStorageException -> ErrorCode.INSUFFICIENT_STORAGE
                            else -> ErrorCode.INSTALLATION_FAILED
                        },
                        message = throwable.message ?: "Linux runtime initialization failed",
                        cause = throwable,
                    ),
                )
            }
        }
    }

    override suspend fun restoreInstalledState(): Boolean = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
        if (_state.value is RuntimeState.Ready && hostBridge.isRunning.value) {
            return@withContext true
        }
        val selectedDistroId = runCatching { settingsDataStore.selectedDistribution.first() }.getOrDefault("ubuntu")

        if (!pathManager.isRootfsInstalled()) {
            logger.i("Restore skipped: rootfs marker or validator check failed (fresh install?)")
            return@withContext false
        }
        if (!pathManager.isProotInstalled()) {
            val message = "已安装的 Linux 环境无法恢复：PRoot 运行组件不完整（APK 内 native 库缺失或不可读）"
            logger.e("Restore failed: $message")
            _state.value = RuntimeState.Error(IllegalStateException(message))
            return@withContext false
        }

        val installedList = pathManager.listInstalledDistroIds()
        val effectiveDistro = if (selectedDistroId in installedList) selectedDistroId else installedList.firstOrNull() ?: selectedDistroId
        pathManager.ensureDistroDirectories(effectiveDistro)
        _activeDistroId.value = effectiveDistro
        refreshInstalledDistros()

        val health = try {
            healthChecker.check()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.e("Restore failed: health check threw", throwable)
            _state.value = RuntimeState.Error(throwable)
            return@withContext false
        }
        if (!health.isHealthy) {
            val message = "已安装的 Linux 环境健康检查未通过：${health.detail.orEmpty()}"
            logger.e("Restore failed: $message")
            _state.value = RuntimeState.Error(IllegalStateException(message))
            return@withContext false
        }
        runCatching { distroConfigurator.configureChinaMirrors(effectiveDistro) }
        runCatching { distroConfigurator.configureEnvironment(effectiveDistro) }
        // APK 升级可能新增或替换内置 MCP/构建脚本。已有 rootfs 不会再走 configureRootfs，
        // 因此恢复时必须幂等同步一次，避免数据库预设已切换到新脚本而沙箱内文件仍是旧版本。
        runCatching { distroConfigurator.syncAssets(effectiveDistro) }
            .onFailure { logger.w("Restore asset synchronization failed for $effectiveDistro: ${it.message}", it) }
        hostBridge.start()
        _state.value = RuntimeState.Ready
        logger.i("Linux runtime restored from disk and ready with distro: $effectiveDistro")
        true
        }
    }

    override suspend fun updateRootfs(distroId: String?): AppResult<Unit> = initializeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (_state.value !is RuntimeState.Ready) {
                return@withContext AppResult.Failure(
                    AppError(ErrorCode.INSTALLATION_FAILED, "只有已就绪的 Linux Runtime 才能更新"),
                )
            }
            try {
                val targetDistro = distroId?.lowercase()?.trim() ?: _activeDistroId.value
                processRegistry.stopAll()
                updateInitializing("更新 RootFS", 0.05f, "保留 /root 和 /opt/taixu 用户数据")
                val distribution = DistributionCatalog.require(targetDistro)
                val result = rootfsInstaller.updateOci(
                    distribution,
                    RegistryRoute.AUTO,
                ) { progress -> updateDownloadProgress("更新 ${distribution.displayName}", 0.1f, 0.65f, progress) }
                result.errorOrNull()?.let { error ->
                    _state.value = RuntimeState.Error(error.cause ?: IllegalStateException(error.message))
                    return@withContext AppResult.Failure(error)
                }

                updateInitializing("configureRootfs", 0.75f)
                distroConfigurator.configureRootfs(targetDistro)
                distroConfigurator.configureDns(targetDistro)
                distroConfigurator.configureEnvironment(targetDistro)
                updateInitializing("runHealthCheck", 0.9f)
                val health = healthChecker.check()
                if (!health.isHealthy) {
                    rootfsInstaller.rollbackPendingUpdate(targetDistro)
                    _state.value = RuntimeState.Error(
                        IllegalStateException("更新后健康检查失败：${health.detail.orEmpty()}"),
                    )
                    return@withContext AppResult.Failure(
                        AppError(
                            ErrorCode.INSTALLATION_FAILED,
                            "RootFS 更新后的健康检查失败，已恢复旧版本",
                        ),
                    )
                }
                rootfsInstaller.finalizePendingUpdate(targetDistro)
                refreshInstalledDistros()
                _state.value = RuntimeState.Ready
                AppResult.Success(Unit)
            } catch (cancellation: CancellationException) {
                val targetDistro = distroId?.lowercase()?.trim() ?: _activeDistroId.value
                rootfsInstaller.rollbackPendingUpdate(targetDistro)
                _state.value = RuntimeState.Error(IllegalStateException("RootFS 更新已取消，旧版本已恢复"))
                throw cancellation
            } catch (throwable: Throwable) {
                val targetDistro = distroId?.lowercase()?.trim() ?: _activeDistroId.value
                rootfsInstaller.rollbackPendingUpdate(targetDistro)
                logger.e("Linux runtime update failed", throwable)
                _state.value = RuntimeState.Error(throwable)
                AppResult.Failure(
                    AppError(ErrorCode.INSTALLATION_FAILED, throwable.message ?: "RootFS 更新失败", throwable),
                )
            }
        }
    }

    override suspend fun checkRootfsUpdate(distroId: String?): AppResult<RootfsUpdateInfo> =
        withContext(Dispatchers.IO) {
            if (_state.value !is RuntimeState.Ready) {
                return@withContext AppResult.Failure(
                    AppError(ErrorCode.INSTALLATION_FAILED, "只有已就绪的 Linux Runtime 才能检查更新"),
                )
            }
            val targetDistro = distroId?.lowercase()?.trim() ?: _activeDistroId.value
            val distribution = DistributionCatalog.require(targetDistro)
            runCatching { rootfsInstaller.checkForUpdate(distribution, RegistryRoute.AUTO) }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = {
                    logger.e("Linux runtime RootFS update check failed", it)
                    AppResult.Failure(AppError(ErrorCode.NETWORK, "RootFS 更新检查失败：${it.message}", it))
                },
            )
        }

    override suspend fun healthCheck(distroId: String?): RuntimeHealth = withContext(Dispatchers.IO) {
        healthChecker.check()
    }

    override suspend fun execute(command: ShellCommand, distroId: String?): CommandResult = storageActivities.activity {
        ensureReady()
        val safeDistro = boundLocalDistribution(distroId, _activeDistroId.value)
        val mounts = storageMounts()
        val execution = resolveExecutionLayout(safeDistro, command.useQemuCompatibility)
        shellExecutor.execute(
            command = prootCommandBuilder.build(
                prootBinary = pathManager.activeProotFile(),
                rootfsDir = execution.rootfsDir,
                workspaceDir = pathManager.workspaceDir,
                homeDir = pathManager.homeDir(safeDistro),
                optDir = execution.optDir,
                tmpDir = pathManager.tmpDir,
                attachmentsDir = pathManager.attachmentsDir,
                command = command,
                mounts = mounts,
                emulatorBinary = execution.emulatorBinary,
            ),
            environment = pathManager.hostProcessEnvironment(safeDistro, execution.rootfsDir),
            timeoutMs = command.timeoutMs,
            onOutput = command.onOutput,
        )
    }

    private suspend fun resolveExecutionLayout(
        distroId: String,
        useQemuCompatibility: Boolean,
    ): ExecutionLayout {
        if (!useQemuCompatibility) {
            return ExecutionLayout(pathManager.rootfsDir(distroId), null, pathManager.taixuRootDir(distroId))
        }
        check(settingsDataStore.qemuCompatibilityEnabled.first()) {
            "QEMU 兼容模式未开启，请先在设置中打开兼容开关"
        }
        val taixuRoot = pathManager.taixuRootDir(distroId)
        check(QemuCompatibilityLayout.isReady(taixuRoot)) {
            "QEMU 兼容环境未就绪，请先安装并验证 qemu-x86-64-compat 插件"
        }
        return ExecutionLayout(
            rootfsDir = QemuCompatibilityLayout.guestRootfs(taixuRoot),
            emulatorBinary = QemuCompatibilityLayout.qemuBinary(taixuRoot),
            optDir = taixuRoot,
        )
    }

    private data class ExecutionLayout(
        val rootfsDir: File,
        val emulatorBinary: File?,
        val optDir: File,
    )

    /**
     * App 进程在宿主上携带的补充组列表（含 inet/everybody/OEM 组等）。
     * android.system.Os 未暴露 getgroups，改从 /proc/self/status 的 Groups 行解析。
     */
    private fun hostSupplementaryGroupIds(): List<Int> =
        runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Groups:") }
                    ?.removePrefix("Groups:")
                    ?.split(' ', '\t')
                    ?.mapNotNullTo(mutableListOf()) { it.trim().toIntOrNull() }
                    .orEmpty()
            }
        }.getOrElse { emptyList() }

    override suspend fun startSession(config: SessionConfig, distroId: String?): LinuxSession = storageActivities.activity {
        val prepared = prepareInteractiveSession(config, distroId)
        try {
            val session = if (ptyManager.nativeAvailable) {
                ptyManager.openNative(
                    command = prepared.command,
                    hostEnvironment = prepared.hostEnvironment,
                    config = prepared.effectiveConfig,
                    cleanup = { prepared.markerFile.delete() },
                )
            } else {
                ptyManager.open(
                    command = prepared.fallbackCommand,
                    hostEnvironment = prepared.hostEnvironment,
                    config = prepared.effectiveConfig,
                    resize = { columns, rows ->
                        resizePty(prepared.markerPath, columns, rows, prepared.distroId)
                    },
                    cleanup = { prepared.markerFile.delete() },
                )
            }
            trackInteractiveSession(session, prepared.distroId)
        } catch (throwable: Throwable) {
            prepared.markerFile.delete()
            throw throwable
        }
    }

    override suspend fun buildInteractiveLaunch(
        config: SessionConfig,
        distroId: String?,
    ): InteractiveLaunchSpec = storageActivities.activity {
        val prepared = prepareInteractiveSession(config, distroId)
        // Termux JNI owns the PTY; marker file is unused for this path.
        prepared.markerFile.delete()
        // Termux JNI createSubprocess replaces the process environment entirely.
        // Merge Android env + our PRoot host vars (same shape as Android-PRoot-Engine).
        val mergedEnv = LinkedHashMap<String, String>().apply {
            System.getenv().forEach { (k, v) -> put(k, v) }
            putAll(prepared.hostEnvironment)
            putIfAbsent("HOME", pathManager.baseDir.parentFile?.absolutePath ?: "/")
            putIfAbsent("PATH", System.getenv("PATH") ?: "/system/bin:/system/xbin")
        }
        InteractiveLaunchSpec(
            executable = prepared.command.first(),
            arguments = prepared.command.toTypedArray(),
            workingDirectory = pathManager.baseDir.parentFile?.absolutePath
                ?: pathManager.baseDir.absolutePath,
            environment = mergedEnv.map { "${it.key}=${it.value}" }.toTypedArray(),
        )
    }

    private data class PreparedInteractive(
        val distroId: String,
        val effectiveConfig: SessionConfig,
        val command: List<String>,
        val fallbackCommand: List<String>,
        val hostEnvironment: Map<String, String>,
        val markerFile: File,
        val markerPath: String,
    )

    private suspend fun prepareInteractiveSession(
        config: SessionConfig,
        distroId: String?,
    ): PreparedInteractive {
        ensureReady()
        val safeDistro = boundLocalDistribution(distroId, _activeDistroId.value)
        val effectiveConfig = if (config.commandLine == "/bin/bash -i") {
            val rootfs = pathManager.rootfsDir(safeDistro)
            val hasBash = File(rootfs, "bin/bash").exists() || File(rootfs, "usr/bin/bash").exists()
            if (!hasBash) {
                config.copy(commandLine = "/bin/sh -i", allowSttyResize = true)
            } else {
                config
            }
        } else {
            config
        }
        val markerId = UUID.randomUUID().toString()
        val markerFile = File(pathManager.taixuRootDir(safeDistro), ".pty-$markerId")
        val markerPath = "/opt/taixu/.pty-$markerId"
        val mounts = storageMounts()
        runCatching {
            syncGuestGroups(
                rootfsDir = pathManager.rootfsDir(safeDistro),
                hostGroupIds = hostSupplementaryGroupIds(),
            )
        }.onFailure {
            logger.w("同步宿主补充组到 /etc/group 失败，登录时可能出现 groups 警告", it)
        }
        if (effectiveConfig.showBanner) {
            runCatching {
                File(pathManager.taixuRootDir(safeDistro), "motd").writeText(terminalBanner())
            }.onFailure {
                logger.w("写入终端横幅失败，本次会话将无横幅", it)
            }
        }
        val command = prootCommandBuilder.buildInteractive(
            prootBinary = pathManager.activeProotFile(),
            rootfsDir = pathManager.rootfsDir(safeDistro),
            workspaceDir = pathManager.workspaceDir,
            homeDir = pathManager.homeDir(safeDistro),
            optDir = pathManager.taixuRootDir(safeDistro),
            tmpDir = pathManager.tmpDir,
            attachmentsDir = pathManager.attachmentsDir,
            config = effectiveConfig,
            nativePty = true,
            mounts = mounts,
        )
        val fallbackCommand = prootCommandBuilder.buildInteractive(
            prootBinary = pathManager.activeProotFile(),
            rootfsDir = pathManager.rootfsDir(safeDistro),
            workspaceDir = pathManager.workspaceDir,
            homeDir = pathManager.homeDir(safeDistro),
            optDir = pathManager.taixuRootDir(safeDistro),
            tmpDir = pathManager.tmpDir,
            attachmentsDir = pathManager.attachmentsDir,
            config = effectiveConfig,
            ptyMarker = markerPath,
            mounts = mounts,
        )
        return PreparedInteractive(
            distroId = safeDistro,
            effectiveConfig = effectiveConfig,
            command = command,
            fallbackCommand = fallbackCommand,
            hostEnvironment = pathManager.hostProcessEnvironment(safeDistro),
            markerFile = markerFile,
            markerPath = markerPath,
        )
    }

    private suspend fun storageMounts(): List<StorageMountBinding> = buildList {
        val sharedEnabled = settingsDataStore.mountSharedStorageEnabled.first()
        val sharedDir = File("/storage/emulated/0")
        if (sharedEnabled && sharedDir.isDirectory && sharedDir.canRead()) {
            add(StorageMountBinding("system-shared", "共享存储", "/storage/emulated/0", "/sdcard", true, true))
        }
        val downloadDir = File("/storage/emulated/0/Download")
        if (!sharedEnabled && settingsDataStore.mountDownloadEnabled.first() && downloadDir.isDirectory && downloadDir.canRead()) {
            add(StorageMountBinding("system-download", "下载", "/storage/emulated/0/Download", "/sdcard/Download", true, true))
        }
        val documentsDir = File("/storage/emulated/0/Documents")
        if (!sharedEnabled && settingsDataStore.mountDocumentsEnabled.first() && documentsDir.isDirectory && documentsDir.canRead()) {
            add(StorageMountBinding("system-documents", "文档", "/storage/emulated/0/Documents", "/sdcard/Documents", true, true))
        }
        // 自定义绑定来自用户可编辑的 Room 表，历史版本可能存入越界路径。
        // 这里必须先行过滤并告警，绝不能让非法绑定流入 ProotCommandBuilder 导致会话启动崩溃；
        // ProotCommandBuilder 内的 require 仅作为防挂载逃逸的最后安全防线。
        storageMountBindingRepository.bindings.first().forEach { binding ->
            if (!binding.enabled) return@forEach
            val error = StorageMountBinding.validationError(binding)
            if (error == null) {
                add(binding)
            } else {
                logger.w("忽略非法存储挂载绑定（请在存储挂载设置中删除后重建） id=${binding.id} " +
                    "host=${binding.hostPath} guest=${binding.guestPath}: $error")
            }
        }
    }

    private suspend fun resizePty(markerPath: String, columns: Int, rows: Int, distroId: String = "ubuntu") {
        val safeColumns = columns.coerceIn(20, 400)
        val safeRows = rows.coerceIn(5, 200)
        runCatching {
            shellExecutor.execute(
                command = prootCommandBuilder.build(
                    prootBinary = pathManager.activeProotFile(),
                    rootfsDir = pathManager.rootfsDir(distroId),
                    workspaceDir = pathManager.workspaceDir,
                    homeDir = pathManager.homeDir(distroId),
                    optDir = pathManager.taixuRootDir(distroId),
                    tmpDir = pathManager.tmpDir,
                    attachmentsDir = pathManager.attachmentsDir,
                    command = ShellCommand(
                        commandLine = "if test -s '$markerPath'; then " +
                            "stty -F \"\$(cat '$markerPath')\" cols $safeColumns rows $safeRows; " +
                            "fi",
                        timeoutMs = 2_000L,
                    ),
                ),
                environment = pathManager.hostProcessEnvironment(distroId),
                timeoutMs = 2_000L,
            )
        }
    }

    override suspend fun startBackground(
        id: String,
        command: ShellCommand,
        toolId: String?,
        type: ProcessType,
        distroId: String?,
    ): ManagedProcess = storageActivities.activity {
        ensureReady()
        val mounts = storageMounts()
        processRegistry.start(
            id = id,
            command = command,
            toolId = toolId,
            type = type,
            distroId = boundLocalDistribution(distroId, _activeDistroId.value),
            mounts = mounts,
        )
    }

    override suspend fun stopBackground(id: String): Boolean = processRegistry.stop(id)

    override fun listBackground(): List<ManagedProcess> = processRegistry.list()

    override suspend fun cleanupDeadBackground(): Int = processRegistry.cleanupDeadProcesses()

    override fun observeBackgroundLogs(idOrToolId: String): Flow<List<String>> =
        processRegistry.observeLogs(idOrToolId)

    override fun getBackgroundLogs(idOrToolId: String): List<String> =
        processRegistry.getLogs(idOrToolId)

    override fun clearBackgroundLogs(idOrToolId: String) {
        processRegistry.clearLogs(idOrToolId)
    }

    override suspend fun shutdown() {
        closeInteractiveSessions()
        processRegistry.stopAll()
        hostBridge.stop()
        _state.value = RuntimeState.NotInitialized
        logger.i("Linux runtime shut down")
    }

    override suspend fun withStorageCleanup(block: suspend () -> Unit) {
        check(initializeMutex.tryLock()) { "正在安装、更新或管理环境，请完成后再清理" }
        try {
            storageActivities.cleanup {
                check(interactiveSessions.keys.none { it.isAlive } && processRegistry.list().none { it.session.isAlive }) {
                    "请先关闭终端并停止后台服务，再清理存储"
                }
                check(_state.value !is RuntimeState.Initializing) { "环境正在初始化，请稍后再清理" }
                block()
            }
        } finally {
            initializeMutex.unlock()
        }
    }

    private fun trackInteractiveSession(session: LinuxSession, distroId: String): LinuxSession {
        val tracked = object : LinuxSession {
            override val pid: Long? get() = session.pid
            override val isAlive: Boolean get() = session.isAlive
            override val output = session.output

            override suspend fun write(data: ByteArray) = session.write(data)
            override suspend fun resize(columns: Int, rows: Int) = session.resize(columns, rows)
            override suspend fun interrupt() = session.interrupt()

            override suspend fun close() {
                try {
                    session.close()
                } finally {
                    interactiveSessions.remove(this)
                }
            }
        }
        interactiveSessions[tracked] = distroId
        return tracked
    }

    private suspend fun closeInteractiveSessions(distroId: String? = null) {
        val sessions = interactiveSessions.entries
            .filter { distroId == null || it.value == distroId }
            .map { it.key }
        sessions.forEach { session ->
            runCatching { session.close() }
                .onFailure { logger.w("Failed to close interactive session before runtime cleanup: ${it.message}") }
        }
    }

    override fun rootfsPath(distroId: String?): File =
        pathManager.rootfsDir(distroId?.lowercase()?.trim() ?: _activeDistroId.value)

    override fun rootfsVersion(distroId: String?): String? =
        pathManager.rootfsVersion(distroId?.lowercase()?.trim() ?: _activeDistroId.value)

    override fun workspacePath(): File = pathManager.workspaceDir

    private fun detectArchitecture(): CpuArch {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return CpuArch.fromBuildAbi(abi)
    }

    private fun checkStorage() {
        val filesPath = pathManager.baseDir.parentFile?.absolutePath
            ?: pathManager.baseDir.absolutePath
        val statFs = StatFs(filesPath)
        val availableBytes = statFs.availableBytes
        if (availableBytes < MIN_FREE_BYTES) {
            throw InsufficientStorageException(
                "Not enough free space. Required at least ${MIN_FREE_BYTES / (1024 * 1024)} MB, " +
                    "available ${availableBytes / (1024 * 1024)} MB",
            )
        }
    }

    private fun createWorkspace() {
        pathManager.workspaceDir.mkdirs()
    }

    private fun ensureReady() {
        if (_state.value !is RuntimeState.Ready) {
            throw IllegalStateException("Linux runtime is not ready. Call initialize() first.")
        }
    }

    private fun updateInitializing(step: String, progress: Float, detail: String? = null) {
        _state.value = RuntimeState.Initializing(
            step = step,
            progress = progress.coerceIn(0f, 1f),
            detail = detail,
        )
    }

    private fun updateDownloadProgress(
        step: String,
        baseProgress: Float,
        progressSpan: Float,
        progress: DownloadProgress,
    ) {
        val fraction = progress.fraction ?: 0f
        val scaled = baseProgress + fraction * progressSpan
        val detail = if (progress.totalMegabytes != null) {
            "${progress.downloadedMegabytes} / ${progress.totalMegabytes} MB"
        } else {
            "已下载 ${progress.downloadedMegabytes} MB"
        }
        updateInitializing(step, scaled, detail)
    }

    private fun failInitialization(error: AppError) {
        _state.value = RuntimeState.Error(
            error.cause ?: IllegalStateException(error.message),
        )
    }

    private class RuntimeArchitectureException(message: String) : IllegalStateException(message)

    private class InsufficientStorageException(message: String) : IllegalStateException(message)

    companion object {
        const val MIN_FREE_BYTES = 600L * 1024L * 1024L
    }
}
