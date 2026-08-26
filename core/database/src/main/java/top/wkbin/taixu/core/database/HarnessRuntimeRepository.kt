package top.wkbin.taixu.core.database

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Persistence port used by the harness runtime; feature modules never depend on its DAO. */
interface HarnessRuntimeRepository {
    suspend fun ensureLane(sessionId: String, laneName: String, atEntryId: String? = null): HarnessLaneEntity
    suspend fun findLane(sessionId: String, laneName: String): HarnessLaneEntity?
    fun observeLanes(sessionId: String): Flow<List<HarnessLaneEntity>>
    suspend fun listEntries(sessionId: String): List<HarnessEntryEntity>
    suspend fun listEntriesInRange(start: Long?, end: Long?): List<HarnessEntryEntity>
    suspend fun countEntriesInRange(start: Long?, end: Long?): Int
    suspend fun branch(sessionId: String, leafId: String?): List<HarnessEntryEntity>
    suspend fun appendToLane(sessionId: String, laneName: String, entry: HarnessEntryEntity)
    suspend fun moveLane(sessionId: String, laneName: String, leafId: String?)
    suspend fun clearLaneOperation(sessionId: String, laneName: String)
    suspend fun findOperation(operationId: String): HarnessOperationEntity?
    suspend fun listActiveOperations(sessionId: String): List<HarnessOperationEntity>
    suspend fun acceptOperation(entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity)
    suspend fun acceptQueuedOperation(queueItemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity)
    suspend fun beginOperation(lane: HarnessLaneEntity, operation: HarnessOperationEntity)
    suspend fun saveOperation(operation: HarnessOperationEntity)
    suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?, operation: HarnessOperationEntity, lane: HarnessLaneEntity)
    suspend fun finishOperation(result: HarnessLaneResultEntity, lane: HarnessLaneEntity)
    suspend fun enqueue(item: HarnessQueueItemEntity)
    suspend fun listQueue(sessionId: String, laneName: String, queueType: String): List<HarnessQueueItemEntity>
    suspend fun cancelQueued(itemId: String)
    suspend fun clearQueue(sessionId: String, laneName: String, queueType: String)
    suspend fun consumeQueued(itemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity)
    suspend fun recordUsage(usage: HarnessUsageEntity)
    suspend fun listUsage(sessionId: String): List<HarnessUsageEntity>
    suspend fun deleteSessionData(sessionId: String)
}

@Singleton
class RoomHarnessRuntimeRepository @Inject constructor(
    private val dao: HarnessRuntimeDao,
) : HarnessRuntimeRepository {
    override suspend fun ensureLane(sessionId: String, laneName: String, atEntryId: String?): HarnessLaneEntity {
        dao.findLane(sessionId, laneName)?.let { return it }
        if (atEntryId != null) {
            require(dao.findEntry(atEntryId)?.sessionId == sessionId) { "Lane anchor does not belong to session $sessionId" }
        }
        val lane = HarnessLaneEntity(
            sessionId = sessionId,
            name = laneName,
            leafId = atEntryId,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertLane(lane)
        return lane
    }

    override suspend fun findLane(sessionId: String, laneName: String) = dao.findLane(sessionId, laneName)
    override fun observeLanes(sessionId: String) = dao.observeLanes(sessionId)
    override suspend fun listEntries(sessionId: String) = dao.listEntries(sessionId)
    override suspend fun listEntriesInRange(start: Long?, end: Long?) = dao.listEntriesInRange(start, end)
    override suspend fun countEntriesInRange(start: Long?, end: Long?) = dao.countEntriesInRange(start, end)

    override suspend fun branch(sessionId: String, leafId: String?): List<HarnessEntryEntity> {
        return EntryTree.branch(dao.listEntries(sessionId), leafId)
    }

    override suspend fun appendToLane(sessionId: String, laneName: String, entry: HarnessEntryEntity) {
        val lane = ensureLane(sessionId, laneName)
        require(entry.sessionId == sessionId && entry.parentId == lane.leafId) { "Entry parent does not match lane leaf" }
        dao.appendEntry(entry, lane.copy(leafId = entry.id, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun moveLane(sessionId: String, laneName: String, leafId: String?) {
        val lane = ensureLane(sessionId, laneName)
        if (leafId != null) require(dao.findEntry(leafId)?.sessionId == sessionId) { "Unknown lane target $leafId" }
        dao.upsertLane(lane.copy(leafId = leafId, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun clearLaneOperation(sessionId: String, laneName: String) {
        val lane = dao.findLane(sessionId, laneName) ?: return
        dao.upsertLane(lane.copy(currentOperationId = null, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun findOperation(operationId: String) = dao.findOperation(operationId)
    override suspend fun listActiveOperations(sessionId: String) = dao.listActiveOperations(sessionId)
    override suspend fun acceptOperation(entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity) =
        dao.acceptOperation(entry, lane, operation)
    override suspend fun acceptQueuedOperation(queueItemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity) =
        dao.acceptQueuedOperation(queueItemId, entry, lane, operation)
    override suspend fun beginOperation(lane: HarnessLaneEntity, operation: HarnessOperationEntity) =
        dao.beginOperation(lane, operation)
    override suspend fun saveOperation(operation: HarnessOperationEntity) = dao.upsertOperation(operation)
    override suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?, operation: HarnessOperationEntity, lane: HarnessLaneEntity) =
        dao.settleEffect(entry, usage, operation, lane)
    override suspend fun finishOperation(result: HarnessLaneResultEntity, lane: HarnessLaneEntity) =
        dao.finishOperation(result, lane)
    override suspend fun enqueue(item: HarnessQueueItemEntity) = dao.insertQueueItem(item)
    override suspend fun listQueue(sessionId: String, laneName: String, queueType: String) =
        dao.listQueue(sessionId, laneName, queueType)
    override suspend fun cancelQueued(itemId: String) = dao.deleteQueueItem(itemId)
    override suspend fun clearQueue(sessionId: String, laneName: String, queueType: String) =
        dao.clearQueue(sessionId, laneName, queueType)
    override suspend fun consumeQueued(itemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity) =
        dao.consumeQueueItem(itemId, entry, lane)
    override suspend fun recordUsage(usage: HarnessUsageEntity) {
        dao.insertUsage(usage)
    }
    override suspend fun listUsage(sessionId: String) = dao.listUsage(sessionId)
    override suspend fun deleteSessionData(sessionId: String) = dao.deleteSessionData(sessionId)
}
