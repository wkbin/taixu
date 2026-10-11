package top.wkbin.taixu.harness.mcp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.wkbin.taixu.harness.core.ResourceCleanupException

/** Concurrent disposal releases once; a failed release remains retryable. */
internal class McpResourceCleanup {
    private val mutex = Mutex()
    private var closed = false

    suspend fun close(release: suspend () -> Unit) = withContext(NonCancellable) {
        mutex.withLock {
            if (!closed) { release(); closed = true }
        }
    }
}

/** Server invalidation attempts every owner even when one subprocess cannot close. */
internal suspend fun closeMcpResources(ids: Collection<String>, close: suspend (String) -> Unit) =
    withContext(NonCancellable) {
        val failures = mutableListOf<Throwable>()
        for (id in ids) {
            try { close(id) } catch (t: Throwable) {
                failures += t
            }
        }
        if (failures.isNotEmpty()) throw ResourceCleanupException(failures)
    }
