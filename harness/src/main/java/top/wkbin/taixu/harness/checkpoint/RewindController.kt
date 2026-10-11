package top.wkbin.taixu.harness.checkpoint

import top.wkbin.taixu.harness.WorkspaceFileAccess
import top.wkbin.taixu.runtime.environment.ExecutionFiles

/**
 * 恢复编排（prepare/commit 两段式）。前端/未来 MCP 只驱动这一套 API，
 * 具体路径校验与落盘复用在 [WorkspaceFileAccess] 的安全层。
 *
 * - CODE：用 [CheckpointStore.planCodeRewind] 拿到方案，逐文件恢复（content==null 即删除）。
 * - CONVERSATION/BOTH：需要可选的对话 fork 处理器（[conversationRewinder]）在目标轮处派生新会话；
 *   未配置时为 partial 结果，不阻塞代码恢复。
 */
class RewindController(
    private val store: CheckpointStore,
    private val fileAccess: WorkspaceFileAccess,
    private val conversationRewinder: ConversationRewinder? = null,
    private val environments: top.wkbin.taixu.harness.environment.SessionExecutionEnvironments? = null,
) {
    fun checkpoints(sessionId: String): List<CheckpointMeta> = store.checkpoints(sessionId)

    /** 用户轮次开始处调用：开启该轮 checkpoint。 */
    fun beginTurn(sessionId: String, prompt: String, anchorMessageId: String? = null) =
        store.beginTurn(sessionId, prompt, anchorMessageId)

    /** 会话结束/删除清理。 */
    fun dropSession(sessionId: String) = store.dropSession(sessionId)

    /** 只读规划，不落盘。 */
    fun prepare(sessionId: String, turn: Int, scope: RewindScope): RewindPlan = RewindPlan(
        sessionId = sessionId,
        turn = turn,
        scope = scope,
        fileSnaps = if (scope == RewindScope.CONVERSATION) emptyList() else store.planCodeRewind(sessionId, turn),
    )

    /** 执行已规划方案。workspace 用于 `withBase` 子工作区定位。 */
    suspend fun commit(plan: RewindPlan, workspace: String = ""): RewindResult =
        if (environments != null) environments.activity(plan.sessionId, workspace) { commitBound(plan, workspace) }
        else commitBound(plan, workspace)

    private suspend fun commitBound(plan: RewindPlan, workspace: String): RewindResult {
        val activeFileAccess = filesFor(plan.sessionId, workspace)
        var restored = 0
        var deleted = 0
        val problems = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        // 撤销快照：仅记录实际会被本方案改动的路径在**改动前**的磁盘状态；
        // 超过快照上限的路径无法经 write 还原，不进撤销记录（该路径 rewind 后不可 undo）。
        val appliedSnaps = mutableListOf<FileSnap>()
        val undoSnaps = mutableListOf<FileSnap>()
        for (snap in plan.fileSnaps) {
            // 外部改动冲突检测：当前文件内容与本 store 记录的最后改动后凭据不一致，
            // 说明会话之外有人改过该文件——恢复会静默覆盖外部修改，保守跳过并报告。
            // 无凭据（写入失败/超大文件跳过捕获/旧数据）的路径不检测，维持原行为。
            if (isExternallyModified(plan.sessionId, snap, activeFileAccess)) {
                conflicts += snap.path
                continue
            }
            val size = activeFileAccess.fileSizeOrNull(snap.path)
            val undoable = size == null || size <= CheckpointStore.SNAPSHOT_MAX_BYTES
            val currentContent = if (undoable) activeFileAccess.previewOrNull(snap.path) else null
            val ok = if (snap.content == null) {
                activeFileAccess.delete(snap.path)
            } else {
                activeFileAccess.write(snap.path, snap.content).isSuccess
            }
            if (ok) {
                if (snap.content == null) deleted++ else restored++
                if (undoable) {
                    appliedSnaps += snap
                    undoSnaps += FileSnap(snap.path, currentContent)
                }
            } else {
                problems += snap.path
            }
        }
        if (appliedSnaps.isNotEmpty()) {
            store.recordRewindUndo(plan.sessionId, RewindUndoRecord(applied = appliedSnaps, undoSnaps = undoSnaps))
        }

        var partial = problems.isNotEmpty() || conflicts.isNotEmpty()
        var note: String? = null
        var forkedSessionId: String? = null
        if (plan.scope != RewindScope.CODE) {
            forkedSessionId = conversationRewinder?.rewindConversation(plan.sessionId, plan.turn)
            if (forkedSessionId == null) {
                partial = true
                note = "对话尚未在目标轮派生新会话（未配置对话 fork 处理器或该轮无锚点）；代码恢复已完成。"
            }
        }
        if (conflicts.isNotEmpty()) {
            note = (note?.let { "$it\n" } ?: "") +
                "以下文件在会话外被修改过，已跳过恢复以免覆盖外部改动（如需回滚请先自行备份）：${conflicts.joinToString("；")}"
        }
        if (problems.isNotEmpty()) {
            note = (note?.let { "$it\n" } ?: "") + "以下文件恢复失败：${problems.joinToString("；")}"
        }
        return RewindResult(
            filesRestored = restored,
            filesDeleted = deleted,
            partial = partial,
            note = note,
            forkedSessionId = forkedSessionId,
            conflicts = conflicts,
        )
    }

    /**
     * 撤销最近一次 rewind（单层级，对齐 Reasonix 的 UndoRewind）：把文件还原到
     * rewind 前的磁盘状态。冲突基线是 rewind 实际写入的状态——rewind 之后智能体
     * 又写过文件（capture 使记录失效）或用户再次外部改动，都会跳过对应路径。
     * 对话侧不在 undo 范围：fork 不改动原会话，切回原会话即可。
     *
     * @return null = 当前没有可撤销的 rewind。
     */
    suspend fun undoLastRewind(sessionId: String, workspace: String = ""): RewindResult? =
        if (environments != null) environments.activity(sessionId, workspace) { undoBound(sessionId, workspace) }
        else undoBound(sessionId, workspace)

    private suspend fun undoBound(sessionId: String, workspace: String): RewindResult? {
        val activeFileAccess = filesFor(sessionId, workspace)
        val record = store.takeRewindUndo(sessionId) ?: return null
        var restored = 0
        var deleted = 0
        val conflicts = mutableListOf<String>()
        val problems = mutableListOf<String>()
        record.undoSnaps.forEachIndexed { index, undoSnap ->
            val applied = record.applied.getOrNull(index) ?: return@forEachIndexed
            // 冲突基线 = rewind 实际写入的状态；不一致说明 rewind 之后又被改过
            val expected = applied.content
            val currentSize = activeFileAccess.fileSizeOrNull(undoSnap.path)
            val externallyModified = when {
                expected == null -> currentSize != null // rewind 时删除，如今又存在 → 外部重建
                currentSize == null -> true             // rewind 时写入，如今消失 → 外部删除
                currentSize > CheckpointStore.SNAPSHOT_MAX_BYTES -> true
                else -> activeFileAccess.previewOrNull(undoSnap.path) != expected
            }
            if (externallyModified) {
                conflicts += undoSnap.path
                return@forEachIndexed
            }
            val ok = if (undoSnap.content == null) {
                activeFileAccess.delete(undoSnap.path)
            } else {
                activeFileAccess.write(undoSnap.path, undoSnap.content).isSuccess
            }
            if (ok) {
                if (undoSnap.content == null) deleted++ else restored++
            } else {
                problems += undoSnap.path
            }
        }
        val note = listOfNotNull(
            conflicts.takeIf { it.isNotEmpty() }?.let { "以下文件在 rewind 后又被改动，已跳过撤销：${it.joinToString("；")}" },
            problems.takeIf { it.isNotEmpty() }?.let { "以下文件撤销失败：${it.joinToString("；")}" },
        ).joinToString("\n").ifEmpty { null }
        return RewindResult(
            filesRestored = restored,
            filesDeleted = deleted,
            partial = conflicts.isNotEmpty() || problems.isNotEmpty(),
            note = note,
            conflicts = conflicts,
        )
    }

    private suspend fun filesFor(session: String, workspace: String): ExecutionFiles =
        if (environments != null) environments.environment(session, workspace).files
        else if (workspace.isNotBlank()) fileAccess.withBase(workspace) else fileAccess

    /**
     * 当前文件是否在 store 的最后凭据之后又被改动过：
     * - 凭据缺失（null）→ 无法判断，视为未改动（不检测）；
     * - 当前文件超过快照上限 → 写入凭据时必在限内，如今超限必是外部替换；
     * - 当前文件缺失但凭据存在 → 被外部删除；
     * - 其余逐字节比较内容。
     */
    private suspend fun isExternallyModified(
        sessionId: String,
        snap: FileSnap,
        activeFileAccess: ExecutionFiles,
    ): Boolean {
        val expected = store.latestAfterImage(sessionId, snap.path) ?: return false
        val size = activeFileAccess.fileSizeOrNull(snap.path)
            ?: return true // 当前文件不存在：凭据存在说明曾由本 store 写入，被外部删除
        if (size > CheckpointStore.SNAPSHOT_MAX_BYTES) return true
        val current = activeFileAccess.previewOrNull(snap.path) ?: return true
        return current != expected
    }
}

/** 在指定轮处派生新会话的可选回调（多态注入点，避免 RewindController 反向依赖会话存储）。 */
fun interface ConversationRewinder {
    /** @return 新会话 id；返回 null 表示未能派生。 */
    suspend fun rewindConversation(sessionId: String, turn: Int): String?
}
