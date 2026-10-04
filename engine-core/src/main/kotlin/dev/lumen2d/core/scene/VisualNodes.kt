/**
 * Lumen2D — visual node library.
 *
 * Sprites, animated sprites, labels, shapes, lights, particles, trails, parallax layers and
 * the camera. Each node declares its properties through [PropertyDef] so the editor's
 * inspector, the serializer and the scene format all work without special cases.
 */
package dev.lumen2d.core.scene

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.MathUtil
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.TAU
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.particles.ParticleEmitterConfig
import dev.lumen2d.core.particles.ParticlePresets
import dev.lumen2d.core.particles.ParticleSystem
import dev.lumen2d.core.render.BlendMode
import dev.lumen2d.core.render.Camera2D
import dev.lumen2d.core.render.Renderer
import dev.lumen2d.core.render.TextEffect
import dev.lumen2d.core.render.TextureFilter
import dev.lumen2d.core.render.TextureRegion
import dev.lumen2d.core.util.Event
import dev.lumen2d.core.util.Signal
import kotlin.math.cos
import kotlin.math.sin

// ----------------------------------------------------------------------------- sprite

/**
 * Draws a texture, a named atlas region, or a frame of a sprite sheet.
 * Textures are referenced by asset id, so a project keeps working when files move.
 */
open class Sprite2D(name: String = "") : Node2D(name) {

    var textureId: String = ""
    /** Named region inside an atlas ("player_idle_0"); empty = whole texture. */
    var regionName: String = ""
    /** Horizontal/vertical frames for classic sprite sheets. */
    var hframes: Int = 1
    var vframes: Int = 1
    var frame: Int = 0
    var flipX: Boolean = false
    var flipY: Boolean = false
    /** Explicit draw size; zero keeps the region's natural pixel size. */
    var drawSize: Vec2 = Vec2.ZERO
    var blend: BlendMode = BlendMode.NORMAL
    var drawOffset: Vec2 = Vec2.ZERO
    var filterOverride: TextureFilter? = null
    /** Modulate multiplies the texture — used for hit flashes and team colours. */
    var cullable: Boolean = true

    private var cachedRegion: TextureRegion? = null
    private var cachedKey: String = ""

    /** Resolves the region this sprite draws, honouring frames and atlases. */
    open fun resolveRegion(): TextureRegion? {
        val res = tree?.resources ?: return cachedRegion
        val key = "$textureId|$regionName|$hframes|$vframes|$frame"
        if (key == cachedKey && cachedRegion != null) return cachedRegion
        val texture = res.texture(textureId) ?: return cachedRegion
        val region = when {
            regionName.isNotEmpty() && hframes <= 1 && vframes <= 1 -> texture.region(regionName) ?: texture.whole
            hframes > 1 || vframes > 1 -> {
                val cellW = texture.width / hframes
                val cellH = texture.height / vframes
                val index = frame.coerceIn(0, hframes * vframes - 1)
                val cx = (index % hframes) * cellW
                val cy = (index / hframes) * cellH
                TextureRegion(texture, cx, cy, cellW, cellH, "frame$index")
            }
            else -> texture.whole
        }
        cachedRegion = region
        cachedKey = key
        return region
    }

    /** Natural size in pixels (region size or explicit draw size). */
    fun naturalSize(): Vec2 {
        val region = resolveRegion() ?: return drawSize
        return if (drawSize.x > 0f && drawSize.y > 0f) drawSize
        else Vec2(region.width.toFloat(), region.height.toFloat())
    }

    override fun localBounds(): Rect {
        val size = naturalSize()
        val hx = pivot.x; val hy = pivot.y
        return Rect(-hx + drawOffset.x, -hy + drawOffset.y, size.x, size.y)
    }

    override fun onDraw(renderer: Renderer, alpha: Float) {
        if (!visible) return
        val region = resolveRegion()
        if (region == null) {
            // Missing art is drawn as a placeholder so the problem is visible in-game.
            val t = globalTransform()
            renderer.drawRect(Rect(t.translation.x - 6f, t.translation.y - 6f, 12f, 12f), Color.MAGENTA, filled = false, lineWidth = 1f)
            return
        }
        val camera = renderer.currentCamera()
        val bounds = worldBounds()
        if (cullable && !camera.visibleWorldRectForAabb().overlaps(bounds.grown(4f))) {
            renderer.stats.culledNodes++
            return
        }
        renderer.stats.renderedNodes++
        val t = globalTransform()
        val size = naturalSize()
        val ox = pivot.x.coerceAtLeast(0f).let { if (it == 0f) size.x / 2f else it }
        val oy = pivot.y.coerceAtLeast(0f).let { if (it == 0f) size.y / 2f else it }
        val filter = filterOverride ?: region.texture.filter
        region.texture.filter = filter
        renderer.drawSprite(
            region,
            t.translation.x + drawOffset.x, t.translation.y + drawOffset.y,
            size.x * t.scaleX, size.y * t.scaleY,
            t.rotation, ox, oy,
            effectiveModulate(), flipX, flipY, blend,
        )
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("textureId", PropertyType.TEXTURE, "Texture", "", category = "Sprite",
            hint = "asset:texture"),
        PropertyDef("regionName", PropertyType.STRING, "Atlas region", "", category = "Sprite"),
        PropertyDef("hframes", PropertyType.INT, "H frames", 1, min = 1f, max = 64f, step = 1f, category = "Sprite"),
        PropertyDef("vframes", PropertyType.INT, "V frames", 1, min = 1f, max = 64f, step = 1f, category = "Sprite"),
        PropertyDef("frame", PropertyType.INT, "Frame", 0, min = 0f, max = 1024f, step = 1f, category = "Sprite"),
        PropertyDef("flipX", PropertyType.BOOL, "Flip X", false, category = "Sprite"),
        PropertyDef("flipY", PropertyType.BOOL, "Flip Y", false, category = "Sprite"),
        PropertyDef("drawSize", PropertyType.VECTOR2, "Draw size", Vec2.ZERO, category = "Sprite",
            tooltip = "Zero keeps the texture's natural pixel size"),
        PropertyDef("drawOffset", PropertyType.VECTOR2, "Offset", Vec2.ZERO, category = "Sprite"),
        PropertyDef("blend", PropertyType.ENUM, "Blend", "NORMAL", category = "Sprite",
            enumValues = BlendMode.entries.map { it.name }),
        PropertyDef("color", PropertyType.COLOR, "Tint", Color.WHITE, category = "Sprite"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "textureId" -> textureId
        "regionName" -> regionName
        "hframes" -> hframes
        "vframes" -> vframes
        "frame" -> frame
        "flipX" -> flipX
        "flipY" -> flipY
        "drawSize" -> drawSize
        "drawOffset" -> drawOffset
        "blend" -> blend.name
        "color" -> modulate
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "textureId" -> { textureId = value?.toString() ?: ""; cachedKey = ""; return true }
            "regionName" -> { regionName = value?.toString() ?: ""; cachedKey = ""; return true }
            "hframes" -> { hframes = asInt(value, 1).coerceAtLeast(1); cachedKey = ""; return true }
            "vframes" -> { vframes = asInt(value, 1).coerceAtLeast(1); cachedKey = ""; return true }
            "frame" -> { frame = asInt(value, 0); return true }
            "flipX" -> { flipX = asBool(value, false); return true }
            "flipY" -> { flipY = asBool(value, false); return true }
            "drawSize" -> { drawSize = asVec2(value, Vec2.ZERO); return true }
            "drawOffset" -> { drawOffset = asVec2(value, Vec2.ZERO); return true }
            "blend" -> { blend = runCatching { BlendMode.valueOf(value.toString()) }.getOrDefault(blend); return true }
            "color" -> { modulate = asColor(value, Color.WHITE); return true }
        }
        return super.setProperty(property, value)
    }
}

// ------------------------------------------------------------------- animated sprite

/**
 * Frame-by-frame animation from a sprite sheet or atlas, with signals the editor and scripts
 * can hook into (`animation_finished`, `frame_changed`).
 */
open class AnimatedSprite2D(name: String = "") : Node2D(name) {

    /** Atlas animation name (when the texture has named animations). */
    var animationName: String = ""
    /** Explicit frame list: atlas region names, or sprite-sheet frame indices as strings. */
    var frames: MutableList<String> = ArrayList()
    var hframes: Int = 1
    var vframes: Int = 1
    var fps: Float = 10f
    var playing: Boolean = true
    var loop: Boolean = true
    var autoplay: Boolean = true
    var speedScale: Float = 1f
    var flipX: Boolean = false
    var flipY: Boolean = false
    var drawScale: Float = 1f
    var blend: BlendMode = BlendMode.NORMAL
    var drawOffset: Vec2 = Vec2.ZERO
    var textureId: String = ""

    var currentFrame: Int = 0
        private set
    private var frameTimer: Float = 0f
    private var finishedEmitted = false

    val frameChanged = Event()
    val animationFinished = Event()
    val animationStarted = Signal<String>()

    /** Switches the explicit frame list (a sprite-sheet animation) and jumps to its first frame. */
    fun setFrames(indices: List<Int>, fps: Float = this.fps) {
        frames = indices.map { it.toString() }.toMutableList()
        this.fps = fps
        cachedSource = ""
        setFrame(0)
    }

    private var regionList: List<TextureRegion> = emptyList()
    private var cachedSource: String = ""

    /** All drawable frames this node can show. */
    fun regions(): List<TextureRegion> {
        val res = tree?.resources ?: return regionList
        val key = "$textureId|$animationName|${frames.joinToString(",")}|$hframes|$vframes"
        if (key == cachedSource) return regionList
        val texture = res.texture(textureId) ?: return regionList
        regionList = when {
            animationName.isNotEmpty() && texture.animations.containsKey(animationName) ->
                texture.animations[animationName] ?: emptyList()
            frames.isNotEmpty() -> frames.mapNotNull { name ->
                texture.region(name) ?: name.toIntOrNull()?.let { index ->
                    val cellW = texture.width / hframes
                    val cellH = texture.height / vframes
                    TextureRegion(texture, (index % hframes) * cellW, (index / hframes) * cellH, cellW, cellH, "f$index")
                }
            }
            hframes > 1 || vframes > 1 -> {
                val cellW = texture.width / hframes
                val cellH = texture.height / vframes
                (0 until hframes * vframes).map { index ->
                    TextureRegion(texture, (index % hframes) * cellW, (index / hframes) * cellH, cellW, cellH, "f$index")
                }
            }
            else -> listOf(texture.whole)
        }
        cachedSource = key
        return regionList
    }

    fun frameCount(): Int = regions().size

    fun play(animation: String? = null, restart: Boolean = false) {
        if (animation != null && animation != animationName) {
            animationName = animation
            frames.clear()
            currentFrame = 0
            frameTimer = 0f
            finishedEmitted = false
            animationStarted.emit(animation)
        }
        playing = true
        if (restart) { currentFrame = 0; frameTimer = 0f; finishedEmitted = false }
    }

    fun stop() { playing = false }

    fun pause() { playing = false }

    fun resume() { playing = true }

    fun setFrame(index: Int) {
        val count = frameCount()
        if (count == 0) return
        val clamped = ((index % count) + count) % count
        if (clamped != currentFrame) { currentFrame = clamped; frameChanged.emit() }
    }

    override fun onProcess(delta: Float) {
        if (!playing || fps <= 0f) return
        val count = frameCount()
        if (count <= 1) return
        frameTimer += delta * fps * speedScale
        while (frameTimer >= 1f) {
            frameTimer -= 1f
            if (currentFrame + 1 >= count) {
                if (loop) setFrame(0)
                else if (!finishedEmitted) { finishedEmitted = true; animationFinished.emit() }
            } else setFrame(currentFrame + 1)
        }
    }

    /** Switches animation automatically when a named state changes (used by sample games). */
    fun setAnimationForState(state: String, map: Map<String, String>) {
        val animation = map[state] ?: return
        if (animation != animationName) play(animation, restart = true)
    }

    override fun localBounds(): Rect {
        val region = regions().getOrNull(currentFrame) ?: return Rect(-8f, -8f, 16f, 16f)
        val w = region.width * drawScale
        val h = region.height * drawScale
        return Rect(-w / 2f + drawOffset.x, -h / 2f + drawOffset.y, w, h)
    }

    override fun onDraw(renderer: Renderer, alpha: Float) {
        val region = regions().getOrNull(currentFrame) ?: return
        if (cullableCheckFails(renderer)) return
        renderer.stats.renderedNodes++
        val t = globalTransform()
        val w = region.width * drawScale * t.scaleX
        val h = region.height * drawScale * t.scaleY
        renderer.drawSprite(region, t.translation.x + drawOffset.x, t.translation.y + drawOffset.y, w, h,
            t.rotation, region.width * drawScale / 2f, region.height * drawScale / 2f,
            effectiveModulate(), flipX, flipY, blend)
    }

    private fun cullableCheckFails(renderer: Renderer): Boolean {
        val camera = renderer.currentCamera()
        if (camera.visibleWorldRectForAabb().overlaps(worldBounds().grown(4f))) return false
        renderer.stats.culledNodes++
        return true
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("textureId", PropertyType.TEXTURE, "Texture", "", category = "Animation", hint = "asset:texture"),
        PropertyDef("animationName", PropertyType.STRING, "Atlas animation", "", category = "Animation"),
        PropertyDef("frames", PropertyType.ARRAY_STRING, "Frames", emptyList<String>(), category = "Animation",
            tooltip = "Atlas region names or sprite-sheet frame indices"),
        PropertyDef("hframes", PropertyType.INT, "H frames", 1, min = 1f, max = 64f, step = 1f, category = "Animation"),
        PropertyDef("vframes", PropertyType.INT, "V frames", 1, min = 1f, max = 64f, step = 1f, category = "Animation"),
        PropertyDef("fps", PropertyType.FLOAT, "FPS", 10f, min = 0.5f, max = 60f, step = 0.5f, category = "Animation"),
        PropertyDef("loop", PropertyType.BOOL, "Loop", true, category = "Animation"),
        PropertyDef("playing", PropertyType.BOOL, "Playing", true, category = "Animation"),
        PropertyDef("drawScale", PropertyType.FLOAT, "Scale", 1f, min = 0.05f, max = 16f, step = 0.05f, category = "Animation"),
        PropertyDef("flipX", PropertyType.BOOL, "Flip X", false, category = "Animation"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "textureId" -> textureId
        "animationName" -> animationName
        "frames" -> frames.toList()
        "hframes" -> hframes
        "vframes" -> vframes
        "fps" -> fps
        "loop" -> loop
        "playing" -> playing
        "drawScale" -> drawScale
        "flipX" -> flipX
        "frame" -> currentFrame
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "textureId" -> { textureId = value?.toString() ?: ""; cachedSource = ""; return true }
            "animationName" -> { animationName = value?.toString() ?: ""; cachedSource = ""; return true }
            "frames" -> {
                frames.clear()
                (value as? List<*>)?.forEach { it?.toString()?.let { f -> frames.add(f) } }
                cachedSource = ""
                return true
            }
            "hframes" -> { hframes = asInt(value, 1).coerceAtLeast(1); cachedSource = ""; return true }
            "vframes" -> { vframes = asInt(value, 1).coerceAtLeast(1); cachedSource = ""; return true }
            "fps" -> { fps = asFloat(value, 10f); return true }
            "loop" -> { loop = asBool(value, true); return true }
            "playing" -> { playing = asBool(value, true); return true }
            "drawScale" -> { drawScale = asFloat(value, 1f); return true }
            "flipX" -> { flipX = asBool(value, false); return true }
            "frame" -> { setFrame(asInt(value, 0)); return true }
        }
        return super.setProperty(property, value)
    }
}

// ------------------------------------------------------------------------------ camera

/** Wraps [Camera2D] as a node, with optional follow target and limits. */
open class CameraNode(name: String = "") : Node2D(name) {
    val camera = Camera2D()
    var isCurrent: Boolean = true
    var followTag: String = ""
    var followNodePath: String = ""
    var limitEnabled: Boolean = false
    var limitRect: Rect = Rect(0f, 0f, 1024f, 576f)
    var smoothingEnabled: Boolean = false
    var smoothing: Float = 0.2f
    var deadZone: Vec2 = Vec2.ZERO

    override fun onReady() {
        camera.position = globalPosition
        updateLimits()
        if (isCurrent) (tree as? SceneTree)?.activeCamera = this
    }

    override fun onProcess(delta: Float) {
        val target = resolveTarget()
        camera.smoothingEnabled = smoothingEnabled
        camera.smoothing = smoothing
        camera.zoom = scale.x
        camera.rotation = globalRotation
        camera.update(delta, target ?: globalPosition)
        updateLimits()
    }

    private fun updateLimits() { camera.limit = if (limitEnabled) limitRect else null }

    private fun resolveTarget(): Vec2? {
        if (followNodePath.isNotEmpty()) {
            val node = root().nodePath(followNodePath) as? Node2D
            if (node != null) return node.globalPosition
        }
        if (followTag.isNotEmpty()) {
            val node = tree?.nodesInGroup(followTag)?.firstOrNull() as? Node2D
            if (node != null) return node.globalPosition
        }
        return null
    }

    fun shake(amplitude: Float, duration: Float) = camera.shake(amplitude, duration)

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("isCurrent", PropertyType.BOOL, "Current", true, category = "Camera"),
        PropertyDef("zoom", PropertyType.FLOAT, "Zoom", 1f, min = 0.1f, max = 8f, step = 0.05f, category = "Camera"),
        PropertyDef("followTag", PropertyType.STRING, "Follow group", "", category = "Camera"),
        PropertyDef("followNodePath", PropertyType.NODE_PATH, "Follow node", "", category = "Camera"),
        PropertyDef("smoothingEnabled", PropertyType.BOOL, "Smoothing", false, category = "Camera"),
        PropertyDef("smoothing", PropertyType.FLOAT, "Smoothing amount", 0.2f, min = 0.01f, max = 1f, category = "Camera"),
        PropertyDef("limitEnabled", PropertyType.BOOL, "Use limits", false, category = "Camera"),
        PropertyDef("limitRect", PropertyType.RECT, "Limit rect", Rect(0f, 0f, 1024f, 576f), category = "Camera"),
        PropertyDef("shakeAmplitude", PropertyType.FLOAT, "Shake amplitude", 0f, min = 0f, max = 64f, category = "Camera"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "isCurrent" -> isCurrent
        "zoom" -> scale.x
        "followTag" -> followTag
        "followNodePath" -> followNodePath
        "smoothingEnabled" -> smoothingEnabled
        "smoothing" -> smoothing
        "limitEnabled" -> limitEnabled
        "limitRect" -> limitRect
        "cameraX" -> camera.position.x
        "cameraY" -> camera.position.y
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "isCurrent" -> { isCurrent = asBool(value, true); if (isCurrent) tree?.activeCamera = this; return true }
            "zoom" -> { scale = Vec2(asFloat(value, 1f), asFloat(value, 1f)); return true }
            "followTag" -> { followTag = value?.toString() ?: ""; return true }
            "followNodePath" -> { followNodePath = value?.toString() ?: ""; return true }
            "smoothingEnabled" -> { smoothingEnabled = asBool(value, false); return true }
            "smoothing" -> { smoothing = asFloat(value, 0.2f); return true }
            "limitEnabled" -> { limitEnabled = asBool(value, false); return true }
            "limitRect" -> { limitRect = (decodeValue(value, PropertyType.RECT, null) as Rect); return true }
            "shakeAmplitude" -> { shake(asFloat(value, 0f), 0.4f); return true }
        }
        return super.setProperty(property, value)
    }
}

// ------------------------------------------------------------------------------- label

/** World-space bitmap-font text (damage numbers, signs, enemy names). */
open class LabelNode2D(name: String = "") : Node2D(name) {
    var text: String = "Label"
    var fontId: String = ""
    var fontSize: Float = 1f
    var color: Color = Color.WHITE
    var outline: Boolean = false
    var shadow: Boolean = true
    var centered: Boolean = true
    var maxWidth: Float = 0f
    var letterSpacing: Float = 0f
    var billboard: Boolean = true    // ignore parent rotation

    override fun localBounds(): Rect {
        val font = tree?.resources?.font(fontId) ?: return Rect(-20f, -6f, 40f, 12f)
        val w = if (maxWidth > 0f) maxWidth else font.measureWidth(text, fontSize)
        val h = font.measureHeight(text, fontSize)
        return Rect(-w / 2f, -h / 2f, w, h)
    }

    override fun onDraw(renderer: Renderer, alpha: Float) {
        val font = tree?.resources?.font(fontId) ?: return
        font.letterSpacing = letterSpacing
        val effect = (if (shadow) TextEffect.SHADOW else 0) or (if (outline) TextEffect.OUTLINE else 0)
        val t = globalTransform()
        renderer.drawText(font, text, t.translation.x, t.translation.y, fontSize, effectiveModulate() * color,
            dev.lumen2d.core.render.TextAlign.CENTER, dev.lumen2d.core.render.TextVAlign.MIDDLE, maxWidth, effect)
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("text", PropertyType.MULTILINE_STRING, "Text", "Label", category = "Text"),
        PropertyDef("fontId", PropertyType.FONT, "Font", "", category = "Text"),
        PropertyDef("fontSize", PropertyType.FLOAT, "Size", 1f, min = 0.2f, max = 8f, step = 0.05f, category = "Text"),
        PropertyDef("color", PropertyType.COLOR, "Color", Color.WHITE, category = "Text"),
        PropertyDef("outline", PropertyType.BOOL, "Outline", false, category = "Text"),
        PropertyDef("shadow", PropertyType.BOOL, "Shadow", true, category = "Text"),
        PropertyDef("maxWidth", PropertyType.FLOAT, "Max width", 0f, min = 0f, max = 2048f, category = "Text"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "text" -> text
        "fontId" -> fontId
        "fontSize" -> fontSize
        "color" -> color
        "outline" -> outline
        "shadow" -> shadow
        "maxWidth" -> maxWidth
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "text" -> { text = value?.toString() ?: ""; return true }
            "fontId" -> { fontId = value?.toString() ?: ""; return true }
            "fontSize" -> { fontSize = asFloat(value, 1f); return true }
            "color" -> { color = asColor(value, Color.WHITE); return true }
            "outline" -> { outline = asBool(value, false); return true }
            "shadow" -> { shadow = asBool(value, true); return true }
            "maxWidth" -> { maxWidth = asFloat(value, 0f); return true }
        }
        return super.setProperty(property, value)
    }
}

// ------------------------------------------------------------------------ shapes

/** Nine-patch panel: scalable UI/level decoration with fixed corners. */
open class NinePatchRectNode(name: String = "") : Node2D(name) {
    var textureId: String = ""
    var size: Vec2 = Vec2(64f, 32f)
    var insets: Rect = Rect(6f, 6f, 6f, 6f)
    var blend: BlendMode = BlendMode.NORMAL
    var tint: Color = Color.WHITE

    override fun localBounds() = Rect(-size.x / 2f, -size.y / 2f, size.x, size.y)

    override fun onDraw(renderer: Renderer, alpha: Float) {
        val region = tree?.resources?.texture(textureId)?.whole ?: return
        val t = globalTransform()
        val rect = Rect(t.translation.x - size.x * t.scaleX / 2f, t.translation.y - size.y * t.scaleY / 2f,
            size.x * t.scaleX, size.y * t.scaleY)
        renderer.drawNinePatch(region, rect, insets, effectiveModulate() * tint, blend)
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("textureId", PropertyType.TEXTURE, "Texture", "", category = "Panel", hint = "asset:texture"),
        PropertyDef("size", PropertyType.VECTOR2, "Size", Vec2(64f, 32f), category = "Panel"),
        PropertyDef("insets", PropertyType.RECT, "Insets", Rect(6f, 6f, 6f, 6f), category = "Panel"),
        PropertyDef("blend", PropertyType.ENUM, "Blend", "NORMAL", category = "Panel",
            enumValues = BlendMode.entries.map { it.name }),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "textureId" -> textureId
        "size" -> size
        "insets" -> insets
        "blend" -> blend.name
        "tint" -> tint
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "textureId" -> { textureId = value?.toString() ?: ""; return true }
            "size" -> { size = asVec2(value, size); return true }
            "insets" -> { insets = decodeValue(value, PropertyType.RECT, null) as Rect; return true }
            "blend" -> { blend = runCatching { BlendMode.valueOf(value.toString()) }.getOrDefault(blend); return true }
            "tint" -> { tint = asColor(value, Color.WHITE); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Polyline with width and optional dash pattern — laser sights, ropes, terrain outlines. */
open class LineNode(name: String = "") : Node2D(name) {
    var points: MutableList<Vec2> = ArrayList()
    var width: Float = 3f
    var color: Color = Color.WHITE
    var closed: Boolean = false
    var rounded: Boolean = true
    var blend: BlendMode = BlendMode.NORMAL

    fun setPoints(vararg values: Float) {
        points.clear()
        var i = 0
        while (i + 1 < values.size) { points.add(Vec2(values[i], values[i + 1])); i += 2 }
    }

    override fun localBounds(): Rect {
        if (points.isEmpty()) return Rect(-4f, -4f, 8f, 8f)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        return Rect(minX - width, minY - width, (maxX - minX) + width * 2f, (maxY - minY) + width * 2f)
    }

    override fun onDraw(renderer: Renderer, alpha: Float) {
        if (points.size < 2) return
        val t = globalTransform()
        val color = effectiveModulate() * this.color
        val world = points.map { t.transformPoint(it) }
        for (i in 0 until world.size - 1) {
            val a = world[i]; val b = world[i + 1]
            renderer.drawLine(a.x, a.y, b.x, b.y, color, width * t.scaleX)
        }
        if (closed) {
            val a = world.last(); val b = world.first()
            renderer.drawLine(a.x, a.y, b.x, b.y, color, width * t.scaleX)
        }
        if (rounded) {
            for (p in world) renderer.drawCircle(p.x, p.y, width * t.scaleX / 2f, color, filled = true)
        }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("width", PropertyType.FLOAT, "Width", 3f, min = 0.5f, max = 64f, step = 0.5f, category = "Line"),
        PropertyDef("color", PropertyType.COLOR, "Color", Color.WHITE, category = "Line"),
        PropertyDef("closed", PropertyType.BOOL, "Closed", false, category = "Line"),
        PropertyDef("rounded", PropertyType.BOOL, "Rounded caps", true, category = "Line"),
        PropertyDef("pointCount", PropertyType.INT, "Points", 0, min = 0f, max = 512f, step = 1f, category = "Line"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "width" -> width
        "color" -> color
        "closed" -> closed
        "rounded" -> rounded
        "pointCount" -> points.size
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "width" -> { width = asFloat(value, 3f); return true }
            "color" -> { color = asColor(value, Color.WHITE); return true }
            "closed" -> { closed = asBool(value, false); return true }
            "rounded" -> { rounded = asBool(value, true); return true }
        }
        return super.setProperty(property, value)
    }

    override fun encodeValue(value: Any?, type: PropertyType): Any? = when {
        value is List<*> && value.firstOrNull() is Vec2 ->
            value.map { v -> listOf((v as Vec2).x, (v as Vec2).y) }
        else -> super.encodeValue(value, type)
    }
}

/** Simple filled polygon (water, terrain chunks, highlights). */
open class PolygonNode(name: String = "") : Node2D(name) {
    var points: MutableList<Vec2> = ArrayList()
    var color: Color = Color.fromHex("#4CC9F0")
    var filled: Boolean = true
    var outlineColor: Color = Color.BLACK
    var outlineWidth: Float = 0f

    override fun localBounds(): Rect {
        if (points.isEmpty()) return Rect(-8f, -8f, 16f, 16f)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        return Rect.fromLTRB(minX, minY, maxX, maxY)
    }

    override fun onDraw(renderer: Renderer, alpha: Float) {
        if (points.size < 3) return
        val t = globalTransform()
        val flat = FloatArray(points.size * 2)
        points.forEachIndexed { i, p ->
            val w = t.transformPoint(p)
            flat[i * 2] = w.x; flat[i * 2 + 1] = w.y
        }
        renderer.drawPolygon(flat, effectiveModulate() * color, filled)
        if (!filled || outlineWidth > 0f) {
            renderer.drawPolygon(flat, outlineColor, filled = false, lineWidth = outlineWidth.coerceAtLeast(1f))
        }
    }

    fun setPoints(vararg values: Float) {
        points.clear()
        var i = 0
        while (i + 1 < values.size) { points.add(Vec2(values[i], values[i + 1])); i += 2 }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("color", PropertyType.COLOR, "Color", Color.fromHex("#4CC9F0"), category = "Polygon"),
        PropertyDef("filled", PropertyType.BOOL, "Filled", true, category = "Polygon"),
        PropertyDef("outlineWidth", PropertyType.FLOAT, "Outline width", 0f, min = 0f, max = 16f, category = "Polygon"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "color" -> color
        "filled" -> filled
        "outlineWidth" -> outlineWidth
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "color" -> { color = asColor(value, color); return true }
            "filled" -> { filled = asBool(value, true); return true }
            "outlineWidth" -> { outlineWidth = asFloat(value, 0f); return true }
        }
        return super.setProperty(property, value)
    }
}

// ------------------------------------------------------------------------ particles

/** Particle emitter node backed by [ParticleSystem]. */
open class ParticlesNode(name: String = "") : Node2D(name) {
    var config: ParticleEmitterConfig = ParticleEmitterConfig()
    var particleTextureId: String = ""
    var presetName: String = ""
    var emitOnce: Boolean = false
    val system = ParticleSystem(config)

    val finished: Event = Event()

    override fun onReady() {
        system.config = config
        system.restart()
        if (presetName.isNotEmpty() && config.maxParticles == ParticleEmitterConfig().maxParticles) {
            ParticlePresets.named[presetName]?.let { applyPreset(it()) }
        }
        if (emitOnce) emit()
    }

    fun applyPreset(preset: ParticleEmitterConfig) {
        config = preset
        system.config = preset
        system.restart()
    }

    /** Emits a burst now (jump dust, hit sparks, explosions). */
    fun emit(count: Int = config.amount) {
        system.config = config
        system.burst(count, Vec2.ZERO, null, rotation)
    }

    fun restart() { system.restart() }

    fun stopEmitting() { config.emitting = false; system.config = config }

    fun startEmitting() { config.emitting = true; system.config = config }

    override fun onProcess(delta: Float) {
        system.config = config
        system.collisionQuery = if (config.collide) { p ->
            val worldPos = globalTransform().transformPoint(p)
            tree?.physics?.queryPoint(worldPos, 0xFFFF)?.isNotEmpty() == true
        } else null
        system.update(delta, Vec2.ZERO, rotation, visible)
        if (emitOnce && system.finished) finished.emit()
    }

    override fun localBounds(): Rect = system.bounds(Vec2.ZERO).grown(24f)

    override fun onDraw(renderer: Renderer, alpha: Float) {
        val region = if (particleTextureId.isNotEmpty()) tree?.resources?.texture(particleTextureId)?.whole else null
        val t = globalTransform()
        // Particles are stored in local space; transform to world for drawing.
        system.draw(renderer, t.translation, region)
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("presetName", PropertyType.ENUM, "Preset", "", category = "Particles",
            enumValues = listOf("") + ParticlePresets.named.keys.toList(),
            tooltip = "Loading a preset overwrites the parameters below"),
        PropertyDef("particleTextureId", PropertyType.TEXTURE, "Texture", "", category = "Particles"),
        PropertyDef("emitting", PropertyType.BOOL, "Emitting", true, category = "Particles"),
        PropertyDef("amount", PropertyType.INT, "Amount", 16, min = 0f, max = 512f, step = 1f, category = "Particles"),
        PropertyDef("lifetime", PropertyType.FLOAT, "Lifetime", 0.8f, min = 0.05f, max = 10f, step = 0.05f, category = "Particles"),
        PropertyDef("speed", PropertyType.FLOAT, "Speed", 120f, min = 0f, max = 1200f, step = 5f, category = "Particles"),
        PropertyDef("direction", PropertyType.FLOAT, "Direction", -TAU / 4f, min = -6.3f, max = 6.3f, category = "Particles"),
        PropertyDef("spread", PropertyType.FLOAT, "Spread", TAU / 8f, min = 0f, max = TAU, category = "Particles"),
        PropertyDef("gravity", PropertyType.VECTOR2, "Gravity", Vec2(0f, 300f), category = "Particles"),
        PropertyDef("startSize", PropertyType.FLOAT, "Start size", 8f, min = 0.5f, max = 128f, category = "Particles"),
        PropertyDef("endSize", PropertyType.FLOAT, "End size", 0f, min = 0f, max = 128f, category = "Particles"),
        PropertyDef("startColor", PropertyType.COLOR, "Start color", Color(1f, 0.9f, 0.4f), category = "Particles"),
        PropertyDef("endColor", PropertyType.COLOR, "End color", Color(1f, 0.3f, 0.1f, 0f), category = "Particles"),
        PropertyDef("blend", PropertyType.ENUM, "Blend", "ADD", category = "Particles",
            enumValues = BlendMode.entries.map { it.name }),
        PropertyDef("oneShot", PropertyType.BOOL, "One shot", false, category = "Particles"),
        PropertyDef("localSpace", PropertyType.BOOL, "Local space", true, category = "Particles"),
        PropertyDef("collide", PropertyType.BOOL, "Collide", false, category = "Particles"),
        PropertyDef("maxParticles", PropertyType.INT, "Max particles", 256, min = 4f, max = 2048f, step = 4f, category = "Particles"),
        PropertyDef("emitOnce", PropertyType.BOOL, "Emit on ready", false, category = "Particles"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "presetName" -> presetName
        "particleTextureId" -> particleTextureId
        "emitting" -> config.emitting
        "amount" -> config.amount
        "lifetime" -> config.lifetime
        "speed" -> config.speed
        "direction" -> config.direction
        "spread" -> config.spread
        "gravity" -> config.gravity
        "startSize" -> config.startSize
        "endSize" -> config.endSize
        "startColor" -> config.startColor
        "endColor" -> config.endColor
        "blend" -> config.blend.name
        "oneShot" -> config.oneShot
        "localSpace" -> config.localSpace
        "collide" -> config.collide
        "maxParticles" -> config.maxParticles
        "emitOnce" -> emitOnce
        "activeParticles" -> system.activeCount
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "presetName" -> {
                presetName = value?.toString() ?: ""
                ParticlePresets.named[presetName]?.let { applyPreset(it()) }
                return true
            }
            "particleTextureId" -> { particleTextureId = value?.toString() ?: ""; return true }
            "emitting" -> { config.emitting = asBool(value, true); return true }
            "amount" -> { config.amount = asInt(value, 16); return true }
            "lifetime" -> { config.lifetime = asFloat(value, 0.8f); return true }
            "speed" -> { config.speed = asFloat(value, 120f); return true }
            "direction" -> { config.direction = asFloat(value, 0f); return true }
            "spread" -> { config.spread = asFloat(value, TAU / 8f); return true }
            "gravity" -> { config.gravity = asVec2(value, Vec2(0f, 300f)); return true }
            "startSize" -> { config.startSize = asFloat(value, 8f); return true }
            "endSize" -> { config.endSize = asFloat(value, 0f); return true }
            "startColor" -> { config.startColor = asColor(value, config.startColor); return true }
            "endColor" -> { config.endColor = asColor(value, config.endColor); return true }
            "blend" -> { config.blend = runCatching { BlendMode.valueOf(value.toString()) }.getOrDefault(config.blend); return true }
            "oneShot" -> { config.oneShot = asBool(value, false); return true }
            "localSpace" -> { config.localSpace = asBool(value, true); return true }
            "collide" -> { config.collide = asBool(value, false); return true }
            "maxParticles" -> { config.maxParticles = asInt(value, 256).coerceIn(4, 2048); return true }
            "emitOnce" -> { emitOnce = asBool(value, false); return true }
        }
        return super.setProperty(property, value)
    }

    override fun serialize(withChildren: Boolean): MutableMap<String, Any?> {
        val out = super.serialize(withChildren)
        val props = (out["properties"] as? MutableMap<String, Any?>) ?: LinkedHashMap()
        props["_particleConfig"] = config.serialize()
        out["properties"] = props
        return out
    }

    override fun deserialize(data: Map<String, Any?>, registry: NodeRegistry) {
        super.deserialize(data, registry)
        val props = data["properties"] as? Map<*, *>
        (props?.get("_particleConfig") as? Map<*, *>)?.let { raw ->
            @Suppress("UNCHECKED_CAST")
            config = ParticleEmitterConfig.fromJson(raw as Map<String, Any?>)
            system.config = config
        }
    }
}

// ----------------------------------------------------------------------------- light

/** Additive radial light: torches, muzzle flashes, glow around pickups. */
open class LightNode(name: String = "") : Node2D(name) {
    var lightColor: Color = Color(1f, 0.9f, 0.6f, 0.55f)
    var radius: Float = 96f
    var energy: Float = 1f
    var flicker: Float = 0f
    var flickerSpeed: Float = 8f
    var castShadows: Boolean = false     // sampled from tilemap occluders in advanced scenes
    private var time: Float = 0f

    override fun onProcess(delta: Float) { time += delta }

    override fun localBounds() = Rect(-radius, -radius, radius * 2f, radius * 2f)

    override fun onDraw(renderer: Renderer, alpha: Float) {
        val intensity = energy * (1f - flicker * 0.5f * (0.5f + 0.5f * sin(time * flickerSpeed)))
        val t = globalTransform()
        val r = radius * t.scaleX
        val color = lightColor.withAlpha(lightColor.a * intensity * effectiveModulate().a)
        // Layered circles approximate a soft radial falloff without a shader: each ring adds a
        // little more light, so the edge of the light fades out instead of showing steps.
        val layers = 14
        for (i in layers downTo 1) {
            val f = i.toFloat() / layers
            val falloff = (1f - f) * (1f - f) * 0.3f
            renderer.drawCircle(t.translation.x, t.translation.y, r * f, color.withAlpha(color.a * falloff), filled = true)
        }
        renderer.drawCircle(t.translation.x, t.translation.y, r * 0.28f, color.withAlpha(color.a * 0.45f), filled = true)
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("color", PropertyType.COLOR, "Color", Color(1f, 0.9f, 0.6f, 0.55f), category = "Light"),
        PropertyDef("radius", PropertyType.FLOAT, "Radius", 96f, min = 4f, max = 1024f, step = 4f, category = "Light"),
        PropertyDef("energy", PropertyType.FLOAT, "Energy", 1f, min = 0f, max = 4f, step = 0.05f, category = "Light"),
        PropertyDef("flicker", PropertyType.FLOAT, "Flicker", 0f, min = 0f, max = 1f, step = 0.05f, category = "Light"),
        PropertyDef("flickerSpeed", PropertyType.FLOAT, "Flicker speed", 8f, min = 0.5f, max = 40f, category = "Light"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "color", "lightColor" -> lightColor
        "radius" -> radius
        "energy" -> energy
        "flicker" -> flicker
        "flickerSpeed" -> flickerSpeed
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "color", "lightColor" -> { lightColor = asColor(value, lightColor); return true }
            "radius" -> { radius = asFloat(value, 96f); return true }
            "energy" -> { energy = asFloat(value, 1f); return true }
            "flicker" -> { flicker = asFloat(value, 0f); return true }
            "flickerSpeed" -> { flickerSpeed = asFloat(value, 8f); return true }
        }
        return super.setProperty(property, value)
    }
}

// -------------------------------------------------------------------------- parallax

/**
 * Scrolling background layer. Children are drawn normally; the layer offsets itself based on
 * the camera position so distant art moves slower than the foreground.
 */
open class ParallaxLayerNode(name: String = "") : Node2D(name) {
    var scrollFactor: Vec2 = Vec2(0.4f, 0.4f)
    var repeatX: Boolean = true
    var repeatY: Boolean = false
    var repeatWidth: Float = 480f
    private var cameraBase: Vec2? = null

    override fun onProcess(delta: Float) {
        val camera = tree?.activeCamera?.camera ?: return
        if (cameraBase == null) cameraBase = camera.position
        val base = cameraBase!!
        val offset = Vec2(-(camera.position.x - base.x) * scrollFactor.x, -(camera.position.y - base.y) * scrollFactor.y)
        position = Vec2(base.x + offset.x, base.y + offset.y)
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("scrollFactor", PropertyType.VECTOR2, "Scroll factor", Vec2(0.4f, 0.4f), min = 0f, max = 2f, step = 0.05f, category = "Parallax"),
        PropertyDef("repeatX", PropertyType.BOOL, "Repeat X", true, category = "Parallax"),
        PropertyDef("repeatWidth", PropertyType.FLOAT, "Repeat width", 480f, min = 16f, max = 4096f, category = "Parallax"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "scrollFactor" -> scrollFactor
        "repeatX" -> repeatX
        "repeatY" -> repeatY
        "repeatWidth" -> repeatWidth
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "scrollFactor" -> { scrollFactor = asVec2(value, scrollFactor); return true }
            "repeatX" -> { repeatX = asBool(value, true); return true }
            "repeatY" -> { repeatY = asBool(value, false); return true }
            "repeatWidth" -> { repeatWidth = asFloat(value, 480f); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Solid colour or vertical gradient background drawn behind everything. */
open class BackgroundLayerNode(name: String = "") : Node2D(name) {
    var topColor: Color = Color.fromHex("#1B2A41")
    var bottomColor: Color = Color.fromHex("#0B1120")
    var gradient: Boolean = true
    var solidColor: Color = Color.fromHex("#101826")
    var stars: Boolean = false
    var starDensity: Float = 0.0006f
    var starSeed: Long = 7L
    private var starBuffer: FloatArray = FloatArray(0)

    private fun ensureStars() {
        if (!stars) return
        if (starBuffer.isNotEmpty()) return
        val rng = dev.lumen2d.core.math.Rng(starSeed)
        val count = (240 * 135 * starDensity).toInt().coerceIn(8, 600)
        starBuffer = FloatArray(count * 3)
        for (i in 0 until count) {
            starBuffer[i * 3] = rng.nextFloat(0f, 240f)
            starBuffer[i * 3 + 1] = rng.nextFloat(0f, 135f)
            starBuffer[i * 3 + 2] = rng.nextFloat(0.3f, 1f)
        }
    }

    override fun onDraw(renderer: Renderer, alpha: Float) {
        // Drawn in screen space, tiled to cover the viewport.
        val camera = renderer.currentCamera()
        val offsetX = -camera.position.x * 0.1f
        val offsetY = -camera.position.y * 0.1f
        if (gradient) {
            val steps = renderer.height
            for (i in 0 until steps) {
                val t = i.toFloat() / (steps - 1).coerceAtLeast(1)
                renderer.drawRectScreen(Rect(0f, i.toFloat(), renderer.width.toFloat(), 1f), topColor.lerp(bottomColor, t), true)
            }
        } else {
            renderer.drawRectScreen(Rect(0f, 0f, renderer.width.toFloat(), renderer.height.toFloat()), solidColor, true)
        }
        if (stars) {
            ensureStars()
            for (i in 0 until starBuffer.size / 3) {
                val x = MathUtil.wrap(starBuffer[i * 3] + offsetX, 0f, renderer.width.toFloat())
                val y = MathUtil.wrap(starBuffer[i * 3 + 1] + offsetY, 0f, renderer.height.toFloat())
                val b = starBuffer[i * 3 + 2]
                renderer.drawRectScreen(Rect(x, y, 1f, 1f), Color(b, b, b, 0.85f), true)
            }
        }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("gradient", PropertyType.BOOL, "Gradient", true, category = "Background"),
        PropertyDef("topColor", PropertyType.COLOR, "Top color", Color.fromHex("#1B2A41"), category = "Background"),
        PropertyDef("bottomColor", PropertyType.COLOR, "Bottom color", Color.fromHex("#0B1120"), category = "Background"),
        PropertyDef("solidColor", PropertyType.COLOR, "Solid color", Color.fromHex("#101826"), category = "Background"),
        PropertyDef("stars", PropertyType.BOOL, "Starfield", false, category = "Background"),
        PropertyDef("starDensity", PropertyType.FLOAT, "Star density", 0.0006f, min = 0f, max = 0.01f, step = 0.0001f, category = "Background"),
        PropertyDef("starSeed", PropertyType.INT, "Star seed", 7, min = 0f, max = 100000f, step = 1f, category = "Background"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "gradient" -> gradient
        "topColor" -> topColor
        "bottomColor" -> bottomColor
        "solidColor" -> solidColor
        "stars" -> stars
        "starDensity" -> starDensity
        "starSeed" -> starSeed
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "gradient" -> { gradient = asBool(value, true); return true }
            "topColor" -> { topColor = asColor(value, topColor); return true }
            "bottomColor" -> { bottomColor = asColor(value, bottomColor); return true }
            "solidColor" -> { solidColor = asColor(value, solidColor); return true }
            "stars" -> { stars = asBool(value, false); starBuffer = FloatArray(0); return true }
            "starDensity" -> { starDensity = asFloat(value, 0.0006f); starBuffer = FloatArray(0); return true }
            "starSeed" -> { starSeed = asInt(value, 7).toLong(); starBuffer = FloatArray(0); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Fading motion trail behind a fast-moving object. */
open class TrailNode(name: String = "") : Node2D(name) {
    var length: Int = 12
    var color: Color = Color.fromHex("#7BF1A8")
    var width: Float = 6f
    var minDistance: Float = 2f
    var fadeSeconds: Float = 0.35f
    private val points = ArrayDeque<TrailPoint>()

    private data class TrailPoint(val position: Vec2, var age: Float)

    override fun onProcess(delta: Float) {
        val target = (parent as? Node2D)?.globalPosition ?: globalPosition
        val last = points.lastOrNull()
        if (last == null || last.position.distanceTo(target) >= minDistance) points.addLast(TrailPoint(target, 0f))
        while (points.size > length) points.removeFirst()
        val iterator = points.iterator()
        while (iterator.hasNext()) { val p = iterator.next(); p.age += delta; if (p.age > fadeSeconds) iterator.remove() }
    }

    override fun onDraw(renderer: Renderer, alpha: Float) {
        if (points.size < 2) return
        val list = points.toList()
        for (i in 0 until list.size - 1) {
            val a = list[i]; val b = list[i + 1]
            val t = 1f - (a.age / fadeSeconds).coerceIn(0f, 1f)
            val c = color.withAlpha(color.a * t * 0.8f)
            renderer.drawLine(a.position.x, a.position.y, b.position.x, b.position.y, c, width * t)
        }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("length", PropertyType.INT, "Length", 12, min = 2f, max = 128f, step = 1f, category = "Trail"),
        PropertyDef("color", PropertyType.COLOR, "Color", Color.fromHex("#7BF1A8"), category = "Trail"),
        PropertyDef("width", PropertyType.FLOAT, "Width", 6f, min = 1f, max = 64f, category = "Trail"),
        PropertyDef("fadeSeconds", PropertyType.FLOAT, "Fade", 0.35f, min = 0.05f, max = 4f, category = "Trail"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "length" -> length
        "color" -> color
        "width" -> width
        "fadeSeconds" -> fadeSeconds
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "length" -> { length = asInt(value, 12).coerceIn(2, 128); return true }
            "color" -> { color = asColor(value, color); return true }
            "width" -> { width = asFloat(value, 6f); return true }
            "fadeSeconds" -> { fadeSeconds = asFloat(value, 0.35f); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Editor-only position marker: spawn points, waypoints, patrol nodes. */
open class Marker2D(name: String = "") : Node2D(name) {
    var markerKind: String = "spawn"
    var radius: Float = 8f

    override fun localBounds() = Rect(-radius, -radius, radius * 2f, radius * 2f)

    override fun onDraw(renderer: Renderer, alpha: Float) {
        // Markers are invisible at runtime; the editor draws its own gizmos.
        val tree = tree
        if (tree != null && !tree.debugDrawEnabled) return
        val t = globalTransform()
        renderer.drawCircle(t.translation.x, t.translation.y, radius, Color.fromHex("#FFD166"), filled = false, lineWidth = 1f)
        renderer.drawLine(t.translation.x - radius, t.translation.y, t.translation.x + radius, t.translation.y, Color.fromHex("#FFD166"), 1f)
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("markerKind", PropertyType.ENUM, "Kind", "spawn", category = "Marker",
            enumValues = listOf("spawn", "waypoint", "patrol", "checkpoint", "goal", "exit", "trigger")),
        PropertyDef("radius", PropertyType.FLOAT, "Radius", 8f, min = 2f, max = 128f, category = "Marker"),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "markerKind" -> markerKind
        "radius" -> radius
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "markerKind" -> { markerKind = value?.toString() ?: "spawn"; return true }
            "radius" -> { radius = asFloat(value, 8f); return true }
        }
        return super.setProperty(property, value)
    }
}
