package com.skipad.ui.theme

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将数字格式化为便于阅读的形式：超过 1 万显示为 "1.2万"。
 */
fun formatCount(value: Long): String = when {
    value < 10_000 -> value.toString()
    value < 100_000_000 -> String.format(Locale.CHINA, "%.1f万", value / 10_000.0)
    else -> String.format(Locale.CHINA, "%.1f亿", value / 100_000_000.0)
}

/**
 * 相对时间描述。
 */
fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000

    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        hours < 24 -> "$hours 小时前"
        days < 30 -> "$days 天前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
    }
}
