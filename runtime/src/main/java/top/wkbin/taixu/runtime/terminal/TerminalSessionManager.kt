package top.wkbin.taixu.runtime.terminal

import com.termux.terminal.TerminalSession
import top.wkbin.taixu.core.database.TerminalSessionEntity
import top.wkbin.taixu.core.database.TerminalSessionRepository
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.SessionConfig
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentFactory
import top.wkbin.taixu.runtime.environment.LocalTerminalLaunch
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TerminalSessionHandle internal constructor(
    val id: String,
    val label: String,
    val workingDirectory: String,
    val distributionId: String = "ubuntu",
    internal val process: TerminalProcess,
) {
    val termuxSession: TerminalSession get() = process.session
    val isAlive: Boolean get() = process.isAlive
}

/** Multi-session Termux console; creation, rollback and disposal retain explicit resource ownership. */
class TerminalSessionManager(
    private val linuxRuntime: LinuxRuntime,
    private val terminalSessionDao: TerminalSessionRepository,
    private val sessionClientRouter: TerminalSessionClientRouter,
    private val environmentFactory: ExecutionEnvironmentFactory? = null,
    private val processFactory: TerminalProcessFactory = TermuxTerminalProcessFactory(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private val resources = mutableMapOf<String, TerminalOwnedResources>()
    private val closeAllMutex = Mutex()
    private var closingAll = false
    private var generation = 0L
    private var fallbackPending = false
    private val _handles = MutableStateFlow<List<TerminalSessionHandle>>(emptyList())
    val handles: StateFlow<List<TerminalSessionHandle>> = _handles.asStateFlow()
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()
    val activeHandle: StateFlow<TerminalSessionHandle?> =
        combine(_handles, _activeId) { handles, id -> handles.firstOrNull { it.id == id } }
            .stateIn(scope, SharingStarted.Eagerly, null)
    @Volatile private var restoredOnce = false

    suspend fun ensureActive(initialWorkingDirectory: String = DEFAULT_CWD) {
        val failures = mutableListOf<Throwable>()
        _handles.value.filterNot { it.isAlive }.forEach { dead ->
            val owned = synchronized(stateLock) { resources[dead.id] } ?: return@forEach
            try { release(owned, deleteRow = false) } catch (t: Throwable) { failures += t }
        }
        if (failures.isNotEmpty()) throw TerminalCleanupException(failures)
        if (_handles.value.isEmpty()) restoredOnce = false
        if (!restoredOnce) {
            try {
                val rows = terminalSessionDao.listAll()
                for (row in rows) {
                    if (_handles.value.none { it.id == row.id }) createSession(row.label, row.workingDirectory, row.distributionId, row.id)
                }
                restoredOnce = true
                if (rows.isNotEmpty()) {
                    _activeId.value = _handles.value.firstOrNull()?.id ?: _activeId.value
                    return
                }
            } catch (t: Throwable) { restoredOnce = false; throw t }
        }
        if (_handles.value.isEmpty()) createSession(workingDirectory = initialWorkingDirectory)
    }

    suspend fun createSession(
        label: String = "会话 ${_handles.value.size + 1}",
        workingDirectory: String = DEFAULT_CWD,
        distributionId: String? = null,
        id: String = UUID.randomUUID().toString(),
    ): TerminalSessionHandle = coroutineScope {
        val owned = TerminalOwnedResources(id, currentCoroutineContext().job)
        synchronized(stateLock) {
            check(!closingAll) { "Terminal sessions are closing" }
            require(id !in resources) { "Terminal session already exists or awaits cleanup: $id" }
            resources[id] = owned
        }
        try {
            val previous = terminalSessionDao.listAll().firstOrNull { it.id == id }
            owned.environment = environmentFactory?.open("terminal:$id", "", distributionId)
            checkCreation(owned)
            val environment = owned.environment
            val targetDistro = environment?.id?.distribution ?: distributionId ?: linuxRuntime.activeDistroId.value
            val config = SessionConfig(workingDirectory = workingDirectory, showBanner = true)
            val launch = if (environment != null) {
                (environment as? LocalTerminalLaunch)?.terminalLaunch(config)
                    ?: error("当前执行环境需要远程 PTY 渲染适配器；禁止启动本地终端。")
            } else linuxRuntime.buildInteractiveLaunch(config, targetDistro)
            checkCreation(owned)
            owned.process = processFactory.create(launch, sessionClientRouter)
            checkCreation(owned)
            owned.rollback = {
                if (previous == null) terminalSessionDao.delete(id) else terminalSessionDao.upsert(previous)
            }
            terminalSessionDao.upsert(TerminalSessionEntity(id = id, label = label, workingDirectory = workingDirectory,
                distributionId = targetDistro, createdAt = System.currentTimeMillis(), sortOrder = terminalSessionDao.nextOrder()))
            checkCreation(owned)
            val handle = TerminalSessionHandle(id, label, workingDirectory, targetDistro, owned.process!!)
            synchronized(stateLock) {
                check(!owned.closing && !closingAll && resources[id] === owned) { "Terminal session was disposed during creation" }
                _handles.update { it + handle }
                _activeId.value = id
                owned.published = true
                owned.rollback = null
            }
            handle
        } catch (t: Throwable) {
            owned.failedCreation = true
            try { owned.release(drainCreation = false); forget(owned) }
            catch (cleanup: Throwable) { t.addSuppressed(cleanup) }
            throw t
        }
    }

    private suspend fun checkCreation(owned: TerminalOwnedResources) {
        currentCoroutineContext().ensureActive()
        check(!owned.closing) { "Terminal session was disposed during creation" }
    }

    suspend fun openOrSwitchToProject(project: String, workingDirectory: String, distributionId: String? = null): TerminalSessionHandle {
        ensureActive()
        val existing = _handles.value.firstOrNull { it.workingDirectory == workingDirectory }
        if (existing != null) { _activeId.value = existing.id; return existing }
        return createSession(project.ifBlank { "工作区" }, workingDirectory, distributionId)
    }

    fun switchTo(id: String) {
        if (_handles.value.any { it.id == id }) _activeId.value = id
    }

    suspend fun closeSession(id: String) {
        val (owned, version) = synchronized(stateLock) { resources[id] to generation }
        if (owned == null) return
        check(!owned.owns(currentCoroutineContext().job)) { "Cannot close terminal from its own creation" }
        release(owned, deleteRow = true)
        val fallback = synchronized(stateLock) {
            (owned.published && _handles.value.isEmpty() && !closingAll && generation == version && !fallbackPending)
                .also { if (it) fallbackPending = true }
        }
        if (fallback) try { createSession(label = "主终端", workingDirectory = DEFAULT_CWD) }
        finally { synchronized(stateLock) { fallbackPending = false } }
    }

    private suspend fun release(owned: TerminalOwnedResources, deleteRow: Boolean) = withContext(NonCancellable) {
        owned.disposalMutex.withLock {
            if (synchronized(stateLock) { resources[owned.id] !== owned }) return@withLock
            owned.release()
            if (deleteRow && !owned.failedCreation) terminalSessionDao.delete(owned.id)
            forget(owned)
        }
    }

    private fun forget(owned: TerminalOwnedResources) = synchronized(stateLock) {
        if (resources[owned.id] === owned) {
            resources.remove(owned.id)
            _handles.update { handles -> handles.filterNot { it.id == owned.id } }
            if (_activeId.value == owned.id) _activeId.value = _handles.value.lastOrNull()?.id
        }
    }

    suspend fun closeAllSessions() {
        val caller = currentCoroutineContext().job
        synchronized(stateLock) {
            check(resources.values.none { it.owns(caller) }) { "Cannot close terminals from their own creation" }
        }
        withContext(NonCancellable) { closeAllMutex.withLock {
            val snapshot = synchronized(stateLock) {
                closingAll = true; generation++
                resources.values.toList().also { all -> all.forEach { it.closing = true; it.creation.cancel() } }
            }
            try {
                val failures = mutableListOf<Throwable>()
                for (owned in snapshot) {
                    try { release(owned, deleteRow = true) } catch (t: Throwable) { failures += t }
                }
                if (failures.isNotEmpty()) throw TerminalCleanupException(failures)
                terminalSessionDao.deleteAll()
                _handles.value = emptyList(); _activeId.value = null; restoredOnce = false
            } finally { synchronized(stateLock) { closingAll = false } }
        } }
    }

    fun write(id: String, data: ByteArray) {
        val owned = synchronized(stateLock) { resources[id] }
        if (owned?.closing != false) return
        _handles.value.firstOrNull { it.id == id }?.process?.write(data)
    }
    fun paste(id: String, text: String) = write(id, text.toByteArray(Charsets.UTF_8))
    fun interrupt(id: String) = write(id, byteArrayOf(3))

    private companion object { const val DEFAULT_CWD = "/root" }
}
