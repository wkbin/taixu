package top.wkbin.taixu.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Atomic storage primitives for the durable harness interpreter. */
@Dao
interface HarnessRuntimeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntry(entry: HarnessEntryEntity): Long

    /**
     * Insert entry or accept an idempotent duplicate (same session + payload).
     * A silent [OnConflictStrategy.IGNORE] against a different row must abort the
     * surrounding `@Transaction` so callers never advance `leafId` to a missing/wrong id.
     */
    suspend fun insertEntryOrThrow(entry: HarnessEntryEntity) {
        val rowId = insertEntry(entry)
        if (rowId != -1L) return
        val existing = findEntry(entry.id)
        check(
            existing != null &&
                existing.sessionId == entry.sessionId &&
                existing.payloadJson == entry.payloadJson,
        ) {
            "Harness entry insert ignored for ${entry.id}; refusing to mutate lane/operation without a stored row"
        }
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUsage(usage: HarnessUsageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLane(lane: HarnessLaneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOperation(operation: HarnessOperationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertQueueItem(item: HarnessQueueItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLaneResult(result: HarnessLaneResultEntity)

    @Query("SELECT * FROM harness_lanes WHERE sessionId = :sessionId AND name = :laneName LIMIT 1")
    suspend fun findLane(sessionId: String, laneName: String): HarnessLaneEntity?

    @Query("SELECT * FROM harness_lanes WHERE sessionId = :sessionId ORDER BY name")
    fun observeLanes(sessionId: String): Flow<List<HarnessLaneEntity>>

    @Query("SELECT * FROM harness_entries WHERE sessionId = :sessionId ORDER BY sequence")
    suspend fun listEntries(sessionId: String): List<HarnessEntryEntity>

    @Query("SELECT * FROM harness_entries WHERE (:start IS NULL OR createdAt >= :start) AND (:end IS NULL OR createdAt < :end) ORDER BY sequence")
    suspend fun listEntriesInRange(start: Long?, end: Long?): List<HarnessEntryEntity>

    @Query("SELECT COUNT(*) FROM harness_entries WHERE (:start IS NULL OR createdAt >= :start) AND (:end IS NULL OR createdAt < :end)")
    suspend fun countEntriesInRange(start: Long?, end: Long?): Int

    // ========== 用量统计专用：SQL 层聚合，避免把含巨大 payloadJson 的整表搬进内存 ==========
    //
    // 背景（2026-09-14 OOM 事故）：用量分析原先调用 listEntriesInRange(null, null) 把全部
    // harness_entries（实测 15,888 条，其中 tool_result 5,696 条可能各含数十 KB~数 MB 的
    // payloadJson）读进内存，再对每条 json.parseToJsonElement 建树 —— 256MB 堆直接 OOM。
    //
    // 以下查询用 json_extract 在 SQLite 侧取数并聚合，只把「每会话每类型的小结果」带回 Kotlin：
    //   · 不 SELECT payloadJson 本体，只取 json_extract 出来的标量；
    //   · 按 (sessionId, customType) GROUP BY，把 1.5 万行压成几十行。
    //
    // ⚠️ 必须带 json_valid 守卫：库中存在非 JSON 的 payloadJson —— 大负载会被外置为文件，
    //    DB 里只留 "@@TAIXU_BLOB@@:harness_blobs/..." 占位串（实测 172 条）。
    //    对这类行调用 json_extract 会让 SQLite 直接抛 "malformed JSON" 并中断整个查询，
    //    因此统一写成 CASE WHEN json_valid(payloadJson) THEN json_extract(...) ELSE ... END。
    // 注意：json_extract/json_valid 需要 SQLite JSON1 扩展（Android API 30+ 与 Room 自带 SQLite
    //       均支持；已在本机实测可用）。

    @Query(
        """
        SELECT sessionId AS sessionId,
               customType AS customType,
               COUNT(*) AS entryCount,
               SUM(CASE WHEN json_valid(payloadJson) THEN COALESCE(json_extract(payloadJson, '${'$'}.promptTokens'), 0) ELSE 0 END) AS promptTokens,
               SUM(CASE WHEN json_valid(payloadJson) THEN COALESCE(json_extract(payloadJson, '${'$'}.completionTokens'), 0) ELSE 0 END) AS completionTokens,
               SUM(CASE WHEN json_valid(payloadJson) THEN COALESCE(json_extract(payloadJson, '${'$'}.cachedTokens'), 0) ELSE 0 END) AS cachedTokens,
               SUM(CASE WHEN json_valid(payloadJson) THEN LENGTH(COALESCE(json_extract(payloadJson, '${'$'}.text'), '')) ELSE 0 END) AS textChars,
               SUM(CASE WHEN json_valid(payloadJson) THEN LENGTH(COALESCE(json_extract(payloadJson, '${'$'}.reasoning'), '')) ELSE 0 END) AS reasoningChars
        FROM harness_entries
        WHERE (:start IS NULL OR createdAt >= :start) AND (:end IS NULL OR createdAt < :end)
        GROUP BY sessionId, customType
        """,
    )
    suspend fun aggregateUsageInRange(start: Long?, end: Long?): List<UsageAggregateRow>

    /**
     * 按本地日聚合用量。
     *
     * [tzOffsetMs] 是 `ZoneId` 相对 UTC 的毫秒偏移（例如 Asia/Shanghai = +28800000）。
     * 用它替代 `CAST(createdAt / 86400000)` 的 UTC 日切，避免本地午夜附近的条目被分到前一天。
     * Token 列与 [aggregateUsageInRange] 同一套 json_extract + json_valid，不 SELECT payloadJson。
     */
    @Query(
        """
        SELECT CAST((createdAt + :tzOffsetMs) / 86400000 AS INTEGER) AS localEpochDay,
               sessionId AS sessionId,
               customType AS customType,
               COUNT(*) AS entryCount,
               SUM(CASE WHEN json_valid(payloadJson) THEN COALESCE(json_extract(payloadJson, '${'$'}.promptTokens'), 0) ELSE 0 END) AS promptTokens,
               SUM(CASE WHEN json_valid(payloadJson) THEN COALESCE(json_extract(payloadJson, '${'$'}.completionTokens'), 0) ELSE 0 END) AS completionTokens,
               SUM(CASE WHEN json_valid(payloadJson) THEN COALESCE(json_extract(payloadJson, '${'$'}.cachedTokens'), 0) ELSE 0 END) AS cachedTokens,
               SUM(CASE WHEN json_valid(payloadJson) THEN LENGTH(COALESCE(json_extract(payloadJson, '${'$'}.text'), '')) ELSE 0 END) AS textChars,
               SUM(CASE WHEN json_valid(payloadJson) THEN LENGTH(COALESCE(json_extract(payloadJson, '${'$'}.reasoning'), '')) ELSE 0 END) AS reasoningChars
        FROM harness_entries
        WHERE (:start IS NULL OR createdAt >= :start) AND (:end IS NULL OR createdAt < :end)
        GROUP BY CAST((createdAt + :tzOffsetMs) / 86400000 AS INTEGER), sessionId, customType
        """,
    )
    suspend fun aggregateDailyCounts(start: Long?, end: Long?, tzOffsetMs: Long): List<DailyCountRow>

    @Query("SELECT * FROM harness_entries WHERE id = :entryId LIMIT 1")
    suspend fun findEntry(entryId: String): HarnessEntryEntity?

    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM branch ORDER BY sequence
    """)
    suspend fun branch(sessionId: String, leafId: String): List<HarnessEntryEntity>

    /**
     * Return only the newest branch window to the Android process. The recursive walk still
     * happens inside SQLite, but payloadJson for older ancestors never enters the managed heap.
     */
    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM (
            SELECT * FROM branch ORDER BY sequence DESC LIMIT :limit
        ) ORDER BY sequence
    """)
    suspend fun branchTail(sessionId: String, leafId: String, limit: Int): List<HarnessEntryEntity>

    /**
     * 按序列号水位线只返回增量条目与召回块，已被折叠的更早历史祖先条目不会加载到托管堆中。
     */
    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM branch
        WHERE sequence >= :minSequence OR entryType = 'recall_context'
        ORDER BY sequence
    """)
    suspend fun branchWindow(sessionId: String, leafId: String, minSequence: Long): List<HarnessEntryEntity>

    /** Latest entry of one type on the selected immutable-tree branch. */
    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM branch
        WHERE entryType = :entryType
        ORDER BY sequence DESC LIMIT 1
    """)
    suspend fun latestBranchEntryOfType(
        sessionId: String,
        leafId: String,
        entryType: String,
    ): HarnessEntryEntity?

    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM branch
        WHERE entryType = 'message' AND payloadJson LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY sequence DESC LIMIT :limit
    """)
    suspend fun searchBranch(sessionId: String, leafId: String, query: String, limit: Int): List<HarnessEntryEntity>

    @Query("""
        WITH RECURSIVE branch AS (
            SELECT * FROM harness_entries WHERE id = :leafId AND sessionId = :sessionId
            UNION ALL
            SELECT parent.* FROM harness_entries AS parent
            JOIN branch AS child ON parent.id = child.parentId
            WHERE parent.sessionId = :sessionId
        )
        SELECT * FROM branch WHERE entryType = 'message' ORDER BY sequence LIMIT 1 OFFSET :index
    """)
    suspend fun branchEntryAt(sessionId: String, leafId: String, index: Int): HarnessEntryEntity?

    @Query("SELECT * FROM harness_operations WHERE id = :operationId LIMIT 1")
    suspend fun findOperation(operationId: String): HarnessOperationEntity?

    @Query("SELECT * FROM harness_operations WHERE sessionId = :sessionId AND status IN ('running', 'waiting_approval', 'suspended', 'aborting') ORDER BY startedAt")
    suspend fun listActiveOperations(sessionId: String): List<HarnessOperationEntity>

    @Query("UPDATE harness_lanes SET currentOperationId = NULL, updatedAt = :updatedAt, faulted = :faulted WHERE sessionId = :sessionId AND name = :laneName AND currentOperationId = :operationId")
    suspend fun clearLaneOperationIfCurrent(sessionId: String, laneName: String, operationId: String, updatedAt: Long, faulted: Boolean): Int

    @Query("SELECT * FROM harness_queue_items WHERE sessionId = :sessionId AND laneName = :laneName AND queueType = :queueType ORDER BY createdAt, id")
    suspend fun listQueue(sessionId: String, laneName: String, queueType: String): List<HarnessQueueItemEntity>

    @Query("SELECT * FROM harness_queue_items WHERE sessionId = :sessionId AND laneName = :laneName ORDER BY createdAt, id")
    suspend fun listAllQueues(sessionId: String, laneName: String): List<HarnessQueueItemEntity>

    @Query("SELECT * FROM harness_usage WHERE sessionId = :sessionId ORDER BY sequence")
    suspend fun listUsage(sessionId: String): List<HarnessUsageEntity>

    @Query("DELETE FROM harness_queue_items WHERE id = :itemId")
    suspend fun deleteQueueItem(itemId: String)

    @Query("DELETE FROM harness_queue_items WHERE id = :itemId AND sessionId = :sessionId AND laneName = :laneName AND queueType = 'next_run'")
    suspend fun claimNextRunQueue(itemId: String, sessionId: String, laneName: String): Int

    @Query("DELETE FROM harness_queue_items WHERE sessionId = :sessionId AND laneName = :laneName AND queueType = :queueType")
    suspend fun clearQueue(sessionId: String, laneName: String, queueType: String)

    @Query("DELETE FROM harness_operations WHERE id = :operationId")
    suspend fun deleteOperation(operationId: String)

    @Query("DELETE FROM harness_queue_items WHERE operationId = :operationId")
    suspend fun deleteOperationQueue(operationId: String)

    @Query("UPDATE harness_queue_items SET operationId = NULL WHERE operationId = :operationId AND queueType = 'next_run'")
    suspend fun detachNextRunQueue(operationId: String)

    @Query("DELETE FROM harness_entries WHERE sessionId = :sessionId")
    suspend fun deleteSessionEntries(sessionId: String)

    @Query("DELETE FROM harness_lanes WHERE sessionId = :sessionId")
    suspend fun deleteSessionLanes(sessionId: String)

    @Query("DELETE FROM harness_operations WHERE sessionId = :sessionId")
    suspend fun deleteSessionOperations(sessionId: String)

    @Query("DELETE FROM harness_queue_items WHERE sessionId = :sessionId")
    suspend fun deleteSessionQueue(sessionId: String)

    @Query("DELETE FROM harness_usage WHERE sessionId = :sessionId")
    suspend fun deleteSessionUsage(sessionId: String)

    @Query("DELETE FROM harness_lane_results WHERE sessionId = :sessionId")
    suspend fun deleteSessionResults(sessionId: String)

    @Transaction
    suspend fun acceptOperation(entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity) {
        insertEntryOrThrow(entry)
        upsertOperation(operation)
        upsertLane(lane)
    }

    @Transaction
    suspend fun acceptQueuedOperation(
        queueItemId: String,
        entry: HarnessEntryEntity,
        lane: HarnessLaneEntity,
        operation: HarnessOperationEntity,
    ) {
        require(entry.sessionId == lane.sessionId && operation.sessionId == lane.sessionId && operation.laneName == lane.name)
        check(claimNextRunQueue(queueItemId, lane.sessionId, lane.name) == 1) {
            "Next-run queue item could not claim admission"
        }
        insertEntryOrThrow(entry)
        upsertOperation(operation)
        upsertLane(lane)
    }

    @Transaction
    suspend fun beginOperation(lane: HarnessLaneEntity, operation: HarnessOperationEntity) {
        upsertOperation(operation)
        upsertLane(lane)
    }

    @Query("""
        UPDATE agent_tasks SET status = 'RUNNING', operationId = :operationId,
            startedAt = COALESCE(startedAt, :now), updatedAt = :now,
            attemptCount = attemptCount + 1, statusDetail = '任务已受理',
            errorMessage = NULL, completedAt = NULL, nextRunAt = NULL
        WHERE id = :taskId AND sessionId = :sessionId AND status = 'QUEUED'
    """)
    suspend fun claimAdmittedTask(taskId: String, sessionId: String, operationId: String, now: Long): Int

    @Transaction
    suspend fun acceptTaskOperation(taskId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity,
        operation: HarnessOperationEntity, queueItemId: String?) {
        require(entry.sessionId == lane.sessionId && operation.sessionId == lane.sessionId && operation.laneName == lane.name)
        check(claimAdmittedTask(taskId, lane.sessionId, operation.id, operation.startedAt) == 1) {
            "Task could not claim admission"
        }
        if (queueItemId == null) acceptOperation(entry, lane, operation)
        else acceptQueuedOperation(queueItemId, entry, lane, operation)
    }

    @Transaction
    suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?, operation: HarnessOperationEntity, lane: HarnessLaneEntity) {
        if (entry != null) insertEntryOrThrow(entry)
        if (usage != null) insertUsage(usage)
        upsertOperation(operation)
        upsertLane(lane)
    }

    @Transaction
    suspend fun acceptRunTakeover(queueItemId: String?, entry: HarnessEntryEntity?, lane: HarnessLaneEntity,
        operation: HarnessOperationEntity, previousLane: HarnessLaneEntity,
        previousResult: HarnessLaneResultEntity?, taskId: String?) {
        require(previousLane.sessionId == lane.sessionId && previousLane.name == lane.name)
        require((entry == null || entry.sessionId == lane.sessionId) && operation.sessionId == lane.sessionId && operation.laneName == lane.name)
        val current = findLane(lane.sessionId, lane.name)
        check(current != null && current.currentOperationId == previousLane.currentOperationId && current.leafId == previousLane.leafId) {
            "Lane changed before input admission"
        }
        require(lane.currentOperationId == operation.id && operation.startLeafId == previousLane.leafId)
        if (entry == null) require(queueItemId == null && taskId == null && lane.leafId == previousLane.leafId)
        else require(entry.parentId == previousLane.leafId && lane.leafId == entry.id)
        if (previousResult != null) {
            require(previousResult.sessionId == lane.sessionId && previousResult.laneName == lane.name &&
                previousResult.operationId == previousLane.currentOperationId)
            finishOperation(previousResult, previousLane.copy(currentOperationId = null,
                updatedAt = previousResult.completedAt, faulted = false))
        } else {
            check(previousLane.currentOperationId?.let { findOperation(it) } == null) {
                "Existing operation requires a takeover result"
            }
        }
        if (entry == null) beginOperation(lane, operation)
        else if (taskId != null) acceptTaskOperation(taskId, entry, lane, operation, queueItemId)
        else if (queueItemId != null) acceptQueuedOperation(queueItemId, entry, lane, operation)
        else acceptOperation(entry, lane, operation)
    }

    @Transaction
    suspend fun finishOperation(result: HarnessLaneResultEntity, lane: HarnessLaneEntity) {
        // Next-run inputs belong to future runs. Preserve them through both normal
        // completion and takeover, including a crash before the next admission.
        detachNextRunQueue(result.operationId)
        deleteOperationQueue(result.operationId)
        deleteOperation(result.operationId)
        upsertLaneResult(result)
        // A later run may have claimed this lane since the caller read its snapshot.
        clearLaneOperationIfCurrent(lane.sessionId, lane.name, result.operationId, lane.updatedAt, lane.faulted)
    }

    @Transaction
    suspend fun consumeQueueItem(itemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity) {
        insertEntryOrThrow(entry)
        deleteQueueItem(itemId)
        upsertLane(lane)
    }

    @Transaction
    suspend fun appendEntry(entry: HarnessEntryEntity, lane: HarnessLaneEntity) {
        val current = findLane(lane.sessionId, lane.name)
        check(current?.leafId == entry.parentId) { "Lane ${lane.name} moved while appending ${entry.id}" }
        insertEntryOrThrow(entry)
        upsertLane(lane)
    }

    @Transaction
    suspend fun deleteSessionData(sessionId: String) {
        deleteSessionQueue(sessionId)
        deleteSessionOperations(sessionId)
        deleteSessionResults(sessionId)
        deleteSessionUsage(sessionId)
        deleteSessionLanes(sessionId)
        deleteSessionEntries(sessionId)
    }
}
