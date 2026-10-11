package top.wkbin.taixu.runtime.environment

/**
 * Select reusable script IDs for projects in this execution world.
 * A remote implementation must namespace bindings by host/workspace, independently of
 * the phone's project names. This manages selection only; it does not launch a build.
 */
interface ExecutionBuildBindings {
    suspend fun bind(project: String, scriptId: String)
    suspend fun unbind(project: String)
}
