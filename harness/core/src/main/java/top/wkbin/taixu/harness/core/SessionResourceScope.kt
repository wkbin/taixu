package top.wkbin.taixu.harness.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Admission closes first, activities drain second, resources release last (reverse acquisition). */
class SessionResourceScope {
    private val lock = Any()
    private val closeMutex = Mutex()
    private var closing = false
    private val activities = mutableSetOf<Job>()
    private val resources = mutableListOf<suspend () -> Unit>()

    /** The handle detaches ownership after the caller has already released the resource. */
    fun own(release: suspend () -> Unit): AutoCloseable = synchronized(lock) {
        check(!closing) { "Session resources are closing" }
        resources.add(release)
        AutoCloseable { synchronized(lock) { resources.remove(release) } }
    }

    suspend fun <T> activity(block: suspend () -> T): T = coroutineScope {
        val task = async(start = CoroutineStart.LAZY) {
            val inherited = currentCoroutineContext()[ActiveScopes]?.scopes.orEmpty()
            withContext(ActiveScopes(inherited + this@SessionResourceScope)) { block() }
        }
        try {
            synchronized(lock) {
                check(!closing) { "Session resources are closing" }
                activities.add(task)
            }
            task.await()
        } finally {
            withContext(NonCancellable) {
                task.cancelAndJoin()
                synchronized(lock) { activities.remove(task) }
            }
        }
    }

    suspend fun close() {
        check(this !in currentCoroutineContext()[ActiveScopes]?.scopes.orEmpty()) { "Cannot close a scope from its own activity" }
        withContext(NonCancellable) {
            closeMutex.withLock {
                val running = synchronized(lock) { closing = true; activities.toList() }
                running.forEach { it.cancel() }
                running.joinAll()
                val releases = synchronized(lock) { resources.toList().asReversed() }
                val failures = mutableListOf<Throwable>()
                for (release in releases) {
                    try {
                        release()
                        synchronized(lock) { resources.remove(release) }
                    } catch (t: Throwable) {
                        failures += t
                    }
                }
                if (failures.isNotEmpty()) throw ResourceCleanupException(failures)
            }
        }
    }

    companion object {
        /** Failed setup rolls back already acquired resources, preserving the original exception. */
        suspend fun <T> create(setup: suspend (SessionResourceScope) -> T): T {
            val scope = SessionResourceScope()
            return try { setup(scope) } catch (t: Throwable) {
                try { scope.close() } catch (cleanup: Throwable) { t.addSuppressed(cleanup) }
                throw t
            }
        }
    }
}

private class ActiveScopes(val scopes: Set<SessionResourceScope>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActiveScopes>
}
