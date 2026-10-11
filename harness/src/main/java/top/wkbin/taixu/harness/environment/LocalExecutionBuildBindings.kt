package top.wkbin.taixu.harness.environment

import top.wkbin.taixu.core.database.BuildScriptRepository
import top.wkbin.taixu.runtime.environment.ExecutionBuildBindings

/** Preserves the local workshop's existing project-name binding keys. */
class LocalExecutionBuildBindings(private val repository: BuildScriptRepository) : ExecutionBuildBindings {
    override suspend fun bind(project: String, scriptId: String) = repository.bind(project, scriptId)
    override suspend fun unbind(project: String) = repository.unbind(project)
}
