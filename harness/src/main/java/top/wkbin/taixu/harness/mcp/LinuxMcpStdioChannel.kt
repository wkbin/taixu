package top.wkbin.taixu.harness.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import top.wkbin.taixu.core.model.McpServerConfig
import top.wkbin.taixu.harness.core.ResourceCleanupException
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.LinuxSession
import top.wkbin.taixu.runtime.shell.SessionConfig

/**
 * Production factory: starts a Linux PTY session, wraps it in a [McpStdioChannel], and pumps
 * its stdout into a buffered line channel. Bounded startup timeout prevents the caller from
 * hanging when PRoot is wedged.
 */
class LinuxMcpStdioChannelFactory(
    private val linuxRuntime: LinuxRuntime,
    private val commandBuilder: McpCommandBuilder,
    private val environments: top.wkbin.taixu.runtime.environment.ExecutionEnvironmentFactory? = null,
) : McpStdioChannelFactory {
    override suspend fun open(server: McpServerConfig): McpStdioChannel {
        val binding = currentCoroutineContext()[ExecutionEnvironmentContext]
        if (binding == null && environments == null && !awaitLinuxRuntimeReady(linuxRuntime.state)) {
            throw IllegalStateException("Linux runtime is not ready. Call initialize() first.")
        }
        val config = SessionConfig(
                workingDirectory = "/root",
                environment = server.env,
                commandLine = commandBuilder.commandLine(server),
                allowSttyResize = false,
            )
        val environment = binding?.environment ?: environments?.open("mcp:${server.id}", "", null)
        var opened: LinuxSession? = null
        try {
            val session = environment?.openSession(config) ?: linuxRuntime.startSession(config)
            opened = session
            val channel = LinuxMcpStdioChannel(server.id, session, environment = environment.takeIf { binding == null })
            try { channel.ownership = binding?.own { channel.close() } }
            catch (t: Throwable) {
                withContext(NonCancellable) { try { channel.close() } catch (cleanup: Throwable) { t.addSuppressed(cleanup) } }
                throw t
            }
            return channel
        } catch (t: Throwable) {
            withContext(NonCancellable) { try { opened?.close() } catch (cleanup: Throwable) { t.addSuppressed(cleanup) } }
            withContext(NonCancellable) { try { if (binding == null) environment?.close() } catch (cleanup: Throwable) { t.addSuppressed(cleanup) } }
            throw t
        }
    }
}

class LinuxMcpStdioChannel(
    private val serverId: String,
    private val session: LinuxSession,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxFrameChars: Int = McpStdioTransport.MAX_FRAME_CHARS,
    private val bufferCapacity: Int = McpStdioTransport.MAX_BUFFERED_LINES,
    private val environment: top.wkbin.taixu.runtime.environment.ExecutionEnvironment? = null,
) : McpStdioChannel {
    internal var ownership: AutoCloseable? = null
    private val cleanup = McpResourceCleanup()
    private val sessionCleanup = McpResourceCleanup()
    private val environmentCleanup = McpResourceCleanup()
    private val lines: Channel<String> = Channel(capacity = bufferCapacity)
    override val incoming: ReceiveChannel<String> = lines
    override val isAlive: Boolean get() = session.isAlive

    init {
        scope.launch {
            val buf = StringBuilder()
            var skippingOversized = false
            // 同一段连续超长输出只广播一条熔断错误帧：首帧已能触发在途请求失败，
            // 若每条超长行都发帧，会被协议层连续计数放大为通道级中毒断连
            var reportedOversizedFrame = false
            try {
                session.output.collect { output ->
                    val chunk = output.text
                    var start = 0
                    while (start < chunk.length) {
                        val newline = chunk.indexOf('\n', start)
                        val end = if (newline >= 0) newline else chunk.length
                        if (skippingOversized) {
                            if (newline >= 0) {
                                skippingOversized = false
                                start = newline + 1
                            } else {
                                break
                            }
                            continue
                        }
                        val partLength = end - start
                        if (buf.length + partLength > maxFrameChars) {
                            skippingOversized = true
                            if (!reportedOversizedFrame) {
                                val idJson = ID_REGEX.find(buf)?.groups?.get(1)?.value ?: "null"
                                reportedOversizedFrame = true
                                lines.send(
                                    """{"jsonrpc":"2.0","id":$idJson,"error":{"code":-32603,"message":"MCP STDIO 单行输出超过安全上限 (${maxFrameChars / 1024 / 1024}MB)，已触发移动端熔断保护"}}""",
                                )
                            }
                            buf.clear()
                            if (newline >= 0) {
                                skippingOversized = false
                                start = newline + 1
                            } else {
                                break
                            }
                            continue
                        }
                        buf.append(chunk, start, end)
                        if (newline < 0) break
                        val line = buf.toString().trim()
                        buf.clear()
                        reportedOversizedFrame = false
                        if (line.startsWith("{")) lines.send(line)
                        start = newline + 1
                    }
                }
                lines.close()
            } catch (cancellation: CancellationException) {
                lines.close(cancellation)
                throw cancellation
            } catch (t: Throwable) {
                lines.close(t)
                runCatching { sessionCleanup.close { session.close() } }
            }
        }
    }

    override suspend fun writeLine(line: String) = session.write((line + "\n").toByteArray(Charsets.UTF_8))

    override suspend fun close(): Unit = cleanup.close {
        // 只关 session 不够：泵协程可能正挂起在 lines.send 上（消费方超时离场、缓冲已满），
        // 此时 output flow 的完成永远观察不到，协程与 Channel 永久泄漏。取消 scope 让泵
        // 走 catch 分支收尾（lines.close(cause)）。
        scope.coroutineContext[Job]?.cancelAndJoin()
        val failures = mutableListOf<Throwable>()
        try { sessionCleanup.close { session.close() } } catch (t: Throwable) { failures += t }
        try { environmentCleanup.close { environment?.close() } } catch (t: Throwable) { failures += t }
        if (failures.isNotEmpty()) throw ResourceCleanupException(failures)
        ownership?.close()
    }

    private companion object {
        private val ID_REGEX = Regex(""""id"\s*:\s*("([^"]+)"|(\d+))""")
    }
}
