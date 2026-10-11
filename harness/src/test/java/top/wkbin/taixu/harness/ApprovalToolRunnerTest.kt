package top.wkbin.taixu.harness

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.logging.SensitiveDataRedactor
import top.wkbin.taixu.core.database.*
import top.wkbin.taixu.harness.effects.ToolRecoveryNotice
import top.wkbin.taixu.harness.operation.*
import top.wkbin.taixu.harness.events.HarnessEventBus
import top.wkbin.taixu.harness.projection.LiveMessagePort
import top.wkbin.taixu.harness.recovery.RecoveryManager
import top.wkbin.taixu.harness.recovery.RecoveryOutcome
import top.wkbin.taixu.harness.session.SessionTreeStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApprovalToolRunnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: AppDatabase
    private lateinit var repository: HarnessRuntimeRepository
    private lateinit var logger: AppLogger
    private var commitHook: suspend (HarnessOperationEntity) -> Unit = {}

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        logger = AppLogger(context, SensitiveDataRedactor { it })
        val durable = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        repository = object : HarnessRuntimeRepository by durable {
            override suspend fun settleEffect(entry: HarnessEntryEntity?, usage: HarnessUsageEntity?,
                operation: HarnessOperationEntity, lane: HarnessLaneEntity) {
                commitHook(operation)
                durable.settleEffect(entry, usage, operation, lane)
            }
        }
    }
    @After fun tearDown() { database.close() }

    private data class Fixture(val runner: ApprovalToolRunner, val request: AgentApprovalRequestEntity,
        val store: SessionTreeStore, val operations: OperationCoordinator)

    private suspend fun fixture(
        toolName: String = "write", dispatcher: ToolRoundDispatcher = ToolRoundDispatcher(),
        publish: suspend (ToolResult) -> Unit = {},
        execute: suspend (ToolCall) -> ToolResult,
    ): Fixture {
        val operations = OperationCoordinator(repository, Json, HarnessEventBus())
        val id = operations.acceptRun("s", UserMessage("user", 1, "write"))
        val args = """{"path":"a.txt","content":"written"}"""
        val call = ToolCall("frozen-call", 2, HarnessApiMapper.toolByName(toolName),
            Json.parseToJsonElement(args) as JsonObject, rawToolName = toolName)
        operations.toolIntent(id, call, args, ReplayPolicy.NEVER, 0)
        operations.toolSettled(id, ToolResult("waiting", 3, call.id, false, "approval needed", awaitingApproval = true), 0)
        operations.waitingApproval(id)
        val store = SessionTreeStore(repository, Json, logger)
        val messages = object : LiveMessagePort {
            override suspend fun append(sessionId: String, message: HarnessMessage) { store.append(sessionId, message) }
            override suspend fun publishPersisted(sessionId: String, message: HarnessMessage) { publish(message as ToolResult) }
            override fun snapshot(sessionId: String) = emptyList<HarnessMessage>()
        }
        val request = AgentApprovalRequestEntity("approval", "s", call.id, toolName, args,
            "/workspace", "medium", "write", "write", createdAt = 3, operationId = id)
        return Fixture(ApprovalToolRunner(dispatcher, operations, Json, store, messages) { tool, _, _, _ -> execute(tool) },
            request, store, operations)
    }

    @Test fun `failed resumed intent cannot execute or duplicate the frozen tool call`() = runBlocking {
        val fixture = fixture { error("backend must not start without resumed intent") }
        val failure = IllegalStateException("intent commit failed")
        commitHook = { if (it.phase == OperationPhase.TOOL_INTENT.id) throw failure }
        assertSame(failure, runCatching { fixture.runner.run(fixture.request) }.exceptionOrNull())
        assertEquals(OperationPhase.WAITING_APPROVAL.id, repository.findOperation(fixture.request.operationId!!)!!.phase)
        val messages = fixture.store.loadStrict("s")
        assertEquals(1, messages.filterIsInstance<ToolCall>().size)
        assertTrue(messages.filterIsInstance<ToolResult>().single().awaitingApproval)
    }

    @Test fun `result commit failure after file write repairs unknown and never reexecutes`() = runBlocking {
        val file = File(temporary.root, "a.txt")
        var executions = 0
        val fixture = fixture { call ->
            executions++
            file.writeText("written")
            ToolResult("success", 4, call.id, true, "written")
        }
        val failure = IllegalStateException("commit failed")
        commitHook = { operation ->
            if (operation.phase == OperationPhase.TOOL_SETTLED.id) { commitHook = {}; throw failure }
        }
        assertSame(failure, runCatching { fixture.runner.run(fixture.request) }.exceptionOrNull())
        assertEquals("written", file.readText())
        assertEquals(OperationPhase.TOOL_INTENT.id, repository.findOperation(fixture.request.operationId!!)!!.phase)
        fixture.runner.repairInterrupted(fixture.request, userStopped = false)
        fixture.runner.repairInterrupted(fixture.request, userStopped = false)
        val results = fixture.store.loadStrict("s").filterIsInstance<ToolResult>()
        assertEquals(2, results.size)
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, results.last().errorCode)
        assertEquals(1, executions)
        assertEquals(1, fixture.store.loadStrict("s").filterIsInstance<ToolCall>().size)
    }

    @Test fun `process restart sees resumed intent even though approval waiting result already exists`() = runBlocking {
        val fixture = fixture { call -> ToolResult("success", 4, call.id, true, "written") }
        commitHook = { if (it.phase == OperationPhase.TOOL_SETTLED.id) error("process lost result") }
        assertTrue(runCatching { fixture.runner.run(fixture.request) }.isFailure)
        commitHook = {}
        val events = HarnessEventBus()
        val recovery = RecoveryManager(repository, OperationCoordinator(repository, Json, events), null, Json, events)
        assertTrue(recovery.recoverSession("s") is RecoveryOutcome.ToolInterrupted)
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, fixture.store.loadStrict("s").filterIsInstance<ToolResult>().last().errorCode)
    }

    @Test fun `publication failure after durable success cannot overwrite result with unknown`() = runBlocking {
        val failure = IllegalStateException("UI publication failed")
        val fixture = fixture(publish = { throw failure }) { call -> ToolResult("success", 4, call.id, true, "written") }
        assertSame(failure, runCatching { fixture.runner.run(fixture.request) }.exceptionOrNull())
        fixture.runner.repairInterrupted(fixture.request, userStopped = false)
        val results = fixture.store.loadStrict("s").filterIsInstance<ToolResult>()
        assertEquals(2, results.size)
        assertTrue(results.last().success)
        assertNull(results.last().errorCode)
    }

    @Test fun `mutation lock covers pending result commit and another workspace remains available`() = runBlocking {
        withTimeout(10_000) {
            val dispatcher = ToolRoundDispatcher()
            val commitEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = fixture(dispatcher = dispatcher) { call -> ToolResult("success", 4, call.id, true, "written") }
            commitHook = { if (it.phase == OperationPhase.TOOL_SETTLED.id) { commitEntered.complete(Unit); release.await() } }
            val job = async { fixture.runner.run(fixture.request) }
            commitEntered.await()
            var competingMutation = false
            val competitor = launch { dispatcher.withMutationLock("/workspace") { competingMutation = true } }
            try {
                repeat(3) { yield() }
                assertFalse(competingMutation)
                dispatcher.withMutationLock("/other") { assertFalse(competingMutation) }
                release.complete(Unit)
                job.await()
                competitor.join()
                assertTrue(competingMutation)
                assertEquals(0, dispatcher.retainedMutationScopeCount)
            } finally { release.complete(Unit); job.cancel(); competitor.cancel() }
        }
    }

    @Test fun `approved subagent can acquire child mutation lock without parent deadlock`() = runBlocking {
        withTimeout(10_000) {
            val dispatcher = ToolRoundDispatcher()
            val fixture = fixture("invoke_subagent", dispatcher) { call ->
                dispatcher.withMutationLock("/workspace") { ToolResult("success", 4, call.id, true, "child written") }
            }
            assertTrue(fixture.runner.run(fixture.request).success)
        }
    }

    @Test fun `cancellation after side effect repairs unknown without replaying`() = runBlocking {
        val file = File(temporary.root, "cancelled.txt")
        val fixture = fixture {
            file.writeText("written")
            throw CancellationException("stopped after write")
        }
        assertTrue(runCatching { fixture.runner.run(fixture.request) }.exceptionOrNull() is CancellationException)
        fixture.runner.repairInterrupted(fixture.request, userStopped = true)
        assertEquals("written", file.readText())
        assertEquals(ToolRecoveryNotice.OUTCOME_UNKNOWN, fixture.store.loadStrict("s").filterIsInstance<ToolResult>().last().errorCode)
    }
}
