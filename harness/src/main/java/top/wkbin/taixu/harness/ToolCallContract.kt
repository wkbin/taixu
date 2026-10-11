package top.wkbin.taixu.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import top.wkbin.taixu.harness.mcp.McpToolApiName

/** Shared preflight rules for main and headless lane calls; mapping alone never authorizes execution. */
internal object ToolCallContract {
    val nativeNames: Set<String> = HarnessTool.entries
        .filter { it != HarnessTool.MCP }
        .map { HarnessApiMapper.apiName(it) }
        .toSet() + setOf("subagent", "invoke_dual_agent", "use_capability", "history.search", "history.read")

    // Direct MCP names remain a compatibility route. Availability is checked by the live gateway,
    // rather than by the provider's possibly stale schema snapshot. Bare "mcp" is never executable.
    fun isKnownName(name: String): Boolean =
        name.trim().lowercase() in nativeNames || name.trim().startsWith("mcp__")

    fun parseArguments(json: Json, raw: String): JsonObject =
        if (raw.isBlank()) buildJsonObject {} else {
            json.parseToJsonElement(raw) as? JsonObject
                ?: throw IllegalArgumentException("参数不是 JSON 对象")
        }

    fun unknownGuidance(called: String, model: ModelConfig): String {
        val nativeTools = nativeNames.sorted()
        val mcpTools = model.dynamicMcpTools
        val mcpList = if (mcpTools.isEmpty()) {
            "（当前没有已启用的 MCP 工具）"
        } else {
            mcpTools.joinToString("；") { tool ->
                "${McpToolApiName.encode(tool)}（${tool.serverName}·${tool.name}）"
            }
        }
        val target = called.lowercase()
        val nearest = (nativeTools + mcpTools.map { McpToolApiName.encode(it) })
            .mapNotNull { candidate ->
                val distance = levenshtein(target, candidate.lowercase())
                if (distance <= (target.length / 2).coerceAtLeast(3)) candidate to distance else null
            }
            .minByOrNull { it.second }
            ?.first
        return buildString {
            append("未知工具：$called。工具名不可编造或猜测，必须从下列清单中原样选取。")
            append("原生工具：${nativeTools.joinToString(" / ")}。")
            append("已启用 MCP 工具：$mcpList。")
            nearest?.let { append("最接近的候选是 $it，是否想调用它？") }
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(
                    prev[j] + 1,
                    current[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            prev = current
        }
        return prev[b.length]
    }
}
