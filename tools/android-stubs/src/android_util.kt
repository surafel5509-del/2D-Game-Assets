// Android framework stubs — android.util (type-check only).
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.util

open class DisplayMetrics {
    var density: Float = 1f
    var densityDpi: Int = 160
    var scaledDensity: Float = 1f
    var widthPixels: Int = 1080
    var heightPixels: Int = 1920
    fun setTo(o: DisplayMetrics) {}
    fun setToDefaults() {}
}

interface AttributeSet {
    fun getAttributeCount(): Int = 0
    fun getAttributeName(index: Int): String? = null
    fun getAttributeValue(index: Int): String? = null
    fun getAttributeValue(namespace: String?, name: String): String? = null
    fun getStyleAttribute(): Int = 0
}

object TypedValue {
    const val COMPLEX_UNIT_PX = 0
    const val COMPLEX_UNIT_DIP = 1
    const val COMPLEX_UNIT_SP = 2
    fun applyDimension(unit: Int, value: Float, metrics: DisplayMetrics): Float = value
}

object Log {
    fun v(tag: String, msg: String): Int = 0
    fun d(tag: String, msg: String): Int = 0
    fun i(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String, tr: Throwable?): Int = 0
    fun e(tag: String, msg: String): Int = 0
    fun e(tag: String, msg: String, tr: Throwable?): Int = 0
    fun getStackTraceString(tr: Throwable?): String = ""
}

class SparseArray<E> {
    fun put(key: Int, value: E) {}
    fun get(key: Int): E? = TODO()
    val size: Int get() = 0
}
