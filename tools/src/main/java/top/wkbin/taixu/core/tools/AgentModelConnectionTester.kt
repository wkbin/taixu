package top.wkbin.taixu.core.tools

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class AgentModelConnectionTester @Inject constructor(private val http: OkHttpClient) {
    suspend fun test(
        baseUrl: String,
        model: String,
        apiKey: String?,
        useResponsesApi: Boolean = false,
        protocol: ProviderProtocol = ProviderProtocol.OPENAI,
        providerName: String? = null,
    ) = withContext(Dispatchers.IO) {
        val cleanBaseUrl = ProviderEndpointPolicy.normalizeUrl(baseUrl)
        require(cleanBaseUrl.isNotBlank() && ProviderEndpointPolicy.isSafeBaseUrl(cleanBaseUrl)) { "Base URL 不安全或为空" }
        val isAnthropic = protocol == ProviderProtocol.ANTHROPIC ||
            cleanBaseUrl.trimEnd('/').contains("api.anthropic.com") ||
            providerName?.contains("anthropic", ignoreCase = true) == true ||
            providerName?.contains("claude", ignoreCase = true) == true
        if (model.isBlank()) {
            testModelCatalog(cleanBaseUrl, apiKey, isAnthropic)
        } else if (isAnthropic) {
            testAnthropic(cleanBaseUrl, model, apiKey)
        } else if (useResponsesApi) {
            testResponses(cleanBaseUrl, model, apiKey)
        } else {
            testOpenAi(cleanBaseUrl, model, apiKey)
        }
    }

    private fun testModelCatalog(baseUrl: String, apiKey: String?, isAnthropic: Boolean) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/models")
            .apply {
                if (isAnthropic) {
                    header("anthropic-version", "2023-06-01")
                    if (!apiKey.isNullOrBlank()) header("x-api-key", apiKey)
                } else if (!apiKey.isNullOrBlank()) {
                    header("Authorization", "Bearer $apiKey")
                }
            }
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            check(response.isSuccessful) { "连接失败 HTTP ${response.code}：${text.take(240)}" }
        }
    }

    private fun testOpenAi(baseUrl: String, model: String, apiKey: String?) {
        val body = """{"model":"${model.replace("\\", "\\\\").replace("\"", "\\\"")}","messages":[{"role":"user","content":"Reply with OK"}],"max_tokens":8}"""
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Content-Type", "application/json")
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .post(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            check(response.isSuccessful) { "连接失败 HTTP ${response.code}：${text.take(240)}" }
        }
    }

    /** 按"使用 Responses API"开关测试 /responses 端点（与运行时使用同一协议结构）。 */
    private fun testResponses(baseUrl: String, model: String, apiKey: String?) {
        val escapedModel = model.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"model":"$escapedModel","input":[{"role":"user","content":[{"type":"input_text","text":"Reply with OK"}]}],"max_output_tokens":8}"""
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/responses")
            .header("Content-Type", "application/json")
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .post(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            check(response.isSuccessful) { "连接失败 HTTP ${response.code}：${text.take(240)}" }
        }
    }

    private fun testAnthropic(baseUrl: String, model: String, apiKey: String?) {
        val body = """{"model":"${model.replace("\\", "\\\\").replace("\"", "\\\"")}","max_tokens":16,"messages":[{"role":"user","content":"Reply with OK"}]}"""
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/messages")
            .header("Content-Type", "application/json")
            .header("anthropic-version", "2023-06-01")
            .apply { if (!apiKey.isNullOrBlank()) header("x-api-key", apiKey) }
            .post(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            check(response.isSuccessful) { "连接失败 HTTP ${response.code}：${text.take(240)}" }
        }
    }
}
