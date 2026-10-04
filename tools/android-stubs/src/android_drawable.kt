// Android framework stubs — android.graphics.drawable (type-check only).
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")

package android.graphics.drawable

open class Drawable {
    open var alpha: Int = 255
    open fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {}
    open fun setTint(tintColor: Int) {}
    open fun mutate(): Drawable = this
    open fun setFilterBitmap(filter: Boolean) {}
    open val intrinsicWidth: Int get() = 0
    open val intrinsicHeight: Int get() = 0
}

open class ColorDrawable(color: Int = 0) : Drawable()

open class ShapeDrawable : Drawable() {
    open val paint: android.graphics.Paint? get() = null
    open fun setIntrinsicWidth(width: Int) {}
    open fun setIntrinsicHeight(height: Int) {}
}

open class LayerDrawable(drawables: Array<Drawable>) : Drawable()

open class StateListDrawable : Drawable() {
    open fun addState(stateSet: IntArray, drawable: Drawable) {}
}

open class InsetDrawable(drawable: Drawable?, inset: Int) : Drawable()

class GradientDrawable : Drawable() {
    enum class Orientation { TOP_BOTTOM, TR_BL, RIGHT_LEFT, LEFT_RIGHT, BL_TR, BOTTOM_TOP, BR_TL, TL_BR }
    fun setColor(color: Int) {}
    fun setColors(colors: IntArray) {}
    fun setCornerRadius(radius: Float) {}
    fun setCornerRadii(radii: FloatArray) {}
    fun setStroke(width: Int, color: Int) {}
    fun setShape(shape: Int) {}
    fun setGradientType(type: Int) {}
    fun setOrientation(orientation: Orientation) {}
    fun setPadding(l: Int, t: Int, r: Int, b: Int) {}
    companion object { const val RECTANGLE = 0; const val OVAL = 1; const val LINE = 2; const val RING = 3 }
}
