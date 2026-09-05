package top.wkbin.taixu.runtime.bridge

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.runtime.privilege.PrivilegeManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import top.wkbin.taixu.runtime.bridge.adb.EmbeddedAdbManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宿主桥接 (Host Bridge) — 沙箱与 Android 宿主之间的 localhost HTTP 通道。
 *
 * 沙箱（PRoot）与 App 共享网络命名空间，因此沙箱内可通过 `127.0.0.1:7980` 访问本服务。
 *
 * 提供以下端点：
 * - `GET  /api/health`       — 桥接健康检查 + 当前特权状态与 ADB 状态
 * - `POST /api/install-apk`  — 在宿主侧安装 APK（无线 ADB 静默安装或系统安装器）
 * - `POST /api/shell`        — 在宿主侧执行 Shell 命令（Shizuku/Root 或内置无线 ADB）
 * - `POST /api/logcat`       — 抓取目标应用或系统 Logcat 日志
 *
 * 安全：仅监听 127.0.0.1；所有写操作需 Bearer Token 认证（token 写入 /opt/taixu/.bridge-key）。
 */
@Singleton
class HostBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger,
    private val privilegeManager: PrivilegeManager,
    private val embeddedAdbManager: EmbeddedAdbManager,
) {
    companion object {
        const val BRIDGE_PORT = 7980
        const val BRIDGE_HOST = "127.0.0.1"
        const val BRIDGE_URL = "http://127.0.0.1:7980"
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
        private const val READ_TIMEOUT_MS = 10_000
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 桥接 API 密钥——构造时生成，始终可用（即使桥接未启动）。 */
    val bridgeKey: String = UUID.randomUUID().toString().replace("-", "")

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val bridgeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 启动 HTTP 监听。重复调用安全，且绑定操作不会被并发启动打断。 */
    @Synchronized
    fun start() {
        if (_isRunning.value) return
        try {
            val socket = ServerSocket(BRIDGE_PORT, 16, InetAddress.getByName(BRIDGE_HOST))
            serverSocket = socket
            _isRunning.value = true
            serverJob = bridgeScope.launch {
                logger.i("HostBridge listening on $BRIDGE_HOST:$BRIDGE_PORT")
                while (isActive) {
                    try {
                        val client = socket.accept()
                        launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (isActive) logger.w("HostBridge accept error", e)
                    }
                }
            }
        } catch (e: Exception) {
            serverSocket?.runCatching { close() }
            serverSocket = null
            _isRunning.value = false
            logger.e("Failed to start HostBridge on port $BRIDGE_PORT", e)
        }
    }

    /** 停止 HTTP 监听。 */
    @Synchronized
    fun stop() {
        serverJob?.cancel()
        serverSocket?.runCatching { close() }
        serverSocket = null
        _isRunning.value = false
        logger.i("HostBridge stopped")
    }

    // ============================ 请求处理 ============================

    private suspend fun handleClient(client: Socket) {
        try {
            client.soTimeout = READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val request = parseHttpRequest(reader) ?: run {
                writeResponse(client, 400, errorJson("Failed to parse HTTP request"))
                return
            }
            val response = route(request)
            writeResponse(client, response.status, response.body)
        } catch (e: Exception) {
            runCatching { writeResponse(client, 500, errorJson("Internal error: ${e.message}")) }
        } finally {
            runCatching { client.close() }
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class HttpResponse(val status: Int, val body: String)

    private fun parseHttpRequest(reader: BufferedReader): HttpRequest? {
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ", limit = 3)
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val path = parts[1]

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim().lowercase()
                val value = line.substring(colonIdx + 1).trim()
                headers[key] = value
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0 && contentLength <= MAX_BODY_BYTES) {
            val chars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(chars, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(chars, 0, read)
        } else {
            ""
        }

        return HttpRequest(method, path, headers, body)
    }

    private suspend fun route(request: HttpRequest): HttpResponse {
        // 认证检查（health 端点免认证）
        val isHealth = request.path.startsWith("/api/health")
        if (!isHealth && !checkAuth(request.headers)) {
            return HttpResponse(401, errorJson("Unauthorized: invalid or missing API key"))
        }

        return when {
            request.method == "GET" && request.path.startsWith("/api/health") ->
                handleHealth()
            request.method == "POST" && request.path.startsWith("/api/install-apk") ->
                handleInstallApk(request.body)
            request.method == "POST" && request.path.startsWith("/api/shell") ->
                handleShell(request.body)
            request.method == "POST" && request.path.startsWith("/api/logcat") ->
                handleLogcat(request.body)
            else ->
                HttpResponse(404, errorJson("Not found: ${request.method} ${request.path}"))
        }
    }

    private fun checkAuth(headers: Map<String, String>): Boolean {
        val auth = headers["authorization"] ?: return false
        val token = auth.removePrefix("Bearer ").removePrefix("bearer ").trim()
        return token == bridgeKey
    }

    // ============================ 端点实现 ============================

    private suspend fun handleHealth(): HttpResponse {
        val info = privilegeManager.getPrivilegeInfo()
        val adbState = embeddedAdbManager.state.value
        val adbConnected = adbState is EmbeddedAdbManager.ConnectionState.Connected
        val body = buildJsonObject {
            put("status", "ok")
            put("bridge", "1.0")
            put("port", BRIDGE_PORT)
            put("mode", info.mode.id)
            put("modeLabel", info.mode.shortLabel)
            put("modeActive", info.modeActive || adbConnected)
            put("shizuku", info.shizukuAvailable)
            put("root", info.rootAvailable)
            put("adb", adbConnected)
            put("adbPort", (adbState as? EmbeddedAdbManager.ConnectionState.Connected)?.port ?: 0)
            put("shellSupported", info.shizukuAvailable || info.rootAvailable || adbConnected)
        }.toString()
        return HttpResponse(200, body)
    }

    private suspend fun handleInstallApk(body: String): HttpResponse {
        val apkPath = try {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["path"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            return HttpResponse(400, errorJson("Invalid JSON body: ${e.message}"))
        }

        if (apkPath.isNullOrBlank()) {
            return HttpResponse(400, errorJson("Missing 'path' field"))
        }

        // 将沙箱路径映射到宿主路径
        val hostPath = resolveSandboxPath(apkPath)
        val apkFile = File(hostPath)
        if (!apkFile.isFile) {
            return HttpResponse(404, errorJson("APK file not found: $apkPath (resolved: $hostPath)"))
        }
        if (!apkFile.name.endsWith(".apk", ignoreCase = true)) {
            return HttpResponse(400, errorJson("File does not have .apk extension: ${apkFile.name}"))
        }

        // 优先通过内置无线 ADB 静默安装（无需点击系统弹窗确认）
        val adbState = embeddedAdbManager.state.value
        if (adbState is EmbeddedAdbManager.ConnectionState.Connected) {
            val installResult = embeddedAdbManager.installApk(apkFile)
            if (installResult.isSuccess) {
                logger.i("HostBridge: APK installed via wireless ADB for $apkPath")
                return HttpResponse(200, buildJsonObject {
                    put("success", true)
                    put("message", "APK 已通过无线 ADB 静默安装成功")
                    put("package", apkFile.name)
                    put("channel", "wireless-adb")
                }.toString())
            }
        }

        // 复制到 cache 目录（FileProvider 需要 cache-path 下的文件）
        val installDir = File(context.cacheDir, "bridge-installs").apply { mkdirs() }
        val cachedApk = File(installDir, "taixu-bridge-${System.currentTimeMillis()}.apk")
        try {
            apkFile.copyTo(cachedApk, overwrite = true)
        } catch (e: Exception) {
            return HttpResponse(500, errorJson("Failed to copy APK: ${e.message}"))
        }

        // 通过 FileProvider + Intent 调起系统安装器
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cachedApk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri("APK", uri)
            }
            context.startActivity(intent)
            logger.i("HostBridge: APK install triggered for $apkPath")
            return HttpResponse(200, buildJsonObject {
                put("success", true)
                put("message", "安装请求已发送，请在系统弹窗中确认安装")
                put("package", apkFile.name)
                put("channel", "system-intent")
            }.toString())
        } catch (e: Exception) {
            cachedApk.delete()
            return HttpResponse(500, errorJson("Failed to launch installer: ${e.message}"))
        }
    }

    private suspend fun handleShell(body: String): HttpResponse {
        val obj = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return HttpResponse(400, errorJson("Invalid JSON body: ${e.message}"))
        }

        val command = obj["command"]?.jsonPrimitive?.content
        if (command.isNullOrBlank()) {
            return HttpResponse(400, errorJson("Missing 'command' field"))
        }

        val info = privilegeManager.getPrivilegeInfo()
        val responseJson = if (info.modeActive && (info.shizukuAvailable || info.rootAvailable)) {
            val result = privilegeManager.executeShellCommand(command)
            buildJsonObject {
                put("success", result.success)
                put("exitCode", result.exitCode)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("channel", "privilege")
            }
        } else {
            val explicitPort = obj["port"]?.jsonPrimitive?.content?.toIntOrNull()
            val adbResult = embeddedAdbManager.executeShell(command, explicitPort)
            buildJsonObject {
                put("success", adbResult.success)
                put("exitCode", adbResult.exitCode ?: if (adbResult.success) 0 else 1)
                put("stdout", if (adbResult.success) adbResult.output else "")
                put("stderr", if (!adbResult.success) adbResult.output else "")
                put("channel", "wireless-adb")
            }
        }
        return HttpResponse(200, responseJson.toString())
    }

    private suspend fun handleLogcat(body: String): HttpResponse {
        val obj = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return HttpResponse(400, errorJson("Invalid JSON body: ${e.message}"))
        }

        val clear = obj["clear"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        if (clear) {
            val clearResult = embeddedAdbManager.clearLogcat()
            return HttpResponse(200, buildJsonObject {
                put("success", clearResult.success)
                put("message", if (clearResult.success) "Logcat buffer cleared" else clearResult.output)
            }.toString())
        }

        val pkg = obj["package"]?.jsonPrimitive?.content?.trim().orEmpty()
        val tag = obj["tag"]?.jsonPrimitive?.content?.trim().orEmpty()
        val priority = obj["priority"]?.jsonPrimitive?.content?.trim()?.uppercase()?.firstOrNull() ?: 'V'
        val keyword = obj["keyword"]?.jsonPrimitive?.content?.trim().orEmpty()
        val lines = obj["lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 200
        val explicitPort = obj["port"]?.jsonPrimitive?.content?.toIntOrNull()

        val result = embeddedAdbManager.captureLogcat(
            EmbeddedAdbManager.LogcatRequest(
                packageName = pkg,
                tag = tag,
                priority = priority,
                keyword = keyword,
                lines = lines,
            ),
            explicitPort = explicitPort,
        )

        return HttpResponse(200, buildJsonObject {
            put("success", result.success)
            put("exitCode", result.exitCode ?: if (result.success) 0 else 1)
            put("output", result.output)
        }.toString())
    }

    // ============================ 工具方法 ============================

    /**
     * 将沙箱内路径映射到宿主路径。
     * /sdcard/Download/app.apk → /storage/emulated/0/Download/app.apk
     */
    private fun resolveSandboxPath(sandboxPath: String): String {
        return when {
            sandboxPath.startsWith("/sdcard/") ->
                "/storage/emulated/0/${sandboxPath.removePrefix("/sdcard/")}"
            sandboxPath == "/sdcard" ->
                "/storage/emulated/0"
            sandboxPath.startsWith("/storage/emulated/0/") ->
                sandboxPath // 已经是宿主路径
            else -> sandboxPath // 原样返回（可能是宿主绝对路径）
        }
    }

    private fun writeResponse(client: Socket, status: Int, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val output: OutputStream = client.getOutputStream()
        output.write("HTTP/1.1 $status $statusText\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray(Charsets.US_ASCII))
        output.write("Connection: close\r\n".toByteArray(Charsets.US_ASCII))
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

    private fun errorJson(message: String): String = buildJsonObject {
        put("success", false)
        put("error", message)
    }.toString()
}
