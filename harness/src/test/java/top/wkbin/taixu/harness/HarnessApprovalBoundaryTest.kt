package top.wkbin.taixu.harness

import java.lang.reflect.Proxy
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.logging.SensitiveDataRedactor
import top.wkbin.taixu.core.database.AgentApprovalRepository
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.HarnessSessionEntity
import top.wkbin.taixu.core.model.ApprovalMode
import top.wkbin.taixu.harness.directory.CapabilityToolGateway
import top.wkbin.taixu.harness.directory.NestedCalls
import top.wkbin.taixu.core.database.RoomHarnessRuntimeRepository
import top.wkbin.taixu.core.database.RoomHarnessSessionRepository
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.datastore.SettingsDataStore
import top.wkbin.taixu.core.network.FileDownloader
import top.wkbin.taixu.core.security.SecretRedactor
import top.wkbin.taixu.core.security.SecretManager
import top.wkbin.taixu.harness.events.AgentEventLogger
import top.wkbin.taixu.harness.events.HarnessEventBus
import top.wkbin.taixu.harness.metrics.RunMetrics
import top.wkbin.taixu.harness.operation.OperationCoordinator
import top.wkbin.taixu.harness.operation.OperationStatus
import top.wkbin.taixu.harness.projection.CurrentSessionTracker
import top.wkbin.taixu.harness.projection.SessionMessageProjector
import top.wkbin.taixu.harness.projection.SessionStateMirrors
import top.wkbin.taixu.harness.recovery.RecoveryManager
import top.wkbin.taixu.harness.recovery.RecoveryOutcome
import top.wkbin.taixu.harness.session.SessionTreeStore
import top.wkbin.taixu.harness.validation.ToolCallLoopDetector
import top.wkbin.taixu.runtime.LinuxRuntime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HarnessApprovalBoundaryTest {
    @Test
    fun questionRoundRemainsWaitingApprovalAfterResultsPersistAndRuntimeRebuilds() = verifyWaitingBoundary(
        "ask_user", """{"questions":[{"question":"What next?"}]}""",
    )

    @Test
    fun scriptRoundPersistsOnlyParentCallAndRetainsApprovalAfterRuntimeRebuilds() = verifyWaitingBoundary(
        "use_capability", """{"action":"script","code":"capability.call('host','package_disable',{package:'example.app'});"}""",
    )

    private fun verifyWaitingBoundary(toolName: String, arguments: String) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
            val sessions = RoomHarnessSessionRepository(database.harnessSessionDao())
            sessions.upsert(HarnessSessionEntity("session", "test", 1, 1, null,
                approvalMode = ApprovalMode.REQUEST.id, workspace = "/workspace/test"))
            val approvals = AgentApprovalRepository(database.agentApprovalDao())
            val logger = AppLogger(context, SensitiveDataRedactor { it })
            val store = SessionTreeStore(repository, Json, logger)
            val events = HarnessEventBus()
            val operations = OperationCoordinator(repository, Json, events)
            val tracker = CurrentSessionTracker()
            val preferences = AgentPreferences(SettingsDataStore(context, SecretManager()))
            val resolver = HarnessPathResolver()
            val executor = ToolExecutor(
                WorkspaceFileAccess(context.cacheDir), resolver,
                ApprovalPolicyEngine(resolver), SecretRedactor(),
                hostToolBackend = HostCapabilityToolBackend(secretRedactor = SecretRedactor()),
                linuxCommandToolBackend = LinuxCommandToolBackend(unusedPort<LinuxRuntime>(), resolver),
                downloadToolBackend = DownloadToolBackend(
                    unusedPort<FileDownloader>(), WorkspaceFileAccess(context.cacheDir), WorkspaceMutationSnapshots(),
                ),
                contextMemoryToolBackend = ContextMemoryToolBackend(),
                askUserToolBackend = AskUserToolBackend(ApprovalPolicyEngine(resolver), approvals),
                promptAssetToolBackend = PromptAssetToolBackend(),
                harnessServiceToolBackend = HarnessServiceToolBackend(),
                capabilityToolGateway = CapabilityToolGateway(null) { _, _, _, _ -> error("unexpected host dispatch") },
                approvalRepository = approvals, sessionDao = sessions,
            )
            val runner = HarnessToolRoundRunner(
                executor, sessions, Json, operations, SessionMessageProjector(store, tracker),
                SessionStateMirrors(tracker), AgentEventLogger(preferences, logger), ToolRoundDispatcher(),
            )
            val operationId = operations.acceptRun("session", UserMessage("user", 1L, "Ask me"))
            try {
                runner.executeToolCalls(
                    "session", listOf(
                        ApiToolCallSpec("call", toolName, arguments),
                        ApiToolCallSpec("later-read", "read", """{"path":"after-approval.txt"}"""),
                        ApiToolCallSpec("later-write", "write", """{"path":"after-approval.txt","content":"unexpected"}"""),
                    ),
                    null, "/workspace/test", false,
                    ModelConfig("test", "test", "test", "https://example.invalid", null),
                    operationId, 0, RunMetrics(1L), ToolCallLoopDetector(),
                )
                fail("Question must pause the round")
            } catch (_: ApprovalPauseException) {
                // Observe durable state rather than the live UI mirror.
            }
            assertEquals(OperationStatus.WAITING_APPROVAL.id, repository.findOperation(operationId)!!.status)
            assertTrue(store.load("session").filterIsInstance<ToolResult>().single().awaitingApproval)
            assertEquals(1, store.load("session").filterIsInstance<ToolCall>().size)
            if (toolName == "use_capability") {
                val pending = approvals.pendingNow("session").single()
                assertEquals(store.load("session").filterIsInstance<ToolCall>().single().id, pending.toolCallId)
                assertTrue(pending.argumentsJson.contains("\"action\":\"call\""))
                assertEquals(1, NestedCalls.read(store.load("session").filterIsInstance<ToolResult>().single().metadata)!!.calls.size)
            }
            val recovered = RecoveryManager(
                repository, OperationCoordinator(repository, Json, events), approvals, Json, events,
            ).recoverSession("session")
            assertEquals(RecoveryOutcome.WaitingApproval, recovered)
        } finally {
            database.close()
        }
    }

    private inline fun <reified T> unusedPort(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected port call: ${method.name}") } as T
}
