package top.wkbin.taixu.harness.subagent

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import top.wkbin.taixu.harness.operation.OperationCoordinator

/** Settle the pending effect before detaching the lane's only recovery pointer. */
internal class SubagentLaneFinalizer(
    private val operations: OperationCoordinator,
    private val releaseResources: suspend () -> Unit = {},
) {
    suspend fun finish(sessionId: String, outcome: String, finalEntryId: String? = null,
        details: String? = null, laneName: String) = withContext(NonCancellable) {
        releaseResources()
        operations.finish(sessionId, outcome, finalEntryId, details, laneName)
    }
    suspend fun interrupted(sessionId: String, laneName: String, operationId: String, outcome: String, details: String?): String? =
        withContext(NonCancellable) {
            val operation = operations.active(sessionId, laneName) ?: return@withContext null
            check(operation.id == operationId) { "Interrupted lane operation changed" }
            val repairOutput = operations.settleInterruptedTool(operationId)
            // Read/commit errors escape before this point, leaving the operation recoverable.
            finish(sessionId, outcome, details = details, laneName = laneName)
            repairOutput
        }
}
