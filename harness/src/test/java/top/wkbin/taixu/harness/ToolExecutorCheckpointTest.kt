package top.wkbin.taixu.harness

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.datastore.SettingsDataStore
import top.wkbin.taixu.core.model.ApprovalMode
import top.wkbin.taixu.core.network.FileDownloader
import top.wkbin.taixu.core.security.SecretManager
import top.wkbin.taixu.core.security.SecretRedactor
import top.wkbin.taixu.harness.core.ToolCheckpoint
import top.wkbin.taixu.harness.core.ToolCheckpoints
import top.wkbin.taixu.harness.core.ToolGateDecision
import top.wkbin.taixu.harness.directory.CapabilityToolGateway
import top.wkbin.taixu.harness.events.AgentEventLogger
import top.wkbin.taixu.harness.events.HarnessEventBus
import top.wkbin.taixu.harness.metrics.RunMetrics
import top.wkbin.taixu.harness.operation.OperationCoordinator
import top.wkbin.taixu.harness.projection.CurrentSessionTracker
import top.wkbin.taixu.harness.projection.SessionMessageProjector
import top.wkbin.taixu.harness.projection.SessionStateMirrors
import top.wkbin.taixu.harness.session.SessionTreeStore
import top.wkbin.taixu.harness.validation.ToolCallLoopDetector
import top.wkbin.taixu.runtime.LinuxRuntime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolExecutorCheckpointTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: AppDatabase
    private lateinit var sessions: RoomHarnessSessionRepository
    private lateinit var approvals: AgentApprovalRepository
    private lateinit var context: Context
    private lateinit var root: File
    private val call = ToolCall("call-write", 1, HarnessTool.WRITE,
        Json.parseToJsonElement("""{"path":"a.txt","content":"written"}""") as JsonObject, rawToolName = "write")

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        root = temporary.newFolder("workspace")
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        sessions = RoomHarnessSessionRepository(database.harnessSessionDao())
        approvals = AgentApprovalRepository(database.agentApprovalDao())
        seed()
    }

    @After fun tearDown() { database.close() }

    private suspend fun seed(mode: String = ApprovalMode.FULL_ACCESS.id, runMode: String = "build") = sessions.upsert(
        HarnessSessionEntity("session", "test", 1, 1, null, approvalMode = mode, runMode = runMode),
    )

    private fun checkpoint(
        before: suspend (ToolExecutionRequest) -> ToolGateDecision = { ToolGateDecision.Allow },
        after: suspend (ToolExecutionRequest, ToolResult) -> String? = { _, _ -> null },
    ) = object : ToolCheckpoint<ToolExecutionRequest, ToolResult> {
        override val id = "test-hook"
        override suspend fun before(request: ToolExecutionRequest) = before(request)
        override suspend fun after(request: ToolExecutionRequest, result: ToolResult) = after(request, result)
    }

    private fun executor(hook: ToolCheckpoint<ToolExecutionRequest, ToolResult>): ToolExecutor {
        val paths = HarnessPathResolver()
        return ToolExecutor(
            WorkspaceFileAccess(root), paths, ApprovalPolicyEngine(paths), SecretRedactor(),
            hostToolBackend = HostCapabilityToolBackend(secretRedactor = SecretRedactor()),
            linuxCommandToolBackend = LinuxCommandToolBackend(unusedPort<LinuxRuntime>(), paths),
            downloadToolBackend = DownloadToolBackend(
                unusedPort<FileDownloader>(), WorkspaceFileAccess(root), WorkspaceMutationSnapshots(),
            ),
            contextMemoryToolBackend = ContextMemoryToolBackend(),
            askUserToolBackend = AskUserToolBackend(ApprovalPolicyEngine(paths), approvals),
            promptAssetToolBackend = PromptAssetToolBackend(),
            harnessServiceToolBackend = HarnessServiceToolBackend(),
            capabilityToolGateway = CapabilityToolGateway(null) { _, _, _, _ -> error("unexpected host dispatch") },
            approvalRepository = approvals, sessionDao = sessions,
            toolCheckpoints = ToolCheckpoints(listOf(hook)),
        )
    }

    @Test fun `allow checkpoint cannot bypass approval or replace pending flags`() = runBlocking {
        seed(ApprovalMode.REQUEST.id)
        val result = executor(checkpoint(after = { _, observed ->
            observed.copy(success = true, awaitingApproval = false)
            "annotation only"
        })).execute(call, "session")
        assertFalse(result.success)
        assertTrue(result.awaitingApproval)
        assertNotNull(result.approvalRequestId)
        assertEquals(result.approvalRequestId, approvals.pendingNow("session").single().id)
        assertEquals(call.id, result.toolCallId)
        assertFalse(File(root, "a.txt").exists())
    }

    @Test fun `lane approval handoff remains deferred without creating a request`() = runBlocking {
        seed(ApprovalMode.REQUEST.id)
        val result = executor(checkpoint(after = { _, _ -> "handoff note" })).execute(call, "session", allowApprovalRequest = false)
        assertTrue(result.approvalDeferred)
        assertFalse(result.awaitingApproval)
        assertFalse(result.success)
        assertTrue(approvals.pendingNow("session").isEmpty())
        assertFalse(File(root, "a.txt").exists())
    }

    @Test fun `plan mode rejects mutation even when checkpoint allows it`() = runBlocking {
        seed(runMode = "plan")
        val result = executor(checkpoint()).execute(call, "session")
        assertFalse(result.success)
        assertTrue(result.output.contains("PLAN"))
        assertTrue(approvals.pendingNow("session").isEmpty())
        assertFalse(File(root, "a.txt").exists())
    }

    @Test fun `checkpoint veto applies to approved replay without creating another approval`() = runBlocking {
        seed(ApprovalMode.REQUEST.id)
        val result = executor(checkpoint(before = { ToolGateDecision.Block("policy veto") }))
            .execute(call, "session", bypassApproval = true)
        assertFalse(result.success)
        assertEquals(call.id, result.toolCallId)
        assertTrue(approvals.pendingNow("session").isEmpty())
        assertFalse(File(root, "a.txt").exists())
    }

    @Test fun `before failure closes gate and hides raw exception payload`() = runBlocking {
        val result = executor(checkpoint(before = { error("secret-payload") })).execute(call, "session")
        assertFalse(result.success)
        assertFalse(result.output.contains("secret-payload"))
        assertFalse(File(root, "a.txt").exists())
    }

    @Test fun `after failure preserves successful mutation and identity`() = runBlocking {
        val result = executor(checkpoint(after = { _, _ -> error("secret-payload") })).execute(call, "session")
        assertTrue(result.success)
        assertEquals(call.id, result.toolCallId)
        assertEquals("written", File(root, "a.txt").readText())
        assertTrue(result.output.contains("结果检查点失败"))
        assertFalse(result.output.contains("secret-payload"))
    }

    @Test fun `extension notes pass through output redaction`() = runBlocking {
        val result = executor(checkpoint(after = { _, _ -> "Bearer sk-test-sensitive-secret" })).execute(call, "session")
        assertTrue(result.success)
        assertFalse(result.output.contains("sk-test-sensitive-secret"))
        assertTrue(result.output.contains("REDACTED"))
    }

    @Test fun `checkpoint cancellation is propagated without starting a backend`() = runBlocking {
        val error = runCatching {
            executor(checkpoint(before = { throw CancellationException("stop") })).execute(call, "session")
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertFalse(File(root, "a.txt").exists())
    }

    @Test fun `question keeps its approval request while allowing a text annotation`() = runBlocking {
        val question = ToolCall("ask", 1, HarnessTool.ASK_USER,
            Json.parseToJsonElement("""{"questions":[{"question":"What next?"}]}""") as JsonObject, rawToolName = "ask_user")
        val result = executor(checkpoint(after = { _, _ -> "question note" })).execute(question, "session")
        assertTrue(result.awaitingApproval)
        assertEquals("ask", result.toolCallId)
        assertEquals("ask_user", approvals.pendingNow("session").single().toolName)
    }

    @Test fun `durable intent precedes checkpoints and annotated result waits for after callback`() = runBlocking {
        val runtime = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val logger = AppLogger(context, SensitiveDataRedactor { it })
        val store = SessionTreeStore(runtime, Json, logger)
        val events = HarnessEventBus()
        val operations = OperationCoordinator(runtime, Json, events)
        val tracker = CurrentSessionTracker()
        val preferences = AgentPreferences(SettingsDataStore(context, SecretManager()))
        val enteredAfter = CompletableDeferred<Unit>()
        val releaseAfter = CompletableDeferred<Unit>()
        var observedId = ""
        val hook = checkpoint(before = { request ->
            observedId = store.load("session").filterIsInstance<ToolCall>().single().id
            assertEquals(request.call.id, observedId)
            ToolGateDecision.Allow
        }, after = { _, _ -> enteredAfter.complete(Unit); releaseAfter.await(); "persisted note" })
        val runner = HarnessToolRoundRunner(
            executor(hook), sessions, Json, operations, SessionMessageProjector(store, tracker),
            SessionStateMirrors(tracker), AgentEventLogger(preferences, logger), ToolRoundDispatcher(),
        )
        val operation = operations.acceptRun("session", UserMessage("user", 1, "write"))
        val running = async {
            runner.executeToolCalls("session", listOf(ApiToolCallSpec("call-write", "write", call.args.toString())),
                null, "", false, ModelConfig("test", "test", "test", "https://example.invalid", null),
                operation, 0, RunMetrics(1), ToolCallLoopDetector())
        }
        try {
            enteredAfter.await()
            assertEquals("written", File(root, "a.txt").readText())
            assertTrue(store.load("session").filterIsInstance<ToolResult>().isEmpty())
            releaseAfter.complete(Unit)
            assertTrue(running.await())
            val settled = store.load("session").filterIsInstance<ToolResult>().single()
            assertEquals(observedId, settled.toolCallId)
            assertTrue(settled.success)
            assertTrue(settled.output.contains("persisted note"))
        } finally { releaseAfter.complete(Unit); running.cancel() }
    }

    @Test fun `parallel reads publish in model order and write is visible to the next read`() = runBlocking {
        withTimeout(15_000) {
            File(root, "a.txt").writeText("before")
            File(root, "b.txt").writeText("other")
            val runtime = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
            val logger = AppLogger(context, SensitiveDataRedactor { it })
            val store = SessionTreeStore(runtime, Json, logger)
            val events = HarnessEventBus()
            val operations = OperationCoordinator(runtime, Json, events)
            val tracker = CurrentSessionTracker()
            val preferences = AgentPreferences(SettingsDataStore(context, SecretManager()))
            val slowEntered = CompletableDeferred<Unit>()
            val fastFinished = CompletableDeferred<Unit>()
            val releaseSlow = CompletableDeferred<Unit>()
            val started = mutableListOf<String>()
            val hook = checkpoint(before = { request ->
                val providerId = request.call.id.substringBeforeLast('_')
                started += providerId
                if (providerId == "slow") { slowEntered.complete(Unit); releaseSlow.await() }
                if (providerId == "call-write") {
                    assertEquals(listOf("slow", "fast"), store.load("session").filterIsInstance<ToolResult>().map { it.toolCallId.substringBeforeLast('_') })
                }
                ToolGateDecision.Allow
            }, after = { request, _ ->
                if (request.call.id.substringBeforeLast('_') == "fast") fastFinished.complete(Unit)
                null
            })
            val runner = HarnessToolRoundRunner(
                executor(hook), sessions, Json, operations, SessionMessageProjector(store, tracker),
                SessionStateMirrors(tracker), AgentEventLogger(preferences, logger), ToolRoundDispatcher(),
            )
            val operation = operations.acceptRun("session", UserMessage("user", 1, "read write read"))
            val specs = listOf(
                ApiToolCallSpec("slow", "read", """{"path":"a.txt"}"""),
                ApiToolCallSpec("fast", "read", """{"path":"b.txt"}"""),
                ApiToolCallSpec("call-write", "write", call.args.toString()),
                ApiToolCallSpec("after-write", "read", """{"path":"a.txt"}"""),
            )
            val running = async {
                runner.executeToolCalls("session", specs, null, "", false,
                    ModelConfig("test", "test", "test", "https://example.invalid", null),
                    operation, 0, RunMetrics(1), ToolCallLoopDetector())
            }
            try {
                slowEntered.await()
                fastFinished.await()
                assertEquals(listOf("slow", "fast"), started)
                assertTrue(store.load("session").filterIsInstance<ToolResult>().isEmpty())
                assertEquals("before", File(root, "a.txt").readText())
                releaseSlow.complete(Unit)
                assertTrue(running.await())
                val results = store.load("session").filterIsInstance<ToolResult>()
                assertEquals(specs.map { it.id }, results.map { it.toolCallId.substringBeforeLast('_') })
                assertTrue(results.all { it.success })
                assertTrue(results.first().output.contains("before"))
                assertTrue(results.last().output.contains("written"))
                assertEquals("written", File(root, "a.txt").readText())
                assertEquals(results.map { it.toolCallId }, store.load("session").filterIsInstance<ToolCall>().map { it.id })
            } finally { releaseSlow.complete(Unit); running.cancel() }
        }
    }

    @Test fun `main preflight rejects unknown and invalid calls before executing wrapped write`() = runBlocking {
        val runtime = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val logger = AppLogger(context, SensitiveDataRedactor { it })
        val store = SessionTreeStore(runtime, Json, logger)
        val operations = OperationCoordinator(runtime, Json, HarnessEventBus())
        val tracker = CurrentSessionTracker()
        val started = mutableListOf<HarnessTool>()
        val runner = HarnessToolRoundRunner(
            executor(checkpoint(before = { started += it.call.tool; ToolGateDecision.Allow })), sessions,
            Json, operations, SessionMessageProjector(store, tracker), SessionStateMirrors(tracker),
            AgentEventLogger(AgentPreferences(SettingsDataStore(context, SecretManager())), logger), ToolRoundDispatcher(),
        )
        val operation = operations.acceptRun("session", UserMessage("user", 1, "write"))
        val specs = listOf(
            ApiToolCallSpec("unknown", "execute", "{"),
            ApiToolCallSpec("invalid", "WRITE", "{}"),
            ApiToolCallSpec("valid", "WRITE", """{"arguments":{"path":"a.txt","content":"written"}}"""),
        )
        assertTrue(runner.executeToolCalls("session", specs, null, "", false,
            ModelConfig("test", "test", "test", "https://example.invalid", null),
            operation, 0, RunMetrics(1), ToolCallLoopDetector()))
        assertEquals(listOf(HarnessTool.WRITE), started)
        assertEquals("written", File(root, "a.txt").readText())
        val results = store.load("session").filterIsInstance<ToolResult>()
        assertTrue(results[0].output.contains("未知工具"))
        assertTrue(results[1].output.contains("缺少必填参数 path"))
        assertTrue(results[2].success)
    }

    @Test fun `lane lease checks the same wrapped path that the real file backend writes`() = runBlocking {
        val runtime = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val operations = OperationCoordinator(runtime, Json, HarnessEventBus())
        val operation = operations.acceptRun("session", UserMessage("user", 1, "write"), "child")
        val backend = executor(checkpoint())
        val runner = top.wkbin.taixu.harness.subagent.SubagentToolRoundRunner(
            operations, Json, ToolRoundDispatcher(),
        ) { call, session, _, _ -> backend.execute(call, session, allowApprovalRequest = false) }
        runner.execute(listOf(
            ApiToolCallSpec("outside", "write", """{"arguments":{"path":"outside.txt","content":"forbidden"}}"""),
            ApiToolCallSpec("allowed", "write", """{"arguments":{"path":"a.txt","content":"written"}}"""),
        ), "session", "", ModelConfig("test", "test", "test", "https://example.invalid", null),
            operation, 0, null, listOf("a.txt"))
        assertFalse(File(root, "outside.txt").exists())
        assertEquals("written", File(root, "a.txt").readText())
        assertEquals(listOf("write outside.txt"), runner.blockedWrites)
    }

    private inline fun <reified T> unusedPort(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected port call: ${method.name}") } as T
}
