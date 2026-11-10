package com.skipad

import android.app.Application
import com.skipad.service.SkipCrashLog

/**
 * 应用入口。
 *
 * 除了持有全局初始化，还负责**记录未捕获异常**：
 * 无障碍服务崩溃后会被系统标记成「无法运行」且不再自动重连，
 * 而崩溃堆栈往往早已被 logcat 冲掉。落一条记录在私有目录里，
 * 下次在「应用」页就能看到上次到底是什么异常。
 */
class SkipAdApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
    }

    /**
     * 安装崩溃记录器。
     *
     * 只做记录，随后**仍然交给原处理器** —— 不吞掉异常，
     * 否则会掩盖真实问题并让系统状态更难以判断。
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                SkipCrashLog.record(this, "进程崩溃（${thread.name}）", error)
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
