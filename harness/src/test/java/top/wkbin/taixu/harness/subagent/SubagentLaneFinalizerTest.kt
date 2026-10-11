package top.wkbin.taixu.harness.subagent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import top.wkbin.taixu.harness.ApiToolCallSpec
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.ToolRoundDispatcher
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.effects.ToolRecoveryNotice
import top.wkbin.taixu.harness.events.HarnessEventBus
import top.wkbin.taixu.harness.operation.OperationCoordinator
import top.wkbin.taixu.harness.operation.OperationPhase
import top.wkbin.taixu.harness.recovery.RecoveryManager

/** Real Room commits plus observable file effects exercise the lane's interruption boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubagentLaneFinalizerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: AppDatabase
    private lateinit var repository: HarnessRuntimeRepository
    private val model = ModelConfig("test", "test", "test", "https://example.invalid", null)
    private val write = ApiToolCallSpec("write", "write", """{"path":"a.txt","content":"written"}""")

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
    }
    @After fun tearDown() { database.close() }

    private fun operations(repo: HarnessRuntimeRepository = repository) = OperationCoordinator(repo, Json, HarnessEventBus())
    private fun finalizer(ops: OperationCoordinator) = SubagentLaneFinalizer(ops)
    @Test fun `lane pointer stays attached until resource release succeeds`() = runBlocking {
        val ops = operations()
        val id = begin(ops)
        var fails = true
        val finalizer = SubagentLaneFinalizer(ops) {
            assertEquals(id, repository.findLane("s", "child")!!.currentOperationId)
            if (fails) error("resource release failed")
        }
        assertTrue(runCatching { finalizer.finish("s", "completed", laneName = "child") }.isFailure)
        assertEquals(id, repository.findLane("s", "child")!!.currentOperationId)
        fails = false
        finalizer.finish("s", "completed", laneName = "child")
        assertNull(repository.findLane("s", "child")!!.currentOperationId)
    }
    private suspend fun begin(ops: OperationCoordinator) = ops.acceptRun("s", UserMessage("user", 1, "write"), "child")
    private suspend fun results() = repository.listEntries("s").mapNotNull {
        Json.decodeFromString<HarnessMessage>(it.payloadJson) as? ToolResult
    }
    private suspend fun SubagentToolRoundRunner.round(id: String, spec: ApiToolCallSpec = write) =
        execute(listOf(spec), "s", "/workspace", model, id, 0, null, null)

    private fun failResult(always: Boolean = false) = object : HarnessRuntimeRepository by repository {
        var failed = false
        override suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?,
            operation: HarnessOperationEntity, lane: HarnessLaneEntity) {
            if (operation.phase == OperationPhase.TOOL_SETTLED.id && (always || !failed)) {
                failed = true
                error("result commit failed")
            }
            repository.settleEffect(entry, usage, operation, lane)
        }
    }

    @Test fun `cancel after file write commits unknown before detaching lane and never repeats effect`() = runBlocking {
        withTimeout(10_000) {
            val ops = operations()
            val id = begin(ops)
            val repair = finalizer(ops)
            val file = File(temporary.root, "a.txt")
            val written = CompletableDeferred<Unit>()
            var executions = 0
            val runner = SubagentToolRoundRunner(ops, Json, ToolRoundDispatcher()) { _, _, _, _ ->
                executions++
                file.writeText("written")
                written.complete(Unit)
                awaitCancellation()
            }
            val child = launch {
                try { runner.round(id) } catch (cancelled: CancellationException) {
                    repair.interrupted("s", "child", id, "aborted", "已取消")
                    throw cancelled
                }
            }
            written.await()
            child.cancel()
            child.join()
            assertEquals("written", file.readText())
            assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, results().single().errorCode)
            assertNull(repository.findLane("s", "child")!!.currentOperationId)
            repair.interrupted("s", "child", id, "aborted", "已取消")
            assertEquals(1, results().size)
            assertEquals(1, executions)
        }
    }

    @Test fun `failed result commit after write is repaired before failed finish`() = runBlocking {
        val failing = failResult()
        val ops = operations(failing)
        val id = begin(ops)
        val file = File(temporary.root, "a.txt")
        var executions = 0
        val runner = SubagentToolRoundRunner(ops, Json, ToolRoundDispatcher()) { call, _, _, _ ->
            executions++
            file.writeText("written")
            ToolResult("result", 2, call.id, true, "written")
        }
        assertTrue(runCatching { runner.round(id) }.isFailure)
        val guidance = finalizer(ops).interrupted("s", "child", id, "failed", "commit failed")
        assertTrue(guidance!!.contains("禁止直接重新发起"))
        assertEquals("written", file.readText())
        assertEquals(1, executions)
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, results().single().errorCode)
        assertNull(repository.findLane("s", "child")!!.currentOperationId)
        assertTrue(repository.findLane("s", "child")!!.faulted)
    }

    @Test fun `repair commit failure keeps pending operation available to restart recovery`() = runBlocking {
        val failing = failResult(always = true)
        val ops = operations(failing)
        val id = begin(ops)
        val runner = SubagentToolRoundRunner(ops, Json, ToolRoundDispatcher()) { call, _, _, _ ->
            ToolResult("result", 2, call.id, true, "written")
        }
        assertTrue(runCatching { runner.round(id) }.isFailure)
        assertTrue(runCatching { finalizer(ops).interrupted("s", "child", id, "failed", "commit failed") }.isFailure)
        assertEquals(id, repository.findLane("s", "child")!!.currentOperationId)
        assertEquals(OperationPhase.TOOL_INTENT.id, repository.findOperation(id)!!.phase)
        assertTrue(results().isEmpty())
        val restored = operations()
        RecoveryManager(repository, restored, json = Json, eventBus = HarnessEventBus()).recoverSession("s")
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, results().single().errorCode)
    }

    @Test fun `exception after successful commit preserves committed success`() = runBlocking {
        val failing = object : HarnessRuntimeRepository by repository {
            override suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?,
                operation: HarnessOperationEntity, lane: HarnessLaneEntity) {
                repository.settleEffect(entry, usage, operation, lane)
                if (operation.phase == OperationPhase.TOOL_SETTLED.id) error("publication failed")
            }
        }
        val ops = operations(failing)
        val id = begin(ops)
        val runner = SubagentToolRoundRunner(ops, Json, ToolRoundDispatcher()) { call, _, _, _ ->
            ToolResult("result", 2, call.id, true, "written")
        }
        assertTrue(runCatching { runner.round(id) }.isFailure)
        finalizer(ops).interrupted("s", "child", id, "failed", "publication failed")
        assertTrue(results().single().success)
        assertNull(results().single().errorCode)
        assertNull(repository.findLane("s", "child")!!.currentOperationId)
    }

    @Test fun `read cancellation settles interruption without claiming side effects or user stop`() = runBlocking {
        val ops = operations()
        val id = begin(ops)
        val runner = SubagentToolRoundRunner(ops, Json, ToolRoundDispatcher()) { _, _, _, _ ->
            throw CancellationException("timeout")
        }
        assertTrue(runCatching { runner.round(id, ApiToolCallSpec("read", "read", """{"path":"a.txt"}""")) }.isFailure)
        finalizer(ops).interrupted("s", "child", id, "aborted", "timeout")
        assertEquals(ToolRecoveryNotice.INTERRUPTED, results().single().errorCode)
        assertFalse(results().single().output.contains("用户停止"))
        assertFalse(results().single().output.contains("副作用"))
    }

    @Test fun `failed intent commit cannot manufacture a pending tool result`() = runBlocking {
        val failing = object : HarnessRuntimeRepository by repository {
            override suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?,
                operation: HarnessOperationEntity, lane: HarnessLaneEntity) {
                if (operation.phase == OperationPhase.TOOL_INTENT.id) error("intent failed")
                repository.settleEffect(entry, usage, operation, lane)
            }
        }
        val ops = operations(failing)
        val id = begin(ops)
        val runner = SubagentToolRoundRunner(ops, Json, ToolRoundDispatcher()) { _, _, _, _ -> error("must not execute") }
        assertTrue(runCatching { runner.round(id) }.isFailure)
        finalizer(ops).interrupted("s", "child", id, "failed", "intent failed")
        assertTrue(results().isEmpty())
        assertNull(repository.findLane("s", "child")!!.currentOperationId)
    }

    @Test fun `write then executor exception retains uncertainty in result and parent summary`() = runBlocking {
        val ops = operations()
        val id = begin(ops)
        val file = File(temporary.root, "a.txt")
        val runner = SubagentToolRoundRunner(ops, Json, ToolRoundDispatcher()) { _, _, _, _ ->
            file.writeText("written")
            error("post write failure")
        }
        runner.round(id)
        assertEquals("written", file.readText())
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, results().single().errorCode)
        val summary = laneSummary(SubagentConclusionVerdict(false, SubagentTermination.WRITE_FAILED, "unconfirmed"),
            "", emptyList(), emptyList(), runner.failedWrites)
        assertTrue(summary.contains("可能已部分写入"))
        assertTrue(summary.contains("不代表执行失败"))
        assertFalse(summary.contains("这些文件并不存在"))
    }

    @Test fun `strict history read failure cannot detach the pending lane`() = runBlocking {
        val ops = operations()
        val id = begin(ops)
        val runner = SubagentToolRoundRunner(ops, Json, ToolRoundDispatcher()) { _, _, _, _ -> throw CancellationException() }
        assertTrue(runCatching { runner.round(id) }.isFailure)
        val failing = object : HarnessRuntimeRepository by repository {
            override suspend fun branchTail(sessionId: String, leafId: String?, limit: Int): List<HarnessEntryEntity> =
                error("history unavailable")
        }
        assertTrue(runCatching { finalizer(operations(failing)).interrupted("s", "child", id, "failed", "read failed") }.isFailure)
        assertEquals(id, repository.findLane("s", "child")!!.currentOperationId)
        assertTrue(results().isEmpty())
    }

    @Test fun `timeout summary retains earlier unknown outcome despite later read failure`() {
        val call = ToolCall("write", 1, top.wkbin.taixu.harness.HarnessTool.WRITE,
            Json.parseToJsonElement(write.argumentsJson) as kotlinx.serialization.json.JsonObject, rawToolName = "write")
        val transcript = listOf(call, ToolRecoveryNotice.unknown().result("unknown", 2, call.id),
            call.copy(id = "read", tool = top.wkbin.taixu.harness.HarnessTool.READ, rawToolName = "read"),
            ToolResult("read-failure", 3, "read", false, "file unavailable"))
        val summary = buildSubagentTimeoutSummary(1_000, 2, transcript, "child")
        assertTrue(summary.contains("禁止直接重新发起"))
        assertTrue(summary.contains("先通过只读查询核验"))
        assertTrue(summary.contains("file unavailable"))
        assertTrue(buildSubagentTimeoutSummary(1_000, 1, listOf(call), "child").contains("TOOL_OUTCOME_UNKNOWN"))
        assertEquals("", subagentOutcomeUncertainty(listOf(call, ToolResult("success", 2, call.id, true, "written"))))
    }
}
