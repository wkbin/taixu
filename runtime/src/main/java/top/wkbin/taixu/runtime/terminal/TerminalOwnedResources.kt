package top.wkbin.taixu.runtime.terminal

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.wkbin.taixu.runtime.environment.ExecutionEnvironment

/** Registered before creation; failed releases remain reachable and successful steps run once. */
internal class TerminalOwnedResources(val id: String, val creation: Job) {
    @Volatile var closing = false
    @Volatile var failedCreation = false
    var published = false
    var environment: ExecutionEnvironment? = null
    var process: TerminalProcess? = null
    var rollback: (suspend () -> Unit)? = null
    val disposalMutex = Mutex()
    private val mutex = Mutex()
    private var processReleased = false
    private var environmentReleased = false

    fun owns(job: Job): Boolean {
        fun contains(parent: Job): Boolean = parent === job || parent.children.any { contains(it) }
        return contains(creation)
    }

    suspend fun release(drainCreation: Boolean = true) = withContext(NonCancellable) {
        closing = true
        // Join before taking the cleanup lock: the creator's catch block also needs this lock.
        if (drainCreation && !creation.isCompleted) creation.cancelAndJoin()
        mutex.withLock {
            val failures = mutableListOf<Throwable>()
            if (!processReleased && process != null) try {
                process!!.finish(); processReleased = true
            } catch (t: Throwable) { failures += t }
            if (!environmentReleased && environment != null) try {
                environment!!.close(); environmentReleased = true
            } catch (t: Throwable) { failures += t }
            rollback?.let { restore ->
                try { restore(); rollback = null } catch (t: Throwable) { failures += t }
            }
            if (failures.isNotEmpty()) throw TerminalCleanupException(failures)
        }
    }
}

/** Keeps all errors intact when coroutine stack recovery copies ordinary exceptions. */
internal class TerminalCleanupException(val failures: List<Throwable>) :
    IllegalStateException(failures.first().message, failures.first()) {
    init { failures.drop(1).forEach(::addSuppressed) }
}
