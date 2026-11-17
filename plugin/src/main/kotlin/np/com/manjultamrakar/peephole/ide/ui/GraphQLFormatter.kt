package np.com.manjultamrakar.peephole.ide.ui

/**
 * Small, dependency-free GraphQL document pretty-printer.
 *
 * Selection sets are broken onto one field per line and indented; argument lists, variable
 * definitions and input objects stay on one line. Returns null when the text can't be tokenized
 * or its brackets don't balance, so callers can fall back to the original text.
 */
internal object GraphQLFormatter {
    private enum class Kind { NAME, PUNCT, STRING, NUMBER, COMMENT }
    private data class Tok(val kind: Kind, val text: String)

    fun formatOrNull(source: String): String? = try {
        format(tokenize(source))
    } catch (_: RuntimeException) {
        null
    }

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() || c == '﻿' -> i++
                c == '#' -> {
                    val end = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    out += Tok(Kind.COMMENT, s.substring(i, end).trimEnd()); i = end
                }
                s.startsWith("\"\"\"", i) -> {
                    var j = i + 3
                    while (true) {
                        if (j >= s.length) error("unterminated block string")
                        if (s[j] == '\\' && s.startsWith("\"\"\"", j + 1)) { j += 4; continue }
                        if (s.startsWith("\"\"\"", j)) break
                        j++
                    }
                    out += Tok(Kind.STRING, s.substring(i, j + 3)); i = j + 3
                }
                c == '"' -> {
                    var j = i + 1
                    while (true) {
                        if (j >= s.length || s[j] == '\n') error("unterminated string")
                        if (s[j] == '\\') { j += 2; continue }
                        if (s[j] == '"') break
                        j++
                    }
                    out += Tok(Kind.STRING, s.substring(i, j + 1)); i = j + 1
                }
                s.startsWith("...", i) -> { out += Tok(Kind.PUNCT, "..."); i += 3 }
                c in "{}()[]:=!@\$|&," -> { out += Tok(Kind.PUNCT, c.toString()); i++ }
                c == '_' || c.isLetter() -> {
                    var j = i + 1
                    while (j < s.length && (s[j] == '_' || s[j].isLetterOrDigit())) j++
                    out += Tok(Kind.NAME, s.substring(i, j)); i = j
                }
                c == '-' || c.isDigit() -> {
                    var j = i + 1
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '.' || s[j] == '+' || s[j] == '-')) j++
                    out += Tok(Kind.NUMBER, s.substring(i, j)); i = j
                }
                else -> error("unexpected '$c'")
            }
        }
        return out
    }

    private fun format(toks: List<Tok>): String {
        val sb = StringBuilder()
        // 'S' = selection set (structural braces), 'A' = parentheses, 'O' = input object, 'L' = list.
        val stack = ArrayDeque<Char>()
        var atLineStart = true
        var blankLinePending = false
        var prev: Tok? = null
        var prevPrev: Tok? = null

        fun newline() {
            if (atLineStart) return
            while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
            if (sb.isNotEmpty()) sb.append('\n')
            atLineStart = true
        }
        fun emit(text: String, space: Boolean) {
            if (atLineStart) {
                if (blankLinePending && sb.isNotEmpty()) sb.append('\n')
                blankLinePending = false
                repeat(stack.count { it == 'S' }) { sb.append("  ") }
                atLineStart = false
            } else if (space) {
                sb.append(' ')
            }
            sb.append(text)
        }
        fun pop(expected: Char) {
            if (stack.removeLast() != expected) error("unbalanced")
        }

        for (tok in toks) {
            val structural = 'A' !in stack
            val t = tok.text
            when {
                tok.kind == Kind.COMMENT -> { emit(t, true); newline() }
                tok.kind == Kind.PUNCT && t == "{" && structural -> {
                    emit("{", true); stack.addLast('S'); newline()
                }
                tok.kind == Kind.PUNCT && t == "}" && stack.lastOrNull() == 'S' -> {
                    pop('S'); newline(); emit("}", false); newline()
                    if (stack.isEmpty()) blankLinePending = true
                }
                tok.kind == Kind.PUNCT && t == "," && structural -> Unit // insignificant in selection sets
                tok.kind == Kind.PUNCT && t == "(" -> { emit("(", false); stack.addLast('A') }
                tok.kind == Kind.PUNCT && t == ")" -> { pop('A'); emit(")", false) }
                tok.kind == Kind.PUNCT && t == "[" -> { emit("[", needsSpace(prev, tok)); stack.addLast('L') }
                tok.kind == Kind.PUNCT && t == "]" -> { pop('L'); emit("]", false) }
                tok.kind == Kind.PUNCT && t == "{" -> { emit("{", needsSpace(prev, tok)); stack.addLast('O') }
                tok.kind == Kind.PUNCT && t == "}" -> { pop('O'); emit("}", true) }
                else -> {
                    if (structural && 'S' in stack && !atLineStart && startsSelection(tok, prev, prevPrev)) newline()
                    emit(t, needsSpace(prev, tok))
                }
            }
            if (tok.kind != Kind.COMMENT) { prevPrev = prev; prev = tok }
        }
        if (stack.isNotEmpty()) error("unbalanced")
        return sb.toString().trimEnd() + "\n"
    }

    /** A field or spread starts a new line unless it continues an alias, directive or `... on Type`. */
    private fun startsSelection(tok: Tok, prev: Tok?, prevPrev: Tok?): Boolean {
        if (!(tok.kind == Kind.NAME || tok.text == "...")) return false
        prev ?: return false
        if (prev.kind == Kind.NAME) return !(prev.text == "on" && prevPrev?.text == "...")
        return prev.text == ")" || prev.kind == Kind.STRING || prev.kind == Kind.NUMBER
    }

    private fun needsSpace(prev: Tok?, tok: Tok): Boolean {
        prev ?: return false
        if (tok.text in setOf(")", "]", "!", ":", ",")) return false
        if (prev.kind == Kind.PUNCT && prev.text in setOf("(", "[", "$", "@")) return false
        if (prev.text == "...") return tok.text == "on"
        return true
    }
}
