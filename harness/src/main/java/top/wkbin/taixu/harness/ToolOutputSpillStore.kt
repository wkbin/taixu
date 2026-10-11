package top.wkbin.taixu.harness

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wkbin.taixu.runtime.environment.ExecutionArtifacts
import top.wkbin.taixu.runtime.environment.ExecutionArtifact
import top.wkbin.taixu.harness.environment.LocalExecutionArtifacts

/**
 * 超长工具输出的落盘引流（对齐 opencode tool/truncate.ts 的 spill-to-file）：
 * 截断不再有损——全量输出写入工作区 `.taixu-outputs/`，结果正文附相对路径提示，
 * 模型可用 read（offset/limit 分页）按需回读，上下文不涨而信息不丢。
 *
 * 写入失败静默降级为纯截断文案（不阻塞工具结果本身）；目录按 7 天保留期滚动清理，
 * GC 依附于每次写入触发，无独立调度。纯 JVM 可测：命名/过期判定与写入解耦。
 */
object ToolOutputSpillStore {

    /** 工作区相对目录；与子代理汇总落盘（.taixu-subagent）同级的 Harness 专用命名空间。 */
    const val DIR = ".taixu-outputs"
    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_WORKSPACE_BYTES = 64L * 1024 * 1024
    const val MAX_WORKSPACE_FILES = 256

    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val LOCKS = mutableMapOf<String, Mutex>()

    private fun lockFor(fileAccess: ExecutionArtifacts): Mutex = synchronized(LOCKS) {
        LOCKS.getOrPut(fileAccess.storageKey) { Mutex() }
    }

    /** 生成落盘文件名 `tool-<时间戳>-<工具名>-<rand>.txt`；工具名白名单化防路径注入。 */
    fun fileName(toolName: String?, now: Long, random: String = UUID.randomUUID().toString()): String {
        val safe = toolName?.trim().orEmpty()
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .take(24)
            .ifEmpty { "tool" }
        val stamp = synchronized(TIMESTAMP_FORMAT) { TIMESTAMP_FORMAT.format(Date(now)) }
        return "tool-$stamp-$safe-$random.txt"
    }

    /**
     * 过期判定按文件名内嵌时间戳（不依赖 mtime，跨备份/同步可靠）；
     * 无法解析的名字一律视为不过期（保守：宁可晚删不可误删）。
     */
    fun isExpired(fileName: String, now: Long): Boolean {
        if (!fileName.startsWith("tool-") || !fileName.endsWith(".txt")) return false
        val body = fileName.removePrefix("tool-").removeSuffix(".txt")
        val parts = body.split('-')
        if (parts.size < 3) return false
        val created = synchronized(TIMESTAMP_FORMAT) {
            TIMESTAMP_FORMAT.parse("${parts[0]}-${parts[1]}", ParsePosition(0))
        } ?: return false
        return now - created.time > RETENTION_MS
    }

    /**
     * 写入全量输出并顺带 GC，返回工作区相对路径；失败返回 null（调用方退回纯截断文案）。
     * [content] 必须已脱敏：调用方先过 SecretRedactor 再落盘，落盘文件与结果正文同一脱敏口径。
     */
    suspend fun spill(fileAccess: WorkspaceFileAccess, toolName: String?, content: String): String? =
        spill(LocalExecutionArtifacts(fileAccess), toolName, content)

    suspend fun spill(fileAccess: ExecutionArtifacts, toolName: String?, content: String): String? {
        if (content.isEmpty()) return null
        return try {
            lockFor(fileAccess).withLock {
                cleanupLocked(fileAccess)
                val bytes = content.toByteArray(Charsets.UTF_8).size.toLong()
                if (bytes > MAX_WORKSPACE_BYTES || bytes > minOf(fileAccess.maxArtifactBytes, ExecutionArtifacts.MAX_ARTIFACT_BYTES)) {
                    return@withLock null
                }
                if (!evictForBudget(fileAccess, bytes)) return@withLock null
                val name = fileName(toolName, System.currentTimeMillis(), UUID.randomUUID().toString())
                if (!fileAccess.write(name, content).isSuccess) return@withLock null
                try { fileAccess.excludeFromSourceControl() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The output remains readable if exclusion fails. */ }
                "$DIR/$name"
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            null
        }
    }

    /** 清理超过保留期的旧落盘文件；列目录/删除失败静默跳过（GC 不阻塞主流程）。 */
    suspend fun cleanup(fileAccess: WorkspaceFileAccess, now: Long = System.currentTimeMillis()) =
        cleanup(LocalExecutionArtifacts(fileAccess), now)

    suspend fun cleanup(fileAccess: ExecutionArtifacts, now: Long = System.currentTimeMillis()) {
        lockFor(fileAccess).withLock { cleanupLocked(fileAccess, now) }
    }

    private suspend fun cleanupLocked(fileAccess: ExecutionArtifacts, now: Long = System.currentTimeMillis()) {
        try {
            fileAccess.list().getOrNull().orEmpty()
                .filter { isExpired(it.name, now) || isTemporaryArtifact(it.name) }
                .forEach { fileAccess.delete(it.name) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Best-effort expiry; budget admission below still fails closed. */ }
    }

    private suspend fun evictForBudget(fileAccess: ExecutionArtifacts, incomingBytes: Long): Boolean {
        val entries = (fileAccess.list().getOrNull() ?: return false)
            .filter { it.name.startsWith("tool-") && it.name.endsWith(".txt") }
            .sortedBy(::embeddedTimestamp)
        var total = entries.sumOf { it.sizeBytes }
        var count = entries.size
        for (oldest in entries) {
            if (count < MAX_WORKSPACE_FILES && total <= MAX_WORKSPACE_BYTES - incomingBytes) return true
            if (fileAccess.delete(oldest.name)) { total -= oldest.sizeBytes; count-- }
        }
        return count < MAX_WORKSPACE_FILES && total <= MAX_WORKSPACE_BYTES - incomingBytes
    }

    private fun isTemporaryArtifact(name: String): Boolean =
        name.startsWith(".") && name.contains(".tmp-")

    private fun embeddedTimestamp(entry: ExecutionArtifact): Long {
        val body = entry.name.removePrefix("tool-")
        val parts = body.split('-')
        if (parts.size < 2) return Long.MIN_VALUE
        return synchronized(TIMESTAMP_FORMAT) {
            TIMESTAMP_FORMAT.parse("${parts[0]}-${parts[1]}", ParsePosition(0))?.time
        } ?: Long.MIN_VALUE
    }
}
