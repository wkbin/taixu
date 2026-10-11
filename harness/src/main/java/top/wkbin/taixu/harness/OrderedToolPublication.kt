package top.wkbin.taixu.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Orders durable intents and results independently, while tool bodies may finish out of order. */
internal class OrderedToolPublication(size: Int) {
    private val intents = gates(size)
    private val results = gates(size)
    private val mutex = Mutex()

    suspend fun intent(index: Int, block: suspend () -> Unit) = publish(intents, index, block)

    suspend fun result(index: Int, block: suspend () -> Unit) = publish(results, index, block)

    private suspend fun publish(
        gates: List<CompletableDeferred<Unit>>,
        index: Int,
        block: suspend () -> Unit,
    ) {
        // Never await a predecessor while holding the publication mutex.
        gates[index].await()
        mutex.withLock { block() }
        // A failed commit must cancel the round, never publish a successor past it.
        gates[index + 1].complete(Unit)
    }

    private fun gates(size: Int) = List(size + 1) { CompletableDeferred<Unit>() }
        .also { it.first().complete(Unit) }
}
