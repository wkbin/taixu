package top.wkbin.taixu.harness.subagent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.HarnessEntryEntity
import top.wkbin.taixu.core.database.HarnessLaneEntity
import top.wkbin.taixu.core.database.HarnessOperationEntity
import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.core.database.HarnessUsageEntity
import top.wkbin.taixu.core.database.RoomHarnessRuntimeRepository
import top.wkbin.taixu.harness.ApiToolCallSpec
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ModelConfig
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.ToolRoundDispatcher
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.validation.ToolSchemaValidator
import top.wkbin.taixu.harness.events.HarnessEventBus
import top.wkbin.taixu.harness.effects.ToolRecoveryNotice
import top.wkbin.taixu.harness.operation.OperationCoordinator
import top.wkbin.taixu.harness.operation.OperationPhase

/** Real Room transactions verify lane evidence and commit failures at the production adapter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubagentToolRoundRunnerTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: HarnessRuntimeRepository
    private val model = ModelConfig("test", "test", "test", "https://example.test", null)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
    }

    @After
    fun tearDown() { database.close() }

    private fun runner(
        operations: OperationCoordinator,
        dispatcher: ToolRoundDispatcher = ToolRoundDispatcher(),
        execute: suspend (ToolCall) -> ToolResult,
    ) = SubagentToolRoundRunner(operations, Json, dispatcher) { call, _, _, _ -> execute(call) }

    private suspend fun SubagentToolRoundRunner.round(operationId: String, writePaths: List<String>? = null) {
        execute(listOf(ApiToolCallSpec("call", "write", """{"path":"a.txt","content":"hello"}""")),
            "s", "/workspace", model, operationId, 0, null, writePaths)
    }

    private suspend fun operations(repo: HarnessRuntimeRepository = repository, laneName: String = "child"): Pair<OperationCoordinator, String> {
        val operations = OperationCoordinator(repo, Json, HarnessEventBus())
        return operations to operations.acceptRun("s", UserMessage("user-$laneName", 1, "write a file"), laneName)
    }

    private suspend fun results() = repository.listEntries("s").mapNotNull {
        Json.decodeFromString<HarnessMessage>(it.payloadJson) as? ToolResult
    }

    private fun failCommit(phase: OperationPhase, failure: Throwable) = object : HarnessRuntimeRepository by repository {
        override suspend fun settleEffect(
            entry: HarnessEntryEntity?, usage: HarnessUsageEntity?,
            operation: HarnessOperationEntity, lane: HarnessLaneEntity,
        ) {
            if (operation.phase == phase.id) throw failure
            repository.settleEffect(entry, usage, operation, lane)
        }
    }

    @Test fun `unknown names never fall back to command execution even with malformed arguments`() = runBlocking {
        val (operations, id) = operations()
        val runner = runner(operations) { error("unknown tool reached backend") }
        runner.execute(listOf(
            ApiToolCallSpec("unknown-command", "execute", """{"command":"touch outside.txt"}"""),
            ApiToolCallSpec("unknown-json", "execute", "{"),
            ApiToolCallSpec("bare-mcp", "mcp", "{}"),
        ), "s", "/workspace", model, id, 0, null, null)
        assertEquals(3, results().size)
        assertTrue(results().all { !it.success && it.output.contains("未知工具") })
        assertTrue(runner.blockedWrites.isEmpty())
        assertTrue(runner.failedWrites.isEmpty())
    }

    @Test fun `wrapped and aliased writes cannot bypass the declared lease`() = runBlocking {
        val (operations, id) = operations()
        val runner = runner(operations) { error("out of lease write reached backend") }
        runner.execute(listOf(
            ApiToolCallSpec("alias", "write", """{"file_path":"outside/a.txt","content":"hello"}"""),
            ApiToolCallSpec("wrapper", "write", """{"arguments":{"path":"outside/b.txt","content":"hello"}}"""),
            ApiToolCallSpec("edit", "edit", """{"input":{"path":"outside/c.txt","oldText":"a","newText":"b"}}"""),
            ApiToolCallSpec("download", "download", """{"arguments":{"url":"https://example.test/file","destination":"outside/d.txt"}}"""),
        ), "s", "/workspace", model, id, 0, null, listOf("allowed"))
        assertEquals(4, results().size)
        assertTrue(results().all { !it.success })
        assertTrue(results().single { it.output.contains("不接受参数 file_path") }.output.contains("参数校验未通过"))
        assertEquals(3, results().count { it.output.contains("写租约范围") })
        assertEquals(listOf("write outside/b.txt", "edit outside/c.txt", "download outside/d.txt"),
            runner.blockedWrites)
        assertTrue(runner.failedWrites.isEmpty())
    }

    @Test fun `allowed wrapped write preserves raw payload and tracks actual failure target`() = runBlocking {
        val (operations, id) = operations()
        val raw = """{"arguments":{"path":"allowed/a.txt","content":"hello"}}"""
        var executions = 0
        val runner = runner(operations) { call ->
            executions++
            assertEquals(Json.parseToJsonElement(raw), call.args)
            val actual = ToolSchemaValidator.normalizeArgs(call.args)
            assertEquals(Json.parseToJsonElement("""{"path":"allowed/a.txt","content":"hello"}"""), actual)
            ToolResult("result-$executions", 2, call.id, executions > 1, "write attempt")
        }
        runner.execute(listOf(ApiToolCallSpec("first", "write", raw)),
            "s", "/workspace", model, id, 0, null, listOf("allowed"))
        assertEquals(setOf("write allowed/a.txt"), runner.failedWrites.keys)
        runner.execute(listOf(ApiToolCallSpec("second", "write", raw)),
            "s", "/workspace", model, id, 1, null, listOf("allowed"))
        assertEquals(2, executions)
        assertTrue(runner.failedWrites.isEmpty())
    }

    @Test fun `case insensitive builtin names still require their schema`() = runBlocking {
        val (operations, id) = operations()
        runner(operations) { error("invalid builtin reached backend") }.execute(
            listOf(ApiToolCallSpec("invalid", "WRITE", "{}")),
            "s", "/workspace", model, id, 0, null, null)
        assertTrue(results().single().output.contains("缺少必填参数 path"))
    }

    @Test fun `history aliases and direct MCP compatibility preserve accepted arguments`() = runBlocking {
        val (operations, id) = operations()
        val specs = listOf(
            ApiToolCallSpec("history", "history.search", """{"query":"earlier"}"""),
            ApiToolCallSpec("mcp", "mcp__legacy__lookup", """{"params":{"file_path":"x","a.b":"y"}}"""),
        )
        val received = mutableListOf<JsonObject>()
        runner(operations) { call ->
            received += call.args
            ToolResult("result-${call.id}", 2, call.id, true, "ok")
        }.execute(specs, "s", "/workspace", model, id, 0, null, null)
        assertEquals(specs.map { Json.parseToJsonElement(it.argumentsJson) }, received)
        assertTrue(results().all { it.success })
    }

    @Test fun `lane mutation waits for main workspace lock but another workspace proceeds`() = runBlocking {
        withTimeout(10_000) {
            val dispatcher = ToolRoundDispatcher()
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch { dispatcher.withMutationLock("/workspace") { held.complete(Unit); release.await() } }
            held.await()
            val (operations, id) = operations()
            var executions = 0
            val childRunner = runner(operations, dispatcher) { call ->
                executions++
                ToolResult("result-$executions", 2, call.id, true, "written")
            }
            val child = async { childRunner.round(id) }
            try {
                repeat(5) { yield() }
                assertEquals(0, executions)
                assertTrue(results().isEmpty())
                val (otherOps, otherId) = operations(laneName = "other")
                runner(otherOps, dispatcher) { call ->
                    ToolResult("other-result", 2, call.id, true, "other workspace")
                }.execute(listOf(ApiToolCallSpec("other", "write", """{"path":"a.txt","content":"hello"}""")),
                    "s", "/other-workspace", model, otherId, 0, null, null)
                assertEquals(0, executions)
                release.complete(Unit)
                child.await()
                holder.join()
                assertEquals(1, executions)
                assertEquals(0, dispatcher.retainedMutationScopeCount)
            } finally { release.complete(Unit); child.cancel(); holder.cancel() }
        }
    }

    @Test fun `cancelled lane waiting for mutation lock never records intent or runs backend`() = runBlocking {
        withTimeout(10_000) {
            val dispatcher = ToolRoundDispatcher()
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch { dispatcher.withMutationLock("/workspace") { held.complete(Unit); release.await() } }
            held.await()
            val (operations, id) = operations()
            val child = async { runner(operations, dispatcher) { error("cancelled backend ran") }.round(id) }
            try {
                repeat(5) { yield() }
                child.cancel()
                child.join()
                assertTrue(repository.listEntries("s").none { Json.decodeFromString<HarnessMessage>(it.payloadJson) is ToolCall })
            } finally { release.complete(Unit); holder.join() }
            assertEquals(0, dispatcher.retainedMutationScopeCount)
        }
    }

    @Test
    fun `write lease rejection commits failure and never invokes execution`() = runBlocking {
        val (operations, id) = operations()
        val runner = runner(operations) { error("must not write without lease") }
        runner.round(id, writePaths = emptyList())
        assertEquals(1, runner.toolCallCount)
        assertEquals(1, runner.blockedWrites.size)
        assertTrue(runner.failedWrites.isEmpty())
        assertFalse(results().single().success)
    }

    @Test
    fun `approval deferral survives result commit as parent handoff`() = runBlocking {
        val (operations, id) = operations()
        val runner = runner(operations) { call ->
            ToolResult("result", 2, call.id, false, "主会话需要审批", approvalDeferred = true)
        }
        runner.round(id)
        assertEquals("write", runner.pendingApprovals.single().toolName)
        assertTrue(runner.failedWrites.isEmpty())
        assertTrue(results().single().approvalDeferred)
    }

    @Test
    fun `execution exception is recorded as an unknown write outcome`() = runBlocking {
        val (operations, id) = operations()
        val runner = runner(operations) { throw IllegalStateException("disk unavailable") }
        runner.round(id)
        assertEquals(1, runner.failedWrites.size)
        assertFalse(results().single().success)
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, results().single().errorCode)
        assertTrue(results().single().output.contains("不代表执行失败"))
        assertFalse(results().single().output.contains("disk unavailable"))
    }

    @Test fun `read execution exception retains an ordinary failure without unknown side effects`() = runBlocking {
        val (operations, id) = operations()
        runner(operations) { throw IllegalStateException("read unavailable") }.execute(
            listOf(ApiToolCallSpec("read", "read", """{"path":"a.txt"}""")),
            "s", "/workspace", model, id, 0, null, null)
        assertEquals(null, results().single().errorCode)
        assertTrue(results().single().output.contains("read unavailable"))
    }

    @Test
    fun `failed intent commit cannot execute or manufacture a tool result`() = runBlocking {
        val failure = IllegalStateException("intent commit failed")
        val (operations, id) = operations(failCommit(OperationPhase.TOOL_INTENT, failure))
        val runner = runner(operations) { error("must not execute") }
        assertSame(failure, runCatching { runner.round(id) }.exceptionOrNull())
        assertTrue(results().isEmpty())
        assertTrue(runner.failedWrites.isEmpty())
    }

    @Test
    fun `failed result commit cannot report completion evidence`() = runBlocking {
        val failure = IllegalStateException("result commit failed")
        val (operations, id) = operations(failCommit(OperationPhase.TOOL_SETTLED, failure))
        var executions = 0
        val runner = runner(operations) { call ->
            executions++
            ToolResult("result", 2, call.id, false, "needs approval", approvalDeferred = true)
        }
        assertSame(failure, runCatching { runner.round(id) }.exceptionOrNull())
        assertEquals(1, executions)
        assertTrue(results().isEmpty())
        assertTrue(runner.pendingApprovals.isEmpty())
        assertEquals(OperationPhase.TOOL_INTENT.id, repository.findOperation(id)!!.phase)
    }

    @Test
    fun `subsequent successful write clears only its own failure`() = runBlocking {
        val (operations, id) = operations()
        var attempts = 0
        val runner = runner(operations) { call ->
            attempts++
            ToolResult("result-$attempts", attempts.toLong(), call.id, attempts > 1, "attempt $attempts")
        }
        runner.round(id)
        assertEquals(1, runner.failedWrites.size)
        runner.round(id)
        assertTrue(runner.failedWrites.isEmpty())
        assertEquals(2, results().size)
    }
}
