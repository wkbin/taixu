package top.wkbin.taixu.runtime.environment

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ExecutionDistributionTest {
    private fun binding(backend: String = "local-proot"): ExecutionEnvironmentContext {
        val world = Proxy.newProxyInstance(ExecutionEnvironment::class.java.classLoader,
            arrayOf(ExecutionEnvironment::class.java)) { _, method, _ ->
            check(method.name == "getId") { "Unexpected effect: ${method.name}" }
            ExecutionEnvironmentId(backend, "ubuntu", "/workspace/p", "one")
        } as ExecutionEnvironment
        return ExecutionEnvironmentContext(world) { AutoCloseable {} }
    }

    @Test fun `legacy local consumers retain pinned distro after active selection changes`() = runBlocking {
        withContext(binding()) { assertEquals("ubuntu", boundLocalDistribution(null, "debian")) }
    }
    @Test fun `explicit conflicting distro cannot bypass the bound execution world`() = runBlocking {
        withContext(binding()) {
            try { boundLocalDistribution("debian", "debian"); fail("cross-world execution accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `remote world cannot fall back to local runtime`() = runBlocking {
        withContext(binding("remote")) {
            try { boundLocalDistribution(null, "debian"); fail("local fallback accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `unbound UI callers keep explicit selection and default behavior`() = runBlocking {
        assertEquals("debian", boundLocalDistribution(" DEBIAN ", "ubuntu"))
        assertEquals("ubuntu", boundLocalDistribution(null, "ubuntu"))
    }
}
