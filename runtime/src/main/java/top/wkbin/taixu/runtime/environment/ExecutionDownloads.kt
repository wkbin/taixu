package top.wkbin.taixu.runtime.environment

import kotlinx.coroutines.flow.Flow

data class ExecutionDownloadRequest(
    val url: String,
    val destination: String,
    val sha256: String?,
    val maxAttempts: Int,
    val maxBytes: Long,
)

sealed interface ExecutionDownloadEvent {
    data object Started : ExecutionDownloadEvent
    data class Progress(val downloadedBytes: Long, val totalBytes: Long?) : ExecutionDownloadEvent
    data object Verifying : ExecutionDownloadEvent
    data class Completed(
        val sizeBytes: Long,
        val checksumVerified: Boolean,
        val validImage: Boolean = true,
        val duplicateOf: String? = null,
    ) : ExecutionDownloadEvent
}

/**
 * Download into guest paths of the bound world, without a host File or temporary mirror.
 * Implementations enforce workspace confinement, HTTPS including redirects, byte limits,
 * retry/Range semantics and optional SHA-256 verification before emitting Completed.
 * Cancellation stops the transfer; completed data and partial transfer state stay in this world.
 */
fun interface ExecutionDownloads {
    fun download(request: ExecutionDownloadRequest): Flow<ExecutionDownloadEvent>
}
