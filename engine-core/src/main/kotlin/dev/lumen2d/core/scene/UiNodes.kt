/**
 * Lumen2D — Control/UI node library.
 *
 * A compact immediate-layout UI toolkit aimed at game HUDs, menus and mobile touch controls.
 * Controls live under a [CanvasLayer], lay themselves out from anchors + offsets, and receive
 * pointer events from the tree (topmost control wins). The editor's overlays (gizmos, rulers,
 * tool hints) are built from the same primitives.
 */
package dev.lumen2d.core.scene

import dev.lumen2d.core.input.GestureEvent
import dev.lumen2d.core.input.Pointer
import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.MathUtil
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.render.Renderer
import dev.lumen2d.core.render.TextAlign
import dev.lumen2d.core.render.TextEffect
import dev.lumen2d.core.render.TextVAlign
import dev.lumen2d.core.util.Event
import dev.lumen2d.core.util.Signal

/**
 * Base UI node.
 *
 * Anchors are 0..1 ratios inside the parent rect; offsets are pixel paddings added on top
 * (Godot's model). `anchors = (0,0,1,1)` with zero offsets fills the parent — the common case
 * for full-screen HUDs.
 */
open class ControlNode(name: String = "Control") : Node2D(name) {
    var anchorLeft: Float = 0f
    var anchorTop: Float = 0f
    var anchorRight: Float = 0f
    var anchorBottom: Float = 0f
    var offsetLeft: Float = 0f
    var offsetTop: Float = 0f
    var offsetRight: Float = 0f
    var offsetBottom: Float = 0f
    var minSize: Vec2 = Vec2.ZERO
    /** Alignment inside the parent rect when anchors are equal (left/top/right/bottom). */
    var growHorizontal: Int = 0     // -1 left, 0 both, 1 right
    var growVertical: Int = 0
    var enabled: Boolean = true
    var mouseFilter: Boolean = true
    var clipContents: Boolean = false
    var backgroundColor: Color = Color.TRANSPARENT
    var borderColor: Color = Color.TRANSPARENT
    var borderWidth: Float = 0f
    var cornerRadius: Float = 0f
    var opacity: Float = 1f
    var visibleUi: Boolean = true
    var tooltip: String = ""
    var themeAccent: Color = Color.fromHex("#6C8CFF")
    var themeText: Color = Color.fromHex("#E8ECFF")
    var fontId: String = ""
    var fontSize: Float = 1f

    /** Rect in screen space, recomputed by [layout]. */
    var rect: Rect = Rect.ZERO
    var parentRect: Rect = Rect.ZERO

    val pressed = Event()
    val released = Event()
    val hovered = Event()
    val focusGained = Event()
    val focusLost = Event()
    val resized = Event()

    var isHovered: Boolean = false
        protected set
    var isPressed: Boolean = false
        protected set
    var hasFocus: Boolean = false
        protected set

    private var layoutDirty = true

    override fun onReady() {
        addToGroup("ui")
        layoutDirty = true
        layout(parentRect())
    }

    override fun onProcess(delta: Float) { if (layoutDirty) { layout(parentRect()); layoutDirty = false } }

    /** Mark the layout dirty (call after changing anchors/offsets/children). */
    fun invalidateLayout() {
        layoutDirty = true
        for (child in children) (child as? ControlNode)?.invalidateLayout()
    }

    /** The rect this control lays out inside. */
    protected fun parentRect(): Rect {
        val p = parent as? ControlNode
        return if (p != null) p.rect
        else {
            val r = tree?.renderer
            if (r != null) Rect(0f, 0f, r.width.toFloat(), r.height.toFloat()) else Rect(0f, 0f, 480f, 270f)
        }
    }

    /** Computes [rect] from anchors/offsets inside [parent]. */
    open fun layout(parent: Rect) {
        parentRect = parent
        val left = parent.x + parent.w * anchorLeft + offsetLeft
        val top = parent.y + parent.h * anchorTop + offsetTop
        val right = parent.x + parent.w * anchorRight + offsetRight
        val bottom = parent.y + parent.h * anchorBottom + offsetBottom
        var w = right - left
        var h = bottom - top
        if (anchorLeft == anchorRight && growHorizontal != 0) {
            w = minSize.x
            val anchorX = parent.x + parent.w * anchorLeft
            val x = when (growHorizontal) {
                -1 -> anchorX
                1 -> anchorX - w
                else -> anchorX - w / 2f
            }
            rect = Rect(x, top, w.coerceAtLeast(minSize.x), h.coerceAtLeast(minSize.y))
        } else {
            rect = Rect(left, top, w.coerceAtLeast(minSize.x), h.coerceAtLeast(minSize.y))
        }
        if (anchorTop == anchorBottom && growVertical != 0) {
            val anchorY = parent.y + parent.h * anchorTop
            val y = when (growVertical) {
                -1 -> anchorY
                1 -> anchorY - rect.h
                else -> anchorY - rect.h / 2f
            }
            rect = Rect(rect.x, y, rect.w, rect.h)
        }
        for (child in children) (child as? ControlNode)?.layout(rect)
        resized.emit()
    }

    /** Centre of the control in screen space. */
    val center: Vec2 get() = rect.center

    override fun localBounds() = rect

    override fun onDraw(renderer: Renderer, alpha: Float) {
        if (!visibleUi) return
        if (backgroundColor.a > 0.001f) {
            renderer.drawRectScreen(rect, backgroundColor.withAlpha(backgroundColor.a * opacity * effectiveModulate().a), filled = true)
        }
        if (borderWidth > 0f && borderColor.a > 0.001f) {
            renderer.drawRectScreen(rect, borderColor.withAlpha(borderColor.a * opacity), filled = false, lineWidth = borderWidth)
        }
        drawContent(renderer, alpha)
        if (clipContents) renderer.pushClipScreen(rect)
    }

    /** Overridden by widgets to paint themselves (text, bars, icons). */
    protected open fun drawContent(renderer: Renderer, alpha: Float) {}

    // -------------------------------------------------------------------- input

    /** Called by the tree with pointers inside this control; returns true when consumed. */
    open fun handlePointer(pointer: Pointer, isDown: Boolean): Boolean {
        if (!enabled || !visibleUi || !mouseFilter) return false
        if (!rect.grown(4f).contains(pointer.position)) return false
        if (pointer.consumed) return false

        if (isDown) {
            isPressed = true
            onPress(pointer)
            pressed.emit()
            isHovered = true
        } else {
            if (isHovered) hovered.emit()
            if (isPressed) { isPressed = false; onRelease(pointer); released.emit() }
        }
        isHovered = true
        return true
    }

    /** Called when a pointer is not over the control anymore. */
    open fun pointerLeft() { isHovered = false }

    protected open fun onPress(pointer: Pointer) {}
    protected open fun onRelease(pointer: Pointer) {}

    fun grabFocus() {
        if (hasFocus) return
        hasFocus = true
        focusGained.emit()
    }

    fun releaseFocus() {
        if (!hasFocus) return
        hasFocus = false
        focusLost.emit()
    }

    // ------------------------------------------------------------------ helpers

    protected fun uiFont(renderer: Renderer) = tree?.resources?.font(fontId)

    protected fun textColor() = themeText * effectiveModulate()

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("anchorLeft", PropertyType.FLOAT, "Anchor L", 0f, min = 0f, max = 1f, step = 0.01f, category = "Layout"),
        PropertyDef("anchorTop", PropertyType.FLOAT, "Anchor T", 0f, min = 0f, max = 1f, step = 0.01f, category = "Layout"),
        PropertyDef("anchorRight", PropertyType.FLOAT, "Anchor R", 0f, min = 0f, max = 1f, step = 0.01f, category = "Layout"),
        PropertyDef("anchorBottom", PropertyType.FLOAT, "Anchor B", 0f, min = 0f, max = 1f, step = 0.01f, category = "Layout"),
        PropertyDef("offsetLeft", PropertyType.FLOAT, "Offset L", 0f, min = -4096f, max = 4096f, category = "Layout"),
        PropertyDef("offsetTop", PropertyType.FLOAT, "Offset T", 0f, min = -4096f, max = 4096f, category = "Layout"),
        PropertyDef("offsetRight", PropertyType.FLOAT, "Offset R", 0f, min = -4096f, max = 4096f, category = "Layout"),
        PropertyDef("offsetBottom", PropertyType.FLOAT, "Offset B", 0f, min = -4096f, max = 4096f, category = "Layout"),
        PropertyDef("minSize", PropertyType.VECTOR2, "Min size", Vec2.ZERO, category = "Layout"),
        PropertyDef("growHorizontal", PropertyType.INT, "Grow H", 0, min = -1f, max = 1f, step = 1f, category = "Layout"),
        PropertyDef("growVertical", PropertyType.INT, "Grow V", 0, min = -1f, max = 1f, step = 1f, category = "Layout"),
        PropertyDef("backgroundColor", PropertyType.COLOR, "Background", Color.TRANSPARENT, category = "Style"),
        PropertyDef("borderColor", PropertyType.COLOR, "Border", Color.TRANSPARENT, category = "Style"),
        PropertyDef("borderWidth", PropertyType.FLOAT, "Border width", 0f, min = 0f, max = 32f, category = "Style"),
        PropertyDef("opacity", PropertyType.FLOAT, "Opacity", 1f, min = 0f, max = 1f, step = 0.05f, category = "Style"),
        PropertyDef("fontId", PropertyType.FONT, "Font", "", category = "Style"),
        PropertyDef("fontSize", PropertyType.FLOAT, "Font size", 1f, min = 0.2f, max = 8f, step = 0.05f, category = "Style"),
        PropertyDef("visibleUi", PropertyType.BOOL, "Visible", true, category = "Style"),
        PropertyDef("enabled", PropertyType.BOOL, "Enabled", true, category = "Behaviour"),
        PropertyDef("tooltip", PropertyType.STRING, "Tooltip", "", category = "Behaviour"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "anchorLeft" -> anchorLeft; "anchorTop" -> anchorTop
        "anchorRight" -> anchorRight; "anchorBottom" -> anchorBottom
        "offsetLeft" -> offsetLeft; "offsetTop" -> offsetTop
        "offsetRight" -> offsetRight; "offsetBottom" -> offsetBottom
        "minSize" -> minSize
        "growHorizontal" -> growHorizontal; "growVertical" -> growVertical
        "backgroundColor" -> backgroundColor
        "borderColor" -> borderColor
        "borderWidth" -> borderWidth
        "opacity" -> opacity
        "fontId" -> fontId
        "fontSize" -> fontSize
        "visibleUi" -> visibleUi
        "enabled" -> enabled
        "tooltip" -> tooltip
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "anchorLeft" -> { anchorLeft = asFloat(value, 0f); invalidateLayout(); return true }
            "anchorTop" -> { anchorTop = asFloat(value, 0f); invalidateLayout(); return true }
            "anchorRight" -> { anchorRight = asFloat(value, 0f); invalidateLayout(); return true }
            "anchorBottom" -> { anchorBottom = asFloat(value, 0f); invalidateLayout(); return true }
            "offsetLeft" -> { offsetLeft = asFloat(value, 0f); invalidateLayout(); return true }
            "offsetTop" -> { offsetTop = asFloat(value, 0f); invalidateLayout(); return true }
            "offsetRight" -> { offsetRight = asFloat(value, 0f); invalidateLayout(); return true }
            "offsetBottom" -> { offsetBottom = asFloat(value, 0f); invalidateLayout(); return true }
            "minSize" -> { minSize = asVec2(value, Vec2.ZERO); invalidateLayout(); return true }
            "growHorizontal" -> { growHorizontal = asInt(value, 0); invalidateLayout(); return true }
            "growVertical" -> { growVertical = asInt(value, 0); invalidateLayout(); return true }
            "backgroundColor" -> { backgroundColor = asColor(value, Color.TRANSPARENT); return true }
            "borderColor" -> { borderColor = asColor(value, Color.TRANSPARENT); return true }
            "borderWidth" -> { borderWidth = asFloat(value, 0f); return true }
            "opacity" -> { opacity = asFloat(value, 1f); return true }
            "fontId" -> { fontId = value?.toString() ?: ""; return true }
            "fontSize" -> { fontSize = asFloat(value, 1f); return true }
            "visibleUi" -> { visibleUi = asBool(value, true); return true }
            "enabled" -> { enabled = asBool(value, true); return true }
            "tooltip" -> { tooltip = value?.toString() ?: ""; return true }
        }
        return super.setProperty(property, value)
    }
}

/** Flat or nine-patch panel. */
open class PanelNode(name: String = "Panel") : ControlNode(name) {
    var textureId: String = ""
    var insets: Rect = Rect(6f, 6f, 6f, 6f)

    override fun onDraw(renderer: Renderer, alpha: Float) {
        if (textureId.isNotEmpty()) {
            val region = tree?.resources?.texture(textureId)?.whole
            if (region != null) {
                renderer.drawNinePatch(region, rect, insets, Color.WHITE.withAlpha(opacity * effectiveModulate().a))
                drawContent(renderer, alpha)
                return
            }
        }
        super.onDraw(renderer, alpha)
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("textureId", PropertyType.TEXTURE, "Panel texture", "", category = "Panel", hint = "asset:texture"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = if (property == "textureId") textureId else super.getProperty(property)
    override fun setProperty(property: String, value: Any?): Boolean {
        if (property == "textureId") { textureId = value?.toString() ?: ""; return true }
        return super.setProperty(property, value)
    }
}

/** Text label with alignment, wrapping and effects. */
open class LabelControl(name: String = "Label") : ControlNode(name) {
    var text: String = "Label"
    var align: TextAlign = TextAlign.LEFT
    var vAlign: TextVAlign = TextVAlign.TOP
    var wrap: Boolean = true
    var shadow: Boolean = false
    var outline: Boolean = false
    var textColor: Color = Color.fromHex("#E8ECFF")
    var letterSpacing: Float = 0f
    /** When set, text is rendered through a format callback (used by HUDs). */
    var format: ((String) -> String)? = null

    override fun drawContent(renderer: Renderer, alpha: Float) {
        val font = uiFont(renderer) ?: return
        font.letterSpacing = letterSpacing
        val content = format?.invoke(text) ?: text
        val effect = (if (shadow) TextEffect.SHADOW else 0) or (if (outline) TextEffect.OUTLINE else 0)
        val x = when (align) {
            TextAlign.LEFT -> rect.x + 4f
            TextAlign.CENTER -> rect.centerX
            TextAlign.RIGHT -> rect.right - 4f
        }
        renderer.drawTextScreen(font, content, x, rect.centerY, fontSize, textColor * effectiveModulate(),
            align, TextVAlign.MIDDLE, if (wrap) rect.w - 8f else 0f, effect)
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("text", PropertyType.MULTILINE_STRING, "Text", "Label", category = "Text"),
        PropertyDef("align", PropertyType.ENUM, "Align", "LEFT", category = "Text",
            enumValues = TextAlign.entries.map { it.name }),
        PropertyDef("wrap", PropertyType.BOOL, "Wrap", true, category = "Text"),
        PropertyDef("shadow", PropertyType.BOOL, "Shadow", false, category = "Text"),
        PropertyDef("outline", PropertyType.BOOL, "Outline", false, category = "Text"),
        PropertyDef("textColor", PropertyType.COLOR, "Text color", Color.fromHex("#E8ECFF"), category = "Text"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "text" -> text
        "align" -> align.name
        "wrap" -> wrap
        "shadow" -> shadow
        "outline" -> outline
        "textColor" -> textColor
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "text" -> { text = value?.toString() ?: ""; return true }
            "align" -> { align = runCatching { TextAlign.valueOf(value.toString()) }.getOrDefault(align); return true }
            "wrap" -> { wrap = asBool(value, true); return true }
            "shadow" -> { shadow = asBool(value, false); return true }
            "outline" -> { outline = asBool(value, false); return true }
            "textColor" -> { textColor = asColor(value, textColor); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Touch/mouse button with hover/press/disabled states. */
open class ButtonControl(name: String = "Button") : ControlNode(name) {
    var text: String = "Button"
    var normalColor: Color = Color.fromHex("#2A3252")
    var hoverColor: Color = Color.fromHex("#38426B")
    var pressedColor: Color = Color.fromHex("#4C5A94")
    var disabledColor: Color = Color.fromHex("#1B2033")
    var textColorNormal: Color = Color.fromHex("#E8ECFF")
    var accentColor: Color = Color.fromHex("#6C8CFF")
    var ninePatchTexture: String = ""
    var iconTexture: String = ""
    var iconSize: Float = 18f
    var toggleMode: Boolean = false
    var toggled: Boolean = false
    var repeatOnHold: Boolean = false
    var holdRepeatInterval: Float = 0.18f

    val clicked = Event()
    val toggledOn = Event()
    val toggledOff = Event()
    val held = Event()

    private var holdTimer = 0f
    private var wasPressed = false

    override fun onRelease(pointer: Pointer) {
        if (!enabled) return
        if (toggleMode) {
            toggled = !toggled
            if (toggled) toggledOn.emit() else toggledOff.emit()
        }
        clicked.emit()
    }

    override fun onProcess(delta: Float) {
        super.onProcess(delta)
        if (repeatOnHold && isPressed) {
            holdTimer += delta
            if (holdTimer >= holdRepeatInterval) { holdTimer = 0f; held.emit(); clicked.emit() }
        } else holdTimer = 0f
        wasPressed = isPressed
    }

    override fun drawContent(renderer: Renderer, alpha: Float) {
        val base = when {
            !enabled -> disabledColor
            isPressed -> pressedColor
            isHovered -> hoverColor
            else -> normalColor
        }
        val ninePatch = if (ninePatchTexture.isNotEmpty()) tree?.resources?.texture(ninePatchTexture) else null
        if (ninePatch != null) {
            renderer.drawNinePatch(ninePatch.whole, rect, Rect(6f, 6f, 6f, 6f), Color.WHITE.withAlpha(opacity))
        } else {
            renderer.drawRectScreen(rect, base.withAlpha(0.95f * opacity), true)
            renderer.drawRectScreen(rect, accentColor.withAlpha(if (toggled) 0.9f else 0.35f), false, 1.5f)
        }
        if (iconTexture.isNotEmpty()) {
            val icon = tree?.resources?.texture(iconTexture)
            if (icon != null) {
                val size = iconSize
                val ix = rect.centerX - size / 2f
                val iy = rect.centerY - size / 2f - (if (text.isNotEmpty()) 6f else 0f)
                // Icons are screen-space; use a transformed draw through a temporary camera reset.
                drawScreenSprite(renderer, icon.whole, Rect(ix, iy, size, size), Color.WHITE)
            }
        }
        val font = uiFont(renderer) ?: return
        renderer.drawTextScreen(font, text, rect.centerX, rect.centerY + (if (iconTexture.isNotEmpty() && text.isNotEmpty()) 6f else 0f),
            fontSize, textColorNormal.withAlpha(if (enabled) 1f else 0.6f),
            TextAlign.CENTER, TextVAlign.MIDDLE, rect.w - 8f, TextEffect.NONE)
    }

    private fun drawScreenSprite(renderer: Renderer, region: dev.lumen2d.core.render.TextureRegion, target: Rect, tint: Color) {
        val camera = renderer.currentCamera()
        val savedPos = camera.position; val savedZoom = camera.zoom; val savedRotation = camera.rotation
        camera.position = Vec2(renderer.width / 2f, renderer.height / 2f)
        camera.zoom = 1f; camera.rotation = 0f
        renderer.drawSprite(region, target.x, target.y, target.w, target.h, 0f, 0f, 0f, tint)
        camera.position = savedPos; camera.zoom = savedZoom; camera.rotation = savedRotation
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("text", PropertyType.STRING, "Text", "Button", category = "Button"),
        PropertyDef("normalColor", PropertyType.COLOR, "Normal", Color.fromHex("#2A3252"), category = "Button"),
        PropertyDef("accentColor", PropertyType.COLOR, "Accent", Color.fromHex("#6C8CFF"), category = "Button"),
        PropertyDef("toggleMode", PropertyType.BOOL, "Toggle mode", false, category = "Button"),
        PropertyDef("toggled", PropertyType.BOOL, "Toggled", false, category = "Button"),
        PropertyDef("ninePatchTexture", PropertyType.TEXTURE, "Nine-patch", "", category = "Button"),
        PropertyDef("iconTexture", PropertyType.TEXTURE, "Icon", "", category = "Button"),
        PropertyDef("iconSize", PropertyType.FLOAT, "Icon size", 18f, min = 4f, max = 256f, category = "Button"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "text" -> text
        "normalColor" -> normalColor
        "accentColor" -> accentColor
        "toggleMode" -> toggleMode
        "toggled" -> toggled
        "ninePatchTexture" -> ninePatchTexture
        "iconTexture" -> iconTexture
        "iconSize" -> iconSize
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "text" -> { text = value?.toString() ?: ""; return true }
            "normalColor" -> { normalColor = asColor(value, normalColor); return true }
            "accentColor" -> { accentColor = asColor(value, accentColor); return true }
            "toggleMode" -> { toggleMode = asBool(value, false); return true }
            "toggled" -> { toggled = asBool(value, false); return true }
            "ninePatchTexture" -> { ninePatchTexture = value?.toString() ?: ""; return true }
            "iconTexture" -> { iconTexture = value?.toString() ?: ""; return true }
            "iconSize" -> { iconSize = asFloat(value, 18f); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Image with stretch/tile modes (icons, logos, minimaps). */
open class TextureRectControl(name: String = "TextureRect") : ControlNode(name) {
    var textureId: String = ""
    var stretch: Boolean = true
    var keepAspect: Boolean = true
    var tintColor: Color = Color.WHITE
    var blend: dev.lumen2d.core.render.BlendMode = dev.lumen2d.core.render.BlendMode.NORMAL

    override fun drawContent(renderer: Renderer, alpha: Float) {
        val texture = tree?.resources?.texture(textureId) ?: return
        var target = rect
        if (keepAspect) {
            val aspect = texture.width.toFloat() / texture.height.toFloat()
            val rectAspect = rect.w / rect.h
            target = if (rectAspect > aspect) {
                val w = rect.h * aspect
                Rect(rect.centerX - w / 2f, rect.y, w, rect.h)
            } else {
                val h = rect.w / aspect
                Rect(rect.x, rect.centerY - h / 2f, rect.w, h)
            }
        }
        val camera = renderer.currentCamera()
        val savedPos = camera.position; val savedZoom = camera.zoom; val savedRotation = camera.rotation
        camera.position = Vec2(renderer.width / 2f, renderer.height / 2f); camera.zoom = 1f; camera.rotation = 0f
        if (stretch) {
            renderer.drawSprite(texture.whole, target.x, target.y, target.w, target.h, 0f, 0f, 0f,
                tintColor * effectiveModulate(), blend = blend)
        } else {
            var y = target.y
            while (y < target.bottom) {
                var x = target.x
                while (x < target.right) {
                    renderer.drawSprite(texture.whole, x, y, texture.width.toFloat(), texture.height.toFloat(), 0f, 0f, 0f,
                        tintColor * effectiveModulate(), blend = blend)
                    x += texture.width
                }
                y += texture.height
            }
        }
        camera.position = savedPos; camera.zoom = savedZoom; camera.rotation = savedRotation
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("textureId", PropertyType.TEXTURE, "Texture", "", category = "Image", hint = "asset:texture"),
        PropertyDef("stretch", PropertyType.BOOL, "Stretch", true, category = "Image"),
        PropertyDef("keepAspect", PropertyType.BOOL, "Keep aspect", true, category = "Image"),
        PropertyDef("tintColor", PropertyType.COLOR, "Tint", Color.WHITE, category = "Image"),
        PropertyDef("blend", PropertyType.ENUM, "Blend", "NORMAL", category = "Image",
            enumValues = dev.lumen2d.core.render.BlendMode.entries.map { it.name }),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "textureId" -> textureId
        "stretch" -> stretch
        "keepAspect" -> keepAspect
        "tintColor" -> tintColor
        "blend" -> blend.name
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "textureId" -> { textureId = value?.toString() ?: ""; return true }
            "stretch" -> { stretch = asBool(value, true); return true }
            "keepAspect" -> { keepAspect = asBool(value, true); return true }
            "tintColor" -> { tintColor = asColor(value, Color.WHITE); return true }
            "blend" -> { blend = runCatching { dev.lumen2d.core.render.BlendMode.valueOf(value.toString()) }.getOrDefault(blend); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Health/XP/loading bar with optional nine-patch skin and smoothed fill. */
open class ProgressBarControl(name: String = "ProgressBar") : ControlNode(name) {
    var value: Float = 0.5f
    var maxValue: Float = 1f
    var fillColor: Color = Color.fromHex("#4ADE80")
    var showPercentage: Boolean = false
    var smoothing: Float = 0f
    var vertical: Boolean = false
    private var displayValue: Float = 0.5f

    init { backgroundColor = Color.fromHex("#1B2033") }

    val ratio: Float get() = if (maxValue <= 0f) 0f else MathUtil.clamp01(value / maxValue)

    fun setRatio(r: Float) { value = MathUtil.clamp01(r) * maxValue }

    override fun onProcess(delta: Float) {
        super.onProcess(delta)
        displayValue = if (smoothing <= 0f) ratio
        else MathUtil.damp(displayValue, ratio, 1f / smoothing, delta)
    }

    override fun drawContent(renderer: Renderer, alpha: Float) {
        renderer.drawRectScreen(rect, backgroundColor.withAlpha(0.9f * opacity), true)
        val r = if (smoothing > 0f) displayValue else ratio
        val fill = if (vertical) Rect(rect.x, rect.bottom - rect.h * r, rect.w, rect.h * r)
        else Rect(rect.x, rect.y, rect.w * r, rect.h)
        renderer.drawRectScreen(fill, fillColor.withAlpha(opacity), true)
        if (showPercentage) {
            val font = uiFont(renderer) ?: return
            renderer.drawTextScreen(font, "${(r * 100).toInt()}%", rect.centerX, rect.centerY, fontSize,
                textColor(), TextAlign.CENTER, TextVAlign.MIDDLE)
        }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("value", PropertyType.FLOAT, "Value", 0.5f, min = 0f, max = 1000f, step = 0.01f, category = "Bar"),
        PropertyDef("maxValue", PropertyType.FLOAT, "Max", 1f, min = 0.001f, max = 100000f, category = "Bar"),
        PropertyDef("fillColor", PropertyType.COLOR, "Fill", Color.fromHex("#4ADE80"), category = "Bar"),
        PropertyDef("backgroundColor", PropertyType.COLOR, "Background", Color.fromHex("#1B2033"), category = "Bar"),
        PropertyDef("showPercentage", PropertyType.BOOL, "Show %", false, category = "Bar"),
        PropertyDef("smoothing", PropertyType.FLOAT, "Smoothing", 0f, min = 0f, max = 1f, step = 0.02f, category = "Bar"),
        PropertyDef("vertical", PropertyType.BOOL, "Vertical", false, category = "Bar"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "value" -> value
        "maxValue" -> maxValue
        "fillColor" -> fillColor
        "backgroundColor" -> backgroundColor
        "showPercentage" -> showPercentage
        "smoothing" -> smoothing
        "vertical" -> vertical
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "value" -> { this.value = asFloat(value, 0.5f); return true }
            "maxValue" -> { maxValue = asFloat(value, 1f); return true }
            "fillColor" -> { fillColor = asColor(value, fillColor); return true }
            "backgroundColor" -> { backgroundColor = asColor(value, backgroundColor); return true }
            "showPercentage" -> { showPercentage = asBool(value, false); return true }
            "smoothing" -> { smoothing = asFloat(value, 0f); return true }
            "vertical" -> { vertical = asBool(value, false); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Draggable value slider (volume, brightness, sensitivity). */
open class SliderControl(name: String = "Slider") : ControlNode(name) {
    var value: Float = 0.5f
    var minValue: Float = 0f
    var maxValue: Float = 1f
    var step: Float = 0f
    var trackColor: Color = Color.fromHex("#1B2033")
    var fillColor: Color = Color.fromHex("#6C8CFF")
    var knobColor: Color = Color.fromHex("#E8ECFF")
    var knobRadius: Float = 6f

    val valueChanged = Signal<Float>()

    val ratio: Float get() = if (maxValue - minValue == 0f) 0f else (value - minValue) / (maxValue - minValue)

    private fun setFromX(x: Float) {
        val r = MathUtil.clamp01((x - rect.x) / rect.w.coerceAtLeast(1f))
        var v = minValue + r * (maxValue - minValue)
        if (step > 0f) v = MathUtil.snapped(v, step)
        if (v != value) { value = v; valueChanged.emit(value) }
    }

    override fun onPress(pointer: Pointer) { setFromX(pointer.position.x) }

    override fun handlePointer(pointer: Pointer, isDown: Boolean): Boolean {
        if (!visibleUi || !enabled) return false
        if (isDown) {
            if (!rect.grown(8f).contains(pointer.position)) return false
            isPressed = true
            setFromX(pointer.position.x)
            return true
        }
        if (isPressed) {
            isPressed = false
            setFromX(pointer.position.x)
            return true
        }
        return false
    }

    override fun onProcess(delta: Float) {
        super.onProcess(delta)
        if (isPressed) {
            val pointer = tree?.input?.activePointers()?.firstOrNull() ?: tree?.input?.primaryPointer
            if (pointer != null && pointer.isDown) setFromX(pointer.position.x)
        }
    }

    override fun drawContent(renderer: Renderer, alpha: Float) {
        val trackHeight = 4f
        val trackY = rect.centerY - trackHeight / 2f
        renderer.drawRectScreen(Rect(rect.x, trackY, rect.w, trackHeight), trackColor.withAlpha(0.9f), true)
        renderer.drawRectScreen(Rect(rect.x, trackY, rect.w * ratio, trackHeight), fillColor, true)
        val knobX = rect.x + rect.w * ratio
        renderer.drawCircleScreen(knobX, rect.centerY, knobRadius, knobColor, true)
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("value", PropertyType.FLOAT, "Value", 0.5f, min = -100000f, max = 100000f, step = 0.01f, category = "Slider"),
        PropertyDef("minValue", PropertyType.FLOAT, "Min", 0f, category = "Slider"),
        PropertyDef("maxValue", PropertyType.FLOAT, "Max", 1f, category = "Slider"),
        PropertyDef("step", PropertyType.FLOAT, "Step", 0f, min = 0f, max = 100f, category = "Slider"),
        PropertyDef("fillColor", PropertyType.COLOR, "Fill", Color.fromHex("#6C8CFF"), category = "Slider"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "value" -> value
        "minValue" -> minValue
        "maxValue" -> maxValue
        "step" -> step
        "fillColor" -> fillColor
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "value" -> { this.value = asFloat(value, 0.5f); return true }
            "minValue" -> { minValue = asFloat(value, 0f); return true }
            "maxValue" -> { maxValue = asFloat(value, 1f); return true }
            "step" -> { step = asFloat(value, 0f); return true }
            "fillColor" -> { fillColor = asColor(value, fillColor); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Vertical stack container. */
/**
 * Places [control] at [cell] by rewriting its anchors/offsets before laying it out.
 *
 * Containers must not assign [ControlNode.rect] directly: the next [ControlNode.layout] call would
 * recompute the rect from anchors and snap every child back to the origin. Writing the cell into the
 * anchors makes the layout idempotent, which is what keeps a container stable across frames.
 */
internal fun placeControlInCell(control: ControlNode, cell: Rect, container: Rect) {
    control.anchorLeft = 0f; control.anchorTop = 0f
    control.anchorRight = 0f; control.anchorBottom = 0f
    control.growHorizontal = 0; control.growVertical = 0
    control.offsetLeft = cell.x - container.x
    control.offsetTop = cell.y - container.y
    control.offsetRight = control.offsetLeft + cell.w
    control.offsetBottom = control.offsetTop + cell.h
    control.layout(container)
}

open class VBoxContainer(name: String = "VBoxContainer") : ControlNode(name) {
    var spacing: Float = 4f

    override fun layout(parent: Rect) {
        super.layout(parent)
        var y = rect.y
        for (child in children) {
            val control = child as? ControlNode ?: continue
            val h = if (control.minSize.y > 0f) control.minSize.y else control.rect.h
            placeControlInCell(control, Rect(rect.x, y, rect.w, h), rect)
            y += h + spacing
        }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("spacing", PropertyType.FLOAT, "Spacing", 4f, min = 0f, max = 128f, category = "Container"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = if (property == "spacing") spacing else super.getProperty(property)
    override fun setProperty(property: String, value: Any?): Boolean {
        if (property == "spacing") { spacing = asFloat(value, 4f); invalidateLayout(); return true }
        return super.setProperty(property, value)
    }
}

/** Horizontal stack container. */
open class HBoxContainer(name: String = "HBoxContainer") : ControlNode(name) {
    var spacing: Float = 4f

    override fun layout(parent: Rect) {
        super.layout(parent)
        var x = rect.x
        for (child in children) {
            val control = child as? ControlNode ?: continue
            val w = if (control.minSize.x > 0f) control.minSize.x else control.rect.w
            placeControlInCell(control, Rect(x, rect.y, w, rect.h), rect)
            x += w + spacing
        }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("spacing", PropertyType.FLOAT, "Spacing", 4f, min = 0f, max = 128f, category = "Container"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = if (property == "spacing") spacing else super.getProperty(property)
    override fun setProperty(property: String, value: Any?): Boolean {
        if (property == "spacing") { spacing = asFloat(value, 4f); invalidateLayout(); return true }
        return super.setProperty(property, value)
    }
}

/** Grid container with a fixed number of columns. */
open class GridContainer(name: String = "GridContainer") : ControlNode(name) {
    var columns: Int = 2
    var spacing: Float = 4f

    override fun layout(parent: Rect) {
        super.layout(parent)
        val cellW = (rect.w - spacing * (columns - 1)) / columns
        var x = rect.x
        var y = rect.y
        var column = 0
        var rowHeight = 0f
        for (child in children) {
            val control = child as? ControlNode ?: continue
            val w = if (control.minSize.x > 0f) control.minSize.x else cellW
            val h = if (control.minSize.y > 0f) control.minSize.y else 24f
            placeControlInCell(control, Rect(x, y, w, h), rect)
            rowHeight = maxOf(rowHeight, h)
            column++
            if (column >= columns) { column = 0; x = rect.x; y += rowHeight + spacing; rowHeight = 0f }
            else x += cellW + spacing
        }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("columns", PropertyType.INT, "Columns", 2, min = 1f, max = 16f, step = 1f, category = "Container"),
        PropertyDef("spacing", PropertyType.FLOAT, "Spacing", 4f, min = 0f, max = 64f, category = "Container"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "columns" -> columns
        "spacing" -> spacing
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "columns" -> { columns = asInt(value, 2).coerceAtLeast(1); invalidateLayout(); return true }
            "spacing" -> { spacing = asFloat(value, 4f); invalidateLayout(); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Scrollable viewport: drag the contents, clamp to bounds (inventory lists, credits). */
open class ScrollContainer(name: String = "ScrollContainer") : ControlNode(name) {
    var scrollY: Float = 0f
    var contentHeight: Float = 0f
    var scrollSpeed: Float = 1f
    var dragToScroll: Boolean = true
    private var dragStart: Float = 0f
    private var scrollStart: Float = 0f
    private var dragging = false

    override fun handlePointer(pointer: Pointer, isDown: Boolean): Boolean {
        if (!enabled || !visibleUi) return false
        if (isDown && !rect.contains(pointer.position)) return false
        if (isDown) {
            if (!dragging && rect.contains(pointer.position)) { dragging = true; dragStart = pointer.position.y; scrollStart = scrollY }
            return dragging
        }
        if (dragging) { dragging = false; return true }
        return false
    }

    override fun onProcess(delta: Float) {
        super.onProcess(delta)
        if (dragging && dragToScroll) {
            val pointer = tree?.input?.primaryPointer
            if (pointer != null && pointer.isDown) {
                scrollY = (scrollStart - (pointer.position.y - dragStart) * scrollSpeed)
                    .coerceIn(0f, maxOf(0f, contentHeight - rect.h))
            } else dragging = false
        }
    }

    override fun layout(parent: Rect) {
        super.layout(parent)
        var y = rect.y - scrollY
        for (child in children) {
            val control = child as? ControlNode ?: continue
            val h = if (control.minSize.y > 0f) control.minSize.y else control.rect.h
            placeControlInCell(control, Rect(rect.x, y, rect.w, h), rect)
            y += h + 4f
        }
        contentHeight = (y - (rect.y - scrollY)).coerceAtLeast(rect.h)
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("scrollY", PropertyType.FLOAT, "Scroll Y", 0f, min = 0f, max = 100000f, category = "Scroll"),
        PropertyDef("dragToScroll", PropertyType.BOOL, "Drag to scroll", true, category = "Scroll"),
        PropertyDef("scrollSpeed", PropertyType.FLOAT, "Speed", 1f, min = 0.1f, max = 4f, category = "Scroll"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "scrollY" -> scrollY
        "dragToScroll" -> dragToScroll
        "scrollSpeed" -> scrollSpeed
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "scrollY" -> { scrollY = asFloat(value, 0f); invalidateLayout(); return true }
            "dragToScroll" -> { dragToScroll = asBool(value, true); return true }
            "scrollSpeed" -> { scrollSpeed = asFloat(value, 1f); return true }
        }
        return super.setProperty(property, value)
    }
}

/**
 * On-screen analogue stick. Feeds the "move" axis of the input system so the same movement
 * code works for touch and keyboard.
 */
open class JoystickControl(name: String = "VirtualJoystick") : ControlNode(name) {
    var axisName: String = "move"
    var knobRadius: Float = 22f
    var baseOpacity: Float = 0.35f
    var dynamicPosition: Boolean = true     // appears where the finger lands (left half)
    var leftHalfOnly: Boolean = true
    var deadZone: Float = 0.15f
    var knobColor: Color = Color.fromHex("#E8ECFF")
    var baseColor: Color = Color.fromHex("#6C8CFF")

    private var pointerId: Int = -1
    private var origin: Vec2 = Vec2.ZERO
    var value: Vec2 = Vec2.ZERO
        private set

    override fun onReady() {
        super.onReady()
        if (dynamicPosition) {
            rect = Rect(0f, 0f, (tree?.viewportWidth ?: 480f) * 0.5f, tree?.viewportHeight ?: 270f)
        }
    }

    override fun handlePointer(pointer: Pointer, isDown: Boolean): Boolean {
        if (!visibleUi || !enabled) return false
        if (isDown) {
            if (pointerId >= 0) return false
            if (leftHalfOnly && pointer.position.x > rect.right) return false
            pointerId = pointer.id
            origin = if (dynamicPosition) pointer.position else rect.center
            value = Vec2.ZERO
            return true
        }
        if (pointer.id == pointerId) {
            pointerId = -1
            value = Vec2.ZERO
            tree?.input?.setAxis(axisName, 0f, 0f)
            return true
        }
        return false
    }

    override fun onProcess(delta: Float) {
        super.onProcess(delta)
        if (pointerId < 0) { value = Vec2.ZERO; tree?.input?.setAxis(axisName, 0f, 0f); return }
        val pointer = tree?.input?.activePointers()?.firstOrNull { it.id == pointerId } ?: run {
            pointerId = -1
            value = Vec2.ZERO
            tree?.input?.setAxis(axisName, 0f, 0f)
            return
        }
        val delta2 = pointer.position - origin
        val magnitude = delta2.length / (knobRadius * 2.5f)
        value = if (magnitude < deadZone) Vec2.ZERO else delta2.normalized() * MathUtil.clamp01(magnitude)
        tree?.input?.setAxis(axisName, value.x, value.y)
    }

    override fun drawContent(renderer: Renderer, alpha: Float) {
        val base = if (dynamicPosition && pointerId < 0) rect.center else origin
        renderer.drawCircleScreen(base.x, base.y, knobRadius * 1.8f, baseColor.withAlpha(baseOpacity * 0.5f), filled = true)
        renderer.drawCircleScreen(base.x, base.y, knobRadius * 1.8f, baseColor.withAlpha(baseOpacity), filled = false, lineWidth = 2f)
        val knob = base + value * (knobRadius * 1.4f)
        renderer.drawCircleScreen(knob.x, knob.y, knobRadius * 0.8f, knobColor.withAlpha(0.75f), filled = true)
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("axisName", PropertyType.STRING, "Axis", "move", category = "Joystick"),
        PropertyDef("knobRadius", PropertyType.FLOAT, "Knob radius", 22f, min = 6f, max = 128f, category = "Joystick"),
        PropertyDef("dynamicPosition", PropertyType.BOOL, "Dynamic position", true, category = "Joystick"),
        PropertyDef("leftHalfOnly", PropertyType.BOOL, "Left half only", true, category = "Joystick"),
        PropertyDef("deadZone", PropertyType.FLOAT, "Dead zone", 0.15f, min = 0f, max = 0.6f, step = 0.01f, category = "Joystick"),
        PropertyDef("baseOpacity", PropertyType.FLOAT, "Opacity", 0.35f, min = 0f, max = 1f, step = 0.05f, category = "Joystick"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "axisName" -> axisName
        "knobRadius" -> knobRadius
        "dynamicPosition" -> dynamicPosition
        "leftHalfOnly" -> leftHalfOnly
        "deadZone" -> deadZone
        "baseOpacity" -> baseOpacity
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "axisName" -> { axisName = value?.toString() ?: "move"; return true }
            "knobRadius" -> { knobRadius = asFloat(value, 22f); return true }
            "dynamicPosition" -> { dynamicPosition = asBool(value, true); return true }
            "leftHalfOnly" -> { leftHalfOnly = asBool(value, true); return true }
            "deadZone" -> { deadZone = asFloat(value, 0.15f); return true }
            "baseOpacity" -> { baseOpacity = asFloat(value, 0.35f); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Big on-screen action button bound to an input action (jump, fire, dash). */
open class TouchButtonControl(name: String = "TouchButton") : ControlNode(name) {
    var action: String = "jump"
    var label: String = "A"
    var round: Boolean = true
    var pressedColor: Color = Color.fromHex("#6C8CFF")
    var idleColor: Color = Color.fromHex("#6C8CFF")
    var labelColor: Color = Color.fromHex("#0E1220")

    val pressedEvent = Event()

    override fun onPress(pointer: Pointer) {
        tree?.input?.setAxis(action, 1f, 0f)
        pressedEvent.emit()
    }

    override fun onRelease(pointer: Pointer) {
        tree?.input?.setAxis(action, 0f, 0f)
    }

    override fun drawContent(renderer: Renderer, alpha: Float) {
        val color = (if (isPressed) pressedColor.lighter(0.2f) else idleColor).withAlpha(0.55f * opacity)
        if (round) {
            renderer.drawCircleScreen(rect.centerX, rect.centerY, minOf(rect.w, rect.h) / 2f, color, filled = true)
            renderer.drawCircleScreen(rect.centerX, rect.centerY, minOf(rect.w, rect.h) / 2f, idleColor.withAlpha(0.9f), filled = false, lineWidth = 2f)
        } else {
            renderer.drawRectScreen(rect, color, true)
            renderer.drawRectScreen(rect, idleColor, false, 2f)
        }
        val font = uiFont(renderer) ?: return
        renderer.drawTextScreen(font, label, rect.centerX, rect.centerY, fontSize * 1.2f, labelColor,
            TextAlign.CENTER, TextVAlign.MIDDLE)
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("action", PropertyType.STRING, "Action", "jump", category = "Touch"),
        PropertyDef("label", PropertyType.STRING, "Label", "A", category = "Touch"),
        PropertyDef("round", PropertyType.BOOL, "Round", true, category = "Touch"),
        PropertyDef("idleColor", PropertyType.COLOR, "Color", Color.fromHex("#6C8CFF"), category = "Touch"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "action" -> action
        "label" -> label
        "round" -> round
        "idleColor" -> idleColor
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "action" -> { action = value?.toString() ?: "jump"; return true }
            "label" -> { label = value?.toString() ?: "A"; return true }
            "round" -> { round = asBool(value, true); return true }
            "idleColor" -> { idleColor = asColor(value, idleColor); return true }
        }
        return super.setProperty(property, value)
    }
}

/**
 * Hearts / icon bar used by the sample games' HUDs: repeats an icon [maxCount] times and
 * fills the first [count] with full colour.
 */
open class HudBarControl(name: String = "HudBar") : ControlNode(name) {
    var count: Int = 3
    var maxCount: Int = 3
    var iconTexture: String = ""
    var iconSize: Float = 14f
    var spacing: Float = 2f
    var emptyColor: Color = Color.fromHex("#3A4160")
    var fillColor: Color = Color.WHITE
    var labelText: String = ""

    fun setCount(value: Int, maximum: Int = maxCount) { count = value; maxCount = maximum }

    override fun drawContent(renderer: Renderer, alpha: Float) {
        val texture = tree?.resources?.texture(iconTexture)
        var x = rect.x
        for (i in 0 until maxCount) {
            val color = if (i < count) fillColor else emptyColor
            if (texture != null) {
                val camera = renderer.currentCamera()
                val savedPos = camera.position; val savedZoom = camera.zoom; val savedRotation = camera.rotation
                camera.position = Vec2(renderer.width / 2f, renderer.height / 2f); camera.zoom = 1f; camera.rotation = 0f
                renderer.drawSprite(texture.whole, x, rect.y, iconSize, iconSize, 0f, 0f, 0f, color * effectiveModulate())
                camera.position = savedPos; camera.zoom = savedZoom; camera.rotation = savedRotation
            } else {
                renderer.drawRectScreen(Rect(x, rect.y, iconSize, iconSize), color, true)
            }
            x += iconSize + spacing
        }
        if (labelText.isNotEmpty()) {
            val font = uiFont(renderer) ?: return
            renderer.drawTextScreen(font, labelText, x + 4f, rect.y + iconSize / 2f, fontSize, textColor(),
                TextAlign.LEFT, TextVAlign.MIDDLE)
        }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("count", PropertyType.INT, "Count", 3, min = 0f, max = 64f, step = 1f, category = "HudBar"),
        PropertyDef("maxCount", PropertyType.INT, "Max", 3, min = 1f, max = 64f, step = 1f, category = "HudBar"),
        PropertyDef("iconTexture", PropertyType.TEXTURE, "Icon", "", category = "HudBar"),
        PropertyDef("iconSize", PropertyType.FLOAT, "Icon size", 14f, min = 4f, max = 128f, category = "HudBar"),
        PropertyDef("spacing", PropertyType.FLOAT, "Spacing", 2f, min = 0f, max = 32f, category = "HudBar"),
        PropertyDef("labelText", PropertyType.STRING, "Label", "", category = "HudBar"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "count" -> count
        "maxCount" -> maxCount
        "iconTexture" -> iconTexture
        "iconSize" -> iconSize
        "spacing" -> spacing
        "labelText" -> labelText
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "count" -> { count = asInt(value, 3); return true }
            "maxCount" -> { maxCount = asInt(value, 3); return true }
            "iconTexture" -> { iconTexture = value?.toString() ?: ""; return true }
            "iconSize" -> { iconSize = asFloat(value, 14f); return true }
            "spacing" -> { spacing = asFloat(value, 2f); return true }
            "labelText" -> { labelText = value?.toString() ?: ""; return true }
        }
        return super.setProperty(property, value)
    }
}
