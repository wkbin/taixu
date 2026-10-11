package top.wkbin.taixu.harness.environment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.wkbin.taixu.core.common.result.*
import top.wkbin.taixu.core.model.SubagentTaskSpec
import top.wkbin.taixu.harness.*
import top.wkbin.taixu.runtime.environment.*

class PortableSubagentReportsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun outcome(summary: String) = SubagentOrchestrator.SubagentExecutionOutcome(
        SubagentTaskSpec(taskName = "task", prompt = "prompt", role = "coder"), "subagent:coder:task", true, summary, 1,
    )
    private fun registry() = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
        FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner))
    })
    @Test fun `parent reads full sanitized child reports from the same remote world`() = runBlocking {
        val host = temporary.newFolder()
        val registry = registry()
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        world.secretValues += "opaque-private-value"
        val raw = "api_key=abcdefghijklmnopqrstuvwxyz0123456789\nopaque-private-value\n" + "result\n".repeat(4000)
        val result = SubagentSummaryPublisher(WorkspaceFileAccess(host), registry).publish(listOf(outcome(raw)), "one", "/workspace/p")
        val path = world.contents.keys.single()
        assertTrue(path.startsWith(".taixu-outputs/"))
        assertTrue(result.contains(path))
        val read = world.files.read(path).getOrNull()!!
        assertFalse(read.contains("abcdefghijklmnopqrstuvwxyz0123456789"))
        assertFalse(read.contains("opaque-private-value"))
        assertTrue(read.endsWith("result\n".repeat(4000)))
        assertEquals(0, host.listFiles()!!.size)
        registry.closeSession("one")
    }
    @Test fun `report storage failure still truncates rather than injecting the full report`() = runBlocking {
        val registry = registry()
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        val files = object : ExecutionFiles by world.files {
            override suspend fun write(path: String, content: String): AppResult<Unit> = AppResult.Failure(AppError(ErrorCode.IO, "offline"))
        }
        val long = "x".repeat(30000)
        val result = paginateSubagentSummary(listOf(outcome(long)), "/workspace/p", files)
        assertFalse(result.contains(long))
        assertTrue(result.contains("完整结果未能保存"))
        assertTrue(result.length < 6000)
        assertTrue(world.contents.isEmpty())
        registry.closeSession("one")
    }
    @Test fun `report write cancellation is not swallowed as a retention failure`() = runBlocking {
        val registry = registry()
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        val files = object : ExecutionFiles by world.files {
            override suspend fun write(path: String, content: String): AppResult<Unit> = throw CancellationException("cancelled")
        }
        assertTrue(runCatching { paginateSubagentSummary(listOf(outcome("x".repeat(30000))), "/workspace/p", files) }
            .exceptionOrNull() is CancellationException)
        registry.closeSession("one")
    }
}
