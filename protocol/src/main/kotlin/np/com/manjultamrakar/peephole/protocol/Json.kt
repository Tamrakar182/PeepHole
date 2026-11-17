package np.com.manjultamrakar.peephole.protocol

/**
 * Minimal, dependency-free JSON reader/writer.
 *
 * The protocol module is shared by the Android library and the IDE plugin; keeping it free of
 * serialization libraries avoids version clashes with whatever the app or the IDE already bundles.
 *
 * Values map to: `Map<String, Any?>`, `List<Any?>`, `String`, `Long`, `Double`, `Boolean`, `null`.
 */
object Json {

    fun parse(text: String): Any? = Parser(text).parseDocument()

    /** Returns null instead of throwing when [text] is not valid JSON. */
    fun parseOrNull(text: String): Any? = try {
        parse(text)
    } catch (_: JsonException) {
        null
    }

    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, value, pretty, 0)
        return sb.toString()
    }

    /** Pretty-prints [text] if it is JSON, otherwise returns null. */
    fun prettyOrNull(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '[')) return null
        val parsed = parseOrNull(trimmed) ?: return null
        return stringify(parsed, pretty = true)
    }

    private fun write(sb: StringBuilder, value: Any?, pretty: Boolean, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(value)
            is Double -> if (value.isNaN() || value.isInfinite()) sb.append("null") else {
                if (value == Math.floor(value) && Math.abs(value) < 1e15) sb.append(value.toLong()) else sb.append(value)
            }
            is Float -> write(sb, value.toDouble(), pretty, indent)
            is Number -> sb.append(value.toLong())
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}"); return
                }
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newline(sb, indent + 1)
                    writeString(sb, k.toString())
                    sb.append(if (pretty) ": " else ":")
                    write(sb, v, pretty, indent + 1)
                }
                if (pretty) newline(sb, indent)
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = value.toList()
                if (list.isEmpty()) {
                    sb.append("[]"); return
                }
                sb.append('[')
                list.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    if (pretty) newline(sb, indent + 1)
                    write(sb, v, pretty, indent + 1)
                }
                if (pretty) newline(sb, indent)
                sb.append(']')
            }
            is Array<*> -> write(sb, value.toList(), pretty, indent)
            is Enum<*> -> writeString(sb, value.name)
            else -> writeString(sb, value.toString())
        }
    }

    private fun newline(sb: StringBuilder, indent: Int) {
        sb.append('\n')
        repeat(indent) { sb.append("  ") }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            skipWs()
            val v = parseValue(0)
            skipWs()
            if (i != s.length) fail("Unexpected trailing content")
            return v
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > 512) fail("Nesting too deep")
            skipWs()
            if (i >= s.length) fail("Unexpected end of input")
            return when (val c = s[i]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') parseNumber() else fail("Unexpected character '$c'")
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (peek() == '}') {
                i++; return map
            }
            while (true) {
                skipWs()
                if (peek() != '"') fail("Expected string key")
                val key = parseString()
                skipWs()
                expect(':')
                map[key] = parseValue(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++; return map
                    }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (peek() == ']') {
                i++; return list
            }
            while (true) {
                list.add(parseValue(depth + 1))
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++; return list
                    }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("Unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Bad unicode escape")
                                val hex = s.substring(i, i + 4)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: fail("Bad unicode escape"))
                                i += 4
                            }
                            else -> fail("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var isDecimal = false
            if (i < s.length && s[i] == '.') {
                isDecimal = true
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDecimal = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val text = s.substring(start, i)
            if (text == "-" || text.isEmpty()) fail("Bad number")
            return if (isDecimal) text.toDouble() else text.toLongOrNull() ?: text.toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Unexpected token")
            i += word.length
            return value
        }

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null

        private fun expect(c: Char) {
            if (peek() != c) fail("Expected '$c'")
            i++
        }

        private fun fail(msg: String): Nothing = throw JsonException("$msg at offset $i")
    }
}

class JsonException(message: String) : RuntimeException(message)
