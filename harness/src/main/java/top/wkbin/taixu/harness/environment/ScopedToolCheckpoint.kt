package top.wkbin.taixu.harness.environment

import java.security.MessageDigest
import top.wkbin.taixu.harness.ToolExecutionRequest
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.core.ToolCheckpoint

/** The same extension ID can be installed in multiple sessions without registration collisions. */
internal class ScopedToolCheckpoint(owner: String,
    private val delegate: ToolCheckpoint<ToolExecutionRequest, ToolResult>,
) : ToolCheckpoint<ToolExecutionRequest, ToolResult> {
    init { require(delegate.id.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid tool checkpoint id" } }
    override val id = "scope-" + MessageDigest.getInstance("SHA-256")
        .digest((owner + "\u0000" + delegate.id).toByteArray()).joinToString("") { "%02x".format(it) }.take(24) +
        "." + delegate.id.take(32)
    override suspend fun before(request: ToolExecutionRequest) = delegate.before(request)
    override suspend fun after(request: ToolExecutionRequest, result: ToolResult) = delegate.after(request, result)
}
