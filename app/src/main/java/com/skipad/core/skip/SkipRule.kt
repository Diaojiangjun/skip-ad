package com.skipad.core.skip

/**
 * 拦截引擎类型。
 */
enum class EngineType {
    /**
     * DNS 引擎：在域名解析阶段拦截网络层广告。
     */
    DNS,

    /**
     * 无障碍引擎：识别并跳过本地渲染的开屏广告与弹窗。
     */
    ACCESSIBILITY,
}

/**
 * 节点所在屏幕区域。
 *
 * 参照 GKD 与 Fuck-AD 的区域划分，用于避免误点页面主体内容。
 */
enum class ScreenRegion {
    /** 不限制区域。 */
    ANY,

    /** 右上角，跳过按钮的典型位置。 */
    TOP_RIGHT,

    /** 右下角。 */
    BOTTOM_RIGHT,

    /** 顶部。 */
    TOP,

    /** 底部。 */
    BOTTOM,
    ;

    /**
     * 判断给定的节点边界是否落在本区域内。
     *
     * @param bounds 节点在屏幕上的边界
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     */
    fun contains(bounds: SkipRect, screenWidth: Int, screenHeight: Int): Boolean {
        if (bounds.isEmpty || screenWidth <= 0 || screenHeight <= 0) return false
        val centerX = bounds.centerX
        val centerY = bounds.centerY
        return when (this) {
            ANY -> true
            TOP_RIGHT -> centerX >= screenWidth * 0.45f && centerY <= screenHeight * 0.45f
            BOTTOM_RIGHT -> centerX >= screenWidth * 0.45f && centerY >= screenHeight * 0.45f
            TOP -> centerY <= screenHeight * 0.45f
            BOTTOM -> centerY >= screenHeight * 0.45f
        }
    }
}

/**
 * 点击位置策略。
 */
enum class TapStrategy {
    /** 点击节点中心。 */
    CENTER,

    /** 点击节点右上角。 */
    TOP_RIGHT,

    /** 点击节点右下角。 */
    BOTTOM_RIGHT,

    /** 按比例点击节点内部。 */
    CUSTOM_RATIO,

    ;

    companion object {
        /**
         * 按名称解析点击策略，未知值回退到 [CENTER]。
         */
        fun fromName(value: String?): TapStrategy = when (value?.lowercase()) {
            "center" -> CENTER
            "topright", "top_right" -> TOP_RIGHT
            "bottomright", "bottom_right" -> BOTTOM_RIGHT
            "customratio", "custom_ratio" -> CUSTOM_RATIO
            else -> CENTER
        }
    }
}

/**
 * 规则的动作配置。
 */
data class SkipAction(
    val tapStrategy: TapStrategy = TapStrategy.CENTER,
    val customXRatio: Float = 0.5f,
    val customYRatio: Float = 0.5f,
) {
    /**
     * 计算最终的点击坐标。
     */
    fun resolveTapPoint(bounds: SkipRect): Pair<Int, Int> = when (tapStrategy) {
        TapStrategy.CENTER -> bounds.centerX to bounds.centerY
        TapStrategy.TOP_RIGHT -> bounds.right - 1 to bounds.top + 1
        TapStrategy.BOTTOM_RIGHT -> bounds.right - 1 to bounds.bottom - 1
        TapStrategy.CUSTOM_RATIO -> {
            val x = bounds.left + (bounds.width * customXRatio.coerceIn(0f, 1f)).toInt()
            val y = bounds.top + (bounds.height * customYRatio.coerceIn(0f, 1f)).toInt()
            x to y
        }
    }
}

/**
 * 节点匹配条件。
 *
 * 支持六个维度的正向匹配与三个维度的反向排除：
 * - 正向：[text]、[desc]、[resourceId]、[className] 任一命中即可
 * - 反向：[excludeText]、[excludeDesc]、[excludeResourceId] 任一命中即排除
 *
 * 语义与 GKD / Fuck-AD 保持一致：**排除优先于匹配**。
 */
data class SkipMatch(
    val text: List<String> = emptyList(),
    val desc: List<String> = emptyList(),
    val resourceId: List<String> = emptyList(),
    val className: List<String> = emptyList(),
    val excludeText: List<String> = emptyList(),
    val excludeDesc: List<String> = emptyList(),
    val excludeResourceId: List<String> = emptyList(),
    val visible: Boolean = true,
    val region: ScreenRegion = ScreenRegion.ANY,
    /**
     * GKD 属性选择器。
     *
     * 非空时优先用它匹配，忽略上面的字段条件。
     * 仅支持属性子集，见 [GkdSelector]；编译失败则该规则不生效。
     */
    val selector: String? = null,
) {
    /** 是否至少指定了一个正向匹配条件。空条件不匹配任何节点，避免误点。 */
    val hasPositiveCondition: Boolean
        get() = selector != null || text.isNotEmpty() || desc.isNotEmpty() ||
            resourceId.isNotEmpty() || className.isNotEmpty()

    /**
     * 根据一个节点的属性判断是否命中。
     */
    fun matches(node: SkipNode): Boolean {
        if (!hasPositiveCondition) return false
        if (visible && !node.visibleToUser) return false
        if (matchesExcluded(node)) return false
        return matchesPositive(node)
    }

    private fun matchesExcluded(node: SkipNode): Boolean {
        if (excludeText.isNotEmpty() && containsAny(node.text, excludeText)) return true
        if (excludeDesc.isNotEmpty() && containsAny(node.desc, excludeDesc)) return true
        if (excludeResourceId.isNotEmpty() && containsAny(node.resourceId, excludeResourceId)) return true
        return false
    }

    private fun matchesPositive(node: SkipNode): Boolean {
        // GKD 选择器优先：它表达的是完整的布尔条件，与字段条件互斥
        selector?.let { raw ->
            val predicate = selectorCache.getOrPut(raw) { GkdSelector.compile(raw) }
            // 无法编译的选择器（含结构组合符）视为不匹配，避免误点
            return predicate?.invoke(node) == true
        }
        if (text.isNotEmpty() && containsAny(node.text, text)) return true
        if (desc.isNotEmpty() && containsAny(node.desc, desc)) return true
        if (resourceId.isNotEmpty() && containsAny(node.resourceId, resourceId)) return true
        if (className.isNotEmpty() && containsAny(node.className, className)) return true
        return false
    }

    companion object {
        /** 选择器编译缓存，避免每帧重复解析同一表达式。 */
        private val selectorCache = java.util.concurrent.ConcurrentHashMap<String, ((SkipNode) -> Boolean)?>()

        /**
         * 字符串匹配：完全相等或包含其一即为命中（忽略大小写）。
         */
        fun containsAny(value: String?, patterns: List<String>): Boolean {
            if (patterns.isEmpty()) return false
            val target = value?.trim().orEmpty()
            if (target.isEmpty()) return false
            return patterns.any { pattern ->
                val needle = pattern.trim()
                needle.isNotEmpty() && (
                    target.equals(needle, ignoreCase = true) ||
                        target.contains(needle, ignoreCase = true)
                    )
            }
        }
    }
}

/**
 * 一条应用级拦截规则。
 *
 * @param packageName 目标包名，`*` 表示所有应用
 * @param activity 目标 Activity，支持逗号/竖线/分号分隔的多值，`*` 表示不限
 * @param priority 优先级，数值越大越先匹配
 * @param cooldownMs 同一规则两次触发之间的最小间隔
 * @param delayMs 命中后延迟多久执行点击，用于等待开屏动画结束
 */
data class SkipRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val packageName: String = "*",
    val activity: String = "*",
    val priority: Int = 0,
    val cooldownMs: Long = 3000L,
    val delayMs: Long = 0L,
    val match: SkipMatch = SkipMatch(),
    val action: SkipAction = SkipAction(),
    val source: String = "builtin",
) {
    /**
     * 解析后的 Activity 列表。空列表表示不限制。
     */
    val activityValues: List<String> = activity
        .split(',', '|', ';', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "*" }

    private val matchesAllActivities: Boolean
        get() = activityValues.isEmpty()

    /**
     * 判断该规则是否适用于当前应用与界面。
     *
     * Activity 匹配采用「简单类名」语义：把规则值与实际类名都取最后一段再比较。
     * 这样规则里写 `SplashActivity` 或 `com.example.SplashActivity`，
     * 都能命中 `com.example.ui.SplashActivity` —— 实际类名带包名前缀是常态，
     * 只做全名精确比较会导致规则大面积失效。
     */
    fun appliesTo(packageName: String, className: String?): Boolean {
        if (!enabled) return false
        val packageMatches = this.packageName == "*" || this.packageName == packageName
        if (!packageMatches) return false
        if (matchesAllActivities) return true
        val target = className.orEmpty()
        if (target.isEmpty()) return false
        val targetSimpleName = target.substringAfterLast('.')
        return activityValues.any { value ->
            val simple = value.substringAfterLast('.')
            target == value || targetSimpleName == simple
        }
    }
}

/**
 * 从无障碍节点提取的快照。
 *
 * 抽离出独立数据类，使匹配逻辑可在 JVM 上单元测试，无需 Android 运行环境。
 */
data class SkipNode(
    val text: String? = null,
    val desc: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val visibleToUser: Boolean = true,
    val clickable: Boolean = false,
    val bounds: SkipRect = SkipRect.EMPTY,
    val depth: Int = 0,
    /** 子节点数量，供 GKD 选择器的 childCount 条件使用。 */
    val childCount: Int = 0,
) {
    /**
     * 判断节点边界是否在屏幕范围内合理。
     *
     * 用于过滤越界、零尺寸等异常节点，避免在手势坐标处产生误触。
     */
    fun isReasonableForScreen(screenWidth: Int, screenHeight: Int): Boolean {
        if (bounds.isEmpty) return false
        if (screenWidth <= 0 || screenHeight <= 0) return false
        if (bounds.width <= 0 || bounds.height <= 0) return false
        // 允许少量越界（贴边按钮），但不允许完全在屏幕外
        if (bounds.right <= 0 || bounds.bottom <= 0) return false
        if (bounds.left >= screenWidth || bounds.top >= screenHeight) return false
        return true
    }
}
