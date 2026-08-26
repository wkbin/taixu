package top.wkbin.taixu.harness

import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.tools.ProviderRepository
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** HTTP 429 的结构化错误，供 Harness 区分临时限流与账户额度耗尽。 */
class LlmRateLimitException(
    message: String,
    val retryAfterSeconds: Long? = null,
    val quotaExhausted: Boolean = false,
) : IOException(message)

/** 可独立测试的 HTTP 层：OpenAI 兼容 chat/completions 请求与响应解析。 */
internal class ChatApi(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult =
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(buildRequest(model, messages, stream = false)).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    if (response.code == 429) {
                        throw ProviderClient.rateLimitException(response.code, body, response.header("Retry-After"))
                    }
                    throw IllegalStateException(ProviderClient.formatHttpErrorMessage(response.code, body))
                }
                val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), body)
                val message = parsed.choices.firstOrNull()?.message ?: ChatResponseMessage()
                val calls = message.tool_calls.orEmpty().mapNotNull { call ->
                    call.function.let { fn ->
                        if (fn.name.isBlank()) null else ApiToolCallSpec(call.id, fn.name, fn.arguments)
                    }
                }
                ChatResult(
                    content = message.content,
                    toolCalls = calls,
                    reasoningContent = message.reasoning_content,
                    usage = parsed.usage?.toChatUsage() ?: ChatUsage(),
                )
            }
        }

    /**
     * 流式调用：逐行读取 SSE（data: ...），每个内容增量立即通过 [onDelta] 回调
     * 交给 UI；工具调用参数按 index 分片累积。推理增量通过 [onReasoning] 回调。
     *
     * 默认携带 stream_options.include_usage 请求最终 usage 块；个别严格校验的
     * Provider 会因此 400，此时自动降级为不带该参数重试一次（请求在流开始前
     * 即失败，不会有增量重复发送的风险）。
     */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun chatStream(
        model: ModelConfig,
        messages: List<ApiMessage>,
        onReasoning: (String) -> Unit = {},
        onDelta: (String) -> Unit,
    ): ChatResult = try {
        executeStream(model, messages, onReasoning, onDelta, includeUsage = true)
    } catch (rejected: IllegalStateException) {
        if (rejected.message?.contains("stream_options", ignoreCase = true) == true) {
            executeStream(model, messages, onReasoning, onDelta, includeUsage = false)
        } else {
            throw rejected
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun executeStream(
        model: ModelConfig,
        messages: List<ApiMessage>,
        onReasoning: (String) -> Unit,
        onDelta: (String) -> Unit,
        includeUsage: Boolean,
    ): ChatResult = withContext(Dispatchers.IO) {
        val call = okHttpClient.newCall(buildRequest(model, messages, stream = true, includeUsage = includeUsage))
        // 关键：阻塞式 readUtf8Line() 不感知协程取消。用户点"停止"时必须主动 call.cancel()
        // 关闭底层 socket，阻塞读才会立刻抛出 IOException 退出——否则要等读超时（最长 3 分钟），
        // 表现为"停止按钮没反应"。
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion(onCancelling = true) { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body.string().take(512)
                    if (response.code == 429) {
                        throw ProviderClient.rateLimitException(response.code, rawBody, response.header("Retry-After"))
                    }
                    throw IllegalStateException(ProviderClient.formatHttpErrorMessage(response.code, rawBody))
                }
                val source = response.body.source()
                val text = StringBuilder()
                // 推理模型的 thinking 内容（如 DeepSeek-R1 的 reasoning_content），后续轮次需原样传回
                val reasoningText = StringBuilder()
                val toolCalls = mutableMapOf<Int, ToolCallAccumulator>()
                var usage = ChatUsage()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()
                    // usage 块位于 chunk 顶层（stream_options.include_usage 时由最后一个 chunk 携带；
                    // DeepSeek/OpenRouter 等默认就会发）。后面的块覆盖前面的，保留最终值。
                    (root?.get("usage") as? JsonObject)?.let { block ->
                        parseUsageBlock(block)?.let { parsed -> usage = parsed }
                    }
                    val choice = root
                        ?.get("choices")?.let { it as? JsonArray }?.firstOrNull() as? JsonObject
                        ?: continue
                    val delta = choice["delta"] as? JsonObject
                    delta?.get("content")?.let { it as? JsonPrimitive }?.contentOrNull?.let { chunk ->
                        if (chunk.isNotEmpty()) {
                            text.append(chunk)
                            onDelta(chunk)
                        }
                    }
                    // 推理增量：兼容 reasoning_content（DeepSeek/GLM 等）与 reasoning（OpenRouter 等网关）两种字段名
                    val reasoningChunk = (delta?.get("reasoning_content") ?: delta?.get("reasoning"))
                        ?.let { it as? JsonPrimitive }?.contentOrNull
                    reasoningChunk?.let { chunk ->
                        if (chunk.isNotEmpty()) {
                            reasoningText.append(chunk)
                            onReasoning(chunk)
                        }
                    }
                    delta?.get("tool_calls")?.let { it as? JsonArray }?.forEach { call2 ->
                        val callObj = call2 as? JsonObject ?: return@forEach
                        val index = callObj["index"]?.let { it as? JsonPrimitive }?.contentOrNull?.toIntOrNull() ?: 0
                        val accum = toolCalls.getOrPut(index) { ToolCallAccumulator() }
                        callObj["id"]?.let { it as? JsonPrimitive }?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }?.let { accum.id = it }
                        val function = callObj["function"] as? JsonObject
                        function?.get("name")?.let { it as? JsonPrimitive }?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }?.let { accum.name = it }
                        function?.get("arguments")?.let { it as? JsonPrimitive }?.contentOrNull
                            ?.let { accum.arguments.append(it) }
                    }
                }
                val calls = toolCalls.values.map { ApiToolCallSpec(it.id, it.name, it.arguments.toString()) }
                ChatResult(
                    content = text.toString().ifEmpty { null },
                    toolCalls = calls,
                    reasoningContent = reasoningText.toString().ifEmpty { null },
                    usage = usage,
                )
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    /** 手工解析流式 chunk 顶层 usage（各 Provider 字段不统一，DTO 反而脆）。 */
    private fun parseUsageBlock(block: JsonObject): ChatUsage? {
        val input = block["prompt_tokens"]?.let { it as? JsonPrimitive }?.contentOrNull?.toLongOrNull() ?: return null
        return ChatUsage(
            inputTokens = input,
            outputTokens = block["completion_tokens"]?.let { it as? JsonPrimitive }?.contentOrNull?.toLongOrNull() ?: 0,
            reasoningTokens = (block["completion_tokens_details"] as? JsonObject)
                ?.get("reasoning_tokens")?.let { it as? JsonPrimitive }?.contentOrNull?.toLongOrNull() ?: 0,
            cacheReadTokens = (block["prompt_tokens_details"] as? JsonObject)
                ?.get("cached_tokens")?.let { it as? JsonPrimitive }?.contentOrNull?.toLongOrNull()
                ?: block["prompt_cache_hit_tokens"]?.let { it as? JsonPrimitive }?.contentOrNull?.toLongOrNull() ?: 0,
            cacheWriteTokens = block["prompt_cache_miss_tokens"]?.let { it as? JsonPrimitive }?.contentOrNull?.toLongOrNull() ?: 0,
        )
    }

    private fun buildRequest(model: ModelConfig, messages: List<ApiMessage>, stream: Boolean, includeUsage: Boolean = true): Request {
            val tools = if (model.pureChatMode) emptyList() else ProviderClient.buildDynamicTools(model.dynamicMcpTools)
        // JSON_TEXT 模式：把工具 JSON 描述追加到 system 消息末尾，让模型在纯文本中输出工具调用
        val effectiveMessages = if (!model.pureChatMode && model.toolCallMode == ToolCallMode.JSON_TEXT && tools.isNotEmpty()) {
            val desc = ProviderClient.buildToolsTextDescription(tools)
            messages.map { msg ->
                if (msg.role == "system" && !msg.content.isNullOrBlank()) {
                    msg.copy(content = msg.content + "\n\n## 可用工具 JSON 定义（必须严格按此 name 与参数输出）\n" + desc)
                } else {
                    msg
                }
            }
        } else {
            messages
        }
        val requestJson = kotlinx.serialization.json.buildJsonObject {
            put("model", kotlinx.serialization.json.JsonPrimitive(model.model))
            put("stream", kotlinx.serialization.json.JsonPrimitive(stream))
            if (stream && includeUsage) {
                // 请求最终 usage 块（OpenAI 官方规范字段；DeepSeek/DashScope/GLM/OpenRouter 均支持）
                put("stream_options", kotlinx.serialization.json.buildJsonObject {
                    put("include_usage", kotlinx.serialization.json.JsonPrimitive(true))
                })
            }
            model.temperature?.let { put("temperature", kotlinx.serialization.json.JsonPrimitive(it)) }
            model.maxTokens?.let { put("max_tokens", kotlinx.serialization.json.JsonPrimitive(it)) }
            model.topP?.let { put("top_p", kotlinx.serialization.json.JsonPrimitive(it)) }
            // 推理开关/强度：按厂商能力翻译（reasoning_effort / thinking_config / thinking / reasoning）
            ReasoningAdapter.openAiFields(model).forEach { (key, value) -> put(key, value) }
            // 工具调用：纯净模式与 DISABLED 模式完全不注入工具相关参数；仅 NATIVE 模式注入标准 tools
            if (!model.pureChatMode && model.toolCallMode == ToolCallMode.NATIVE && tools.isNotEmpty()) {
                put("tools", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ApiToolDefinition.serializer()), tools))
            }
            put("messages", kotlinx.serialization.json.buildJsonArray {
                effectiveMessages.forEach { msg ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("role", kotlinx.serialization.json.JsonPrimitive(msg.role))
                        if (msg.imageUrls.isNotEmpty()) {
                            put("content", kotlinx.serialization.json.buildJsonArray {
                                if (!msg.content.isNullOrBlank()) {
                                    add(kotlinx.serialization.json.buildJsonObject {
                                        put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                                        put("text", kotlinx.serialization.json.JsonPrimitive(msg.content))
                                    })
                                }
                                msg.imageUrls.forEach { url ->
                                    add(kotlinx.serialization.json.buildJsonObject {
                                        put("type", kotlinx.serialization.json.JsonPrimitive("image_url"))
                                        put("image_url", kotlinx.serialization.json.buildJsonObject {
                                            put("url", kotlinx.serialization.json.JsonPrimitive(url))
                                        })
                                    })
                                }
                            })
                        } else if (msg.content != null) {
                            put("content", kotlinx.serialization.json.JsonPrimitive(msg.content))
                        }
                        msg.reasoning_content?.let { put("reasoning_content", kotlinx.serialization.json.JsonPrimitive(it)) }
                        msg.tool_call_id?.let { put("tool_call_id", kotlinx.serialization.json.JsonPrimitive(it)) }
                        msg.tool_calls?.let { calls ->
                            put("tool_calls", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ApiToolCall.serializer()), calls))
                        }
                    })
                }
            })
        }
        return Request.Builder()
            .url("${model.baseUrl.trimEnd('/')}/chat/completions")
            .header("Content-Type", "application/json")
            .apply {
                model.apiKey?.let { header("Authorization", "Bearer $it") }
                ProviderClient.parseCustomHeaders(model.customHeaders).forEach { (name, value) ->
                    header(name, value)
                }
            }
            .post(requestJson.toString().toRequestBody(ProviderClient.JSON_MEDIA_TYPE))
            .build()
    }
}

/** 分片累积一次工具调用的 id/name/arguments（OpenAI 与 Anthropic 流式均复用）。 */
internal data class ToolCallAccumulator(
    var id: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder(),
)

/** 解析后的模型运行配置。 */
data class ModelConfig(
    val name: String,
    val provider: String,
    val model: String,
    val baseUrl: String,
    val apiKey: String?,
    /** 同一接口地址下参与轮询的 Key 池；为空时兼容使用 [apiKey]。 */
    val apiKeys: List<String> = emptyList(),
    /** 单 Key 每分钟请求上限；0 表示不限。 */
    val requestsPerMinutePerKey: Int = 0,
    /** 接入协议：OPENAI 兼容或 Anthropic Messages API。 */
    val protocol: ApiProtocol = ApiProtocol.OPENAI,
    /** 推理参数（null = 服务端默认）。 */
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    /** 推理开关：AUTO = 跟随模型服务端默认。 */
    val reasoningMode: ReasoningMode = ReasoningMode.AUTO,
    /** 推理强度：null = 服务端默认。 */
    val reasoningEffort: ReasoningEffort? = null,
    /**
     * 工具调用模式：NATIVE = OpenAI 标准 function calling（注入 tools）；
     * JSON_TEXT = 工具列表写入系统提示词，模型用文本输出工具调用；
     * DISABLED = 禁用工具（纯聊天）。
     */
    val toolCallMode: ToolCallMode = ToolCallMode.NATIVE,
    val dynamicMcpTools: List<top.wkbin.taixu.core.model.McpToolInfo> = emptyList(),
    /** 上下文 Token 容量上限（如 128000，超出时滑动窗口压缩）。 */
    val contextTokens: Int? = null,
    /** 自定义请求头（多行 Key: Value 格式）。 */
    val customHeaders: String = "",
    /** 纯净排查模式：不注入系统提示词与工具。 */
    val pureChatMode: Boolean = false,
    /** 是否支持视觉多模态直接发送图片。 */
    val visionEnabled: Boolean = true,
)

/** LLM 接入协议：绝大多数厂商提供 OpenAI 兼容端点；Anthropic Claude 需要专用适配。 */
enum class ApiProtocol { OPENAI, ANTHROPIC }

/** 工具调用模式：NATIVE = 标准函数调用；JSON_TEXT = 文本 JSON 标记；DISABLED = 禁用。 */
enum class ToolCallMode { NATIVE, JSON_TEXT, DISABLED }

/** LLM 返回的一轮结果：纯文本 或 一个/多个工具调用。 */
data class ChatResult(
    val content: String?,
    val toolCalls: List<ApiToolCallSpec>,
    /** 推理模型输出的思考内容（DeepSeek 等），多轮对话需原样传回 API。 */
    val reasoningContent: String? = null,
    /** Provider 报告的本轮 token 用量；未报告时全部为 0。 */
    val usage: ChatUsage = ChatUsage(),
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}

/**
 * 一次补全的 token 用量（OpenAI usage 与 Anthropic usage 的统一投影）。
 * OpenAI: prompt/completion_tokens + details(cached/reasoning)；
 * Anthropic: input/output_tokens + cache_read/cache_creation_input_tokens；
 * DeepSeek: prompt_cache_hit/miss_tokens 映射为 cacheRead/cacheWrite。
 */
data class ChatUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
) {
    val hasData: Boolean
        get() = inputTokens > 0 || outputTokens > 0 || reasoningTokens > 0 ||
            cacheReadTokens > 0 || cacheWriteTokens > 0
}

data class ApiToolCallSpec(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

// ---------- OpenAI 兼容 chat/completions DTO ----------

@Serializable
data class ApiMessage(
    val role: String,
    val content: String? = null,
    val reasoning_content: String? = null,
    val tool_calls: List<ApiToolCall>? = null,
    val tool_call_id: String? = null,
    val imageUrls: List<String> = emptyList(),
)

@Serializable
data class ApiToolCall(
    val id: String,
    val type: String = "function",
    val function: ApiFunctionCall,
)

@Serializable
data class ApiFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val tools: List<ApiToolDefinition>? = null,
    val tool_choice: String = "auto",
    val stream: Boolean = false,
    val temperature: Float? = null,
    val max_tokens: Int? = null,
    val top_p: Float? = null,
)

@Serializable
data class ApiToolDefinition(
    val type: String = "function",
    val function: ApiFunctionDefinition,
)

@Serializable
data class ApiFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: kotlinx.serialization.json.JsonObject,
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsageResponse? = null,
)

/** OpenAI 兼容 usage 块（含 DeepSeek 缓存字段与 reasoning details）。 */
@Serializable
data class ChatUsageResponse(
    val prompt_tokens: Long? = null,
    val completion_tokens: Long? = null,
    val prompt_tokens_details: PromptTokensDetails? = null,
    val completion_tokens_details: CompletionTokensDetails? = null,
    val prompt_cache_hit_tokens: Long? = null,
    val prompt_cache_miss_tokens: Long? = null,
) {
    @Serializable
    data class PromptTokensDetails(val cached_tokens: Long? = null)

    @Serializable
    data class CompletionTokensDetails(val reasoning_tokens: Long? = null)

    fun toChatUsage(): ChatUsage = ChatUsage(
        inputTokens = prompt_tokens ?: 0,
        outputTokens = completion_tokens ?: 0,
        reasoningTokens = completion_tokens_details?.reasoning_tokens ?: 0,
        cacheReadTokens = prompt_tokens_details?.cached_tokens ?: prompt_cache_hit_tokens ?: 0,
        cacheWriteTokens = prompt_cache_miss_tokens ?: 0,
    )
}

@Serializable
data class ChatChoice(
    val message: ChatResponseMessage = ChatResponseMessage(),
)

@Serializable
data class ChatResponseMessage(
    val content: String? = null,
    val reasoning_content: String? = null,
    val tool_calls: List<ApiToolCall>? = null,
)

/**
 * 调用 LLM（OpenAI 兼容 chat/completions，支持 tools/tool_calls）。
 *
 * 模型配置优先取 [AiModelRepository] 中激活的 [top.wkbin.taixu.core.database.AiModelEntity]，
 * 未配置时回退到 [ProviderRepository]；API Key 始终从加密存储读取，绝不落库/落日志。
 */
@Singleton
class ProviderClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val providerRepository: ProviderRepository,
    private val modelDao: AiModelRepository,
    private val mcpManager: top.wkbin.taixu.harness.mcp.McpManager,
    private val settingsDataStore: AgentPreferences,
    private val json: Json,
) {
    private val apiKeyScheduler = ApiKeyScheduler()
    private val httpClient: OkHttpClient = okHttpClient.newBuilder()
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun resolveModel(): ModelConfig = withContext(Dispatchers.IO) {
        val active = modelDao.activeModel()
        val baseConfig = if (active != null) {
            active.toModelConfig(providerRepository)
        } else {
            ModelConfig(
                name = "默认",
                provider = providerRepository.provider.first(),
                model = providerRepository.model.first().ifBlank { DEFAULT_MODEL },
                baseUrl = providerRepository.baseUrl.first().ifBlank { DEFAULT_BASE_URL },
                apiKey = providerRepository.readApiKey(),
            )
        }
        val dynamicMcp = runCatching { mcpManager.getActiveMcpTools() }.getOrDefault(emptyList())
        baseConfig.applyGlobalReasoningDepth().copy(dynamicMcpTools = dynamicMcp)
    }

    /**
     * 同 [resolveModel]，但额外做最小配置校验：无激活模型且未设置 API Key 时
     * 直接抛出明确异常，让发送前就能拦截，而不是让 Agent 空转后以 401 告警收场。
     */
    suspend fun resolveConfigured(): ModelConfig = withContext(Dispatchers.IO) {
        val active = modelDao.activeModel()
        val providerKey = providerRepository.readApiKey().orEmpty()
        if (active == null && providerKey.isBlank()) {
            throw IllegalStateException("未配置模型或 API Key，请先在「设置 → 模型」中添加并激活一个模型")
        }
        val baseConfig = if (active != null) {
            active.toModelConfig(providerRepository)
        } else {
            val provider = providerRepository.provider.first()
            val baseUrl = providerRepository.baseUrl.first().ifBlank { DEFAULT_BASE_URL }
            ModelConfig(
                name = "默认",
                provider = provider,
                model = providerRepository.model.first().ifBlank { DEFAULT_MODEL },
                baseUrl = baseUrl,
                apiKey = providerKey.ifBlank { null },
                protocol = inferProtocol(baseUrl, provider),
            )
        }
        val dynamicMcp = runCatching { mcpManager.getActiveMcpTools() }.getOrDefault(emptyList())
        baseConfig.applyGlobalReasoningDepth().copy(dynamicMcpTools = dynamicMcp)
    }

    /**
     * 把「全局推理深度」设置应用到未显式配置的模型上。**只对该厂商实际支持的能力生效**：
     * - 先探测厂商能力（能否关闭 / 能否调强度），不支持的选项直接忽略，避免设置"改了却没反应"；
     * - 模型已显式关闭推理 -> 保持不动（用户意图优先）；
     * - 全局 disabled：仅当厂商 [ReasoningCapabilities.supportsDisable] 且模型 AUTO 时关闭推理；
     * - 全局 low/medium/high：仅当厂商 [ReasoningCapabilities.supportsEffort] 时按深度设置强度
     *   （模型 AUTO 则同时开启推理）；厂商不支持强度（如豆包）则保持 AUTO 跟随服务端默认；
     * - 全局 auto 或未知值 -> 不动。
     */
    private suspend fun ModelConfig.applyGlobalReasoningDepth(): ModelConfig {
        if (reasoningMode == ReasoningMode.DISABLED) return this
        val depth = settingsDataStore.defaultReasoningDepth.first()
        val caps = ReasoningAdapter.capabilities(this)
        return when (depth) {
            "disabled" ->
                if (reasoningMode == ReasoningMode.AUTO && caps.supportsDisable) {
                    copy(reasoningMode = ReasoningMode.DISABLED)
                } else {
                    this
                }
            "low", "medium", "high" -> {
                if (!caps.supportsEffort) return this // 不支持强度 -> 跟随服务端默认
                val effort = when (depth) {
                    "low" -> ReasoningEffort.LOW
                    "medium" -> ReasoningEffort.MEDIUM
                    else -> ReasoningEffort.HIGH
                }
                if (reasoningMode == ReasoningMode.AUTO) {
                    copy(reasoningMode = ReasoningMode.ENABLED, reasoningEffort = effort)
                } else {
                    copy(reasoningEffort = reasoningEffort ?: effort)
                }
            }
            else -> this
        }
    }

    suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult =
        executeWithRotatedApiKey(model, apiKeyScheduler) { selected ->
            when (selected.protocol) {
                ApiProtocol.ANTHROPIC -> AnthropicApi(httpClient, json).chat(selected, messages)
                ApiProtocol.OPENAI -> ChatApi(httpClient, json).chat(selected, messages)
            }
        }

    /** 流式调用：内容增量通过 [onDelta] 实时回调，推理增量通过 [onReasoning] 实时回调。 */
    suspend fun chatStream(
        model: ModelConfig,
        messages: List<ApiMessage>,
        onReasoning: (String) -> Unit = {},
        onDelta: (String) -> Unit,
    ): ChatResult = executeWithRotatedApiKey(model, apiKeyScheduler) { selected ->
        when (selected.protocol) {
            ApiProtocol.ANTHROPIC -> AnthropicApi(httpClient, json).chatStream(selected, messages, onReasoning, onDelta)
            ApiProtocol.OPENAI -> ChatApi(httpClient, json).chatStream(selected, messages, onReasoning, onDelta)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        private const val CALL_TIMEOUT_MS = 3 * 60 * 1000L

        /** Room 实体 → 运行配置：推理参数原样透传，协议按 Base URL / 厂商名自动推断。 */
        private suspend fun top.wkbin.taixu.core.database.AiModelEntity.toModelConfig(
            providerRepository: top.wkbin.taixu.core.tools.ProviderRepository,
        ): ModelConfig {
            val baseUrl = this.baseUrl.ifBlank { DEFAULT_BASE_URL }
            val modelKeys = providerRepository.readModelApiKeys(secretRef)
            val fallbackKey = providerRepository.readApiKey().orEmpty().ifBlank { null }
            val effectiveKeys = modelKeys.ifEmpty { listOfNotNull(fallbackKey) }
            return ModelConfig(
                name = name,
                provider = provider,
                model = model,
                baseUrl = baseUrl,
                apiKey = effectiveKeys.firstOrNull(),
                apiKeys = effectiveKeys,
                requestsPerMinutePerKey = requestsPerMinutePerKey.coerceAtLeast(0),
                protocol = inferProtocol(baseUrl, provider),
                temperature = temperature,
                maxTokens = maxTokens,
                topP = topP,
                reasoningMode = when (reasoningMode?.lowercase()) {
                    "disabled" -> ReasoningMode.DISABLED
                    "enabled" -> ReasoningMode.ENABLED
                    else -> ReasoningMode.AUTO
                },
                reasoningEffort = when (reasoningEffort?.lowercase()) {
                    "low" -> ReasoningEffort.LOW
                    "medium" -> ReasoningEffort.MEDIUM
                    "high" -> ReasoningEffort.HIGH
                    else -> null
                },
                toolCallMode = when (toolCallMode?.lowercase()) {
                    "json" -> ToolCallMode.JSON_TEXT
                    "disabled" -> ToolCallMode.DISABLED
                    else -> ToolCallMode.NATIVE
                },
                contextTokens = contextTokens,
                customHeaders = customHeaders,
                pureChatMode = pureChatMode,
                visionEnabled = visionEnabled,
            )
        }

        /** 解析多行自定义请求头（格式为 Key: Value，支持忽略空行与 # 注释） */
        fun parseCustomHeaders(raw: String): List<Pair<String, String>> {
            if (raw.isBlank()) return emptyList()
            return raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val colonIndex = line.indexOf(':')
                    if (colonIndex > 0) {
                        val name = line.substring(0, colonIndex).trim()
                        val value = line.substring(colonIndex + 1).trim()
                        if (name.isNotEmpty() && value.isNotEmpty()) name to value else null
                    } else null
                }
                .toList()
        }

        /** Anthropic 协议自动识别：官方域名或厂商名含 anthropic/claude。 */
        fun inferProtocol(baseUrl: String, provider: String): ApiProtocol {
            val host = runCatching { java.net.URI(baseUrl.trim()).host?.lowercase() }.getOrNull().orEmpty()
            val providerLower = provider.lowercase()
            return if (host == "api.anthropic.com" ||
                providerLower.contains("anthropic") ||
                providerLower.contains("claude")
            ) {
                ApiProtocol.ANTHROPIC
            } else {
                ApiProtocol.OPENAI
            }
        }

        fun formatHttpErrorMessage(code: Int, rawBody: String): String {
            val errorMsg = runCatching {
                val obj = Json.parseToJsonElement(rawBody) as? JsonObject
                val err = obj?.get("error") as? JsonObject
                err?.get("message")?.let { it as? JsonPrimitive }?.contentOrNull
            }.getOrNull()?.trim() ?: rawBody.take(300).trim()

            val lowerMsg = errorMsg.lowercase()
            return when {
                code == 403 && (lowerMsg.contains("free quota") || lowerMsg.contains("quota exhausted") || lowerMsg.contains("free tier")) ->
                    "API 免费额度已耗尽 (HTTP 403)：请前往模型服务商控制台充值、关闭免费层限制，或在太墟中切换其他可用模型。"
                code == 401 || lowerMsg.contains("invalid api key") || lowerMsg.contains("unauthorized") ->
                    "API Key 无效或未授权 (HTTP 401)：请在模型设置中检查并更新该服务商的 API Key。"
                code == 429 || lowerMsg.contains("rate limit") || lowerMsg.contains("insufficient_quota") || lowerMsg.contains("quota") ->
                    "API 额度已用尽或请求频率超限 (HTTP $code)：$errorMsg"
                code == 404 ->
                    "模型名称或 API 地址不存在 (HTTP 404)：请检查模型名称是否拼写正确。"
                errorMsg.isNotBlank() ->
                    "LLM 请求失败 (HTTP $code)：$errorMsg"
                else ->
                    "LLM 请求失败 (HTTP $code)"
            }
        }

        internal fun rateLimitException(code: Int, rawBody: String, retryAfter: String?): LlmRateLimitException {
            val message = formatHttpErrorMessage(code, rawBody)
            val lower = message.lowercase()
            val quotaExhausted = listOf(
                "insufficient_quota",
                "quota exceeded",
                "allocated quota",
                "quota exhausted",
                "resource_exhausted",
                "余额",
                "额度已用尽",
            ).any { it in lower }
            val retrySeconds = retryAfter?.trim()?.toLongOrNull()?.coerceIn(1L, 300L)
                ?: runCatching {
                    ZonedDateTime.parse(retryAfter?.trim().orEmpty(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toEpochSecond() - System.currentTimeMillis() / 1000L
                }.getOrNull()?.takeIf { it > 0 }?.coerceAtMost(300L)
            return LlmRateLimitException(message, retrySeconds, quotaExhausted)
        }

        private const val READ_TIMEOUT_MS = 3 * 60 * 1000L
        internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 工具 JSON Schema，与 ToolExecutor 的参数契约一一对应。 */
        val TOOLS: List<ApiToolDefinition> = listOf(
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "history.search",
                    description = "在当前会话的完整历史中按关键词检索旧消息。压缩摘要缺少关键细节时先用它定位消息，再用 history.read 读取原文。只读，不修改历史。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"query":{"type":"string","description":"要检索的关键词、文件名、错误信息或约束"},"limit":{"type":"integer","minimum":1,"maximum":20,"description":"最多返回命中条数，默认 8"}},"required":["query"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "history.read",
                    description = "读取当前会话某条历史消息的原文。使用 history.search 返回的 message_id，或使用稳定的历史 index。单条返回有大小上限。只读。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"message_id":{"type":"string","description":"history.search 返回的消息 ID"},"index":{"type":"integer","minimum":0,"description":"历史消息的 0 起始索引；与 message_id 二选一"}},"anyOf":[{"required":["message_id"]},{"required":["index"]}]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "read",
                    description = "读取文件内容（UTF-8，单文件上限 1MB）。路径可用相对路径或以 /workspace/ 开头。优先用它检查文件内容，而不是用 cat/sed。大文件用 offset（1 起始行号）和 limit（行数）分页读取，返回头部会标注总行数与当前窗口。若文件不存在或读取失败，用 base 的 ls/find 定位。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"path":{"type":"string","description":"文件路径"},"offset":{"type":"integer","description":"起始行号（1 起始），可选"},"limit":{"type":"integer","description":"读取的最大行数，可选"}},"required":["path"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "write",
                    description = "创建或完全覆盖文件内容，自动创建父目录。只用于新文件或完整重写；若只想修改局部内容，请改用 edit。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "edit",
                    description = "在文件中做精确文本替换。oldText 必须与原文逐字匹配且唯一，一次可传多个替换，但每个不能重叠或嵌套。oldText 重复或匹配多处会失败——先 read 确认内容再改。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"path":{"type":"string"},"oldText":{"type":"string"},"newText":{"type":"string"}},"required":["path","oldText","newText"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "base",
                    description = "在 Debian Linux 沙箱中执行前台 shell 命令，返回退出码/stdout/stderr。用于安装软件、运行脚本、检查状态和执行构建。默认超时由用户在 Agent 设置中配置；可用 timeout_seconds 为单次调用指定 1-3600 秒。常驻服务不要使用 nohup 或 &，应改用 process 工具。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"command":{"type":"string","description":"要执行的 shell 命令"},"cwd":{"type":"string","description":"工作目录；关联工作区时默认使用工作区，否则为 /root"},"timeout_seconds":{"type":"integer","minimum":1,"maximum":3600,"description":"可选的单次超时秒数；省略时使用用户设置的默认值"}},"required":["command"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "process",
                    description = "管理需要跨工具调用持续运行的 PRoot 后台进程。start 的命令必须以前台模式运行，由 TaiXu 托管生命周期；不要使用 nohup、& 或自行 daemonize。使用 status/logs/list/stop 查询和停止。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"action":{"type":"string","enum":["start","status","logs","list","stop"]},"id":{"type":"string","pattern":"^[a-z0-9][a-z0-9._-]{0,63}$","description":"稳定的进程标识；list 不需要"},"command":{"type":"string","description":"start 时必需，需以前台模式持续运行"},"cwd":{"type":"string","description":"start 的工作目录"},"tail_lines":{"type":"integer","minimum":1,"maximum":500,"description":"logs 返回的末尾行数，默认 120"}},"required":["action"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "download",
                    description = "使用内置 HTTPS 下载器把远程文件保存到工作区。支持 HTTP Range 断点续传、自动重试、最大文件大小限制和可选 SHA-256 校验；当前为单连接续传，不是多线程分片。destination 必须位于当前工作区内，不要填写宿主机绝对路径。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"url":{"type":"string","description":"HTTPS 下载地址"},"destination":{"type":"string","description":"工作区内目标路径，例如 dist/tool.tar.gz"},"sha256":{"type":"string","description":"可选 SHA-256 十六进制摘要"},"max_attempts":{"type":"integer","description":"可选最大尝试次数，1-10，默认 3"},"max_bytes":{"type":"integer","description":"可选最大文件大小（字节），默认 1 GiB，最大 4 GiB"}},"required":["url","destination"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "memory",
                    description = "长期语义与事实记忆管理：持久化记录用户的长期偏好、项目架构规范、稳定事实。支持 action: save, query, list, delete。scope: global, project, session。kind: preference, rule, fact, project_info。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"action":{"type":"string","enum":["save","query","list","delete"],"description":"操作动作"},"key":{"type":"string","description":"记忆键名"},"value":{"type":"string","description":"记忆内容（save 必需）"},"kind":{"type":"string","enum":["preference","rule","fact","project_info"],"description":"记忆类型"},"scope":{"type":"string","enum":["global","project","session"],"description":"记忆作用域"}},"required":["action"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "plan",
                    description = "结构化多步骤任务规划管理：拆解长任务子步骤并持续跟踪推进进度。支持 action: replace_active, get_active, advance, clear_active。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"action":{"type":"string","enum":["replace_active","get_active","advance","clear_active"],"description":"规划操作动作"},"goal":{"type":"string","description":"任务总体目标"},"steps":{"type":"array","description":"规划步骤列表（每个步骤包含 id, title, status: pending|in_progress|completed|failed）","items":{"type":"object","properties":{"id":{"type":"string"},"title":{"type":"string"},"status":{"type":"string"}},"required":["id","title","status"]}},"status":{"type":"string","description":"任务整体状态"}},"required":["action"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "scratchpad",
                    description = "任务/会话局部工作草稿便签：临时记录排查假说、分析草稿、当前子目标与阻塞点（Blockers）。支持 action: save, get, list, delete, clear。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"action":{"type":"string","enum":["save","get","list","delete","clear"],"description":"草稿操作动作"},"key":{"type":"string","description":"草稿键名"},"value":{"type":"string","description":"草稿内容（save 必需）"}},"required":["action"]}""",
                    ).jsonObject,
                ),
            ),
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "invoke_subagent",
                    description = "并发派发一个或多个专业角色子智能体（Subagents）执行调研、编写、编译或测试等特定子任务，并在完成后汇总结构化结论。每个子智能体在专属子会话中独立运行。",
                    parameters = Json.parseToJsonElement(
                        """{"type":"object","properties":{"subagents":{"type":"array","description":"子任务列表","items":{"type":"object","properties":{"taskName":{"type":"string","description":"子任务名称（如: 数据库结构调研 / 编写测试用例）"},"role":{"type":"string","description":"子智能体角色（如: researcher / coder / tester）"},"prompt":{"type":"string","description":"详细的任务指令与要求"}},"required":["taskName","role","prompt"]}}},"required":["subagents"]}""",
                    ).jsonObject,
                ),
            ),
        )

        /** 组装静态基础工具 + 动态 MCP 插件工具 */
        fun buildDynamicTools(mcpTools: List<top.wkbin.taixu.core.model.McpToolInfo> = emptyList()): List<ApiToolDefinition> {
            val list = TOOLS.toMutableList()
            mcpTools.forEach { mcp ->
                val fullToolName = "mcp__${mcp.serverId}__${mcp.name}"
                val params = runCatching {
                    Json.parseToJsonElement(mcp.parametersJson).jsonObject
                }.getOrDefault(JsonObject(emptyMap()))
                list.add(
                    ApiToolDefinition(
                        function = ApiFunctionDefinition(
                            name = fullToolName,
                            description = "【MCP 插件: ${mcp.serverName}】${mcp.description}",
                            parameters = params,
                        ),
                    ),
                )
            }
            return list
        }

        /**
         * 把工具定义转成给模型看的 JSON 文本描述（JSON_TEXT 工具调用模式使用）。
         * 每个工具一行 JSON，格式与 OpenAI function calling 一致，模型按 name/parameters 输出调用。
         */
        fun buildToolsTextDescription(tools: List<ApiToolDefinition>): String {
            if (tools.isEmpty()) return "（无可用工具）"
            return tools.joinToString("\n") { tool ->
                val fn = tool.function
                buildString {
                    append("- ").append(fn.name).append(": ").append(fn.description)
                    append("\n  参数 JSON Schema: ").append(fn.parameters.toString())
                }
            }
        }
    }
}
