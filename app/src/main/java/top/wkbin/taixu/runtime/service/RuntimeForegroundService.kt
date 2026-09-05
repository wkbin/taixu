package top.wkbin.taixu.runtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import top.wkbin.taixu.R
import top.wkbin.taixu.runtime.shell.ProcessRegistry
import top.wkbin.taixu.runtime.SshServiceManager
import top.wkbin.taixu.runtime.FtpServiceManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

import kotlinx.coroutines.launch

@AndroidEntryPoint
class RuntimeForegroundService : Service() {
    @Inject lateinit var processRegistry: ProcessRegistry
    @Inject lateinit var localServiceLauncher: LocalServiceLauncher
    @Inject lateinit var sshServiceManager: SshServiceManager
    @Inject lateinit var ftpServiceManager: FtpServiceManager
    /** 停止后的沙箱进程清理作用域：独立于服务生命周期，服务销毁后也要跑完。 */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** CPU 唤醒锁：息屏后保持 CPU 运行，防止 Linux 后台进程/构建/安装被冻结。 */
    private var wakeLock: PowerManager.WakeLock? = null
    /** Wi-Fi 锁：息屏后防止 Wi-Fi 无线电源进入省电模式导致沙箱内网络断连。 */
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        sshServiceManager.startObserving()
        ftpServiceManager.startObserving()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.taixu_runtime_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "用于展示 Linux 沙箱后台运行状态的常驻通知"
            enableVibration(false)
            setSound(null, null)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
        // 清理历史版本遗留的灵动岛/胶囊渠道，避免系统设置里残留无效项
        runCatching { manager.deleteNotificationChannel(LEGACY_CAPSULE_CHANNEL_ID) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Android 15+ 要求收到停止请求后在数秒时限内退出前台态，超时直接抛
            // ForegroundServiceDidNotStopInTimeException 杀进程。杀沙箱进程可能超过该
            // 时限，因此必须先同步退出前台，进程清理放到独立作用域异步完成。
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            cleanupScope.launch {
                runCatching { localServiceLauncher.stopAll() }
                runCatching { processRegistry.stopAll() }
                runCatching { ftpServiceManager.stop() }
                runCatching { sshServiceManager.stop() }
                releaseLocks()
            }
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        acquireLocks()
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        // dataSync 前台服务有系统级 6 小时硬超时（Android 15+），超时后必须立即退出
        // 前台，否则系统抛 ForegroundServiceDidNotStopInTimeException 杀进程。
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        onTimeout(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseLocks()
        cleanupScope.launch {
            runCatching { ftpServiceManager.stop() }
            runCatching { sshServiceManager.stop() }
        }
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            runCatching {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    .also { it.acquire(LOCK_TIMEOUT_MS) }
                Log.i(TAG, "Acquired partial wake lock for runtime service")
            }.onFailure { Log.w(TAG, "获取 CPU 唤醒锁失败", it) }
        }
        if (wifiLock?.isHeld != true) {
            runCatching {
                @Suppress("DEPRECATION")
                wifiLock = getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL, WIFI_LOCK_TAG)
                    .also { it.acquire() }
                Log.i(TAG, "Acquired Wi-Fi lock for runtime service")
            }.onFailure { Log.w(TAG, "获取 Wi-Fi 锁失败", it) }
        }
    }

    private fun releaseLocks() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
            Log.i(TAG, "Released wake lock")
        }.onFailure { Log.w(TAG, "释放 CPU 唤醒锁失败", it) }
        wakeLock = null
        runCatching {
            wifiLock?.takeIf { it.isHeld }?.release()
            Log.i(TAG, "Released Wi-Fi lock")
        }.onFailure { Log.w(TAG, "释放 Wi-Fi 锁失败", it) }
        wifiLock = null
    }

    private fun notification(): Notification {
        val stopPending = PendingIntent.getService(
            this,
            1002,
            Intent(this, RuntimeForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.taixu_notification)
            .setContentTitle("Linux 沙箱")
            .setContentText(getString(R.string.taixu_runtime_running))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.taixu_notification,
                    getString(R.string.taixu_notification_stop),
                    stopPending,
                ),
            )
            .build()
    }

    companion object {
        const val ACTION_STOP = "top.wkbin.taixu.action.STOP_RUNTIME_SERVICE"
        private const val CHANNEL_ID = "taixu-runtime-v5"
        private const val LEGACY_CAPSULE_CHANNEL_ID = "taixu-runtime-capsule-v4"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "RuntimeForegroundService"
        private const val WAKE_LOCK_TAG = "taixu:runtime-service"
        private const val WIFI_LOCK_TAG = "taixu:runtime-wifi"
        /** 唤醒锁超时：8 小时兜底，避免异常情况下永久持有。 */
        private const val LOCK_TIMEOUT_MS = 8 * 60 * 60 * 1000L
    }
}
