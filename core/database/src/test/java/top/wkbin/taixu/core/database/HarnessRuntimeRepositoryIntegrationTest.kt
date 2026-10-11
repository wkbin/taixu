package top.wkbin.taixu.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import top.wkbin.taixu.core.database.task.AgentTaskEntity
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 真实 Room（in-memory）上的持久化事务集成测试：
 * 覆盖 entry tree 事务原子性、并发 append 的 leaf 重接、branch 投影与级联删除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HarnessRuntimeRepositoryIntegrationTest {

    private suspend fun queuedTask(sessionId: String = "s", status: String = "QUEUED") {
        database.agentTaskDao().upsertTask(AgentTaskEntity(
            id = "task", sessionId = sessionId, title = "input", description = "input",
            status = status, createdAt = 1, updatedAt = 1))
    }

    @Test fun `task admission commits recovery state with input and operation`() = runBlocking {
        queuedTask()
        repository.acceptTaskOperation("task", entry("input", "s", null),
            lane("s", leafId = "input", currentOperationId = "op"), operation("op", "s"))
        val restored = database.agentTaskDao().getTaskById("task")!!
        assertEquals("RUNNING", restored.status)
        assertEquals("op", restored.operationId)
        assertEquals(1, restored.attemptCount)
        assertNotNull(repository.findEntry("s", "input"))
        assertNotNull(repository.findOperation("op"))
    }

    @Test fun `queued task consumption commits its recovery state in the same transaction`() = runBlocking {
        queuedTask()
        repository.enqueue(HarnessQueueItemEntity("queue", "s", "main", null, "next_run", 1, "{}"))
        repository.acceptTaskOperation("task", entry("input", "s", null),
            lane("s", leafId = "input", currentOperationId = "op"), operation("op", "s"), "queue")
        assertTrue(repository.listQueue("s", "main", "next_run").isEmpty())
        assertEquals("RUNNING", database.agentTaskDao().getTaskById("task")!!.status)
        assertEquals("op", database.agentTaskDao().getTaskById("task")!!.operationId)
    }

    @Test fun `failed entry commit rolls back task claim and preserves queued input`() = runBlocking {
        queuedTask()
        repository.enqueue(HarnessQueueItemEntity("queue", "s", "main", null, "next_run", 1, "{}"))
        dao.insertEntry(entry("input", "other-session", null))
        try {
            dao.acceptTaskOperation("task", entry("input", "s", null),
                lane("s", leafId = "input", currentOperationId = "op"), operation("op", "s"), "queue")
            fail("must roll back")
        } catch (_: IllegalStateException) { }
        val task = database.agentTaskDao().getTaskById("task")!!
        assertEquals("QUEUED", task.status)
        assertEquals(0, task.attemptCount)
        assertNull(task.operationId)
        assertEquals(1, repository.listQueue("s", "main", "next_run").size)
        assertNull(repository.findOperation("op"))
        assertNull(repository.findLane("s", "main"))
    }

    @Test fun `cancelled or foreign task cannot accept input or create operation`() = runBlocking {
        for ((owner, status) in listOf("s" to "CANCELLED", "other" to "QUEUED")) {
            queuedTask(owner, status)
            try {
                repository.acceptTaskOperation("task", entry("input", "s", null),
                    lane("s", leafId = "input", currentOperationId = "op"), operation("op", "s"))
                fail("must reject")
            } catch (_: IllegalStateException) { }
            assertNull(repository.findEntry("s", "input"))
            assertNull(repository.findOperation("op"))
            assertEquals(status, database.agentTaskDao().getTaskById("task")!!.status)
        }
    }

    private lateinit var database: AppDatabase
    private lateinit var dao: HarnessRuntimeDao
    private lateinit var repository: HarnessRuntimeRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.harnessRuntimeDao()
        repository = RoomHarnessRuntimeRepository(dao, HarnessBlobStore(context))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun entry(id: String, sessionId: String, parentId: String?) = HarnessEntryEntity(
        id = id,
        sessionId = sessionId,
        parentId = parentId,
        createdAt = System.nanoTime(),
        entryType = "message",
        customType = "user",
        payloadJson = "{}",
    )

    private fun lane(sessionId: String, name: String = "main", leafId: String? = null, currentOperationId: String? = null) = HarnessLaneEntity(
        sessionId = sessionId,
        name = name,
        leafId = leafId,
        currentOperationId = currentOperationId,
        updatedAt = System.currentTimeMillis(),
    )

    private fun operation(id: String, sessionId: String, status: String = "running") = HarnessOperationEntity(
        id = id,
        sessionId = sessionId,
        laneName = "main",
        kind = "run",
        status = status,
        phase = "checkpoint",
        startedAt = 0L,
        updatedAt = 0L,
        startLeafId = null,
        stateJson = "{}",
    )

    @Test
    fun `settleEffect atomically writes entry usage operation and lane`() = runBlocking {
        val sessionId = "s1"
        val op = operation("op-1", sessionId)
        dao.acceptOperation(entry("e1", sessionId, null), lane(sessionId, leafId = "e1"), op)

        val usage = HarnessUsageEntity(
            id = "u1", sessionId = sessionId, operationId = "op-1", entryId = "e2",
            provider = "deepseek", modelId = "deepseek-chat",
            inputTokens = 100, outputTokens = 40, createdAt = 1L,
        )
        repository.settleEffect(
            entry = entry("e2", sessionId, "e1"),
            usage = usage,
            operation = op.copy(status = "running", phase = "provider_settled"),
            lane = lane(sessionId, leafId = "e2", currentOperationId = "op-1"),
        )

        assertEquals("e2", repository.findLane(sessionId, "main")!!.leafId)
        assertNotNull(repository.findOperation("op-1"))
        assertEquals(2, repository.listEntries(sessionId).size)
        assertEquals(1, repository.listUsage(sessionId).size)
        assertEquals("provider_settled", repository.findOperation("op-1")!!.phase)
    }

    @Test
    fun `appendToLane rebases concurrent stale leaf writes into a chain`() = runBlocking {
        val sessionId = "s2"
        repository.ensureLane(sessionId, "main")

        // Two coroutines append from the same stale parent; both should succeed as a linear chain.
        val results = (1..2).map { index ->
            async(Dispatchers.IO) {
                runCatching {
                    repository.appendToLane(sessionId, "main", entry("race-$index", sessionId, null))
                }
            }
        }.awaitAll()

        assertEquals("并发 append 都应成功（落后的 parent 会接到当前 leaf）", 2, results.count { it.isSuccess })
        results.forEachIndexed { index, result ->
            assertTrue("append #$index failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        }
        val leaf = repository.findLane(sessionId, "main")!!.leafId
        val branch = repository.branch(sessionId, leaf)
        assertEquals(2, branch.size)
        assertEquals(branch[0].id, branch[1].parentId)
    }

    @Test
    fun `branch projects only ancestors of lane leaf`() = runBlocking {
        val sessionId = "s3"
        // 直接插入 entries 构成分叉树（b 与 leaf 同为 root 的子节点）：
        // lane 分支/回退不经过 appendToLane 的单叉守卫，此处模拟已有分叉历史。
        val root = entry("root", sessionId, null)
        val a = entry("a", sessionId, "root")
        val b = entry("b", sessionId, "root")
        val leaf = entry("leaf", sessionId, "b")
        listOf(root, a, b, leaf).forEach { dao.insertEntry(it) }

        // lane 指向 leaf → 投影 root→b→leaf（a 在另一分支，不出现）
        repository.moveLane(sessionId, "main", "leaf")
        assertEquals(listOf("root", "b", "leaf"), repository.branch(sessionId, "leaf").map { it.id })

        // lane 移动到 b → 投影 root→b（回退丢弃 leaf 但历史保留）
        repository.moveLane(sessionId, "main", "b")
        assertEquals(listOf("root", "b"), repository.branch(sessionId, "b").map { it.id })
        assertEquals(4, repository.listEntries(sessionId).size)
    }

    @Test
    fun `branch tail and latest typed entry are bounded inside Room`() = runBlocking {
        val sessionId = "s-tail"
        var parentId: String? = null
        repeat(10) { index ->
            val type = if (index == 4) "compaction" else "message"
            val next = entry("e$index", sessionId, parentId).copy(entryType = type)
            dao.insertEntry(next)
            parentId = next.id
        }

        assertEquals(listOf("e7", "e8", "e9"), repository.branchTail(sessionId, "e9", 3).map { it.id })
        assertEquals("e4", repository.latestBranchEntryOfType(sessionId, "e9", "compaction")?.id)
    }

    @Test
    fun `branchWindow returns entries at or above sequence plus recall contexts`() = runBlocking {
        val sessionId = "s-window"
        var parentId: String? = null
        repeat(10) { index ->
            val type = when (index) {
                1 -> "recall_context"
                4 -> "compaction"
                else -> "message"
            }
            val next = entry("e$index", sessionId, parentId).copy(entryType = type)
            dao.insertEntry(next)
            parentId = next.id
        }

        val all = repository.branch(sessionId, "e9")
        val e4Seq = all.first { it.id == "e4" }.sequence
        val window = repository.branchWindow(sessionId, "e9", e4Seq)

        assertEquals(listOf("e1", "e4", "e5", "e6", "e7", "e8", "e9"), window.map { it.id })
    }

    @Test
    fun `branch search and indexed read stay on active branch and treat wildcards literally`() = runBlocking {
        val sessionId = "s-search"
        val root = entry("root", sessionId, null).copy(payloadJson = "literal 100% complete")
        val active = entry("active", sessionId, "root").copy(payloadJson = "active needle")
        val abandoned = entry("abandoned", sessionId, "root").copy(payloadJson = "abandoned needle")
        listOf(root, active, abandoned).forEach { dao.insertEntry(it) }

        assertEquals(listOf("active"), repository.searchBranch(sessionId, "active", "needle", 10).map { it.id })
        assertEquals(listOf("root"), repository.searchBranch(sessionId, "active", "100%", 10).map { it.id })
        assertEquals("active", repository.branchEntryAt(sessionId, "active", 1)?.id)
        assertNull(repository.branchEntryAt(sessionId, "active", 2))
    }

    @Test
    fun `finishOperation clears lane pointer and removes operation row`() = runBlocking {
        val sessionId = "s4"
        val op = operation("op-4", sessionId)
        dao.acceptOperation(entry("e1", sessionId, null), lane(sessionId, leafId = "e1", currentOperationId = "op-4"), op)

        repository.finishOperation(
            HarnessLaneResultEntity(sessionId, "main", "op-4", "completed", "e1", null, 2L),
            lane(sessionId, leafId = "e1", currentOperationId = null),
        )

        assertNull(repository.findOperation("op-4"))
        assertNull(repository.findLane(sessionId, "main")!!.currentOperationId)
        assertTrue(repository.listActiveOperations(sessionId).isEmpty())
    }

    @Test
    fun `finishOperation promotes leftover steer in order and drops follow-up`() = runBlocking {
        val sessionId = "s-steer"
        dao.acceptOperation(
            entry("e1", sessionId, null),
            lane(sessionId, leafId = "e1", currentOperationId = "op-steer"),
            operation("op-steer", sessionId),
        )
        dao.insertQueueItem(HarnessQueueItemEntity("s-late", sessionId, "main", "op-steer", "steer", 30L, "late"))
        dao.insertQueueItem(HarnessQueueItemEntity("s-early", sessionId, "main", "op-steer", "steer", 10L, "early"))
        dao.insertQueueItem(HarnessQueueItemEntity("s-mid", sessionId, "main", null, "steer", 20L, "mid"))
        dao.insertQueueItem(HarnessQueueItemEntity("f-up", sessionId, "main", "op-steer", "follow_up", 15L, "follow"))

        repository.finishOperation(
            HarnessLaneResultEntity(sessionId, "main", "op-steer", "failed", "e1", null, 2L),
            lane(sessionId, leafId = "e1", currentOperationId = null),
        )

        val next = repository.listQueue(sessionId, "main", "next_run")
        assertEquals(listOf("s-early", "s-late"), next.map { it.id })
        assertEquals(listOf("early", "late"), next.map { it.payloadJson })
        assertTrue(next.all { it.operationId == null })
        val leftover = repository.listQueue(sessionId, "main", "steer")
        assertEquals("s-mid", leftover.single().id)
        assertNull(leftover.single().operationId)
        assertTrue(repository.listQueue(sessionId, "main", "follow_up").isEmpty())
        assertNull(repository.findOperation("op-steer"))
    }

    @Test
    fun `finishOperation drops operation steer when a next run is already waiting`() = runBlocking {
        val sessionId = "s-steer-keep"
        dao.acceptOperation(
            entry("e1", sessionId, null),
            lane(sessionId, leafId = "e1", currentOperationId = "op-keep"),
            operation("op-keep", sessionId),
        )
        dao.insertQueueItem(HarnessQueueItemEntity("n1", sessionId, "main", "op-keep", "next_run", 5L, "queued"))
        dao.insertQueueItem(HarnessQueueItemEntity("s1", sessionId, "main", "op-keep", "steer", 10L, "steer"))
        dao.insertQueueItem(HarnessQueueItemEntity("f1", sessionId, "main", "op-keep", "follow_up", 11L, "follow"))

        repository.finishOperation(
            HarnessLaneResultEntity(sessionId, "main", "op-keep", "completed", "e1", null, 2L),
            lane(sessionId, leafId = "e1", currentOperationId = null),
        )

        val next = repository.listQueue(sessionId, "main", "next_run")
        assertEquals(listOf("n1"), next.map { it.id })
        assertNull(next.single().operationId)
        assertTrue(repository.listQueue(sessionId, "main", "steer").isEmpty())
        assertTrue(repository.listQueue(sessionId, "main", "follow_up").isEmpty())
    }

    @Test
    fun `deleteSessionData cascades across all runtime tables`() = runBlocking {
        val sessionId = "s5"
        val op = operation("op-5", sessionId)
        dao.acceptOperation(entry("e1", sessionId, null), lane(sessionId, leafId = "e1", currentOperationId = "op-5"), op)
        dao.insertUsage(
            HarnessUsageEntity(id = "u5", sessionId = sessionId, operationId = "op-5", entryId = null, provider = null, modelId = null, createdAt = 1L),
        )
        dao.insertQueueItem(
            HarnessQueueItemEntity(id = "q5", sessionId = sessionId, laneName = "main", operationId = "op-5", queueType = "next_run", createdAt = 1L, payloadJson = "{}"),
        )

        repository.deleteSessionData(sessionId)

        assertTrue(repository.listEntries(sessionId).isEmpty())
        assertTrue(repository.listUsage(sessionId).isEmpty())
        assertTrue(repository.listQueue(sessionId, "main", "next_run").isEmpty())
        assertNull(repository.findLane(sessionId, "main"))
        assertNull(repository.findOperation("op-5"))
    }

    @Test
    fun `consumeQueued atomically removes item and appends entry`() = runBlocking {
        val sessionId = "s6"
        repository.ensureLane(sessionId, "main")
        val queueItem = HarnessQueueItemEntity(
            id = "q6", sessionId = sessionId, laneName = "main", operationId = null,
            queueType = "next_run", createdAt = 1L, payloadJson = "{}",
        )
        dao.insertQueueItem(queueItem)

        val entry = entry("consumed", sessionId, null)
        repository.consumeQueued(queueItem.id, entry, lane(sessionId, leafId = "consumed"))

        assertTrue(repository.listQueue(sessionId, "main", "next_run").isEmpty())
        assertEquals(1, repository.listEntries(sessionId).size)
        assertEquals("consumed", repository.findLane(sessionId, "main")!!.leafId)
    }

    @Test
    fun `large payload offloads to sandbox file and transparently restores on query`() = runBlocking {
        val sessionId = "session-large-test"
        repository.ensureLane(sessionId, "main")

        // 构造一个 120KB 的超大文本（远超 48KB 阈值）
        val largeText = "A".repeat(120 * 1024)
        val largeEntry = entry("large-1", sessionId, null).copy(payloadJson = largeText)

        repository.appendToLane(sessionId, "main", largeEntry)

        // 验证 DAO 数据库中存储的是引用指针而非 120KB 大文本
        val rawInDb = dao.listEntries(sessionId).first()
        assertTrue(rawInDb.payloadJson.startsWith(HarnessBlobStore.BLOB_PREFIX))

        // 验证 Repository 查询时自动透明解引用并还原完整大文本
        val restoredFromRepo = repository.listEntries(sessionId).first()
        assertEquals(largeText, restoredFromRepo.payloadJson)

        // 验证分支树查询也透明还原
        val branchList = repository.branch(sessionId, "large-1")
        assertEquals(1, branchList.size)
        assertEquals(largeText, branchList.first().payloadJson)

        // 验证删除会话时级联清理外置沙箱文件
        repository.deleteSessionData(sessionId)
        assertTrue(repository.listEntries(sessionId).isEmpty())
    }

    @Test
    fun `duplicate entry id does not throw UNIQUE constraint exception and safely resolves unique id`() = runBlocking {
        val sessionId = "session-duplicate-id-test"
        repository.ensureLane(sessionId, "main")

        // 第一次追加 ID 为 "call_0" 的 entry
        val entry1 = entry("call_0", sessionId, null).copy(payloadJson = "{\"call\":\"first\"}")
        repository.appendToLane(sessionId, "main", entry1)

        val laneAfterFirst = repository.findLane(sessionId, "main")!!
        assertEquals("call_0", laneAfterFirst.leafId)

        // 模拟另一轮再次生成同名 ID "call_0"，内容不同
        val entry2 = entry("call_0", sessionId, laneAfterFirst.leafId).copy(payloadJson = "{\"call\":\"second\"}")
        repository.appendToLane(sessionId, "main", entry2)

        val laneAfterSecond = repository.findLane(sessionId, "main")!!
        assertTrue("Second entry must have acquired a unique suffix", laneAfterSecond.leafId!!.startsWith("call_0_"))

        // 两条记录均成功安全落库，树链接不断裂
        val entries = repository.listEntries(sessionId)
        assertEquals(2, entries.size)
        assertEquals("call_0", entries[0].id)
        assertEquals(laneAfterSecond.leafId, entries[1].id)
        assertEquals("call_0", entries[1].parentId)
    }

    @Test
    fun `idempotent identical entry write safely preserves single entry without crash`() = runBlocking {
        val sessionId = "session-idempotent-test"
        repository.ensureLane(sessionId, "main")

        val entry = entry("idempotent_1", sessionId, null).copy(payloadJson = "{\"text\":\"hello\"}")
        repository.appendToLane(sessionId, "main", entry)

        // 重复插入完全相同的 entry（如恢复重试场景）
        repository.appendToLane(sessionId, "main", entry)

        val entries = repository.listEntries(sessionId)
        assertEquals(1, entries.size)
        assertEquals("idempotent_1", entries.first().id)
    }

    @Test
    fun `appendEntry rolls back lane when insert is ignored for conflicting id`() = runBlocking {
        val sessionId = "session-ignore-conflict"
        repository.ensureLane(sessionId, "main")
        repository.appendToLane(sessionId, "main", entry("root", sessionId, null).copy(payloadJson = "{\"a\":1}"))

        // Simulate a race / missed rename: another row already owns this primary key.
        dao.insertEntry(entry("child", "other-session", null).copy(payloadJson = "{\"hijacked\":true}"))

        val leafBefore = repository.findLane(sessionId, "main")!!.leafId
        val failed = runCatching {
            dao.appendEntry(
                entry("child", sessionId, leafBefore).copy(payloadJson = "{\"a\":2}"),
                lane(sessionId, leafId = "child"),
            )
        }
        assertTrue("Conflicting IGNORE must abort the transaction", failed.isFailure)
        assertEquals(leafBefore, repository.findLane(sessionId, "main")!!.leafId)
        assertEquals("other-session", dao.findEntry("child")!!.sessionId)
    }

    @Test
    fun `local offset day buckets split a UTC day without loading payloads`() = runBlocking {
        val sessionId = "stats-daily"
        val shanghaiOffsetMs = 8L * 60 * 60 * 1000
        // UTC 2026-09-17 15:00 → 上海 23:00（17 日）；UTC 16:00 → 上海次日 00:00。
        val utcSep17Afternoon = Instant.parse("2026-09-17T15:00:00Z").toEpochMilli()
        val utcSep17Evening = Instant.parse("2026-09-17T16:00:00Z").toEpochMilli()
        assertEquals(utcSep17Afternoon / 86_400_000L, utcSep17Evening / 86_400_000L)

        dao.insertEntry(entry("user-1", sessionId, null).copy(createdAt = utcSep17Afternoon))
        dao.insertEntry(entry("asst-1", sessionId, "user-1").copy(createdAt = utcSep17Evening, customType = "assistant"))

        // Robolectric SQLite 未编 JSON1，这里只验证与 DAO 相同的本地日切表达式。
        val db = database.openHelper.readableDatabase
        db.query(
            "SELECT CAST((createdAt + ?) / 86400000 AS INTEGER) AS d, COUNT(*) FROM harness_entries GROUP BY d ORDER BY d",
            arrayOf(shanghaiOffsetMs.toString()),
        ).use { cursor ->
            val days = mutableListOf<Long>()
            while (cursor.moveToNext()) days += cursor.getLong(0)
            assertEquals("Shanghai offset must split the UTC day", 2, days.size)
            assertEquals((utcSep17Afternoon + shanghaiOffsetMs) / 86_400_000L, days[0])
            assertEquals((utcSep17Evening + shanghaiOffsetMs) / 86_400_000L, days[1])
        }
        db.query(
            "SELECT COUNT(DISTINCT CAST(createdAt / 86400000 AS INTEGER)) FROM harness_entries",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("UTC grouping would keep both rows on one day", 1, cursor.getInt(0))
        }
    }
}
