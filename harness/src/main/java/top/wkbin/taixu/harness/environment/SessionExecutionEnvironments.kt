package top.wkbin.taixu.harness.environment

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import top.wkbin.taixu.harness.core.SessionResourceScope
import top.wkbin.taixu.harness.core.ResourceCleanupException
import top.wkbin.taixu.harness.core.ToolCheckpoints
import top.wkbin.taixu.harness.core.ToolCheckpoint
import top.wkbin.taixu.harness.ToolExecutionRequest
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.runtime.environment.ExecutionEnvironment
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentFactory
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentContext

/** Immutable session binding; STDIO connections and child owners follow its resource scope. */
class SessionExecutionEnvironments(private val factory: ExecutionEnvironmentFactory) {
    private data class BindingKey(val session: String, val anonymousWorkspace: String, val owner: String?)
    private class Entry(val key: BindingKey, val workspace: String, val parent: Entry?) {
        val resources = SessionResourceScope()
        val setup = Mutex()
        var detach: AutoCloseable? = null
        @Volatile var acquired: ExecutionEnvironment? = null
        var initialized = false
        var failed = false
        val environment get() = checkNotNull(acquired)
        init { resources.own { acquired?.close() } }
    }
    private val mutex = Mutex()
    private val entries = mutableMapOf<BindingKey, Entry>()
    private val closedSessions = mutableSetOf<String>()
    private val closedOwners = mutableSetOf<Pair<String, String>>()

    suspend fun environment(session: String, workspace: String): ExecutionEnvironment = entry(session, workspace).environment

    private suspend fun entry(session: String, workspace: String): Entry {
        val normalized = session.ifBlank { "" }
        val owner = currentCoroutineContext()[ResourceOwner]?.takeIf { it.session == normalized }?.owner
        return entry(normalized, workspace, owner)
    }

    private suspend fun entry(session: String, workspace: String, owner: String?): Entry {
        val key = BindingKey(session, workspace.takeIf { session.isEmpty() }.orEmpty(), owner)
        mutex.withLock { checkOpen(key) }
        val parent = if (owner != null) entry(session, workspace, null) else null
        while (true) {
            val candidate = mutex.withLock {
                checkOpen(key)
                entries[key]?.also {
                    check(it.workspace == workspace) { "会话执行环境已绑定其他工作区，请先结束该会话资源" }
                } ?: Entry(key, workspace, parent).also {
                    it.detach = parent?.resources?.own { it.resources.close() }
                    entries[key] = it
                }
            }
            val ready = candidate.setup.withLock {
                if (candidate.failed) { dispose(candidate); null }
                else {
                    if (!candidate.initialized) initialize(candidate)
                    candidate
                }
            }
            if (ready != null) return mutex.withLock {
                checkOpen(key)
                check(entries[key] === ready) { "Environment was disposed during creation" }
                ready
            }
        }
    }

    private fun checkOpen(key: BindingKey) {
        check(key.session !in closedSessions && (key.owner == null || (key.session to key.owner) !in closedOwners)) {
            "Session execution environment has been disposed"
        }
    }

    private suspend fun initialize(entry: Entry) {
        try {
            val parent = entry.parent?.environment
            entry.resources.activity {
                val root = entry.key.session.ifEmpty { "anonymous:${entry.workspace}" }
                val owner = entry.key.owner?.let { "$root::$it" } ?: root
                val environment = factory.open(owner, entry.workspace, parent?.id?.distribution)
                // Cleanup is already registered: even a cancellation-resistant factory's late result is owned.
                entry.acquired = environment
                currentCoroutineContext().ensureActive()
            }
            val environment = entry.environment
            if (parent != null) require(environment.id.backend == parent.id.backend &&
                environment.id.distribution == parent.id.distribution &&
                environment.id.workspace == parent.id.workspace) { "子任务与父会话执行环境不一致" }
            currentCoroutineContext().ensureActive()
            entry.initialized = true
        } catch (t: Throwable) {
            entry.failed = true
            try { dispose(entry) } catch (cleanup: Throwable) { t.addSuppressed(cleanup) }
            throw t
        }
    }

    private suspend fun dispose(entry: Entry) = withContext(NonCancellable) {
        entry.resources.close()
        entry.detach?.close()
        mutex.withLock { if (entries[entry.key] === entry) entries.remove(entry.key) }
    }

    suspend fun <T> activity(session: String, workspace: String, resourceOwner: String? = null, block: suspend () -> T): T =
        if (resourceOwner != null) withContext(ResourceOwner(session.ifBlank { "" }, resourceOwner)) {
            boundActivity(entry(session, workspace), block)
        } else boundActivity(entry(session, workspace), block)

    private suspend fun <T> boundActivity(entry: Entry, block: suspend () -> T): T =
        entry.resources.activity {
            withContext(ExecutionEnvironmentContext(entry.environment, entry.resources::own)) { block() }
        }

    /** Acquire extensions/listeners with the same rollback and reverse-release contract. */
    suspend fun own(session: String, workspace: String, release: suspend () -> Unit) {
        entry(session, workspace).resources.own(release)
    }

    suspend fun installCheckpoint(session: String, workspace: String,
        checkpoints: ToolCheckpoints<ToolExecutionRequest, ToolResult>,
        checkpoint: ToolCheckpoint<ToolExecutionRequest, ToolResult>): AutoCloseable = activity(session, workspace) {
        val registration = checkpoints.register(ScopedToolCheckpoint(environment(session, workspace).id.owner, checkpoint)) { it.sessionId == session }
        try { own(session, workspace) { registration.close() }; registration }
        catch (t: Throwable) { registration.close(); throw t }
    }

    /** Extension creation is transactional; failed setup cannot leave registrations/listeners behind. */
    suspend fun install(session: String, workspace: String, setup: suspend (SessionResourceScope) -> Unit) {
        val entry = entry(session, workspace)
        boundActivity(entry) {
            val extension = SessionResourceScope()
            val lease = entry.resources.own { extension.close() }
            try { setup(extension) } catch (t: Throwable) {
                try { extension.close(); lease.close() } catch (cleanup: Throwable) { t.addSuppressed(cleanup) }
                throw t
            }
        }
    }

    suspend fun closeSession(session: String) {
        withContext(NonCancellable) {
            val normalized = session.ifBlank { "" }
            val roots = mutex.withLock {
                closedSessions.add(normalized)
                entries.values.filter { it.key.session == normalized && it.parent == null }
            }
            val failures = mutableListOf<Throwable>()
            for (root in roots) {
                try {
                    root.resources.close()
                    mutex.withLock {
                        entries.entries.removeAll { it.value === root || it.value.parent === root }
                    }
                } catch (t: Throwable) { failures += t }
            }
            if (failures.isNotEmpty()) throw ResourceCleanupException(failures)
        }
    }

    suspend fun closeOwner(session: String, owner: String) {
        withContext(NonCancellable) {
            val normalized = session.ifBlank { "" }
            val owners = mutex.withLock {
                closedOwners.add(normalized to owner)
                entries.values.filter { it.key.session == normalized && it.key.owner == owner }
            }
            val failures = mutableListOf<Throwable>()
            for (entry in owners) {
                try {
                    dispose(entry)
                } catch (t: Throwable) { failures += t }
            }
            if (failures.isNotEmpty()) throw ResourceCleanupException(failures)
        }
    }
}

private class ResourceOwner(val session: String, val owner: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ResourceOwner>
}
