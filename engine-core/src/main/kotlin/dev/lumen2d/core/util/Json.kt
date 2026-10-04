/**
 * Lumen2D — JSON support.
 *
 * The engine deliberately carries **zero third-party dependencies** for its data layer:
 * project files, scenes, prefabs, asset catalogs, save games and tilemaps are all JSON,
 * and this file provides a fast, allocation-friendly reader/writer that works identically
 * on Android (ART) and on a desktop JVM.
 *
 * Parsed values use plain Kotlin types so engine code stays ergonomic:
 *   object -> LinkedHashMap<String, Any?>
 *   array  -> ArrayList<Any?>
 *   string -> String, number -> Double, bool -> Boolean, null -> null
 * Helpers for typed reads live in [JsonAccessors].
 */
package dev.lumen2d.core.util

/** Thrown for malformed JSON with human-readable line/column information. */
class JsonParseException(message: String, val line: Int, val column: Int) :
    RuntimeException("$message (line $line, column $column)")

object Json {

    // ---------------------------------------------------------------- parsing

    /**
     * Parses [text] into plain Kotlin containers.
     * @param allowComments tolerate `//` and `/* */` comments (project files are author-edited).
     * @param allowTrailingCommas tolerate a trailing comma before `}` / `]`.
     */
    fun parse(text: String, allowComments: Boolean = true, allowTrailingCommas: Boolean = true): Any? =
        Parser(text, allowComments, allowTrailingCommas).parseDocument()

    /** Parses [text] and asserts the document is an object. */
    fun parseObject(text: String): MutableMap<String, Any?> {
        val v = parse(text)
        return v as? MutableMap<String, Any?>
            ?: throw JsonParseException("Expected a JSON object at the document root", 1, 1)
    }

    fun parseArray(text: String): List<Any?> {
        val v = parse(text)
        return v as? List<Any?>
            ?: throw JsonParseException("Expected a JSON array at the document root", 1, 1)
    }

    private class Parser(
        private val src: String,
        private val allowComments: Boolean,
        private val allowTrailingCommas: Boolean,
    ) {
        private var i = 0
        private var line = 1
        private var lineStart = 0

        private val column: Int get() = i - lineStart + 1

        fun parseDocument(): Any? {
            skipWs()
            if (i >= src.length) throw err("Unexpected end of input")
            val v = parseValue()
            skipWs()
            if (i < src.length) throw err("Unexpected trailing content '${src[i]}'")
            return v
        }

        private fun parseValue(): Any? {
            skipWs()
            if (i >= src.length) throw err("Unexpected end of input")
            return when (val c = src[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c == '+' || c.isDigit()) parseNumber()
                        else throw err("Unexpected character '$c'")
            }
        }

        private fun parseObject(): MutableMap<String, Any?> {
            expect("{")
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWs()
                if (allowTrailingCommas && peek() == '}') { i++; return out }
                if (peek() != '"') throw err("Expected a property name in double quotes")
                val key = parseString()
                skipWs()
                expect(":")
                val value = parseValue()
                out[key] = value
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw err("Expected ',' or '}' but found '$c'")
                }
            }
        }

        private fun parseArray(): MutableList<Any?> {
            expect("[")
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return out }
            while (true) {
                skipWs()
                if (allowTrailingCommas && peek() == ']') { i++; return out }
                out.add(parseValue())
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw err("Expected ',' or ']' but found '$c'")
                }
            }
        }

        private fun parseString(): String {
            expect("\"")
            val sb = StringBuilder()
            while (true) {
                if (i >= src.length) throw err("Unterminated string")
                when (val c = src[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= src.length) throw err("Unterminated escape sequence")
                        when (val e = src[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > src.length) throw err("Truncated \\u escape")
                                val hex = src.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: throw err("Invalid \\u escape '$hex'")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw err("Invalid escape '\\$e'")
                        }
                    }
                    '\n' -> { line++; lineStart = i; sb.append(c) }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Double {
            val start = i
            if (peek() == '-' || peek() == '+') i++
            while (i < src.length && (src[i].isDigit() || src[i] == '.' || src[i] == 'e' || src[i] == 'E' ||
                        ((src[i] == '-' || src[i] == '+') && (src[i - 1] == 'e' || src[i - 1] == 'E')))) i++
            val raw = src.substring(start, i)
            return raw.toDoubleOrNull() ?: throw err("Invalid number '$raw'")
        }

        private fun skipWs() {
            while (i < src.length) {
                val c = src[i]
                when {
                    c == '\n' -> { line++; i++; lineStart = i }
                    c == ' ' || c == '\t' || c == '\r' -> i++
                    c == '/' && allowComments && i + 1 < src.length && src[i + 1] == '/' -> {
                        while (i < src.length && src[i] != '\n') i++
                    }
                    c == '/' && allowComments && i + 1 < src.length && src[i + 1] == '*' -> {
                        i += 2
                        while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) {
                            if (src[i] == '\n') { line++; lineStart = i + 1 }
                            i++
                        }
                        i += 2
                    }
                    else -> return
                }
            }
        }

        private fun peek(): Char = if (i < src.length) src[i] else '\u0000'
        private fun next(): Char = if (i < src.length) src[i++] else throw err("Unexpected end of input")
        private fun expect(s: String) {
            if (i + s.length > src.length || src.regionMatches(i, s, 0, s.length)) { i += s.length; return }
            throw err("Expected '$s'")
        }
        private fun err(msg: String) = JsonParseException(msg, line, column)
    }

    // ------------------------------------------------------------- serializing

    fun stringify(value: Any?, pretty: Boolean = true, indent: Int = 2): String =
        StringBuilder().also { write(it, value, pretty, indent, 0) }.toString()

    private fun write(sb: StringBuilder, value: Any?, pretty: Boolean, indentSize: Int, depth: Int) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> sb.append(formatNumber(value))
            is String -> writeString(sb, value)
            is Map<*, *> -> {
                if (value.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                val entries = value.entries.toList()
                entries.forEachIndexed { index, (k, v) ->
                    if (index > 0) sb.append(',')
                    newline(sb, pretty, indentSize, depth + 1)
                    writeString(sb, k.toString())
                    sb.append(':')
                    if (pretty) sb.append(' ')
                    write(sb, v, pretty, indentSize, depth + 1)
                }
                newline(sb, pretty, indentSize, depth)
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = value.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                list.forEachIndexed { index, v ->
                    if (index > 0) sb.append(',')
                    newline(sb, pretty, indentSize, depth + 1)
                    write(sb, v, pretty, indentSize, depth + 1)
                }
                newline(sb, pretty, indentSize, depth)
                sb.append(']')
            }
            is FloatArray -> write(sb, value.toList(), pretty, indentSize, depth)
            is IntArray -> write(sb, value.toList(), pretty, indentSize, depth)
            else -> writeString(sb, value.toString())
        }
    }

    private fun newline(sb: StringBuilder, pretty: Boolean, indentSize: Int, depth: Int) {
        if (!pretty) return
        sb.append('\n')
        repeat(depth * indentSize) { sb.append(' ') }
    }

    private fun formatNumber(n: Number): String = when (n) {
        is Double -> if (n == n.toLong().toDouble() && kotlin.math.abs(n) < 1e15) n.toLong().toString() else n.toString()
        is Float -> {
            val d = n.toDouble()
            if (d == d.toLong().toDouble() && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
        }
        else -> n.toString()
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
        }
        sb.append('"')
    }
}

// --------------------------------------------------------------- typed accessors

object JsonAccessors

fun Map<String, Any?>.str(key: String, def: String = ""): String = this[key] as? String ?: def
fun Map<String, Any?>.num(key: String, def: Double = 0.0): Double = when (val v = this[key]) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull() ?: def
    is Boolean -> if (v) 1.0 else 0.0
    else -> def
}
fun Map<String, Any?>.flt(key: String, def: Float = 0f): Float = num(key, def.toDouble()).toFloat()
fun Map<String, Any?>.int(key: String, def: Int = 0): Int = num(key, def.toDouble()).toInt()
fun Map<String, Any?>.long(key: String, def: Long = 0L): Long = num(key, def.toDouble()).toLong()
fun Map<String, Any?>.bool(key: String, def: Boolean = false): Boolean = when (val v = this[key]) {
    is Boolean -> v
    is Number -> v.toDouble() != 0.0
    is String -> v.equals("true", true) || v == "1"
    else -> def
}
fun Map<String, Any?>.obj(key: String): MutableMap<String, Any?>? = this[key] as? MutableMap<String, Any?>
fun Map<String, Any?>.arr(key: String): List<Any?> = this[key] as? List<Any?> ?: emptyList()
fun Map<String, Any?>.strList(key: String): List<String> = arr(key).map { it?.toString() ?: "" }
fun Map<String, Any?>.mapList(key: String): List<MutableMap<String, Any?>> =
    arr(key).mapNotNull { it as? MutableMap<String, Any?> }
fun Map<String, Any?>.fltList(key: String): List<Float> = arr(key).mapNotNull { (it as? Number)?.toFloat() }
fun Map<String, Any?>.intList(key: String): List<Int> = arr(key).mapNotNull { (it as? Number)?.toInt() }

/** Deep-freezes a parsed JSON tree into immutable containers (used for asset metadata caching). */
fun freezeJson(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.entries.associate { it.key.toString() to freezeJson(it.value) }
    is List<*> -> value.map { freezeJson(it) }
    else -> value
}

/** Deep-copies a JSON tree so callers can mutate without aliasing. */
fun deepCopyJson(value: Any?): Any? = when (value) {
    is Map<*, *> -> LinkedHashMap<String, Any?>().also { out -> value.forEach { (k, v) -> out[k.toString()] = deepCopyJson(v) } }
    is List<*> -> ArrayList<Any?>().also { out -> value.forEach { out.add(deepCopyJson(it)) } }
    else -> value
}

/** Merges [overrides] into a deep copy of [base] (recursive for nested objects). */
fun mergeJson(base: Map<String, Any?>?, overrides: Map<String, Any?>?): LinkedHashMap<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    base?.forEach { (k, v) -> out[k] = deepCopyJson(v) }
    overrides?.forEach { (k, v) ->
        val existing = out[k]
        out[k] = if (existing is Map<*, *> && v is Map<*, *>)
            mergeJson(existing as Map<String, Any?>, v as Map<String, Any?>) else deepCopyJson(v)
    }
    return out
}
