package top.wkbin.taixu.harness.mcp

import kotlinx.coroutines.currentCoroutineContext
import top.wkbin.taixu.core.model.McpServerConfig
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentContext
import java.security.MessageDigest

internal fun String.mcpServerKey(): String = substringBefore("@environment:")

/** A connection must never be reused across execution owners or distributions. */
internal suspend fun McpServerConfig.forExecutionEnvironment(): McpServerConfig {
    val binding = currentCoroutineContext()[ExecutionEnvironmentContext] ?: return this
    val hash = MessageDigest.getInstance("SHA-256").digest(binding.environment.id.toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
    return copy(id = "$id@environment:$hash")
}
