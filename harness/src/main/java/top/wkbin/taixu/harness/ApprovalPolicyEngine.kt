package top.wkbin.taixu.harness

import top.wkbin.taixu.core.database.AgentApprovalRequestEntity
import top.wkbin.taixu.core.model.ApprovalMode
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ApprovalDecision(
    val required: Boolean,
    val riskLevel: String = "low",
    val reason: String = "",
    val summary: String = "",
)

/** Host-side policy. The model prompt is deliberately not part of this decision. */
class ApprovalPolicyEngine {
    fun decide(mode: ApprovalMode, tool: HarnessTool, args: JsonObject, workspace: String): ApprovalDecision {
        if (mode == ApprovalMode.FULL_ACCESS) return ApprovalDecision(false)
        if (tool == HarnessTool.READ || tool == HarnessTool.MEMORY || tool == HarnessTool.PLAN ||
            tool == HarnessTool.SCRATCHPAD || tool == HarnessTool.HISTORY_SEARCH || tool == HarnessTool.HISTORY_READ
        ) {
            return ApprovalDecision(false)
        }
        if (tool == HarnessTool.SUBAGENT) return ApprovalDecision(false)
        if (tool == HarnessTool.PROCESS && processAction(args) in setOf("status", "logs", "list")) {
            return ApprovalDecision(false)
        }

        val summary = summarize(tool, args)
        if (mode == ApprovalMode.REQUEST) {
            return ApprovalDecision(true, "normal", "当前权限模式要求所有会改变状态或产生外部副作用的工具操作先获得批准。", summary)
        }

        return when (tool) {
            HarnessTool.WRITE, HarnessTool.EDIT -> {
                val path = args["path"]?.jsonPrimitive?.content.orEmpty()
                if (workspace.isNotBlank() && isWithinWorkspace(path, workspace)) {
                    ApprovalDecision(false)
                } else {
                    ApprovalDecision(true, "high", "写入目标不在当前工作区内，可能影响工作区之外的文件。", summary)
                }
            }
            HarnessTool.BASE -> {
                val command = args["command"]?.jsonPrimitive?.content.orEmpty()
                if (isRoutineCommand(command)) ApprovalDecision(false)
                else ApprovalDecision(true, if (isDestructiveCommand(command)) "critical" else "high", reasonForCommand(command), summary)
            }
            HarnessTool.PROCESS -> when (processAction(args)) {
                "start" -> {
                    val command = args["command"]?.jsonPrimitive?.content.orEmpty()
                    if (isRoutineCommand(command)) ApprovalDecision(false)
                    else ApprovalDecision(true, if (isDestructiveCommand(command)) "critical" else "high", reasonForCommand(command), summary)
                }
                "stop" -> ApprovalDecision(true, "high", "停止操作会终止一个由 TaiXu 托管的后台进程。", summary)
                else -> ApprovalDecision(false)
            }
            HarnessTool.DOWNLOAD -> ApprovalDecision(true, "high", "下载会访问外部网络并写入工作区文件。", summary)
            HarnessTool.MCP -> ApprovalDecision(true, "high", "MCP 工具可能访问外部服务或产生工作区之外的副作用。", summary)
            HarnessTool.READ, HarnessTool.MEMORY, HarnessTool.PLAN, HarnessTool.SCRATCHPAD,
            HarnessTool.HISTORY_SEARCH, HarnessTool.HISTORY_READ, HarnessTool.SUBAGENT -> ApprovalDecision(false)
        }
    }

    fun createRequest(
        sessionId: String,
        toolCall: ToolCall,
        workspace: String,
        decision: ApprovalDecision,
        operationId: String? = null,
    ): AgentApprovalRequestEntity {
        val now = System.currentTimeMillis()
        return AgentApprovalRequestEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            toolCallId = toolCall.id,
            toolName = toolCall.rawToolName ?: toolCall.tool.name.lowercase(),
            argumentsJson = toolCall.args.toString(),
            workspace = workspace,
            riskLevel = decision.riskLevel,
            reason = decision.reason,
            summary = decision.summary,
            createdAt = now,
            operationId = operationId,
            argsHash = argsHash(toolCall.args.toString()),
            expiresAt = now + APPROVAL_TTL_MS,
        )
    }

    companion object {
        /** 审批有效期：超时未决的请求自动失效，恢复执行前也会复核。 */
        const val APPROVAL_TTL_MS: Long = 10 * 60 * 1000L

        /** argumentsJson 的 SHA-256 十六进制摘要；创建时写入，执行前复核。 */
        fun argsHash(argumentsJson: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(argumentsJson.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    private fun summarize(tool: HarnessTool, args: JsonObject): String = when (tool) {
        HarnessTool.WRITE, HarnessTool.EDIT -> "${tool.name.lowercase()} ${args["path"]?.jsonPrimitive?.content.orEmpty()}"
        HarnessTool.BASE -> args["command"]?.jsonPrimitive?.content.orEmpty().lineSequence().firstOrNull().orEmpty()
        HarnessTool.PROCESS -> "process ${processAction(args)} ${args["id"]?.jsonPrimitive?.content.orEmpty()}".trim()
        HarnessTool.DOWNLOAD -> "download ${args["destination"]?.jsonPrimitive?.content.orEmpty()}"
        HarnessTool.MCP -> "MCP ${args["name"]?.jsonPrimitive?.content ?: "工具调用"}"
        else -> tool.name.lowercase()
    }

    private fun processAction(args: JsonObject): String =
        args["action"]?.jsonPrimitive?.content.orEmpty().trim().lowercase()

    private fun isWithinWorkspace(path: String, workspace: String): Boolean {
        if (path.isBlank()) return false
        val normalizedPath = path.replace('\\', '/').trimEnd('/')
        val normalizedWorkspace = workspace.replace('\\', '/').trimEnd('/')
        if (normalizedPath.split('/').any { it == ".." }) return false
        if (!normalizedPath.startsWith("/")) return true
        return normalizedPath == normalizedWorkspace || normalizedPath.startsWith("$normalizedWorkspace/")
    }

    private fun isRoutineCommand(command: String): Boolean {
        val normalized = command.trim().lowercase()
        if (normalized.isBlank()) return false
        if (isDestructiveCommand(normalized)) return false
        // Auto-approval is intentionally limited to one transparent command.
        // Shell composition, substitution and redirection can hide a second mutation
        // behind an otherwise harmless prefix such as `git status`.
        if (listOf("\n", "\r", ";", "&&", "||", "|", ">", "<", "`", "$(").any { it in normalized }) return false
        if (Regex("\\bfind\\b.*\\s(-delete|-exec|-execdir)\\b").containsMatchIn(normalized)) return false
        if (Regex("\\b(curl|wget|nc|ssh|scp|adb|taixu-host|mount|umount|kill|pkill|chmod|chown|apt(-get)?|apk|dnf|pacman|npm\\s+(install|publish)|pip\\s+install|git\\s+(push|reset|clean))\\b").containsMatchIn(normalized)) return false
        return Regex("^(pwd|ls|find|rg|grep|head|tail|cat|git\\s+(status|diff|log|show)|gradle(w)?\\b.*(test|check|assemble)|npm\\s+(test|run\\s+(test|lint|build))|flutter\\s+(test|analyze|build)|pytest\\b|kotlinc\\b|./gradlew\\b.*(test|check|assemble))").containsMatchIn(normalized)
    }

    private fun isDestructiveCommand(command: String): Boolean = listOf(
        Regex("\\brm\\s+-(?:[^\\s]*r[^\\s]*f|[^\\s]*f[^\\s]*r)\\b", RegexOption.IGNORE_CASE),
        Regex("\\brm\\s+-r\\b", RegexOption.IGNORE_CASE),
        Regex("\\brm\\s+-rf?\\s+--no-preserve-root"),
        Regex("\\bmkfs\\.", RegexOption.IGNORE_CASE),
        Regex("\\bdd\\s+if=.*\\bof=/dev/", RegexOption.IGNORE_CASE),
        Regex("\\b(shutdown|reboot|halt|poweroff)\\b"),
        Regex("\\btruncate\\s+-s\\s+0\\b", RegexOption.IGNORE_CASE),
        Regex(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}"),
        Regex(">\\s*/dev/(sd|mmcblk|nvme)", RegexOption.IGNORE_CASE),
        Regex("\\bchmod\\s+-r\\s+777\\s+/", RegexOption.IGNORE_CASE),
    ).any { it.containsMatchIn(command) }

    private fun reasonForCommand(command: String): String = if (isDestructiveCommand(command)) {
        "命令匹配到删除、格式化、设备写入或关机等不可逆高危模式。"
    } else {
        "命令可能安装软件、访问外部网络、修改系统状态或影响工作区之外的资源。"
    }
}
