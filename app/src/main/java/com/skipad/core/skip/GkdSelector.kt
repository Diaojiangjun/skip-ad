package com.skipad.core.skip

/**
 * GKD 选择器求值器（属性子集）。
 *
 * GKD 的选择器是 CSS 风格的节点查询语言，完整语法包含层级组合符
 * （`>`、`<`、`+`、`-`、`<<n`、`>(1,2)`）与目标标记 `@`。
 * 那部分需要构建完整节点树关系才能求值，本引擎不实现。
 *
 * 这里只求值**纯属性选择器**，例如：
 * ```
 * [text="跳过"][visibleToUser=true]
 * [childCount=0][text*="跳过"]
 * [(text.length<10&&(text*="跳过"||desc*="skip")) || vid*="skip"]
 * [text="a"] || [text="b"]
 * ```
 * 这类规则在社区订阅中约占 40%，且恰好覆盖「开屏广告跳过按钮」这一核心场景。
 *
 * 含结构组合符的选择器会被判定为 [isSupported] = false，由调用方直接跳过，
 * 而不是猜一个近似语义 —— 猜错会导致误点。
 */
object GkdSelector {

    /**
     * 判断选择器是否可被本引擎求值。
     */
    fun isSupported(selector: String): Boolean = toExpression(selector) != null

    /**
     * 编译选择器为谓词。不支持的语法返回 null。
     */
    fun compile(selector: String): ((SkipNode) -> Boolean)? {
        val expression = toExpression(selector) ?: return null
        return try {
            Parser(expression).parse()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 把选择器转换为布尔表达式文本。
     *
     * `[a][b]` → `(a) && (b)`；`||` 保持为或运算。
     * 遇到括号外出现结构组合符则返回 null。
     */
    private fun toExpression(selector: String): String? {
        val sb = StringBuilder(selector.length + 16)
        var i = 0
        var lastWasGroup = false
        var groupCount = 0

        while (i < selector.length) {
            val c = selector[i]
            when {
                c == '[' -> {
                    // 找到配对的 ]（不处理嵌套，GKD 选择器内不会出现）
                    val end = selector.indexOf(']', i)
                    if (end < 0) return null
                    val inner = selector.substring(i + 1, end)
                    if (inner.isBlank()) return null
                    if (lastWasGroup) sb.append(" && ")
                    sb.append('(').append(inner).append(')')
                    lastWasGroup = true
                    groupCount++
                    i = end + 1
                }
                c.isWhitespace() -> i++
                c == '|' && selector.getOrNull(i + 1) == '|' -> {
                    sb.append(" || ")
                    lastWasGroup = false
                    i += 2
                }
                c == '&' && selector.getOrNull(i + 1) == '&' -> {
                    sb.append(" && ")
                    lastWasGroup = false
                    i += 2
                }
                // 结构组合符或目标标记：不支持
                c == '>' || c == '<' || c == '+' || c == '-' || c == '@' || c == '(' -> return null
                else -> return null
            }
        }
        return if (groupCount == 0) null else sb.toString()
    }

    /**
     * 递归下降解析器。
     *
     * 文法：
     * ```
     * or      := and ('||' and)*
     * and     := unary ('&&' unary)*
     * unary   := '!' unary | '(' or ')' | predicate
     * predicate := identifier operator value
     * ```
     */
    private class Parser(private val text: String) {
        private var pos = 0

        fun parse(): (SkipNode) -> Boolean {
            val expr = parseOr()
            skipWhitespace()
            if (pos < text.length) throw IllegalStateException("未消费的输入: ${text.substring(pos)}")
            return expr
        }

        private fun parseOr(): (SkipNode) -> Boolean {
            var left = parseAnd()
            while (true) {
                skipWhitespace()
                if (!consume("||")) break
                val right = parseAnd()
                val l = left
                left = { node -> l(node) || right(node) }
            }
            return left
        }

        private fun parseAnd(): (SkipNode) -> Boolean {
            var left = parseUnary()
            while (true) {
                skipWhitespace()
                if (!consume("&&")) break
                val right = parseUnary()
                val l = left
                left = { node -> l(node) && right(node) }
            }
            return left
        }

        private fun parseUnary(): (SkipNode) -> Boolean {
            skipWhitespace()
            if (consume("!")) {
                // 避免把 != 当成否定
                if (peek() == '=') {
                    throw IllegalStateException("非法的一元运算")
                }
                val inner = parseUnary()
                return { node -> !inner(node) }
            }
            if (consume("(")) {
                val inner = parseOr()
                skipWhitespace()
                if (!consume(")")) throw IllegalStateException("缺少右括号")
                return inner
            }
            return parsePredicate()
        }

        private fun parsePredicate(): (SkipNode) -> Boolean {
            val identifier = readIdentifier().lowercase()
            skipWhitespace()
            val operator = readOperator()
            skipWhitespace()
            val literal = readValue()

            return { node ->
                val attribute = attributeOf(node, identifier)
                compare(attribute, operator, literal)
            }
        }

        private fun readIdentifier(): String {
            skipWhitespace()
            val start = pos
            while (pos < text.length &&
                (text[pos].isLetterOrDigit() || text[pos] == '_' || text[pos] == '.')
            ) {
                pos++
            }
            if (start == pos) throw IllegalStateException("期待属性名 @ $pos")
            return text.substring(start, pos)
        }

        private fun readOperator(): String {
            for (op in OPERATORS) {
                if (text.startsWith(op, pos)) {
                    pos += op.length
                    return op
                }
            }
            throw IllegalStateException("期待运算符 @ $pos")
        }

        private fun readValue(): Any? {
            skipWhitespace()
            if (pos >= text.length) throw IllegalStateException("期待值")
            val c = text[pos]
            if (c == '"' || c == '\'') {
                pos++
                val sb = StringBuilder()
                while (pos < text.length && text[pos] != c) {
                    if (text[pos] == '\\' && pos + 1 < text.length) {
                        sb.append(text[pos + 1])
                        pos += 2
                    } else {
                        sb.append(text[pos])
                        pos++
                    }
                }
                if (pos >= text.length) throw IllegalStateException("字符串未闭合")
                pos++
                return sb.toString()
            }
            val start = pos
            while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '.' || text[pos] == '-')) {
                pos++
            }
            val raw = text.substring(start, pos)
            return when (raw.lowercase()) {
                "true" -> true
                "false" -> false
                "null" -> null
                else -> raw.toDoubleOrNull() ?: raw
            }
        }

        private fun peek(): Char? = text.getOrNull(pos)

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun consume(token: String): Boolean {
            if (text.startsWith(token, pos)) {
                pos += token.length
                return true
            }
            return false
        }
    }

    private val OPERATORS = listOf("!*=", "!^=", "!$=", "*=", "^=", "$=", "!=", ">=", "<=", "=", ">", "<")

    /**
     * 读取节点属性。未知属性返回 null。
     */
    private fun attributeOf(node: SkipNode, identifier: String): Any? = when (identifier) {
        "text" -> node.text
        "desc" -> node.desc
        "vid" -> node.resourceId?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        "id" -> node.resourceId
        "class", "classname" -> node.className
        "clickable" -> node.clickable
        "visibletouser" -> node.visibleToUser
        "childcount" -> node.childCount
        "depth" -> node.depth
        "text.length" -> node.text?.length ?: 0
        "width" -> node.bounds.width
        "height" -> node.bounds.height
        else -> null
    }

    /**
     * 按运算符比较属性值与字面量。
     */
    private fun compare(attribute: Any?, operator: String, literal: Any?): Boolean {
        return when (operator) {
            "=" -> equalsValue(attribute, literal)
            "!=" -> !equalsValue(attribute, literal)
            "*=" -> stringOp(attribute, literal) { a, b -> a.contains(b) }
            "^=" -> stringOp(attribute, literal) { a, b -> a.startsWith(b) }
            "$=" -> stringOp(attribute, literal) { a, b -> a.endsWith(b) }
            "!*=" -> stringOp(attribute, literal) { a, b -> !a.contains(b) }
            "!^=" -> stringOp(attribute, literal) { a, b -> !a.startsWith(b) }
            "!$=" -> stringOp(attribute, literal) { a, b -> !a.endsWith(b) }
            ">", "<", ">=", "<=" -> numericOp(attribute, literal, operator)
            else -> false
        }
    }

    private fun equalsValue(attribute: Any?, literal: Any?): Boolean {
        if (attribute == null && literal == null) return true
        if (attribute == null || literal == null) return false
        return when {
            attribute is Boolean && literal is Boolean -> attribute == literal
            attribute is Number && literal is Number -> attribute.toDouble() == literal.toDouble()
            else -> attribute.toString() == literal.toString()
        }
    }

    private fun stringOp(
        attribute: Any?,
        literal: Any?,
        op: (String, String) -> Boolean,
    ): Boolean {
        val a = attribute?.toString() ?: return false
        val b = literal?.toString() ?: return false
        if (b.isEmpty()) return false
        return op(a, b)
    }

    private fun numericOp(attribute: Any?, literal: Any?, operator: String): Boolean {
        val a = (attribute as? Number)?.toDouble() ?: return false
        val b = (literal as? Number)?.toDouble() ?: return false
        return when (operator) {
            ">" -> a > b
            "<" -> a < b
            ">=" -> a >= b
            "<=" -> a <= b
            else -> false
        }
    }

    /**
     * 从**结构型选择器**中提取目标节点的属性条件。
     *
     * GKD 用 `@` 标记要点击的目标节点，例如：
     * ```
     * @ImageView[vid="obfuscated"][text=null][childCount=0] <2 LinearLayout < [id="android:id/content"]
     * ```
     * 结构部分（`<2`、`> ...`）需要完整节点树关系，本引擎无法求值；
     * 但 `@` 之后那段属性条件本身是可以独立判断的。
     *
     * 为避免误点，提取时要求满足：
     * 1. 目标属性里必须至少有一个 **带字面量的 text / desc / vid / id**
     * 2. 只保留引擎支持的属性，未知属性（如 `index`、`checked`）整条丢弃
     * 3. 属性组内不含 `&&`/`||` 组合（含组合则视为不可安全提取）
     *
     * @return 可编译的规范化选择器；无法安全提取时返回 null
     */
    fun normalizeTarget(selector: String): String? {
        val at = selector.indexOf('@')
        if (at < 0) return null

        // 截取 @ 之后直到结构组合符（括号外）
        val rest = selector.substring(at + 1)
        var depth = 0
        var end = rest.length
        for (i in rest.indices) {
            val c = rest[i]
            when {
                c == '[' -> depth++
                c == ']' -> depth = (depth - 1).coerceAtLeast(0)
                depth == 0 && (c == '<' || c == '>' || c == '+' || c == '-' || c == '~') -> {
                    end = i
                    break
                }
            }
        }
        val segment = rest.substring(0, end).trim()
        if (segment.isEmpty()) return null

        // 提取所有 [ ... ] 属性组
        val groups = ArrayList<String>(4)
        var i = 0
        while (i < segment.length) {
            if (segment[i] == '[') {
                val close = segment.indexOf(']', i)
                if (close < 0) return null
                groups += segment.substring(i + 1, close).trim()
                i = close + 1
            } else {
                i++
            }
        }
        if (groups.isEmpty()) return null

        val kept = ArrayList<String>(groups.size)
        var hasSpecificLiteral = false

        for (group in groups) {
            // 含逻辑组合的属性组不做拆分，直接放弃整条规则
            if (group.contains("&&") || group.contains("||")) return null

            val predicate = parseSinglePredicate(group) ?: return null
            val (identifier, operator, literal) = predicate

            // `index` 是纯位置条件，忽略它只会略微放宽匹配范围，可以安全丢弃；
            // 其余未支持的属性（checked / selected / focused 等）属于状态条件，
            // 丢弃会改变语义，整条规则放弃。
            if (identifier == "index") continue
            if (identifier !in SUPPORTED_ATTRIBUTES) return null

            if (literal is String && literal.length >= 2 &&
                identifier in listOf("text", "desc", "vid", "id")
            ) {
                hasSpecificLiteral = true
            }
            kept += group
        }

        if (!hasSpecificLiteral) return null
        // 用相邻方括号拼接：本引擎把 `[a][b]` 解释为 a && b
        return kept.joinToString("") { "[$it]" }
    }

    /**
     * 解析单个 `属性 运算符 值`，失败返回 null。
     */
    private fun parseSinglePredicate(group: String): Triple<String, String, Any?>? {
        val matcher = SINGLE_PREDICATE.matchEntire(group) ?: return null
        val identifier = matcher.groupValues[1].lowercase()
        val operator = matcher.groupValues[2]
        val rawValue = matcher.groupValues[3]

        val literal: Any? = when {
            rawValue.startsWith("\"") && rawValue.endsWith("\"") && rawValue.length >= 2 ->
                rawValue.substring(1, rawValue.length - 1)
            rawValue.equals("null", ignoreCase = true) -> null
            rawValue.equals("true", ignoreCase = true) -> true
            rawValue.equals("false", ignoreCase = true) -> false
            else -> rawValue.toDoubleOrNull() ?: rawValue
        }
        return Triple(identifier, operator, literal)
    }

    /** 单个属性条件的正则：属性名 / 运算符 / 值。 */
    private val SINGLE_PREDICATE =
        Regex("""^([A-Za-z_][A-Za-z0-9_.]*)\s*(!\*=|!\^=|!\$=|\*=|\^=|\$=|!=|>=|<=|=|>|<)\s*(.+)$""")

    /** 引擎支持的属性名（小写）。 */
    private val SUPPORTED_ATTRIBUTES = setOf(
        "text", "desc", "vid", "id", "class", "classname",
        "clickable", "visibletouser", "childcount", "depth",
        "text.length", "width", "height",
    )

}
