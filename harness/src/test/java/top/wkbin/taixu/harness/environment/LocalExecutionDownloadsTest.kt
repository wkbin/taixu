package top.wkbin.taixu.harness.environment

import java.io.File
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.wkbin.taixu.core.network.*
import top.wkbin.taixu.harness.WorkspaceFileAccess
import top.wkbin.taixu.runtime.environment.*

class LocalExecutionDownloadsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun downloader(bytes: ByteArray) = object : FileDownloader {
        override fun download(request: DownloadRequest) = flow {
            request.destination.parentFile?.mkdirs()
            request.destination.writeBytes(bytes)
            emit(DownloadEvent.Completed(request.destination))
        }
    }
    private fun request(path: String) = ExecutionDownloadRequest("https://example.com/a", path, null, 3, 4096)

    @Test fun `local adapter preserves image validation and duplicate warnings`() = runBlocking {
        val root = temporary.newFolder()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        File(root, "existing.png").writeBytes(png)
        val adapter = LocalExecutionDownloads(downloader(png), WorkspaceFileAccess(root))
        val result = adapter.download(request("a.png")).last() as ExecutionDownloadEvent.Completed
        assertTrue(result.validImage)
        assertEquals("existing.png", result.duplicateOf)
        assertEquals(8L, result.sizeBytes)
        assertFalse(result.checksumVerified)
    }

    @Test fun `invalid image and unexpected completion destination do not pass validation`() = runBlocking {
        val root = temporary.newFolder()
        val invalid = LocalExecutionDownloads(downloader("error page".toByteArray()), WorkspaceFileAccess(root))
        assertFalse((invalid.download(request("a.jpg")).last() as ExecutionDownloadEvent.Completed).validImage)
        val wrong = LocalExecutionDownloads(object : FileDownloader {
            override fun download(request: DownloadRequest) = flow { emit(DownloadEvent.Completed(File(root, "other.bin"))) }
        }, WorkspaceFileAccess(root))
        assertTrue(runCatching { wrong.download(request("a.bin")).last() }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `local download cannot escape workspace`() = runBlocking {
        val root = temporary.newFolder()
        val adapter = LocalExecutionDownloads(downloader("data".toByteArray()), WorkspaceFileAccess(root))
        assertTrue(runCatching { adapter.download(request("../escaped.bin")).last() }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, root.listFiles()!!.size)
    }
}
