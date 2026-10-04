/**
 * Lumen2D — 2D math library: vectors, rectangles, transforms, colors, easing, deterministic RNG.
 * Pure Kotlin, no platform dependencies, allocation-light where it matters.
 */
package dev.lumen2d.core.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

const val TAU = (PI * 2).toFloat()
const val DEG2RAD = (PI / 180.0).toFloat()
const val RAD2DEG = (180.0 / PI).toFloat()

object MathUtil {
    fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v
    fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
    fun clamp01(v: Float): Float = clamp(v, 0f, 1f)
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
    fun lerpUnclamped(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    fun invLerp(a: Float, b: Float, v: Float): Float = if (abs(b - a) < 1e-9f) 0f else (v - a) / (b - a)
    fun remap(v: Float, inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float =
        lerp(outMin, outMax, clamp01(invLerp(inMin, inMax, v)))
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = clamp01(invLerp(edge0, edge1, x)); return t * t * (3f - 2f * t)
    }
    fun smootherstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = clamp01(invLerp(edge0, edge1, x)); return t * t * t * (t * (t * 6f - 15f) + 10f)
    }
    /** Frame-rate independent exponential smoothing (Game Programming Gems 4). */
    fun damp(current: Float, target: Float, lambda: Float, dt: Float): Float =
        lerp(current, target, 1f - kotlin.math.exp(-lambda * dt))
    fun moveTowards(current: Float, target: Float, maxDelta: Float): Float =
        if (abs(target - current) <= maxDelta) target else current + kotlin.math.sign(target - current) * maxDelta
    fun wrap(v: Float, min: Float, max: Float): Float {
        val range = max - min
        if (range <= 0f) return min
        var r = (v - min) % range
        if (r < 0f) r += range
        return r + min
    }
    fun wrapInt(v: Int, size: Int): Int { if (size <= 0) return 0; val r = v % size; return if (r < 0) r + size else r }
    fun pingPong(t: Float, length: Float): Float {
        if (length <= 0f) return 0f
        val l = wrap(t, 0f, length * 2f); return if (l <= length) l else length * 2f - l
    }
    fun snapped(v: Float, step: Float): Float = if (step <= 0f) v else (v / step).roundToInt() * step
    fun snapTo(v: Float, grid: Float): Float = if (grid <= 0f) v else floor(v / grid + 0.5f) * grid
    fun sign(v: Float): Float = if (v > 0f) 1f else if (v < 0f) -1f else 0f
    fun approx(a: Float, b: Float, eps: Float = 1e-5f): Boolean = abs(a - b) <= eps
    fun isZero(v: Float, eps: Float = 1e-6f): Boolean = abs(v) <= eps
    fun fract(v: Float): Float = v - floor(v)
    fun degToRad(d: Float) = d * DEG2RAD
    fun radToDeg(r: Float) = r * RAD2DEG
    /** Shortest signed angular difference from [from] to [to] in radians. */
    fun angleDiff(from: Float, to: Float): Float {
        var d = (to - from) % TAU
        if (d > PI) d -= TAU
        if (d < -PI) d += TAU
        return d
    }
    fun lerpAngle(a: Float, b: Float, t: Float): Float = a + angleDiff(a, b) * t
    fun nextPowerOfTwo(v: Int): Int { var n = 1; while (n < v) n = n shl 1; return n }
    fun isPowerOfTwo(v: Int) = v > 0 && (v and (v - 1)) == 0
}

/** Immutable 2D vector. Engine-facing code uses this; physics hot loops use raw floats. */
data class Vec2(val x: Float, val y: Float) {
    companion object {
        val ZERO = Vec2(0f, 0f)
        val ONE = Vec2(1f, 1f)
        val UP = Vec2(0f, -1f)
        val DOWN = Vec2(0f, 1f)
        val LEFT = Vec2(-1f, 0f)
        val RIGHT = Vec2(1f, 0f)
        fun fromAngle(radians: Float, length: Float = 1f) = Vec2(cos(radians) * length, sin(radians) * length)
    }

    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    operator fun div(s: Float) = Vec2(x / s, y / s)
    operator fun unaryMinus() = Vec2(-x, -y)
    operator fun get(i: Int) = if (i == 0) x else y
    infix fun dot(o: Vec2): Float = x * o.x + y * o.y
    infix fun cross(o: Vec2): Float = x * o.y - y * o.x
    val length: Float get() = sqrt(x * x + y * y)
    val lengthSquared: Float get() = x * x + y * y
    val angle: Float get() = atan2(y, x)
    val isZero: Boolean get() = x == 0f && y == 0f
    fun normalized(): Vec2 { val l = length; return if (l < 1e-6f) ZERO else Vec2(x / l, y / l) }
    fun withLength(len: Float): Vec2 { val l = length; return if (l < 1e-6f) ZERO else Vec2(x / l * len, y / l * len) }
    fun limit(maxLen: Float): Vec2 { val l = length; return if (l > maxLen) Vec2(x / l * maxLen, y / l * maxLen) else this }
    fun rotated(radians: Float): Vec2 {
        val c = cos(radians); val s = sin(radians); return Vec2(x * c - y * s, x * s + y * c)
    }
    fun lerp(o: Vec2, t: Float) = Vec2(MathUtil.lerp(x, o.x, t), MathUtil.lerp(y, o.y, t))
    fun movedTowards(o: Vec2, maxDelta: Float): Vec2 {
        val d = o - this; val l = d.length
        return if (l <= maxDelta || l < 1e-6f) o else this + d * (maxDelta / l)
    }
    fun distanceTo(o: Vec2): Float = hypot(x - o.x, y - o.y)
    fun distanceSquaredTo(o: Vec2): Float { val dx = x - o.x; val dy = y - o.y; return dx * dx + dy * dy }
    fun perpendicular() = Vec2(-y, x)
    fun abs() = Vec2(kotlin.math.abs(x), kotlin.math.abs(y))
    fun floor() = Vec2(floor(x), floor(y))
    fun round() = Vec2(x.roundToInt().toFloat(), y.roundToInt().toFloat())
    override fun toString() = "(%.2f, %.2f)".format(x, y)
}

/** Axis-aligned rectangle, stored as position + size (negative sizes are normalized on demand). */
data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
    companion object {
        val ZERO = Rect(0f, 0f, 0f, 0f)
        fun fromLTRB(l: Float, t: Float, r: Float, b: Float) = Rect(l, t, r - l, b - t)
        fun fromCenter(cx: Float, cy: Float, w: Float, h: Float) = Rect(cx - w / 2f, cy - h / 2f, w, h)
        fun expandToPoint(r: Rect?, px: Float, py: Float): Rect = if (r == null) Rect(px, py, 0f, 0f) else {
            val l = min(r.left, px); val t = min(r.top, py)
            val rr = max(r.right, px); val b = max(r.bottom, py)
            Rect.fromLTRB(l, t, rr, b)
        }
    }

    val left get() = x
    val top get() = y
    val right get() = x + w
    val bottom get() = y + h
    val centerX get() = x + w / 2f
    val centerY get() = y + h / 2f
    val center get() = Vec2(centerX, centerY)
    val position get() = Vec2(x, y)
    val size get() = Vec2(w, h)
    val area get() = w * h
    val isEmpty get() = w <= 0f || h <= 0f

    fun contains(px: Float, py: Float): Boolean = px >= left && px <= right && py >= top && py <= bottom
    fun contains(p: Vec2): Boolean = contains(p.x, p.y)
    fun containsFully(o: Rect): Boolean = o.left >= left && o.right <= right && o.top >= top && o.bottom <= bottom
    fun overlaps(o: Rect): Boolean = !(o.left >= right || o.right <= left || o.top >= bottom || o.bottom <= top)
    fun intersection(o: Rect): Rect {
        val l = max(left, o.left); val t = max(top, o.top)
        val r = min(right, o.right); val b = min(bottom, o.bottom)
        return Rect(l, t, max(0f, r - l), max(0f, b - t))
    }
    fun union(o: Rect): Rect {
        val l = min(left, o.left); val t = min(top, o.top)
        val r = max(right, o.right); val b = max(bottom, o.bottom)
        return Rect.fromLTRB(l, t, r, b)
    }
    fun grown(amount: Float) = Rect(x - amount, y - amount, w + amount * 2f, h + amount * 2f)
    fun grown(l: Float, t: Float, r: Float, b: Float) = Rect(x - l, y - t, w + l + r, h + t + b)
    fun translated(dx: Float, dy: Float) = Rect(x + dx, y + dy, w, h)
    fun atCenter(cx: Float, cy: Float) = Rect(cx - w / 2f, cy - h / 2f, w, h)
    fun withSize(nw: Float, nh: Float) = Rect(x, y, nw, nh)
    fun closestPoint(px: Float, py: Float) = Vec2(MathUtil.clamp(px, min(left, right), max(left, right)),
                                                   MathUtil.clamp(py, min(top, bottom), max(top, bottom)))
    override fun toString() = "Rect(%.1f, %.1f, %.1f, %.1f)".format(x, y, w, h)
}

/** Full 2D affine transform as a 3x3 matrix laid out row-major with the last row implicit (m20=0, m21=0, m22=1). */
data class Transform2D(
    val m00: Float = 1f, val m01: Float = 0f, val m02: Float = 0f,
    val m10: Float = 0f, val m11: Float = 1f, val m12: Float = 0f,
) {
    companion object {
        val IDENTITY = Transform2D()

        fun fromTranslation(x: Float, y: Float) = Transform2D(1f, 0f, x, 0f, 1f, y)

        fun fromScale(sx: Float, sy: Float) = Transform2D(sx, 0f, 0f, 0f, sy, 0f)

        fun fromRotation(radians: Float): Transform2D {
            val c = cos(radians); val s = sin(radians)
            return Transform2D(c, -s, 0f, s, c, 0f)
        }

        /** Builds a TRS transform with an optional pivot/origin in local space. */
        fun trs(pos: Vec2, rotation: Float, scale: Vec2, origin: Vec2 = Vec2.ZERO, skew: Float = 0f): Transform2D {
            val cx = cos(rotation); val s = sin(rotation)
            val kx = tan(skew)
            return Transform2D(
                cx * scale.x, (-s + kx * cx) * scale.y, pos.x - (origin.x * (cx * scale.x) + origin.y * (-s + kx * cx) * scale.y),
                s * scale.x, (cx + kx * s) * scale.y, pos.y - (origin.x * (s * scale.x) + origin.y * (cx + kx * s) * scale.y),
            )
        }
    }

    operator fun times(o: Transform2D) = Transform2D(
        m00 * o.m00 + m01 * o.m10, m00 * o.m01 + m01 * o.m11, m00 * o.m02 + m01 * o.m12 + m02,
        m10 * o.m00 + m11 * o.m10, m10 * o.m01 + m11 * o.m11, m10 * o.m02 + m11 * o.m12 + m12,
    )

    fun transformPoint(x: Float, y: Float) = Vec2(m00 * x + m01 * y + m02, m10 * x + m11 * y + m12)
    fun transformPoint(p: Vec2) = transformPoint(p.x, p.y)
    /** Transforms a direction (ignores translation) — used for normals and velocity. */
    fun transformVector(x: Float, y: Float) = Vec2(m00 * x + m01 * y, m10 * x + m11 * y)

    fun inverse(): Transform2D {
        val det = m00 * m11 - m01 * m10
        if (abs(det) < 1e-12f) return IDENTITY
        val inv = 1f / det
        val i00 = m11 * inv; val i01 = -m01 * inv
        val i10 = -m10 * inv; val i11 = m00 * inv
        return Transform2D(i00, i01, -(i00 * m02 + i01 * m12), i10, i11, -(i10 * m02 + i11 * m12))
    }

    val translation: Vec2 get() = Vec2(m02, m12)
    val rotation: Float get() = atan2(m10, m00)
    val scaleX: Float get() = hypot(m00, m10)
    val scaleY: Float get() = hypot(m01, m11)

    fun withTranslation(x: Float, y: Float) = copy(m02 = x, m12 = y)
    override fun toString() = "Transform2D[%.2f %.2f %.2f | %.2f %.2f %.2f]".format(m00, m01, m02, m10, m11, m12)
}

/** RGBA color with float components in 0..1 and sRGB-friendly helpers. */
data class Color(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {
    companion object {
        val TRANSPARENT = Color(0f, 0f, 0f, 0f)
        val BLACK = Color(0f, 0f, 0f)
        val WHITE = Color(1f, 1f, 1f)
        val RED = Color(1f, 0f, 0f)
        val GREEN = Color(0f, 1f, 0f)
        val BLUE = Color(0f, 0f, 1f)
        val YELLOW = Color(1f, 0.92f, 0.16f)
        val CYAN = Color(0f, 1f, 1f)
        val MAGENTA = Color(1f, 0f, 1f)
        val GRAY = Color(0.5f, 0.5f, 0.5f)
        val ORANGE = Color(1f, 0.6f, 0.15f)

        fun fromRgb24(rgb: Int, alpha: Float = 1f) = Color(
            ((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f, alpha)
        fun fromArgb32(argb: Int) = Color(
            ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f, ((argb ushr 24) and 0xFF) / 255f)
        fun fromHex(hex: String, alpha: Float = 1f): Color {
            var h = hex.trim().removePrefix("#")
            if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
            if (h.length == 8) {
                return Color(h.substring(0, 2).toInt(16) / 255f, h.substring(2, 4).toInt(16) / 255f,
                    h.substring(4, 6).toInt(16) / 255f, h.substring(6, 8).toInt(16) / 255f)
            }
            if (h.length != 6) return WHITE
            return Color(h.substring(0, 2).toInt(16) / 255f, h.substring(2, 4).toInt(16) / 255f,
                h.substring(4, 6).toInt(16) / 255f, alpha)
        }
    }

    fun toHex(includeAlpha: Boolean = false): String =
        if (includeAlpha) "#%02X%02X%02X%02X".format((r * 255).toInt().coerceIn(0, 255), (g * 255).toInt().coerceIn(0, 255),
            (b * 255).toInt().coerceIn(0, 255), (a * 255).toInt().coerceIn(0, 255))
        else "#%02X%02X%02X".format((r * 255).toInt().coerceIn(0, 255), (g * 255).toInt().coerceIn(0, 255), (b * 255).toInt().coerceIn(0, 255))

    fun toArgb32(): Int {
        val ai = (a.coerceIn(0f, 1f) * 255).toInt(); val ri = (r.coerceIn(0f, 1f) * 255).toInt()
        val gi = (g.coerceIn(0f, 1f) * 255).toInt(); val bi = (b.coerceIn(0f, 1f) * 255).toInt()
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
    fun toRgb24(): Int = (toArgb32() and 0xFFFFFF)

    operator fun times(s: Float) = Color(r * s, g * s, b * s, a)
    operator fun times(o: Color) = Color(r * o.r, g * o.g, b * o.b, a * o.a)
    fun withAlpha(na: Float) = Color(r, g, b, na)
    fun lerp(o: Color, t: Float) = Color(MathUtil.lerp(r, o.r, t), MathUtil.lerp(g, o.g, t),
        MathUtil.lerp(b, o.b, t), MathUtil.lerp(a, o.a, t))
    fun lighter(amount: Float = 0.1f) = Color(
        MathUtil.lerp(r, 1f, amount), MathUtil.lerp(g, 1f, amount), MathUtil.lerp(b, 1f, amount), a)
    fun darker(amount: Float = 0.1f) = Color(
        MathUtil.lerp(r, 0f, amount), MathUtil.lerp(g, 0f, amount), MathUtil.lerp(b, 0f, amount), a)
    /** Rec. 709 luminance. */
    val luminance: Float get() = 0.2126f * r + 0.7152f * g + 0.0722f * b
    fun toHsv(): FloatArray {
        val mx = max(max(r, g), b); val mn = min(min(r, g), b); val d = mx - mn
        var h = 0f
        if (d > 1e-6f) h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0f) h += 360f
        return floatArrayOf(h, if (mx <= 0f) 0f else d / mx, mx, a)
    }

    fun withHue(hueDegrees: Float): Color {
        val hsv = toHsv()
        return ColorUtil.fromHsv(hueDegrees, hsv[1], hsv[2], a)
    }
}

object ColorUtil {
    /** h: 0..360, s/v: 0..1 — the editor's color picker writes colors back through this. */
    fun fromHsv(h: Float, s: Float, v: Float, alpha: Float = 1f): Color {
        val hh = MathUtil.wrap(h, 0f, 360f) / 60f
        val i = floor(hh).toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        return when (i % 6) {
            0 -> Color(v, t, p, alpha)
            1 -> Color(q, v, p, alpha)
            2 -> Color(p, v, t, alpha)
            3 -> Color(p, q, v, alpha)
            4 -> Color(t, p, v, alpha)
            else -> Color(v, p, q, alpha)
        }
    }
}

object Easing {
    fun linear(t: Float) = t
    fun inQuad(t: Float) = t * t
    fun outQuad(t: Float) = t * (2f - t)
    fun inOutQuad(t: Float) = if (t < 0.5f) 2f * t * t else -1f + (4f - 2f * t) * t
    fun inCubic(t: Float) = t * t * t
    fun outCubic(t: Float) = (t - 1f).let { 1f + it * it * it }
    fun inOutCubic(t: Float) = if (t < 0.5f) 4f * t * t * t else (t - 1f).let { 1f + 4f * it * it * it }
    fun inQuart(t: Float) = t * t * t * t
    fun outQuart(t: Float) = 1f - (t - 1f).let { it * it * it * it }
    fun inOutQuart(t: Float): Float {
        val u = t - 1f
        return if (t < 0.5f) 8f * t * t * t * t else 1f - 8f * u * u * u * u
    }
    fun inQuint(t: Float) = t.pow(5)
    fun outQuint(t: Float) = 1f + (t - 1f).pow(5)
    fun inOutQuint(t: Float) = if (t < 0.5f) 16f * t.pow(5) else 1f + 16f * (t - 1f).pow(5)
    fun inSine(t: Float) = 1f - cos(t * PI.toFloat() / 2f)
    fun outSine(t: Float) = sin(t * PI.toFloat() / 2f)
    fun inOutSine(t: Float) = -(cos(PI.toFloat() * t) - 1f) / 2f
    fun inExpo(t: Float) = if (t <= 0f) 0f else 2f.pow(10f * (t - 1f))
    fun outExpo(t: Float) = if (t >= 1f) 1f else 1f - 2f.pow(-10f * t)
    fun inOutExpo(t: Float) = when {
        t <= 0f -> 0f; t >= 1f -> 1f; t < 0.5f -> 2f.pow(20f * t - 10f) / 2f
        else -> (2f - 2f.pow(-20f * t + 10f)) / 2f
    }
    fun inCirc(t: Float) = 1f - sqrt(max(0f, 1f - t * t))
    fun outCirc(t: Float) = sqrt(max(0f, 1f - (t - 1f) * (t - 1f)))
    fun inOutCirc(t: Float) = if (t < 0.5f) (1f - sqrt(max(0f, 1f - 4f * t * t))) / 2f
        else (sqrt(max(0f, 1f - (-2f * t + 2f) * (-2f * t + 2f))) + 1f) / 2f
    fun inBack(t: Float): Float { val c = 1.70158f; return (c + 1f) * t * t * t - c * t * t }
    fun outBack(t: Float): Float { val c = 1.70158f; val u = t - 1f; return 1f + (c + 1f) * u * u * u + c * u * u }
    fun inOutBack(t: Float): Float {
        val c = 1.70158f * 1.525f
        val u = t * 2f
        return if (u < 1f) (u * u * ((c + 1f) * u - c)) / 2f
        else ((u - 2f).let { it * it * ((c + 1f) * it + c) } + 2f) / 2f
    }
    fun inElastic(t: Float): Float {
        if (t <= 0f) return 0f; if (t >= 1f) return 1f
        val c = TAU / 3f
        return -2f.pow(10f * t - 10f) * sin((t * 10f - 10.75f) * c)
    }
    fun outElastic(t: Float): Float {
        if (t <= 0f) return 0f; if (t >= 1f) return 1f
        val c = TAU / 3f
        return 2f.pow(-10f * t) * sin((t * 10f - 0.75f) * c) + 1f
    }
    fun inBounce(t: Float) = 1f - outBounce(1f - t)
    fun outBounce(t: Float): Float {
        val n = 7.5625f; val d = 2.75f
        return when {
            t < 1f / d -> n * t * t
            t < 2f / d -> { val u = t - 1.5f / d; n * u * u + 0.75f }
            t < 2.5f / d -> { val u = t - 2.25f / d; n * u * u + 0.9375f }
            else -> { val u = t - 2.625f / d; n * u * u + 0.984375f }
        }
    }
    fun inOutBounce(t: Float) = if (t < 0.5f) (1f - outBounce(1f - 2f * t)) / 2f
        else (1f + outBounce(2f * t - 1f)) / 2f

    /** Named lookup so animations/tweens can store easing as a string in project files. */
    val named: Map<String, (Float) -> Float> = linkedMapOf(
        "linear" to ::linear, "in_quad" to ::inQuad, "out_quad" to ::outQuad, "in_out_quad" to ::inOutQuad,
        "in_cubic" to ::inCubic, "out_cubic" to ::outCubic, "in_out_cubic" to ::inOutCubic,
        "in_quart" to ::inQuart, "out_quart" to ::outQuart, "in_out_quart" to ::inOutQuart,
        "in_quint" to ::inQuint, "out_quint" to ::outQuint, "in_out_quint" to ::inOutQuint,
        "in_sine" to ::inSine, "out_sine" to ::outSine, "in_out_sine" to ::inOutSine,
        "in_expo" to ::inExpo, "out_expo" to ::outExpo, "in_out_expo" to ::inOutExpo,
        "in_circ" to ::inCirc, "out_circ" to ::outCirc, "in_out_circ" to ::inOutCirc,
        "in_back" to ::inBack, "out_back" to ::outBack, "in_out_back" to ::inOutBack,
        "in_elastic" to ::inElastic, "out_elastic" to ::outElastic,
        "in_bounce" to ::inBounce, "out_bounce" to ::outBounce, "in_out_bounce" to ::inOutBounce,
    )

    fun byName(name: String?): (Float) -> Float = named[name ?: "linear"] ?: ::linear
    val names: List<String> get() = named.keys.toList()
}

/** Deterministic PCG-style RNG — same sequence on every device, so replays & procgen match. */
class Rng(seed: Long = 0x2545F4914F6CDD1DL) {
    private var state: Long = 0
    private var inc: Long = 0

    init { reseed(seed) }

    fun reseed(seed: Long) {
        state = 0L; inc = (seed shl 1) or 1L
        nextUInt(); state += seed; nextUInt()
    }

    private fun nextUInt(): Int {
        val old = state
        state = old * 6364136223846793005L + inc
        val xorshifted = (((old ushr 18) xor old) ushr 27).toInt()
        val rot = (old ushr 59).toInt()
        return (xorshifted ushr rot) or (xorshifted shl ((-rot) and 31))
    }

    /** Uniform float in [0, 1). */
    fun nextFloat(): Float = (nextUInt() ushr 8) / 16777216f
    fun nextFloat(min: Float, max: Float): Float = min + nextFloat() * (max - min)
    fun nextInt(bound: Int): Int = if (bound <= 0) 0 else ((nextUInt().toLong() and 0x7FFFFFFFL) % bound).toInt()
    fun nextInt(min: Int, max: Int): Int = if (max <= min) min else min + nextInt(max - min + 1)
    fun nextLong(): Long = (nextUInt().toLong() shl 32) or (nextUInt().toLong() and 0xFFFFFFFFL)
    fun nextBool(chance: Float = 0.5f): Boolean = nextFloat() < chance
    fun sign(): Float = if (nextBool()) 1f else -1f
    fun nextVec2(min: Float = 0f, max: Float = 1f) = Vec2(nextFloat(min, max), nextFloat(min, max))
    fun insideCircle(radius: Float): Vec2 = Vec2.fromAngle(nextFloat(0f, TAU), radius * sqrt(nextFloat()))
    fun insideRect(r: Rect) = Vec2(nextFloat(r.left, r.right), nextFloat(r.top, r.bottom))
    fun onCircleEdge(radius: Float): Vec2 = Vec2.fromAngle(nextFloat(0f, TAU), radius)
    fun angle(): Float = nextFloat(0f, TAU)
    fun <T> pick(list: List<T>): T = list[nextInt(list.size)]
    fun <T> pickOrNull(list: List<T>): T? = if (list.isEmpty()) null else list[nextInt(list.size)]
    fun <T> pickWeighted(items: List<T>, weights: List<Float>): T? {
        if (items.isEmpty() || items.size != weights.size) return null
        var total = 0f; weights.forEach { total += max(0f, it) }
        if (total <= 0f) return pick(items)
        var r = nextFloat() * total
        for (i in items.indices) { r -= max(0f, weights[i]); if (r <= 0f) return items[i] }
        return items.last()
    }
    fun <T> shuffle(list: MutableList<T>) {
        for (i in list.size - 1 downTo 1) { val j = nextInt(i + 1); val t = list[i]; list[i] = list[j]; list[j] = t }
    }
    fun gaussian(mean: Float = 0f, stdDev: Float = 1f): Float {
        // Box–Muller, cached second sample.
        if (hasSpare) { hasSpare = false; return spare * stdDev + mean }
        var u: Float; var v: Float; var s: Float
        do { u = nextFloat() * 2f - 1f; v = nextFloat() * 2f - 1f; s = u * u + v * v } while (s >= 1f || s == 0f)
        val mul = sqrt(-2f * kotlin.math.ln(s) / s)
        spare = v * mul; hasSpare = true
        return u * mul * stdDev + mean
    }
    private var hasSpare = false
    private var spare = 0f

    /** Result of [nextUInt] surfaced for hashing usage. */
    fun nextSeed(): Long = nextLong()
}

/** Value-noise + fBm helpers for procedural terrain, textures and dungeon layout. */
class Noise2D(seed: Long = 1337L) {
    private val perm = IntArray(512)
    private val gradX = FloatArray(512)
    private val gradY = FloatArray(512)

    init {
        val rng = Rng(seed)
        val p = MutableList(256) { it }
        rng.shuffle(p)
        for (i in 0 until 512) {
            perm[i] = p[i and 255]
            val a = (perm[i] / 256f) * TAU * 2f
            gradX[i] = cos(a); gradY[i] = sin(a)
        }
    }

    private fun fade(t: Float) = t * t * t * (t * (t * 6f - 15f) + 10f)

    fun at(x: Float, y: Float): Float {
        val xi = floor(x).toInt() and 255
        val yi = floor(y).toInt() and 255
        val xf = x - floor(x); val yf = y - floor(y)
        val u = fade(xf); val v = fade(yf)
        fun dot(hash: Int, dx: Float, dy: Float): Float {
            val h = hash and 511
            return gradX[h] * dx + gradY[h] * dy
        }
        val n00 = dot(perm[xi] + yi, xf, yf)
        val n10 = dot(perm[xi + 1] + yi, xf - 1f, yf)
        val n01 = dot(perm[xi] + yi + 1, xf, yf - 1f)
        val n11 = dot(perm[xi + 1] + yi + 1, xf - 1f, yf - 1f)
        val nx0 = MathUtil.lerp(n00, n10, u)
        val nx1 = MathUtil.lerp(n01, n11, u)
        return MathUtil.lerp(nx0, nx1, v)   // roughly -1..1
    }

    fun fbm(x: Float, y: Float, octaves: Int = 4, lacunarity: Float = 2f, gain: Float = 0.5f): Float {
        var sum = 0f; var amp = 1f; var freq = 1f; var norm = 0f
        repeat(octaves) {
            sum += at(x * freq, y * freq) * amp; norm += amp
            amp *= gain; freq *= lacunarity
        }
        return if (norm > 0f) sum / norm else 0f
    }

    /** Ridged multifractal — good for cave walls and mountain silhouettes. */
    fun ridged(x: Float, y: Float, octaves: Int = 4): Float {
        var sum = 0f; var amp = 1f; var freq = 1f; var norm = 0f
        repeat(octaves) {
            sum += (1f - abs(at(x * freq, y * freq))) * amp; norm += amp
            amp *= 0.5f; freq *= 2f
        }
        return if (norm > 0f) sum / norm else 0f
    }
}
