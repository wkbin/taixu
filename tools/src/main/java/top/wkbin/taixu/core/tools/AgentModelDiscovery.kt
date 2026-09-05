package top.wkbin.taixu.core.tools

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class AgentModelDiscovery @Inject constructor(
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(
        provider: AgentProviderDefinition,
        baseUrl: String,
        apiKey: String?,
    ): List<String> = withContext(Dispatchers.IO) {
        val cleanBaseUrl = ProviderEndpointPolicy.normalizeUrl(baseUrl)
        val url = when {
            provider.id == "custom" -> "${cleanBaseUrl.trimEnd('/')}/models"
            cleanBaseUrl.isNotBlank() && cleanBaseUrl.trimEnd('/') != provider.baseUrl.trimEnd('/') -> "${cleanBaseUrl.trimEnd('/')}/models"
            provider.modelsUrl.isNotBlank() -> provider.modelsUrl
            else -> "${provider.baseUrl.trimEnd('/')}/models"
        }
        val targetUrl = ProviderEndpointPolicy.normalizeUrl(url)
        require(targetUrl.isNotBlank() && ProviderEndpointPolicy.isSafeBaseUrl(targetUrl)) { "模型发现地址不安全或为空" }
        val isAnthropic = provider.protocol == ProviderProtocol.ANTHROPIC ||
            targetUrl.contains("api.anthropic.com") ||
            provider.name.contains("anthropic", ignoreCase = true) ||
            provider.name.contains("claude", ignoreCase = true)
        val request = Request.Builder().url(targetUrl)
            .apply {
                when {
                    isAnthropic -> {
                        // Anthropic 模型列表接口用 x-api-key 而非 Bearer
                        if (!apiKey.isNullOrBlank()) header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    else -> if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
                }
            }
            .get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw ModelDiscoveryResponseException("获取模型失败 HTTP ${response.code}：${body.take(200)}")
            }
            val mediaType = response.body.contentType()
            if (looksLikeHtml(body)) {
                throw ModelDiscoveryResponseException(
                    "模型接口返回了网页而不是 JSON（HTTP ${response.code}，Content-Type: ${mediaType ?: "未知"}）",
                )
            }
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
                .getOrElse { cause -> throw ModelDiscoveryResponseException("模型接口返回的 JSON 无法解析", cause) }
                ?: throw ModelDiscoveryResponseException("模型接口返回的 JSON 顶层不是对象")
            val ids = runCatching {
                root["data"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                    ?: root["models"]?.jsonArray?.mapNotNull { item ->
                        val obj = item.jsonObject
                        obj["name"]?.jsonPrimitive?.content ?: obj["model"]?.jsonPrimitive?.content
                    }.orEmpty()
            }.getOrElse { cause ->
                throw ModelDiscoveryResponseException("模型接口返回的 JSON 结构不符合预期", cause)
            }
            ids.filter(::isAgentModel).distinct().sorted()
        }
    }

    private fun looksLikeHtml(body: String): Boolean {
        val prefix = body.trimStart().take(32).lowercase()
        return prefix.startsWith("<!doctype html") || prefix.startsWith("<html")
    }

    private fun isAgentModel(id: String): Boolean {
        val value = id.lowercase()
        return MEDIA_OR_NON_CHAT.none { token -> value.contains(token) }
    }

    private companion object {
        val MEDIA_OR_NON_CHAT = listOf(
            "embedding", "embed-", "rerank", "whisper", "tts", "speech", "audio",
            "image", "imagen", "dall-e", "flux", "stable-diffusion", "recraft",
            "video", "veo", "sora", "moderation", "guard", "classifier",
        )
    }
}

class ModelDiscoveryResponseException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
