package com.skipad.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 保活调度器。
 *
 * 通过 AlarmManager 安排周期性自检：若「用户期望开启保护」但服务已不在运行，
 * 则尝试重新拉起。这是对 START_STICKY 的补充 —— START_STICKY 只能应对
 * 系统因内存压力回收进程，无法应对厂商 ROM 的清理策略。
 *
 * 使用 `setAndAllowWhileIdle` 而非精确闹钟：
 * - 无需 `SCHEDULE_EXACT_ALARM` 权限，不会因未授权而静默失效
 * - 在 Doze 休眠下仍可触发（系统限制约每 9 分钟一次，实际间隔会放宽）
 *
 * 每次触发后重新安排下一次，形成自愈闭环。
 */
object KeepAliveScheduler {

    private const val TAG = "SkipAdKeepAlive"

    /** 自检动作。 */
    const val ACTION_WATCHDOG = "com.skipad.action.KEEPALIVE_WATCHDOG"

    /** 立即重启动作。 */
    const val ACTION_RESTART = "com.skipad.action.KEEPALIVE_RESTART"

    /** 自检周期：15 分钟。 */
    private const val INTERVAL_MS = 15L * 60 * 1000

    /** 服务被移除后延迟多久尝试恢复。 */
    private const val RESTART_DELAY_MS = 1_500L

    /** 同一进程内两次安排的间隔下限。 */
    private const val RESCHEDULE_GUARD_MS = 5_000L

    private const val REQUEST_WATCHDOG = 1001
    private const val REQUEST_RESTART = 1002

    /** 上次安排自检的时间戳。 */
    private var lastScheduledAt = 0L

    /**
     * 安排周期性自检。
     */
    fun scheduleWatchdog(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return

        // 去重：服务启动路径会被 onStartCommand / onResume / 保活接收器同时触发，
        // 同一个 PendingIntent 反复 set 只会把触发时间往后推，没有意义且刷日志。
        val now = System.currentTimeMillis()
        if (now - lastScheduledAt < RESCHEDULE_GUARD_MS) return
        lastScheduledAt = now

        val triggerAt = now + INTERVAL_MS

        try {
            // 在低电耗模式下也可触发，避免 Doze 期间完全静默
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    watchdogIntent(context),
                )
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, watchdogIntent(context))
            }
            Log.d(TAG, "已安排自检，下次触发于 ${INTERVAL_MS / 60000} 分钟后")
        } catch (e: Exception) {
            Log.w(TAG, "安排自检失败", e)
        }
    }

    /**
     * 取消周期性自检。
     */
    fun cancelWatchdog(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarmManager.cancel(watchdogIntent(context)) }
    }

    /**
     * 安排一次快速重启。
     *
     * 用于服务被「从最近任务划掉」等场景：延迟短暂时间后重新拉起，
     * 避免与系统回收动作竞争。
     */
    fun scheduleRestart(context: Context, delayMs: Long = RESTART_DELAY_MS) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs,
                restartIntent(context),
            )
        } catch (e: Exception) {
            Log.w(TAG, "安排重启失败", e)
        }
    }

    private fun watchdogIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_WATCHDOG,
            Intent(context, KeepAliveReceiver::class.java).setAction(ACTION_WATCHDOG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun restartIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_RESTART,
            Intent(context, KeepAliveReceiver::class.java).setAction(ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
