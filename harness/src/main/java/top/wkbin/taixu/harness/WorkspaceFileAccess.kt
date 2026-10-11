package top.wkbin.taixu.harness

import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.harness.text.TextReplacerEngine
import top.wkbin.taixu.harness.text.UnifiedDiffGenerator
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WorkspaceEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

/**
 * Harness 文件工具的受控访问层。
 *
 * 只允许在工作区根目录内操作：
 * - 拒绝 `..` 段、绝对路径逃逸、符号链接逃逸（canonical 校验）
 * - 写入使用临时文件 + 原子 rename，避免半写文件
 * - 读取限制单文件大小，防止把巨型文件灌进 LLM 上下文
 */
class WorkspaceFileAccess(
    private val root: File,
    private val globalRootCanonical: File? = null,
) : WorkspaceToolOperations {
    private val rootCanonical: File = root.absoluteFile.canonicalFile

    suspend fun list(path: String): AppResult<List<WorkspaceEntry>> = withContext(Dispatchers.IO) {
        try {
            val directory = resolveRequired(path)
            check(directory.isDirectory) { "不是目录：${display(path)}" }
            val entries = Files.newDirectoryStream(directory.toPath()).use { stream ->
                val iterator = stream.iterator()
                buildList {
                    while (iterator.hasNext() && size < MAX_LIST_ENTRIES) {
                        val file = iterator.next().toFile()
                        add(WorkspaceEntry(file.name, file.isDirectory, if (file.isFile) file.length() else 0L))
                    }
                }
            }
                .sortedWith(compareBy<WorkspaceEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
            AppResult.Success(entries)
        } catch (throwable: Throwable) {
            failure(path, throwable)
        }
    }

    /**
     * 读取文件内容。支持行级分页（pi 的 read 工具设计）：
     * [offset] 为 1 起始的行号，[limit] 为最多读取的行数（上限 [DEFAULT_READ_LINES]）；
     * 分页时在内容前加范围头，让模型知道文件总行数与当前窗口，便于继续翻页或精确定位。
     * 无参调用对小文件返回全文；超过 [DEFAULT_READ_LINES] 行的大文件自动限窗，
     * 并在范围头里给出续读偏移，避免模型在不知道截断的情况下基于半份内容做分析。
     */
    override suspend fun read(path: String, offset: Int?, limit: Int?): AppResult<String> = withContext(Dispatchers.IO) {
        try {
            val file = resolveRequired(path)
            check(file.isFile) { "不是文件：${display(path)}" }
            val pagedSpillRead = isPagedSpillRead(path, offset, limit)
            val readLimit = if (pagedSpillRead) MAX_HARNESS_ARTIFACT_BYTES.toLong() else MAX_READ_BYTES
            check(file.length() <= readLimit) {
                "文件过大（${file.length()} 字节，上限 ${if (pagedSpillRead) MAX_HARNESS_ARTIFACT_BYTES else MAX_READ_BYTES}）。" +
                    "请用 base 执行 grep -n 定位关键行号，再用 read(path, offset, limit) 分段精读。"
            }
            if (pagedSpillRead) {
                AppResult.Success(readPagedLines(file, path, offset!!, limit!!))
            } else {
                val content = file.readText(Charsets.UTF_8)
                val lines = content.split('\n')
                val totalLines = if (content.endsWith("\n")) lines.size - 1 else lines.size
                if (offset == null && limit == null && totalLines <= DEFAULT_READ_LINES) {
                    AppResult.Success(content)
                } else {
                    val effectiveLimit = (limit ?: DEFAULT_READ_LINES).coerceIn(1, DEFAULT_READ_LINES)
                    val start = ((offset ?: 1) - 1).coerceIn(0, totalLines)
                    val end = minOf(start + effectiveLimit, totalLines)
                    val selected = lines.subList(start, end)
                    val header = rangeHeader(path, totalLines, start, end)
                    AppResult.Success(header + selected.joinToString("\n"))
                }
            }
        } catch (throwable: Throwable) {
            failure(path, throwable)
        }
    }

    /**
     * 读取图片等二进制文件的原始字节（供沙箱多模态视觉直通使用）。
     * 与文本 [read] 分开：不做 UTF-8 解码，避免图片被当文本读出乱码或触发字符集异常。
     */
    override suspend fun readRawBytes(path: String): AppResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val file = resolveRequired(path)
            check(file.isFile) { "不是文件：${display(path)}" }
            check(file.length() <= MAX_IMAGE_BYTES) {
                "图片过大（${file.length()} 字节，上限 $MAX_IMAGE_BYTES），请先压缩或改用文字/图表描述"
            }
            AppResult.Success(file.readBytes())
        } catch (throwable: Throwable) {
            failure(path, throwable)
        }
    }

    override suspend fun write(path: String, content: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val contentBytes = content.toByteArray(Charsets.UTF_8).size
            require(contentBytes <= MAX_WRITE_BYTES) { "内容过长（$contentBytes 字节，上限 $MAX_WRITE_BYTES）" }
            val file = resolveWritable(path)
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
            try {
                temporary.writeText(content, Charsets.UTF_8)
                if (!temporary.renameTo(file)) {
                    temporary.copyTo(file, overwrite = true)
                    temporary.delete()
                }
            } finally {
                temporary.delete()
            }
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            failure(path, throwable)
        }
    }

    /**
     * Harness 内部产物写入：不经过模型 write 工具的 1 MiB 限制，但仍受工作区边界、
     * 原子替换和独立 16 MiB 配额约束。仅供日志/子代理产物引流使用。
     */
    internal suspend fun writeHarnessArtifact(path: String, content: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val bytes = content.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_HARNESS_ARTIFACT_BYTES) {
                "Harness 产物过大（${bytes.size} 字节，上限 $MAX_HARNESS_ARTIFACT_BYTES）"
            }
            val file = resolveWritable(path)
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
            try {
                temporary.outputStream().buffered().use { it.write(bytes) }
                try {
                    Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temporary.delete()
            }
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            failure(path, throwable)
        }
    }

    suspend fun edit(path: String, oldText: String, newText: String): AppResult<Unit> =
        editDetailed(path, oldText, newText).map { }

    /**
     * 多级容错编辑：Exact -> LineTrimmed -> BlockAnchor，命中后写出并按真实缩进重排。
     * 返回命中策略与文件级 Unified Diff（供 ToolResult.metadata["diff"] 前端渲染）；
     * 模型上下文仍只收到简短文本，不含 diff 正文。
     */
    override suspend fun editDetailed(path: String, oldText: String, newText: String): AppResult<WorkspaceEditOutcome> =
        withContext(Dispatchers.IO) {
            try {
                require(oldText.isNotEmpty()) { "oldText 不能为空" }
                val file = resolveWritable(path)
                check(file.isFile) { "不是文件：${display(path)}" }
                check(file.length() <= MAX_EDIT_BYTES) {
                    "文件过大（${file.length()} 字节，上限 $MAX_EDIT_BYTES），请使用 base 的流式文本工具进行定点修改"
                }
                val content = file.readText(Charsets.UTF_8)
                val result = TextReplacerEngine.replace(content, oldText, newText)
                write(path, result.updated)
                    .errorOrNull()?.let { throw it.cause ?: IllegalStateException(it.message) }
                AppResult.Success(
                    WorkspaceEditOutcome(
                        strategy = result.strategy,
                        replacements = result.replacements,
                        diff = UnifiedDiffGenerator.diff(content, result.updated, path.trim().trimStart('/')),
                    ),
                )
            } catch (throwable: Throwable) {
                failure(path, throwable)
            }
        }

    /** 解析路径并强制其位于工作区内；目录可以不存在（用于写入）。 */
    private fun resolveWritable(path: String): File {
        val resolved = resolve(path)
            ?: throw IllegalArgumentException("路径越界：${display(path)}")
        if (resolved.exists() && resolved.isDirectory) {
            throw IllegalArgumentException("是目录：${display(path)}")
        }
        return resolved
    }

    /** 解析路径，且要求目标存在。 */
    private fun resolveRequired(path: String): File {
        val resolved = resolve(path)
            ?: throw IllegalArgumentException("路径越界：${display(path)}")
        check(resolved.exists()) { "不存在：${display(path)}" }
        return resolved
    }

    /** Resolve a download destination while preserving the workspace boundary checks. */
    fun resolveDownloadDestination(path: String): File = resolveWritable(path)

    /**
     * 读取指定路径当前内容；文件不存在或无权限读取时返回 null（不抛异常）。
     * 供 checkpoint 快照在写工具触碰前捕获轮初内容。
     */
    override suspend fun previewOrNull(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val file = resolveRequired(path)
            if (!file.isFile) null else file.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    /** 文件字节数（路径越界/非文件/不存在返回 null）；供 checkpoint 预判是否跳过超大文件快照。 */
    override suspend fun fileSizeOrNull(path: String): Long? = withContext(Dispatchers.IO) {
        try {
            val file = resolveRequired(path)
            if (file.isFile) file.length() else null
        } catch (_: Throwable) {
            null
        }
    }

    /** 在工作区边界内删除文件（路径逃逸或删除失败返回 false）；文件不存在视为成功。 */
    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = resolve(path) ?: return@withContext false
            if (!file.exists()) return@withContext true
            if (file.isDirectory) return@withContext false
            file.delete()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 「提示词相关元数据」的廉价缓存戳，不读取文件正文。
     *
     * 缩放口径刻意**排除工作区根目录自身的 mtime**：目录 mtime 会随任何条目的增删改而变，
     * 而智能体每写一个新文件都会触发它，导致系统提示词逐轮重算、前缀缓存整段失效。
     * 戳只由 [relativePaths] 里那些**真正决定提示词内容**的条目构成。
     *
     * 缺失条目也计入戳（占位 -1）：否则「会话进行中才出现的标记文件」永远无法触发重算，
     * 项目类型会一直停留在首次判定结果。
     */
    suspend fun changeStamp(path: String, relativePaths: List<String>): Long = withContext(Dispatchers.IO) {
        val base = resolveRequired(path)
        var stamp = 0L
        relativePaths.forEach { relative ->
            val child = File(base, relative).canonicalFile
            val present = isInside(base, child) && child.exists()
            stamp = stamp * 31L + if (present) child.lastModified() else -1L
            if (present) stamp = stamp * 31L + child.length()
        }
        stamp
    }

    internal fun workspaceLockKey(): String = rootCanonical.absolutePath

    fun withBase(workspaceBase: String): WorkspaceFileAccess {
        val clean = workspaceBase.trim().removePrefix("/workspace/").removePrefix("/workspace").removePrefix("/")
        if (clean.isBlank()) return this
        val target = File(root, clean)
        val canonical = target.canonicalFile
        return if (isInside(rootCanonical, canonical)) WorkspaceFileAccess(canonical, rootCanonical) else this
    }

    private fun resolve(path: String): File? {
        val trimmed = path.trim().replace('\\', '/')
        val segments = trimmed.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        if (trimmed.startsWith("/") && segments.isNotEmpty() && segments.first() != "workspace") return null

        val isWorkspacePrefixed = segments.isNotEmpty() && segments.first() == "workspace"
        // 仅绝对路径 /workspace/... 才解释为全局根相对；相对路径以 workspace/ 开头时
        // 视为“当前工作区内相对路径”（strip 前缀后在当前工作区内解析），
        // 避免被重解释为全局根相对而越过当前工作区读到其他项目的文件。
        val useGlobalRoot = isWorkspacePrefixed && trimmed.startsWith("/")
        val baseRoot = if (useGlobalRoot) (globalRootCanonical ?: rootCanonical) else root
        val relative = if (isWorkspacePrefixed) segments.drop(1) else segments
        var candidate = baseRoot
        for (segment in relative) {
            candidate = File(candidate, segment)
        }
        if (candidate == baseRoot) return baseRoot
        val canonical = candidate.canonicalFile
        val allowedRoot = globalRootCanonical ?: rootCanonical
        return canonical.takeIf { isInside(allowedRoot, it) }
    }

    private fun readPagedLines(file: File, path: String, offset: Int, limit: Int): String {
        val effectiveLimit = limit.coerceIn(1, DEFAULT_READ_LINES)
        val requestedStart = (offset - 1).coerceAtLeast(0)
        val selected = ArrayList<String>(effectiveLimit)
        var totalLines = 0
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (totalLines >= requestedStart && selected.size < effectiveLimit) selected += line
                totalLines++
            }
        }
        val start = requestedStart.coerceAtMost(totalLines)
        val end = start + selected.size
        return rangeHeader(path, totalLines, start, end) + selected.joinToString("\n")
    }

    private fun rangeHeader(path: String, totalLines: Int, start: Int, end: Int): String = buildString {
        append("[文件 ${display(path)} 共 $totalLines 行，当前显示第 ${start + 1}-$end 行")
        if (end < totalLines) append("；内容未完，继续读取请用 offset=${end + 1}")
        append("]\n")
    }

    private fun isPagedSpillRead(path: String, offset: Int?, limit: Int?): Boolean =
        offset != null && limit != null && path.trim().replace('\\', '/').trimStart('/').startsWith("$DIR/")

    private fun isInside(root: File, candidate: File): Boolean =
        candidate.absolutePath == root.absolutePath ||
            candidate.absolutePath.startsWith(root.absolutePath + File.separator)

    private fun display(path: String): String =
        if (path.trim().startsWith("/")) path.trim() else "/workspace/${path.trim().trimStart('/')}"

    private fun failure(path: String, throwable: Throwable): AppResult<Nothing> =
        AppResult.Failure(AppError(ErrorCode.IO, "${display(path)}：${throwable.message}", throwable))

    companion object {
        const val MAX_READ_BYTES = 1 * 1024 * 1024L
        const val MAX_WRITE_BYTES = 1 * 1024 * 1024
        internal const val MAX_HARNESS_ARTIFACT_BYTES = 16 * 1024 * 1024
        private const val DIR = ".taixu-outputs"
        const val MAX_EDIT_BYTES = 1 * 1024 * 1024L
        /** 单张图片多模态直读上限：超过则提示压缩，避免把巨型 Base64 灌进请求体。 */
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024L
        const val MAX_LIST_ENTRIES = 5_000

        /** 无参 read 的默认行窗口；单次 limit 也以此为上限，避免单次读取灌爆工具输出。 */
        const val DEFAULT_READ_LINES = 2000
    }
}
