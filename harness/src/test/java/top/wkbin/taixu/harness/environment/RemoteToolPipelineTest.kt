package top.wkbin.taixu.harness.environment

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.wkbin.taixu.harness.*
import top.wkbin.taixu.harness.directory.CapabilityToolGateway
import top.wkbin.taixu.core.security.SecretRedactor
import top.wkbin.taixu.core.network.FileDownloader
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.environment.*
import top.wkbin.taixu.harness.checkpoint.*

class RemoteToolPipelineTest {
    @Test fun `remote rewind and undo restore only the bound world's files`() = runBlocking {
        val host = temporary.newFolder()
        val local = WorkspaceFileAccess(host)
        local.write("a.txt", "phone")
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
            FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner))
        })
        val store = CheckpointStore()
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        world.contents["a.txt"] = "remote-after"
        world.contents["created.txt"] = "created"
        store.beginTurn("one", "edit")
        store.capture("one", "a.txt", "remote-before")
        store.captureAfterImage("one", "a.txt", "remote-after")
        store.capture("one", "created.txt", null)
        store.captureAfterImage("one", "created.txt", "created")
        store.endTurn("one")
        val rewind = RewindController(store, local, environments = registry)
        val result = rewind.commit(rewind.prepare("one", 0, RewindScope.CODE), "/workspace/p")
        assertFalse(result.partial)
        assertEquals(1, result.filesRestored)
        assertEquals(1, result.filesDeleted)
        assertEquals("remote-before", world.contents["a.txt"])
        assertNull(world.contents["created.txt"])
        assertFalse(rewind.undoLastRewind("one", "/workspace/p")!!.partial)
        assertEquals("remote-after", world.contents["a.txt"])
        assertEquals("created", world.contents["created.txt"])
        assertNull(store.takeRewindUndo("one"))
        assertEquals("phone", local.previewOrNull("a.txt"))
        registry.closeSession("one")
    }
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `remote external edits are preserved during rewind`() = runBlocking {
        val local = WorkspaceFileAccess(temporary.newFolder())
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
            FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner))
        })
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        val store = CheckpointStore()
        store.beginTurn("one", "edit")
        store.capture("one", "a.txt", "before")
        store.captureAfterImage("one", "a.txt", "agent")
        store.endTurn("one")
        world.contents["a.txt"] = "external"
        val rewind = RewindController(store, local, environments = registry)
        val result = rewind.commit(rewind.prepare("one", 0, RewindScope.CODE), "/workspace/p")
        assertTrue(result.partial)
        assertEquals(listOf("a.txt"), result.conflicts)
        assertEquals("external", world.contents["a.txt"])
        assertNull(rewind.undoLastRewind("one", "/workspace/p"))
        registry.closeSession("one")
    }
    @Test fun `remote undo reports deletion failure instead of complete success`() = runBlocking {
        val local = WorkspaceFileAccess(temporary.newFolder())
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
            FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner))
        })
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        world.contents["a.txt"] = "after-rewind"
        world.failedDeletes += "a.txt"
        val store = CheckpointStore()
        store.recordRewindUndo("one", RewindUndoRecord(listOf(FileSnap("a.txt", "after-rewind")), listOf(FileSnap("a.txt", null))))
        val result = RewindController(store, local, environments = registry).undoLastRewind("one", "/workspace/p")!!
        assertTrue(result.partial)
        assertTrue(result.note!!.contains("撤销失败"))
        assertEquals(0, result.filesDeleted)
        assertEquals("after-rewind", world.contents["a.txt"])
        registry.closeSession("one")
    }
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("unexpected local call: ${method.name}") } as T

    @Test fun `remote output truncation and download cannot write into host workspace`() = runBlocking {
        val host = temporary.newFolder()
        val local = WorkspaceFileAccess(host)
        val paths = HarnessPathResolver()
        val policy = ApprovalPolicyEngine(paths)
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
            FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner))
        })
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        world.secretValues += "remote-opaque-secret"
        world.contents["a.txt"] = "api_key=abcdefghijklmnopqrstuvwxyz0123456789\nremote-opaque-secret\n" + "line\n".repeat(20000)
        val download = DownloadToolBackend(unused<FileDownloader>(), local, WorkspaceMutationSnapshots(), registry)
        val executor = ToolExecutor(local, paths, policy, SecretRedactor(),
            HostCapabilityToolBackend(secretRedactor = SecretRedactor()),
            LinuxCommandToolBackend(unused<LinuxRuntime>(), paths, environments = registry), download,
            ContextMemoryToolBackend(), AskUserToolBackend(policy, null), PromptAssetToolBackend(),
            HarnessServiceToolBackend(), CapabilityToolGateway(null) { _, _, _, _ -> error("host") },
            workspaceToolBackend = WorkspaceToolBackend({ error("local files") }, environments = registry),
            environments = registry)
        val result = executor.execute(ToolCall("call", 1, HarnessTool.READ,
            JsonObject(mapOf("path" to JsonPrimitive("a.txt")))), "one", "/workspace/p")
        assertTrue(result.success)
        assertTrue(result.output.contains("截断"))
        val saved = world.contents.filterKeys { it.startsWith(".taixu-outputs/") }.values.single()
        assertFalse(saved.contains("abcdefghijklmnopqrstuvwxyz0123456789"))
        assertFalse(saved.contains("remote-opaque-secret"))
        assertTrue(saved.endsWith("line\n".repeat(20000)))
        assertTrue(result.output.contains("完整输出已保存"))
        assertEquals(0, host.listFiles()!!.size)
        val subagent = executor.execute(ToolCall("child", 1, HarnessTool.SUBAGENT,
            kotlinx.serialization.json.Json.parseToJsonElement("""{"subagents":[{"taskName":"check","department":"testing","agentQuery":"test automation","prompt":"report","writePaths":[]}]}""") as JsonObject),
            "one", "/workspace/p", bypassApproval = true)
        assertFalse(subagent.success)
        assertTrue(subagent.output.contains("未初始化子智能体编排器"))
        val downloaded = download.execute(DownloadToolRequest(JsonObject(mapOf(
            "url" to JsonPrimitive("https://example.com/a"), "destination" to JsonPrimitive("a.txt"))),
            "one", "/workspace/p", null))
        assertFalse(downloaded.first)
        assertEquals(0, host.listFiles()!!.size)
        registry.closeSession("one")
    }
}
