// Android framework stubs — android.widget (type-check only).
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")

package android.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup

open class LinearLayout : ViewGroup {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    var orientation: Int = HORIZONTAL
    var gravity: Int = Gravity.NO_GRAVITY
    var weightSum: Float = 0f
    var dividerPadding: Int = 0
    fun setDividerDrawable(divider: Drawable?) {}
    fun setShowDividers(showDividers: Int) {}
    companion object {
        const val HORIZONTAL = 0
        const val VERTICAL = 1
        const val SHOW_DIVIDER_NONE = 0
        const val SHOW_DIVIDER_BEGINNING = 1
        const val SHOW_DIVIDER_MIDDLE = 2
        const val SHOW_DIVIDER_END = 4
    }
    class LayoutParams : ViewGroup.MarginLayoutParams {
        var weight: Float = 0f
        var gravity: Int = Gravity.NO_GRAVITY
        constructor(width: Int, height: Int) : super(width, height)
        constructor(width: Int, height: Int, weight: Float) : super(width, height) { this.weight = weight }
    }
}

open class FrameLayout : ViewGroup {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    var foregroundGravity: Int = Gravity.NO_GRAVITY
    class LayoutParams : ViewGroup.MarginLayoutParams {
        var gravity: Int = Gravity.NO_GRAVITY
        constructor(width: Int, height: Int) : super(width, height)
        constructor(width: Int, height: Int, gravity: Int) : super(width, height) { this.gravity = gravity }
    }
}

open class ScrollView : FrameLayout {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    var isFillViewport: Boolean = false
    fun fullScroll(direction: Int): Boolean = true
    fun smoothScrollTo(x: Int, y: Int) {}
    companion object { const val FOCUS_DOWN = 130; const val FOCUS_UP = 33 }
}

open class HorizontalScrollView : FrameLayout {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    var isFillViewport: Boolean = false
    fun fullScroll(direction: Int): Boolean = true
    fun smoothScrollTo(x: Int, y: Int) {}
}

open class TextView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    open var text: CharSequence? = null
    var hint: CharSequence? = null
    var textSize: Float = 14f
    var textColor: Int = 0
    var gravity: Int = Gravity.NO_GRAVITY
    var typeface: Typeface? = null
    var maxLines: Int = Int.MAX_VALUE
    var isSingleLine: Boolean = false
    var isAllCaps: Boolean = false
    var letterSpacing: Float = 0f
    var paint: android.graphics.Paint? = null
    open fun setText(value: Int) {}
    fun setTextSize(unit: Int, size: Float) {}
    fun setTypeface(tf: Typeface?, style: Int) {}
    fun setLineSpacing(add: Float, mult: Float) {}
    fun setShadowLayer(radius: Float, dx: Float, dy: Float, color: Int) {}
    fun append(value: CharSequence?) {}
    fun setEllipsize(where: android.text.TextUtils.TruncateAt?) {}
    fun setMinLines(minLines: Int) {}
    fun setMaxWidth(maxPixels: Int) {}
    fun setHorizontallyScrolling(horizontallyScrolling: Boolean) {}
    fun setIncludeFontPadding(includepad: Boolean) {}
    override fun onDraw(canvas: Canvas) {}
    companion object { const val TEXT_ALIGNMENT_TEXT_START = 5 }
}

open class Button : TextView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
}

open class EditText : TextView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    var inputType: Int = 0
    var imeOptions: Int = 0
    var isCursorVisible: Boolean = true
    var selectionStart: Int = 0
    var selectionEnd: Int = 0
    fun setSelection(index: Int) {}
    fun setFilters(filters: Array<InputFilter>) {}
    fun addTextChangedListener(watcher: TextWatcher) {}
    fun removeTextChangedListener(watcher: TextWatcher) {}
    fun getText(): Editable? = TODO()
    fun setSelection(start: Int, stop: Int) {}
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean = false
}

open class CompoundButton : Button {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    var isChecked: Boolean = false
    fun toggle() {}
    fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {}
    fun interface OnCheckedChangeListener { fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) }
}

open class CheckBox : CompoundButton {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
}

open class Switch : CompoundButton {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    var switchText: CharSequence? = null
    fun setTextOn(text: CharSequence?) {}
    fun setTextOff(text: CharSequence?) {}
}

open class RadioButton : CompoundButton {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
}

open class RadioGroup : LinearLayout {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    var checkedRadioButtonId: Int = -1
    fun check(id: Int) {}
    fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {}
    fun interface OnCheckedChangeListener { fun onCheckedChanged(group: RadioGroup?, checkedId: Int) }
}

open class SeekBar : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    var max: Int = 100
    var progress: Int = 0
    var keyProgressIncrement: Int = 1
    fun setOnSeekBarChangeListener(listener: OnSeekBarChangeListener?) {}
    interface OnSeekBarChangeListener {
        fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
        fun onStartTrackingTouch(seekBar: SeekBar?) {}
        fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}

open class ProgressBar : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    var max: Int = 100
    var progress: Int = 0
    var isIndeterminate: Boolean = false
}

open class ImageView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    var scaleType: ScaleType? = null
    fun setImageBitmap(bm: android.graphics.Bitmap?) {}
    fun setImageResource(resId: Int) {}
    fun setImageDrawable(drawable: Drawable?) {}
    enum class ScaleType { FIT_CENTER, CENTER, CENTER_CROP, FIT_XY, MATRIX }
}

open class Space : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
}

open class Toast {
    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1
        fun makeText(context: Context, text: CharSequence, duration: Int): Toast = Toast()
        fun makeText(context: Context, resId: Int, duration: Int): Toast = Toast()
    }
    var text: CharSequence? = null
    fun show() {}
    fun cancel() {}
    fun setGravity(gravity: Int, xOffset: Int, yOffset: Int) {}
    fun setDuration(duration: Int) {}
}

open class PopupMenu {
    constructor(context: Context, anchor: View)
    fun getMenu(): Menu = Menu()
    fun show() {}
    fun dismiss() {}
    fun setOnMenuItemClickListener(listener: OnMenuItemClickListener?) {}
    fun interface OnMenuItemClickListener { fun onMenuItemClick(item: Menu.MenuItem?): Boolean }
}

open class Menu {
    fun add(title: CharSequence): MenuItem = MenuItem()
    fun add(groupId: Int, itemId: Int, order: Int, title: CharSequence): MenuItem = MenuItem()
    fun addSubMenu(title: CharSequence): SubMenu = SubMenu()
    fun size(): Int = 0
    fun clear() {}
    fun getItem(index: Int): MenuItem? = null
    open class MenuItem {
        var itemId: Int = 0
        var order: Int = 0
        var title: CharSequence? = null
        fun setOnMenuItemClickListener(listener: PopupMenu.OnMenuItemClickListener?): MenuItem = this
        fun setEnabled(enabled: Boolean): MenuItem = this
        fun setVisible(visible: Boolean): MenuItem = this
    }
    class SubMenu : MenuItem() {
        fun add(title: CharSequence): MenuItem = MenuItem()
    }
}

open class ArrayAdapter<T>(context: Context, resource: Int, objects: List<T>) {
    fun add(element: T) {}
    fun addAll(elements: Collection<T>) {}
    fun clear() {}
    fun notifyDataSetChanged() {}
    fun getCount(): Int = 0
    fun getItem(position: Int): T? = TODO()
}

open class ListView : ViewGroup {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    var adapter: Any? = null
    var choiceMode: Int = 0
    var isFastScrollEnabled: Boolean = false
    fun setOnItemClickListener(listener: AdapterView.OnItemClickListener?) {}
    fun setOnItemLongClickListener(listener: AdapterView.OnItemLongClickListener?) {}
    fun smoothScrollToPosition(position: Int) {}
    fun setSelection(position: Int) {}
    companion object { const val CHOICE_MODE_NONE = 0; const val CHOICE_MODE_SINGLE = 1 }
}

class AdapterView {
    fun interface OnItemClickListener { fun onItemClick(parent: AdapterView?, view: View?, position: Int, id: Long) }
    interface OnItemLongClickListener { fun onItemLongClick(parent: AdapterView?, view: View?, position: Int, id: Long): Boolean }
}
