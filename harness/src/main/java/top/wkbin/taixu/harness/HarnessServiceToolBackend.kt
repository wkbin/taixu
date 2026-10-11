package top.wkbin.taixu.harness

import kotlinx.serialization.json.JsonObject
import top.wkbin.taixu.harness.core.ToolBackend
import top.wkbin.taixu.harness.dual.DualAgentCoordinator

data class HarnessServiceRequest(
    val tool: HarnessTool,       // MEMORY / PLAN / SCRATCHPAD / SUBAGENT / BUILD_SCRIPT / RENDER_SURFACE
    val args: JsonObject,
    val rawToolName: String?,
    val sessionId: String,
    val workspace: String,
)

/**
 * Agent 工具后端：转发到 harness 内建服务的内置工具。
 *
 * 这些工具的执行已由各自独立组件承载，本后端只负责路由与未初始化降级文案：
 * - memory / plan / scratchpad → [AgentContextExecutor]（会话级上下文状态）
 * - invoke_subagent / invoke_dual_agent → [SubagentOrchestrator] / [DualAgentCoordinator]
 * - build_script → [BuildScriptToolExecutor]（taixu-build 脚本化构建）
 * - render_surface → [A2uiSurfaceBus]（A2UI 渲染面发布，无外部依赖）
 */
class HarnessServiceToolBackend(
    private val contextExecutor: AgentContextExecutor? = null,
    private val subagentOrchestrator: SubagentOrchestrator? = null,
    private val dualAgentCoordinator: DualAgentCoordinator? = null,
    private val buildScriptToolExecutor: BuildScriptToolExecutor? = null,
) : ToolBackend<HarnessServiceRequest, Pair<Boolean, String>> {

    override suspend fun execute(request: HarnessServiceRequest): Pair<Boolean, String> =
        when (request.tool) {
            HarnessTool.MEMORY -> contextExecutor?.executeMemory(request.args, request.sessionId, request.workspace)
                ?: (false to "未初始化记忆执行器")
            HarnessTool.PLAN -> contextExecutor?.executePlan(request.args, request.sessionId)
                ?: (false to "未初始化计划执行器")
            HarnessTool.SCRATCHPAD -> contextExecutor?.executeScratchpad(request.args, request.sessionId)
                ?: (false to "未初始化草稿执行器")
            HarnessTool.SUBAGENT -> if (request.rawToolName.equals("invoke_dual_agent", ignoreCase = true)) {
                dualAgentCoordinator?.executeFromTool(request.args, request.sessionId, request.workspace)
                    ?: (false to "未初始化双智能体编排器")
            } else {
                subagentOrchestrator?.executeSubagents(request.args, request.sessionId)
                    ?: (false to "未初始化子智能体编排器")
            }
            HarnessTool.BUILD_SCRIPT -> buildScriptToolExecutor?.execute(request.args, request.workspace, request.sessionId)
                ?: (false to "未初始化构建脚本管理器")
            HarnessTool.RENDER_SURFACE -> A2uiSurfaceBus.publishFromTool(request.args, request.sessionId)
            else -> throw IllegalArgumentException("Unsupported tool: ${request.tool}")
        }
}
