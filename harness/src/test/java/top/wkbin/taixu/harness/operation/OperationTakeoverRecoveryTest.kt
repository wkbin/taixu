package top.wkbin.taixu.harness.operation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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

/** Same-process takeover must preserve pending effects without relying on startup recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OperationTakeoverRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: AppDatabase
    private lateinit var repository: HarnessRuntimeRepository
    private val call = ToolCall("pending-write", 2, HarnessTool.WRITE,
        Json.parseToJsonElement("""{"path":"a.txt","content":"written"}""") as JsonObject, rawToolName = "write")

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
    }
    @After fun tearDown() { database.close() }
    private fun operations(repo: HarnessRuntimeRepository = repository) = OperationCoordinator(repo, Json, HarnessEventBus())
    private suspend fun pending(ops: OperationCoordinator, lane: String = "main", tool: ToolCall = call,
        replay: ReplayPolicy = ReplayPolicy.NEVER): String {
        val id = ops.acceptRun("s", UserMessage("old-user", 1, "old task"), lane)
        ops.toolIntent(id, tool, tool.args.toString(), replay, 3)
        return id
    }
    private suspend fun messages() = repository.listEntries("s").map {
        Json.decodeFromString<HarnessMessage>(it.payloadJson)
    }
    private suspend fun results() = messages().filterIsInstance<ToolResult>()
    private fun newUser() = UserMessage("new-user", 4, "continue")

    @Test fun `child takeover commits unknown before new input and preserves actual file effect`() = runBlocking {
        val ops = operations()
        val old = pending(ops, "child")
        val file = File(temporary.root, "a.txt").apply { writeText("written") }
        val fresh = ops.acceptRun("s", newUser(), "child")
        assertNotEquals(old, fresh)
        assertEquals("written", file.readText())
        assertNull(repository.findOperation(old))
        assertEquals(fresh, repository.findLane("s", "child")!!.currentOperationId)
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, results().single().errorCode)
        assertEquals(call.id, results().single().toolCallId)
        assertEquals(results().single().id, repository.findEntry("s", newUser().id)!!.parentId)
        assertEquals(listOf("old-user", call.id, results().single().id, newUser().id), messages().map { it.id })
    }

    @Test fun `queued admission repair failure leaves old pointer and queued input intact for retry`() = runBlocking {
        var fail = true
        val failing = object : HarnessRuntimeRepository by repository {
            override suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?,
                operation: HarnessOperationEntity, lane: HarnessLaneEntity) {
                if (fail && operation.phase == OperationPhase.TOOL_SETTLED.id) error("repair failed")
                repository.settleEffect(entry, usage, operation, lane)
            }
        }
        val ops = operations(failing)
        val old = pending(ops)
        repository.enqueue(HarnessQueueItemEntity("queued", "s", "main", old, "next_run", 4, "{}"))
        assertTrue(runCatching { ops.acceptQueuedRun("s", "queued", newUser()) }.isFailure)
        assertEquals(old, repository.findLane("s", "main")!!.currentOperationId)
        assertEquals("queued", repository.listQueue("s", "main", "next_run").single().id)
        assertNull(repository.findEntry("s", newUser().id))
        assertTrue(results().isEmpty())
        fail = false
        val fresh = ops.acceptQueuedRun("s", "queued", newUser())
        assertNotEquals(old, fresh)
        assertTrue(repository.listQueue("s", "main", "next_run").isEmpty())
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, results().single().errorCode)
    }

    @Test fun `begin run replaces suspended read intent only after recording interruption`() = runBlocking {
        val ops = operations()
        val read = call.copy(tool = HarnessTool.READ, rawToolName = "read")
        val old = pending(ops, tool = read, replay = ReplayPolicy.SAFE)
        ops.suspendOperation(old, "cancelled")
        val fresh = ops.beginRun("s")
        assertNotEquals(old, fresh)
        assertNull(repository.findOperation(old))
        assertEquals(ToolRecoveryNotice.INTERRUPTED, results().single().errorCode)
        assertFalse(results().single().output.contains("用户停止"))
        assertFalse(results().single().output.contains("副作用"))
        assertEquals(fresh, ops.beginRun("s"))
        assertEquals(1, results().size)
    }

    @Test fun `takeover history read failure cannot hide pending effect or accept new input`() = runBlocking {
        val ops = operations()
        val old = pending(ops)
        val failing = object : HarnessRuntimeRepository by repository {
            override suspend fun branchTail(sessionId: String, leafId: String?, limit: Int): List<HarnessEntryEntity> =
                error("history unavailable")
        }
        assertTrue(runCatching { operations(failing).acceptRun("s", newUser()) }.isFailure)
        assertEquals(old, repository.findLane("s", "main")!!.currentOperationId)
        assertEquals(OperationPhase.TOOL_INTENT.id, repository.findOperation(old)!!.phase)
        assertNull(repository.findEntry("s", newUser().id))
        assertTrue(results().isEmpty())
    }

    @Test fun `takeover preserves committed success even if durable phase still indicates intent`() = runBlocking {
        val ops = operations()
        val old = pending(ops)
        val intent = repository.findOperation(old)!!
        val result = ToolResult("success", 3, call.id, true, "written")
        ops.toolSettled(old, result, 3)
        repository.saveOperation(intent)
        ops.acceptRun("s", newUser())
        assertEquals(listOf(result), results())
        assertNull(repository.findOperation(old))
    }

    @Test fun `resumed approval intent cannot be masked by its earlier waiting result`() = runBlocking {
        val ops = operations()
        val old = pending(ops)
        ops.toolSettled(old, ToolResult("approval", 3, call.id, false, "waiting", awaitingApproval = true), 3)
        ops.waitingApproval(old)
        ops.toolIntent(old, call, call.args.toString(), ReplayPolicy.NEVER, 4, persistMessage = false)
        ops.acceptRun("s", newUser())
        assertEquals(2, results().size)
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, results().last().errorCode)
        assertEquals(1, messages().filterIsInstance<ToolCall>().size)
    }

    @Test fun `corrupt pending snapshot prevents takeover instead of erasing the recovery pointer`() = runBlocking {
        val ops = operations()
        val old = pending(ops)
        repository.saveOperation(repository.findOperation(old)!!.copy(stateJson = "{"))
        assertTrue(runCatching { ops.acceptRun("s", newUser()) }.isFailure)
        assertEquals(old, repository.findLane("s", "main")!!.currentOperationId)
        assertNull(repository.findEntry("s", newUser().id))
        assertTrue(results().isEmpty())
    }

    @Test fun `failure after repair commit cannot duplicate the result on takeover retry`() = runBlocking {
        val failing = object : HarnessRuntimeRepository by repository {
            override suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?,
                operation: HarnessOperationEntity, lane: HarnessLaneEntity) {
                repository.settleEffect(entry, usage, operation, lane)
                if (operation.phase == OperationPhase.TOOL_SETTLED.id) error("publication failed")
            }
        }
        val ops = operations(failing)
        val old = pending(ops)
        assertTrue(runCatching { ops.acceptRun("s", newUser()) }.isFailure)
        assertEquals(old, repository.findLane("s", "main")!!.currentOperationId)
        assertNull(repository.findEntry("s", newUser().id))
        ops.acceptRun("s", newUser())
        assertEquals(1, results().size)
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, results().single().errorCode)
    }

    @Test fun `pending effect without a replay declaration is never treated as a safe read`() = runBlocking {
        val ops = operations()
        val old = pending(ops, tool = call.copy(tool = HarnessTool.READ, rawToolName = "read"), replay = ReplayPolicy.SAFE)
        repository.saveOperation(repository.findOperation(old)!!.copy(replayPolicy = null))
        ops.suspendOperation(old, "interrupted")
        ops.beginRun("s")
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, results().single().errorCode)
    }

    @Test fun `failed queued claim preserves repaired old operation and retry cannot duplicate unknown result`() = runBlocking {
        val ops = operations()
        val old = pending(ops)
        assertTrue(runCatching { ops.acceptQueuedRun("s", "missing", newUser()) }.isFailure)
        assertEquals(old, repository.findLane("s", "main")!!.currentOperationId)
        assertEquals(OperationPhase.TOOL_SETTLED.id, repository.findOperation(old)!!.phase)
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, results().single().errorCode)
        assertNull(repository.findEntry("s", newUser().id))
        repository.enqueue(HarnessQueueItemEntity("queued", "s", "main", old, "next_run", 4, "{}"))
        ops.acceptQueuedRun("s", "queued", newUser())
        assertNull(repository.findOperation(old))
        assertEquals(1, results().size)
        assertEquals(results().single().id, repository.findEntry("s", newUser().id)!!.parentId)
    }
}
