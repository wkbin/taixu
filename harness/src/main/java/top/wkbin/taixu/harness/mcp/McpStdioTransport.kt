package top.wkbin.taixu.harness.mcp

import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import top.wkbin.taixu.core.model.McpServerConfig
import top.wkbin.taixu.core.model.McpToolInfo
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonElement

/** STDIO 传输层故障（子进程死亡 / EOF / IO 错误）：连接不可复用，需要销毁重建 */
internal class McpStdioChannelException(message: String) : IOException(message)

/** server 正常返回的 JSON-RPC error 响应（unknown tool / invalid params 等）：连接本身健康，错误应作为结果传回调用方 */
internal class McpJsonRpcErrorException(val code: Int, message: String) :
    IllegalStateException("MCP JSON-RPC $code: $message")

/** Reusable JSON-RPC sessions; subprocess ownership belongs to McpStdioChannelFactory. */
class McpStdioTransport(
    private val json: Json,
    private val commandBuilder: McpCommandBuilder,
    private val channelFactory: McpStdioChannelFactory,
) : McpTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, Connection>()
    private val startupMutexes = ConcurrentHashMap<String, Mutex>()
    private val downUntil = ConcurrentHashMap<String, Long>()

    init {
        scope.launch {
            while (isActive) {
                delay(SWEEP_INTERVAL_MS.milliseconds)
                runCatching { sweepIdleOnce(System.currentTimeMillis()) }
            }
        }
    }

    override suspend fun check(server: McpServerConfig): Boolean {
        val conn = try {
            connection(server, bypassCooldown = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            // 连接建立失败：map 中无连接可丢弃，失败冷却已在 connectionLocked 内写入
            return false
        }
        return try {
            conn.withInitialized { true }
        } catch (cancellation: CancellationException) {
            // B2: 取消（如 checkConnection 8s 超时、等待 initialize 握手锁被取消）不代表进程损坏，
            // 不销毁连接——握手锁只挡毫秒级握手，绝不会误杀在飞的最长 600s 的 tools/call
            throw cancellation
        } catch (t: Throwable) {
            // B2: 仅传输层故障（进程退出/EOF/IO）才销毁；server 返回错误响应等本次探测失败保留连接
            if (isChannelFailure(t)) discardConnection(conn.serverId, conn)
            false
        }
    }

    override suspend fun discover(server: McpServerConfig): List<McpToolInfo> {
        val conn = connection(server)
        return try {
            conn.withInitialized {
                val response = request("tools/list", JsonObject(emptyMap()))
                val result = response.result?.let {
                    json.decodeFromJsonElement(McpToolsListResponse.serializer(), it)
                } ?: error("MCP tools/list did not return a result")
                result.tools.map { dto -> dto.toInfo(server, json.encodeToString(JsonObject.serializer(), dto.inputSchema)) }
            }
        } catch (t: Throwable) {
            // B2: 取消（含发现总超时）与业务错误响应不销毁连接，仅传输层故障销毁
            if (t !is CancellationException && isChannelFailure(t)) discardConnection(conn.serverId, conn)
            throw t
        }
    }

    override suspend fun execute(server: McpServerConfig, toolName: String, arguments: JsonObject): Pair<Boolean, String> {
        val conn = connection(server)
        return try {
            conn.withInitialized {
                val params = json.encodeToJsonElement(McpCallToolParams.serializer(), McpCallToolParams(toolName, arguments))
                val response = request("tools/call", params)
                val result = response.result?.let {
                    json.decodeFromJsonElement(McpCallToolResult.serializer(), it)
                } ?: error("MCP tools/call did not return a result")
                !result.isError to result.content.joinToString("\n") { it.text.orEmpty() }
                    .ifBlank { if (result.isError) "执行失败" else "执行成功" }
            }
        } catch (e: McpJsonRpcErrorException) {
            // B5: server 返回的 JSON-RPC 错误响应是正常协议行为，连接健康；
            // 错误作为结果返回给模型，而不是销毁子进程重启（丢失有状态上下文）
            false to (e.message ?: "MCP JSON-RPC 错误")
        } catch (cancellation: CancellationException) {
            // B2: 调用被取消不代表进程损坏，保留连接
            throw cancellation
        } catch (t: Throwable) {
            // B5: 仅传输层失败（EOF/IO/进程死亡）才 discard，其余（序列化/业务异常等）透传且保留连接
            if (isChannelFailure(t)) discardConnection(conn.serverId, conn)
            throw t
        }
    }

    /** 是否为传输层故障（子进程死亡 / EOF / IO 错误），只有此类失败才值得销毁重建连接 */
    private fun isChannelFailure(t: Throwable): Boolean =
        t is McpStdioChannelException || t is IOException

    suspend fun closeConnection(serverId: String) = startupMutexes.getOrPut(serverId) { Mutex() }.withLock {
        val ids = (connections.keys + downUntil.keys + serverId).filter { it == serverId || it.startsWith("$serverId@environment:") }
        closeMcpResources(ids) { id -> downUntil.remove(id); discardConnection(id) }
    }

    private suspend fun discardConnection(serverId: String, failed: Connection? = null) {
        withContext(NonCancellable + Dispatchers.IO) {
            // 释放成功后按引用删除；失败保留重试入口，迟到的旧连接清理不能误删替代连接。
            val target = failed ?: connections[serverId] ?: return@withContext
            target.close()
            connections.remove(serverId, target)
        }
    }

    internal suspend fun sweepIdleOnce(nowMs: Long): Int {
        var closed = 0
        val entries = connections.entries.toList()
        for ((id, connection) in entries) {
            if (connection.inFlight) continue
            if (nowMs - connection.lastActivityMs < IDLE_TIMEOUT_MS) continue
            // B8: 与 connection() 的获取路径互斥（startupMutex）后二次确认 inFlight 与活跃时间，
            // 消除"新请求刚从 map 拿到连接、尚未锁 Connection.mutex"被清扫误关的 TOCTOU：
            // 新请求要么先在锁内 markActive（此处复查到新活跃时间即跳过），要么等锁释放后拿到新连接
            val removed = startupMutexes.getOrPut(id.mcpServerKey()) { Mutex() }.withLock {
                !connection.inFlight &&
                    nowMs - connection.lastActivityMs >= IDLE_TIMEOUT_MS &&
                    connections[id] === connection && run { discardConnection(id, connection); true }
            }
            if (removed) {
                closed++
            }
        }
        return closed
    }

    private suspend fun connection(server: McpServerConfig, bypassCooldown: Boolean = false): Connection {
        val scoped = server.forExecutionEnvironment()
        return startupMutexes.getOrPut(server.id) { Mutex() }.withLock {
            connectionLocked(scoped, bypassCooldown)
        }
    }

    private suspend fun connectionLocked(server: McpServerConfig, bypassCooldown: Boolean): Connection {
        connections[server.id]?.takeIf { it.dead == null && it.channel.isAlive && it.fingerprint == commandBuilder.fingerprint(server) }?.let {
            it.markActive()
            return it
        }
        val now = System.currentTimeMillis()
        if (!bypassCooldown) {
            val until = downUntil[server.id]
            if (until != null && now < until) {
                throw IllegalStateException(
                    "MCP[" + server.name + "] 沙箱会话拉起冷却中（剩余 " + (until - now) / 1000 + "s），跳过本次连接",
                )
            }
        }
        discardConnection(server.id)
        val channel = try {
            withTimeoutOrNull(STARTUP_TIMEOUT_MS.milliseconds) {
                channelFactory.open(server)
            } ?: run {
                downUntil[server.id] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
                error("MCP 沙箱会话启动超时（" + STARTUP_TIMEOUT_MS / 1000 + "s）：" + server.command)
            }
        } catch (t: Throwable) {
            downUntil[server.id] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
            throw t
        }
        downUntil.remove(server.id)
        return Connection(server.id, channel, commandBuilder.fingerprint(server)).also { connection ->
            connections[server.id] = connection
            startReaderLoop(connection)
            try { connection.ownership = currentCoroutineContext()[ExecutionEnvironmentContext]?.own { discardConnection(server.id, connection) } }
            catch (t: Throwable) { discardConnection(server.id, connection); throw t }
        }
    }

    /** Requests multiplex by ID; writes and initialization serialize without locking long calls. */
    private inner class Connection(
        val serverId: String,
        val channel: McpStdioChannel,
        val fingerprint: String,
    ) {
        var ownership: AutoCloseable? = null
        private val cleanup = McpResourceCleanup()
        private val writeMutex = Mutex()
        private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>()
        /** initialize 握手串行化：只挡握手本身，绝不跨 tools/call 持有。 */
        private val initMutex = Mutex()
        private var initialized = false

        /**
         * 连续不可路由帧（垃圾/回显/无主响应）计数：成功路由任何响应即清零——毒化只判定
         * "通道持续输出不可解析"。旧实现每请求重置；多路复用后由读泵统一计数，若改为连接
         * 生命周期累计，PTY 回显与 server 进度通知随正常流量穿插也会在长会话中累积到阈值，
         * 毒杀健康连接。仅读泵线程访问。
         */
        private var consecutiveIgnoredFrames = 0

        /** 读泵 Job：连接创建时启动，close 时取消。 */
        @Volatile
        internal var readerJob: Job? = null

        /** 通道判定死亡（EOF/毒帧/关闭）后置位：新请求立即失败，不再等满超时。 */
        @Volatile
        internal var dead: String? = null

        @Volatile
        internal var lastActivityMs: Long = System.currentTimeMillis()

        /** 供空闲清扫判断"有在途请求"（旧实现等价于 mutex.isLocked）。 */
        val inFlight: Boolean get() = pending.isNotEmpty()

        fun markActive() {
            lastActivityMs = System.currentTimeMillis()
        }

        suspend fun <T> withInitialized(block: suspend Connection.() -> T): T {
            markActive()
            initMutex.withLock {
                if (!initialized) {
                    markActive()
                    val params = json.encodeToJsonElement(McpInitializeParams.serializer(), McpInitializeParams())
                    val response = requestInternal("initialize", params, REQUEST_TIMEOUT_MS)
                    val result = response.result?.let {
                        json.decodeFromJsonElement(McpInitializeResult.serializer(), it)
                    } ?: error("MCP initialize did not return a result")
                    require(result.protocolVersion.isNotBlank())
                    notify("notifications/initialized")
                    initialized = true
                }
            }
            return block()
        }

        suspend fun request(method: String, params: JsonElement): JsonRpcResponse =
            requestInternal(method, params, CALL_REQUEST_TIMEOUT_MS)

        private suspend fun requestInternal(
            method: String,
            params: JsonElement,
            timeoutMs: Long,
        ): JsonRpcResponse {
            dead?.let { throw McpStdioChannelException("MCP 通道已失效：" + it) }
            markActive()
            val id = UUID.randomUUID().toString()
            val waiter = CompletableDeferred<JsonRpcResponse>()
            pending[id] = waiter
            try {
                writeLine(json.encodeToString(JsonRpcRequest.serializer(), JsonRpcRequest(id = id, method = method, params = params)))
            } catch (cancellation: CancellationException) {
                // 写锁上被取消 ≠ 通道故障：必须原样重抛，否则用户取消会误判为传输失败销毁连接；
                // 等待者同步移除，否则 inFlight 永真、空闲清扫永不回收此连接
                pending.remove(id)
                throw cancellation
            } catch (t: Throwable) {
                pending.remove(id)
                throw McpStdioChannelException("MCP 请求 " + method + " 写入失败：" + (t.message ?: t::class.simpleName))
            }
            // 超时必须以普通异常而非 TimeoutCancellationException 暴露，且销毁连接：
            // tools/call 可能已产生副作用，不能重放；下次调用必须从干净连接重建。
            val response = try {
                withTimeoutOrNull((requestTimeoutOverrideMs ?: timeoutMs).milliseconds) { waiter.await() }
            } finally {
                pending.remove(id)
            } ?: run {
                // A timed-out request may have reached the server and produced a side effect.
                // Drop the session rather than replaying it on the next call; the next request
                // will establish a fresh initialized connection.
                failPending(McpStdioChannelException("MCP 请求 $method 响应超时"))
                scope.launch { discardConnection(serverId, this@Connection) }
                throw McpStdioChannelException("MCP 请求 $method 响应超时（${timeoutMs / 1000}s）")
            }
            // B5: server 的 JSON-RPC error 响应用专用异常承载，调用方将其作为结果返回而非传输故障
            response.error?.let { throw McpJsonRpcErrorException(it.code, it.message) }
            return response
        }

        /** 读泵回调：把一行原始输出路由给等待者或按中毒阈值计数。 */
        fun route(rawLine: String) {
            val element = runCatching { json.parseToJsonElement(rawLine) }.getOrNull()
            if (element == null) {
                countIgnored("MCP 输出了过多无效 JSON 行")
                return
            }
            if (element is JsonObject && element.containsKey("method")) {
                // Valid server notifications have no id and are allowed by JSON-RPC; they must
                // not poison a healthy stream. A request echo with an id remains suspicious.
                if (!element.containsKey("id")) return
                countIgnored("MCP 输出了过多请求回显")
                return
            }
            val parsed = runCatching { json.decodeFromJsonElement(JsonRpcResponse.serializer(), element) }.getOrNull()
                ?: run {
                    countIgnored("MCP 输出了过多无效响应帧")
                    return
                }
            val waiter = parsed.id?.let { pending.remove(it) }
            if (waiter != null) {
                consecutiveIgnoredFrames = 0
                waiter.complete(parsed)
                return
            }
            if (parsed.id == null && parsed.error != null && pending.isNotEmpty()) {
                // 熔断帧或无 ID 错误响应：优先路由给在途请求，避免请求白白等待最长 600s 超时
                consecutiveIgnoredFrames = 0
                if (pending.size == 1) {
                    val entry = pending.entries.firstOrNull() ?: return
                    if (pending.remove(entry.key, entry.value)) {
                        entry.value.complete(parsed.copy(id = entry.key))
                    }
                } else {
                    // 多请求在途且无法确认归属时，为避免挂起，令全部在途请求快速失败（按快照条件原子移除）
                    val entries = pending.entries.toList()
                    entries.forEach { (id, waiter) ->
                        if (pending.remove(id, waiter)) {
                            waiter.complete(parsed.copy(id = id))
                        }
                    }
                }
                return
            }
            // 无等待者的响应 = 超时后的迟到响应或 id 错乱的 server：计入连续不可路由帧——
            // 迟到响应每次超时至多一条且随后必有成功路由清零；持续错乱说明 server 坏了
            countIgnored("MCP 输出了过多无主响应帧")
        }

        private fun countIgnored(reason: String) {
            // 输出持续不可解析说明通道已"中毒"，后续请求同样无法工作，判定为传输层故障
            if (++consecutiveIgnoredFrames > MAX_IGNORED_FRAMES) {
                failPending(McpStdioChannelException(reason))
            }
        }

        /** 通道死亡：所有等待者以传输层故障失败（调用方 discard 重建）；之后新请求快速失败。 */
        fun failPending(cause: Throwable) {
            if (dead == null) dead = cause.message ?: cause::class.simpleName
            pending.values.forEach { it.completeExceptionally(cause) }
            pending.clear()
        }

        private suspend fun notify(method: String) =
            writeLine(json.encodeToString(JsonRpcNotification.serializer(), JsonRpcNotification(method = method)))

        private suspend fun writeLine(payload: String) = writeMutex.withLock { channel.writeLine(payload) }

        suspend fun close() = cleanup.close {
            if (dead == null) dead = "connection closed"
            readerJob?.cancelAndJoin()
            failPending(McpStdioChannelException("MCP 通道已关闭"))
            channel.close()
            ownership?.close()
        }
    }

    /** 常驻读泵：逐行读取 server 输出并按 id 路由；通道终止时让所有等待者以传输层故障失败。 */
    private fun startReaderLoop(connection: Connection) {
        connection.readerJob = scope.launch {
            try {
                while (true) {
                    connection.route(connection.channel.incoming.receive())
                }
            } catch (cancellation: CancellationException) {
                throw cancellation // close() 主动取消，等待者已由 close 收尾
            } catch (t: Throwable) {
                // B5/B2: EOF/进程退出/IO 是传输层故障，只有此类失败才值得销毁重建
                connection.failPending(McpStdioChannelException("MCP 通道读取终止：" + (t.message ?: t::class.simpleName)))
            }
        }
    }


    internal fun injectConnectionForTest(server: McpServerConfig, channel: McpStdioChannel) {
        Connection(server.id, channel, commandBuilder.fingerprint(server)).also { connection ->
            connections[server.id] = connection
            startReaderLoop(connection)
        }
    }

    /** 测试钩子：覆盖请求超时（ms），使超时语义（普通异常 vs 伪取消）可在毫秒级验证。 */
    @Volatile
    internal var requestTimeoutOverrideMs: Long? = null

    companion object {
        internal const val STARTUP_TIMEOUT_MS = 3500L
        private const val FAILURE_COOLDOWN_MS = 3L * 60L * 1000L
        private const val REQUEST_TIMEOUT_MS = 120_000L
        private const val CALL_REQUEST_TIMEOUT_MS = 600_000L
        internal const val MAX_BUFFERED_LINES = 64
        internal const val MAX_FRAME_CHARS = 1 * 1024 * 1024
        internal const val MAX_IGNORED_FRAMES = 256
        internal const val IDLE_TIMEOUT_MS = 10L * 60L * 1000L
        internal const val SWEEP_INTERVAL_MS = 60L * 1000L
    }
}
