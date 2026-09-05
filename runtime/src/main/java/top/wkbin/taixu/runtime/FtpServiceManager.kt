package top.wkbin.taixu.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wkbin.taixu.core.datastore.FtpPreferences
import top.wkbin.taixu.core.datastore.SshPreferences
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.runtime.ftp.AndroidFtpServer
import top.wkbin.taixu.runtime.ftp.FtpServerConfig

sealed interface FtpServiceState {
    data class Stopped(val distroId: String) : FtpServiceState
    data class Starting(val distroId: String) : FtpServiceState
    data class Running(
        val distroId: String,
        val port: Int,
        val host: String,
    ) : FtpServiceState
    data class Failed(val distroId: String, val message: String) : FtpServiceState
}

/** Owns the built-in FTP server exposing the active Linux distribution's rootfs. */
@Singleton
class FtpServiceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val linuxRuntime: LinuxRuntime,
    private val preferences: FtpPreferences,
    private val sshPreferences: SshPreferences,
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceMutex = Mutex()
    private val observing = AtomicBoolean(false)
    private val _state = MutableStateFlow<FtpServiceState>(FtpServiceState.Stopped("ubuntu"))
    val state: StateFlow<FtpServiceState> = _state.asStateFlow()

    private var ftpServer: AndroidFtpServer? = null
    private var serviceDistroId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val recentLogs = ConcurrentLinkedDeque<String>()
    private val _logUpdateFlow = MutableStateFlow(0L)

    /** Idempotently restores FTP when runtime is ready or active distro changes. */
    fun startObserving() {
        if (!observing.compareAndSet(false, true)) return
        managerScope.launch {
            combine(linuxRuntime.state, linuxRuntime.activeDistroId) { runtimeState, distroId ->
                (runtimeState is RuntimeState.Ready) to distroId
            }
                .distinctUntilChanged()
                .collectLatest { (ready, distroId) ->
                    serviceMutex.withLock {
                        if (serviceDistroId != null && serviceDistroId != distroId) stopLocked()
                    }
                    if (!ready) {
                        _state.value = FtpServiceState.Stopped(distroId)
                        return@collectLatest
                    }
                    refresh(distroId)
                    if (preferences.enabled(distroId).first()) {
                        try {
                            start(distroId)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            // start() updates state with failure message
                        }
                    }
                }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        val distroId = linuxRuntime.activeDistroId.value
        if (enabled) {
            start(distroId)
            preferences.setEnabled(distroId, true)
        } else {
            preferences.setEnabled(distroId, false)
            stop()
        }
    }

    suspend fun setPort(port: Int) {
        require(port in 1024..65535) { "FTP 端口必须在 1024..65535 之间" }
        val distroId = linuxRuntime.activeDistroId.value
        preferences.setPort(distroId, port)
        restartIfRunning(distroId)
    }

    suspend fun setUsername(username: String) {
        val distroId = linuxRuntime.activeDistroId.value
        preferences.setUsername(distroId, username.trim().ifBlank { "root" })
        restartIfRunning(distroId)
    }

    suspend fun setPassword(password: String?) {
        val distroId = linuxRuntime.activeDistroId.value
        preferences.setPassword(distroId, password)
        restartIfRunning(distroId)
    }

    suspend fun clearPassword() {
        val distroId = linuxRuntime.activeDistroId.value
        preferences.setPassword(distroId, null)
        restartIfRunning(distroId)
    }

    suspend fun setAnonymousEnabled(enabled: Boolean) {
        val distroId = linuxRuntime.activeDistroId.value
        preferences.setAnonymousEnabled(distroId, enabled)
        restartIfRunning(distroId)
    }

    suspend fun setReadOnly(readOnly: Boolean) {
        val distroId = linuxRuntime.activeDistroId.value
        preferences.setReadOnly(distroId, readOnly)
        restartIfRunning(distroId)
    }

    suspend fun refresh(distroId: String = linuxRuntime.activeDistroId.value) {
        if (serviceDistroId == distroId && ftpServer?.isRunning == true) return
        _state.value = FtpServiceState.Stopped(distroId)
    }

    suspend fun start(distroId: String = linuxRuntime.activeDistroId.value) = serviceMutex.withLock {
        val current = ftpServer
        if (serviceDistroId == distroId && current?.isRunning == true) return@withLock
        stopLocked()

        _state.value = FtpServiceState.Starting(distroId)

        try {
            val rootDir = linuxRuntime.rootfsPath(distroId)
            val workspaceDir = runCatching { linuxRuntime.workspacePath() }.getOrNull()
            val sdcardDir = File("/storage/emulated/0").takeIf { it.exists() }

            val port = preferences.port(distroId).first()
            val username = preferences.username(distroId).first()
            val ftpPassword = preferences.readPassword(distroId)?.ifBlank { null }
            val sshPassword = sshPreferences.readPassword(distroId)?.ifBlank { null }
            val password = ftpPassword ?: sshPassword
            val anonymous = preferences.anonymousEnabled(distroId).first()
            val readOnly = preferences.readOnly(distroId).first()

            val config = FtpServerConfig(
                port = port,
                rootDirectory = rootDir,
                workspaceDirectory = workspaceDir,
                sdcardDirectory = sdcardDir,
                username = username,
                password = password,
                anonymousEnabled = anonymous,
                readOnly = readOnly,
            )

            val server = AndroidFtpServer(config) { logLine ->
                appendLog(logLine)
            }
            server.start()

            ftpServer = server
            serviceDistroId = distroId

            val host = SshServiceManager.localIpv4Address() ?: "127.0.0.1"
            _state.value = FtpServiceState.Running(
                distroId = distroId,
                port = port,
                host = host,
            )

            acquireWakeLock()
            acquireWifiLock()
            showNotification(host, port, username, anonymous)
            appendLog("FTP 服务启动成功，监听端口 $port，根目录：${rootDir.absolutePath}")
        } catch (cancellation: CancellationException) {
            hideNotification()
            _state.value = FtpServiceState.Stopped(distroId)
            throw cancellation
        } catch (throwable: Throwable) {
            hideNotification()
            ftpServer = null
            serviceDistroId = null
            _state.value = FtpServiceState.Failed(distroId, throwable.message ?: "FTP 服务启动失败")
            appendLog("FTP 服务启动失败: ${throwable.message}")
            throw throwable
        }
    }

    suspend fun stop() = serviceMutex.withLock {
        val distroId = serviceDistroId ?: linuxRuntime.activeDistroId.value
        stopLocked()
        refresh(distroId)
    }

    private fun stopLocked() {
        try {
            ftpServer?.stop()
        } finally {
            ftpServer = null
            serviceDistroId = null
            releaseWakeLock()
            hideNotification()
        }
    }

    private suspend fun restartIfRunning(distroId: String) {
        if (serviceDistroId == distroId && ftpServer?.isRunning == true) {
            serviceMutex.withLock { stopLocked() }
            start(distroId)
        }
    }

    private fun appendLog(line: String) {
        recentLogs.addLast(line)
        while (recentLogs.size > MAX_LOG_LINES) {
            recentLogs.pollFirst()
        }
        _logUpdateFlow.value = System.currentTimeMillis()
    }

    fun logs(): Flow<List<String>> = flow {
        _logUpdateFlow.collect {
            emit(recentLogs.toList())
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = runCatching {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull() ?: return
        val lock = wakeLock ?: pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Taixu::FtpServer",
        ).also { it.setReferenceCounted(false) }
        wakeLock = lock
        runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifi = runCatching {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull() ?: return
        val lock = wifiLock ?: wifi.createWifiLock(
            @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "Taixu::FtpServer",
        ).also { it.setReferenceCounted(false) }
        wifiLock = lock
        runCatching { lock.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wakeLock = null
        wifiLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wifiLock = null
    }

    private fun showNotification(host: String, port: Int, username: String, anonymous: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "太墟 Linux FTP 文件服务",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "用于展示 Linux 沙箱 FTP 远程文件访问运行状态的常驻通知"
                    setShowBadge(false)
                },
            )
        }
        val url = if (anonymous) "ftp://$host:$port/" else "ftp://$username@$host:$port/"
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Linux FTP 远程文件服务运行中")
            .setContentText("连接地址: $url")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun hideNotification() {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 2121
        const val NOTIFICATION_CHANNEL_ID = "taixu_ftp_server"
        private const val MAX_LOG_LINES = 100
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L
    }
}
