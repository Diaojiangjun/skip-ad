package com.skipad.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.skipad.R
import com.skipad.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 保活接收器。
 *
 * 负责全部「让服务重新活过来」的入口：
 * 1. 开机完成（含各厂商的快速开机广播）
 * 2. 应用被覆盖安装后
 * 3. 周期性自检（由 [KeepAliveScheduler] 安排）
 * 4. 服务被从最近任务移除后的快速重启
 *
 * 关于「强行停止」：用户在系统设置中点击「强行停止」后，Android 会把应用置为
 * stopped 状态，**任何方式都无法自动重启**，直到用户手动再次打开应用。
 * 这是系统安全设计，无 root 无法绕过，因此本类不尝试处理该场景。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "收到保活广播: $action")

        when (action) {
            KeepAliveScheduler.ACTION_WATCHDOG -> handleWatchdog(context)
            KeepAliveScheduler.ACTION_RESTART -> handleRestart(context)
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT_POWERON,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> handleBootOrUpgrade(context, action)
            else -> Unit
        }
    }

    /**
     * 周期自检：若用户期望开启但服务已停，尝试拉起。
     */
    private fun handleWatchdog(context: Context) {
        val pendingResult = goAsync()
        appScope.launch {
            try {
                val settings = runCatching { SettingsRepository(context).settings.first() }.getOrNull()
                    ?: return@launch

                // 无论是否需要恢复，都续期下一次自检，保持闭环不断裂
                if (settings.protectionEnabled) {
                    if (!isServiceActive()) {
                        Log.i(TAG, "自检发现服务已停止，尝试恢复")
                        startProtection(context, fromBackground = true)
                    }
                    KeepAliveScheduler.scheduleWatchdog(context)
                } else {
                    KeepAliveScheduler.cancelWatchdog(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 服务被移除后的快速重启。
     */
    private fun handleRestart(context: Context) {
        val pendingResult = goAsync()
        appScope.launch {
            try {
                val settings = runCatching { SettingsRepository(context).settings.first() }.getOrNull()
                    ?: return@launch
                if (settings.protectionEnabled && !isServiceActive()) {
                    Log.i(TAG, "尝试快速重启服务")
                    startProtection(context, fromBackground = true)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 开机或应用更新后恢复服务。
     */
    private fun handleBootOrUpgrade(context: Context, action: String) {
        val pendingResult = goAsync()
        appScope.launch {
            try {
                val settings = runCatching { SettingsRepository(context).settings.first() }.getOrNull()
                    ?: return@launch

                // 覆盖安装后应无条件恢复（用户此前已开启就说明期望继续保护）
                val isUpgrade = action == Intent.ACTION_MY_PACKAGE_REPLACED
                if (!isUpgrade && !settings.bootStartEnabled) {
                    Log.d(TAG, "未开启开机自启，跳过")
                    return@launch
                }
                if (!settings.protectionEnabled) return@launch

                // 开机后系统组件尚未就绪，稍作等待再建立隧道
                if (!isUpgrade) kotlinx.coroutines.delay(BOOT_DELAY_MS)

                startProtection(context, fromBackground = true)
                KeepAliveScheduler.scheduleWatchdog(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 拉起保护服务。
     *
     * Android 12+ 限制后台启动前台服务，此处做降级：
     * 若被系统拒绝，则发一条通知让用户一键恢复，而不是静默失败。
     */
    private fun startProtection(context: Context, fromBackground: Boolean) {
        if (isServiceActive()) return
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "VPN 未授权，无法自动启动")
            notifyManualRestore(context, "VPN 授权已失效，点击重新开启")
            return
        }

        val serviceIntent = Intent(context, AdBlockVpnService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "已请求启动保护服务")
        } catch (e: Exception) {
            // Android 12+ 可能在后台拒绝启动前台服务
            Log.w(TAG, "后台启动前台服务被拒绝", e)
            if (fromBackground) {
                notifyManualRestore(context, "后台保活受限，点击恢复广告拦截")
            }
        }
    }

    /**
     * 发送「需要手动恢复」通知。
     */
    private fun notifyManualRestore(context: Context, text: String) {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.skipad.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, AdBlockVpnService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("广告拦截已停止")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFY_ID_RESTORE, notification)
        }
    }

    private fun isServiceActive(): Boolean = AdBlockVpnService.instance?.isActive() == true

    private companion object {
        const val TAG = "SkipAdKeepAlive"
        const val BOOT_DELAY_MS = 3_000L
        const val NOTIFY_ID_RESTORE = 1002

        /** 各厂商的快速开机广播。 */
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"

        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
