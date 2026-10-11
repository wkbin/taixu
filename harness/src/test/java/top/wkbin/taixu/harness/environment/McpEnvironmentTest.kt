package top.wkbin.taixu.harness.environment

import java.lang.reflect.Proxy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.yield
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.core.model.McpServerConfig
import top.wkbin.taixu.core.model.McpTransportType
import top.wkbin.taixu.harness.mcp.*
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.environment.*

class McpEnvironmentTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val server = McpServerConfig("server", "server", transportType = McpTransportType.STDIO, command = "mcp")
    private fun registry() = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
        FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner))
    })
    private fun unusedRuntime() = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader,
        arrayOf(LinuxRuntime::class.java)) { _, method, _ -> error("local fallback: ${method.name}") } as LinuxRuntime
    private fun factory(open: suspend () -> McpStdioChannel) = object : McpStdioChannelFactory {
        override suspend fun open(server: McpServerConfig) = open()
    }

    @Test fun `production MCP channel uses bound remote subprocess and releases without closing shared world`() = runBlocking {
        val registry = registry()
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        val factory = LinuxMcpStdioChannelFactory(unusedRuntime(), McpCommandBuilder())
        registry.activity("one", "/workspace/p") {
            val channel = factory.open(server)
            assertEquals(listOf("stdio:/root"), world.events)
            channel.close()
        }
        assertEquals(0, world.closes)
        registry.closeSession("one")
        assertEquals(1, world.closes)
    }

    @Test fun `same server has independent reusable connections per execution owner`() = runBlocking {
        val registry = registry()
        val channels = mutableListOf<ReplyChannel>()
        val owners = mutableListOf<String>()
        val transport = McpStdioTransport(json, McpCommandBuilder(), factory {
            owners += currentCoroutineContext()[ExecutionEnvironmentContext]!!.environment.id.owner
            ReplyChannel().also { channels += it }
        })
        repeat(2) { registry.activity("one", "/workspace/p") { assertTrue(transport.check(server)) } }
        registry.activity("two", "/workspace/p") { assertTrue(transport.check(server)) }
        assertEquals(listOf("one", "two"), owners)
        registry.closeSession("one")
        assertFalse(channels[0].isAlive)
        assertTrue(channels[1].isAlive)
        registry.activity("two", "/workspace/p") { assertTrue(transport.check(server)) }
        assertEquals(2, channels.size)
        registry.closeSession("two")
        assertFalse(channels[1].isAlive)
    }

    @Test fun `server invalidation closes every execution owner connection`() = runBlocking {
        val registry = registry()
        val channels = mutableListOf<ReplyChannel>()
        val transport = McpStdioTransport(json, McpCommandBuilder(), factory {
            ReplyChannel().also { channels += it }
        })
        registry.activity("one", "/workspace/p") { assertTrue(transport.check(server)) }
        registry.activity("two", "/workspace/p") { assertTrue(transport.check(server)) }
        transport.closeConnection(server.id)
        assertTrue(channels.none { it.isAlive })
        registry.closeSession("one"); registry.closeSession("two")
    }

    private inner class ReplyChannel : McpStdioChannel {
        var failCloseOnce = false
        var closeAttempts = 0
        override val incoming = Channel<String>(Channel.UNLIMITED)
        override var isAlive = true
        override suspend fun writeLine(line: String) {
            val request = json.parseToJsonElement(line).jsonObject
            val id = request["id"] ?: return
            incoming.send("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-03-26"}}""")
        }
        override suspend fun close() {
            closeAttempts++
            yield()
            if (failCloseOnce) { failCloseOnce = false; error("channel close failed") }
            isAlive = false; incoming.close()
        }
    }

    @Test fun `failed connection close remains owned and session disposal retries cleanup`() = runBlocking {
        val registry = registry()
        val channel = ReplyChannel()
        val transport = McpStdioTransport(json, McpCommandBuilder(), factory { channel })
        registry.activity("one", "/workspace/p") { assertTrue(transport.check(server)) }
        channel.failCloseOnce = true
        try { transport.closeConnection(server.id); fail("cleanup failure was swallowed") }
        catch (_: IllegalStateException) { }
        assertTrue(channel.isAlive)
        registry.closeSession("one")
        assertFalse(channel.isAlive)
    }

    @Test fun `invalidation attempts all owners and reports every failed close`() = runBlocking {
        val registry = registry()
        val channels = mutableListOf<ReplyChannel>()
        val transport = McpStdioTransport(json, McpCommandBuilder(), factory {
            ReplyChannel().also { channels += it }
        })
        for (owner in listOf("one", "two", "three")) {
            registry.activity(owner, "/workspace/p") { assertTrue(transport.check(server)) }
        }
        channels[0].failCloseOnce = true
        channels[1].failCloseOnce = true
        try { transport.closeConnection(server.id); fail("failure swallowed") }
        catch (t: IllegalStateException) {
            assertTrue(generateSequence(t as Throwable) { it.cause }.any { it.suppressed.size == 1 })
        }
        assertEquals(listOf(1, 1, 1), channels.map { it.closeAttempts })
        assertFalse(channels[2].isAlive)
        transport.closeConnection(server.id)
        assertTrue(channels.none { it.isAlive })
        for (owner in listOf("one", "two", "three")) registry.closeSession(owner)
        assertEquals(listOf(2, 2, 1), channels.map { it.closeAttempts })
    }

    @Test fun `standalone invalidation retains failed close for retry without a session owner`() = runBlocking {
        val channel = ReplyChannel()
        val transport = McpStdioTransport(json, McpCommandBuilder(), factory { channel })
        assertTrue(transport.check(server))
        channel.failCloseOnce = true
        try { transport.closeConnection(server.id); fail("failure swallowed") }
        catch (_: IllegalStateException) { }
        transport.closeConnection(server.id)
        assertFalse(channel.isAlive)
        assertEquals(2, channel.closeAttempts)
    }

    @Test fun `reconnection closes failed old resource before starting a replacement`() = runBlocking {
        val channels = mutableListOf<ReplyChannel>()
        val transport = McpStdioTransport(json, McpCommandBuilder(), factory {
            ReplyChannel().also { channels += it }
        })
        assertTrue(transport.check(server))
        val old = channels.single()
        old.failCloseOnce = true
        try { transport.closeConnection(server.id); fail("failure swallowed") }
        catch (_: IllegalStateException) { }
        old.failCloseOnce = true
        assertFalse(transport.check(server))
        assertEquals(1, channels.size)
        assertTrue(transport.check(server))
        assertFalse(old.isAlive)
        assertEquals(3, old.closeAttempts)
        assertEquals(2, channels.size)
        transport.closeConnection(server.id)
    }

    @Test fun `concurrent server invalidation and session disposal close each channel once`() = runBlocking {
        val registry = registry()
        val channel = ReplyChannel()
        val transport = McpStdioTransport(json, McpCommandBuilder(), factory { channel })
        registry.activity("one", "/workspace/p") { assertTrue(transport.check(server)) }
        listOf(async { transport.closeConnection(server.id) }, async { registry.closeSession("one") }).awaitAll()
        assertEquals(1, channel.closeAttempts)
        assertFalse(channel.isAlive)
    }

    @Test fun `invalid MCP command fails before acquiring a standalone environment`() = runBlocking {
        var opens = 0
        val factory = LinuxMcpStdioChannelFactory(unusedRuntime(), McpCommandBuilder(),
            ExecutionEnvironmentFactory { owner, workspace, _ ->
                opens++; FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner))
            })
        try { factory.open(server.copy(command = "")); fail("invalid command accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, opens)
    }

    @Test fun `server invalidation waits for in progress connection acquisition before closing it`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finishOpen = CompletableDeferred<Unit>()
        val channel = ReplyChannel()
        val transport = McpStdioTransport(json, McpCommandBuilder(), factory {
            started.complete(Unit); finishOpen.await(); channel
        })
        val connecting = async { transport.check(server) }
        started.await()
        val closing = async { transport.closeConnection(server.id) }
        yield()
        assertFalse(closing.isCompleted)
        finishOpen.complete(Unit)
        connecting.await(); closing.await()
        assertFalse(channel.isAlive)
        assertEquals(1, channel.closeAttempts)
    }

    @Test fun `idle cleanup failure remains retryable without reconnecting the channel`() = runBlocking {
        val channel = ReplyChannel()
        val transport = McpStdioTransport(json, McpCommandBuilder(), factory { error("unexpected reconnect") })
        transport.injectConnectionForTest(server, channel)
        transport.rewindIdleForTest(McpStdioTransport.IDLE_TIMEOUT_MS + 60_000)
        channel.failCloseOnce = true
        try { transport.sweepIdleOnce(System.currentTimeMillis()); fail("failure swallowed") }
        catch (_: IllegalStateException) { }
        assertTrue(channel.isAlive)
        assertEquals(1, transport.sweepIdleOnce(System.currentTimeMillis()))
        assertFalse(channel.isAlive)
        assertEquals(2, channel.closeAttempts)
    }
}
