package com.skipad.core.skip

/**
 * 匹配结果。
 */
data class SkipMatchResult(
    val rule: SkipRule,
    val node: SkipNode,
    val tapX: Int,
    val tapY: Int,
)

/**
 * 无障碍节点匹配引擎。
 *
 * 引擎只依赖抽象的 [SkipNode] 快照，不直接引用 `AccessibilityNodeInfo`，
 * 因此全部匹配逻辑都能在 JVM 上单元测试。
 *
 * 匹配顺序：
 * 1. 规则按优先级降序（同优先级保持注册顺序）
 * 2. 逐条规则在节点快照中寻找首个命中节点
 * 3. 命中后立即返回，不再检查后续规则
 */
class SkipMatcher(
    private val screenWidth: Int,
    private val screenHeight: Int,
) {

    /**
     * 在节点快照中寻找首个匹配的规则与节点。
     */
    fun findMatch(nodes: List<SkipNode>, rules: List<SkipRule>): SkipMatchResult? {
        if (nodes.isEmpty() || rules.isEmpty()) return null

        // 优先级降序；同优先级保持原有顺序（稳定排序）
        val ordered = rules.sortedByDescending { it.priority }

        for (rule in ordered) {
            val node = findMatchingNode(nodes, rule) ?: continue
            val (tapX, tapY) = rule.action.resolveTapPoint(node.bounds)
            return SkipMatchResult(rule, node, tapX, tapY)
        }
        return null
    }

    /**
     * 查找指定规则命中的节点。
     */
    fun findMatchingNode(nodes: List<SkipNode>, rule: SkipRule): SkipNode? =
        nodes.firstOrNull { node -> matches(node, rule) }

    /**
     * 判断节点是否满足规则。
     */
    fun matches(node: SkipNode, rule: SkipRule): Boolean {
        // 边界必须合理，过滤越界与零尺寸节点
        if (!node.isReasonableForScreen(screenWidth, screenHeight)) return false

        // 区域约束
        if (!rule.match.region.contains(node.bounds, screenWidth, screenHeight)) return false

        return rule.match.matches(node)
    }
}

/**
 * 规则索引：按包名预先分桶，避免每次事件都遍历全部规则。
 */
class SkipRuleIndex(private val rules: List<SkipRule>) {

    /**
     * 通配规则（包名为 `*`），对所有应用生效。
     */
    private val wildcardRules: List<SkipRule> = rules.filter { it.packageName == "*" }

    /**
     * 按包名索引的具体规则。
     */
    private val packageRules: Map<String, List<SkipRule>> = rules
        .filter { it.packageName != "*" }
        .groupBy { it.packageName }

    /**
     * 全部规则。
     */
    val allRules: List<SkipRule> = rules

    val size: Int get() = rules.size

    /**
     * 取某个包名下适用的规则，按优先级降序。
     *
     * 通配规则与具体规则合并后统一按 priority 排序，
     * 保证任何路径返回的规则顺序都是确定的。
     */
    fun rulesFor(packageName: String): List<SkipRule> {
        val specific = packageRules[packageName].orEmpty()
        val wildcard = wildcardRules
        if (specific.isEmpty() && wildcard.isEmpty()) return emptyList()
        return (specific + wildcard).sortedByDescending { it.priority }
    }
}

/**
 * 点击去重与冷却控制。
 *
 * 参照 SKIP 的 `clickedSkipRect` 机制：
 * - 同一个矩形在同一应用会话内只点击一次，避免重复触发
 * - 切换应用时清空
 * - 规则级冷却，避免高频误触
 */
class ClickGuard {

    /**
     * 已点击过的目标标识集合。
     */
    private val clicked = HashSet<String>()

    /**
     * 各规则上次触发的时间戳。
     */
    private val lastTriggered = HashMap<String, Long>()

    /**
     * 当前应用包名，用于检测应用切换。
     */
    private var currentPackage: String? = null

    /**
     * 准备一次新的应用会话。若应用已切换，清空去重记录。
     */
    fun onPackageChanged(packageName: String?) {
        if (currentPackage != packageName) {
            currentPackage = packageName
            clicked.clear()
        }
    }

    /**
     * 判断是否允许执行本次点击。
     *
     * @param key 目标唯一标识，通常由包名 + 节点标识 + 规则 id 组成
     * @param ruleId 规则 id，用于冷却判定
     * @param now 当前时间戳
     */
    fun canClick(key: String, ruleId: String, now: Long): Boolean {
        if (clicked.contains(key)) return false

        val last = lastTriggered[ruleId]
        if (last != null) {
            val rule = ruleCooldowns[ruleId] ?: 0L
            if (now - last < rule) return false
        }
        return true
    }

    /**
     * 记录一次已执行的点击。
     */
    fun markClicked(key: String, ruleId: String, now: Long) {
        clicked += key
        lastTriggered[ruleId] = now
    }

    /**
     * 规则冷却配置。
     */
    private val ruleCooldowns = HashMap<String, Long>()

    /**
     * 设置规则冷却时间。
     */
    fun setCooldown(ruleId: String, cooldownMs: Long) {
        ruleCooldowns[ruleId] = cooldownMs
    }

    /**
     * 清空所有状态。
     */
    fun reset() {
        clicked.clear()
        lastTriggered.clear()
        currentPackage = null
    }
}
