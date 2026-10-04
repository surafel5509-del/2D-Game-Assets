// Android framework stubs — android.graphics (type-check only).
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")

package android.graphics

open class Bitmap {
    val width: Int get() = 0
    val height: Int get() = 0
    val isRecycled: Boolean get() = false
    fun recycle() {}
    fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {}
    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {}
    fun copy(config: Config, isMutable: Boolean): Bitmap = this
    class Config {
        companion object {
            val ARGB_8888: Config = Config()
            val RGB_565: Config = Config()
            val ALPHA_8: Config = Config()
        }
    }
    companion object {
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap = Bitmap()
        fun createBinaryBitmap(value: Bitmap): Bitmap = value
        fun createBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap = source
        fun createScaledBitmap(src: Bitmap, width: Int, height: Int, filter: Boolean): Bitmap = src
        fun createBitmap(pixels: IntArray, width: Int, height: Int, config: Config): Bitmap = Bitmap()
    }
}

open class Canvas {
    constructor(bitmap: Bitmap)
    constructor()
    val width: Int get() = 0
    val height: Int get() = 0
    fun drawColor(color: Int) {}
    fun drawColor(color: Int, mode: PorterDuff.Mode) {}
    fun drawARGB(a: Int, r: Int, g: Int, b: Int) {}
    fun drawRGB(r: Int, g: Int, b: Int) {}
    fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {}
    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect?, paint: Paint?) {}
    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {}
    fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {}
    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {}
    fun drawRect(rect: Rect, paint: Paint) {}
    fun drawRect(rect: RectF, paint: Paint) {}
    fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) {}
    fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {}
    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {}
    fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {}
    fun drawLines(pts: FloatArray, offset: Int, count: Int, paint: Paint) {}
    fun drawText(text: String, x: Float, y: Float, paint: Paint) {}
    fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {}
    fun drawPath(path: Path, paint: Paint) {}
    fun drawPoint(x: Float, y: Float, paint: Paint) {}
    fun save(): Int = 0
    fun restore() {}
    fun restoreToCount(count: Int) {}
    fun translate(dx: Float, dy: Float) {}
    fun scale(sx: Float, sy: Float) {}
    fun rotate(degrees: Float) {}
    fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean = true
    fun clipRect(rect: Rect): Boolean = true
}

open class Paint {
    constructor()
    constructor(flags: Int)
    var color: Int = 0
    var style: Style = Style.FILL
    var strokeWidth: Float = 0f
    var textSize: Float = 0f
    var isAntiAlias: Boolean = false
    var isFilterBitmap: Boolean = false
    var typeface: Typeface? = null
    var textAlign: Align = Align.LEFT
    var alpha: Int = 255
    var shader: Shader? = null
    var strokeCap: Cap = Cap.BUTT
    var strokeJoin: Join = Join.MITER
    var isFakeBoldText: Boolean = false
    var isUnderlineText: Boolean = false
    fun measureText(text: String): Float = 0f
    fun measureText(text: CharSequence, start: Int, end: Int): Float = 0f
    fun getTextBounds(text: String, start: Int, end: Int, bounds: Rect) {}
    fun getFontMetrics(): FontMetrics = FontMetrics()
    fun getFontMetricsInt(): FontMetricsInt = FontMetricsInt()
    fun setShadowLayer(radius: Float, dx: Float, dy: Float, color: Int) {}
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Align { LEFT, CENTER, RIGHT }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }
    class FontMetrics {
        var top: Float = 0f
        var ascent: Float = 0f
        var descent: Float = 0f
        var bottom: Float = 0f
        var leading: Float = 0f
    }
    class FontMetricsInt {
        var top: Int = 0
        var ascent: Int = 0
        var descent: Int = 0
        var bottom: Int = 0
        var leading: Int = 0
    }
    companion object {
        const val ANTI_ALIAS_FLAG = 1
        const val FILTER_BITMAP_FLAG = 2
    }
}

class Rect {
    var left: Int = 0
    var top: Int = 0
    var right: Int = 0
    var bottom: Int = 0
    constructor()
    constructor(l: Int, t: Int, r: Int, b: Int)
    constructor(r: Rect)
    fun set(left: Int, top: Int, right: Int, bottom: Int) {}
    fun setEmpty() {}
    fun width(): Int = right - left
    fun height(): Int = bottom - top
    val isEmpty: Boolean get() = left >= right || top >= bottom
    fun centerX(): Int = (left + right) / 2
    fun centerY(): Int = (top + bottom) / 2
    fun contains(x: Int, y: Int): Boolean = false
    fun offset(dx: Int, dy: Int) {}
    fun inset(dx: Int, dy: Int) {}
    fun union(l: Int, t: Int, r: Int, b: Int) {}
    override fun toString(): String = "Rect($left, $top, $right, $bottom)"
}

class RectF {
    var left: Float = 0f
    var top: Float = 0f
    var right: Float = 0f
    var bottom: Float = 0f
    constructor()
    constructor(l: Float, t: Float, r: Float, b: Float)
    constructor(r: Rect)
    fun set(l: Float, t: Float, r: Float, b: Float) {}
    fun width(): Float = right - left
    fun height(): Float = bottom - top
    val isEmpty: Boolean get() = left >= right || top >= bottom
    fun centerX(): Float = (left + right) / 2f
    fun centerY(): Float = (top + bottom) / 2f
    fun contains(x: Float, y: Float): Boolean = false
    fun inset(dx: Float, dy: Float) {}
    fun offset(dx: Float, dy: Float) {}
}

class Point {
    var x: Int = 0
    var y: Int = 0
    constructor()
    constructor(x: Int, y: Int)
}

object Color {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = -1
    const val RED = 0xFFFF0000.toInt()
    const val GREEN = 0xFF00FF00.toInt()
    const val BLUE = 0xFF0000FF.toInt()
    const val TRANSPARENT = 0
    const val GRAY = 0xFF888888.toInt()
    const val LTGRAY = 0xFFCCCCCC.toInt()
    const val DKGRAY = 0xFF444444.toInt()
    const val YELLOW = 0xFFFFFF00.toInt()
    const val CYAN = 0xFF00FFFF.toInt()
    const val MAGENTA = 0xFFFF00FF.toInt()
    fun rgb(r: Int, g: Int, b: Int): Int = 0
    fun argb(a: Int, r: Int, g: Int, b: Int): Int = 0
    fun parseColor(colorString: String): Int = 0
    fun alpha(color: Int): Int = 0
    fun red(color: Int): Int = 0
    fun green(color: Int): Int = 0
    fun blue(color: Int): Int = 0
    fun colorToHSV(color: Int, hsv: FloatArray) {}
    fun HSVToColor(hsv: FloatArray): Int = 0
    fun HSVToColor(alpha: Int, hsv: FloatArray): Int = 0
}

open class Typeface {
    companion object {
        val DEFAULT: Typeface = Typeface()
        val DEFAULT_BOLD: Typeface = Typeface()
        val MONOSPACE: Typeface = Typeface()
        val SANS_SERIF: Typeface = Typeface()
        val SERIF: Typeface = Typeface()
        const val NORMAL = 0
        const val BOLD = 1
        const val ITALIC = 2
        fun create(family: String?, style: Int): Typeface = Typeface()
        fun create(family: Typeface?, style: Int): Typeface = family ?: Typeface()
    }
}

class Path {
    fun moveTo(x: Float, y: Float) {}
    fun lineTo(x: Float, y: Float) {}
    fun close() {}
    fun reset() {}
    fun addRect(l: Float, t: Float, r: Float, b: Float, dir: Direction) {}
    enum class Direction { CW, CCW }
}

class Matrix {
    fun setScale(sx: Float, sy: Float) {}
    fun postTranslate(dx: Float, dy: Float) {}
    fun reset() {}
}

open class Shader
class LinearGradient(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, positions: FloatArray?, tile: TileMode) : Shader()
enum class TileMode { CLAMP, REPEAT, MIRROR }
class PorterDuff { enum class Mode { CLEAR, SRC, DST, SRC_OVER, DST_OVER, SRC_IN, DST_IN, MULTIPLY, SCREEN, ADD, OVERLAY } }
