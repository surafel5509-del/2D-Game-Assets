/**
 * Lumen2D — LumenScript.
 *
 * A small, dependency-free scripting language for gameplay code. It is deliberately close to
 * Godot's GDScript in spirit (and to Python in syntax) but uses braces rather than indentation
 * so scripts stay easy to embed, diff and hot-reload:
 *
 * ```lumen
 * export var speed: = 120.0
 * var coyote := 0.0
 *
 * func ready() {
 *     add_to_group("player")
 * }
 *
 * func physics_process(delta) {
 *     var dir = input.axis("move").x
 *     velocity.x = dir * speed
 *     if input.just_pressed("jump") and is_on_floor() {
 *         velocity.y = -jump_force
 *     }
 *     move_and_slide(delta)
 * }
 * ```
 *
 * The interpreter is a straightforward tree-walking evaluator: tokenizer -> recursive descent
 * parser -> AST -> evaluation against [Env] chains. It has no JVM reflection, no codegen and no
 * external dependencies, so the exact same code runs on Android, desktop and in unit tests.
 *
 * Design notes:
 *  * Every value is a Kotlin `Any?` — null, Boolean, Double, String, List, Map, [ScriptFunction],
 *    [ScriptStruct] (vectors/colours) or a [ScriptForeign] wrapper around an engine object.
 *  * Control flow uses lightweight throwables ([ReturnSignal], ...) so no special return
 *    plumbing is needed inside the evaluator.
 *  * Member assignment back-writes through the chain (`node.position.x = 5`), which keeps the
 *    engine's immutable [dev.lumen2d.core.math.Vec2] cheap while scripts stay ergonomic.
 */
package dev.lumen2d.core.script

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.MathUtil
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// ------------------------------------------------------------------------------ errors

/** Raised for syntax and runtime problems; carries the source line so the editor can point at it. */
class ScriptError(message: String, val line: Int = 0, val sourcePath: String = "") : RuntimeException(message) {
    /** Human readable location ("player.lumen:12: ..."). */
    val location: String get() = if (sourcePath.isEmpty()) "line $line" else "$sourcePath:$line"
    val prettyMessage: String get() = "$location: $message"
}

// ------------------------------------------------------------------------------ tokens

internal enum class TokKind { NUMBER, STRING, IDENT, KEYWORD, OP, EOF }

internal class Token(val kind: TokKind, val text: String, val line: Int, val number: Double = 0.0) {
    fun isOp(op: String) = kind == TokKind.OP && text == op
    fun isKeyword(word: String) = kind == TokKind.KEYWORD && text == word
    override fun toString() = "$text(${kind.name.lowercase()}@$line)"
}

private val KEYWORDS = setOf(
    "var", "const", "export", "func", "if", "elif", "else", "while", "for", "in",
    "break", "continue", "return", "and", "or", "not", "true", "false", "null", "self",
)

internal class Lexer(private val source: String, private val path: String) {
    private var index = 0
    private var line = 1
    private val tokens = ArrayList<Token>()

    fun tokenize(): List<Token> {
        while (index < source.length) {
            val c = source[index]
            when {
                c == '\n' -> { line++; index++ }
                c == ' ' || c == '\t' || c == '\r' -> index++
                c == '#' -> skipComment()
                c == '"' || c == '\'' -> readString(c)
                c.isDigit() || (c == '.' && index + 1 < source.length && source[index + 1].isDigit()) -> readNumber()
                c.isLetter() || c == '_' -> readIdent()
                else -> readOperator()
            }
        }
        tokens.add(Token(TokKind.EOF, "<eof>", line))
        return tokens
    }

    private fun skipComment() {
        while (index < source.length && source[index] != '\n') index++
    }

    private fun readString(quote: Char) {
        index++
        val sb = StringBuilder()
        while (index < source.length && source[index] != quote) {
            val c = source[index]
            if (c == '\\' && index + 1 < source.length) {
                index++
                when (val esc = source[index]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    '\'' -> sb.append('\'')
                    '0' -> sb.append('\u0000')
                    else -> sb.append(esc)
                }
            } else {
                if (c == '\n') line++
                sb.append(c)
            }
            index++
        }
        if (index >= source.length) throw ScriptError("unterminated string", line, path)
        index++
        tokens.add(Token(TokKind.STRING, sb.toString(), line))
    }

    private fun readNumber() {
        val start = index
        var seenDot = false
        while (index < source.length) {
            val c = source[index]
            if (c.isDigit()) { index++ } else if (c == '.' && !seenDot && index + 1 < source.length && source[index + 1].isDigit()) {
                seenDot = true; index++
            } else if (c == 'e' || c == 'E') {
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
            } else break
        }
        val text = source.substring(start, index)
        val value = text.toDoubleOrNull() ?: throw ScriptError("bad number literal '$text'", line, path)
        tokens.add(Token(TokKind.NUMBER, text, line, value))
    }

    private fun readIdent() {
        val start = index
        while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
        val text = source.substring(start, index)
        tokens.add(Token(if (text in KEYWORDS) TokKind.KEYWORD else TokKind.IDENT, text, line))
    }

    private fun readOperator() {
        // Longest match first so "==" wins over "=", ".." over ".", "+=" over "+".
        for (op in listOf("==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=", "..", "->")) {
            if (source.startsWith(op, index)) {
                tokens.add(Token(TokKind.OP, op, line)); index += op.length; return
            }
        }
        val c = source[index]
        if (c in "+-*/%()[]{},.:<>!=?") {
            tokens.add(Token(TokKind.OP, c.toString(), line)); index++; return
        }
        throw ScriptError("unexpected character '$c'", line, path)
    }
}

// ------------------------------------------------------------------------------ AST

internal sealed class Expr(val line: Int) {
    class Literal(val value: Any?, line: Int) : Expr(line)
    class Variable(val name: String, line: Int) : Expr(line)
    class SelfExpr(line: Int) : Expr(line)
    class ListLiteral(val items: List<Expr>, line: Int) : Expr(line)
    class DictLiteral(val entries: List<Pair<Expr, Expr>>, line: Int) : Expr(line)
    class Unary(val op: String, val operand: Expr, line: Int) : Expr(line)
    class Binary(val op: String, val left: Expr, val right: Expr, line: Int) : Expr(line)
    class Logical(val op: String, val left: Expr, val right: Expr, line: Int) : Expr(line)
    class Get(val target: Expr, val name: String, line: Int) : Expr(line)
    class Index(val target: Expr, val index: Expr, line: Int) : Expr(line)
    class Call(val callee: Expr, val args: List<Expr>, line: Int) : Expr(line)
    /** `target = value` / `target += value` (compounds are lowered to `target = target op value`). */
    class Assign(val target: Expr, val value: Expr, line: Int) : Expr(line)
    class FunctionExpr(val params: List<String>, val body: List<Stmt>, line: Int) : Expr(line)
    internal fun visit(visitor: Interpreter, env: Env): Any? = visitor.evalExpr(this, env)
}

internal sealed class Stmt(val line: Int) {
    class VarDecl(val name: String, val initializer: Expr?, val exported: Boolean, val isConst: Boolean, line: Int) : Stmt(line)
    class FunctionDecl(val name: String, val params: List<String>, val body: List<Stmt>, val exported: Boolean, line: Int) : Stmt(line)
    class Expression(val expr: Expr, line: Int) : Stmt(line)
    class If(val branches: List<Pair<Expr, List<Stmt>>>, val elseBody: List<Stmt>?, line: Int) : Stmt(line)
    class While(val condition: Expr, val body: List<Stmt>, line: Int) : Stmt(line)
    class For(val variable: String, val iterable: Expr, val body: List<Stmt>, line: Int) : Stmt(line)
    class Return(val value: Expr?, line: Int) : Stmt(line)
    class Break(line: Int) : Stmt(line)
    class Continue(line: Int) : Stmt(line)
}

/** A compiled script: declarations plus the top-level body executed when the instance is created. */
class ScriptProgram internal constructor(
    internal val statements: List<Stmt>,
    /** Names of `export var` declarations, in declaration order (inspector properties). */
    val exports: List<String>,
    /** Names of `export func` declarations (callable from other scripts and C-like hosts). */
    val exportedFunctions: List<String>,
    val path: String = "",
)

internal class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)
internal class BreakSignal : RuntimeException(null, null, false, false)
internal class ContinueSignal : RuntimeException(null, null, false, false)

// ------------------------------------------------------------------------------ parser

private val ASSIGN_OPS = setOf("=", "+=", "-=", "*=", "/=", "%=")

internal class Parser(private val tokens: List<Token>, private val path: String) {
    private var pos = 0
    private val exports = ArrayList<String>()
    private val exportedFunctions = ArrayList<String>()

    fun parseProgram(): ScriptProgram {
        val statements = ArrayList<Stmt>()
        while (!check(TokKind.EOF)) statements.add(parseStatement())
        return ScriptProgram(statements, exports.toList(), exportedFunctions.toList(), path)
    }

    private fun peek(offset: Int = 0): Token = tokens[min(pos + offset, tokens.size - 1)]
    private fun advance(): Token = tokens[pos++]
    private fun check(kind: TokKind, text: String? = null): Boolean {
        val t = peek()
        return t.kind == kind && (text == null || t.text == text)
    }

    private fun matchKeyword(vararg words: String): Boolean {
        if (peek().kind == TokKind.KEYWORD && peek().text in words) { pos++; return true }
        return false
    }

    private fun matchOp(vararg ops: String): Boolean {
        if (peek().kind == TokKind.OP && peek().text in ops) { pos++; return true }
        return false
    }

    private fun expectOp(op: String): Token {
        if (!check(TokKind.OP, op)) throw ScriptError("expected '$op' but found '${peek().text}'", peek().line, path)
        return advance()
    }

    private fun expectIdent(): Token {
        if (peek().kind != TokKind.IDENT) throw ScriptError("expected a name but found '${peek().text}'", peek().line, path)
        return advance()
    }

    private fun parseStatement(): Stmt {
        val line = peek().line
        if (check(TokKind.KEYWORD, "export")) {
            advance()
            return when {
                check(TokKind.KEYWORD, "var") || check(TokKind.KEYWORD, "const") -> parseVarDecl(true, line)
                check(TokKind.KEYWORD, "func") -> parseFunctionDecl(true, line)
                else -> throw ScriptError("'export' must be followed by var, const or func", line, path)
            }
        }
        if (check(TokKind.KEYWORD, "var") || check(TokKind.KEYWORD, "const")) return parseVarDecl(false, line)
        if (check(TokKind.KEYWORD, "func")) return parseFunctionDecl(false, line)
        if (matchKeyword("if")) return parseIf(line)
        if (matchKeyword("while")) {
            val condition = parseExpression()
            return Stmt.While(condition, parseBlock(), line)
        }
        if (matchKeyword("for")) {
            val name = expectIdent().text
            if (!matchKeyword("in")) throw ScriptError("expected 'in' after the loop variable", peek().line, path)
            val iterable = parseExpression()
            return Stmt.For(name, iterable, parseBlock(), line)
        }
        if (matchKeyword("return")) {
            val value = if (check(TokKind.OP, "}") || check(TokKind.EOF)) null else parseExpression()
            return Stmt.Return(value, line)
        }
        if (matchKeyword("break")) return Stmt.Break(line)
        if (matchKeyword("continue")) return Stmt.Continue(line)

        return parseExpressionStatement(line)
    }

    /** `foo()`, `a = b`, `node.position.x += 2` — statement-level expressions and assignments. */
    private fun parseExpressionStatement(line: Int): Stmt {
        val expr = parseExpression()
        if (peek().kind == TokKind.OP && peek().text in ASSIGN_OPS) {
            val op = advance().text
            val value = parseExpression()
            val assigned = if (op == "=") value else Expr.Binary(op.dropLast(1), expr, value, line)
            return Stmt.Expression(Expr.Assign(expr, assigned, expr.line), expr.line)
        }
        return Stmt.Expression(expr, line)
    }

    private fun parseVarDecl(exported: Boolean, line: Int): Stmt {
        val isConst = peek().text == "const"
        advance()
        val name = expectIdent().text
        var initializer: Expr? = null
        if (matchOp("=")) initializer = parseExpression()
        if (exported) exports.add(name)
        return Stmt.VarDecl(name, initializer, exported, isConst, line)
    }

    private fun parseFunctionDecl(exported: Boolean, line: Int): Stmt {
        advance() // func
        val name = expectIdent().text
        expectOp("(")
        val params = ArrayList<String>()
        if (!check(TokKind.OP, ")")) {
            do {
                params.add(expectIdent().text)
            } while (matchOp(","))
        }
        expectOp(")")
        if (exported) exportedFunctions.add(name)
        return Stmt.FunctionDecl(name, params, parseBlock(), exported, line)
    }

    private fun parseIf(line: Int): Stmt {
        val branches = ArrayList<Pair<Expr, List<Stmt>>>()
        val condition = parseExpression()
        branches.add(condition to parseBlock())
        while (matchKeyword("elif")) branches.add(parseExpression() to parseBlock())
        val elseBody = if (matchKeyword("else")) parseBlock() else null
        return Stmt.If(branches, elseBody, line)
    }

    private fun parseBlock(): List<Stmt> {
        expectOp("{")
        val statements = ArrayList<Stmt>()
        while (!check(TokKind.OP, "}")) {
            if (check(TokKind.EOF)) throw ScriptError("unexpected end of file inside a block", peek().line, path)
            statements.add(parseStatement())
        }
        expectOp("}")
        return statements
    }

    // --------------------------------------------------------------- expressions

    fun parseExpression(): Expr = parseOr()

    private fun parseOr(): Expr {
        var left = parseAnd()
        while (peek().kind == TokKind.KEYWORD && peek().text == "or" || peek().isOp("||")) {
            val line = advance().line
            left = Expr.Logical("or", left, parseAnd(), line)
        }
        return left
    }

    private fun parseAnd(): Expr {
        var left = parseEquality()
        while (peek().kind == TokKind.KEYWORD && peek().text == "and" || peek().isOp("&&")) {
            val line = advance().line
            left = Expr.Logical("and", left, parseEquality(), line)
        }
        return left
    }

    private fun parseEquality(): Expr {
        var left = parseComparison()
        while (check(TokKind.OP, "==") || check(TokKind.OP, "!=")) {
            val op = advance()
            left = Expr.Binary(op.text, left, parseComparison(), op.line)
        }
        return left
    }

    private fun parseComparison(): Expr {
        var left = parseAdditive()
        while (check(TokKind.OP, "<") || check(TokKind.OP, "<=") || check(TokKind.OP, ">") || check(TokKind.OP, ">=")) {
            val op = advance()
            left = Expr.Binary(op.text, left, parseAdditive(), op.line)
        }
        return left
    }

    private fun parseAdditive(): Expr {
        var left = parseMultiplicative()
        while (check(TokKind.OP, "+") || check(TokKind.OP, "-")) {
            val op = advance()
            left = Expr.Binary(op.text, left, parseMultiplicative(), op.line)
        }
        return left
    }

    private fun parseMultiplicative(): Expr {
        var left = parseUnary()
        while (check(TokKind.OP, "*") || check(TokKind.OP, "/") || check(TokKind.OP, "%")) {
            val op = advance()
            left = Expr.Binary(op.text, left, parseUnary(), op.line)
        }
        return left
    }

    private fun parseUnary(): Expr {
        val token = peek()
        if (token.isOp("-")) { advance(); return Expr.Unary("-", parseUnary(), token.line) }
        if (token.isOp("!")) { advance(); return Expr.Unary("not", parseUnary(), token.line) }
        if (token.kind == TokKind.KEYWORD && token.text == "not") { advance(); return Expr.Unary("not", parseUnary(), token.line) }
        return parsePostfix()
    }

    private fun parsePostfix(): Expr {
        var expr = parsePrimary()
        while (true) {
            when {
                matchOp(".") -> {
                    val name = expectIdent().text
                    expr = Expr.Get(expr, name, peek().line)
                }
                matchOp("[") -> {
                    val index = parseExpression()
                    expectOp("]")
                    expr = Expr.Index(expr, index, peek().line)
                }
                matchOp("(") -> {
                    val args = ArrayList<Expr>()
                    if (!check(TokKind.OP, ")")) {
                        do { args.add(parseExpression()) } while (matchOp(","))
                    }
                    expectOp(")")
                    expr = Expr.Call(expr, args, peek().line)
                }
                else -> return expr
            }
        }
    }

    private fun parsePrimary(): Expr {
        val token = peek()
        when {
            token.kind == TokKind.NUMBER -> { advance(); return Expr.Literal(token.number, token.line) }
            token.kind == TokKind.STRING -> { advance(); return Expr.Literal(token.text, token.line) }
            token.kind == TokKind.KEYWORD && token.text == "true" -> { advance(); return Expr.Literal(true, token.line) }
            token.kind == TokKind.KEYWORD && token.text == "false" -> { advance(); return Expr.Literal(false, token.line) }
            token.kind == TokKind.KEYWORD && token.text == "null" -> { advance(); return Expr.Literal(null, token.line) }
            token.kind == TokKind.KEYWORD && token.text == "self" -> { advance(); return Expr.SelfExpr(token.line) }
            token.kind == TokKind.KEYWORD && token.text == "func" -> {
                advance()
                expectOp("(")
                val params = ArrayList<String>()
                if (!check(TokKind.OP, ")")) {
                    do { params.add(expectIdent().text) } while (matchOp(","))
                }
                expectOp(")")
                return Expr.FunctionExpr(params, parseBlock(), token.line)
            }
            token.isOp("(") -> {
                advance()
                val inner = parseExpression()
                expectOp(")")
                return inner
            }
            token.isOp("[") -> {
                advance()
                val items = ArrayList<Expr>()
                if (!check(TokKind.OP, "]")) {
                    do { items.add(parseExpression()) } while (matchOp(","))
                }
                expectOp("]")
                return Expr.ListLiteral(items, token.line)
            }
            token.isOp("{") -> {
                advance()
                val entries = ArrayList<Pair<Expr, Expr>>()
                if (!check(TokKind.OP, "}")) {
                    do {
                        val key = if (check(TokKind.STRING)) Expr.Literal(advance().text, token.line) else parseExpression()
                        expectOp(":")
                        entries.add(key to parseExpression())
                    } while (matchOp(","))
                }
                expectOp("}")
                return Expr.DictLiteral(entries, token.line)
            }
            token.kind == TokKind.IDENT -> { advance(); return Expr.Variable(token.text, token.line) }
        }
        throw ScriptError("unexpected token '${token.text}' in an expression", token.line, path)
    }
}

// ------------------------------------------------------------------------------ values

/** A script-defined function with its captured environment. */
class ScriptFunction internal constructor(
    val name: String,
    internal val params: List<String>,
    internal val body: List<Stmt>,
    internal val closure: Env,
    /** True for functions declared with `export func` (visible to other scripts). */
    val exported: Boolean = false,
) {
    override fun toString() = "<func $name/${params.size}>"
}

/** A callable implemented in Kotlin (builtins, node methods, engine callbacks). */
class NativeFunction(val name: String, val arity: Int = -1, val body: (List<Any?>) -> Any?) {
    override fun toString() = "<native $name>"
}

/**
 * Value wrapper around an engine object (a node, the input state, an audio player, ...).
 * The adapter decides which properties and methods the script can see.
 */
interface ScriptForeign {
    val scriptTypeName: String
    fun scriptGet(name: String): Any?
    fun scriptSet(name: String, value: Any?): Boolean
    fun scriptCall(name: String, args: List<Any?>): Any?
    /** True when [name] is readable (used to give good error messages). */
    fun scriptHas(name: String): Boolean = true
}

/**
 * Vector/colour/rect value used inside scripts. Structs are mutable so
 * `node.position.x = 5` works; [toEngineValue] converts back at the FFI boundary.
 */
class ScriptStruct(val typeName: String, val fields: MutableMap<String, Any?> = LinkedHashMap()) {
    operator fun get(name: String): Any? = fields[name]
    operator fun set(name: String, value: Any?) { fields[name] = value }
    override fun toString(): String = when (typeName) {
        "vec2" -> "vec2(${num(fields["x"])}, ${num(fields["y"])})"
        "rect" -> "rect(${num(fields["x"])}, ${num(fields["y"])}, ${num(fields["w"])}, ${num(fields["h"])})"
        "color" -> "color(${num(fields["r"])}, ${num(fields["g"])}, ${num(fields["b"])}, ${num(fields["a"])})"
        else -> "$typeName$fields"
    }

    fun copy(): ScriptStruct = ScriptStruct(typeName, LinkedHashMap(fields))

    companion object {
        private fun num(v: Any?): String = (v as? Double)?.let { if (it == floor(it) && abs(it) < 1e9) it.toLong().toString() else it.toString() } ?: "null"
        fun vec2(x: Double, y: Double) = ScriptStruct("vec2", linkedMapOf("x" to x, "y" to y))
        fun rect(x: Double, y: Double, w: Double, h: Double) = ScriptStruct("rect", linkedMapOf("x" to x, "y" to y, "w" to w, "h" to h))
        fun color(r: Double, g: Double, b: Double, a: Double = 1.0) = ScriptStruct("color", linkedMapOf("r" to r, "g" to g, "b" to b, "a" to a))
    }
}

/** Converts an engine value into the scripting value model. */
fun toScriptValue(value: Any?, runtime: ScriptRuntimeContext? = null): Any? = when (value) {
    null, is Double, is Boolean, is String -> value
    is Int -> value.toDouble()
    is Long -> value.toDouble()
    is Float -> value.toDouble()
    is Short -> value.toDouble()
    is Byte -> value.toDouble()
    is Enum<*> -> value.name
    is Vec2 -> ScriptStruct.vec2(value.x.toDouble(), value.y.toDouble())
    is Color -> ScriptStruct.color(value.r.toDouble(), value.g.toDouble(), value.b.toDouble(), value.a.toDouble())
    is Rect -> ScriptStruct.rect(value.x.toDouble(), value.y.toDouble(), value.w.toDouble(), value.h.toDouble())
    is Map<*, *> -> value.entries.associate { it.key.toString() to toScriptValue(it.value, runtime) }
    is List<*> -> value.map { toScriptValue(it, runtime) }
    is ScriptForeign, is ScriptFunction, is NativeFunction, is ScriptStruct -> value
    else -> runtime?.foreignFor(value) ?: value
}

/** Converts a scripting value back into something engine APIs accept. */
fun fromScriptValue(value: Any?): Any? = when (value) {
    is ScriptStruct -> when (value.typeName) {
        "vec2" -> Vec2(fnum(value["x"]), fnum(value["y"]))
        "rect" -> Rect(fnum(value["x"]), fnum(value["y"]), fnum(value["w"]), fnum(value["h"]))
        "color" -> Color(fnum(value["r"]), fnum(value["g"]), fnum(value["b"]), fnum(value["a"]))
        else -> value
    }
    is Double -> if (value == floor(value) && abs(value) < 2.0e9) value.toInt() else value.toFloat()
    else -> value
}

private fun fnum(v: Any?): Float = (v as? Double)?.toFloat() ?: 0f

/** Hook the interpreter uses to wrap engine objects it has never seen before. */
interface ScriptRuntimeContext {
    fun foreignFor(value: Any): Any?
    /** Called by `print`; the editor routes it to the output panel. */
    fun log(message: String) {}
}

// ------------------------------------------------------------------------------ environment

/** Lexical scope: a map plus a parent link. */
internal class Env(val parent: Env? = null) {
    private val values = HashMap<String, Any?>(16)
    private val constants = HashSet<String>(2)

    fun declare(name: String, value: Any?, isConst: Boolean = false) {
        values[name] = value
        if (isConst) constants.add(name)
    }

    fun get(name: String): Any? {
        var env: Env? = this
        while (env != null) {
            val v = env.values
            if (v.containsKey(name)) return v[name]
            env = env.parent
        }
        return UNDEFINED
    }

    fun has(name: String): Boolean {
        var env: Env? = this
        while (env != null) {
            if (env.values.containsKey(name)) return true
            env = env.parent
        }
        return false
    }

    fun assign(name: String, value: Any?): Boolean {
        var env: Env? = this
        while (env != null) {
            if (env.values.containsKey(name)) {
                if (name in env.constants) throw ScriptError("cannot assign to constant '$name'")
                env.values[name] = value
                return true
            }
            env = env.parent
        }
        return false
    }

    fun snapshot(): Map<String, Any?> = LinkedHashMap(values)

    companion object {
        val UNDEFINED = Any()
    }
}

// ------------------------------------------------------------------------------ interpreter

/**
 * Tree-walking evaluator. One [Interpreter] is shared by every instance of a script; the
 * per-instance state lives in the [Env] created by [createInstance].
 */
class Interpreter(private val context: ScriptRuntimeContext? = null) {

    /** Executes top-level statements and returns the instance environment. */
    internal fun createInstance(program: ScriptProgram, self: Any?, builtins: Env): Env {
        val env = Env(builtins)
        env.declare("self", self, isConst = true)
        for (statement in program.statements) {
            if (statement is Stmt.FunctionDecl) {
                env.declare(statement.name, ScriptFunction(statement.name, statement.params, statement.body, env, statement.exported), isConst = true)
            }
        }
        val exports = LinkedHashMap<String, Any?>()
        for (statement in program.statements) {
            try {
                execute(statement, env, exports)
            } catch (t: ScriptError) {
                throw ScriptError(t.message ?: "script error", t.line, program.path)
            }
        }
        return env
    }

    /** Evaluates the program's top level, collecting `export var` values into [exports]. */
    private fun execute(statement: Stmt, env: Env, exports: MutableMap<String, Any?>) {
        when (statement) {
            is Stmt.VarDecl -> {
                val value = statement.initializer?.let { evalExpr(it, env) }
                env.declare(statement.name, value, statement.isConst)
                if (statement.exported) exports[statement.name] = value
            }
            is Stmt.FunctionDecl -> Unit // hoisted in createInstance
            else -> executeInner(statement, env)
        }
    }

    private fun executeInner(statement: Stmt, env: Env) {
        when (statement) {
            is Stmt.VarDecl -> env.declare(statement.name, statement.initializer?.let { evalExpr(it, env) }, statement.isConst)
            is Stmt.FunctionDecl -> env.declare(statement.name, ScriptFunction(statement.name, statement.params, statement.body, env, statement.exported), isConst = true)
            is Stmt.Expression -> evalExpr(statement.expr, env)
            is Stmt.If -> {
                for ((condition, body) in statement.branches) {
                    if (truthy(evalExpr(condition, env))) {
                        executeBlock(body, env)
                        return
                    }
                }
                statement.elseBody?.let { executeBlock(it, env) }
            }
            is Stmt.While -> {
                var guard = 0
                while (truthy(evalExpr(statement.condition, env))) {
                    try {
                        executeBlock(statement.body, env)
                    } catch (b: BreakSignal) { break } catch (c: ContinueSignal) { /* next iteration */ }
                    if (++guard > MAX_LOOP_ITERATIONS) throw ScriptError("loop ran for more than $MAX_LOOP_ITERATIONS iterations", statement.line)
                }
            }
            is Stmt.For -> {
                val iterable = evalExpr(statement.iterable, env)
                val items = iterableItems(iterable, statement.line)
                for (item in items) {
                    val scope = Env(env)
                    scope.declare(statement.variable, item)
                    try {
                        executeBlock(statement.body, scope)
                    } catch (b: BreakSignal) { break } catch (c: ContinueSignal) { /* next */ }
                }
            }
            is Stmt.Return -> throw ReturnSignal(statement.value?.let { evalExpr(it, env) })
            is Stmt.Break -> throw BreakSignal()
            is Stmt.Continue -> throw ContinueSignal()
        }
    }

    private fun executeBlock(body: List<Stmt>, env: Env) {
        val scope = Env(env)
        for (statement in body) executeInner(statement, scope)
    }

    // --------------------------------------------------------------- expressions

    internal fun evalExpr(expr: Expr, env: Env): Any? = when (expr) {
        is Expr.Literal -> expr.value
        is Expr.SelfExpr -> env.get("self")
        is Expr.Variable -> {
            val value = env.get(expr.name)
            if (value !== Env.UNDEFINED) return value
            // Bare engine properties (`position`, `velocity`, `visible`, ...) resolve on `self`.
            val self = env.get("self")
            if (self is ScriptForeign && self.scriptHas(expr.name)) return self.scriptGet(expr.name)
            throw ScriptError("unknown name '${expr.name}'", expr.line)
        }
        is Expr.ListLiteral -> expr.items.map { evalExpr(it, env) }.toMutableList()
        is Expr.DictLiteral -> {
            val map = LinkedHashMap<String, Any?>()
            for ((key, value) in expr.entries) map[evalExpr(key, env).toString()] = evalExpr(value, env)
            map
        }
        is Expr.Unary -> {
            val v = evalExpr(expr.operand, env)
            when (expr.op) {
                "-" -> -number(v, expr.line)
                "not" -> !truthy(v)
                else -> throw ScriptError("unknown unary operator '${expr.op}'", expr.line)
            }
        }
        is Expr.Logical -> {
            val left = evalExpr(expr.left, env)
            if (expr.op == "and") { if (!truthy(left)) left else evalExpr(expr.right, env) }
            else { if (truthy(left)) left else evalExpr(expr.right, env) }
        }
        is Expr.Binary -> binary(expr, env)
        is Expr.Get -> getMember(evalExpr(expr.target, env), expr.name, expr.line)
        is Expr.Index -> index(evalExpr(expr.target, env), evalExpr(expr.index, env), expr.line)
        is Expr.Assign -> {
            val value = evalExpr(expr.value, env)
            assign(expr.target, value, env)
            value
        }
        is Expr.Call -> call(expr, env)
        is Expr.FunctionExpr -> ScriptFunction("<lambda>", expr.params, expr.body, env)
    }

    private fun binary(expr: Expr.Binary, env: Env): Any? {
        val op = expr.op
        // Short-circuit-free operators: evaluate both sides.
        val left = evalExpr(expr.left, env)
        val right = evalExpr(expr.right, env)
        when (op) {
            "==" -> return equalsValue(left, right)
            "!=" -> return !equalsValue(left, right)
        }
        return when (op) {
            "+" -> {
                if (left is String || right is String) return stringify(left) + stringify(right)
                if (left is List<*> && right is List<*>) return (left + right).toMutableList()
                if (left is ScriptStruct || right is ScriptStruct) return structArithmetic(left, right, op, expr.line)
                if (left is Map<*, *> && right is Map<*, *>) {
                    val merged = LinkedHashMap<String, Any?>()
                    left.forEach { (k, v) -> merged[k.toString()] = v }
                    right.forEach { (k, v) -> merged[k.toString()] = v }
                    return merged
                }
                return number(left, expr.line) + number(right, expr.line)
            }
            "-", "*", "/", "%" -> {
                if (left is ScriptStruct || right is ScriptStruct) return structArithmetic(left, right, op, expr.line)
                if (op == "*" && left is String && right is Double) return left.repeat(max(0, right.toInt()))
                val a = number(left, expr.line); val b = number(right, expr.line)
                if (op == "/" && b == 0.0) throw ScriptError("division by zero", expr.line)
                if (op == "%" && b == 0.0) throw ScriptError("modulo by zero", expr.line)
                when (op) {
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> a / b
                    else -> a % b
                }
            }
            "<" -> compare(left, right) < 0
            "<=" -> compare(left, right) <= 0
            ">" -> compare(left, right) > 0
            ">=" -> compare(left, right) >= 0
            else -> throw ScriptError("unknown operator '$op'", expr.line)
        }
    }

    private fun structArithmetic(left: Any?, right: Any?, op: String, line: Int): Any? {
        if (left !is ScriptStruct) throw ScriptError("cannot apply '$op' to ${typeName(left)}", line)
        val result = left.copy()
        val outcome: Any? = when (left.typeName) {
            "vec2" -> {
                val x = number(left["x"], line); val y = number(left["y"], line)
                when (op) {
                    "*" -> if (right is Double) {
                        result["x"] = x * right; result["y"] = y * right; result
                    } else vectorPair(left, right, line) { a, b -> a * b }
                    "+" -> {
                        result["x"] = x + number(vecField(right, "x", line), line)
                        result["y"] = y + number(vecField(right, "y", line), line)
                        result
                    }
                    "-" -> {
                        result["x"] = x - number(vecField(right, "x", line), line)
                        result["y"] = y - number(vecField(right, "y", line), line)
                        result
                    }
                    "/" -> if (right is Double) {
                        result["x"] = x / right; result["y"] = y / right; result
                    } else throw ScriptError("cannot divide a vec2 by ${typeName(right)}", line)
                    else -> throw ScriptError("unsupported vec2 operator '$op'", line)
                }
            }
            "color" -> {
                if (right is Double) {
                    for (key in listOf("r", "g", "b", "a")) {
                        val v = number(left[key], line)
                        result[key] = when (op) {
                            "*" -> v * right
                            "+" -> v + right
                            "-" -> v - right
                            "/" -> v / right
                            else -> v
                        }
                    }
                    result
                } else throw ScriptError("colour ${op} requires a number", line)
            }
            else -> throw ScriptError("operator '$op' is not defined for ${left.typeName}", line)
        }
        return outcome
    }

    private inline fun vectorPair(a: ScriptStruct, b: Any?, line: Int, f: (Double, Double) -> Double): ScriptStruct {
        val other = b as? ScriptStruct ?: throw ScriptError("expected a vec2", line)
        val out = a.copy()
        out["x"] = f(number(a["x"], line), number(other["x"], line))
        out["y"] = f(number(a["y"], line), number(other["y"], line))
        return out
    }

    private fun vecField(value: Any?, field: String, line: Int): Any? {
        if (value is ScriptStruct) return value[field]
        val n = value as? Double ?: throw ScriptError("expected a vec2 or a number", line)
        return n
    }

    internal fun getMember(target: Any?, name: String, line: Int): Any? = when (target) {
        null -> throw ScriptError("cannot read '$name' of null", line)
        is ScriptStruct -> target[name] ?: throw ScriptError("${target.typeName} has no field '$name'", line)
        is Map<*, *> -> {
            if (target.containsKey(name)) target[name]
            else throw ScriptError("dictionary has no key '$name'", line)
        }
        is List<*> -> when (name) {
            "size", "length", "count" -> target.size.toDouble()
            "empty", "is_empty" -> target.isEmpty()
            "first" -> target.firstOrNull()
            "last" -> target.lastOrNull()
            else -> throw ScriptError("lists have no property '$name'", line)
        }
        is String -> when (name) {
            "size", "length" -> target.length.toDouble()
            "empty", "is_empty" -> target.isEmpty()
            "upper", "to_upper" -> target.uppercase()
            "lower", "to_lower" -> target.lowercase()
            else -> throw ScriptError("strings have no property '$name'", line)
        }
        is ScriptForeign -> target.scriptGet(name)
        is ScriptFunction -> when (name) {
            "name" -> target.name
            else -> throw ScriptError("functions have no property '$name'", line)
        }
        else -> throw ScriptError("${typeName(target)} has no property '$name'", line)
    }

    private fun index(target: Any?, key: Any?, line: Int): Any? = when (target) {
        is List<*> -> {
            val i = number(key, line).toInt()
            if (i < 0 || i >= target.size) throw ScriptError("list index $i out of range (size ${target.size})", line)
            target[i]
        }
        is Map<*, *> -> target[key?.toString()]
        is String -> {
            val i = number(key, line).toInt()
            if (i < 0 || i >= target.length) throw ScriptError("string index $i out of range", line)
            target[i].toString()
        }
        is ScriptStruct -> target[key?.toString() ?: ""]
        else -> throw ScriptError("${typeName(target)} is not indexable", line)
    }

    private fun call(expr: Expr.Call, env: Env): Any? {
        val args = expr.args.map { evalExpr(it, env) }
        // Member calls need the receiver, not the resolved property value.
        val callee = expr.callee
        if (callee is Expr.Get) {
            val receiver = evalExpr(callee.target, env)
            return callMember(receiver, callee.name, args, expr.line)
        }
        val target = evalExpr(callee, env)
        return invoke(target, args, expr.line)
    }

    internal fun invoke(target: Any?, args: List<Any?>, line: Int): Any? = when (target) {
        is ScriptFunction -> callFunction(target, args, line)
        is NativeFunction -> {
            if (target.arity >= 0 && args.size != target.arity) {
                throw ScriptError("${target.name}() expects ${target.arity} argument(s) but got ${args.size}", line)
            }
            try {
                target.body(args)
            } catch (t: ScriptError) {
                throw t
            } catch (t: Throwable) {
                throw ScriptError("${target.name}() failed: ${t.message}", line)
            }
        }
        else -> throw ScriptError("${typeName(target)} is not callable", line)
    }

    internal fun callMember(receiver: Any?, name: String, args: List<Any?>, line: Int): Any? {
        when (receiver) {
            is ScriptForeign -> return receiver.scriptCall(name, args)
            is List<*> -> return listMethod(receiver, name, args, line)
            is String -> return stringMethod(receiver, name, args, line)
            is Map<*, *> -> return mapMethod(receiver, name, args, line)
            is ScriptStruct -> return structMethod(receiver, name, args, line)
            null -> throw ScriptError("cannot call '$name' on null", line)
        }
        // Node methods reached through a member chain resolve on the foreign wrapper only; a
        // plain Kotlin object here means the runtime forgot to wrap it.
        throw ScriptError("${typeName(receiver)} has no method '$name'", line)
    }

    /** Vector/colour convenience methods: `v.normalized()`, `v.length()`, `c.lerp(other, t)`. */
    private fun structMethod(struct: ScriptStruct, name: String, args: List<Any?>, line: Int): Any? {
        val x = number(struct["x"], line); val y = number(struct["y"], line)
        return when (struct.typeName) {
            "vec2" -> when (name) {
                "normalized", "normalize" -> {
                    val length = sqrt(x * x + y * y)
                    if (length < 1e-9) ScriptStruct.vec2(0.0, 0.0) else ScriptStruct.vec2(x / length, y / length)
                }
                "length", "magnitude" -> sqrt(x * x + y * y)
                "length_squared" -> x * x + y * y
                "angle" -> atan2(y, x)
                "distance_to" -> {
                    val other = args.getOrNull(0) as? ScriptStruct ?: return 0.0
                    val dx = x - number(other["x"], line); val dy = y - number(other["y"], line)
                    sqrt(dx * dx + dy * dy)
                }
                "dot" -> {
                    val other = args.getOrNull(0) as? ScriptStruct ?: return 0.0
                    x * number(other["x"], line) + y * number(other["y"], line)
                }
                "rotated" -> {
                    val angle = number(args.getOrNull(0), line)
                    ScriptStruct.vec2(x * cos(angle) - y * sin(angle), x * sin(angle) + y * cos(angle))
                }
                "lerp" -> {
                    val other = args.getOrNull(0) as? ScriptStruct ?: return struct
                    val t = number(args.getOrNull(1), line)
                    ScriptStruct.vec2(x + (number(other["x"], line) - x) * t, y + (number(other["y"], line) - y) * t)
                }
                "clamp_length" -> {
                    val maxLength = number(args.getOrNull(0), line)
                    val length = sqrt(x * x + y * y)
                    if (length <= maxLength || length < 1e-9) struct
                    else ScriptStruct.vec2(x / length * maxLength, y / length * maxLength)
                }
                else -> throw ScriptError("vec2 has no method '$name'", line)
            }
            "color" -> when (name) {
                "lerp" -> {
                    val other = args.getOrNull(0) as? ScriptStruct ?: return struct
                    val t = number(args.getOrNull(1), line)
                    val out = struct.copy()
                    for (key in listOf("r", "g", "b", "a")) {
                        val a = number(struct[key], line); val b = number(other[key], line)
                        out[key] = a + (b - a) * t
                    }
                    out
                }
                "darkened" -> {
                    val factor = number(args.getOrNull(0), line)
                    val out = struct.copy()
                    for (key in listOf("r", "g", "b")) out[key] = number(struct[key], line) * factor
                    out
                }
                "lightened" -> {
                    val amount = number(args.getOrNull(0), line)
                    val out = struct.copy()
                    for (key in listOf("r", "g", "b")) out[key] = min(1.0, number(struct[key], line) + amount)
                    out
                }
                else -> throw ScriptError("color has no method '$name'", line)
            }
            else -> throw ScriptError("${struct.typeName} has no method '$name'", line)
        }
    }

    private fun callFunction(function: ScriptFunction, args: List<Any?>, line: Int): Any? {
        if (args.size != function.params.size) {
            throw ScriptError(
                "${function.name}() expects ${function.params.size} argument(s) but got ${args.size}", line,
            )
        }
        val scope = Env(function.closure)
        function.params.forEachIndexed { i, name -> scope.declare(name, args[i]) }
        return try {
            for (statement in function.body) executeInner(statement, scope)
            null
        } catch (r: ReturnSignal) {
            r.value
        } catch (b: BreakSignal) {
            throw ScriptError("'break' used outside a loop in ${function.name}()", line)
        } catch (c: ContinueSignal) {
            throw ScriptError("'continue' used outside a loop in ${function.name}()", line)
        }
    }

    private fun listMethod(list: List<*>, name: String, args: List<Any?>, line: Int): Any? {
        val mutable = list as? MutableList<Any?>
        return when (name) {
            "size", "length", "count" -> list.size.toDouble()
            "empty", "is_empty" -> list.isEmpty()
            "push", "append", "add" -> { mutable?.add(args.getOrNull(0)); list.size.toDouble() }
            "pop" -> mutable?.removeLastOrNull()
            "clear" -> { mutable?.clear(); null }
            "has", "contains" -> list.any { equalsValue(it, args.getOrNull(0)) }
            "find", "index_of" -> list.indexOfFirst { equalsValue(it, args.getOrNull(0)) }.toDouble()
            "join" -> list.joinToString(args.getOrNull(0)?.toString() ?: ", ") { stringify(it) }
            "slice" -> {
                val from = (args.getOrNull(0) as? Double)?.toInt() ?: 0
                val to = (args.getOrNull(1) as? Double)?.toInt() ?: list.size
                list.subList(from.coerceIn(0, list.size), to.coerceIn(0, list.size)).toMutableList()
            }
            "sorted", "sort" -> {
                val sorted = list.map { it }.sortedBy { number(it, line) }
                if (name == "sort") { mutable?.clear(); mutable?.addAll(sorted) }
                sorted.toMutableList()
            }
            "shuffled" -> list.shuffled().toMutableList()
            "duplicate", "duplicate_deep" -> list.toMutableList()
            else -> throw ScriptError("lists have no method '$name'", line)
        }
    }

    private fun stringMethod(text: String, name: String, args: List<Any?>, line: Int): Any? = when (name) {
        "split" -> text.split(args.getOrNull(0)?.toString() ?: " ").toMutableList()
        "strip", "strip_edges", "trim" -> text.trim()
        "replace" -> text.replace(args.getOrNull(0)?.toString() ?: "", args.getOrNull(1)?.toString() ?: "")
        "to_int" -> text.toDoubleOrNull() ?: 0.0
        "to_float" -> text.toDoubleOrNull() ?: 0.0
        "begins_with", "starts_with" -> text.startsWith(args.getOrNull(0)?.toString() ?: "")
        "ends_with" -> text.endsWith(args.getOrNull(0)?.toString() ?: "")
        "contains", "find" -> text.contains(args.getOrNull(0)?.toString() ?: "")
        "upper", "to_upper" -> text.uppercase()
        "lower", "to_lower" -> text.lowercase()
        "substr" -> {
            val from = (args.getOrNull(0) as? Double)?.toInt() ?: 0
            val length = (args.getOrNull(1) as? Double)?.toInt() ?: (text.length - from)
            text.substring(from.coerceIn(0, text.length), (from + length).coerceIn(0, text.length))
        }
        "size", "length" -> text.length.toDouble()
        else -> throw ScriptError("strings have no method '$name'", line)
    }

    private fun mapMethod(map: Map<*, *>, name: String, args: List<Any?>, line: Int): Any? = when (name) {
        "has", "contains" -> map.containsKey(args.getOrNull(0)?.toString())
        "get" -> map[args.getOrNull(0)?.toString()] ?: args.getOrNull(1)
        "keys" -> map.keys.map { it.toString() }.toMutableList()
        "values" -> map.values.toMutableList()
        "size", "count" -> map.size.toDouble()
        "empty", "is_empty" -> map.isEmpty()
        "erase", "remove" -> { (map as? MutableMap<Any?, Any?>)?.remove(args.getOrNull(0)?.toString()); null }
        else -> throw ScriptError("dictionaries have no method '$name'", line)
    }

    /** Called by the runtime to run a script function from Kotlin (signals, timers, tween callbacks). */
    fun callFunctionValue(value: Any?, args: List<Any?>, line: Int = 0): Any? = invoke(value, args, line)

    // --------------------------------------------------------------- assignment

    /**
     * Assigns to a variable, member or index target. Member chains write back through their base
     * object so `node.position.x = 5` works even though engine vectors are immutable.
     */
    internal fun assign(target: Expr, value: Any?, env: Env) {
        when (target) {
            is Expr.Variable -> {
                if (!env.assign(target.name, value)) {
                    // Assigning a bare engine property writes through `self`.
                    val self = env.get("self")
                    if (self is ScriptForeign && self.scriptSet(target.name, fromScriptValue(value))) return
                    throw ScriptError("unknown name '${target.name}'", target.line)
                }
            }
            is Expr.Index -> {
                val collection = evalExpr(target.target, env)
                val key = evalExpr(target.index, env)
                @Suppress("UNCHECKED_CAST")
                when (collection) {
                    is MutableList<*> -> {
                        val list = collection as MutableList<Any?>
                        val i = number(key, target.line).toInt()
                        if (i < 0 || i >= list.size) throw ScriptError("list index $i out of range", target.line)
                        list[i] = value
                    }
                    is MutableMap<*, *> -> (collection as MutableMap<Any?, Any?>)[key?.toString()] = value
                    else -> throw ScriptError("cannot assign into ${typeName(collection)}", target.line)
                }
            }
            is Expr.Get -> {
                val receiver = evalExpr(target.target, env)
                val converted = fromScriptValue(value)
                when (receiver) {
                    is ScriptStruct -> receiver[target.name] = value
                    is MutableMap<*, *> -> (receiver as MutableMap<Any?, Any?>)[target.name] = value
                    is ScriptForeign -> {
                        if (!receiver.scriptSet(target.name, converted)) {
                            throw ScriptError("${receiver.scriptTypeName} has no settable property '${target.name}'", target.line)
                        }
                    }
                    is ScriptFunction -> throw ScriptError("cannot assign to a function", target.line)
                    else -> throw ScriptError("cannot assign '${pathOf(target)}' on ${typeName(receiver)}", target.line)
                }
                // Write the possibly-mutated struct back through the chain (node.position.x = 5).
                if (receiver is ScriptStruct) writeBack(target.target, receiver, env)
            }
            else -> throw ScriptError("invalid assignment target", target.line)
        }
    }

    /**
     * Stores [value] back into the thing [accessor] points at. Member chains keep working because
     * every level writes into its parent until an engine object (`self.position.x = 5`) is hit.
     */
    private fun writeBack(accessor: Expr, value: Any?, env: Env) {
        when (accessor) {
            is Expr.Get -> {
                val holder = evalExpr(accessor.target, env)
                when (holder) {
                    is ScriptForeign -> holder.scriptSet(accessor.name, fromScriptValue(value))
                    is ScriptStruct -> {
                        holder[accessor.name] = value
                        writeBack(accessor.target, holder, env)
                    }
                    is MutableMap<*, *> -> (holder as MutableMap<Any?, Any?>)[accessor.name] = value
                }
            }
            is Expr.Variable -> {
                if (!env.assign(accessor.name, value)) {
                    val self = env.get("self")
                    if (self is ScriptForeign) self.scriptSet(accessor.name, fromScriptValue(value))
                }
            }
            else -> Unit
        }
    }

    private fun pathOf(expr: Expr): String = when (expr) {
        is Expr.Variable -> expr.name
        is Expr.Get -> "${pathOf(expr.target)}.${expr.name}"
        else -> "<expression>"
    }

    // --------------------------------------------------------------- helpers

    fun isTruthy(value: Any?): Boolean = truthy(value)

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Double -> value != 0.0
        is String -> value.isNotEmpty()
        is List<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    private fun number(value: Any?, line: Int): Double = when (value) {
        is Double -> value
        is Boolean -> if (value) 1.0 else 0.0
        null -> 0.0
        else -> throw ScriptError("expected a number but got ${typeName(value)}", line)
    }

    private fun compare(left: Any?, right: Any?): Int = when {
        left is String && right is String -> left.compareTo(right)
        left is Double || right is Double || left is Boolean || right is Boolean ->
            number(left, 0).compareTo(number(right, 0))
        left == null && right == null -> 0
        left == null -> -1
        right == null -> 1
        else -> stringify(left).compareTo(stringify(right))
    }

    internal fun equalsValue(left: Any?, right: Any?): Boolean {
        if (left is Double && right is Double) return abs(left - right) < 1e-12
        if (left is ScriptStruct && right is ScriptStruct) return left.typeName == right.typeName && left.fields == right.fields
        if (left is List<*> && right is List<*>) return left.size == right.size && left.indices.all { equalsValue(left[it], right[it]) }
        return left == right
    }

    private fun iterableItems(value: Any?, line: Int): List<Any?> = when (value) {
        is List<*> -> value
        is Map<*, *> -> value.keys.map { it.toString() }
        is String -> value.map { it.toString() }
        is Double -> (0 until value.toInt()).map { it.toDouble() }
        is ScriptForeign -> throw ScriptError("cannot iterate over ${value.scriptTypeName}", line)
        null -> emptyList()
        else -> throw ScriptError("cannot iterate over ${typeName(value)}", line)
    }

    fun truthyValue(value: Any?): Boolean = truthy(value)

    fun numberValue(value: Any?, line: Int = 0): Double = number(value, line)

    companion object {
        const val MAX_LOOP_ITERATIONS = 5_000_000

        fun typeName(value: Any?): String = when (value) {
            null -> "null"
            is Double -> "number"
            is Boolean -> "bool"
            is String -> "string"
            is List<*> -> "list"
            is Map<*, *> -> "dictionary"
            is ScriptFunction -> "function"
            is NativeFunction -> "native function"
            is ScriptStruct -> value.typeName
            is ScriptForeign -> value.scriptTypeName
            else -> value::class.simpleName ?: "object"
        }

        /** Formatting used by `print`, string concatenation and the console. */
        fun stringify(value: Any?): String = when (value) {
            null -> "null"
            is Double -> if (value == floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()
            is Boolean -> if (value) "true" else "false"
            is List<*> -> "[" + value.joinToString(", ") { stringify(it) } + "]"
            is Map<*, *> -> "{" + value.entries.joinToString(", ") { "${it.key}: ${stringify(it.value)}" } + "}"
            is ScriptStruct -> value.toString()
            else -> value.toString()
        }
    }
}

/**
 * A compiled script with a live environment — the public entry point for tests, the editor's
 * script console and any tool that wants to run a script without a scene node.
 *
 * ```kotlin
 * val runner = compileScript("func double(n) { return n * 2 }")
 * runner.call("double", 21.0)   // 42.0
 * ```
 */
class ScriptRunner internal constructor(
    private val interpreter: Interpreter,
    internal val env: Env,
) {
    /** Calls a top-level function; returns null when it does not exist. */
    fun call(name: String, vararg args: Any?): Any? {
        val function = env.get(name)
        if (function === Env.UNDEFINED || function == null) return null
        return interpreter.invoke(function, args.toList(), 0)
    }

    fun hasFunction(name: String): Boolean = env.get(name) is ScriptFunction

    /** Reads a script global (`var`/`export var`/`const`). */
    fun get(name: String): Any? = env.get(name).takeIf { it !== Env.UNDEFINED }

    /** Writes a script global, declaring it when it does not exist yet. */
    fun set(name: String, value: Any?) {
        if (!env.assign(name, value)) env.declare(name, value)
    }

    /** True when the value is truthy according to script rules. */
    fun truthy(value: Any?): Boolean = interpreter.isTruthy(value)
}

/**
 * Compiles [source] and creates a [ScriptRunner]. [self] becomes the script's `self` value —
 * pass a node to script against the engine, or null for pure logic.
 */
fun compileScript(
    source: String,
    path: String = "<inline>",
    self: Any? = null,
    context: ScriptRuntimeContext? = null,
): ScriptRunner {
    val program = compileSource(source, path)
    return instantiateScript(program, self, context)
}

/** Compiles without instantiating (syntax checking in the editor). */
fun compileSource(source: String, path: String = "<inline>"): ScriptProgram {
    val tokens = Lexer(source, path).tokenize()
    return Parser(tokens, path).parseProgram()
}

/** Creates a runner for an already-compiled program. */
fun instantiateScript(program: ScriptProgram, self: Any? = null, context: ScriptRuntimeContext? = null): ScriptRunner {
    val interpreter = Interpreter(context)
    val builtins = ScriptMath.buildEnvs(Env())
    val env = interpreter.createInstance(program, self, builtins)
    return ScriptRunner(interpreter, env)
}

/** Math helpers exposed to scripts — kept in one place so the docs stay accurate. */
object ScriptMath {
    internal fun buildEnvs(parent: Env): Env {
        val env = Env(parent)
        fun fn(name: String, arity: Int, body: (List<Any?>) -> Any?) { env.declare(name, NativeFunction(name, arity, body), isConst = true) }
        fun opt(name: String, arity: Int, body: (List<Any?>) -> Any?) { env.declare(name, NativeFunction(name, arity, body), isConst = true) }
        val interpreter = Interpreter()

        fn("sin", 1) { sin(num(it[0])) }
        fn("cos", 1) { cos(num(it[0])) }
        fn("tan", 1) { tan(num(it[0])) }
        fn("asin", 1) { kotlin.math.asin(num(it[0])) }
        fn("acos", 1) { kotlin.math.acos(num(it[0])) }
        fn("atan2", 2) { atan2(num(it[0]), num(it[1])) }
        fn("sqrt", 1) { sqrt(num(it[0])) }
        fn("abs", 1) { abs(num(it[0])) }
        fn("floor", 1) { floor(num(it[0])) }
        fn("ceil", 1) { ceil(num(it[0])) }
        fn("round", 1) { round(num(it[0])) }
        fn("sign", 1) { sign(num(it[0])) }
        fn("pow", 2) { num(it[0]).pow(num(it[1])) }
        fn("min", 2) { min(num(it[0]), num(it[1])) }
        fn("max", 2) { max(num(it[0]), num(it[1])) }
        fn("clamp", 3) { MathUtil.clamp(num(it[0]).toFloat(), num(it[1]).toFloat(), num(it[2]).toFloat()).toDouble() }
        fn("lerp", 3) { MathUtil.lerp(num(it[0]).toFloat(), num(it[1]).toFloat(), num(it[2]).toFloat()).toDouble() }
        fn("damp", 4) { MathUtil.damp(num(it[0]).toFloat(), num(it[1]).toFloat(), num(it[2]).toFloat(), num(it[3]).toFloat()).toDouble() }
        fn("smoothstep", 3) { MathUtil.smoothstep(num(it[0]).toFloat(), num(it[1]).toFloat(), num(it[2]).toFloat()).toDouble() }
        fn("wrap", 3) { MathUtil.wrap(num(it[0]).toFloat(), num(it[1]).toFloat(), num(it[2]).toFloat()).toDouble() }
        fn("pingpong", 2) { MathUtil.pingPong(num(it[0]).toFloat(), num(it[1]).toFloat()).toDouble() }
        fn("deg_to_rad", 1) { MathUtil.degToRad(num(it[0]).toFloat()).toDouble() }
        fn("rad_to_deg", 1) { MathUtil.radToDeg(num(it[0]).toFloat()).toDouble() }
        fn("lerp_angle", 3) { MathUtil.lerpAngle(num(it[0]).toFloat(), num(it[1]).toFloat(), num(it[2]).toFloat()).toDouble() }

        env.declare("vec2", NativeFunction("vec2", -1) { args ->
            when (args.size) {
                0 -> ScriptStruct.vec2(0.0, 0.0)
                1 -> ScriptStruct.vec2(num(args[0]), num(args[0]))
                else -> ScriptStruct.vec2(num(args[0]), num(args[1]))
            }
        }, isConst = true)
        env.declare("vec", NativeFunction("vec", -1) { args ->
            when (args.size) {
                0 -> ScriptStruct.vec2(0.0, 0.0)
                1 -> ScriptStruct.vec2(num(args[0]), num(args[0]))
                else -> ScriptStruct.vec2(num(args[0]), num(args[1]))
            }
        }, isConst = true)
        fn("rect", 4) { ScriptStruct.rect(num(it[0]), num(it[1]), num(it[2]), num(it[3])) }
        env.declare("color", NativeFunction("color", -1) { args ->
            when (args.size) {
                1 -> parseColor(args[0].toString())
                3 -> ScriptStruct.color(num(args[0]), num(args[1]), num(args[2]))
                4 -> ScriptStruct.color(num(args[0]), num(args[1]), num(args[2]), num(args[3]))
                else -> ScriptStruct.color(1.0, 1.0, 1.0, 1.0)
            }
        }, isConst = true)

        fn("len", 1) { when (val v = it[0]) { is List<*> -> v.size.toDouble(); is String -> v.length.toDouble(); is Map<*, *> -> v.size.toDouble(); else -> 0.0 } }
        fn("str", 1) { Interpreter.stringify(it[0]) }
        fn("num", 1) { num(it[0]) }
        fn("int", 1) { floor(num(it[0])) }
        fn("bool", 1) { interpreter.truthyValue(it[0]) }
        env.declare("range", NativeFunction("range", -1) { args ->
            when (args.size) {
                1 -> (0 until num(args[0]).toInt()).map { i -> i.toDouble() }
                2 -> (num(args[0]).toInt() until num(args[1]).toInt()).map { i -> i.toDouble() }
                else -> (num(args[0]).toInt() until num(args[1]).toInt() step max(1, num(args[2]).toInt())).map { i -> i.toDouble() }
            }
        }, isConst = true)
        fn("keys", 1) { (it[0] as? Map<*, *>)?.keys?.map { key -> key.toString() }?.toMutableList() ?: mutableListOf<Any?>() }
        fn("values", 1) { (it[0] as? Map<*, *>)?.values?.toMutableList() ?: mutableListOf<Any?>() }
        fn("duplicate", 1) { when (val v = it[0]) { is Map<*, *> -> LinkedHashMap(v); is List<*> -> v.toMutableList(); else -> v } }
        fn("is_instance_of", 2) { (it[0] as? ScriptForeign)?.scriptTypeName == it[1]?.toString() }
        fn("vec2_distance", 2) {
            val a = it[0] as? ScriptStruct; val b = it[1] as? ScriptStruct
            if (a == null || b == null) 0.0 else sqrt((num(a["x"]) - num(b["x"])).pow(2) + (num(a["y"]) - num(b["y"])).pow(2))
        }
        fn("vec2_normalized", 1) {
            val v = it[0] as? ScriptStruct ?: return@fn ScriptStruct.vec2(0.0, 0.0)
            val length = sqrt(num(v["x"]).pow(2) + num(v["y"]).pow(2))
            if (length < 1e-6) ScriptStruct.vec2(0.0, 0.0) else ScriptStruct.vec2(num(v["x"]) / length, num(v["y"]) / length)
        }
        fn("vec2_length", 1) {
            val v = it[0] as? ScriptStruct ?: return@fn 0.0
            sqrt(num(v["x"]).pow(2) + num(v["y"]).pow(2))
        }
        fn("vec2_dot", 2) {
            val a = it[0] as? ScriptStruct ?: return@fn 0.0
            val b = it[1] as? ScriptStruct ?: return@fn 0.0
            num(a["x"]) * num(b["x"]) + num(a["y"]) * num(b["y"])
        }
        fn("vec2_angle", 1) { val v = it[0] as? ScriptStruct ?: return@fn 0.0; atan2(num(v["y"]), num(v["x"])) }
        fn("color_lerp", 3) {
            val a = it[0] as? ScriptStruct ?: return@fn ScriptStruct.color(1.0, 1.0, 1.0)
            val b = it[1] as? ScriptStruct ?: return@fn a
            val t = num(it[2])
            ScriptStruct.color(
                MathUtil.lerp(num(a["r"]).toFloat(), num(b["r"]).toFloat(), t.toFloat()).toDouble(),
                MathUtil.lerp(num(a["g"]).toFloat(), num(b["g"]).toFloat(), t.toFloat()).toDouble(),
                MathUtil.lerp(num(a["b"]).toFloat(), num(b["b"]).toFloat(), t.toFloat()).toDouble(),
                MathUtil.lerp(num(a["a"]).toFloat(), num(b["a"]).toFloat(), t.toFloat()).toDouble(),
            )
        }
        return env
    }

    private fun num(v: Any?): Double = (v as? Double) ?: 0.0

    private fun parseColor(text: String): ScriptStruct {
        val hex = text.removePrefix("#")
        return runCatching {
            when (hex.length) {
                6 -> ScriptStruct.color(
                    (hex.substring(0, 2).toInt(16) / 255.0), (hex.substring(2, 4).toInt(16) / 255.0),
                    (hex.substring(4, 6).toInt(16) / 255.0), 1.0,
                )
                8 -> ScriptStruct.color(
                    (hex.substring(0, 2).toInt(16) / 255.0), (hex.substring(2, 4).toInt(16) / 255.0),
                    (hex.substring(4, 6).toInt(16) / 255.0), (hex.substring(6, 8).toInt(16) / 255.0),
                )
                else -> ScriptStruct.color(1.0, 1.0, 1.0, 1.0)
            }
        }.getOrElse { ScriptStruct.color(1.0, 1.0, 1.0, 1.0) }
    }
}
