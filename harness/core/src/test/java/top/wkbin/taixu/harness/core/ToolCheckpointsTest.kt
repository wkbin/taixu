package top.wkbin.taixu.harness.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ToolCheckpointsTest {
    @Test fun `stale unregister handle cannot remove new registration with reused id`() = runTest {
        val checkpoints = ToolCheckpoints<String, Boolean>()
        val old = checkpoints.register(checkpoint("guard", before = { ToolGateDecision.Block("old") }))
        old.close()
        val replacement = checkpoints.register(checkpoint("guard", before = { ToolGateDecision.Block("new") }))
        old.close()
        assertEquals("new", checkpoints.before("call")!!.reason)
        replacement.close()
        assertNull(checkpoints.before("call"))
    }
    private fun checkpoint(
        name: String,
        before: suspend (String) -> ToolGateDecision = { ToolGateDecision.Allow },
        after: suspend (String, Boolean) -> String? = { _, _ -> null },
    ) = object : ToolCheckpoint<String, Boolean> {
        override val id = name
        override suspend fun before(request: String) = before(request)
        override suspend fun after(request: String, result: Boolean) = after(request, result)
    }

    @Test fun `before waits for checkpoint completion`() = runTest {
        val release = CompletableDeferred<Unit>()
        val checkpoints = ToolCheckpoints(listOf(checkpoint("gate", before = { release.await(); ToolGateDecision.Allow })))
        val result = async { checkpoints.before("call") }
        runCurrent()
        assertFalse(result.isCompleted)
        release.complete(Unit)
        assertNull(result.await())
    }

    @Test fun `veto short circuits later checkpoints in registration order`() = runTest {
        val calls = mutableListOf<String>()
        val checkpoints = ToolCheckpoints(listOf(
            checkpoint("first", before = { calls += "first"; ToolGateDecision.Allow }),
            checkpoint("veto", before = { calls += "veto"; ToolGateDecision.Block("blocked") }),
            checkpoint("later", before = { calls += "later"; ToolGateDecision.Allow }),
        ))
        assertEquals(ToolCheckpointBlock("veto", "blocked"), checkpoints.before("call"))
        assertEquals(listOf("first", "veto"), calls)
    }

    @Test fun `before failure blocks without leaking exception payload`() = runTest {
        val checkpoints = ToolCheckpoints(listOf(checkpoint("gate", before = { error("secret-token") })))
        val blocked = checkpoints.before("call")!!
        assertEquals("gate", blocked.checkpointId)
        assertFalse(blocked.reason.contains("secret-token"))
    }

    @Test fun `after failure is an annotation and does not prevent later observers`() = runTest {
        val results = mutableListOf<Boolean>()
        val checkpoints = ToolCheckpoints(listOf(
            checkpoint("broken", after = { _, _ -> throw AssertionError("secret-token") }),
            checkpoint("next", after = { _, result -> results += result; "checked" }),
        ))
        val notes = checkpoints.after("call", true)
        assertEquals(listOf(true), results)
        assertEquals(listOf("broken", "next"), notes.map { it.checkpointId })
        assertFalse(notes.first().text.contains("secret-token"))
    }

    @Test fun `after waits for completion and bounds text notes`() = runTest {
        val release = CompletableDeferred<Unit>()
        val checkpoints = ToolCheckpoints(listOf(checkpoint("note", after = { _, _ ->
            release.await(); "x".repeat(10_000)
        })))
        val result = async { checkpoints.after("call", true) }
        runCurrent()
        assertFalse(result.isCompleted)
        release.complete(Unit)
        assertEquals(ToolCheckpoints.MAX_NOTE_CHARS, result.await().single().text.length)
    }

    @Test fun `blank annotation is omitted and blank veto still rejects`() = runTest {
        val checkpoints = ToolCheckpoints(listOf(checkpoint("gate", before = { ToolGateDecision.Block(" ") }, after = { _, _ -> " " })))
        assertTrue(checkpoints.before("call")!!.reason.isNotBlank())
        assertTrue(checkpoints.after("call", false).isEmpty())
    }

    @Test fun `registration snapshot cannot be changed by mutating caller list`() = runTest {
        val hooks = mutableListOf(checkpoint("gate", before = { ToolGateDecision.Block("blocked") }))
        val checkpoints = ToolCheckpoints(hooks)
        hooks.clear()
        assertNotNull(checkpoints.before("call"))
    }

    @Test fun `duplicate or invalid ids fail at construction`() {
        assertTrue(runCatching { ToolCheckpoints(listOf(checkpoint("same"), checkpoint("same"))) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ToolCheckpoints(listOf(checkpoint("invalid id"))) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ToolCheckpoints(List(33) { checkpoint("hook-$it") }) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `explicit cancellation is propagated from before and after`() = runTest {
        val checkpoints = ToolCheckpoints(listOf(checkpoint("gate",
            before = { throw CancellationException("stop") }, after = { _, _ -> throw CancellationException("stop") },
        )))
        assertTrue(runCatching { checkpoints.before("call") }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { checkpoints.after("call", true) }.exceptionOrNull() is CancellationException)
    }

    @Test fun `cancelled non cancellable before callback cannot allow execution`() = runTest {
        val release = CompletableDeferred<Unit>()
        var returned = false
        val checkpoints = ToolCheckpoints(listOf(checkpoint("gate", before = {
            withContext(NonCancellable) { release.await(); ToolGateDecision.Allow }
        })))
        val job = launch { checkpoints.before("call"); returned = true }
        runCurrent()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertFalse(returned)
    }

    @Test fun `cancelled non cancellable after callback cannot publish notes`() = runTest {
        val release = CompletableDeferred<Unit>()
        var returned = false
        val checkpoints = ToolCheckpoints(listOf(checkpoint("gate", after = { _, _ ->
            withContext(NonCancellable) { release.await(); "note" }
        })))
        val job = launch { checkpoints.after("call", true); returned = true }
        runCurrent()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertFalse(returned)
    }

    @Test fun `empty registration still checks cancellation`() = runTest {
        var returned = false
        val checkpoints = ToolCheckpoints<String, Boolean>()
        val job = launch { kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel(); checkpoints.before("call"); returned = true }
        job.join()
        assertFalse(returned)
    }
}
