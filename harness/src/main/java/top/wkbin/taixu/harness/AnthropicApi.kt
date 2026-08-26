package top.wkbin.taixu.harness

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anthropic Messages API 适配层：把内部统一的 OpenAI 风格 [ApiMessage] 列表
 * 翻译成 Claude 的 /v1/messages 请求，并把响应（含 SSE 流式）映射回 [ChatResult]。
 *
 * 主要协议差异：
 * - 鉴权头为 x-api-key + anthropic-version，而非 Authorization Bearer；
 * - 系统提示是顶层 system 字段，不在 messages 数组里；
 * - 工具结果以 user 角色 content 中的 tool_result 块回传，且同一轮的多个
 *   tool_result 必须合并在同一条 user 消息里；
 * - max_tokens 为必填；
 * - 流式事件为 content_block_start / content_block_delta / message_stop，
 *   工具参数通过 input_json_delta 增量分片。
 */
internal class AnthropicApi(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult =
        withContext(Dispatchers.IO) {
            val call = okHttpClient.newCall(buildRequest(model, messages, stream = false))
            call.execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    if (response.code == 429) {
                        throw ProviderClient.rateLimitException(response.code, body, response.header("Retry-After"))
                    }
                    throw IllegalStateException("Claude 请求失败 HTTP ${response.code}：${extractError(body)}")
                }
                parseFinalResponse(body)
            }
        }

    @OptIn(InternalCoroutinesApi::class)
    suspend fun chatStream(
        model: ModelConfig,
        messages: List<ApiMessage>,
        onReasoning: (String) -> Unit,
        onDelta: (String) -> Unit,
    ): ChatResult = withContext(Dispatchers.IO) {
        val call = okHttpClient.newCall(buildRequest(model, messages, stream = true))
        // 与 ChatApi 一致：取消时立即关闭 socket，保证"停止"秒级生效
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion(onCancelling = true) { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body.string()
                    if (response.code == 429) {
                        throw ProviderClient.rateLimitException(response.code, errorBody, response.header("Retry-After"))
                    }
                    throw IllegalStateException("Claude 请求失败 HTTP ${response.code}：${extractError(errorBody)}")
                }
                val source = response.body.source()
                val text = StringBuilder()
                val reasoningText = StringBuilder()
                // index -> 工具调用累积器（Claude 以 content block index 标识每个 tool_use）
                val toolCalls = mutableMapOf<Int, ToolCallAccumulator>()
                var usage = ChatUsage()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    val event = runCatching {
                        json.parseToJsonElement(data) as? JsonObject
                    }.getOrNull() ?: continue
                    when (event["type"]?.jsonPrimitive?.contentOrNull) {
                        "message_start" -> {
                            // message_start.usage 携带 input/cache 计数（output_tokens 此时尚未确定）
                            val messageUsage = (event["message"] as? JsonObject)?.get("usage") as? JsonObject
                            if (messageUsage != null) usage = parseUsage(messageUsage)
                        }
                        "message_delta" -> {
                            // message_delta.usage.output_tokens 为最终值，覆盖 message_start 的占位数
                            val messageUsage = event["usage"] as? JsonObject
                            val outputTokens = messageUsage?.get("output_tokens")?.jsonPrimitive?.longOrNull
                            if (outputTokens != null) usage = usage.copy(outputTokens = outputTokens)
                        }
                        "content_block_start" -> {
                            val index = event["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                            val block = event["content_block"] as? JsonObject
                            if (block != null && block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                                toolCalls.getOrPut(index) { ToolCallAccumulator() }.apply {
                                    id = block["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    name = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                }
                            }
                        }
                        "content_block_delta" -> {
                            val index = event["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                            val delta = event["delta"] as? JsonObject ?: continue
                            when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                                "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.let { chunk ->
                                    if (chunk.isNotEmpty()) {
                                        text.append(chunk)
                                        onDelta(chunk)
                                    }
                                }
                                "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.let { chunk ->
                                    if (chunk.isNotEmpty()) {
                                        reasoningText.append(chunk)
                                        onReasoning(chunk)
                                    }
                                }
                                "input_json_delta" -> delta["partial_json"]?.jsonPrimitive?.contentOrNull?.let { chunk ->
                                    toolCalls.getOrPut(index) { ToolCallAccumulator() }.arguments.append(chunk)
                                }
                            }
                        }
                        "message_stop" -> break
                        else -> Unit // message_start / ping / content_block_stop / message_delta 等无需处理
                    }
                }
                ChatResult(
                    content = text.toString().ifEmpty { null },
                    toolCalls = toolCalls.values.map { ApiToolCallSpec(it.id, it.name, it.arguments.toString()) },
                    reasoningContent = reasoningText.toString().ifEmpty { null },
                    usage = usage,
                )
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    private fun buildRequest(model: ModelConfig, messages: List<ApiMessage>, stream: Boolean): Request {
        val systemPrompt = StringBuilder()
        val anthropicMessages = mutableListOf<JsonObject>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            when (message.role) {
                "system" -> {
                    if (message.content != null) {
                        if (systemPrompt.isNotEmpty()) systemPrompt.append("\n\n")
                        systemPrompt.append(message.content)
                    }
                    index++
                }
                "user" -> {
                    anthropicMessages += buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                if (!message.content.isNullOrBlank()) {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", message.content)
                                        },
                                    )
                                }
                                message.imageUrls.forEach { url ->
                                    if (url.startsWith("data:")) {
                                        val match = Regex("""data:(image/[a-zA-Z+]+);base64,(.+)""").matchEntire(url)
                                        if (match != null) {
                                            val mediaType = match.groupValues[1]
                                            val b64Data = match.groupValues[2]
                                            add(
                                                buildJsonObject {
                                                    put("type", "image")
                                                    put(
                                                        "source",
                                                        buildJsonObject {
                                                            put("type", "base64")
                                                            put("media_type", mediaType)
                                                            put("data", b64Data)
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                    index++
                }
                "assistant" -> {
                    val content = buildJsonArray {
                        if (!message.content.isNullOrBlank()) add(JsonPrimitive(message.content))
                        message.tool_calls.orEmpty().forEach { call ->
                            add(
                                buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", call.id)
                                    put("name", call.function.name)
                                    put(
                                        "input",
                                        runCatching {
                                            json.parseToJsonElement(call.function.arguments.ifBlank { "{}" })
                                        }.getOrElse { JsonPrimitive("") },
                                    )
                                },
                            )
                        }
                    }
                    anthropicMessages += buildJsonObject {
                        put("role", "assistant")
                        put("content", content)
                    }
                    index++
                }
                "tool" -> {
                    // 同一轮的多个 tool_result 必须合并进同一条 user 消息（Claude 协议硬性要求）
                    val results = buildJsonArray {
                        while (index < messages.size && messages[index].role == "tool") {
                            val toolMessage = messages[index]
                            add(
                                buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", toolMessage.tool_call_id.orEmpty())
                                    put("content", toolMessage.content.orEmpty())
                                },
                            )
                            index++
                        }
                    }
                    anthropicMessages += buildJsonObject {
                        put("role", "user")
                        put("content", results)
                    }
                }
                else -> index++
            }
        }

        val requestBody = buildJsonObject {
            put("model", model.model)
            // Anthropic 必填；未配置时用安全默认值
            put("max_tokens", model.maxTokens ?: DEFAULT_MAX_TOKENS)
            // 推理开关/强度：thinking enabled 时 Anthropic 强制要求 temperature=1（省略即默认），
            // 且此时不再发送 temperature/top_p 以免 400。
            val thinking = ReasoningAdapter.anthropicThinking(model)
            thinking?.let { put("thinking", it) }
            val thinkingEnabled = thinking?.get("type")?.jsonPrimitive?.contentOrNull == "enabled"
            if (!thinkingEnabled) {
                model.temperature?.let { put("temperature", it) }
                model.topP?.let { put("top_p", it) }
            }
            put("stream", stream)
            val dynamicTools = if (model.pureChatMode) emptyList() else ProviderClient.buildDynamicTools(model.dynamicMcpTools)
            if (!model.pureChatMode && model.toolCallMode == ToolCallMode.JSON_TEXT && dynamicTools.isNotEmpty()) {
                // JSON 文本模式：工具定义写进 system，模型用文本输出工具调用
                systemPrompt.append("\n\n## 可用工具 JSON 定义（必须严格按此 name 与参数输出）\n")
                    .append(ProviderClient.buildToolsTextDescription(dynamicTools))
            }
            if (!model.pureChatMode && systemPrompt.isNotEmpty()) put("system", systemPrompt.toString())
            put("messages", JsonArray(anthropicMessages))
            // 仅 NATIVE 模式注入标准 tools；纯净模式与 JSON_TEXT / DISABLED 均不注入
            if (!model.pureChatMode && model.toolCallMode == ToolCallMode.NATIVE && dynamicTools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        dynamicTools.forEach { definition ->
                            add(
                                buildJsonObject {
                                    put("name", definition.function.name)
                                    put("description", definition.function.description)
                                    put("input_schema", definition.function.parameters)
                                },
                            )
                        }
                    },
                )
                put("tool_choice", buildJsonObject { put("type", "auto") })
            }
        }

        return Request.Builder()
            .url("${model.baseUrl.trimEnd('/')}/messages")
            .header("Content-Type", "application/json")
            .header("anthropic-version", ANTHROPIC_VERSION)
            .apply {
                model.apiKey?.let { header("x-api-key", it) }
                ProviderClient.parseCustomHeaders(model.customHeaders).forEach { (name, value) ->
                    header(name, value)
                }
            }
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun parseFinalResponse(body: String): ChatResult {
        val root = json.parseToJsonElement(body).jsonObject
        val contentBlocks = root["content"]?.jsonArray.orEmpty()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ApiToolCallSpec>()
        contentBlocks.forEach { block ->
            val obj = block as? JsonObject ?: return@forEach
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> text.append(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "thinking" -> reasoning.append(obj["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "tool_use" -> calls += ApiToolCallSpec(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    argumentsJson = obj["input"]?.toString() ?: "{}",
                )
            }
        }
        return ChatResult(
            content = text.toString().ifEmpty { null },
            toolCalls = calls,
            reasoningContent = reasoning.toString().ifEmpty { null },
            usage = (root["usage"] as? JsonObject)?.let(::parseUsage) ?: ChatUsage(),
        )
    }

    /** Anthropic usage：input/output_tokens + cache_read/cache_creation_input_tokens。 */
    private fun parseUsage(usage: JsonObject): ChatUsage = ChatUsage(
        inputTokens = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
        outputTokens = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
        cacheReadTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
        cacheWriteTokens = usage["cache_creation_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
    )

    private fun extractError(body: String): String {
        val message = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return message ?: body.take(512)
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 8192
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
