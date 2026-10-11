package top.wkbin.taixu.harness.operation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.wkbin.taixu.core.database.HarnessEntryEntity
import top.wkbin.taixu.core.database.HarnessLaneEntity
import top.wkbin.taixu.core.database.HarnessLaneResultEntity
import top.wkbin.taixu.core.database.HarnessOperationEntity
import top.wkbin.taixu.core.database.HarnessQueueItemEntity
import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.core.database.HarnessUsageEntity
import top.wkbin.taixu.harness.ChatUsage
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.core.database.DailyCountRow
import top.wkbin.taixu.core.database.UsageAggregateRow
import top.wkbin.taixu.harness.events.HarnessEvent
import top.wkbin.taixu.harness.events.HarnessEventBus

class OperationCoordinatorTest {

    private lateinit var repository: FakeRuntimeRepository
    private lateinit var eventBus: HarnessEventBus
    private lateinit var coordinator: OperationCoordinator

    @Before
    fun setUp() {
        repository = FakeRuntimeRepository()
        eventBus = HarnessEventBus()
        coordinator = OperationCoordinator(repository, Json, eventBus)
    }

    private fun userMessage(text: String) = UserMessage(id = "msg-$text", createdAt = 1L, text = text)

    @Test
    fun `accept run finishes suspended leftover and starts new operation`() = runBlocking {
        val staleId = coordinator.acceptRun("s1", userMessage("old"))
        coordinator.suspendOperation(staleId, "进程中断")
        assertEquals(OperationStatus.SUSPENDED.id, repository.operations[staleId]!!.status)

        val freshId = coordinator.acceptRun("s1", userMessage("new"))

        assertNotEquals(staleId, freshId)
        assertNull("旧操作应已被 finish 移除", repository.operations[staleId])
        assertEquals(freshId, repository.lanes["s1" to "main"]!!.currentOperationId)
        val result = repository.results["s1" to "main"]!!
        assertEquals(staleId, result.operationId)
        assertEquals("aborted", result.outcome)
        assertTrue(result.detailsJson!!.contains("接管"))
        // 新用户消息必须进入 entry tree，且挂在旧 leaf 之下（历史保留）
        assertEquals(2, repository.entries("s1").size)
    }

    @Test
    fun `accept run reclaims waiting approval leftover when reached`() = runBlocking {
        // 正常流程中等待审批的 lane 会被运行状态门挡住（消息进队列），
        // 只有状态不一致（如 cancel 后残留）才会走到 acceptRun——此时应接管而非拒绝。
        val staleId = coordinator.acceptRun("s2", userMessage("old"))
        coordinator.waitingApproval(staleId)

        val freshId = coordinator.acceptRun("s2", userMessage("next"))

        assertNotEquals(staleId, freshId)
        assertNull(repository.operations[staleId])
        assertEquals(freshId, repository.lanes["s2" to "main"]!!.currentOperationId)
        assertEquals("aborted", repository.results["s2" to "main"]!!.outcome)
        // 接管后新消息在树中，旧消息历史保留
        assertEquals(2, repository.entries("s2").size)
    }

    @Test
    fun `active prefers lane pointer over stale active rows`() = runBlocking {
        val staleId = coordinator.acceptRun("s3", userMessage("old"))
        coordinator.suspendOperation(staleId, "进程中断")
        // 直接清 lane 指针模拟接管残留（reclaim 之外的场景）
        repository.upsertLaneForTest(repository.lanes["s3" to "main"]!!.copy(currentOperationId = null))
        val freshId = coordinator.beginRun("s3")

        assertEquals(freshId, coordinator.active("s3")!!.id)
    }

    @Test
    fun `operation exists reflects finish`() = runBlocking {
        val operationId = coordinator.acceptRun("s7", userMessage("hi"))
        assertTrue(coordinator.operationExists(operationId))
        coordinator.finish("s7", "completed", laneName = "main")
        assertNull(coordinator.active("s7"))
        assertTrue(!coordinator.operationExists(operationId))
    }

    @Test
    fun `accept run clears dangling operation pointer`() = runBlocking {
        repository.ensureLane("s3", "main").let { lane ->
            repository.upsertLaneForTest(lane.copy(currentOperationId = "ghost-op"))
        }

        val operationId = coordinator.acceptRun("s3", userMessage("fresh"))

        assertEquals(operationId, repository.lanes["s3" to "main"]!!.currentOperationId)
        assertNull(repository.results["s3" to "main"])
    }

    @Test
    fun `accept queued run reclaims suspended leftover and consumes queue item`() = runBlocking {
        val staleId = coordinator.acceptRun("s4", userMessage("old"))
        coordinator.suspendOperation(staleId, "进程中断")
        repository.enqueue(
            HarnessQueueItemEntity(
                id = "q1",
                sessionId = "s4",
                laneName = "main",
                operationId = staleId,
                queueType = "next_run",
                createdAt = 2L,
                payloadJson = "{}",
            ),
        )

        val operationId = coordinator.acceptQueuedRun("s4", "q1", userMessage("queued"))

        assertNotEquals(staleId, operationId)
        assertTrue("队列项应被消费", repository.queue("s4", "main", "next_run").isEmpty())
        assertNull(repository.operations[staleId])
    }

    @Test
    fun `usage entity maps chat usage onto ledger row`() {
        val entity = coordinator.usageEntity(
            sessionId = "s5",
            operationId = "op-1",
            entryId = "entry-1",
            provider = "deepseek",
            modelId = "deepseek-chat",
            usage = ChatUsage(
                inputTokens = 100,
                outputTokens = 40,
                reasoningTokens = 5,
                cacheReadTokens = 60,
                cacheWriteTokens = 30,
            ),
        )
        assertEquals("s5", entity.sessionId)
        assertEquals("op-1", entity.operationId)
        assertEquals("entry-1", entity.entryId)
        assertEquals("deepseek", entity.provider)
        assertEquals(100L, entity.inputTokens)
        assertEquals(40L, entity.outputTokens)
        assertEquals(5L, entity.reasoningTokens)
        assertEquals(60L, entity.cacheReadTokens)
        assertEquals(30L, entity.cacheWriteTokens)
    }

    @Test
    fun `provider settled records usage in ledger`() = runBlocking {
        val operationId = coordinator.acceptRun("s6", userMessage("hi"))
        val usage = coordinator.usageEntity(
            sessionId = "s6",
            operationId = operationId,
            entryId = null,
            provider = "openai",
            modelId = "gpt-4o-mini",
            usage = ChatUsage(inputTokens = 10, outputTokens = 5),
        )
        coordinator.providerSettled(operationId, null, usage = usage, round = 0)

        assertEquals(1, repository.usage("s6").size)
        assertEquals(10L, repository.usage("s6").single().inputTokens)
    }

    @Test
    fun `operation lifecycle emits structured events`() = runBlocking {
        val events = mutableListOf<HarnessEvent>()
        val job = GlobalScope.launch(Dispatchers.Unconfined) {
            eventBus.events.collect { events += it }
        }
        try {
            val operationId = coordinator.acceptRun("s8", userMessage("hi"))
            coordinator.providerIntent(operationId, "entry-1", 0, 1, 1)
            coordinator.providerSettled(operationId, null, round = 0)
            coordinator.finish("s8", "completed", laneName = "main")

            val kinds = events.map { it::class.simpleName }
            assertTrue(kinds.contains("OperationStarted"))
            assertTrue(kinds.contains("ProviderRoundStarted"))
            assertTrue(kinds.contains("ProviderRoundSettled"))
            assertTrue(kinds.contains("OperationFinished"))
            val finished = events.filterIsInstance<HarnessEvent.OperationFinished>().single()
            assertEquals("completed", finished.outcome)
            assertEquals("s8", finished.sessionId)
            assertEquals(operationId, finished.operationId)
        } finally {
            job.cancel()
        }
    }

    /** 全内存 fake：只实现 coordinator 依赖的事务语义，其余为简单存取。 */
    private class FakeRuntimeRepository : HarnessRuntimeRepository {
        val lanes = LinkedHashMap<Pair<String, String>, HarnessLaneEntity>()
        val operations = LinkedHashMap<String, HarnessOperationEntity>()
        val results = LinkedHashMap<Pair<String, String>, HarnessLaneResultEntity>()
        private val entryList = mutableListOf<HarnessEntryEntity>()
        private val queueItems = mutableListOf<HarnessQueueItemEntity>()
        private val usageList = mutableListOf<HarnessUsageEntity>()

        fun entries(sessionId: String) = entryList.filter { it.sessionId == sessionId }
        fun usage(sessionId: String) = usageList.filter { it.sessionId == sessionId }
        fun queue(sessionId: String, laneName: String, queueType: String) =
            queueItems.filter { it.sessionId == sessionId && it.laneName == laneName && it.queueType == queueType }

        fun upsertLaneForTest(lane: HarnessLaneEntity) {
            lanes[lane.sessionId to lane.name] = lane
        }

        override suspend fun ensureLane(sessionId: String, laneName: String, atEntryId: String?): HarnessLaneEntity {
            lanes[sessionId to laneName]?.let { return it }
            val lane = HarnessLaneEntity(
                sessionId = sessionId,
                name = laneName,
                leafId = atEntryId,
                updatedAt = 0L,
            )
            upsertLaneForTest(lane)
            return lane
        }

        override suspend fun findLane(sessionId: String, laneName: String) = lanes[sessionId to laneName]
        override fun observeLanes(sessionId: String): Flow<List<HarnessLaneEntity>> = flowOf(emptyList())
        override suspend fun listEntries(sessionId: String) = entries(sessionId)
        override suspend fun listEntriesInRange(start: Long?, end: Long?) =
            entryList.filter { (start == null || it.createdAt >= start) && (end == null || it.createdAt < end) }

        override suspend fun countEntriesInRange(start: Long?, end: Long?) = listEntriesInRange(start, end).size

        // 用量聚合（SQL 层）：生产实现走 json_extract + GROUP BY 以避免 OOM。
        // 本 Fake 不模拟聚合语义，返回空列表即可——本测试不涉及用量统计。
        override suspend fun aggregateUsageInRange(start: Long?, end: Long?) =
            emptyList<UsageAggregateRow>()

        override suspend fun aggregateDailyCounts(start: Long?, end: Long?, tzOffsetMs: Long) =
            emptyList<DailyCountRow>()
        override suspend fun branch(sessionId: String, leafId: String?): List<HarnessEntryEntity> {
            if (leafId == null) return emptyList()
            val byId = entryList.associateBy { it.id }
            val chain = ArrayList<HarnessEntryEntity>()
            var cursor: String? = leafId
            while (cursor != null) {
                val entry = byId[cursor] ?: error("Missing entry $cursor")
                chain += entry
                cursor = entry.parentId
            }
            return chain.asReversed()
        }

        override suspend fun appendToLane(sessionId: String, laneName: String, entry: HarnessEntryEntity) {
            entryList += entry
            lanes[sessionId to laneName] = lanes[sessionId to laneName]!!.copy(leafId = entry.id)
        }

        override suspend fun moveLane(sessionId: String, laneName: String, leafId: String?) {
            lanes[sessionId to laneName] = lanes[sessionId to laneName]!!.copy(leafId = leafId)
        }

        override suspend fun clearLaneOperation(sessionId: String, laneName: String) {
            lanes[sessionId to laneName]?.let { upsertLaneForTest(it.copy(currentOperationId = null)) }
        }

        override suspend fun findOperation(operationId: String) = operations[operationId]
        override suspend fun listActiveOperations(sessionId: String) =
            operations.values.filter { it.sessionId == sessionId && it.status in ACTIVE_STATUSES }

        override suspend fun acceptOperation(entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity) {
            entryList += entry
            operations[operation.id] = operation
            upsertLaneForTest(lane)
        }

        override suspend fun acceptQueuedOperation(queueItemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity, operation: HarnessOperationEntity) {
            queueItems.removeAll { it.id == queueItemId }
            acceptOperation(entry, lane, operation)
        }

        override suspend fun acceptRunTakeover(queueItemId: String?, entry: HarnessEntryEntity?, lane: HarnessLaneEntity,
            operation: HarnessOperationEntity, previousLane: HarnessLaneEntity,
            previousResult: HarnessLaneResultEntity?, taskId: String?) {
            if (previousResult != null) finishOperation(previousResult, previousLane.copy(currentOperationId = null))
            if (entry == null) beginOperation(lane, operation)
            else if (queueItemId != null) acceptQueuedOperation(queueItemId, entry, lane, operation)
            else acceptOperation(entry, lane, operation)
        }

        override suspend fun beginOperation(lane: HarnessLaneEntity, operation: HarnessOperationEntity) {
            operations[operation.id] = operation
            upsertLaneForTest(lane)
        }

        override suspend fun saveOperation(operation: HarnessOperationEntity) {
            operations[operation.id] = operation
        }

        override suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?, operation: HarnessOperationEntity, lane: HarnessLaneEntity) {
            if (entry != null) entryList += entry
            if (usage != null) usageList += usage
            operations[operation.id] = operation
            upsertLaneForTest(lane)
        }

        override suspend fun finishOperation(result: HarnessLaneResultEntity, lane: HarnessLaneEntity) {
            queueItems.removeAll { it.operationId == result.operationId && it.queueType != "next_run" }
            operations.remove(result.operationId)
            results[result.sessionId to result.laneName] = result
            upsertLaneForTest(lane)
        }

        override suspend fun enqueue(item: HarnessQueueItemEntity) {
            queueItems += item
        }

        override suspend fun listQueue(sessionId: String, laneName: String, queueType: String) =
            queue(sessionId, laneName, queueType)

        override suspend fun listAllQueues(sessionId: String, laneName: String) =
            queueItems.filter { it.sessionId == sessionId && it.laneName == laneName }

        override suspend fun cancelQueued(itemId: String) {
            queueItems.removeAll { it.id == itemId }
        }

        override suspend fun clearQueue(sessionId: String, laneName: String, queueType: String) {
            queueItems.removeAll { it.sessionId == sessionId && it.laneName == laneName && it.queueType == queueType }
        }

        override suspend fun consumeQueued(itemId: String, entry: HarnessEntryEntity, lane: HarnessLaneEntity) {
            queueItems.removeAll { it.id == itemId }
            entryList += entry
            upsertLaneForTest(lane)
        }

        override suspend fun recordUsage(usage: HarnessUsageEntity) {
            usageList += usage
        }

        override suspend fun listUsage(sessionId: String) = usage(sessionId)

        override suspend fun deleteSessionData(sessionId: String) {
            lanes.keys.removeAll { it.first == sessionId }
            operations.values.removeAll { it.sessionId == sessionId }
            results.keys.removeAll { it.first == sessionId }
            entryList.removeAll { it.sessionId == sessionId }
            queueItems.removeAll { it.sessionId == sessionId }
            usageList.removeAll { it.sessionId == sessionId }
        }

        companion object {
            private val ACTIVE_STATUSES = setOf("running", "waiting_approval", "suspended", "aborting")
        }
    }
}
