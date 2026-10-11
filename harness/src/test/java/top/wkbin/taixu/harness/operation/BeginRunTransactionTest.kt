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
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.effects.ToolRecoveryNotice
import top.wkbin.taixu.harness.events.HarnessEventBus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BeginRunTransactionTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: HarnessRuntimeRepository
    private fun operations(repo: HarnessRuntimeRepository = repository) = OperationCoordinator(repo, Json, HarnessEventBus())
    private val call = ToolCall("write", 2, HarnessTool.WRITE,
        Json.parseToJsonElement("""{"path":"a","content":"written"}""") as JsonObject, rawToolName = "write")

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
    }
    @After fun tearDown() { database.close() }
    private suspend fun suspended(lane: String = "main", intent: Boolean = false): String {
        val ops = operations()
        val old = ops.acceptRun("s", UserMessage("old-$lane", 1, "first"), lane)
        if (intent) ops.toolIntent(old, call, call.args.toString(), ReplayPolicy.NEVER, 1)
        ops.suspendOperation(old, "process stopped")
        return old
    }
    private suspend fun enqueue(old: String) {
        repository.enqueue(HarnessQueueItemEntity("steer", "s", "main", old, "steer", 3, "{}"))
        repository.enqueue(HarnessQueueItemEntity("later", "s", "main", old, "next_run", 4, "{}"))
    }
    private suspend fun assertOldPreserved(old: String) {
        assertEquals(old, repository.findLane("s", "main")!!.currentOperationId)
        assertEquals(OperationStatus.SUSPENDED.id, repository.findOperation(old)!!.status)
        assertEquals(old, repository.listQueue("s", "main", "next_run").single().operationId)
        assertEquals("steer", repository.listQueue("s", "main", "steer").single().id)
    }

    @Test fun `new run insert failure preserves suspended repaired run and retry creates one successor`() = runBlocking {
        val old = suspended(intent = true)
        enqueue(old)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_new_run BEFORE INSERT ON harness_operations
            WHEN NEW.id != '$old' BEGIN SELECT RAISE(ABORT, 'new run unavailable'); END
        """)
        repeat(2) {
            assertTrue(runCatching { operations().beginRun("s") }.isFailure)
            assertOldPreserved(old)
        }
        val before = repository.listEntries("s")
        val result = before.map { Json.decodeFromString<HarnessMessage>(it.payloadJson) }.filterIsInstance<ToolResult>().single()
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, result.errorCode)
        assertEquals(OperationPhase.TOOL_SETTLED.id, repository.findOperation(old)!!.phase)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_new_run")
        val fresh = operations().beginRun("s")
        assertNotEquals(old, fresh)
        assertNull(repository.findOperation(old))
        assertEquals(fresh, operations().beginRun("s"))
        assertEquals(before, repository.listEntries("s"))
        assertEquals(result.id, repository.findOperation(fresh)!!.startLeafId)
        assertEquals(result.id, repository.findLane("s", "main")!!.leafId)
        assertTrue(repository.listQueue("s", "main", "steer").isEmpty())
        assertNull(repository.listQueue("s", "main", "next_run").single().operationId)
    }

    @Test fun `failed old finish record rolls back suspended replacement`() = runBlocking {
        val old = suspended()
        enqueue(old)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_finish BEFORE INSERT ON harness_lane_results
            BEGIN SELECT RAISE(ABORT, 'finish unavailable'); END
        """)
        assertTrue(runCatching { operations().beginRun("s") }.isFailure)
        assertOldPreserved(old)
        assertEquals(listOf(old), repository.listActiveOperations("s").map { it.id })
    }

    private fun racing(before: suspend (HarnessLaneEntity) -> Unit) = object : HarnessRuntimeRepository by repository {
        override suspend fun acceptRunTakeover(queueItemId: String?, entry: HarnessEntryEntity?, lane: HarnessLaneEntity,
            operation: HarnessOperationEntity, previousLane: HarnessLaneEntity,
            previousResult: HarnessLaneResultEntity?, taskId: String?) {
            assertNull(entry)
            before(previousLane)
            repository.acceptRunTakeover(queueItemId, entry, lane, operation, previousLane, previousResult, taskId)
        }
    }

    @Test fun `begin cannot overwrite concurrently appended history`() = runBlocking {
        val old = suspended()
        enqueue(old)
        val message = UserMessage("newer", 3, "newer input")
        val racing = racing { lane ->
            repository.appendToLane("s", "main", HarnessEntryEntity(id = message.id, sessionId = "s", parentId = lane.leafId,
                createdAt = message.createdAt, entryType = "message", customType = "user",
                payloadJson = Json.encodeToString(HarnessMessage.serializer(), message)))
        }
        assertTrue(runCatching { operations(racing).beginRun("s") }.isFailure)
        assertOldPreserved(old)
        assertEquals(message.id, repository.findLane("s", "main")!!.leafId)
    }

    @Test fun `begin cannot overwrite concurrently claimed operation`() = runBlocking {
        val old = suspended()
        enqueue(old)
        val winner = repository.findOperation(old)!!.copy(id = "winner", status = OperationStatus.RUNNING.id)
        val racing = racing { lane -> repository.beginOperation(lane.copy(currentOperationId = winner.id), winner) }
        assertTrue(runCatching { operations(racing).beginRun("s") }.isFailure)
        assertEquals(winner.id, repository.findLane("s", "main")!!.currentOperationId)
        assertNotNull(repository.findOperation(winner.id))
        assertNotNull(repository.findOperation(old))
        assertEquals(old, repository.listQueue("s", "main", "next_run").single().operationId)
    }

    @Test fun `begin reuses running and waiting approval operations without settling their live tools`() = runBlocking {
        val ops = operations()
        val old = ops.acceptRun("s", UserMessage("old", 1, "first"))
        ops.toolIntent(old, call, call.args.toString(), ReplayPolicy.NEVER, 1)
        enqueue(old)
        assertEquals(old, ops.beginRun("s"))
        assertEquals(OperationPhase.TOOL_INTENT.id, repository.findOperation(old)!!.phase)
        ops.waitingApproval(old)
        assertEquals(old, ops.beginRun("s"))
        assertEquals(OperationStatus.WAITING_APPROVAL.id, repository.findOperation(old)!!.status)
        assertEquals(2, repository.listEntries("s").size)
        assertEquals(old, repository.listQueue("s", "main", "next_run").single().operationId)
    }

    @Test fun `dangling pointer remains after failed begin and is replaced only on successful retry`() = runBlocking {
        val lane = repository.ensureLane("s", "main")
        database.harnessRuntimeDao().upsertLane(lane.copy(currentOperationId = "missing"))
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_new_run BEFORE INSERT ON harness_operations
            BEGIN SELECT RAISE(ABORT, 'new run unavailable'); END
        """)
        assertTrue(runCatching { operations().beginRun("s") }.isFailure)
        assertEquals("missing", repository.findLane("s", "main")!!.currentOperationId)
        assertTrue(repository.listEntries("s").isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_new_run")
        val fresh = operations().beginRun("s")
        assertEquals(fresh, repository.findLane("s", "main")!!.currentOperationId)
        assertNull(repository.findOperation(fresh)!!.startLeafId)
    }

    @Test fun `child suspended replacement does not change active main run or append input`() = runBlocking {
        val ops = operations()
        val main = ops.acceptRun("s", UserMessage("old-main", 1, "main"))
        val child = suspended("child")
        val entries = repository.listEntries("s")
        val fresh = ops.beginRun("s", "child")
        assertNotEquals(child, fresh)
        assertNull(repository.findOperation(child))
        assertEquals(main, repository.findLane("s", "main")!!.currentOperationId)
        assertEquals(entries, repository.listEntries("s"))
        assertEquals("old-child", repository.findOperation(fresh)!!.startLeafId)
    }

    @Test fun `strict history failure preserves suspended pending intent for later recovery`() = runBlocking {
        val old = suspended(intent = true)
        enqueue(old)
        val failing = object : HarnessRuntimeRepository by repository {
            override suspend fun branchTail(sessionId: String, leafId: String?, limit: Int): List<HarnessEntryEntity> =
                error("history unavailable")
        }
        assertTrue(runCatching { operations(failing).beginRun("s") }.isFailure)
        assertOldPreserved(old)
        assertEquals(OperationPhase.TOOL_INTENT.id, repository.findOperation(old)!!.phase)
        assertEquals(2, repository.listEntries("s").size)
    }
}
