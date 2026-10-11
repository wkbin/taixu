package top.wkbin.taixu.harness

import kotlinx.serialization.json.JsonObject
import top.wkbin.taixu.harness.directory.CapabilityToolGateway

/**
 * 单次工具派发所需的上下文：由管道层 [ToolExecutor] 在 Schema 归一化后组装，
 * 打包各能力后端拼装请求所需的运行时信息与重入回调。
 */
internal class ToolInvocationContext(
    val tool: HarnessTool,
    val args: JsonObject,
    val rawToolName: String?,
    val parentToolCallId: String,
    val sessionId: String,
    val workspace: String,
    val progressReporter: (suspend (String) -> Unit)?,
    val operationId: String?,
    val metadata: MutableMap<String, String>,
    /** 能力网关内层调用重入入口：回到 [ToolExecutor] 的完整管道（保留审批/PLAN/检查点语义）。 */
    val reenter: suspend (ToolCall) -> ToolResult,
)

/** 单条工具路由：读取上下文，返回 `(success, output)`。 */
internal typealias ToolRouteFn = suspend (ToolInvocationContext) -> Pair<Boolean, String>

/**
 * 工具路由表：把 [HarnessTool] 映射到具体领域能力后端的调用。
 *
 * 「统一切面管道 + 领域能力插槽」中的插槽层：新增工具只需在 [routes] 登记一条路由，
 * 无需改动管道层的分支逻辑（开闭原则）。横切关注点（Schema 归一化、审批、脱敏、
 * 截断/落盘）仍由管道层 [ToolExecutor] 独占，路由只做纯粹的请求拼装与后端委派。
 */
internal class ToolRouting(
    private val workspaceTools: WorkspaceToolBackend,
    private val linuxCommandToolBackend: LinuxCommandToolBackend,
    private val hostToolBackend: HostCapabilityToolBackend,
    private val downloadToolBackend: DownloadToolBackend,
    private val harnessServiceToolBackend: HarnessServiceToolBackend,
    private val contextMemoryToolBackend: ContextMemoryToolBackend,
    private val promptAssetToolBackend: PromptAssetToolBackend,
    private val capabilityToolGateway: CapabilityToolGateway,
) {
    private val routes: Map<HarnessTool, ToolRouteFn> = buildRoutes()

    init {
        // 穷尽性兜底：新增工具枚举若漏登记路由，在这里即刻失败（而非运行时 NoSuchElement）。
        val missing = HarnessTool.entries.filterNot(routes::containsKey)
        require(missing.isEmpty()) { "缺少工具路由：$missing" }
    }

    suspend fun dispatch(ctx: ToolInvocationContext): Pair<Boolean, String> {
        return routes.getValue(ctx.tool).invoke(ctx)
    }

    private fun buildRoutes(): Map<HarnessTool, ToolRouteFn> {
        val workspace: ToolRouteFn = { ctx ->
            val outcome = workspaceTools.execute(WorkspaceToolRequest(ctx.tool, ctx.args, ctx.sessionId, ctx.workspace))
            ctx.metadata.putAll(outcome.metadata)
            outcome.success to outcome.output
        }
        val service: ToolRouteFn = { ctx ->
            harnessServiceToolBackend.execute(
                HarnessServiceRequest(ctx.tool, ctx.args, ctx.rawToolName, ctx.sessionId, ctx.workspace),
            )
        }
        val contextMemory: ToolRouteFn = { ctx ->
            contextMemoryToolBackend.execute(ContextMemoryRequest(ctx.tool, ctx.args, ctx.sessionId))
        }
        return mapOf(
            HarnessTool.READ to workspace,
            HarnessTool.WRITE to workspace,
            HarnessTool.EDIT to workspace,
            HarnessTool.BASE to { ctx ->
                linuxCommandToolBackend.execute(LinuxCommandRequest(HarnessTool.BASE, ctx.args, ctx.workspace, ctx.sessionId))
            },
            HarnessTool.PROCESS to { ctx ->
                linuxCommandToolBackend.execute(LinuxCommandRequest(HarnessTool.PROCESS, ctx.args, ctx.workspace, ctx.sessionId))
            },
            HarnessTool.HOST to { ctx ->
                hostToolBackend.execute(HostToolRequest(ctx.args, ctx.operationId, ctx.sessionId, ctx.metadata))
            },
            // 下载前的变更快照由 DownloadToolBackend 内部完成（与 WorkspaceToolBackend 同约定）。
            HarnessTool.DOWNLOAD to { ctx ->
                downloadToolBackend.execute(DownloadToolRequest(ctx.args, ctx.sessionId, ctx.workspace, ctx.progressReporter))
            },
            // 内建服务转发（记忆/计划/草稿、子智能体、构建脚本、渲染面）统一委托 HarnessServiceToolBackend。
            HarnessTool.MEMORY to service,
            HarnessTool.PLAN to service,
            HarnessTool.SCRATCHPAD to service,
            HarnessTool.SUBAGENT to service,
            HarnessTool.BUILD_SCRIPT to service,
            HarnessTool.RENDER_SURFACE to service,
            HarnessTool.HISTORY_SEARCH to contextMemory,
            HarnessTool.HISTORY_READ to contextMemory,
            HarnessTool.COMPRESS to contextMemory,
            // ask_user 在 execute() 入口特判（不走审批门控）；此处仅为路由穷尽兜底。
            HarnessTool.ASK_USER to { false to "ask_user 应在执行入口处理，不应到达工具分派" },
            // use_capability 与 legacy mcp__ 直连统一走能力网关；script 内层调用按原语义重入本执行器。
            HarnessTool.MCP to { ctx ->
                capabilityToolGateway.execute(
                    ctx.args, ctx.rawToolName, ctx.workspace, ctx.parentToolCallId,
                    ctx.operationId, ctx.sessionId, ctx.metadata,
                ) { inner -> ctx.reenter(inner) }
            },
            HarnessTool.LOAD_SKILL to { ctx -> promptAssetToolBackend.execute(PromptAssetRequest(ctx.tool, ctx.args)) },
            HarnessTool.LOAD_RULE to { ctx -> promptAssetToolBackend.execute(PromptAssetRequest(ctx.tool, ctx.args)) },
        )
    }
}
