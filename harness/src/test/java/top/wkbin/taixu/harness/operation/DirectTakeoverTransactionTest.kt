package top.wkbin.taixu.harness.operation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.effects.ToolRecoveryNotice
import top.wkbin.taixu.harness.events.HarnessEventBus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectTakeoverTransactionTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: HarnessRuntimeRepository
    private fun operations(repo: HarnessRuntimeRepository = repository) = OperationCoordinator(repo, Json, HarnessEventBus())
    private val input = UserMessage("accepted", 5, "later", listOf("original-image"))

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
    }
    @After fun tearDown() { database.close() }
    private suspend fun oldRun(lane: String = "main") = operations().acceptRun("s", UserMessage("old-$lane", 1, "first"), lane)
    private suspend fun task(status: String = "QUEUED", owner: String = "s") {
        database.agentTaskDao().upsertTask(AgentTaskEntity(id = "task", sessionId = owner, title = "later",
            description = "later", status = status, createdAt = 2, updatedAt = 2))
    }
    private suspend fun assertNotAdmitted(old: String) {
        assertEquals(old, repository.findLane("s", "main")!!.currentOperationId)
        assertNotNull(repository.findOperation(old))
        assertNull(repository.findEntry("s", input.id))
        val task = database.agentTaskDao().getTaskById("task")
        assertNull(task?.operationId)
        assertEquals(0, task?.attemptCount ?: 0)
    }

    @Test fun `failed input write preserves repaired old run instructions and task for idempotent retry`() = runBlocking {
        val ops = operations()
        val old = oldRun()
        task()
        val call = ToolCall("write", 2, HarnessTool.WRITE,
            Json.parseToJsonElement("""{"path":"a","content":"written"}""") as JsonObject, rawToolName = "write")
        ops.toolIntent(old, call, call.args.toString(), ReplayPolicy.NEVER, 1)
        repository.enqueue(HarnessQueueItemEntity("steer", "s", "main", old, "steer", 3, "{}"))
        repository.enqueue(HarnessQueueItemEntity("later", "s", "main", old, "next_run", 4, "{}"))
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_input BEFORE INSERT ON harness_entries
            WHEN NEW.id = 'accepted' BEGIN SELECT RAISE(ABORT, 'input unavailable'); END
        """)
        repeat(2) {
            assertTrue(runCatching { ops.acceptRun("s", input, taskId = "task") }.isFailure)
            assertNotAdmitted(old)
            assertEquals("QUEUED", database.agentTaskDao().getTaskById("task")!!.status)
            assertEquals("steer", repository.listQueue("s", "main", "steer").single().id)
            assertEquals(old, repository.listQueue("s", "main", "next_run").single().operationId)
        }
        val messages = repository.listEntries("s").map { Json.decodeFromString<HarnessMessage>(it.payloadJson) }
        val unknown = messages.filterIsInstance<ToolResult>().single()
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, unknown.errorCode)
        assertEquals(OperationPhase.TOOL_SETTLED.id, repository.findOperation(old)!!.phase)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_input")
        val fresh = operations().acceptRun("s", input, taskId = "task")
        assertNull(repository.findOperation(old))
        assertEquals(fresh, database.agentTaskDao().getTaskById("task")!!.operationId)
        assertEquals(1, database.agentTaskDao().getTaskById("task")!!.attemptCount)
        assertEquals(unknown.id, repository.findEntry("s", input.id)!!.parentId)
        assertEquals(input, Json.decodeFromString<HarnessMessage>(repository.findEntry("s", input.id)!!.payloadJson))
        assertEquals(1, repository.listEntries("s").count { it.customType == "tool_result" })
        assertTrue(repository.listQueue("s", "main", "steer").isEmpty())
        assertNull(repository.listQueue("s", "main", "next_run").single().operationId)
    }

    @Test fun `missing cancelled and foreign tasks cannot end an existing run`() = runBlocking {
        val old = oldRun()
        assertTrue(runCatching { operations().acceptRun("s", input, taskId = "task") }.isFailure)
        assertNotAdmitted(old)
        for ((status, owner) in listOf("CANCELLED" to "s", "QUEUED" to "other")) {
            task(status, owner)
            assertTrue(runCatching { operations().acceptRun("s", input, taskId = "task") }.isFailure)
            assertNotAdmitted(old)
            assertEquals(status, database.agentTaskDao().getTaskById("task")!!.status)
        }
    }

    private fun racing(before: suspend (HarnessLaneEntity) -> Unit) = object : HarnessRuntimeRepository by repository {
        override suspend fun acceptRunTakeover(queueItemId: String?, entry: HarnessEntryEntity?, lane: HarnessLaneEntity,
            operation: HarnessOperationEntity, previousLane: HarnessLaneEntity,
            previousResult: HarnessLaneResultEntity?, taskId: String?) {
            before(previousLane)
            repository.acceptRunTakeover(queueItemId, entry, lane, operation, previousLane, previousResult, taskId)
        }
    }

    @Test fun `task cancelled immediately before admission rolls back old completion`() = runBlocking {
        val old = oldRun()
        task()
        val racing = racing {
            val task = database.agentTaskDao().getTaskById("task")!!
            database.agentTaskDao().upsertTask(task.copy(status = "CANCELLED"))
        }
        assertTrue(runCatching { operations(racing).acceptRun("s", input, taskId = "task") }.isFailure)
        assertNotAdmitted(old)
        assertEquals("CANCELLED", database.agentTaskDao().getTaskById("task")!!.status)
    }

    @Test fun `direct input cannot overwrite concurrently appended history`() = runBlocking {
        val old = oldRun()
        task()
        val concurrent = UserMessage("concurrent", 4, "newer input")
        val racing = racing { lane ->
            repository.appendToLane("s", "main", HarnessEntryEntity(id = concurrent.id, sessionId = "s", parentId = lane.leafId,
                createdAt = concurrent.createdAt, entryType = "message", customType = "user",
                payloadJson = Json.encodeToString(HarnessMessage.serializer(), concurrent)))
        }
        assertTrue(runCatching { operations(racing).acceptRun("s", input, taskId = "task") }.isFailure)
        assertNotAdmitted(old)
        assertEquals(concurrent.id, repository.findLane("s", "main")!!.leafId)
    }

    @Test fun `direct input cannot replace a concurrently claimed run`() = runBlocking {
        val old = oldRun()
        task()
        val winner = repository.findOperation(old)!!.copy(id = "winner")
        val racing = racing { lane -> repository.beginOperation(lane.copy(currentOperationId = winner.id), winner) }
        assertTrue(runCatching { operations(racing).acceptRun("s", input, taskId = "task") }.isFailure)
        assertNotAdmitted(winner.id)
        assertNotNull(repository.findOperation(old))
    }

    @Test fun `failed direct admission leaves dangling pointer intact for recovery`() = runBlocking {
        val lane = repository.ensureLane("s", "main")
        database.harnessRuntimeDao().upsertLane(lane.copy(currentOperationId = "missing"))
        assertTrue(runCatching { operations().acceptRun("s", input, taskId = "task") }.isFailure)
        assertEquals("missing", repository.findLane("s", "main")!!.currentOperationId)
        assertNull(repository.findEntry("s", input.id))
        task()
        val fresh = operations().acceptRun("s", input, taskId = "task")
        assertEquals(fresh, repository.findLane("s", "main")!!.currentOperationId)
    }

    @Test fun `child direct takeover preserves main run and future child input`() = runBlocking {
        val main = oldRun()
        val old = oldRun("child")
        repository.enqueue(HarnessQueueItemEntity("later", "s", "child", old, "next_run", 3, "{}"))
        val fresh = operations().acceptRun("s", input, "child")
        assertNull(repository.findOperation(old))
        assertEquals(fresh, repository.findLane("s", "child")!!.currentOperationId)
        assertEquals(main, repository.findLane("s", "main")!!.currentOperationId)
        assertNotNull(repository.findOperation(main))
        assertEquals("old-child", repository.findEntry("s", input.id)!!.parentId)
        assertNull(repository.listQueue("s", "child", "next_run").single().operationId)
    }
}
