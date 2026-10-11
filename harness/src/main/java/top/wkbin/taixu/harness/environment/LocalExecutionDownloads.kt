package top.wkbin.taixu.harness.environment

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import top.wkbin.taixu.core.network.DownloadEvent
import top.wkbin.taixu.core.network.DownloadRequest
import top.wkbin.taixu.core.network.FileDownloader
import top.wkbin.taixu.harness.WorkspaceFileAccess
import top.wkbin.taixu.runtime.environment.*

/** Host files are confined to this local adapter, never passed to backend-neutral consumers. */
class LocalExecutionDownloads(
    private val downloader: FileDownloader,
    private val files: WorkspaceFileAccess,
) : ExecutionDownloads {
    override fun download(request: ExecutionDownloadRequest) = flow {
        val destination = files.resolveDownloadDestination(request.destination)
        downloader.download(DownloadRequest(request.url, destination, sha256 = request.sha256,
            maxAttempts = request.maxAttempts, maxBytes = request.maxBytes)).collect { event ->
            emit(when (event) {
                DownloadEvent.Started -> ExecutionDownloadEvent.Started
                is DownloadEvent.Progress -> ExecutionDownloadEvent.Progress(event.downloadedBytes, event.totalBytes)
                DownloadEvent.Verifying -> ExecutionDownloadEvent.Verifying
                is DownloadEvent.Completed -> {
                    require(event.file.canonicalFile == destination.canonicalFile) { "下载器返回了不同的目标文件" }
                    ExecutionDownloadEvent.Completed(destination.length(), !request.sha256.isNullOrBlank(),
                        !hasImageExtension(destination) || isImageMagic(destination), findDuplicateDownload(destination))
                }
            })
        }
    }.flowOn(Dispatchers.IO)

    private fun hasImageExtension(file: File): Boolean =
        file.extension.lowercase() in IMAGE_DOWNLOAD_EXTENSIONS

    private fun isImageMagic(file: File): Boolean {
        val head = ByteArray(16)
        val read = try {
            FileInputStream(file).use { it.read(head) }
        } catch (_: IOException) {
            return false
        }
        if (read < 3) return false
        val jpeg = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        val png = read >= 8 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
        val gif = head[0] == 0x47.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte()
        val bmp = head[0] == 0x42.toByte() && head[1] == 0x4D.toByte()
        val webp = read >= 12 && head[0] == 0x52.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte() && head[3] == 0x46.toByte() &&
            head[8] == 0x57.toByte() && head[9] == 0x45.toByte() && head[10] == 0x42.toByte() && head[11] == 0x50.toByte()
        return jpeg || png || gif || bmp || webp
    }

    /** 检测同目录下是否已有字节完全相同的文件（反爬占位图的典型特征：不同 URL 下载结果一模一样）。 */
    private suspend fun findDuplicateDownload(file: File): String? {
        if (file.length() <= 0L || file.length() > 32L * 1024 * 1024) return null
        val parent = file.parentFile ?: return null
        val candidates = parent.listFiles { f -> f.isFile && f.name != file.name &&
            f.canonicalFile.parentFile == parent.canonicalFile && f.length() == file.length() }
            ?: return null
        val digest = try {
            sha256Of(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } ?: return null
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            if (sha256Of(candidate) == digest) return candidate.name
        }
        return null
    }

    private suspend fun sha256Of(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private companion object { val IMAGE_DOWNLOAD_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") }
}
