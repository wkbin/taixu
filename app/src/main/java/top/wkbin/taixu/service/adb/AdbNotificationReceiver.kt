package top.wkbin.taixu.service.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.wkbin.taixu.runtime.bridge.adb.EmbeddedAdbManager

/**
 * 接收来自系统通知栏的无线 ADB 操作：
 * 1. 提取 RemoteInput 输入的 6 位配对码并异步发起 TLS + SPAKE2 配对与连接；
 * 2. 处理直接连接、断开连接与关闭通知请求。
 */
@AndroidEntryPoint
class AdbNotificationReceiver : BroadcastReceiver() {

    @Inject
    lateinit var embeddedAdbManager: EmbeddedAdbManager

    @Inject
    lateinit var adbNotificationManager: AdbNotificationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AdbNotificationManager.ACTION_PAIR_INPUT -> {
                val pairingCode = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(AdbNotificationManager.KEY_PAIRING_CODE)
                    ?.toString()
                    ?.trim()

                if (pairingCode.isNullOrBlank() || !pairingCode.matches(Regex("\\d{6}"))) {
                    adbNotificationManager.showFailed("配对码格式无效：必须是 6 位纯数字，请重试")
                    return
                }

                // 立即刷新为配对中状态（防止通知栏 RemoteInput 转圈死锁）
                adbNotificationManager.showPairingInProgress()
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        Log.i(TAG, "从通知栏接收到配对码，正在发起无线 ADB 配对…")
                        val result = embeddedAdbManager.pair(pairingCode)
                        result.fold(
                            onSuccess = {
                                Log.i(TAG, "通知栏无线 ADB 配对成功")
                                // EmbeddedAdbManager 会自动切入 Connected 状态，并通过 StateFlow 触发 showConnected
                            },
                            onFailure = { error ->
                                Log.w(TAG, "通知栏无线 ADB 配对失败", error)
                                adbNotificationManager.showFailed(error.message ?: "无线 ADB 配对失败，请确认系统配对码并重试")
                            },
                        )
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            AdbNotificationManager.ACTION_CONNECT -> {
                adbNotificationManager.showConnectingInProgress()
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        Log.i(TAG, "从通知栏发起无线 ADB 直接连接…")
                        val result = embeddedAdbManager.connect()
                        result.fold(
                            onSuccess = {
                                Log.i(TAG, "通知栏无线 ADB 连接成功")
                            },
                            onFailure = { error ->
                                Log.w(TAG, "通知栏无线 ADB 连接失败", error)
                                adbNotificationManager.showFailed(error.message ?: "无线 ADB 连接失败，请确认端口或重新配对")
                            },
                        )
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            AdbNotificationManager.ACTION_DISCONNECT -> {
                Log.i(TAG, "从通知栏请求断开无线 ADB")
                embeddedAdbManager.disconnect()
                adbNotificationManager.showPairingPrompt()
            }

            AdbNotificationManager.ACTION_DISMISS -> {
                adbNotificationManager.dismiss()
            }
        }
    }

    private companion object {
        const val TAG = "AdbNotifReceiver"
    }
}
