// Android framework stubs — android.view (type-check only).
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")

package android.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.accessibility.AccessibilityNodeInfo

open class KeyEvent {
    val keyCode: Int get() = 0
    val action: Int get() = 0
    val repeatCount: Int get() = 0
    val scanCode: Int get() = 0
    val isShiftPressed: Boolean get() = false
    val isCtrlPressed: Boolean get() = false
    val isAltPressed: Boolean get() = false
    val eventTime: Long get() = 0L
    val unicodeChar: Int get() = 0
    val deviceId: Int get() = 0
    val source: Int get() = 0
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MULTIPLE = 2
        const val KEYCODE_UNKNOWN = 0
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_DPAD_CENTER = 23
        const val KEYCODE_BACK = 4
        const val KEYCODE_MENU = 82
        const val KEYCODE_A = 29
        const val KEYCODE_B = 30
        const val KEYCODE_C = 31
        const val KEYCODE_D = 32
        const val KEYCODE_E = 33
        const val KEYCODE_F = 34
        const val KEYCODE_G = 35
        const val KEYCODE_H = 36
        const val KEYCODE_I = 37
        const val KEYCODE_J = 38
        const val KEYCODE_K = 39
        const val KEYCODE_L = 40
        const val KEYCODE_M = 41
        const val KEYCODE_N = 42
        const val KEYCODE_O = 43
        const val KEYCODE_P = 44
        const val KEYCODE_Q = 45
        const val KEYCODE_R = 46
        const val KEYCODE_S = 47
        const val KEYCODE_T = 48
        const val KEYCODE_U = 49
        const val KEYCODE_V = 50
        const val KEYCODE_W = 51
        const val KEYCODE_X = 52
        const val KEYCODE_Y = 53
        const val KEYCODE_Z = 54
        const val KEYCODE_0 = 7
        const val KEYCODE_1 = 8
        const val KEYCODE_2 = 9
        const val KEYCODE_3 = 10
        const val KEYCODE_4 = 11
        const val KEYCODE_5 = 12
        const val KEYCODE_6 = 13
        const val KEYCODE_7 = 14
        const val KEYCODE_8 = 15
        const val KEYCODE_9 = 16
        const val KEYCODE_ALT_LEFT = 57
        const val KEYCODE_ALT_RIGHT = 58
        const val KEYCODE_SHIFT_LEFT = 59
        const val KEYCODE_SHIFT_RIGHT = 60
        const val KEYCODE_TAB = 61
        const val KEYCODE_SPACE = 62
        const val KEYCODE_ENTER = 66
        const val KEYCODE_DEL = 67
        const val KEYCODE_ESCAPE = 111
        const val KEYCODE_FORWARD_DEL = 112
        const val KEYCODE_CTRL_LEFT = 113
        const val KEYCODE_CTRL_RIGHT = 114
        const val KEYCODE_F1 = 131
        const val KEYCODE_F2 = 132
        const val KEYCODE_F3 = 133
        const val KEYCODE_F4 = 134
        const val KEYCODE_F5 = 135
        const val KEYCODE_BUTTON_A = 96
        const val KEYCODE_BUTTON_B = 97
        const val KEYCODE_BUTTON_X = 99
        const val KEYCODE_BUTTON_Y = 100
        const val KEYCODE_BUTTON_L1 = 102
        const val KEYCODE_BUTTON_R1 = 103
        const val KEYCODE_BUTTON_START = 108
    }
}

open class MotionEvent {
    val action: Int get() = 0
    val actionMasked: Int get() = 0
    val actionIndex: Int get() = 0
    val pointerCount: Int get() = 1
    val x: Float get() = 0f
    val y: Float get() = 0f
    val eventTime: Long get() = 0L
    val downTime: Long get() = 0L
    val source: Int get() = 0
    val deviceId: Int get() = 0
    fun getX(index: Int): Float = 0f
    fun getY(index: Int): Float = 0f
    fun getPointerId(index: Int): Int = 0
    fun getPressure(index: Int): Float = 1f
    fun findPointerIndex(id: Int): Int = 0
    fun getHistoricalX(i: Int): Float = 0f
    fun getHistoricalY(i: Int): Float = 0f
    fun getHistorySize(): Int = 0
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_OUTSIDE = 4
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
        const val ACTION_HOVER_MOVE = 7
        const val ACTION_SCROLL = 8
        const val ACTION_POINTER_INDEX_SHIFT = 8
        const val ACTION_POINTER_INDEX_MASK = 0xFF00
        const val TOOL_TYPE_FINGER = 1
        const val TOOL_TYPE_STYLUS = 2
        const val TOOL_TYPE_MOUSE = 3
        const val AXIS_X = 0
        const val AXIS_Y = 1
        const val AXIS_HSCROLL = 10
        const val AXIS_VSCROLL = 9
    }
}

open class View {
    constructor(context: Context)
    constructor(context: Context, attrs: AttributeSet?)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int)
    open var id: Int = 0
    open var visibility: Int = VISIBLE
    open var isEnabled: Boolean = true
    open var isClickable: Boolean = false
    open var isFocusable: Boolean = true
    open var isFocusableInTouchMode: Boolean = false
    open var isSelected: Boolean = false
    open var isActivated: Boolean = false
    open var keepScreenOn: Boolean = false
    open var alpha: Float = 1f
    open var rotation: Float = 0f
    open var translationX: Float = 0f
    open var translationY: Float = 0f
    open var tag: Any? = null
    var background: Drawable? = null
    var layoutParams: ViewGroup.LayoutParams? = null
    open val parent: ViewParent? get() = null
    open val isAttachedToWindow: Boolean get() = false
    open val isShown: Boolean get() = true
    open val width: Int get() = 0
    open val height: Int get() = 0
    open val measuredWidth: Int get() = 0
    open val measuredHeight: Int get() = 0
    open val paddingLeft: Int get() = 0
    open val paddingTop: Int get() = 0
    open val paddingRight: Int get() = 0
    open val paddingBottom: Int get() = 0
    open val resources: android.content.res.Resources get() = android.content.res.Resources()
    open val context: Context get() = Context()
    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {}
    fun setPaddingRelative(start: Int, top: Int, end: Int, bottom: Int) {}
    fun setBackgroundColor(color: Int) {}
    fun setBackgroundResource(resId: Int) {}
    fun setOnClickListener(l: OnClickListener?) {}
    fun setOnLongClickListener(l: OnLongClickListener?) {}
    fun setOnTouchListener(l: OnTouchListener?) {}
    fun setOnKeyListener(l: OnKeyListener?) {}
    fun setOnGenericMotionListener(l: OnGenericMotionListener?) {}
    fun setOnFocusChangeListener(l: OnFocusChangeListener?) {}
    fun setLayerType(layerType: Int, paint: android.graphics.Paint?) {}
    fun setElevation(elevation: Float) {}
    fun setMinimumWidth(minWidth: Int) {}
    fun setMinimumHeight(minHeight: Int) {}
    fun setContentDescription(description: CharSequence?) {}
    fun setSystemUiVisibility(visibility: Int) {}
    fun setTextAlignment(textAlignment: Int) {}
    fun requestFocus(): Boolean = true
    fun requestLayout() {}
    fun invalidate() {}
    fun postInvalidate() {}
    fun postInvalidateOnAnimation() {}
    fun post(action: Runnable): Boolean = true
    fun postDelayed(action: Runnable, delayMillis: Long): Boolean = true
    fun removeCallbacks(action: Runnable) {}
    fun measure(widthMeasureSpec: Int, heightMeasureSpec: Int) {}
    fun layout(l: Int, t: Int, r: Int, b: Int) {}
    fun getLocationOnScreen(location: IntArray) {}
    fun getLocationInWindow(location: IntArray) {}
    fun performClick(): Boolean = true
    fun performHapticFeedback(feedbackConstant: Int): Boolean = true
    fun scrollTo(x: Int, y: Int) {}
    fun scrollBy(x: Int, y: Int) {}
    fun startAnimation(animation: Any?) {}
    fun bringToFront() {}
    fun addOnLayoutChangeListener(listener: OnLayoutChangeListener?) {}
    open fun onDraw(canvas: Canvas) {}
    open fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {}
    open fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {}
    open fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {}
    open fun onTouchEvent(event: MotionEvent): Boolean = false
    open fun onGenericMotionEvent(event: MotionEvent): Boolean = false
    open fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false
    open fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
    open fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean = false
    open fun onAttachedToWindow() {}
    open fun onDetachedFromWindow() {}
    open fun onWindowVisibilityChanged(visibility: Int) {}
    open fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {}
    open fun draw(canvas: Canvas) {}
    open fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {}

    fun interface OnClickListener { fun onClick(v: View?) }
    fun interface OnLongClickListener { fun onLongClick(v: View?): Boolean }
    fun interface OnTouchListener { fun onTouch(v: View?, event: MotionEvent): Boolean }
    fun interface OnKeyListener { fun onKey(v: View?, keyCode: Int, event: KeyEvent): Boolean }
    interface OnGenericMotionListener { fun onGenericMotion(v: View?, event: MotionEvent): Boolean }
    fun interface OnFocusChangeListener { fun onFocusChange(v: View?, hasFocus: Boolean) }
    interface OnLayoutChangeListener { fun onLayoutChange(v: View?, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) }

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
        const val LAYER_TYPE_NONE = 0
        const val LAYER_TYPE_SOFTWARE = 1
        const val LAYER_TYPE_HARDWARE = 2
        const val TEXT_ALIGNMENT_TEXT_START = 5
        const val TEXT_ALIGNMENT_VIEW_START = 2
        const val SYSTEM_UI_FLAG_FULLSCREEN = 4
        const val SYSTEM_UI_FLAG_HIDE_NAVIGATION = 2
        const val SYSTEM_UI_FLAG_IMMERSIVE_STICKY = 4096
        const val SYSTEM_UI_FLAG_LAYOUT_STABLE = 256
        const val SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN = 1024
        const val SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION = 512
        const val HAPTIC_FEEDBACK_ENABLED = 1
        fun resolveSize(size: Int, measureSpec: Int): Int = size
        fun getDefaultSize(size: Int, measureSpec: Int): Int = size
        fun generateViewId(): Int = 1
    }
}

interface ViewParent {
    fun requestLayout() {}
    fun invalidateChild(child: View?, dirty: Rect?) {}
    fun bringChildToFront(child: View?) {}
    fun getParent(): ViewParent? = TODO()
}

open class ViewGroup : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
    var clipChildren: Boolean = true
    var clipToPadding: Boolean = true
    var descendantFocusability: Int = 0
    val childCount: Int get() = 0
    open fun addView(child: View) {}
    open fun addView(child: View, index: Int) {}
    open fun addView(child: View, params: LayoutParams?) {}
    open fun addView(child: View, width: Int, height: Int) {}
    open fun removeView(view: View) {}
    open fun removeViewAt(index: Int) {}
    open fun removeAllViews() {}
    open fun removeAllViewsInLayout() {}
    open fun getChildAt(index: Int): View? = TODO()
    open fun indexOfChild(child: View?): Int = -1
    open fun bringChildToFront(child: View) {}
    open fun setLayoutTransition(transition: Any?) {}
    open fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {}
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {}

    open class LayoutParams {
        var width: Int = 0
        var height: Int = 0
        constructor(width: Int, height: Int)
        constructor(source: LayoutParams)
        companion object {
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
            const val FILL_PARENT = -1
        }
    }

    open class MarginLayoutParams : LayoutParams {
        var leftMargin: Int = 0
        var topMargin: Int = 0
        var rightMargin: Int = 0
        var bottomMargin: Int = 0
        var marginStart: Int = 0
        var marginEnd: Int = 0
        constructor(width: Int, height: Int) : super(width, height)
        constructor(source: MarginLayoutParams) : super(source)
        fun setMargins(left: Int, top: Int, right: Int, bottom: Int) {}
    }
}

open class SurfaceView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    open val holder: Any get() = Any()
}

open class TextureView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
}

open class Choreographer {
    open fun postFrameCallback(callback: FrameCallback) {}
    open fun postFrameCallbackDelayed(callback: FrameCallback, delayMillis: Long) {}
    open fun removeFrameCallback(callback: FrameCallback) {}
    interface FrameCallback { fun doFrame(frameTimeNanos: Long) }
    companion object { fun getInstance(): Choreographer = Choreographer() }
}

open class Window {
    open var decorView: View? = null
    open val attributes: WindowManager.LayoutParams get() = WindowManager.LayoutParams()
    open fun addFlags(flags: Int) {}
    open fun clearFlags(flags: Int) {}
    open fun setSoftInputMode(mode: Int) {}
    open fun setStatusBarColor(color: Int) {}
    open fun setNavigationBarColor(color: Int) {}
    open fun setBackgroundDrawable(drawable: Drawable?) {}
    open fun takeSurface(surfaceHolderCallback: Any?) {}
    open fun takeInputQueue(callback: Any?) {}
    companion object {
        const val FEATURE_NO_TITLE = 1
        const val FEATURE_ACTION_BAR = 8
        const val FEATURE_PROGRESS = 2
        const val FLAG_FULLSCREEN = 1024
        const val FLAG_KEEP_SCREEN_ON = 128
        const val FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS = 0x80000000.toInt()
        const val FLAG_LAYOUT_NO_LIMITS = 512
    }
}

open class WindowManager {
    @Suppress("DEPRECATION")
    open val defaultDisplay: Display get() = Display()
    open class LayoutParams {
        var width: Int = 0
        var height: Int = 0
        var flags: Int = 0
        var gravity: Int = 0
        companion object {
            const val FLAG_FULLSCREEN = 1024
            const val FLAG_KEEP_SCREEN_ON = 128
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }
    companion object {
        const val FIRST_SYSTEM_WINDOW = 2000
        const val LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES = 1
    }
}

class Display {
    fun getMetrics(outMetrics: android.util.DisplayMetrics) {}
    fun getRealMetrics(outMetrics: android.util.DisplayMetrics) {}
    val rotation: Int get() = 0
    val width: Int get() = 0
    val height: Int get() = 0
    companion object {
        const val ROTATION_0 = 0
        const val ROTATION_90 = 1
        const val ROTATION_180 = 2
        const val ROTATION_270 = 3
    }
}

object Gravity {
    const val NO_GRAVITY = 0
    const val CENTER = 17
    const val CENTER_HORIZONTAL = 1
    const val CENTER_VERTICAL = 16
    const val LEFT = 3
    const val RIGHT = 5
    const val TOP = 48
    const val BOTTOM = 80
    const val START = 0x00800003
    const val END = 0x00800005
    const val FILL = 119
    const val FILL_HORIZONTAL = 7
    const val FILL_VERTICAL = 112
    const val CLIP_HORIZONTAL = 8
    const val CLIP_VERTICAL = 128
}

class ViewConfiguration {
    companion object {
        fun get(context: Context): ViewConfiguration = ViewConfiguration()
        fun getLongPressTimeout(): Int = 500
        fun getTapTimeout(): Int = 100
        fun getScaledTouchSlop(): Int = 8
        fun getScaledMinimumScalingSpan(context: Context): Int = 27
    }
    val scaledTouchSlop: Int get() = 8
    val longPressTimeout: Int get() = 500
}

class LayoutInflater {
    companion object { fun from(context: Context): LayoutInflater = LayoutInflater() }
}

class OrientationEventListener {
    constructor(context: Context)
    constructor(context: Context, rate: Int)
    open fun enable() {}
    open fun disable() {}
    fun canDetectOrientation(): Boolean = false
    companion object { const val SENSOR_DELAY_NORMAL = 3 }
}

open class GestureDetector {
    constructor(context: Context, listener: OnGestureListener)
    constructor(context: Context, listener: OnGestureListener, handler: android.os.Handler?)
    fun onTouchEvent(ev: MotionEvent): Boolean = false
    fun setOnDoubleTapListener(listener: OnDoubleTapListener?) {}
    fun setIsLongpressEnabled(enabled: Boolean) {}
    interface OnGestureListener {
        fun onDown(e: MotionEvent): Boolean
        fun onShowPress(e: MotionEvent)
        fun onSingleTapUp(e: MotionEvent): Boolean
        fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean
        fun onLongPress(e: MotionEvent)
        fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean
    }
    interface OnDoubleTapListener {
        fun onSingleTapConfirmed(e: MotionEvent): Boolean
        fun onDoubleTap(e: MotionEvent): Boolean
        fun onDoubleTapEvent(e: MotionEvent): Boolean
    }
    open class SimpleOnGestureListener : OnGestureListener {
        override fun onDown(e: MotionEvent): Boolean = false
        override fun onShowPress(e: MotionEvent) {}
        override fun onSingleTapUp(e: MotionEvent): Boolean = false
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean = false
        override fun onLongPress(e: MotionEvent) {}
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean = false
    }
}

open class VelocityTracker {
    companion object { fun obtain(): VelocityTracker = VelocityTracker() }
    fun addMovement(event: MotionEvent) {}
    fun computeCurrentVelocity(units: Int) {}
    fun getXVelocity(id: Int): Float = 0f
    fun getYVelocity(id: Int): Float = 0f
    fun recycle() {}
}
