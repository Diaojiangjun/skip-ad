package com.skipad.core.skip

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON5 转严格 JSON。
 *
 * GKD 订阅文件是 JSON5：允许无引号键名、单引号字符串、行尾逗号与注释，
 * 而 `org.json` 只接受严格 JSON。这里把 JSON5 文本规范化为严格 JSON，
 * 再交给 `org.json` 解析 —— 避免为了一个文件格式引入第三方依赖。
 *
 * 只覆盖 JSON5 中实际会被订阅文件用到的特性：
 * - 行注释与块注释
 * - 单引号字符串（转成双引号，转义内部双引号）
 * - 无引号键名
 * - 数组/对象尾随逗号
 */
object Json5 {

    /**
     * 规范化后解析为 JSON 对象。
     */
    fun parseObject(text: String): JSONObject = JSONObject(toStrictJson(text))

    /**
     * 规范化后解析为 JSON 数组。
     */
    fun parseArray(text: String): JSONArray = JSONArray(toStrictJson(text))

    /**
     * 把 JSON5 文本转换为严格 JSON 文本。
     */
    fun toStrictJson(text: String): String {
        val out = StringBuilder(text.length + text.length / 8)
        var i = 0
        val n = text.length

        // 记录最近一个非空白字符，用于判断键名位置
        var inString = false
        var quoteChar = '"'

        while (i < n) {
            val c = text[i]

            if (inString) {
                when {
                    c == '\\' && i + 1 < n -> {
                        val next = text[i + 1]
                        // 单引号字符串里 \' 是合法转义，但 JSON 不接受 \'
                        if (next == '\'' && quoteChar == '\'') {
                            out.append('\'')
                        } else {
                            // 其余转义序列原样保留
                            out.append(c).append(next)
                        }
                        i += 2
                        continue
                    }
                    c == quoteChar -> {
                        inString = false
                        out.append('"')
                        i++
                        continue
                    }
                    c == '"' && quoteChar == '\'' -> {
                        // 单引号字符串内部的未转义双引号需要转义
                        out.append("\\\"")
                        i++
                        continue
                    }
                    else -> {
                        out.append(c)
                        i++
                        continue
                    }
                }
            }

            when {
                // 注释
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') i++
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(n)
                }
                // 字符串起始
                c == '"' || c == '\'' -> {
                    inString = true
                    quoteChar = c
                    out.append('"')
                    i++
                }
                // 无引号键名：出现在 { 或 , 之后，且后面紧跟冒号
                (c.isLetter() || c == '_' || c == '$') && isKeyPosition(out) -> {
                    val start = i
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$' || text[i] == '-')) i++
                    val word = text.substring(start, i)
                    // 跳过空白看是否紧跟冒号
                    var j = i
                    while (j < n && text[j].isWhitespace()) j++
                    if (j < n && text[j] == ':') {
                        out.append('"').append(word).append('"')
                    } else {
                        // 不是键名（例如 true/false/null 字面量），原样输出
                        out.append(word)
                    }
                }
                // 尾随逗号：, 后面紧跟 } 或 ]
                c == ',' -> {
                    var j = i + 1
                    while (j < n && text[j].isWhitespace()) j++
                    if (j < n && (text[j] == '}' || text[j] == ']')) {
                        i++ // 丢弃该逗号
                    } else {
                        out.append(c)
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * 判断当前输出位置是否处于「期待键名」的状态。
     *
     * 即最近一个有效字符是 `{` 或 `,`（忽略空白）。
     */
    private fun isKeyPosition(out: StringBuilder): Boolean {
        for (k in out.length - 1 downTo 0) {
            val ch = out[k]
            if (ch.isWhitespace()) continue
            return ch == '{' || ch == ','
        }
        return true
    }
}
