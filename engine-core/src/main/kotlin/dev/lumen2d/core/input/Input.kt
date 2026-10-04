/**
 * Lumen2D — input system.
 *
 * Mobile-first: multi-touch, gestures (tap, double-tap, long-press, drag, pinch, swipe) and
 * on-screen controls, while still supporting keyboard/gamepad on desktop for testing.
 * Games read *actions* ("move_left", "jump", "fire") rather than raw keys, so the same
 * project plays identically on a phone, in the editor's play mode and in CI tests.
 */
package dev.lumen2d.core.input

import dev.lumen2d.core.math.MathUtil
import dev.lumen2d.core.math.Vec2

enum class InputSource { TOUCH, MOUSE, KEYBOARD, GAMEPAD, VIRTUAL }

enum class KeyCode {
    NONE, LEFT, RIGHT, UP, DOWN, SPACE, ENTER, ESCAPE, TAB, SHIFT, CTRL, ALT, BACKSPACE,
    KEY_A, KEY_B, KEY_C, KEY_D, KEY_E, KEY_F, KEY_G, KEY_H, KEY_I, KEY_J, KEY_K, KEY_L, KEY_M,
    KEY_N, KEY_O, KEY_P, KEY_Q, KEY_R, KEY_S, KEY_T, KEY_U, KEY_V, KEY_W, KEY_X, KEY_Y, KEY_Z,
    DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4, DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9,
    F1, F2, F3, F4, F5;

    companion object {
        /** Maps an Android keycode (android.view.KeyEvent) to an engine [KeyCode]. */
        fun fromAndroidKeyCode(code: Int): KeyCode = when (code) {
            21 -> LEFT; 22 -> RIGHT; 19 -> UP; 20 -> DOWN
            62 -> SPACE; 66 -> ENTER; 111 -> ESCAPE; 61 -> TAB; 59 -> SHIFT; 113 -> CTRL; 57 -> ALT
            67 -> BACKSPACE
            29 -> KEY_A; 30 -> KEY_B; 31 -> KEY_C; 32 -> KEY_D; 33 -> KEY_E; 34 -> KEY_F; 35 -> KEY_G
            36 -> KEY_H; 37 -> KEY_I; 38 -> KEY_J; 39 -> KEY_K; 40 -> KEY_L; 41 -> KEY_M; 42 -> KEY_N
            43 -> KEY_O; 44 -> KEY_P; 45 -> KEY_Q; 46 -> KEY_R; 47 -> KEY_S; 48 -> KEY_T; 49 -> KEY_U
            50 -> KEY_V; 51 -> KEY_W; 52 -> KEY_X; 53 -> KEY_Y; 54 -> KEY_Z
            7 -> DIGIT_0; 8 -> DIGIT_1; 9 -> DIGIT_2; 10 -> DIGIT_3; 11 -> DIGIT_4
            12 -> DIGIT_5; 13 -> DIGIT_6; 14 -> DIGIT_7; 15 -> DIGIT_8; 16 -> DIGIT_9
            131 -> F1; 132 -> F2; 133 -> F3; 134 -> F4; 135 -> F5
            else -> NONE
        }
    }
}

/** One binding: an action can be triggered by several sources (key, touch region, gamepad). */
data class InputBinding(
    val action: String,
    val source: InputSource,
    val key: KeyCode = KeyCode.NONE,
    /** Virtual on-screen button region in screen coordinates (mobile). */
    val buttonRect: dev.lumen2d.core.math.Rect? = null,
    val deadZone: Float = 0.2f,
)

/** A pointer (finger/mouse) tracked by the input system. */
class Pointer(
    val id: Int,
    var position: Vec2,
    var startPosition: Vec2 = position,
    var isDown: Boolean = true,
    var isVirtual: Boolean = false,
    val source: InputSource = InputSource.TOUCH,
) {
    var pressure: Float = 1f
    var previousPosition: Vec2 = position
    val delta: Vec2 get() = position - previousPosition
    val totalDelta: Vec2 get() = position - startPosition
    var heldSeconds: Float = 0f
    var consumed: Boolean = false
    /** Internal gesture state. */
    var wasDragging: Boolean = false

    internal fun reset(p: Vec2, down: Boolean, src: InputSource) {
        position = p; startPosition = p; previousPosition = p; isDown = down; consumed = false
    }
}

/** Gesture events emitted by the input system (consumed by UI and by gameplay scripts). */
sealed class GestureEvent {
    data class Tap(val position: Vec2, val pointerId: Int) : GestureEvent()
    data class DoubleTap(val position: Vec2, val pointerId: Int) : GestureEvent()
    data class LongPress(val position: Vec2, val pointerId: Int) : GestureEvent()
    data class DragStart(val position: Vec2, val pointerId: Int) : GestureEvent()
    data class Drag(val position: Vec2, val delta: Vec2, val pointerId: Int) : GestureEvent()
    data class DragEnd(val position: Vec2, val totalDelta: Vec2, val pointerId: Int) : GestureEvent()
    data class Swipe(val direction: Vec2, val position: Vec2, val pointerId: Int) : GestureEvent()
    data class Pinch(val scaleDelta: Float, val center: Vec2, val fingers: Int) : GestureEvent()
    data class Scroll(val delta: Float, val position: Vec2) : GestureEvent()
    data class KeyDown(val key: KeyCode) : GestureEvent()
    data class KeyUp(val key: KeyCode) : GestureEvent()
}

/**
 * Central input state. The platform backend feeds events; the game reads actions/axes.
 */
class InputState {
    /** Screen size (used to normalise pointer coordinates and place virtual controls). */
    var screenWidth: Float = 480f
    var screenHeight: Float = 270f
    /** Set by the editor/runtime: when true, world-space taps are dispatched to the UI layer. */
    var uiConsumesTaps: Boolean = false

    private val downKeys = LinkedHashSet<KeyCode>()
    private val justPressedKeys = LinkedHashSet<KeyCode>()
    private val justReleasedKeys = LinkedHashSet<KeyCode>()
    private val actionStates = HashMap<String, Float>()      // 0..1 strength
    private val actionJustPressed = HashSet<String>()
    private val actionJustReleased = HashSet<String>()

    private val bindings = ArrayList<InputBinding>()
    private val pointers = LinkedHashMap<Int, Pointer>()
    private val pointerPool = ArrayList<Pointer>(8)
    private val gestureListeners = ArrayList<(GestureEvent) -> Unit>(8)

    /** Mouse position (desktop) and mouse buttons. */
    var mousePosition: Vec2 = Vec2.ZERO
        private set
    private val mouseButtons = HashSet<Int>()

    /** Analogue axes provided by virtual controls or gamepads. */
    private val axisValues = HashMap<String, Vec2>()

    private var lastTapTime = -10f
    private var lastTapPosition = Vec2.ZERO
    private var clock: Float = 0f

    /** Raw event queue drained each frame (scripts and editor tools can inspect it). */
    val events = ArrayList<GestureEvent>(16)

    val pointerCount: Int get() = pointers.size

    // -------------------------------------------------------------------- bindings

    fun bind(action: String, key: KeyCode): InputState {
        bindings.add(InputBinding(action, InputSource.KEYBOARD, key = key)); return this
    }

    fun bindTouch(action: String, rect: dev.lumen2d.core.math.Rect): InputState {
        bindings.add(InputBinding(action, InputSource.VIRTUAL, buttonRect = rect)); return this
    }

    fun bindingsFor(action: String): List<InputBinding> = bindings.filter { it.action == action }
    fun allBindings(): List<InputBinding> = bindings.toList()
    fun clearBindings() { bindings.clear() }

    /** Replaces the whole action map (project settings -> runtime). */
    fun setBindings(newBindings: List<InputBinding>) {
        bindings.clear(); bindings.addAll(newBindings)
    }

    /** Registers an on-screen analogue axis (virtual joystick). */
    fun setAxis(axis: String, x: Float, y: Float) { axisValues[axis] = Vec2(x, y) }

    fun axis(axis: String): Vec2 = axisValues[axis] ?: Vec2.ZERO

    // ---------------------------------------------------------------------- polling

    fun isActionPressed(action: String): Boolean = (actionStates[action] ?: 0f) > 0.5f
    fun isActionJustPressed(action: String): Boolean = action in actionJustPressed
    fun isActionJustReleased(action: String): Boolean = action in actionJustReleased
    fun actionStrength(action: String): Float = actionStates[action] ?: 0f

    fun isKeyDown(key: KeyCode): Boolean = key in downKeys
    fun isKeyJustPressed(key: KeyCode): Boolean = key in justPressedKeys
    fun isKeyJustReleased(key: KeyCode): Boolean = key in justReleasedKeys

    /** Mouse buttons (0 = left, 1 = right, 2 = middle). */
    fun isMouseButtonDown(button: Int): Boolean = button in mouseButtons
    val isMouseDown: Boolean get() = mouseButtons.isNotEmpty() || pointers.values.any { it.isDown && it.source == InputSource.MOUSE }

    val primaryPointer: Pointer? get() = pointers.values.firstOrNull()

    /** All currently active pointers (multi-touch). */
    fun activePointers(): List<Pointer> = pointers.values.filter { it.isDown }

    fun pointersIn(rect: dev.lumen2d.core.math.Rect): List<Pointer> = pointers.values.filter { rect.contains(it.position) }

    // ----------------------------------------------------------------- raw events

    fun onKeyDown(key: KeyCode) {
        if (key == KeyCode.NONE) return
        if (downKeys.add(key)) justPressedKeys.add(key)
        chooseAxisFromKeys()
        events.add(GestureEvent.KeyDown(key))
    }

    fun onKeyUp(key: KeyCode) {
        downKeys.remove(key)
        justReleasedKeys.add(key)
        chooseAxisFromKeys()
        events.add(GestureEvent.KeyUp(key))
    }

    fun onPointerDown(id: Int, x: Float, y: Float, source: InputSource = InputSource.TOUCH, pressure: Float = 1f) {
        val pointer = pointers.getOrPut(id) { pointerPool.removeLastOrNull() ?: Pointer(id, Vec2(x, y)) }
        pointer.reset(Vec2(x, y), down = true, src = source)
        pointer.pressure = pressure
        if (source == InputSource.MOUSE) mousePosition = Vec2(x, y)
    }

    fun onPointerMove(id: Int, x: Float, y: Float, pressure: Float = 1f) {
        val pointer = pointers[id] ?: return
        pointer.previousPosition = pointer.position
        pointer.position = Vec2(x, y)
        pointer.pressure = pressure
        if (pointer.source == InputSource.MOUSE) {
            mousePosition = Vec2(x, y)
            events.add(GestureEvent.Drag(Vec2(x, y), pointer.delta, id))
        }
        // Emit drag gestures for touch.
        if (pointer.isDown && pointer.totalDelta.length > 6f) {
            val alreadyDragging = pointer.wasDragging
            if (!alreadyDragging) { pointer.wasDragging = true; events.add(GestureEvent.DragStart(pointer.startPosition, id)) }
            events.add(GestureEvent.Drag(pointer.position, pointer.delta, id))
        }
    }

    fun onPointerUp(id: Int) {
        val pointer = pointers[id] ?: return
        val position = pointer.position
        val held = pointer.heldSeconds
        val total = pointer.totalDelta
        pointer.isDown = false
        if (pointer.wasDragging) events.add(GestureEvent.DragEnd(position, total, id))
        else {
            if (total.length < 24f && held > 0.65f) events.add(GestureEvent.LongPress(position, id))
            else if (total.length < 24f) {
                if (clock - lastTapTime < 0.32f && position.distanceTo(lastTapPosition) < 40f) {
                    events.add(GestureEvent.DoubleTap(position, id))
                    lastTapTime = -10f
                } else {
                    events.add(GestureEvent.Tap(position, id))
                    lastTapTime = clock
                    lastTapPosition = position
                }
            } else if (total.length > 60f && held < 0.5f) {
                events.add(GestureEvent.Swipe(total.normalized(), position, id))
            }
        }
        pointers.remove(id)
        pointer.wasDragging = false
        pointerPool.add(pointer)
    }

    fun onMouseButton(button: Int, down: Boolean, x: Float, y: Float) {
        mousePosition = Vec2(x, y)
        if (down) {
            mouseButtons.add(button)
            onPointerDown(-1 - button, x, y, InputSource.MOUSE)
        } else {
            mouseButtons.remove(button)
            onPointerUp(-1 - button)
        }
    }

    fun onScroll(delta: Float) { events.add(GestureEvent.Scroll(delta, mousePosition)) }

    /** Emits a pinch gesture using two active pointers (called by the backend each frame). */
    fun emitPinch(scaleDelta: Float, center: Vec2, fingers: Int) {
        events.add(GestureEvent.Pinch(scaleDelta, center, fingers))
    }

    fun addGestureListener(listener: (GestureEvent) -> Unit) { gestureListeners.add(listener) }
    fun removeGestureListener(listener: (GestureEvent) -> Unit) { gestureListeners.remove(listener) }

    // ------------------------------------------------------------------ frame tick

    /** Called at the beginning of each frame by [dev.lumen2d.core.scene.SceneTree]. */
    fun beginFrame(delta: Float) {
        clock += delta
        justPressedKeys.clear(); justReleasedKeys.clear()
        actionJustPressed.clear(); actionJustReleased.clear()
        events.clear()
        for (pointer in pointers.values) {
            if (pointer.isDown) {
                pointer.heldSeconds += delta
                pointer.previousPosition = pointer.position
            }
        }
    }

    /** Called by the UI/gameplay layer once it consumed a pointer this frame. */
    fun consumePointer(id: Int) { pointers[id]?.consumed = true }

    fun isPointerConsumed(id: Int): Boolean = pointers[id]?.consumed == true

    /** Recomputes action strengths from keys, virtual controls and axes; dispatch gestures. */
    internal fun endFrame() {
        val previous = HashMap(actionStates)
        actionStates.clear()
        // Keyboard bindings.
        for (binding in bindings) {
            if (binding.source == InputSource.KEYBOARD && binding.key != KeyCode.NONE) {
                if (binding.key in downKeys) actionStates[binding.action] = 1f
            }
            if (binding.source == InputSource.VIRTUAL && binding.buttonRect != null) {
                val rect = binding.buttonRect!!
                val pressed = pointersIn(rect).any { it.isDown && !it.consumed }
                if (pressed) { actionStates[binding.action] = maxOf(actionStates[binding.action] ?: 0f, 1f); }
            }
        }
        // Virtual axes map into action strengths when named identically.
        for ((axis, value) in axisValues) {
            if (value.lengthSquared > 0.0001f) actionStates[axis] = value.length.coerceAtMost(1f)
        }
        for ((action, strength) in actionStates) {
            val was = previous[action] ?: 0f
            if (strength > 0.5f && was <= 0.5f) actionJustPressed.add(action)
            if (strength <= 0.5f && was > 0.5f) actionJustReleased.add(action)
        }
        for (action in previous.keys) {
            if (action !in actionStates) actionJustReleased.add(action)
        }
        // Dispatch gestures.
        if (events.isNotEmpty() && gestureListeners.isNotEmpty()) {
            val snapshot = events.toList()
            for (event in snapshot) for (listener in gestureListeners.toList()) listener(event)
        }
    }

    private fun chooseAxisFromKeys() {
        axisValues.remove("move")
        var x = 0f; var y = 0f
        if (KeyCode.LEFT in downKeys) x -= 1f
        if (KeyCode.RIGHT in downKeys) x += 1f
        if (KeyCode.UP in downKeys) y -= 1f
        if (KeyCode.DOWN in downKeys) y += 1f
        if (x != 0f || y != 0f) axisValues["move"] = Vec2(x, y)
    }

    /** Combined movement vector from the "move_*" actions plus the "move" axis. */
    fun movementVector(deadZone: Float = 0.15f): Vec2 {
        val axis = axis("move")
        var x = axis.x
        var y = axis.y
        if (isActionPressed("move_left")) x -= 1f
        if (isActionPressed("move_right")) x += 1f
        if (isActionPressed("move_up")) y -= 1f
        if (isActionPressed("move_down")) y += 1f
        val v = Vec2(x, y)
        return if (v.length < deadZone) Vec2.ZERO else v.limit(1f)
    }

    fun reset() {
        downKeys.clear(); justPressedKeys.clear(); justReleasedKeys.clear()
        actionStates.clear(); actionJustPressed.clear(); actionJustReleased.clear()
        pointers.clear(); mouseButtons.clear(); axisValues.clear(); events.clear()
    }

    /** Rebuilds virtual button rectangles when the screen size or orientation changes. */
    fun onViewportChanged(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
    }

    /** Convenience for UI code: normalised pointer position (0..1). */
    fun normalizedPosition(pointer: Pointer): Vec2 =
        Vec2(MathUtil.clamp01(pointer.position.x / screenWidth), MathUtil.clamp01(pointer.position.y / screenHeight))
}
