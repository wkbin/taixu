package top.wkbin.taixu.harness.environment

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.harness.core.ResourceCleanupException
import top.wkbin.taixu.harness.mcp.LinuxMcpStdioChannel
import top.wkbin.taixu.runtime.environment.ExecutionEnvironment
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentId
import top.wkbin.taixu.runtime.shell.*

@OptIn(ExperimentalCoroutinesApi::class)
class McpChannelCleanupTest {
    private class Fixture(val scope: CoroutineScope, val outputFailure: Boolean = false) {
        var sessionCloses = 0
        var environmentCloses = 0
        var sessionFailures = 0
        var environmentFailures = 0
        var pumpDrained = false
        val session = object : LinuxSession by FakeSession(mutableListOf()) {
            override val output = flow<TerminalOutput> {
                try {
                    if (outputFailure) error("output failed")
                    awaitCancellation()
                } finally { pumpDrained = true }
            }
            override suspend fun close() {
                assertTrue(pumpDrained)
                sessionCloses++
                if (sessionFailures-- > 0) error("session close")
            }
        }
        val environment = object : ExecutionEnvironment by FakeWorld(
            ExecutionEnvironmentId("remote", "linux", "/workspace", "owner")) {
            override suspend fun close() {
                environmentCloses++
                if (environmentFailures-- > 0) error("environment close")
            }
        }
        val channel = LinuxMcpStdioChannel("server", session, scope, environment = environment)
    }

    @Test fun `environment close failure does not repeat a successful session close`() = runTest {
        val fixture = Fixture(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        runCurrent(); fixture.environmentFailures = 1
        try { fixture.channel.close(); fail("failure lost") } catch (_: ResourceCleanupException) { }
        fixture.channel.close(); fixture.channel.close()
        assertEquals(1, fixture.sessionCloses)
        assertEquals(2, fixture.environmentCloses)
    }

    @Test fun `session close failure still releases environment and only session is retried`() = runTest {
        val fixture = Fixture(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        runCurrent(); fixture.sessionFailures = 1
        try { fixture.channel.close(); fail("failure lost") } catch (_: ResourceCleanupException) { }
        fixture.channel.close()
        assertEquals(2, fixture.sessionCloses)
        assertEquals(1, fixture.environmentCloses)
    }

    @Test fun `all channel cleanup errors are retained and concurrent retries release once`() = runTest {
        val fixture = Fixture(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        runCurrent(); fixture.sessionFailures = 1; fixture.environmentFailures = 1
        try { fixture.channel.close(); fail("failure lost") }
        catch (t: ResourceCleanupException) { assertEquals(2, t.failures.size) }
        awaitAll(async { fixture.channel.close() }, async { fixture.channel.close() })
        assertEquals(2, fixture.sessionCloses)
        assertEquals(2, fixture.environmentCloses)
    }

    @Test fun `session released by a failed pump is not released again by channel disposal`() = runTest {
        val fixture = Fixture(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), outputFailure = true)
        runCurrent()
        assertEquals(1, fixture.sessionCloses)
        fixture.channel.close()
        assertEquals(1, fixture.sessionCloses)
        assertEquals(1, fixture.environmentCloses)
    }
}
