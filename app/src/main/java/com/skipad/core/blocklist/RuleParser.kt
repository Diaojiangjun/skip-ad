package com.skipad.core.blocklist

/**
 * 规则解析器。
 *
 * 支持两类常见格式：
 *
 * 1. **hosts 格式**（AdAway / StevenBlack / anti-ad 等绝大多数列表采用）
 *    ```
 *    0.0.0.0 ads.example.com
 *    127.0.0.1 tracker.example.com    # 行尾注释
 *    ```
 *
 * 2. **Adblock / uBlock 语法**
 *    ```
 *    ||ads.example.com^               域名 + 任意子域名
 *    |http://exact.example.com^       精确匹配
 *    @@||allowed.com^                例外放行
 *    /banner\d+/                     正则
 *    ```
 */
object RuleParser {

    /** 正则规则数量上限，避免病态规则拖慢匹配。 */
    internal const val MAX_REGEX_RULES = 5000

    /** 允许的正则规则条数，用于分批解析。 */
    private var regexBudget = MAX_REGEX_RULES

    /**
     * 重置正则规则预算。每次批量解析新列表前调用。
     */
    fun resetBudget() {
        regexBudget = MAX_REGEX_RULES
    }

    /**
     * 解析规则文本，返回解析出的规则列表。
     *
     * @param text 规则文本内容
     * @param source 来源标识（规则源名称），用于在界面上溯源
     */
    fun parse(text: String, source: String = ""): List<Rule> {
        val rules = ArrayList<Rule>(4096)

        for (rawLine in text.lineSequence()) {
            if (rules.size >= 200_000) break // 安全上限

            // 先用下标算出「有效区间」，只做一次 substring。
            // 早期写法是 stripComment(line).trim()，每行两次字符串分配；
            // 27 万行时这部分本身就是可观的 GC 压力。
            var end = rawLine.length
            for (i in rawLine.indices) {
                val ch = rawLine[i]
                if (ch == '#' || ch == '!') {
                    // hosts 的 `#` 与 adblock 的 `!` 都表示注释，`!#include` 之类同理
                    end = i
                    break
                }
            }
            var start = 0
            while (start < end && rawLine[start].isWhitespace()) start++
            while (end > start && rawLine[end - 1].isWhitespace()) end--
            if (start >= end) continue

            parseLine(rawLine.substring(start, end), source)?.let { rules += it }
        }
        return rules
    }

    private fun parseLine(line: String, source: String): Rule? {
        // Adblock 语法优先
        if (line.startsWith("@@")) {
            return parseAdblock(line.substring(2), RuleAction.ALLOW, source)
        }
        if (line.startsWith("/") && line.endsWith("/") && line.length > 2) {
            return parseRegex(line.substring(1, line.length - 1), RuleAction.BLOCK, source)
        }
        if (line.startsWith("||") || line.startsWith("|")) {
            return parseAdblock(line, RuleAction.BLOCK, source)
        }

        // hosts 格式
        return parseHosts(line, source)
    }

    /**
     * 解析 hosts 行：`0.0.0.0 domain1 domain2`。
     */
    private fun parseHosts(line: String, source: String): Rule? {
        // 手工按空白切分，不用 split(Regex(...))。
        // 这条路径每份列表要走 27 万次，而 `Regex("[ \t]+")` 写在这里会被**逐行编译**，
        // 实测占了整个解析耗时的绝大部分（5087ms → 优化后约 1s）。
        val length = line.length
        var index = 0

        // 第一个 token：屏蔽地址
        while (index < length && line[index].isWhitespace()) index++
        val addressStart = index
        while (index < length && !line[index].isWhitespace()) index++
        if (addressStart == index) return null
        val address = line.substring(addressStart, index)
        // 仅接受回环/未指定地址，它们都表示「屏蔽此域名」
        if (address !in BLOCKING_ADDRESSES) return null

        // hosts 语义：屏蔽该域名**及其子域名**，取第一个合法域名
        while (index < length) {
            while (index < length && line[index].isWhitespace()) index++
            if (index >= length) break
            val start = index
            while (index < length && !line[index].isWhitespace()) index++
            val domain = normalizeDomain(line, start, index) ?: continue
            return Rule(
                action = RuleAction.BLOCK,
                domain = domain,
                subdomains = true,
                source = source,
            )
        }
        return null
    }

    /** 表示「屏蔽此域名」的地址集合。 */
    private val BLOCKING_ADDRESSES = hashSetOf(
        "0.0.0.0", "127.0.0.1", "::", "::1", "0::0", "0.0.0.0.0.0.0.0",
    )

    /**
     * 解析 Adblock 规则。
     */
    private fun parseAdblock(ruleText: String, action: RuleAction, source: String): Rule? {
        var text = ruleText

        // 去掉选项部分（$third-party、$script 等）
        val optionIndex = text.indexOf('$')
        if (optionIndex >= 0) {
            text = text.substring(0, optionIndex)
        }
        if (text.isEmpty()) return null

        // `||domain^` 形式：匹配域名及其子域名
        if (text.startsWith("||")) {
            var domain = text.substring(2)
            domain = trimBoundary(domain, '^', '|')
            domain = normalizeDomain(domain) ?: return null
            return Rule(
                action = action,
                domain = domain,
                subdomains = true,
                source = source,
            )
        }

        // `|http://domain^` 形式：精确匹配
        if (text.startsWith("|")) {
            var domain = text.substring(1)
            domain = trimBoundary(domain, '^', '|')
            val schemeIndex = domain.indexOf("://")
            if (schemeIndex >= 0) domain = domain.substring(schemeIndex + 3)
            domain = domain.substringBefore('/')
            domain = normalizeDomain(domain) ?: return null
            return Rule(
                action = action,
                domain = domain,
                subdomains = false,
                source = source,
            )
        }

        // 裸域名
        val domain = normalizeDomain(text) ?: return null
        return Rule(
            action = action,
            domain = domain,
            subdomains = true,
            source = source,
        )
    }

    private fun parseRegex(pattern: String, action: RuleAction, source: String): Rule? {
        if (regexBudget <= 0) return null
        regexBudget--
        val compiled = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return Rule(
            action = action,
            isRegex = true,
            regex = compiled,
            source = source,
        )
    }

    /**
     * 去掉 Adblock 边界锚点字符。
     */
    private fun trimBoundary(text: String, vararg chars: Char): String {
        var result = text
        while (result.isNotEmpty() && (result.endsWith(chars.first()) || chars.any { result.endsWith(it) })) {
            result = result.dropLast(1)
        }
        return result
    }

    /**
     * 规范化域名：转小写、去除前导点与结尾点。非法域名返回 null。
     */
    fun normalizeDomain(raw: String): String? = normalizeDomain(raw, 0, raw.length)

    /**
     * 规范化 `[start, end)` 区间内的域名，避免为切分出的每个 token 先建字符串。
     *
     * 全程只做一次字符数组分配，最终才生成目标字符串 ——
     * 在 27 万行的解析路径上，这能显著减少临时对象。
     */
    private fun normalizeDomain(raw: String, from: Int, to: Int): String? {
        var start = from
        var end = to
        while (start < end && raw[start].isWhitespace()) start++
        while (end > start && raw[end - 1].isWhitespace()) end--
        if (start >= end) return null

        val chars = CharArray(end - start)
        var length = 0
        for (i in start until end) {
            val ch = raw[i]
            chars[length++] = if (ch in 'A'..'Z') ch + 32 else ch
        }

        // 去掉前导点与结尾点
        var s = 0
        while (s < length && chars[s] == '.') s++
        var e = length
        while (e > s && chars[e - 1] == '.') e--
        if (e <= s) return null

        // 只接受合法域名字符，同时确认至少含一个点（排除 TLD 级别的误伤）
        var hasDot = false
        for (i in s until e) {
            val ch = chars[i]
            val valid = ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '_'
            if (!valid) return null
            if (ch == '.') hasDot = true
        }
        if (!hasDot) return null
        if (e - s > 253) return null

        val result = String(chars, s, e - s)
        if (result == "localhost" || result == "localhost.localdomain") return null
        return result
    }
}
