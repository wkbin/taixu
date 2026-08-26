package top.wkbin.taixu.runtime

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import top.wkbin.taixu.core.common.files.SafeFileTree
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.core.database.WorkspaceRepository
import top.wkbin.taixu.core.database.WorkspaceEntity
import top.wkbin.taixu.template.ProjectTemplateEngine
import top.wkbin.taixu.runtime.shell.ShellCommand
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

enum class ProjectType {
    ANDROID,
    FLUTTER,
    REVERSE,
    GENERAL;

    val displayName: String
        get() = when (this) {
            ANDROID -> "Android"
            FLUTTER -> "Flutter"
            REVERSE -> "APK 逆向"
            GENERAL -> "通用"
        }
}

enum class ProjectImportSource {
    LOCAL_ARCHIVE,
    GITHUB,
}

enum class GitTransport {
    HTTP,
    SSH,
}

data class ProjectArchiveSource(
    val uri: String,
    val fileName: String,
)

enum class ProjectTemplate {
    EMPTY,
    ANDROID_COMPOSE,
    ANDROID_NO_ACTIVITY,
    ANDROID_XPOSED,
    FLUTTER,
    APK_REVERSE,
    GIT_IMPORT;

    val displayName: String
        get() = when (this) {
            EMPTY -> "空工程 (Empty)"
            ANDROID_COMPOSE -> "Jetpack Compose"
            ANDROID_NO_ACTIVITY -> "No Activity"
            ANDROID_XPOSED -> "Xposed"
            FLUTTER -> "Flutter 跨平台"
            APK_REVERSE -> "APK 逆向"
            GIT_IMPORT -> "从 Git 导入"
        }
}

/**
 * APK 逆向模板的安装包来源：
 * - [FromInstalledApp]：从本机已安装应用提取安装包（applicationInfo.sourceDir）；
 * - [FromFileUri]：通过系统文件管理器（SAF OpenDocument）选择 .apk 文件。
 */
sealed class ApkImportSource {
    data class FromInstalledApp(
        val packageName: String,
        val appLabel: String,
    ) : ApkImportSource()

    data class FromFileUri(
        val uri: String,
        val fileName: String,
    ) : ApkImportSource()

    val displayName: String
        get() = when (this) {
            is FromInstalledApp -> "$appLabel ($packageName)"
            is FromFileUri -> fileName
        }
}

data class WorkspaceProject(
    val name: String,
    val path: String,
    val linuxPath: String,
    val sizeBytes: Long,
    val ownsDirectory: Boolean = true,
    val projectType: ProjectType = ProjectType.GENERAL,
    val packageName: String = "",
)

enum class WorkspaceStorage { INTERNAL, SHARED }

data class WorkspaceFileItem(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val extension: String = "",
)

/** 工作区：目录在 App 私有挂载点，元数据（路径/创建时间）存 Room。 */
@Singleton
class WorkspaceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pathManager: RuntimePathManager,
    private val workspaceDao: WorkspaceRepository,
    private val fileService: WorkspaceFileService,
    private val linuxRuntime: dagger.Lazy<LinuxRuntime>,
    private val projectTemplateEngine: ProjectTemplateEngine,
) {
    constructor(
        pathManager: RuntimePathManager,
        workspaceDao: WorkspaceRepository,
        projectTemplateEngine: ProjectTemplateEngine,
    ) : this(
        ContextWrapper(null),
        pathManager,
        workspaceDao,
        WorkspaceFileService(pathManager, workspaceDao),
        dagger.Lazy<LinuxRuntime> { error("Linux runtime is unavailable in this test constructor") },
        projectTemplateEngine,
    )
    fun observeProjects(): Flow<List<WorkspaceProject>> = workspaceDao.observeAll().map { entities ->
        val projectPaths = entities.mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }.toSet()
        val filtered = entities.filter { entity ->
            val entityCanonical = runCatching { File(entity.path).canonicalPath }.getOrNull() ?: return@filter true
            projectPaths.none { otherPath ->
                otherPath != entityCanonical && otherPath.startsWith(entityCanonical + File.separator)
            }
        }
        filtered.mapNotNull(::projectFromEntity)
    }.flowOn(Dispatchers.IO)

    suspend fun listProjects(): List<WorkspaceProject> = withContext(Dispatchers.IO) {
        pathManager.workspaceDir.mkdirs()
        // 自动播种内置开箱即用示例工程，直接复用项目模板文件
        ensureBuiltinSamples()
        // 目录为准；缺失的目录从 Room 补录
        val known = workspaceDao.listAll().associateBy { it.name }
        val knownPaths = known.values.mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }.toSet()
        val directories = pathManager.workspaceDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && isValidProjectName(it.name) && !File(it, UNLINKED_MARKER).exists() }
        directories.forEach { directory ->
            if (directory.name !in known && directory.canonicalPath !in knownPaths) {
                workspaceDao.upsert(
                    WorkspaceEntity(directory.name, directory.absolutePath, System.currentTimeMillis()),
                )
            }
        }
        // 先收集所有有效实体，再过滤掉作为其他项目父目录的实体
        // （避免嵌套路径创建项目时，父目录被误注册为独立项目）
        val allEntities = workspaceDao.listAll().filter { it.name in known || File(it.path).isDirectory }
        val projectPaths = allEntities.mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }.toSet()
        val filtered = allEntities.filter { entity ->
            val entityCanonical = runCatching { File(entity.path).canonicalPath }.getOrNull() ?: return@filter true
            // 如果存在其他项目的路径以此实体路径为前缀，则此实体是父目录，应过滤掉
            projectPaths.none { otherPath ->
                otherPath != entityCanonical && otherPath.startsWith(entityCanonical + File.separator)
            }
        }
        filtered
            .filter { entity -> File(entity.path).isDirectory }
            .sortedBy { it.name.lowercase() }
            .mapNotNull(::projectFromEntity)
    }

    /**
     * 首次启动或工作区为空时，基于内置项目模板播种两套开箱即用示例工程：
     *  - `android-demo`：Jetpack Compose 基础示例
     *  - `flutter-demo`：Flutter 跨平台示例
     *
     * 直接复用 [ProjectTemplateEngine] 物化模板文件，与用户手动从模板创建工程
     * 的产物完全一致，避免在 Kotlin 源码里硬编码模板内容。
     */
    private suspend fun ensureBuiltinSamples() {
        runCatching {
            pathManager.workspaceDir.mkdirs()
            seedSampleProject(
                dirName = "android-demo",
                templateId = ProjectTemplateEngine.ANDROID_COMPOSE_ID,
                values = mapOf(
                    "projectName" to "android-demo",
                    "appName" to "TaiXu Android Demo",
                    "packageName" to "com.example.taixudemo",
                    "packagePath" to "com/example/taixudemo",
                ),
            )
            seedSampleProject(
                dirName = "flutter-demo",
                templateId = ProjectTemplateEngine.FLUTTER_ID,
                values = mapOf(
                    "projectName" to "flutter-demo",
                    "appName" to "TaiXu Flutter Demo",
                    "flutterProjectName" to "flutter_demo",
                    "packageName" to "com.example.flutterdemo",
                    "packagePath" to "com/example/flutterdemo",
                ),
            )
        }
    }

    private suspend fun seedSampleProject(
        dirName: String,
        templateId: String,
        values: Map<String, String>,
    ) {
        val projectDir = File(pathManager.workspaceDir, dirName)
        if (projectDir.exists() && projectDir.listFiles()?.isNotEmpty() == true) return
        projectDir.mkdirs()
        projectTemplateEngine.materialize(templateId, projectDir, values)
        // gradlew 需要可执行权限才能在终端直接运行
        File(projectDir, "gradlew").takeIf { it.isFile }?.setExecutable(true)
        File(projectDir, "android/gradlew").takeIf { it.isFile }?.setExecutable(true)
        workspaceDao.upsert(
            WorkspaceEntity(
                name = dirName,
                path = projectDir.absolutePath,
                createdAt = System.currentTimeMillis(),
                ownsDirectory = false,
            ),
        )
    }

    suspend fun createProject(
        name: String,
        storage: WorkspaceStorage = WorkspaceStorage.INTERNAL,
        directoryPath: String = "",
        template: ProjectTemplate = ProjectTemplate.EMPTY,
        packageName: String = "",
        apkSource: ApkImportSource? = null,
        exportApkToDownload: Boolean = false,
        gitUrl: String = "",
        templateVariables: Map<String, String> = emptyMap(),
        templateId: String = "",
        trustTemplateScripts: Boolean = false,
    ): AppResult<WorkspaceProject> = withContext(Dispatchers.IO) {
        try {
            val safeName = name.trim()
            require(isValidProjectName(safeName)) { "名称需以文字或数字开头，只能包含文字、数字、点、下划线和短横线" }
            require(safeName != "sdcard") { "sdcard 是系统共享空间保留名称" }
            check(workspaceDao.findByName(safeName) == null) { "项目已存在：$safeName" }
            pathManager.workspaceDir.mkdirs()
            val base = when (storage) {
                WorkspaceStorage.INTERNAL -> pathManager.workspaceDir
                WorkspaceStorage.SHARED -> SHARED_STORAGE_ROOT
            }
            check(base.isDirectory || base.mkdirs()) { "关联空间不可用：${base.absolutePath}" }
            val prefix = if (storage == WorkspaceStorage.INTERNAL) "/workspace/" else "/sdcard/"
            val requested = directoryPath.trim().replace('\\', '/').removePrefix(prefix).trim('/')
            val relative = requested.ifBlank { safeName }
            require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "关联目录包含无效路径" }
            val directory = File(base, relative).canonicalFile
            check(isInside(base.canonicalFile, directory) && directory != base.canonicalFile) { "关联目录越界" }
            val duplicate = workspaceDao.listAll().any {
                it.name != safeName && runCatching { File(it.path).canonicalFile == directory }.getOrDefault(false)
            }
            check(!duplicate) { "该目录已关联其他工程" }
            val existed = directory.exists()
            if (template == ProjectTemplate.GIT_IMPORT) {
                require(isValidGitUrl(gitUrl)) { "Git 仓库地址必须是 HTTPS、SSH 或 git@ 地址" }
                require(!existed || directory.isDirectory) { "Git 导入目标不是目录" }
                require(!existed || directory.listFiles().orEmpty().isEmpty()) { "Git 导入目标目录必须为空" }
                if (!existed) require(directory.mkdirs()) { "无法创建 Git 导入目录" }
            } else {
                check((existed && directory.isDirectory) || (!existed && directory.mkdirs())) { "无法创建或访问关联目录" }
                if (template != ProjectTemplate.EMPTY || templateId.isNotBlank()) {
                    check(!existed || directory.listFiles().orEmpty().isEmpty()) { "模板目标目录必须为空" }
                }
            }
            File(directory, UNLINKED_MARKER).delete()

            // 模板初始化处理
            // APK 逆向模板：包名无需用户输入，由导入的安装包决定（无则留空）
            val resolvedTemplateId = templateId.ifBlank {
                when (template) {
                    ProjectTemplate.ANDROID_COMPOSE -> ProjectTemplateEngine.ANDROID_COMPOSE_ID
                    ProjectTemplate.ANDROID_NO_ACTIVITY -> ProjectTemplateEngine.ANDROID_NO_ACTIVITY_ID
                    ProjectTemplate.ANDROID_XPOSED -> ProjectTemplateEngine.ANDROID_XPOSED_ID
                    ProjectTemplate.FLUTTER -> ProjectTemplateEngine.FLUTTER_ID
                    else -> ""
                }
            }
            val resolvedManifest = resolvedTemplateId.takeIf(String::isNotBlank)?.let(projectTemplateEngine::inspect)
            val needsPackageName = resolvedManifest?.variables.orEmpty().any {
                it.name == "packageName" || it.name == "packagePath"
            }
            var effectivePackage = ""
            if (needsPackageName) {
                val packageDefault = resolvedManifest?.variables?.firstOrNull { it.name == "packageName" }?.defaultValue.orEmpty()
                val cleanPkg = templateVariables["packageName"].orEmpty().trim()
                    .ifBlank { packageName.trim() }
                    .ifBlank { packageDefault }
                    .ifBlank { "com.example.${safeName.lowercase().filter { it.isLetterOrDigit() }}" }
                require(PACKAGE_NAME.matches(cleanPkg)) { "包名必须是合法的 Java/Kotlin 包名：$cleanPkg" }
                effectivePackage = cleanPkg
            }
            if (resolvedTemplateId.isNotBlank() && resolvedManifest != null) {
                val values = projectTemplateEngine.resolvedValues(
                    resolvedManifest,
                    builtinTemplateValues(safeName, effectivePackage, templateVariables)
                        .let { standardValues ->
                            if (needsPackageName) standardValues else standardValues - setOf("packageName", "packagePath")
                        } + mapOf(
                        "projectPath" to linuxPathFor(directory),
                        "flutterProjectName" to safeName.lowercase()
                            .replace(Regex("[^a-z0-9_]+"), "_")
                            .trim('_')
                            .ifBlank { "flutter_app" },
                    ),
                )
                val hasScripts = resolvedManifest.hooks.beforeCreate.isNotBlank() || resolvedManifest.hooks.afterCreate.isNotBlank()
                require(!hasScripts || trustTemplateScripts) { "该模板包含构造脚本，请先查看并明确授权" }
                if (resolvedManifest.hooks.beforeCreate.isNotBlank()) {
                    executeTemplateHook(
                        resolvedTemplateId,
                        "before-create",
                        resolvedManifest.hooks.beforeCreate,
                        directory,
                        values,
                    )
                }
                projectTemplateEngine.materialize(
                    resolvedTemplateId,
                    directory,
                    values,
                )
                if (resolvedManifest.hooks.afterCreate.isNotBlank()) {
                    executeTemplateHook(
                        resolvedTemplateId,
                        "after-create",
                        resolvedManifest.hooks.afterCreate,
                        directory,
                        values,
                    )
                }
            } else when (template) {
                ProjectTemplate.APK_REVERSE -> {
                    val imported = importApkForReverse(directory, safeName, apkSource)
                    effectivePackage = imported.packageName
                    if (exportApkToDownload) {
                        exportApkToDownload(imported.apkFileName, directory, safeName)
                    }
                }
                ProjectTemplate.GIT_IMPORT -> cloneGitRepository(directory, gitUrl, cleanupOnFailure = !existed)
                ProjectTemplate.EMPTY -> { /* 保持空目录 */ }
                ProjectTemplate.ANDROID_COMPOSE,
                ProjectTemplate.ANDROID_NO_ACTIVITY,
                ProjectTemplate.ANDROID_XPOSED,
                ProjectTemplate.FLUTTER,
                -> error("内置模板标识解析失败")
            }

            val ownsDirectory = storage == WorkspaceStorage.INTERNAL && !existed
            workspaceDao.upsert(
                WorkspaceEntity(safeName, directory.absolutePath, System.currentTimeMillis(), ownsDirectory),
            )
            AppResult.Success(projectFromEntity(workspaceDao.findByName(safeName)!!)!!)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: "创建项目失败", throwable))
        }
    }

    /**
     * Imports a ZIP project archive into an internal sandbox directory and records the user-selected
     * project label. Extraction always happens under /workspace and rejects path traversal entries.
     */
    suspend fun importProjectArchive(
        name: String,
        directoryPath: String = "",
        projectType: ProjectType,
        source: ProjectArchiveSource,
    ): AppResult<WorkspaceProject> = withContext(Dispatchers.IO) {
        importProject(name, directoryPath, projectType, ProjectImportSource.LOCAL_ARCHIVE) { directory, cleanupOnFailure ->
            try {
                extractProjectArchive(source, directory)
            } catch (throwable: Throwable) {
                if (cleanupOnFailure) SafeFileTree.delete(directory)
                throw throwable
            }
        }
    }

    /** Imports a GitHub repository over the explicitly selected HTTP(S) or SSH transport. */
    suspend fun importGithubProject(
        name: String,
        directoryPath: String = "",
        projectType: ProjectType,
        gitUrl: String,
        transport: GitTransport,
    ): AppResult<WorkspaceProject> = withContext(Dispatchers.IO) {
        importProject(name, directoryPath, projectType, ProjectImportSource.GITHUB) { directory, cleanupOnFailure ->
            require(isValidGitUrlForTransport(gitUrl, transport)) {
                when (transport) {
                    GitTransport.HTTP -> "HTTP 地址必须以 http:// 或 https:// 开头"
                    GitTransport.SSH -> "SSH 地址必须使用 ssh:// 或 git@host:path 格式"
                }
            }
            cloneGitRepository(directory, gitUrl, cleanupOnFailure)
        }
    }

    /** Compresses all regular project files and exports the ZIP into a SAF-selected local directory. */
    suspend fun exportProject(name: String, targetTreeUri: String): AppResult<String> = withContext(Dispatchers.IO) {
        try {
            require(isValidProjectName(name)) { "项目名称无效" }
            val entity = workspaceDao.findByName(name) ?: error("项目不存在：$name")
            val projectDir = File(entity.path).canonicalFile
            check(projectDir.isDirectory) { "项目目录不存在：$name" }

            val treeUri = Uri.parse(targetTreeUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val fileName = "$name-${System.currentTimeMillis()}.zip"
            val outputUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                "application/zip",
                fileName,
            ) ?: error("无法在所选目录创建导出文件")
            val output = context.contentResolver.openOutputStream(outputUri, "w")
                ?: error("无法写入导出文件")
            output.use { stream -> writeProjectZip(projectDir, stream) }
            AppResult.Success(fileName)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: "导出项目失败", throwable))
        }
    }

    suspend fun deleteProject(name: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            require(isValidProjectName(name)) { "项目名称无效" }
            val entity = workspaceDao.findByName(name) ?: error("项目不存在：$name")
            val directory = File(entity.path)
            if (entity.ownsDirectory && directory.exists()) {
                SafeFileTree.delete(directory)
            } else if (directory.isDirectory) {
                File(directory, UNLINKED_MARKER).writeText("unlinkedAt=${System.currentTimeMillis()}\n")
            }
            workspaceDao.delete(name)
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: "删除项目失败", throwable))
        }
    }

    suspend fun linuxWorkingDirectory(name: String): String {
        if (name == "sdcard") return "/sdcard"
        require(isValidProjectName(name)) { "项目名称无效" }
        val entity = workspaceDao.findByName(name) ?: error("项目不存在：$name")
        check(File(entity.path).isDirectory) { "关联目录不存在：$name" }
        return linuxPathFor(File(entity.path))
    }

    /** 会话关联的工作区目录；返回 null 表示不关联。 */
    suspend fun workspaceForName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        return runCatching { linuxWorkingDirectory(name) }.getOrNull()
    }

    // ==================== 项目内文件管理 API ====================

    /**
     * 该项目根目录是否位于宿主共享存储（/storage/emulated/0）之下。
     * 共享存储受 Android 11+ "所有文件访问" 权限约束：未授权时系统会过滤
     * 其他应用的文件（典型表现是只列出文件夹），需要引导用户授权。
     */
    suspend fun usesSharedStorage(projectName: String): Boolean = withContext(Dispatchers.IO) {
        if (projectName == "sdcard") return@withContext true
        val entityPath = workspaceDao.findByName(projectName)?.path ?: return@withContext false
        val canonical = runCatching { File(entityPath).canonicalPath }.getOrDefault(entityPath)
        val sharedRoot = runCatching { SHARED_STORAGE_ROOT.canonicalPath }.getOrDefault(SHARED_STORAGE_ROOT.absolutePath)
        canonical == sharedRoot || canonical.startsWith(sharedRoot + File.separator)
    }

    /** 列出项目指定相对路径下的所有文件与子目录（目录优先排序）。 */
    suspend fun listFiles(projectName: String, relativePath: String = ""): AppResult<List<WorkspaceFileItem>> =
        fileService.listFiles(projectName, relativePath)

    /** 读取文件内容（UTF-8，限制单文件最大读取大小）。 */
    suspend fun readFile(projectName: String, relativePath: String): AppResult<String> =
        fileService.readFile(projectName, relativePath)

    /** 写入文件内容（原子临时文件替换）。 */
    suspend fun writeFile(projectName: String, relativePath: String, content: String): AppResult<Unit> =
        fileService.writeFile(projectName, relativePath, content)

    /** 创建新文件（空文件）。 */
    suspend fun createFile(projectName: String, relativePath: String): AppResult<Unit> =
        fileService.createFile(projectName, relativePath)

    /** 创建新目录。 */
    suspend fun createDirectory(projectName: String, relativePath: String): AppResult<Unit> =
        fileService.createDirectory(projectName, relativePath)

    /** 重命名文件或目录。 */
    suspend fun renameItem(projectName: String, oldRelativePath: String, newName: String): AppResult<Unit> =
        fileService.renameItem(projectName, oldRelativePath, newName)

    /** 删除文件或目录。 */
    suspend fun deleteItem(projectName: String, relativePath: String): AppResult<Unit> =
        fileService.deleteItem(projectName, relativePath)

    private fun isInside(root: File, candidate: File): Boolean =
        candidate.absolutePath == root.absolutePath ||
            candidate.absolutePath.startsWith(root.absolutePath + File.separator)

    private fun projectFromEntity(entity: WorkspaceEntity): WorkspaceProject? {
        val directory = File(entity.path)
        if (!directory.isDirectory) return null
        val type = detectProjectType(directory)
        val pkg = extractPackageName(directory, type)
        return WorkspaceProject(
            name = entity.name,
            path = entity.path,
            linuxPath = linuxPathFor(directory),
            sizeBytes = sizeOf(directory),
            ownsDirectory = entity.ownsDirectory,
            projectType = type,
            packageName = pkg,
        )
    }

    private fun detectProjectType(directory: File): ProjectType {
        readProjectTypeMetadata(directory)?.let { return it }
        return when {
            File(directory, "pubspec.yaml").exists() -> ProjectType.FLUTTER
            File(directory, "settings.gradle.kts").exists() ||
                File(directory, "app/build.gradle.kts").exists() ||
                File(directory, "build.gradle").exists() -> ProjectType.ANDROID
            // APK 逆向工程：优先使用导入元数据标记，再兼容 .apk/解包目录
            File(directory, "apk-info.properties").isFile ||
                directory.listFiles().orEmpty().any { it.isFile && it.extension.equals("apk", ignoreCase = true) } ||
                (File(directory, "unpacked").isDirectory && File(directory, "unpacked").listFiles().orEmpty()
                    .any { it.isFile && it.name.startsWith("classes") && it.extension == "dex" }) -> ProjectType.REVERSE
            else -> ProjectType.GENERAL
        }
    }

    private fun extractPackageName(directory: File, type: ProjectType): String {
        return runCatching {
            when (type) {
                ProjectType.ANDROID -> {
                    val appBuild = File(directory, "app/build.gradle.kts").takeIf { it.exists() }
                        ?: File(directory, "app/build.gradle").takeIf { it.exists() }
                    val content = appBuild?.readText()
                    val namespaceMatch = Regex("""(?:namespace|applicationId)\s*=\s*["']([^"']+)["']""").find(content ?: "")
                    namespaceMatch?.groupValues?.get(1) ?: ""
                }
                ProjectType.FLUTTER -> {
                    val pubspec = File(directory, "pubspec.yaml").takeIf { it.exists() }
                    val nameMatch = Regex("""name:\s*([a-zA-Z0-9_]+)""").find(pubspec?.readText() ?: "")
                    nameMatch?.groupValues?.get(1) ?: ""
                }
                ProjectType.REVERSE -> {
                    val info = File(directory, "apk-info.properties").takeIf { it.exists() }?.readText().orEmpty()
                    Regex("""packageName\s*=\s*(.+)""").find(info)?.groupValues?.get(1)?.trim() ?: ""
                }
                ProjectType.GENERAL -> ""
            }
        }.getOrDefault("")
    }

    private fun isValidGitUrl(url: String): Boolean =
        url.trim().let { value ->
            value.startsWith("https://") || value.startsWith("http://") ||
                value.startsWith("ssh://") || Regex("^[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:.+").matches(value)
        }

    private fun isValidGitUrlForTransport(url: String, transport: GitTransport): Boolean =
        url.trim().let { value ->
            when (transport) {
                GitTransport.HTTP -> value.startsWith("https://") || value.startsWith("http://")
                GitTransport.SSH -> value.startsWith("ssh://") ||
                    Regex("^[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:.+").matches(value)
            }
        }

    private suspend fun cloneGitRepository(directory: File, url: String, cleanupOnFailure: Boolean) {
        val result = linuxRuntime.get().execute(
            ShellCommand(
                commandLine = "git clone --depth 1 -- ${shellQuote(url.trim())} ${shellQuote(linuxPathFor(directory))}",
                timeoutMs = GIT_CLONE_TIMEOUT_SECONDS * 1_000L,
            ),
        )
        check(result.isSuccess) {
            if (cleanupOnFailure) SafeFileTree.delete(directory)
            val output = (result.stderr + "\n" + result.stdout).trim().takeLast(1200)
            "Git clone 失败：${output.ifBlank { "请确认 Git 已安装、仓库地址和认证配置可用" }}"
        }
        SafeFileTree.delete(File(directory, ".git/hooks"))
    }

    private suspend fun importProject(
        name: String,
        directoryPath: String,
        projectType: ProjectType,
        source: ProjectImportSource,
        materialize: suspend (directory: File, cleanupOnFailure: Boolean) -> Unit,
    ): AppResult<WorkspaceProject> {
        return try {
            val safeName = name.trim()
            require(isValidProjectName(safeName)) { "名称需以文字或数字开头，只能包含文字、数字、点、下划线和短横线" }
            require(safeName != "sdcard") { "sdcard 是系统共享空间保留名称" }
            check(workspaceDao.findByName(safeName) == null) { "项目已存在：$safeName" }
            pathManager.workspaceDir.mkdirs()
            val base = pathManager.workspaceDir.canonicalFile
            check(base.isDirectory || base.mkdirs()) { "内部沙盒目录不可用" }
            val requested = directoryPath.trim().replace('\\', '/').removePrefix("/workspace/").trim('/')
            val relative = requested.ifBlank { safeName }
            require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "关联目录包含无效路径" }
            val directory = File(base, relative).canonicalFile
            check(isInside(base, directory) && directory != base) { "关联目录越界" }
            val duplicate = workspaceDao.listAll().any {
                runCatching { File(it.path).canonicalFile == directory }.getOrDefault(false)
            }
            check(!duplicate) { "该目录已关联其他工程" }
            val existed = directory.exists()
            require(!existed || directory.isDirectory) { "导入目标不是目录" }
            require(!existed || directory.listFiles().orEmpty().isEmpty()) { "导入目标目录必须为空" }
            if (!existed) require(directory.mkdirs()) { "无法创建导入目录" }
            materialize(directory, !existed)
            writeProjectTypeMetadata(directory, projectType, source)
            workspaceDao.upsert(
                WorkspaceEntity(safeName, directory.absolutePath, System.currentTimeMillis(), ownsDirectory = !existed),
            )
            AppResult.Success(projectFromEntity(workspaceDao.findByName(safeName)!!)!!)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: "导入项目失败", throwable))
        }
    }

    private fun extractProjectArchive(source: ProjectArchiveSource, directory: File) {
        require(source.fileName.endsWith(".zip", ignoreCase = true)) { "本地导入目前仅支持 ZIP 项目压缩包" }
        val input = context.contentResolver.openInputStream(Uri.parse(source.uri))
            ?: error("无法读取所选项目压缩包（URI 授权可能已过期，请重新选择）")
        extractProjectArchive(input, source.fileName, directory)
    }

    internal fun extractProjectArchive(input: java.io.InputStream, fileName: String, directory: File) {
        require(fileName.endsWith(".zip", ignoreCase = true)) { "本地导入目前仅支持 ZIP 项目压缩包" }
        val staging = File(directory, IMPORT_STAGING_DIRECTORY).canonicalFile
        check(isInside(directory.canonicalFile, staging)) { "导入暂存目录越界" }
        SafeFileTree.delete(staging)
        check(staging.mkdirs()) { "无法创建导入暂存目录" }
        var entryCount = 0
        var totalBytes = 0L
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    require(entryCount <= MAX_ARCHIVE_ENTRIES) { "压缩包文件数量过多" }
                    val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                    require(normalizedName.isNotBlank()) { "压缩包包含空路径" }
                    require(normalizedName.split('/').none { it == ".." }) { "压缩包包含越界路径：${entry.name}" }
                    val target = File(staging, normalizedName).canonicalFile
                    require(isInside(staging, target) && target != staging) { "压缩包包含越界路径：${entry.name}" }
                    if (entry.isDirectory) {
                        check(target.isDirectory || target.mkdirs()) { "无法创建目录：${entry.name}" }
                    } else {
                        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
                            "无法创建目录：${entry.name}"
                        }
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var entryBytes = 0L
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                totalBytes += read
                                require(entryBytes <= MAX_ARCHIVE_ENTRY_BYTES) { "压缩包单个文件过大：${entry.name}" }
                                require(totalBytes <= MAX_ARCHIVE_TOTAL_BYTES) { "压缩包解压后体积过大" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(entryCount > 0) { "项目压缩包为空" }
            val meaningful = staging.listFiles().orEmpty().filterNot { it.name == "__MACOSX" }
            val contentRoot = meaningful.singleOrNull()?.takeIf { it.isDirectory } ?: staging
            val children = contentRoot.listFiles().orEmpty().filterNot { it.name == "__MACOSX" }
            require(children.isNotEmpty()) { "项目压缩包没有可导入的文件" }
            children.forEach { child ->
                val destination = File(directory, child.name).canonicalFile
                check(isInside(directory.canonicalFile, destination) && !destination.exists()) { "导入文件冲突：${child.name}" }
                check(child.renameTo(destination)) { "无法写入导入文件：${child.name}" }
            }
        } finally {
            SafeFileTree.delete(staging)
        }
    }

    private fun writeProjectZip(projectDir: File, output: java.io.OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            projectDir.walkTopDown()
                .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .filter { it != projectDir && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .forEach { file ->
                    val relative = file.toRelativeString(projectDir).replace(File.separatorChar, '/')
                    if (relative == UNLINKED_MARKER || relative == IMPORT_STAGING_DIRECTORY) return@forEach
                    val entryName = if (file.isDirectory) "$relative/" else relative
                    zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                    if (file.isFile) file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private fun writeProjectTypeMetadata(directory: File, type: ProjectType, source: ProjectImportSource) {
        File(directory, PROJECT_METADATA_FILE).writeText(
            "type=${type.name}\nsource=${source.name}\nimportedAt=${System.currentTimeMillis()}\n",
            Charsets.UTF_8,
        )
    }

    private fun readProjectTypeMetadata(directory: File): ProjectType? = runCatching {
        val metadata = File(directory, PROJECT_METADATA_FILE)
        if (!metadata.isFile) return@runCatching null
        val typeName = metadata.useLines { lines ->
            lines.firstOrNull { it.startsWith("type=") }?.substringAfter("type=")?.trim()
        }
        ProjectType.entries.firstOrNull { it.name == typeName }
    }.getOrNull()

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun builtinTemplateValues(
        name: String,
        packageName: String,
        templateVariables: Map<String, String>,
    ): Map<String, String> = templateVariables + mapOf(
        "projectName" to name,
        "appName" to name,
        "packageName" to packageName,
        "packagePath" to packageName.replace('.', '/'),
    )

    private suspend fun executeTemplateHook(
        templateId: String,
        stage: String,
        relativePath: String,
        projectDir: File,
        values: Map<String, String>,
    ) {
        val hookFile = File(projectDir, ".taixu-template-$stage-${UUID.randomUUID()}.sh")
        try {
            hookFile.writeBytes(projectTemplateEngine.readHook(templateId, relativePath))
            hookFile.setExecutable(true)
            val result = linuxRuntime.get().execute(
                ShellCommand(
                    commandLine = "sh ${shellQuote(linuxPathFor(hookFile))}",
                    workingDirectory = linuxPathFor(projectDir),
                    environment = values.mapKeys { (name, _) -> "TAIXU_VAR_${name.uppercase()}" } +
                        ("TAIXU_PROJECT_DIR" to linuxPathFor(projectDir)),
                    timeoutMs = TEMPLATE_HOOK_TIMEOUT_MS,
                ),
            )
            check(result.isSuccess) {
                "模板脚本执行失败（$stage）：${result.stderr.ifBlank { result.stdout }.takeLast(2_000)}"
            }
        } finally {
            hookFile.delete()
        }
    }

    // ==================== APK 逆向模板 ====================

    private data class ImportedApk(
        val packageName: String,
        val apkFileName: String,
        val sourceLabel: String,
        val sourceKind: String,
    )

    /**
     * APK 逆向模板初始化：把安装包导入工程目录并做第一层"解包"。
     *
     * 产物结构（以工程名 [name] 为例）：
     * ```
     * <project>/
     * ├── <name>.apk            # 原始安装包（可直接交给 jadx / apktool / MT 管理器）
     * ├── unpacked/             # 标准 ZIP 解包产物（dex / res / assets / lib / 二进制 AXML）
     * │   ├── AndroidManifest.xml
     * │   ├── classes.dex
     * │   ├── resources.arsc
     * │   └── ...
     * ├── apk-info.properties   # 来源与元数据（工程包名读取处）
     * └── REVERSE.md            # 逆向工作流指引（jadx / apktool / MCP）
     * ```
     */
    private fun importApkForReverse(
        projectDir: File,
        name: String,
        apkSource: ApkImportSource?,
    ): ImportedApk {
        requireNotNull(apkSource) { "APK 逆向模板必须选择安装包来源（已安装应用或 APK 文件）" }
        projectDir.mkdirs()

        // 1. 解析来源并拷贝安装包到工程目录
        val apkFileName: String
        val sourceLabel: String
        val sourceKind: String
        val packageHint: String
        val apkFile: File
        when (apkSource) {
            is ApkImportSource.FromInstalledApp -> {
                val info = runCatching {
                    context.packageManager.getApplicationInfo(apkSource.packageName, 0)
                }.getOrElse { error("无法读取已安装应用信息：${apkSource.packageName}") }
                val source = File(info.sourceDir)
                require(source.isFile) { "应用安装包不可读：${source.absolutePath}" }
                apkFileName = "${apkSource.packageName}.apk"
                sourceLabel = apkSource.appLabel
                sourceKind = "installed-app"
                packageHint = apkSource.packageName
                apkFile = File(projectDir, apkFileName).canonicalFile
                check(isInside(projectDir.canonicalFile, apkFile)) { "APK 输出路径越界" }
                source.copyTo(apkFile, overwrite = true)
            }
            is ApkImportSource.FromFileUri -> {
                val uri = android.net.Uri.parse(apkSource.uri)
                val safeBase = apkSource.fileName
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
                    .trim('.')
                    .ifBlank { "target" }
                val displayName = if (safeBase.endsWith(".apk", ignoreCase = true)) safeBase else "$safeBase.apk"
                apkFileName = displayName
                sourceLabel = apkSource.displayName.ifBlank { displayName }
                sourceKind = "file-uri"
                packageHint = ""
                apkFile = File(projectDir, apkFileName).canonicalFile
                check(isInside(projectDir.canonicalFile, apkFile)) { "APK 输出路径越界" }
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("无法读取所选 APK 文件（URI 授权可能已过期，请重新选择）")
                input.use { source ->
                    apkFile.outputStream().use { output -> source.copyTo(output) }
                }
            }
        }
        require(apkFile.isFile && apkFile.length() > 0) { "安装包导入失败：$apkFileName" }

        // 2. 标准 ZIP 解包 -> unpacked/
        val unpackedDir = File(projectDir, "unpacked").apply { mkdirs() }
        unpackApk(apkFile, unpackedDir)

        // 3. 写入元数据与逆向工作流指引
        File(projectDir, "apk-info.properties").writeText(
            buildString {
                appendLine("apk=${apkFile.name}")
                appendLine("apkSizeBytes=${apkFile.length()}")
                appendLine("source=$sourceKind")
                appendLine("sourceLabel=$sourceLabel")
                appendLine("packageName=$packageHint")
                appendLine("importedAt=${System.currentTimeMillis()}")
            },
            Charsets.UTF_8,
        )
        writeReverseReadme(projectDir, name, apkFile.name, unpackedDir, sourceLabel)

        return ImportedApk(
            packageName = packageHint,
            apkFileName = apkFileName,
            sourceLabel = sourceLabel,
            sourceKind = sourceKind,
        )
    }

    /** 用标准 ZIP 读取器把 APK 逐条目解包到 [unpackedDir]（防 zip-slip 路径穿越）。 */
    private fun unpackApk(apkFile: File, unpackedDir: File) {
        val unpackedCanonical = unpackedDir.canonicalFile
        java.util.zip.ZipFile(apkFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val rawName = entry.name.replace('\\', '/')
                // 防 zip-slip：拒绝绝对路径与 .. 穿越
                if (rawName.startsWith("/") || rawName.split('/').any { it == ".." }) return@forEach
                val target = File(unpackedDir, rawName)
                if (!isInside(unpackedCanonical, target.canonicalFile)) return@forEach
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /** 生成逆向工作流指引 README，衔接太墟内置的 jadx / apktool / 逆向 MCP 能力。 */
    private fun writeReverseReadme(
        projectDir: File,
        name: String,
        apkFileName: String,
        unpackedDir: File,
        sourceLabel: String,
    ) {
        val entryCount = unpackedDir.walkTopDown().count { it.isFile }
        File(projectDir, "REVERSE.md").writeText(
            """
            # $name · APK 逆向工程

            > 来源：$sourceLabel
            > 导入时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}

            ## 工程结构

            | 路径 | 说明 |
            | :--- | :--- |
            | `$apkFileName` | 原始安装包（未改动） |
            | `unpacked/` | 第一层 ZIP 解包产物（$entryCount 个文件）：`classes.dex`、`resources.arsc`、`AndroidManifest.xml`（二进制 AXML）、`res/`、`assets/`、`lib/` 等 |
            | `apk-info.properties` | 来源与元数据 |

            ## 下一步：在太墟终端 / Agent 中继续深挖

            沙箱内已内置逆向工具链（Android & 移动全栈开发套件 或 apktool 套件装配后可用）：

            ```bash
            # 1) DEX -> Java 源码（推荐，可读性最好）
            jadx -d java-src "$apkFileName"

            # 2) 完整解包资源 + Smali（可回编译）
            apktool d "$apkFileName" -o apktool-out
            #   回编译：apktool b apktool-out -o rebuilt.apk

            # 3) 二进制清单解码（配合 apktool 产物）
            #    aapt dump badging "$apkFileName"   # 包名 / 版本 / 权限
            #    aapt dump xmltree "$apkFileName" AndroidManifest.xml
            ```

            Agent 对话中还可启用内置 **Android 逆向 MCP 服务**（`mcp_apktool`，在 MCP 设置中开启）：
            `decode_apk` / `analyze_manifest` / `extract_strings` / `search_smali` / `build_apk` / `sign_apk`。

            ## 分析关注点

            - **AndroidManifest.xml**：四大组件导出状态、权限声明、Application 类
            - **classes.dex**：核心业务逻辑（jadx 反编译后检索 URL / 密钥 / 加解密特征）
            - **lib/**：native .so（可用 IDA / 玄星逆核 SOMCP 深度分析）
            - **assets/** 与 **res/**：内置资源、配置文件、可能存在的加固壳特征

            > 提示：如果打开 `unpacked/AndroidManifest.xml` 是乱码，属正常现象（AXML 二进制格式），
            > 用 `apktool d` 或 `aapt dump xmltree` 解码即可。

            ## 识别加固壳（jadx 打开看不到真实代码时）

            若 `unpacked/classes.dex` 反编译后只有壳的 stub 加载器，说明 APK 被加固。看 `lib/` 下的 so 名最快定位厂商：

            | 特征 so | 加固厂商 |
            | :--- | :--- |
            | `libjiagu.so` / `libjiagu_art.so` | **360 加固**（入口 `com.stub.StubApp`） |
            | `libDexHelper.so` / `libSecShell.so` / `libsecexe.so` | **梆梆（SecNeo/Bangcle）**（入口 `com.secneo.apkwrapper.ApplicationWrapper`） |
            | `libshellx-super*.so` / `libtup.so` / `libexec.so` | **腾讯乐固 / 御安全**（`com.tencent.StubShell`） |
            | `libnesec.so` | **网易易盾**（`com.netease.nis.wrapper`） |
            | `ijiami.ajm` / `libexecmain.so` / `assets/ijm_lib/` | **爱加密**（入口 `s.h.e.l.l.S`） |
            | `libbaiduprotect.so` / `assets/baiduprotect*` | **百度加固** |
            | `libzuma.so` / `assets/qihoo/` | **阿里聚安全** |
            | `libddog.so` / `libchaosvmp.so` | **娜迦（Nagain，VMP 壳）** |
            | `libx3g.so` | **顶像** |
            | `libkwscmm.so` / `libkwsgmain.so` | **几维** |
            | `libnqshield.so` / `libmobisec.so` / `libkiroro.so` | 网秦 / 阿里旧版 / Kiro 等 |

            辅助判据：`assets/` 下的特征文件（`ijiami.dat`、`bangcleplugin/`、`libjiagu*`、`appsealing*`），以及 AndroidManifest 入口 `android:name`。

            ## 遇到加固壳：脱壳指引

            | 壳级别 | 特征 | 脱壳方案 |
            | :--- | :--- | :--- |
            | **一代壳**（整体 dex 加密） | jadx 只能看到 stub | **通用脱壳**：FRIDA-DEXDump（`frida -U -f 包名 -l frida-dexdump.js`）、BlackDex / FullDump（免 root 一键）、MT 管理器脱壳插件 |
            | **二代壳**（方法抽取 / 函数抽取） | 方法体运行时回填 | **主动调用脱壳**：FART / Youpk / 反射大师（定制 ROM 或 Xposed 级框架触发每个方法回填后再 dump） |
            | **VMP 壳**（指令虚拟化，如娜迦 chaosvmp） | 代码被虚拟化保护 | 极难整体脱，通常只能**动态调试关键逻辑**（Frida hook / Unidbg 模拟执行） |

            脱壳后处理：dump 出的 `classesN.dex` 可能头部/校验被破坏 → 修复 dex header 后再 `jadx` 反编译；若要改逻辑，多数壳允许在原 APK 对应 smali/so 上 patch 后重打包。
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }

    /**
     * 把工程内导入的 APK 同步导出到宿主公共下载目录（best-effort，供宿主侧 MT 管理器等外部工具直接读取；
     * Android 11+ 需已授予"所有文件访问"权限，未授权时静默跳过，不影响工程创建）。
     */
    private fun exportApkToDownload(apkFileName: String, projectDir: File, projectName: String) {
        runCatching {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists() && !downloadDir.mkdirs()) return
            val source = File(projectDir, apkFileName)
            if (!source.isFile) return
            source.copyTo(File(downloadDir, "$projectName.apk"), overwrite = true)
        }
    }

    private fun linuxPathFor(directory: File): String {
        val canonical = directory.canonicalFile
        val internal = pathManager.workspaceDir.canonicalFile
        val shared = SHARED_STORAGE_ROOT.canonicalFile
        return when {
            isInside(internal, canonical) -> "/workspace/${canonical.toRelativeString(internal).replace(File.separatorChar, '/')}"
            isInside(shared, canonical) -> "/sdcard/${canonical.toRelativeString(shared).replace(File.separatorChar, '/')}"
            else -> error("目录不在可关联空间内")
        }.trimEnd('/')
    }

    private fun sizeOf(file: File): Long = file.walkTopDown()
        .onEnter { directory -> !java.nio.file.Files.isSymbolicLink(directory.toPath()) }
        .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
        .sumOf { it.length() }

    private fun isValidProjectName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_PROJECT_NAME_LENGTH) return false
        if (!name.first().isLetterOrDigit()) return false
        return name.drop(1).all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    }

    companion object {
        private const val UNLINKED_MARKER = ".taixu-unlinked-project"
        private const val PROJECT_METADATA_FILE = ".taixu-project.properties"
        private const val IMPORT_STAGING_DIRECTORY = ".taixu-import-staging"
        private const val MAX_ARCHIVE_ENTRIES = 100_000
        private const val MAX_ARCHIVE_ENTRY_BYTES = 1024L * 1024L * 1024L
        private const val MAX_ARCHIVE_TOTAL_BYTES = 4L * 1024L * 1024L * 1024L
        private const val GIT_CLONE_TIMEOUT_SECONDS = 15 * 60L
        private const val TEMPLATE_HOOK_TIMEOUT_MS = 60_000L
        private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
        const val MAX_PROJECT_NAME_LENGTH = 64
        const val MAX_FILE_READ_BYTES = 4 * 1024 * 1024L // 4 MB
        const val MAX_FILE_WRITE_CHARS = 4 * 1024 * 1024 // 4 M 字符
        val SHARED_STORAGE_ROOT: File = File("/storage/emulated/0")
    }
}
