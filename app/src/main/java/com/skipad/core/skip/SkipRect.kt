package com.skipad.core.skip

/**
 * 矩形区域。
 *
 * 核心层刻意不依赖 `android.graphics.Rect`，
 * 这样全部匹配逻辑都能在 JVM 上直接单元测试，无需 Robolectric 或模拟器。
 * Android 层的转换在 `SkipAccessibilityService` 中完成。
 */
data class SkipRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    val isEmpty: Boolean get() = width <= 0 || height <= 0

    companion object {
        val EMPTY = SkipRect(0, 0, 0, 0)
    }
}
