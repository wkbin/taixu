package top.wkbin.taixu.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ChatApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ChatApi(OkHttpClient(), Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun model(): ModelConfig = ModelConfig(
        name = "测试",
        provider = "OpenAI",
        model = "gpt-4o-mini",
        baseUrl = server.url("/v1").toString().removeSuffix("/"),
        apiKey = "sk-test-key",
    )

    @Test
    fun `parses plain text response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"你好，我已经看完了"}}]}""",
            ),
        )
        val result = api.chat(model(), listOf(ApiMessage(role = "user", content = "hi")))
        assertEquals("你好，我已经看完了", result.content)
        assertFalse(result.hasToolCalls)
    }

    @Test
    fun `parses tool calls response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                    {"id":"call_1","type":"function","function":{"name":"base","arguments":"{\"command\":\"uname -m\"}"}}
                ]}}]}""",
            ),
        )
        val result = api.chat(model(), emptyList())
        assertTrue(result.hasToolCalls)
        val call = result.toolCalls.single()
        assertEquals("call_1", call.id)
        assertEquals("base", call.name)
        assertEquals("""{"command":"uname -m"}""", call.argumentsJson)
    }

    @Test
    fun `request sends bearer auth and tools schema`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"choices":[]}"""),
        )
        api.chat(model(), emptyList())
        val recorded = server.takeRequest()
        assertEquals("Bearer sk-test-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.path == "/v1/chat/completions")
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(body.contains("\"tools\""))
        assertTrue(body.contains("\"name\":\"read\""))
    }

    @Test
    fun `http error throws with code`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid key"}"""))
        val thrown = runCatching { runBlocking { api.chat(model(), emptyList()) } }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue(thrown!!.message!!.contains("401"))
    }

    @Test
    fun `429 exposes retry after and quota exhaustion`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "7")
                .setBody("""{"error":{"message":"Workspace allocated quota exceeded, please increase your quota limit."}}"""),
        )
        val thrown = runCatching { runBlocking { api.chat(model(), emptyList()) } }.exceptionOrNull()
        assertTrue(thrown is LlmRateLimitException)
        val rateLimit = thrown as LlmRateLimitException
        assertEquals(7L, rateLimit.retryAfterSeconds)
        assertTrue(rateLimit.quotaExhausted)
    }

    @Test
    fun `openai official maps reasoning effort to reasoning_effort`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        api.chat(
            model().copy(reasoningMode = ReasoningMode.ENABLED, reasoningEffort = ReasoningEffort.MEDIUM),
            emptyList(),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning_effort\":\"medium\""))
    }

    @Test
    fun `openai official disables reasoning with effort none`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        api.chat(model().copy(reasoningMode = ReasoningMode.DISABLED), emptyList())
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning_effort\":\"none\""))
    }

    @Test
    fun `gemini maps reasoning to thinking_config thinkingBudget`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        api.chat(
            model().copy(
                provider = "Google Gemini",
                reasoningMode = ReasoningMode.ENABLED,
                reasoningEffort = ReasoningEffort.LOW,
            ),
            emptyList(),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking_config\":{\"thinkingBudget\":1024}"))
    }

    @Test
    fun `gemini disables reasoning with zero budget`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        api.chat(
            model().copy(
                provider = "Google Gemini",
                reasoningMode = ReasoningMode.DISABLED,
            ),
            emptyList(),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking_config\":{\"thinkingBudget\":0}"))
    }

    @Test
    fun `unknown provider does not inject reasoning fields`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        api.chat(
            model().copy(
                provider = "自定义 OpenAI 兼容接口",
                reasoningMode = ReasoningMode.ENABLED,
                reasoningEffort = ReasoningEffort.HIGH,
            ),
            emptyList(),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("reasoning_effort"))
        assertFalse(body.contains("thinking_config"))
        assertFalse(body.contains("\"reasoning\":{"))
    }

    @Test
    fun `auto mode does not inject reasoning fields`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        api.chat(model(), emptyList())
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("reasoning_effort"))
        assertFalse(body.contains("thinking_config"))
    }

    @Test
    fun `streams content deltas and accumulates tool calls`() = runBlocking {
        val body = """
            data: {"choices":[{"delta":{"content":"你"}}]}
            data: {"choices":[{"delta":{"content":"好。"}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"base","arguments":"{\"command\":\""}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"abc\"}"}}]}}]}
            data: [DONE]
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body))
        val deltas = mutableListOf<String>()
        val result = api.chatStream(model(), emptyList()) { deltas += it }
        assertEquals(listOf("你", "好。"), deltas)
        assertTrue(result.hasToolCalls)
        val call = result.toolCalls.single()
        assertEquals("base", call.name)
        assertEquals("""{"command":"abc"}""", call.argumentsJson)
    }

    @Test
    fun `parses usage from non-stream response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"ok"}}],
                   "usage":{"prompt_tokens":120,"completion_tokens":45,
                     "prompt_tokens_details":{"cached_tokens":80},
                     "completion_tokens_details":{"reasoning_tokens":12}}}""",
            ),
        )
        val result = api.chat(model(), emptyList())
        assertEquals(120L, result.usage.inputTokens)
        assertEquals(45L, result.usage.outputTokens)
        assertEquals(12L, result.usage.reasoningTokens)
        assertEquals(80L, result.usage.cacheReadTokens)
        assertTrue(result.usage.hasData)
    }

    @Test
    fun `parses deepseek style cache fields from non-stream response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"ok"}}],
                   "usage":{"prompt_tokens":100,"completion_tokens":20,
                     "prompt_cache_hit_tokens":60,"prompt_cache_miss_tokens":40}}""",
            ),
        )
        val result = api.chat(model(), emptyList())
        assertEquals(60L, result.usage.cacheReadTokens)
        assertEquals(40L, result.usage.cacheWriteTokens)
    }

    @Test
    fun `parses usage from final stream chunk and requests include_usage`() = runBlocking {
        val body = """
            data: {"choices":[{"delta":{"content":"你好"}}]}
            data: {"choices":[{"delta":{"content":"世界"}}]}
            data: {"choices":[],"usage":{"prompt_tokens":31,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":16},"completion_tokens_details":{"reasoning_tokens":3}}}
            data: [DONE]
        """.trimIndent()
        server.enqueue(MockResponse().setBody(body))
        val result = api.chatStream(model(), emptyList()) { }
        assertEquals(31L, result.usage.inputTokens)
        assertEquals(7L, result.usage.outputTokens)
        assertEquals(3L, result.usage.reasoningTokens)
        assertEquals(16L, result.usage.cacheReadTokens)
        val recorded = server.takeRequest()
        assertTrue(recorded.body.readUtf8().contains("\"stream_options\":{\"include_usage\":true}"))
    }

    @Test
    fun `retries without stream_options when provider rejects it`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"Extra inputs are not permitted: stream_options"}}"""),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                data: {"choices":[{"delta":{"content":"ok"}}]}
                data: [DONE]
                """.trimIndent(),
            ),
        )
        val result = api.chatStream(model(), emptyList()) { }
        assertEquals("ok", result.content)
        server.takeRequest() // 第一次带 stream_options 的请求
        val retry = server.takeRequest()
        assertFalse(retry.body.readUtf8().contains("stream_options"))
    }

    @Test
    fun `propagates other stream errors without retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"server exploded"}"""))
        var thrown: Throwable? = null
        try {
            api.chatStream(model(), emptyList()) { }
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue(thrown is IllegalStateException)
        assertEquals(1, server.requestCount)
    }
}
