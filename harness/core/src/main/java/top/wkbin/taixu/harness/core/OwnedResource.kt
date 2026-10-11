package top.wkbin.taixu.harness.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A successful explicit close revokes ownership; failed disposal remains in the enclosing scope. */
class OwnedResource<T : Any> internal constructor(private val release: suspend (T) -> Unit) {
    private val mutex = Mutex()
    private var acquired: T? = null
    private var closed = false
    internal var ownership: AutoCloseable? = null
    val value: T get() = checkNotNull(acquired)
    internal fun acquired(value: T) { acquired = value }

    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            if (!closed) {
                acquired?.let { release(it) }
                closed = true
                ownership?.close()
            }
        }
    }
}

/** Register before allocation, drain a late result, and retain failed rollback for scope disposal. */
suspend fun <T : Any> SessionResourceScope.acquire(
    open: suspend () -> T,
    release: suspend (T) -> Unit,
): OwnedResource<T> = activity {
    val owned = OwnedResource(release)
    owned.ownership = own { owned.close() }
    try {
        owned.acquired(open())
        currentCoroutineContext().ensureActive()
        owned
    } catch (t: Throwable) {
        try { owned.close() } catch (cleanup: Throwable) { t.addSuppressed(cleanup) }
        throw t
    }
}
