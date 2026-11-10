package com.skipad.core.blocklist

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 不可变的规则索引。
 *
 * 匹配采用三级结构：
 * 1. [exactDomains] —— 精确域名集合（不含子域）
 * 2. [suffixDomains] —— 域名及其子域均命中的集合（hosts 语义）
 * 3. [regexRules] —— 正则规则，按需逐一匹配
 *
 * 对于一次查询 `a.b.example.com`，引擎会按标签逐级回溯检查：
 * `a.b.example.com` → `b.example.com` → `example.com` → `com`，
 * 在每一级同时查询精确集合与后缀集合，因此**无需遍历全部规则**。
 */
class RuleIndex private constructor(
    private val exactDomains: Set<String>,
    private val suffixDomains: Set<String>,
    private val regexRules: List<Rule>,
    private val allowDomains: Set<String>,
    private val allowSuffixDomains: Set<String>,
    private val allowRegexRules: List<Rule>,
    private val sourceRulesProvider: () -> List<Rule>,
) {
    /**
     * 原始规则快照。
     *
     * 只有「在已有规则上追加白名单」这条路径才用得到它，因此改为惰性生成：
     * 正常装载 27 万条规则时会额外 new 27 万个 Rule 对象，纯属浪费。
     */
    private val sourceRules: List<Rule> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        sourceRulesProvider()
    }


    val size: Int get() = exactDomains.size + suffixDomains.size + regexRules.size

    val regexCount: Int get() = regexRules.size + allowRegexRules.size

    /**
     * 返回本次索引所依据的原始规则快照，用于在追加白名单后重建索引。
     */
    fun allRulesSnapshot(): List<Rule> = sourceRules

    /**
     * 匹配一个域名。
     */
    fun match(host: String): MatchResult {
        if (host.isEmpty()) return MatchResult.NoMatch
        val normalized = host.trim().lowercase().removeSuffix(".")

        // 例外规则优先：放行规则若命中，则不再考虑拦截规则。
        findAllow(normalized)?.let { return MatchResult.Allowed(it) }

        findBlock(normalized)?.let { return MatchResult.Blocked(it) }

        return MatchResult.NoMatch
    }

    /**
     * 判断域名是否应被拦截。
     */
    fun isBlocked(host: String): Boolean = match(host) is MatchResult.Blocked

    private fun findAllow(host: String): Rule? {
        // 逐级回溯
        var domain = host
        while (true) {
            if (allowDomains.contains(domain)) {
                return Rule(RuleAction.ALLOW, domain, subdomains = false, source = "allowlist")
            }
            if (allowSuffixDomains.contains(domain)) {
                return Rule(RuleAction.ALLOW, domain, subdomains = true, source = "allowlist")
            }
            val next = domain.substringAfter('.', "")
            if (next.isEmpty() || next == domain) break
            domain = next
        }
        for (rule in allowRegexRules) {
            if (rule.matches(host)) return rule
        }
        return null
    }

    private fun findBlock(host: String): Rule? {
        // 逐级回溯：host → 去掉最左标签 → …
        var domain = host
        var isFirstLevel = true
        while (true) {
            if (suffixDomains.contains(domain)) {
                return Rule(RuleAction.BLOCK, domain, subdomains = true, source = "blocklist")
            }
            // 精确规则只在完全相等时命中，不能因为回溯到父级就误伤子域名。
            // 例：精确规则 exact.example.com 不应拦截 sub.exact.example.com。
            if (isFirstLevel && exactDomains.contains(domain)) {
                return Rule(RuleAction.BLOCK, domain, subdomains = false, source = "blocklist")
            }
            val next = domain.substringAfter('.', "")
            if (next.isEmpty() || next == domain) break
            domain = next
            isFirstLevel = false
        }
        for (rule in regexRules) {
            if (rule.matches(host)) return rule
        }
        return null
    }

    /**
     * 构建索引。
     */
    class Builder {
        private val exactDomains = HashSet<String>()
        private val suffixDomains = HashSet<String>()
        private val regexRules = ArrayList<Rule>()
        private val allowDomains = HashSet<String>()
        private val allowSuffixDomains = HashSet<String>()
        private val allowRegexRules = ArrayList<Rule>()

        fun add(rule: Rule): Builder = apply {
            when {
                rule.isRegex -> {
                    if (rule.action == RuleAction.BLOCK) regexRules += rule else allowRegexRules += rule
                }
                rule.action == RuleAction.BLOCK -> {
                    if (rule.subdomains) suffixDomains += rule.domain else exactDomains += rule.domain
                }
                else -> {
                    if (rule.subdomains) allowSuffixDomains += rule.domain else allowDomains += rule.domain
                }
            }
        }

        fun addAll(rules: Iterable<Rule>): Builder = apply {
            rules.forEach { add(it) }
        }

        /**
         * 加入用户自定义白名单（域名及其子域均放行）。
         */
        fun allowDomain(domain: String): Builder = apply {
            val normalized = RuleParser.normalizeDomain(domain) ?: return@apply
            allowSuffixDomains += normalized
        }

        fun build(): RuleIndex = RuleIndex(
            exactDomains = exactDomains.toSet(),
            suffixDomains = suffixDomains.toSet(),
            regexRules = regexRules.toList(),
            allowDomains = allowDomains.toSet(),
            allowSuffixDomains = allowSuffixDomains.toSet(),
            allowRegexRules = allowRegexRules.toList(),
            sourceRulesProvider = { buildSourceSnapshot() },
        )

        /**
         * 保留原始规则（精确/后缀/正则三种形态），以便后续追加白名单时能够完整重建。
         */
        private fun buildSourceSnapshot(): List<Rule> {
            val result = ArrayList<Rule>(exactDomains.size + suffixDomains.size + regexRules.size)
            exactDomains.forEach { result += Rule(RuleAction.BLOCK, it, subdomains = false) }
            suffixDomains.forEach { result += Rule(RuleAction.BLOCK, it, subdomains = true) }
            result += regexRules
            return result
        }
    }
}

/**
 * 线程安全的规则集合。
 *
 * 规则更新时整体替换索引，查询路径无锁竞争，仅在替换瞬间使用写锁。
 */
class BlocklistEngine {

    private val lock = ReentrantReadWriteLock()
    @Volatile
    private var index: RuleIndex = RuleIndex.Builder().build()

    /** 累计拦截次数。 */
    private val blockedCount = AtomicLong(0)

    /** 规则总数。 */
    val ruleCount: Int get() = index.size

    val regexRuleCount: Int get() = index.regexCount

    /**
     * 用给定规则集合整体替换当前索引。
     */
    fun replaceRules(
        rules: Collection<Rule>,
        allowDomains: Collection<String> = emptyList(),
    ) {
        val builder = RuleIndex.Builder()
        builder.addAll(rules)
        // 白名单与规则一起入索引：分成 replaceRules + setAllowList 两步时，
        // 第二步要先把全部规则拍成快照再重建一遍索引，等于把最贵的工作做了两次。
        allowDomains.forEach { builder.allowDomain(it) }
        val newIndex = builder.build()
        lock.write {
            index = newIndex
        }
    }

    /**
     * 在现有规则基础上追加用户白名单。
     */
    fun setAllowList(domains: Collection<String>) {
        lock.write {
            val builder = RuleIndex.Builder()
            builder.addAll(index.allRulesSnapshot())
            domains.forEach { builder.allowDomain(it) }
            index = builder.build()
        }
    }

    /**
     * 匹配域名。
     */
    fun match(host: String): MatchResult {
        val result = index.match(host)
        if (result is MatchResult.Blocked) {
            blockedCount.incrementAndGet()
        }
        return result
    }

    fun isBlocked(host: String): Boolean = match(host) is MatchResult.Blocked

    fun blockedTotal(): Long = blockedCount.get()

    fun resetCounter() {
        blockedCount.set(0)
    }
}
