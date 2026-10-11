package top.wkbin.taixu.runtime.environment

import top.wkbin.taixu.core.common.result.AppResult

data class ExecutionArtifact(val name: String, val sizeBytes: Long)

/** Internal output storage in the same workspace as [ExecutionEnvironment.files]. */
interface ExecutionArtifacts {
    /** Stable across sessions sharing storage, distinct across backend hosts/workspaces. */
    val storageKey: String
    val maxArtifactBytes: Int get() = MAX_ARTIFACT_BYTES
    /** Missing directory returns an empty list; failures must not look like empty storage. */
    suspend fun list(): AppResult<List<ExecutionArtifact>>
    /** Name is a single basename. Write atomically, preserve existing data on failure/cancellation. */
    suspend fun write(name: String, content: String): AppResult<Unit>
    suspend fun delete(name: String): Boolean
    /** Optional backend-specific exclusion from source control. */
    suspend fun excludeFromSourceControl() {}

    companion object {
        const val DIRECTORY = ".taixu-outputs"
        const val MAX_ARTIFACT_BYTES = 16 * 1024 * 1024
    }
}
