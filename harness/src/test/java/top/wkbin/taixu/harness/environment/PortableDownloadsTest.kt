package top.wkbin.taixu.harness.environment

import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.wkbin.taixu.core.network.FileDownloader
import top.wkbin.taixu.harness.*
import top.wkbin.taixu.runtime.environment.*

class PortableDownloadsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val localDownloader = Proxy.newProxyInstance(FileDownloader::class.java.classLoader,
        arrayOf(FileDownloader::class.java)) { _, _, _ -> error("local downloader must not be called") } as FileDownloader
    private fun request(sha: Boolean = false) = DownloadToolRequest(Json.parseToJsonElement(
        """{"url":"https://example.com/a","destination":"nested/a.bin","max_bytes":4096${if (sha) ",\"sha256\":\"${"a".repeat(64)}\"" else ""}}"""
    ) as JsonObject, "one", "/workspace/p", null)
    private fun registry() = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
        FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner))
    })

    @Test fun `download passes guest paths limits and checksum to remote capability`() = runBlocking {
        val host = temporary.newFolder()
        val registry = registry()
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        var seen: ExecutionDownloadRequest? = null
        world.downloads = ExecutionDownloads { spec -> flow {
            seen = spec
            emit(ExecutionDownloadEvent.Progress(7, 7))
            emit(ExecutionDownloadEvent.Verifying)
            world.files.write(spec.destination, "payload")
            emit(ExecutionDownloadEvent.Completed(7, true))
        } }
        val backend = DownloadToolBackend(localDownloader, WorkspaceFileAccess(host), WorkspaceMutationSnapshots(), registry)
        val result = backend.execute(request(sha = true))
        assertTrue(result.first)
        assertTrue(result.second.contains("SHA-256：已校验"))
        assertEquals("nested/a.bin", seen!!.destination)
        assertEquals(4096L, seen.maxBytes)
        assertEquals(3, seen.maxAttempts)
        assertEquals("a".repeat(64), seen.sha256)
        assertEquals("payload", world.contents["nested/a.bin"])
        assertEquals(0, host.listFiles()!!.size)
        registry.closeSession("one")
    }

    @Test fun `missing completion and invalid image are reported as failures`() = runBlocking {
        val registry = registry()
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        val backend = DownloadToolBackend(localDownloader, WorkspaceFileAccess(temporary.newFolder()), WorkspaceMutationSnapshots(), registry)
        world.downloads = ExecutionDownloads { flow { emit(ExecutionDownloadEvent.Started) } }
        assertFalse(backend.execute(request()).first)
        world.downloads = ExecutionDownloads { flow { emit(ExecutionDownloadEvent.Completed(7, false, validImage = false)) } }
        assertFalse(backend.execute(request()).first)
        registry.closeSession("one")
    }

    @Test fun `checksum requests cannot report success without verified completion`() = runBlocking {
        val registry = registry()
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        world.downloads = ExecutionDownloads { flow { emit(ExecutionDownloadEvent.Verifying); emit(ExecutionDownloadEvent.Completed(7, false)) } }
        val backend = DownloadToolBackend(localDownloader, WorkspaceFileAccess(temporary.newFolder()), WorkspaceMutationSnapshots(), registry)
        val result = runCatching { backend.execute(request(sha = true)) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        registry.closeSession("one")
    }

    @Test fun `session close drains the remote transfer before closing its environment`() = runBlocking {
        val registry = registry()
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        val started = CompletableDeferred<Unit>()
        world.downloads = ExecutionDownloads { flow {
            try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { yield(); world.events += "download-drained" } }
        } }
        val backend = DownloadToolBackend(localDownloader, WorkspaceFileAccess(temporary.newFolder()), WorkspaceMutationSnapshots(), registry)
        val job = launch { registry.activity("one", "/workspace/p") { backend.execute(request()) } }
        started.await()
        registry.closeSession("one")
        job.join()
        assertTrue(world.events.indexOf("download-drained") < world.events.indexOf("environment-close"))
    }
}
