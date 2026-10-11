package top.wkbin.taixu.harness

import java.util.Locale
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.taixu.runtime.environment.ExecutionDownloadEvent
import top.wkbin.taixu.runtime.environment.ExecutionDownloadRequest
import top.wkbin.taixu.runtime.environment.ExecutionDownloads
import top.wkbin.taixu.harness.environment.LocalExecutionDownloads
import top.wkbin.taixu.core.network.FileDownloader
import top.wkbin.taixu.harness.core.ToolBackend

data class DownloadToolRequest(
    val args: JsonObject,
    val sessionId: String,
    val workspace: String,
    val progressReporter: (suspend (String) -> Unit)?,
)

/**
 * Agent 工具后端：HTTPS 文件下载（download 工具）。
 *
 * 职责边界：
 * - 调用当前环境下载接口（ExecutionDownloads）、上报进度并解释下载校验结果。
 * - 输出截断、脱敏等横切关注点由 ToolExecutor 管道层处理。
 */
class DownloadToolBackend(
    private val fileDownloader: FileDownloader,
    private val fileAccess: WorkspaceFileAccess,
    private val mutationSnapshots: WorkspaceMutationSnapshots,
    private val environments: top.wkbin.taixu.harness.environment.SessionExecutionEnvironments? = null,
) : ToolBackend<DownloadToolRequest, Pair<Boolean, String>> {

    override suspend fun execute(request: DownloadToolRequest): Pair<Boolean, String> {
        val environment = environments?.environment(request.sessionId, request.workspace)
        val activeFileAccess = environment?.files
            ?: if (request.workspace.isNotBlank()) fileAccess.withBase(request.workspace) else fileAccess
        val downloads = if (environment != null) environment.downloads
            ?: return false to "当前执行环境未提供下载适配器，请在该环境中用 base 下载。"
        else LocalExecutionDownloads(fileDownloader, fileAccess.withBase(request.workspace))
        if (request.args["destination"]?.jsonPrimitive?.content?.trim()?.isNotBlank() == true) {
            mutationSnapshots.before(request.sessionId, activeFileAccess, JsonArgs.requireString(request.args, "destination"))
        }
        return executeDownload(request.args, downloads, request.progressReporter)
    }

    private suspend fun executeDownload(
        args: JsonObject,
        downloads: ExecutionDownloads,
        progressReporter: (suspend (String) -> Unit)?,
    ): Pair<Boolean, String> {
        val url = JsonArgs.requireString(args, "url")
        require(url.startsWith("https://", ignoreCase = true)) { "下载地址必须使用 HTTPS" }
        val destinationPath = JsonArgs.requireString(args, "destination")
        val sha256 = args["sha256"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val maxAttempts = JsonArgs.optionalLong(args, "max_attempts", DEFAULT_DOWNLOAD_ATTEMPTS, 1L, MAX_DOWNLOAD_ATTEMPTS).toInt()
        val maxBytes = JsonArgs.optionalLong(args, "max_bytes", DEFAULT_DOWNLOAD_MAX_BYTES, 1L, MAX_DOWNLOAD_MAX_BYTES)
        var latestProgress: ExecutionDownloadEvent.Progress? = null
        var completed: ExecutionDownloadEvent.Completed? = null
        val startedAt = System.currentTimeMillis()
        var lastReportedAt = 0L
        downloads.download(
            ExecutionDownloadRequest(
                url = url,
                destination = destinationPath,
                sha256 = sha256,
                maxAttempts = maxAttempts,
                maxBytes = maxBytes,
            ),
        ).collect { event ->
            when (event) {
                is ExecutionDownloadEvent.Progress -> {
                    latestProgress = event
                    val now = System.currentTimeMillis()
                    if (progressReporter != null && (now - lastReportedAt >= PROGRESS_REPORT_INTERVAL_MS || event.totalBytes != null && event.downloadedBytes == event.totalBytes)) {
                        lastReportedAt = now
                        progressReporter(formatDownloadProgress(event, startedAt))
                    }
                }
                ExecutionDownloadEvent.Verifying -> {
                    progressReporter?.invoke("正在校验下载文件 SHA-256…")
                }
                is ExecutionDownloadEvent.Completed -> completed = event
                ExecutionDownloadEvent.Started -> Unit
            }
        }
        val result = completed ?: return false to "下载器未确认下载完成。"
        require(result.sizeBytes in 0L..maxBytes) { "下载文件超过大小限制" }
        require(sha256 == null || result.checksumVerified) { "下载器未确认 SHA-256 校验成功" }
        val size = result.sizeBytes
        if (!result.validImage) {
            return false to buildString {
                append("下载失败：目标应为图片，但内容不是有效的图片格式（JPEG/PNG/GIF/WebP/BMP）。")
                append("\n来源：").append(url)
                append("\n大小：").append(size).append(" bytes")
                append("\n常见原因：图床反爬/防盗链返回了占位图或错误页，或链接已过期。")
                append("\n建议：更换图源（换站点/换域名）或稍后重试，不要用相同 URL 原样重试。")
            }
        }
        val duplicateOf = result.duplicateOf
        val body = buildString {
            append("下载完成：").append(destinationPath)
            append("\n大小：").append(size).append(" bytes")
            latestProgress?.totalBytes?.let { append(" / ").append(it).append(" bytes") }
            append("\n特性：HTTPS、HTTP Range 断点续传、自动重试（最多 ").append(maxAttempts).append(" 次）")
            if (result.checksumVerified) append("\nSHA-256：已校验")
            append("\n说明：当前下载器是单连接续传，不是多线程分片下载。")
            if (duplicateOf != null) {
                append("\n\n警告：本次下载内容与工作区已有文件 ").append(duplicateOf)
                append(" 完全相同（SHA-256 一致），疑似图床反爬占位图。请立即更换图源（换站点/换域名），")
                append("不要继续从同一图床高频下载，也不要重复下载相同的 URL。")
            }
        }
        return true to body
    }

    private fun formatDownloadProgress(event: ExecutionDownloadEvent.Progress, startedAt: Long): String {
        val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        val speed = event.downloadedBytes * 1000L / elapsedMs
        val downloaded = formatBytes(event.downloadedBytes)
        val total = event.totalBytes?.let(::formatBytes)
        val percent = event.totalBytes?.takeIf { it > 0L }?.let { event.downloadedBytes * 100 / it }
        return buildString {
            append("下载中：").append(downloaded)
            if (total != null) {
                append(" / ").append(total)
                percent?.let { append(" (").append(it.coerceIn(0L, 100L)).append("%)") }
            }
            append(" · ").append(formatBytes(speed)).append("/s")
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index += 1
        }
        return if (value >= 100 || value % 1.0 == 0.0) "${value.toInt()} ${units[index]}" else "${"%.1f".format(Locale.US, value)} ${units[index]}"
    }

    companion object {
        const val DEFAULT_DOWNLOAD_ATTEMPTS = 3L
        const val MAX_DOWNLOAD_ATTEMPTS = 10L
        const val DEFAULT_DOWNLOAD_MAX_BYTES = 1024L * 1024L * 1024L
        const val MAX_DOWNLOAD_MAX_BYTES = 4L * 1024L * 1024L * 1024L
        const val PROGRESS_REPORT_INTERVAL_MS = 250L
    }
}
