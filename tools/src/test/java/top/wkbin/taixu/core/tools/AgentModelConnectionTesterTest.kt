package top.wkbin.taixu.core.tools

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentModelConnectionTesterTest {
    private lateinit var server: MockWebServer
    private lateinit var tester: AgentModelConnectionTester

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tester = AgentModelConnectionTester(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `blank model tests catalog without requiring a selection`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"data\":[]}"))

        tester.test(server.url("/v1").toString(), "", "secret")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
    }

    @Test
    fun `selected model still tests chat completion`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        tester.test(server.url("/v1").toString(), "test-model", null)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.path)
    }

    @Test
    fun `responses api flag tests the responses endpoint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        tester.test(server.url("/v1").toString(), "test-model", "secret", useResponsesApi = true)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/responses", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"max_output_tokens\""))
        assertTrue(body.contains("\"type\":\"input_text\""))
    }

    @Test
    fun `anthropic protocol tests messages endpoint on non-official domain`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"content\":[{\"text\":\"OK\"}]}"))

        tester.test(
            baseUrl = server.url("/v1").toString(),
            model = "claude-3-7-sonnet",
            apiKey = "ant-secret",
            protocol = ProviderProtocol.ANTHROPIC,
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/messages", request.path)
        assertEquals("ant-secret", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"claude-3-7-sonnet\""))
        assertTrue(body.contains("\"max_tokens\":16"))
    }

    @Test
    fun `anthropic protocol tests catalog with x-api-key on non-official domain`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"data\":[]}"))

        tester.test(
            baseUrl = server.url("/v1").toString(),
            model = "",
            apiKey = "ant-secret",
            protocol = ProviderProtocol.ANTHROPIC,
        )

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.path)
        assertEquals("ant-secret", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
    }
}
