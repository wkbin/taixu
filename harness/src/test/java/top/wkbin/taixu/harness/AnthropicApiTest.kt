package top.wkbin.taixu.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AnthropicApi
    private val recordedRequests = mutableListOf<okhttp3.Request>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = AnthropicApi(OkHttpClient(), Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() {
        server.shutdown()
        recordedRequests.clear()
    }

    private fun model(): ModelConfig = ModelConfig(
        name = "测试",
        provider = "Anthropic Claude",
        model = "claude-sonnet-4.6",
        baseUrl = server.url("/v1").toString().removeSuffix("/"),
        apiKey = "sk-ant-test",
        protocol = ApiProtocol.ANTHROPIC,
    )

    @Test
    fun `plain text response parses content`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"搞定，已完成修改"}]}""",
            ),
        )
        val result = api.chat(model(), listOf(ApiMessage(role = "user", content = "hi")))
        assertEquals("搞定，已完成修改", result.content)
        assertFalse(result.hasToolCalls)
    }

    @Test
    fun `tool use response parses call id name and arguments`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[
                    {"type":"text","text":"我来执行命令"},
                    {"type":"tool_use","id":"toolu_1","name":"base","input":{"command":"uname -m"}}
                ]}""",
            ),
        )
        val result = api.chat(model(), listOf(ApiMessage(role = "user", content = "run")))
        assertEquals("我来执行命令", result.content)
        assertTrue(result.hasToolCalls)
        val call = result.toolCalls.single()
        assertEquals("toolu_1", call.id)
        assertEquals("base", call.name)
        assertTrue(call.argumentsJson.contains("uname -m"))
    }

    @Test
    fun `system prompt moves to top-level and auth headers are anthropic style`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model(),
            listOf(
                ApiMessage(role = "system", content = "你是太墟 Agent"),
                ApiMessage(role = "user", content = "hi"),
            ),
        )
        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("sk-ant-test", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""system":"你是太墟 Agent""""))
        assertFalse(body.contains(""""role":"system""""))
    }

    @Test
    fun `tool results merge into single user message with tool_result blocks`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model(),
            listOf(
                ApiMessage(role = "user", content = "run two commands"),
                ApiMessage(
                    role = "assistant",
                    content = null,
                    tool_calls = listOf(
                        ApiToolCall(id = "toolu_1", function = ApiFunctionCall(name = "base", arguments = """{"command":"ls"}""")),
                        ApiToolCall(id = "toolu_2", function = ApiFunctionCall(name = "base", arguments = """{"command":"pwd"}""")),
                    ),
                ),
                ApiMessage(role = "tool", content = "file-a\nfile-b", tool_call_id = "toolu_1"),
                ApiMessage(role = "tool", content = "/root", tool_call_id = "toolu_2"),
            ),
        )
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        // 两个 tool_result 必须在同一条 user 消息内
        val toolResultCount = Regex(""""type":"tool_result"""").findAll(body).count()
        assertEquals(2, toolResultCount)
        // assistant 的 tool_calls 翻译成 tool_use 块
        val toolUseCount = Regex(""""type":"tool_use"""").findAll(body).count()
        assertEquals(2, toolUseCount)
        assertTrue(body.contains(""""tool_use_id":"toolu_1""""))
        assertTrue(body.contains(""""tool_use_id":"toolu_2""""))
        assertTrue(body.contains("file-a"))
    }

    @Test
    fun `max tokens is required and defaults when unset`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(model(), listOf(ApiMessage(role = "user", content = "hi")))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""max_tokens":8192"""))
    }

    @Test
    fun `inference parameters are sent when configured`() = runBlocking {
        val configured = model().copy(temperature = 0.7f, maxTokens = 1024, topP = 0.9f)
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(configured, listOf(ApiMessage(role = "user", content = "hi")))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""temperature":0.7"""))
        assertTrue(body.contains(""""max_tokens":1024"""))
        assertTrue(body.contains(""""top_p":0.9"""))
    }

    @Test
    fun `thinking enabled sends budget tokens and drops temperature`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model().copy(
                reasoningMode = ReasoningMode.ENABLED,
                reasoningEffort = ReasoningEffort.MEDIUM,
                temperature = 0.7f,
                topP = 0.9f,
            ),
            listOf(ApiMessage(role = "user", content = "hi")),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":2048}"))
        assertFalse(body.contains("\"temperature\""))
        assertFalse(body.contains("\"top_p\""))
    }

    @Test
    fun `thinking enabled without effort uses default budget and clamps to max tokens`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model().copy(reasoningMode = ReasoningMode.ENABLED, maxTokens = 2500),
            listOf(ApiMessage(role = "user", content = "hi")),
        )
        val body = server.takeRequest().body.readUtf8()
        // 默认 2048 需严格小于 max_tokens 2500，clamp 到 2500-1024=1476
        assertTrue(body.contains("\"budget_tokens\":1476"))
    }

    @Test
    fun `thinking disabled sends type disabled and keeps temperature`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(
            model().copy(reasoningMode = ReasoningMode.DISABLED, temperature = 0.3f),
            listOf(ApiMessage(role = "user", content = "hi")),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(body.contains("\"temperature\":0.3"))
    }

    @Test
    fun `auto mode does not inject thinking`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        api.chat(model(), listOf(ApiMessage(role = "user", content = "hi")))
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("\"thinking\""))
    }

    @Test
    fun `stream events assemble text reasoning and tool arguments`() = runBlocking {
        val sse = buildString {
            append("event: message_start\n")
            append("""data: {"type":"message_start","message":{}}""" + "\n\n")
            append("event: content_block_start\n")
            append("""data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""" + "\n\n")
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"正在分析"}}""" + "\n\n")
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"文件"}}""" + "\n\n")
            append("event: content_block_start\n")
            append("""data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_9","name":"base","input":{}}}""" + "\n\n")
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"command\":"}}""" + "\n\n")
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"ls -la\"}"}}""" + "\n\n")
            append("event: message_stop\n")
            append("""data: {"type":"message_stop"}""" + "\n\n")
        }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        var text = ""
        val result = api.chatStream(model(), listOf(ApiMessage(role = "user", content = "hi")), onReasoning = {}, onDelta = { text += it })
        assertEquals("正在分析文件", result.content)
        assertEquals("正在分析文件", text)
        val call = result.toolCalls.single()
        assertEquals("toolu_9", call.id)
        assertEquals("base", call.name)
        assertEquals("""{"command":"ls -la"}""", call.argumentsJson)
        assertNull(result.reasoningContent)
    }

    @Test
    fun `thinking delta maps to reasoning content`() = runBlocking {
        val sse = buildString {
            append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先看看目录"}}""" + "\n\n")
            append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"结论"}}""" + "\n\n")
            append("""data: {"type":"message_stop"}""" + "\n\n")
        }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        var reasoning = ""
        val result = api.chatStream(model(), listOf(ApiMessage(role = "user", content = "hi")), onReasoning = { reasoning += it }, onDelta = {})
        assertEquals("结论", result.content)
        assertEquals("先看看目录", result.reasoningContent)
        assertEquals("先看看目录", reasoning)
    }

    @Test
    fun `http error surfaces anthropic error message`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
            ),
        )
        val error = runCatching {
            api.chat(model(), listOf(ApiMessage(role = "user", content = "hi")))
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("invalid x-api-key"))
    }

    @Test
    fun `parses usage from non-stream response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"ok"}],
                   "usage":{"input_tokens":210,"output_tokens":88,
                     "cache_read_input_tokens":150,"cache_creation_input_tokens":60}}""",
            ),
        )
        val result = api.chat(model(), listOf(ApiMessage(role = "user", content = "hi")))
        assertEquals(210L, result.usage.inputTokens)
        assertEquals(88L, result.usage.outputTokens)
        assertEquals(150L, result.usage.cacheReadTokens)
        assertEquals(60L, result.usage.cacheWriteTokens)
    }

    @Test
    fun `parses usage from message_start and message_delta in stream`() = runBlocking {
        val sse = buildString {
            append("event: message_start\n")
            append(
                """data: {"type":"message_start","message":{"usage":{"input_tokens":512,"output_tokens":2,"cache_read_input_tokens":300,"cache_creation_input_tokens":50}}}""" + "\n\n",
            )
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"结论"}}""" + "\n\n")
            append("event: message_delta\n")
            append("""data: {"type":"message_delta","delta":{},"usage":{"output_tokens":96}}""" + "\n\n")
            append("event: message_stop\n")
            append("""data: {"type":"message_stop"}""" + "\n\n")
        }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val result = api.chatStream(model(), listOf(ApiMessage(role = "user", content = "hi")), onReasoning = {}, onDelta = {})
        assertEquals(512L, result.usage.inputTokens)
        assertEquals(96L, result.usage.outputTokens)
        assertEquals(300L, result.usage.cacheReadTokens)
        assertEquals(50L, result.usage.cacheWriteTokens)
    }
}
