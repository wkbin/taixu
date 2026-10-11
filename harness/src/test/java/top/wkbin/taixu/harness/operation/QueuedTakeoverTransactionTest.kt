package top.wkbin.taixu.harness.operation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.database.*
import top.wkbin.taixu.core.database.task.AgentTaskEntity
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.PendingMessage
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.events.HarnessEventBus

/** Real Room failures on both sides of the old-run/new-run boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueuedTakeoverTransactionTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: HarnessRuntimeRepository
    private fun operations(repo: HarnessRuntimeRepository = repository) = OperationCoordinator(repo, Json, HarnessEventBus())
    private fun input(id: String = "accepted") = UserMessage(id, 5, "later", listOf("original-image"))

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
    }
    @After fun tearDown() { database.close() }

    private suspend fun enqueue(id: String = "q1", old: String? = null, session: String = "s",
        lane: String = "main", type: String = "next_run", at: Long = 2) {
        val pending = PendingMessage("later", listOf("original-image"), createdAt = at, taskId = id)
        repository.enqueue(HarnessQueueItemEntity(id, session, lane, old, type, at,
            Json.encodeToString(PendingMessage.serializer(), pending)))
        database.agentTaskDao().upsertTask(AgentTaskEntity(id = id, sessionId = "s", title = "later",
            description = "later", status = "QUEUED", createdAt = at, updatedAt = at))
    }
    private suspend fun assertQueued(id: String = "q1") {
        val task = database.agentTaskDao().getTaskById(id)!!
        assertEquals("QUEUED", task.status)
        assertNull(task.operationId)
        assertEquals(0, task.attemptCount)
    }

    @Test fun `completion preserves ordered next runs and discards only old run instructions`() = runBlocking {
        val ops = operations()
        val old = ops.acceptRun("s", UserMessage("old", 1, "first"))
        enqueue("q1", old)
        enqueue("q2", old, at = 3)
        enqueue("steer", old, type = "steer")
        enqueue("follow", old, type = "follow_up")
        enqueue("other", "another-operation", type = "steer")
        ops.finish("s", "completed")
        val remaining = repository.listQueue("s", "main", "next_run")
        assertEquals(listOf("q1", "q2"), remaining.map { it.id })
        assertTrue(remaining.all { it.operationId == null })
        assertEquals(listOf("other"), repository.listQueue("s", "main", "steer").map { it.id })
        assertTrue(repository.listQueue("s", "main", "follow_up").isEmpty())
        assertNull(repository.findOperation(old))
        assertNull(repository.findLane("s", "main")!!.currentOperationId)
        assertQueued()
        assertQueued("q2")
    }

    @Test fun `failure before takeover transaction preserves old run and queued task through recreation`() = runBlocking {
        val old = operations().acceptRun("s", UserMessage("old", 1, "first"))
        enqueue("q1", old)
        enqueue("q2", old, at = 3)
        val failing = object : HarnessRuntimeRepository by repository {
            override suspend fun acceptRunTakeover(queueItemId: String?, entry: HarnessEntryEntity?, lane: HarnessLaneEntity,
                operation: HarnessOperationEntity, previousLane: HarnessLaneEntity,
                previousResult: HarnessLaneResultEntity?, taskId: String?) {
                error("process stopped before new admission")
            }
        }
        assertTrue(runCatching { operations(failing).acceptQueuedRun("s", "q1", input(), "q1") }.isFailure)
        assertNotNull(repository.findOperation(old))
        assertEquals(old, repository.findLane("s", "main")!!.currentOperationId)
        assertNull(repository.findEntry("s", input().id))
        assertQueued()
        val restored = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val pending = restored.listQueue("s", "main", "next_run")
        assertEquals(listOf("q1", "q2"), pending.map { it.id })
        assertEquals(listOf("original-image"), Json.decodeFromString<PendingMessage>(pending.first().payloadJson).imageUrls)
        val fresh = operations(restored).acceptQueuedRun("s", "q1", input(), "q1")
        assertEquals(fresh, database.agentTaskDao().getTaskById("q1")!!.operationId)
        assertEquals(1, database.agentTaskDao().getTaskById("q1")!!.attemptCount)
        assertEquals(listOf("q2"), restored.listQueue("s", "main", "next_run").map { it.id })
        assertEquals(input(), Json.decodeFromString<HarnessMessage>(restored.findEntry("s", input().id)!!.payloadJson))
        assertQueued("q2")
    }

    @Test fun `database failure after claims rolls back old completion with task and queue`() = runBlocking {
        val ops = operations()
        val old = ops.acceptRun("s", UserMessage("old", 1, "first"))
        enqueue(old = old)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_input BEFORE INSERT ON harness_entries
            WHEN NEW.id = 'accepted' BEGIN SELECT RAISE(ABORT, 'input unavailable'); END
        """)
        assertTrue(runCatching { ops.acceptQueuedRun("s", "q1", input(), "q1") }.isFailure)
        assertNotNull(repository.findOperation(old))
        assertEquals(old, repository.findLane("s", "main")!!.currentOperationId)
        assertNull(repository.findEntry("s", input().id))
        assertEquals("q1", repository.listQueue("s", "main", "next_run").single().id)
        assertQueued()
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_input")
        val fresh = operations().acceptQueuedRun("s", "q1", input(), "q1")
        assertEquals(fresh, database.agentTaskDao().getTaskById("q1")!!.operationId)
        assertTrue(repository.listQueue("s", "main", "next_run").isEmpty())
    }

    @Test fun `old finish transaction failure rolls back queue detachment and lane cleanup`() = runBlocking {
        val ops = operations()
        val old = ops.acceptRun("s", UserMessage("old", 1, "first"))
        enqueue(old = old)
        enqueue("steer", old, type = "steer")
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_finish BEFORE INSERT ON harness_lane_results
            BEGIN SELECT RAISE(ABORT, 'finish unavailable'); END
        """)
        assertTrue(runCatching { ops.acceptQueuedRun("s", "q1", input(), "q1") }.isFailure)
        assertEquals(old, repository.findLane("s", "main")!!.currentOperationId)
        assertNotNull(repository.findOperation(old))
        assertEquals(old, repository.listQueue("s", "main", "next_run").single().operationId)
        assertEquals("steer", repository.listQueue("s", "main", "steer").single().id)
        assertNull(repository.findEntry("s", input().id))
        assertQueued()
    }

    private suspend fun rejectQueue(session: String = "s", lane: String = "main", type: String = "next_run") {
        val old = operations().acceptRun("s", UserMessage("old", 1, "first"))
        enqueue(session = session, lane = lane, type = type)
        assertTrue(runCatching { operations().acceptQueuedRun("s", "q1", input(), "q1") }.isFailure)
        assertQueued()
        assertNull(repository.findEntry("s", input().id))
        assertEquals(old, repository.findLane("s", "main")!!.currentOperationId)
        assertNotNull(repository.findOperation(old))
        assertEquals("q1", repository.listQueue(session, lane, type).single().id)
    }
    @Test fun `foreign session queue cannot be claimed`() = runBlocking { rejectQueue(session = "other") }
    @Test fun `foreign lane queue cannot be claimed`() = runBlocking { rejectQueue(lane = "child") }
    @Test fun `steering queue cannot be admitted as a next run`() = runBlocking { rejectQueue(type = "steer") }
    @Test fun `missing queue rolls back task admission`() = runBlocking {
        val old = operations().acceptRun("s", UserMessage("old", 1, "first"))
        enqueue(old = old)
        repository.cancelQueued("q1")
        assertTrue(runCatching { operations().acceptQueuedRun("s", "q1", input(), "q1") }.isFailure)
        assertQueued()
        assertNull(repository.findEntry("s", input().id))
        assertEquals(old, repository.findLane("s", "main")!!.currentOperationId)
        assertNotNull(repository.findOperation(old))
    }

    @Test fun `consumed queue cannot start a second run without a task`() = runBlocking {
        enqueue()
        val ops = operations()
        ops.acceptQueuedRun("s", "q1", input())
        ops.finish("s", "completed")
        assertTrue(runCatching { ops.acceptQueuedRun("s", "q1", input("duplicate")) }.isFailure)
        assertNull(repository.findEntry("s", "duplicate"))
        assertEquals(listOf(input().id), repository.listEntries("s").map { it.id })
        assertNull(repository.findLane("s", "main")!!.currentOperationId)
    }

    @Test fun `cancelled task cannot end the previous run or consume its queued successor`() = runBlocking {
        val old = operations().acceptRun("s", UserMessage("old", 1, "first"))
        enqueue(old = old)
        val task = database.agentTaskDao().getTaskById("q1")!!
        database.agentTaskDao().upsertTask(task.copy(status = "CANCELLED"))
        assertTrue(runCatching { operations().acceptQueuedRun("s", "q1", input(), "q1") }.isFailure)
        assertEquals(old, repository.findLane("s", "main")!!.currentOperationId)
        assertNotNull(repository.findOperation(old))
        assertEquals(old, repository.listQueue("s", "main", "next_run").single().operationId)
        assertNull(repository.findEntry("s", input().id))
        assertEquals("CANCELLED", database.agentTaskDao().getTaskById("q1")!!.status)
    }

    @Test fun `concurrent history append rejects stale takeover without losing the newer leaf`() = runBlocking {
        val old = operations().acceptRun("s", UserMessage("old", 1, "first"))
        enqueue(old = old)
        val concurrent = UserMessage("concurrent", 4, "newer input")
        val racing = object : HarnessRuntimeRepository by repository {
            override suspend fun acceptRunTakeover(queueItemId: String?, entry: HarnessEntryEntity?, lane: HarnessLaneEntity,
                operation: HarnessOperationEntity, previousLane: HarnessLaneEntity,
                previousResult: HarnessLaneResultEntity?, taskId: String?) {
                repository.appendToLane("s", "main", HarnessEntryEntity(id = concurrent.id, sessionId = "s", parentId = previousLane.leafId,
                    createdAt = concurrent.createdAt, entryType = "message", customType = "user",
                    payloadJson = Json.encodeToString(HarnessMessage.serializer(), concurrent)))
                repository.acceptRunTakeover(queueItemId, entry, lane, operation, previousLane, previousResult, taskId)
            }
        }
        assertTrue(runCatching { operations(racing).acceptQueuedRun("s", "q1", input(), "q1") }.isFailure)
        assertEquals(old, repository.findLane("s", "main")!!.currentOperationId)
        assertEquals(concurrent.id, repository.findLane("s", "main")!!.leafId)
        assertNotNull(repository.findOperation(old))
        assertNull(repository.findEntry("s", input().id))
        assertEquals(old, repository.listQueue("s", "main", "next_run").single().operationId)
        assertQueued()
    }

    @Test fun `concurrent run claim cannot be overwritten by stale queued takeover`() = runBlocking {
        val old = operations().acceptRun("s", UserMessage("old", 1, "first"))
        enqueue(old = old)
        val winner = repository.findOperation(old)!!.copy(id = "winner")
        val racing = object : HarnessRuntimeRepository by repository {
            override suspend fun acceptRunTakeover(queueItemId: String?, entry: HarnessEntryEntity?, lane: HarnessLaneEntity,
                operation: HarnessOperationEntity, previousLane: HarnessLaneEntity,
                previousResult: HarnessLaneResultEntity?, taskId: String?) {
                repository.beginOperation(previousLane.copy(currentOperationId = winner.id), winner)
                repository.acceptRunTakeover(queueItemId, entry, lane, operation, previousLane, previousResult, taskId)
            }
        }
        assertTrue(runCatching { operations(racing).acceptQueuedRun("s", "q1", input(), "q1") }.isFailure)
        assertEquals(winner.id, repository.findLane("s", "main")!!.currentOperationId)
        assertNotNull(repository.findOperation(winner.id))
        assertNotNull(repository.findOperation(old))
        assertNull(repository.findEntry("s", input().id))
        assertEquals("q1", repository.listQueue("s", "main", "next_run").single().id)
        assertQueued()
    }

    @Test fun `dangling pointer is replaced only with successful queued admission`() = runBlocking {
        val lane = repository.ensureLane("s", "main")
        database.harnessRuntimeDao().upsertLane(lane.copy(currentOperationId = "missing"))
        enqueue()
        val fresh = operations().acceptQueuedRun("s", "q1", input(), "q1")
        assertEquals(fresh, repository.findLane("s", "main")!!.currentOperationId)
        assertEquals(fresh, database.agentTaskDao().getTaskById("q1")!!.operationId)
        assertTrue(repository.listQueue("s", "main", "next_run").isEmpty())
    }
}
