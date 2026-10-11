package top.wkbin.taixu.harness.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface ToolGateDecision {
    data object Allow : ToolGateDecision
    data class Block(val reason: String) : ToolGateDecision
}

data class ToolCheckpointBlock(val checkpointId: String, val reason: String)
data class ToolCheckpointNote(val checkpointId: String, val text: String)

/** Trusted code callbacks: veto a call or annotate its result; no argument/result replacement. */
interface ToolCheckpoint<Request, Result> {
    val id: String
    suspend fun before(request: Request): ToolGateDecision = ToolGateDecision.Allow
    suspend fun after(request: Request, result: Result): String? = null
}

/** Awaited control checkpoints, independent of lossy observation events and persistence. */
class ToolCheckpoints<Request, Result>(checkpoints: List<ToolCheckpoint<Request, Result>> = emptyList()) {
    private class Entry<Q, R>(val id: String, val checkpoint: ToolCheckpoint<Q, R>, val applies: (Q) -> Boolean)
    private val lock = Any()
    private val registrations = mutableListOf<Entry<Request, Result>>()

    init {
        checkpoints.forEach { register(it) }
    }

    /** Unload removes this exact registration, including after an ID has been reused. */
    fun register(checkpoint: ToolCheckpoint<Request, Result>, appliesTo: (Request) -> Boolean = { true }): AutoCloseable {
        val entry = Entry(checkpoint.id, checkpoint, appliesTo)
        synchronized(lock) {
            require(entry.id.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid tool checkpoint id" }
            require(registrations.size < 32) { "Too many tool checkpoints" }
            require(registrations.none { it.id == entry.id }) { "Duplicate tool checkpoint id" }
            registrations.add(entry)
        }
        return AutoCloseable { synchronized(lock) { registrations.remove(entry) }; Unit }
    }

    private fun snapshot() = synchronized(lock) { registrations.toList() }

    suspend fun before(request: Request): ToolCheckpointBlock? {
        currentCoroutineContext().ensureActive()
        for (entry in snapshot()) {
            val id = entry.id
            val decision = try {
                if (!entry.applies(request)) continue
                entry.checkpoint.before(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                // Never expose exception messages, which may contain credentials or raw payloads.
                return ToolCheckpointBlock(id, "执行前检查点失败（${failure::class.simpleName}），本次调用未执行。")
            }
            currentCoroutineContext().ensureActive()
            if (decision is ToolGateDecision.Block) {
                return ToolCheckpointBlock(id, decision.reason.take(MAX_NOTE_CHARS).ifBlank { "工具调用被扩展检查点阻止。" })
            }
        }
        return null
    }

    suspend fun after(request: Request, result: Result): List<ToolCheckpointNote> {
        currentCoroutineContext().ensureActive()
        val notes = mutableListOf<ToolCheckpointNote>()
        for (entry in snapshot()) {
            val id = entry.id
            val text = try {
                if (!entry.applies(request)) continue
                entry.checkpoint.after(request, result)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                // A failed annotation must not turn an already executed effect into a retryable tool failure.
                "结果检查点失败（${failure::class.simpleName}），工具结果状态保持不变。"
            }
            currentCoroutineContext().ensureActive()
            if (!text.isNullOrBlank()) notes += ToolCheckpointNote(id, text.take(MAX_NOTE_CHARS))
        }
        return notes
    }

    companion object { const val MAX_NOTE_CHARS = 4_096 }
}
