package com.skipad.service

import android.content.Context
import android.provider.Settings

/**
 * 无障碍服务的真实运行状态。
 *
 * 「设置里已开启」和「服务真的在运行」是两件事：
 * 服务崩溃后系统会把它记成 `Crashed services`，系统设置里显示「无法运行」，
 * 但 `ENABLED_ACCESSIBILITY_SERVICES` 里仍然留着这个组件。
 * 只看设置项会误报「已授权」，用户以为在跳过，实际什么都没发生。
 */
enum class AccessibilityState {
    /** 用户从未开启（或已被系统彻底关闭）。 */
    DISABLED,

    /** 设置里是开启的，但服务没有连上 —— 系统多半把它标记成了「无法运行」。 */
    ENABLED_NOT_CONNECTED,

    /** 正常工作中。 */
    RUNNING,
}

/**
 * 无障碍服务的状态查询。
 */
object AccessibilityHealth {

    /**
     * 读取当前状态。
     *
     * 「是否连上」以进程内的服务实例为准：`onServiceConnected` 里赋值、
     * `onUnbind` / `onDestroy` 里清空，能够如实反映连接状态。
     *
     * @param graceMs 刚启动应用时会有一小段重新绑定的窗口，可传正数容忍。
     *                为 0 表示立即判定。
     */
    fun state(context: Context, graceMs: Long = 0L): AccessibilityState {
        val enabled = isEnabledInSettings(context)
        if (!enabled) return AccessibilityState.DISABLED

        if (SkipAccessibilityService.instance != null) return AccessibilityState.RUNNING

        if (graceMs > 0L) {
            // 绑定是异步的：进程刚起来时实例还没赋值，先等一小会儿再判断，
            // 否则每次冷启动都会闪一下「未连接」的红色警告。
            val deadline = System.currentTimeMillis() + graceMs
            while (System.currentTimeMillis() < deadline) {
                if (SkipAccessibilityService.instance != null) return AccessibilityState.RUNNING
                runCatching { Thread.sleep(120) }
            }
            if (SkipAccessibilityService.instance != null) return AccessibilityState.RUNNING
        }
        return AccessibilityState.ENABLED_NOT_CONNECTED
    }

    /**
     * 组件是否出现在系统的启用列表里。
     *
     * 注意：这不等于服务在运行，崩溃后的服务同样满足该条件。
     */
    fun isEnabledInSettings(context: Context): Boolean {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        // 不同 ROM 写入的格式不同：
        //   完整形式 com.skipad.debug/com.skipad.service.SkipAccessibilityService
        //   简写形式 com.skipad.debug/.service.SkipAccessibilityService
        // 用「包名前缀 + 类名/类简名包含」判断，两种都能命中。
        val className = SkipAccessibilityService::class.java.name
        val simpleName = SkipAccessibilityService::class.java.simpleName
        return raw.split(':').any { entry ->
            val trimmed = entry.trim()
            trimmed.startsWith(context.packageName) &&
                (trimmed.contains(className) || trimmed.contains(simpleName))
        }
    }
}
