package com.skipad.core.blocklist

/**
 * 规则的动作类型。
 */
enum class RuleAction {
    /** 拦截该域名。 */
    BLOCK,

    /** 例外放行（优先级高于拦截规则）。 */
    ALLOW,
}

/**
 * 一条已解析的规则。
 *
 * @param domain 规则作用的域名（不含前导点，全小写）。空表示该规则不基于域名。
 * @param subdomains 是否同时作用于子域名（对应 hosts 文件的 `domain` 与 adblock 的 `||domain^`）。
 * @param regex 对应的正则表达式，仅当 [isRegex] 为 true 时有效。
 */
data class Rule(
    val action: RuleAction,
    val domain: String = "",
    val subdomains: Boolean = false,
    val isRegex: Boolean = false,
    val regex: Regex? = null,
    val source: String = "",
) {
    /**
     * 判断该规则是否命中给定域名。
     */
    fun matches(host: String): Boolean {
        if (isRegex) {
            val pattern = regex ?: return false
            return pattern.containsMatchIn(host)
        }
        if (domain.isEmpty()) return false
        return if (subdomains) {
            host == domain || host.endsWith(".$domain")
        } else {
            host == domain
        }
    }
}

/**
 * 规则集合的匹配结果。
 */
sealed interface MatchResult {
    /** 未命中任何规则。 */
    data object NoMatch : MatchResult

    /** 命中拦截规则。 */
    data class Blocked(val rule: Rule) : MatchResult

    /** 命中例外放行规则。 */
    data class Allowed(val rule: Rule) : MatchResult
}
