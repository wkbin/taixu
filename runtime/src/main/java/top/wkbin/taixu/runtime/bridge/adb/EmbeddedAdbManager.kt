package top.wkbin.taixu.runtime.bridge.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path.Companion.toPath
import top.wkbin.taixu.core.datastore.RuntimePreferences

/**
 * 应用内置的无线 ADB 客户端。
 *
 * Android 无线调试端口会在每次开启时变化，因此连接和配对端口均由 mDNS 自动发现。
 * ADB 私钥持久化在应用私有目录；只要用户未在系统设置中撤销授权或清除应用数据，
 * 成功输入一次配对码后即可在后续启动时自动重连。
 *
 * mDNS 发现使用 Android NsdManager 经典 API（discoverServices + resolveService），
 * 不使用 Android 14 的 registerServiceInfoCallback，避免已知的 onServiceUpdated 无限递归
 * StackOverflowError（NSD Binder 回调在部分设备上会对同一服务连续触发 onServiceUpdated，
 * 且调用栈不跨线程，导致同步无限递归）。所有回调均通过协程调度器异步处理。
 */
@Singleton
class EmbeddedAdbManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: RuntimePreferences,
    private val pathManager: top.wkbin.taixu.runtime.RuntimePathManager,
) {
    sealed interface ConnectionState {
        data object Disconnected : ConnectionState
        data object Discovering : ConnectionState
        data object Pairing : ConnectionState
        data object Connecting : ConnectionState
        data class Connected(val host: String, val port: Int) : ConnectionState
        data class Failed(val message: String) : ConnectionState
    }

    data class DiscoveryState(
        val running: Boolean = false,
        val pairingEndpoints: List<Endpoint> = emptyList(),
        val connectEndpoints: List<Endpoint> = emptyList(),
    )

    data class Endpoint(val name: String, val host: String, val port: Int)

    data class ShellOutcome(
        val exitCode: Int?,
        val output: String,
        val success: Boolean,
    )

    data class LogcatRequest(
        val packageName: String = "",
        val tag: String = "",
        val priority: Char = 'V',
        val keyword: String = "",
        val lines: Int = 200,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val nsdManager: NsdManager =
        context.applicationContext.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val multicastLock = wifiManager.createMulticastLock("taixu-wireless-adb-mdns").apply {
        setReferenceCounted(false)
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val _discovery = MutableStateFlow(DiscoveryState())
    val discovery: StateFlow<DiscoveryState> = _discovery.asStateFlow()

    private var client: Kadb? = null

    // 防止 resolveService 并发（NsdManager 不允许同时多个 resolve）
    private val resolving = AtomicBoolean(false)

    // 已发现的端点缓存；key = serviceName
    private val pairingEndpointMap = ConcurrentHashMap<String, Endpoint>()
    private val connectEndpointMap = ConcurrentHashMap<String, Endpoint>()

    // NSD 监听器引用，stop 时需要注销
    private var pairingDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var connectDiscoveryListener: NsdManager.DiscoveryListener? = null
    private val discoveryStarted = AtomicBoolean(false)

    private val keyDir: File get() = File(context.filesDir, "adb").apply { mkdirs() }
    private val privateKeyFile: File get() = File(keyDir, "kadb-private-key.pem")

    init {
        KadbCert.configure(OkioFilePrivateKeyStore(privateKeyFile.absolutePath.toPath()))
        KadbCert.ensureReady()
        startDiscovery()
    }

    // ── 发现 ────────────────────────────────────────────────────────────────

    fun startDiscovery() {
        if (discoveryStarted.getAndSet(true)) return
        runCatching {
            if (!multicastLock.isHeld) multicastLock.acquire()
            _state.value = ConnectionState.Discovering
            _discovery.value = DiscoveryState(running = true)

            pairingDiscoveryListener = buildDiscoveryListener(isPairing = true)
            connectDiscoveryListener = buildDiscoveryListener(isPairing = false)

            nsdManager.discoverServices(
                SERVICE_PAIRING,
                NsdManager.PROTOCOL_DNS_SD,
                pairingDiscoveryListener,
            )
            nsdManager.discoverServices(
                SERVICE_CONNECT,
                NsdManager.PROTOCOL_DNS_SD,
                connectDiscoveryListener,
            )
        }.onFailure { error ->
            discoveryStarted.set(false)
            _state.value = ConnectionState.Failed(error.userMessage("无法启动 mDNS 自动发现"))
            Log.w(TAG, "startDiscovery failed", error)
        }
    }

    fun stopDiscovery() {
        if (!discoveryStarted.getAndSet(false)) return
        runCatching { pairingDiscoveryListener?.let { nsdManager.stopServiceDiscovery(it) } }
        runCatching { connectDiscoveryListener?.let { nsdManager.stopServiceDiscovery(it) } }
        pairingDiscoveryListener = null
        connectDiscoveryListener = null
        if (multicastLock.isHeld) multicastLock.release()
        _discovery.value = DiscoveryState(running = false)
    }

    /**
     * 构建 NsdManager.DiscoveryListener。
     *
     * 设计约束：所有回调通过 scope.launch 异步派发到协程中处理，
     * 回调体本身不调用任何 NSD API，彻底规避同步递归崩溃。
     */
    private fun buildDiscoveryListener(isPairing: Boolean): NsdManager.DiscoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD discovery started: $serviceType (pairing=$isPairing)")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // 仅做最简操作，真正逻辑异步处理
                scope.launch { onDiscoveryError("onStartDiscoveryFailed errorCode=$errorCode") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "onStopDiscoveryFailed errorCode=$errorCode")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // 立即返回；resolve 在协程里异步执行，绝不在此调用任何 NSD API
                scope.launch { resolveAsync(serviceInfo, isPairing) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                scope.launch {
                    val name = serviceInfo.serviceName ?: return@launch
                    if (isPairing) pairingEndpointMap.remove(name)
                    else connectEndpointMap.remove(name)
                    publishDiscoveryState()
                    Log.d(TAG, "NSD service lost: $name (pairing=$isPairing)")
                }
            }
        }

    /**
     * 异步 resolve NsdServiceInfo，获取 host + port。
     * 使用已废弃但在所有版本均稳定的 resolveService API，无 onServiceUpdated 递归问题。
     * 原子标志串行化保护：NsdManager 不支持并发 resolve。
     */
    @Suppress("DEPRECATION")
    private suspend fun resolveAsync(serviceInfo: NsdServiceInfo, isPairing: Boolean) =
        withContext(Dispatchers.IO) {
            // 若已有进行中的 resolve，跳过
            if (!resolving.compareAndSet(false, true)) return@withContext
            try {
                val resolved = resolveServiceSuspend(serviceInfo) ?: return@withContext
                val host = resolved.host?.hostAddress?.takeIf { it.isNotBlank() } ?: return@withContext
                val port = resolved.port.takeIf { it in VALID_PORTS } ?: return@withContext
                val name = resolved.serviceName ?: serviceInfo.serviceName ?: return@withContext
                val endpoint = Endpoint(name = name, host = host, port = port)
                if (isPairing) pairingEndpointMap[name] = endpoint
                else connectEndpointMap[name] = endpoint
                publishDiscoveryState()
                Log.i(TAG, "NSD resolved: $name @ $host:$port (pairing=$isPairing)")

                // 若已配对且发现了连接端点，自动尝试连接
                if (!isPairing && client == null && preferences.adbPairedOnce.first()) {
                    connect()
                }
            } finally {
                resolving.set(false)
            }
        }

    /**
     * 将 NsdManager.resolveService 包装为挂起函数。
     * 返回 null 表示 resolve 失败或超时。
     */
    @Suppress("DEPRECATION")
    private suspend fun resolveServiceSuspend(serviceInfo: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "resolveService failed errorCode=$errorCode for ${info.serviceName}")
                            if (cont.isActive) cont.resume(null) {}
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            if (cont.isActive) cont.resume(info) {}
                        }
                    },
                )
            }
        }

    private fun onDiscoveryError(reason: String) {
        Log.w(TAG, "mDNS discovery error: $reason")
        if (client == null) {
            _state.value = ConnectionState.Failed("mDNS 自动发现失败，请确认 Wi-Fi 与无线调试已开启")
        }
    }

    private fun publishDiscoveryState() {
        _discovery.value = DiscoveryState(
            running = discoveryStarted.get(),
            pairingEndpoints = pairingEndpointMap.values.toList(),
            connectEndpoints = connectEndpointMap.values.toList(),
        )
    }

    // ── 配对 ────────────────────────────────────────────────────────────────

    /** 使用 mDNS 发现到的配对端口完成真实的 TLS + SPAKE2 配对。 */
    suspend fun pair(pairingCode: String): Result<Unit> = pair(null, pairingCode)

    /** 显式端口仅作为 mDNS 不可用时的兼容入口。 */
    suspend fun pair(pairingPort: Int?, pairingCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(pairingCode.matches(PAIRING_CODE)) { "配对码必须是 6 位数字" }
            val endpoint = pairingEndpoint(pairingPort)
            _state.value = ConnectionState.Pairing
            Kadb.pair(endpoint.host, endpoint.port, pairingCode, "TaiXu")
            preferences.setAdbPairedOnce(true)
            Log.i(TAG, "wireless adb pairing succeeded via ${endpoint.host}:${endpoint.port}")

            val connectEndpoint = awaitConnectEndpoint(endpoint.host)
            if (connectEndpoint != null) {
                connectTo(connectEndpoint)
            } else {
                // 配对本身已完成且密钥已保存；连接服务稍后出现时会自动重连。
                _state.value = ConnectionState.Discovering
            }
        }.onFailure { error ->
            _state.value = ConnectionState.Failed(error.userMessage("无线 ADB 配对失败"))
            Log.w(TAG, "wireless adb pairing failed", error)
        }.map { }
    }

    // ── 连接 ────────────────────────────────────────────────────────────────

    // ── 连接 ────────────────────────────────────────────────────────────────

    /** 优先连接指定/当前 mDNS 端点；排除配对端口，且在局域网 IP 失败时自动尝试 127.0.0.1 回环。 */
    suspend fun connect(explicitPort: Int? = null): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val discovered = connectEndpointMap.values.toList()
                val pairingPorts = pairingEndpointMap.values.map { it.port }.toSet()
                val savedPort = preferences.adbWirelessPort.first()
                val candidates = buildList {
                    if (explicitPort != null && explicitPort in VALID_PORTS) {
                        add(Endpoint("explicit", LOOPBACK, explicitPort))
                    }
                    addAll(discovered)
                    if (savedPort in VALID_PORTS && savedPort !in pairingPorts && none { it.port == savedPort }) {
                        add(Endpoint("saved", LOOPBACK, savedPort))
                    }
                }
                require(candidates.isNotEmpty()) {
                    "未发现无线调试连接端口，请确认系统「无线调试」已开启，或手动填入 5 位连接端口"
                }
                var lastError: Throwable? = null
                for (endpoint in candidates) {
                    try {
                        connectTo(endpoint)
                        return@runCatching
                    } catch (error: Throwable) {
                        lastError = error
                    }
                }
                throw lastError ?: IllegalStateException("没有可用的无线调试端点")
            }.onFailure { error ->
                _state.value = ConnectionState.Failed(error.userMessage("无线 ADB 连接失败"))
                Log.w(TAG, "wireless adb connect failed", error)
            }.map { }
        }
    }

    private fun connectTo(endpoint: Endpoint) {
        closeClient()
        _state.value = ConnectionState.Connecting
        // 尝试用 endpoint.host 连接；若 host 不是 127.0.0.1 且失败，自动尝试 127.0.0.1 回环
        val hostsToTry = if (endpoint.host != LOOPBACK) listOf(endpoint.host, LOOPBACK) else listOf(LOOPBACK)
        var lastError: Throwable? = null
        for (h in hostsToTry) {
            val connected = try {
                Kadb.create(
                    host = h,
                    port = endpoint.port,
                    connectTimeout = CONNECT_TIMEOUT_MS,
                    socketTimeout = SHELL_TIMEOUT_MS,
                )
            } catch (e: Throwable) {
                lastError = e
                continue
            }
            try {
                val probe = connected.shell("echo ok")
                check(probe.output.trim() == "ok") { "ADB 链路探活失败" }
                client = connected
                _state.value = ConnectionState.Connected(h, endpoint.port)
                scope.launch {
                    preferences.setAdbWirelessPort(endpoint.port)
                    persistAdbEndpoint(h, endpoint.port)
                }
                Log.i(TAG, "embedded adb connected on $h:${endpoint.port}")
                return
            } catch (error: Throwable) {
                connected.close()
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("无法连接到端口 ${endpoint.port}")
    }

    fun disconnect() {
        closeClient()
        _state.value = ConnectionState.Disconnected
        scope.launch { clearAdbEndpoint() }
    }

    private fun persistAdbEndpoint(host: String, port: Int) {
        runCatching {
            val distroIds = pathManager.listInstalledDistroIds()
            for (distroId in distroIds) {
                val taixuRoot = pathManager.taixuRootDir(distroId)
                if (taixuRoot.exists()) {
                    File(taixuRoot, ".adb-port").writeText(port.toString())
                    File(taixuRoot, ".adb-host").writeText(host)
                }
            }
        }.onFailure { Log.w(TAG, "Failed to persist adb endpoint to sandboxes", it) }
    }

    private fun clearAdbEndpoint() {
        runCatching {
            val distroIds = pathManager.listInstalledDistroIds()
            for (distroId in distroIds) {
                val taixuRoot = pathManager.taixuRootDir(distroId)
                if (taixuRoot.exists()) {
                    File(taixuRoot, ".adb-port").delete()
                    File(taixuRoot, ".adb-host").delete()
                }
            }
        }.onFailure { Log.w(TAG, "Failed to clear adb endpoint from sandboxes", it) }
    }

    // ── Shell / Logcat ───────────────────────────────────────────────────────

    suspend fun executeShell(command: String, explicitPort: Int? = null): ShellOutcome {
        val currentClient = client
        val currentConnectedPort = (_state.value as? ConnectionState.Connected)?.port
        if (currentClient == null || (explicitPort != null && currentConnectedPort != explicitPort)) {
            val connection = if (explicitPort != null) connect(explicitPort) else connect()
            if (connection.isFailure) {
                return ShellOutcome(
                    null,
                    connection.exceptionOrNull()?.userMessage("内置 ADB 未就绪").orEmpty(),
                    false,
                )
            }
        }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val current = client
                try {
                    val response = requireNotNull(current).shell(command)
                    ShellOutcome(response.exitCode, response.allOutput, response.exitCode == 0)
                } catch (error: Throwable) {
                    closeClient()
                    _state.value = ConnectionState.Failed(error.userMessage("ADB 执行失败"))
                    ShellOutcome(null, error.userMessage("ADB 执行失败"), false)
                }
            }
        }
    }

    /** 抓取目标应用日志；关键词在本进程过滤，避免把用户文本拼进 shell。 */
    suspend fun captureLogcat(request: LogcatRequest, explicitPort: Int? = null): ShellOutcome {
        require(request.packageName.isBlank() || PACKAGE_NAME.matches(request.packageName)) { "包名格式不合法" }
        require(request.tag.isBlank() || LOGCAT_TAG.matches(request.tag)) { "Logcat Tag 格式不合法" }
        require(request.priority.uppercaseChar() in PRIORITIES) { "日志优先级无效" }
        val lines = request.lines.coerceIn(1, MAX_LOG_LINES)
        val tagArgs = if (request.tag.isBlank()) {
            shellQuote("*:${request.priority.uppercaseChar()}")
        } else {
            "${shellQuote("${request.tag}:${request.priority.uppercaseChar()}")} ${shellQuote("*:S")}"
        }
        val logcat = "/system/bin/logcat -d -v threadtime -t $lines"
        val command = if (request.packageName.isBlank()) {
            "$logcat $tagArgs"
        } else {
            "pid=\$(/system/bin/pidof ${shellQuote(request.packageName)} | /system/bin/cut -d' ' -f1); " +
                "if [ -z \"\$pid\" ]; then echo '目标应用未运行：${request.packageName}'; exit 3; fi; " +
                "$logcat --pid=\"\$pid\" $tagArgs"
        }
        val outcome = executeShell(command, explicitPort)
        if (!outcome.success || request.keyword.isBlank()) return outcome
        val filtered = outcome.output.lineSequence()
            .filter { it.contains(request.keyword, ignoreCase = true) }
            .joinToString("\n")
        return outcome.copy(output = filtered)
    }

    suspend fun clearLogcat(): ShellOutcome = executeShell("/system/bin/logcat -c")

    suspend fun installApk(apk: File): Result<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val current = client ?: error("内置 ADB 未连接，请先开启无线调试并完成配对")
                current.install(apk)
                "安装成功"
            }
        }
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    private fun pairingEndpoint(explicitPort: Int?): Endpoint {
        if (explicitPort != null) {
            require(explicitPort in VALID_PORTS) { "配对端口无效" }
            return pairingEndpointMap.values.firstOrNull { it.port == explicitPort }
                ?: Endpoint("manual", LOOPBACK, explicitPort)
        }
        return pairingEndpointMap.values.firstOrNull()
            ?: error("未发现配对端口：请在系统\"无线调试\"中打开\"使用配对码配对设备\"")
    }

    private suspend fun awaitConnectEndpoint(preferredHost: String): Endpoint? =
        connectEndpointMap.values.preferHost(preferredHost)
            ?: withTimeoutOrNull(CONNECT_DISCOVERY_TIMEOUT_MS) {
                discovery.first { it.connectEndpoints.isNotEmpty() }.connectEndpoints.preferHost(preferredHost)
            }

    private fun Collection<Endpoint>.preferHost(host: String): Endpoint? =
        firstOrNull { it.host == host } ?: firstOrNull()

    private fun closeClient() {
        runCatching { client?.close() }
        client = null
    }

    private fun Throwable.userMessage(prefix: String): String {
        val raw = message.orEmpty()
        val friendlyMessage = when {
            raw.contains("Failure in SSL library", ignoreCase = true) || raw.contains("ssl", ignoreCase = true) ->
                "TLS 握手失败（无线调试连接端口已变更，或误连接了配对端口）。请确认系统「无线调试」已开启并核对端口号，或重新完成配对。"
            raw.contains("Connection refused", ignoreCase = true) ->
                "连接被拒绝（端口未监听），请确认系统「无线调试」已开启，且端口号输入无误。"
            raw.contains("ETIMEDOUT", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
                "连接超时，请确认手机已连接 Wi-Fi 且无线调试处于开启状态。"
            raw.isNotBlank() -> raw
            else -> javaClass.simpleName
        }
        return "$prefix：$friendlyMessage"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val TAG = "EmbeddedAdb"
        const val LOOPBACK = "127.0.0.1"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val SHELL_TIMEOUT_MS = 30_000
        const val CONNECT_DISCOVERY_TIMEOUT_MS = 10_000L
        const val RESOLVE_TIMEOUT_MS = 8_000L
        const val MAX_LOG_LINES = 5_000

        // Android 无线调试 mDNS 服务类型
        const val SERVICE_PAIRING = "_adb-tls-pairing._tcp."
        const val SERVICE_CONNECT = "_adb-tls-connect._tcp."

        val VALID_PORTS = 1024..65535
        val PAIRING_CODE = Regex("\\d{6}")
        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
        val LOGCAT_TAG = Regex("^[A-Za-z0-9_.-]{1,80}$")
        val PRIORITIES = setOf('V', 'D', 'I', 'W', 'E', 'F')
    }
}
