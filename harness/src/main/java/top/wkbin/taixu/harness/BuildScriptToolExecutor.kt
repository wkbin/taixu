package top.wkbin.taixu.harness

import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.taixu.core.database.BuildScriptEntity
import top.wkbin.taixu.core.database.BuildScriptRepository
import top.wkbin.taixu.harness.environment.LocalExecutionBuildBindings
import top.wkbin.taixu.harness.environment.SessionExecutionEnvironments
import kotlinx.coroutines.CancellationException

/** Controlled Harness API for reusable workshop scripts. */
class BuildScriptToolExecutor(
    private val repository: BuildScriptRepository,
    private val environments: SessionExecutionEnvironments? = null,
) {
    suspend fun execute(args: JsonObject, workspace: String, sessionId: String = ""): Pair<Boolean, String> {
        val action = args.string("action").trim().lowercase()
        return when (action) {
            "list" -> {
                val scripts = repository.listScripts()
                true to if (scripts.isEmpty()) "暂无构建脚本" else scripts.joinToString("\n") {
                    "${it.id}\t[${it.projectType}]\t${it.name}\t${it.description.ifBlank { "无描述" }}${if (it.isBuiltin) "\t[builtin]" else ""}"
                }
            }
            "get" -> {
                val id = args.string("id")
                if (id.isBlank()) return false to "缺少参数：id"
                val script = repository.findScript(id) ?: return false to "构建脚本不存在：$id"
                true to "id: ${script.id}\nname: ${script.name}\nproject_type: ${script.projectType}\ndescription: ${script.description}\n--- script ---\n${script.content}"
            }
            "create", "update" -> {
                val id = args.string("id")
                if (action == "update" && id.isBlank()) return false to "缺少参数：id"
                val old = if (action == "update") repository.findScript(id) ?: return false to "构建脚本不存在：$id" else null
                val name = args.string("name").ifBlank { old?.name.orEmpty() }
                val type = args.string("project_type").ifBlank { old?.projectType.orEmpty() }.trim().uppercase()
                val content = args.string("content").ifBlank { old?.content.orEmpty() }
                if (name.isBlank()) return false to "name 不能为空"
                if (type !in setOf("ANDROID", "FLUTTER")) return false to "project_type 只能是 android 或 flutter"
                if (content.isBlank() || content.length > 200_000) return false to "content 不能为空且不能超过 200 KB"
                if (!content.lineSequence().firstOrNull().orEmpty().contains("sh")) return false to "脚本首行必须声明 shell（例如 #!/bin/sh）"
                val now = System.currentTimeMillis()
                val saved = BuildScriptEntity(
                    id = old?.id ?: UUID.randomUUID().toString(),
                    name = name.trim(),
                    description = args.string("description").ifBlank { old?.description.orEmpty() }.trim(),
                    projectType = type,
                    content = content.removePrefix("\uFEFF").replace("\r\n", "\n"),
                    isBuiltin = old?.isBuiltin ?: false,
                    createdAt = old?.createdAt ?: now,
                    updatedAt = now,
                )
                repository.upsertScript(saved)
                true to "已${if (old == null) "创建" else "更新"}构建脚本：${saved.name} (${saved.id})"
            }
            "delete" -> {
                val id = args.string("id")
                if (id.isBlank()) return false to "缺少参数：id"
                if (repository.deleteScript(id)) true to "已删除构建脚本及其项目挂载" else false to "内置脚本不可删除，或脚本不存在"
            }
            "bind" -> {
                val project = resolveProject(args, workspace)
                if (project.isBlank()) return false to "project 不能为空，且当前会话没有工作区"
                val id = args.string("id")
                if (id.isBlank()) return false to "缺少参数：id"
                if (repository.findScript(id) == null) return false to "构建脚本不存在：$id"
                val bindings = bindingsFor(sessionId, workspace) ?: return false to "当前执行环境尚未提供项目构建脚本挂载适配器"
                runCatching {
                    bindings.bind(project, id)
                }.fold(
                    onSuccess = { true to "已将项目 $project 挂载到构建脚本 $id" },
                    onFailure = { if (it is CancellationException) throw it; false to (it.message ?: "挂载失败") },
                )
            }
            "unbind" -> {
                val project = resolveProject(args, workspace)
                if (project.isBlank()) return false to "project 不能为空，且当前会话没有工作区"
                val bindings = bindingsFor(sessionId, workspace) ?: return false to "当前执行环境尚未提供项目构建脚本挂载适配器"
                bindings.unbind(project)
                true to "项目 $project 已恢复标准构建流程"
            }
            else -> false to "未知 action：$action"
        }
    }

    private suspend fun bindingsFor(session: String, workspace: String) =
        if (environments != null) environments.environment(session, workspace).buildBindings
        else LocalExecutionBuildBindings(repository)

    private fun resolveProject(args: JsonObject, workspace: String): String =
        args.string("project").ifBlank { workspace.trim().trimEnd('/').substringAfterLast('/') }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
}
