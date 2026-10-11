package top.wkbin.taixu.harness.environment

import kotlinx.coroutines.CancellationException
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.harness.WorkspaceFileAccess
import top.wkbin.taixu.runtime.environment.ExecutionArtifact
import top.wkbin.taixu.runtime.environment.ExecutionArtifacts

class LocalExecutionArtifacts(private val files: WorkspaceFileAccess) : ExecutionArtifacts {
    override val storageKey = "local-proot:${files.workspaceLockKey()}"
    override val maxArtifactBytes = WorkspaceFileAccess.MAX_HARNESS_ARTIFACT_BYTES
    override suspend fun list(): AppResult<List<ExecutionArtifact>> {
        // Resolving also checks confinement, including symlinks, before checking existence.
        return try {
            val directory = files.resolveDownloadDestination("${ExecutionArtifacts.DIRECTORY}/.probe").parentFile!!
            if (!directory.exists()) AppResult.Success(emptyList())
            else files.list(ExecutionArtifacts.DIRECTORY).map { entries ->
                require(entries.size < WorkspaceFileAccess.MAX_LIST_ENTRIES) { "产物目录枚举不完整，停止新增输出" }
                entries.filter { !it.isDirectory }.map { ExecutionArtifact(it.name, it.sizeBytes) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
    }
    override suspend fun write(name: String, content: String) = files.writeHarnessArtifact(path(name), content)
    override suspend fun delete(name: String) = files.delete(path(name))
    override suspend fun excludeFromSourceControl() {
        val exclude = ".git/info/exclude"
        val current = files.previewOrNull(exclude).orEmpty()
        val marker = "/${ExecutionArtifacts.DIRECTORY}/"
        if (current.lineSequence().any { it.trim() == marker }) return
        files.writeHarnessArtifact(exclude, current.trimEnd().let { if (it.isEmpty()) "" else "$it\n" } + "$marker\n")
    }
    private fun path(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' })
        return "${ExecutionArtifacts.DIRECTORY}/$name"
    }
}
