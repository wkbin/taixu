package top.wkbin.taixu.harness.environment

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.core.common.result.*
import top.wkbin.taixu.harness.ToolOutputSpillStore
import top.wkbin.taixu.runtime.environment.*

class PortableArtifactsTest {
    private class Storage : ExecutionArtifacts {
        override val storageKey = UUID.randomUUID().toString()
        val entries = mutableListOf<ExecutionArtifact>()
        var failList = false
        var failDelete = false
        var cancelList = false
        var writes = 0
        override suspend fun list(): AppResult<List<ExecutionArtifact>> {
            if (cancelList) throw CancellationException("remote disconnected")
            return if (failList) AppResult.Failure(AppError(ErrorCode.IO, "remote unavailable"))
            else AppResult.Success(entries.toList())
        }
        override suspend fun write(name: String, content: String): AppResult<Unit> {
            writes++; entries += ExecutionArtifact(name, content.toByteArray().size.toLong()); return AppResult.Success(Unit)
        }
        override suspend fun delete(name: String): Boolean = if (failDelete) false else entries.removeAll { it.name == name }
    }

    @Test fun `remote listing failure does not bypass retention budget`() = runBlocking {
        val storage = Storage().apply { failList = true }
        assertNull(ToolOutputSpillStore.spill(storage, "read", "output"))
        assertEquals(0, storage.writes)
    }

    @Test fun `failed eviction cannot exceed file or byte budgets`() = runBlocking {
        val storage = Storage().apply { failDelete = true }
        repeat(ToolOutputSpillStore.MAX_WORKSPACE_FILES) {
            storage.entries += ExecutionArtifact(ToolOutputSpillStore.fileName("read", System.currentTimeMillis(), "$it"), 1)
        }
        assertNull(ToolOutputSpillStore.spill(storage, "read", "output"))
        assertEquals(0, storage.writes)
        storage.entries.clear()
        storage.entries += ExecutionArtifact(ToolOutputSpillStore.fileName("read", System.currentTimeMillis()),
            ToolOutputSpillStore.MAX_WORKSPACE_BYTES)
        assertNull(ToolOutputSpillStore.spill(storage, "read", "output"))
        assertEquals(0, storage.writes)
    }

    @Test fun `successful eviction admits one replacement and cancellation propagates`() = runBlocking {
        val storage = Storage()
        repeat(ToolOutputSpillStore.MAX_WORKSPACE_FILES) {
            storage.entries += ExecutionArtifact(ToolOutputSpillStore.fileName("read", System.currentTimeMillis(), "$it"), 1)
        }
        assertNotNull(ToolOutputSpillStore.spill(storage, "read", "output"))
        assertEquals(ToolOutputSpillStore.MAX_WORKSPACE_FILES, storage.entries.size)
        storage.cancelList = true
        assertTrue(runCatching { ToolOutputSpillStore.spill(storage, "read", "output") }.exceptionOrNull() is CancellationException)
        assertEquals(1, storage.writes)
    }
}
