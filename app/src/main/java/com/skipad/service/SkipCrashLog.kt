package com.skipad.service

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最近一次异常的落盘记录。
 *
 * 为什么需要它：无障碍服务一旦崩溃，系统会把它记成 `Crashed services`，
 * 在系统设置里显示「无法运行」且**不再自动重新绑定**，用户只能手动开关一次。
 * 但崩溃现场往往在 logcat 里被后续日志冲掉，事后无从查起。
 *
 * 把最近一次异常写进应用私有目录，至少能在下次打开时回答「上次为什么挂了」。
 * 记录只保留最近一条，且限制长度，避免无限增长。
 */
object SkipCrashLog {

    private const val TAG = "SkipCrashLog"
    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 8_000

    /**
     * 记录一次异常。
     *
     * 用 [runCatching] 包住全部 IO：记录日志本身绝不能再抛异常。
     */
    fun record(context: Context, scene: String, error: Throwable) {
        runCatching {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date())
            val text = buildString {
                appendLine("时间：$time")
                appendLine("场景：$scene")
                appendLine("类型：${error.javaClass.name}")
                appendLine("信息：${error.message}")
                appendLine()
                appendLine(error.stackTraceToString().take(MAX_CHARS))
            }
            File(context.filesDir, FILE_NAME).writeText(text)
        }.onFailure { Log.w(TAG, "写入崩溃记录失败", it) }
    }

    /**
     * 读取最近一次异常记录，没有则返回 null。
     */
    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()
    }.getOrNull()

    /**
     * 清除记录。
     */
    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
