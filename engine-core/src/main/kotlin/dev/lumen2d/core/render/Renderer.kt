/**
 * Lumen2D — renderer abstraction, cameras and render statistics.
 *
 * [Renderer] is an immediate-mode drawing interface implemented by each backend:
 *  * `SoftwareRenderer` (core) — CPU rasterizer used by CI previews, thumbnails and tests.
 *  * `CanvasRenderer` (engine-android) — hardware-accelerated `android.graphics.Canvas`.
 *  * `AwtRenderer` (engine-desktop) — draws the software buffer to a window.
 * Gameplay code only ever touches this interface, so a scene looks the same everywhere.
 */
package dev.lumen2d.core.render

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.MathUtil
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Vec2

/** Per-frame renderer counters surfaced in the editor profiler. */
class RenderStats {
    var drawCalls: Int = 0
    var sprites: Int = 0
    var vertices: Int = 0
    var clippedPixels: Int = 0
    var blendSwitches: Int = 0
    var textureSwitches: Int = 0
    var culledNodes: Int = 0
    var renderedNodes: Int = 0

    fun reset() {
        drawCalls = 0; sprites = 0; vertices = 0; clippedPixels = 0
        blendSwitches = 0; textureSwitches = 0; culledNodes = 0; renderedNodes = 0
    }

    override fun toString() =
        "draws=$drawCalls sprites=$sprites nodes=$renderedNodes culled=$culledNodes"
}

/**
 * Camera used for 2D viewports: supports pan, zoom, rotation, viewport size, limits
 * (clamping to level bounds), smoothing and screen-shake (used by every sample game).
 */
class Camera2D {
    var position: Vec2 = Vec2.ZERO
    var zoom: Float = 1f
    var rotation: Float = 0f
    var offset: Vec2 = Vec2.ZERO
    var viewportWidth: Float = 480f
    var viewportHeight: Float = 270f
    /** Optional clamp region in world units; null means unbounded. */
    var limit: Rect? = null
    /** 0 = rigid, 1 = instant-follow. Applied when following a target. */
    var smoothing: Float = 0.25f
    var smoothingEnabled: Boolean = false

    /** Screen shake state. */
    private var shakeAmplitude = 0f
    private var shakeDuration = 0f
    private var shakeElapsed = 0f
    var shakeOffset: Vec2 = Vec2.ZERO
        private set

    /** Applies an immediate shake (trauma style: amplitude in world units). */
    fun shake(amplitude: Float, duration: Float) {
        shakeAmplitude = maxOf(shakeAmplitude, amplitude)
        shakeDuration = maxOf(shakeDuration, duration)
        shakeElapsed = 0f
    }

    fun update(dt: Float, follow: Vec2? = null) {
        if (follow != null) {
            val target = follow + offset
            position = if (smoothingEnabled && smoothing > 0f)
                position.lerp(target, MathUtil.clamp01(smoothing * dt * 60f)) else target
        }
        // Shake
        if (shakeDuration > 0f) {
            shakeElapsed += dt
            val t = MathUtil.clamp01(1f - shakeElapsed / shakeDuration)
            val amp = shakeAmplitude * t * t
            shakeOffset = Vec2(
                (Math.random().toFloat() * 2f - 1f) * amp,
                (Math.random().toFloat() * 2f - 1f) * amp,
            )
            if (shakeElapsed >= shakeDuration) { shakeDuration = 0f; shakeAmplitude = 0f; shakeOffset = Vec2.ZERO }
        } else shakeOffset = Vec2.ZERO
        clampToLimits()
    }

    private fun clampToLimits() {
        val l = limit ?: return
        val halfW = viewportWidth / (2f * zoom)
        val halfH = viewportHeight / (2f * zoom)
        var cx = position.x
        var cy = position.y
        cx = if (l.w <= halfW * 2f) l.centerX else MathUtil.clamp(cx, l.left + halfW, l.right - halfW)
        cy = if (l.h <= halfH * 2f) l.centerY else MathUtil.clamp(cy, l.top + halfH, l.bottom - halfH)
        position = Vec2(cx, cy)
    }

    val effectivePosition: Vec2 get() = position + shakeOffset

    /** World position currently centred on screen. */
    val center: Vec2 get() = effectivePosition

    fun worldToScreen(world: Vec2, out: FloatArray? = null): FloatArray {
        val c = effectivePosition
        val dx = world.x - c.x; val dy = world.y - c.y
        val cos = kotlin.math.cos(-rotation); val sin = kotlin.math.sin(-rotation)
        val rx = dx * cos - dy * sin
        val ry = dx * sin + dy * cos
        val sx = rx * zoom + viewportWidth / 2f
        val sy = ry * zoom + viewportHeight / 2f
        return if (out != null) { out[0] = sx; out[1] = sy; out } else floatArrayOf(sx, sy)
    }

    fun screenToWorld(screenX: Float, screenY: Float): Vec2 {
        val c = effectivePosition
        val rx = (screenX - viewportWidth / 2f) / zoom
        val ry = (screenY - viewportHeight / 2f) / zoom
        val cos = kotlin.math.cos(rotation); val sin = kotlin.math.sin(rotation)
        return Vec2(c.x + rx * cos - ry * sin, c.y + rx * sin + ry * cos)
    }

    /** Visible world rectangle — used for culling. */
    fun visibleWorldRect(): Rect {
        val halfW = viewportWidth / (2f * zoom)
        val halfH = viewportHeight / (2f * zoom)
        val c = effectivePosition
        // Slightly larger box when rotated so nothing pops in.
        val pad = if (rotation == 0f) 0f else (halfW + halfH) * 0.5f
        return Rect(c.x - halfW - pad, c.y - halfH - pad, (halfW + pad) * 2f, (halfH + pad) * 2f)
    }

    fun visibleWorldRectForAabb(): Rect {
        if (rotation == 0f || MathUtil.isZero(rotation)) return visibleWorldRect()
        // Conservative AABB of the rotated view.
        val halfW = viewportWidth / (2f * zoom); val halfH = viewportHeight / (2f * zoom)
        val extent = kotlin.math.sqrt(halfW * halfW + halfH * halfH)
        val c = effectivePosition
        return Rect(c.x - extent, c.y - extent, extent * 2f, extent * 2f)
    }

    fun applyTo(renderer: Renderer) {
        renderer.setCamera(effectivePosition, zoom, rotation, viewportWidth, viewportHeight)
    }
}

/**
 * The drawing contract every backend fulfils. Coordinates passed to `draw*` are world-space
 * unless the method name ends in `Screen`.
 */
interface Renderer {
    val width: Int
    val height: Int
    val stats: RenderStats
    val name: String

    /** Clears the target and prepares for a frame. */
    fun beginFrame(clear: Color)
    /** Finalises the frame (flush batches, present). */
    fun endFrame()

    fun setCamera(position: Vec2, zoom: Float, rotation: Float, viewportWidth: Float, viewportHeight: Float)
    fun currentCamera(): Camera2D

    // ------------------------------------------------------------------ transforms
    fun pushTransform(x: Float, y: Float, rotation: Float, scaleX: Float, scaleY: Float, originX: Float, originY: Float)
    fun popTransform()
    /** Clips to a world-space rectangle (intersected with any active clip). */
    fun pushClipWorld(rect: Rect)
    /** Clips to a screen-space rectangle. */
    fun pushClipScreen(rect: Rect)
    fun popClip()

    // --------------------------------------------------------------------- sprites
    fun drawSprite(
        region: TextureRegion,
        x: Float, y: Float, width: Float, height: Float,
        rotation: Float = 0f,
        originX: Float = 0f, originY: Float = 0f,
        tint: Color = Color.WHITE,
        flipX: Boolean = false, flipY: Boolean = false,
        blend: BlendMode = BlendMode.NORMAL,
    )

    /** Draws a nine-patch panel; [insets] describe the border size inside [region]. */
    fun drawNinePatch(region: TextureRegion, rect: Rect, insets: Rect, tint: Color = Color.WHITE, blend: BlendMode = BlendMode.NORMAL)

    // ---------------------------------------------------------------------- shapes
    fun drawRect(rect: Rect, color: Color, filled: Boolean = true, lineWidth: Float = 1f)
    fun drawRotatedRect(rect: Rect, rotation: Float, color: Color, filled: Boolean = true, lineWidth: Float = 1f)
    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, width: Float = 1f)
    fun drawCircle(cx: Float, cy: Float, radius: Float, color: Color, filled: Boolean = true, lineWidth: Float = 1f)
    fun drawEllipse(cx: Float, cy: Float, rx: Float, ry: Float, color: Color, filled: Boolean = true, lineWidth: Float = 1f)
    fun drawPolygon(points: FloatArray, color: Color, filled: Boolean = true, lineWidth: Float = 1f)
    fun drawArc(cx: Float, cy: Float, radius: Float, startAngle: Float, endAngle: Float, color: Color, width: Float = 2f)

    // ------------------------------------------------------------------------ text
    fun drawText(
        font: BitmapFont,
        text: String,
        x: Float, y: Float,
        scale: Float = 1f,
        color: Color = Color.WHITE,
        align: TextAlign = TextAlign.LEFT,
        vAlign: TextVAlign = TextVAlign.TOP,
        maxWidth: Float = 0f,
        effect: Int = TextEffect.NONE,
    )

    // --------------------------------------------------------------- screen overlays
    /** Full-screen colour overlay (damage flash, fades, transitions). */
    fun drawOverlay(color: Color)
    fun drawRectScreen(rect: Rect, color: Color, filled: Boolean = true, lineWidth: Float = 1f)
    /**
     * Filled or outlined circle in screen pixels — the UI toolkit (knobs, virtual joysticks,
     * round buttons, radial HUDs) draws through this.
     */
    fun drawCircleScreen(x: Float, y: Float, radius: Float, color: Color, filled: Boolean = true, lineWidth: Float = 1f)
    fun drawTextScreen(
        font: BitmapFont, text: String, x: Float, y: Float, scale: Float = 1f,
        color: Color = Color.WHITE, align: TextAlign = TextAlign.LEFT, vAlign: TextVAlign = TextVAlign.TOP,
        maxWidth: Float = 0f, effect: Int = TextEffect.NONE,
    )

    // ----------------------------------------------------------------------- effects
    /** Post-processing knobs a game can animate for cheap "juice". */
    var saturation: Float
    var brightness: Float
    var contrast: Float
    var vignette: Float
    var scanlines: Float

    /** Captures the current frame as an image (used by the editor and the CI previews). */
    fun captureFrame(): PixelBuffer?
    /** Backend hook for debug drawing of physics shapes etc. */
    var debugDraw: Boolean

    val supportsBlendModes: Boolean get() = true
}

/** Base class handling shared stats/camera/effect plumbing so backends stay small. */
abstract class BaseRenderer(override val width: Int, override val height: Int) : Renderer {
    override val stats = RenderStats()
    protected val camera = Camera2D()
    protected var cameraPosition = Vec2.ZERO
    protected var cameraZoom = 1f
    protected var cameraRotation = 0f

    protected val transformStack = ArrayList<FloatArray>(16)
    protected val clipStack = ArrayList<Rect>(8)

    /** Bumped whenever the clip stack changes so backends can cache the mapped clip rectangle. */
    protected var clipVersion: Int = 0
        private set

    override var debugDraw: Boolean = false
    override var saturation: Float = 1f
    override var brightness: Float = 0f
    override var contrast: Float = 1f
    override var vignette: Float = 0f
    override var scanlines: Float = 0f

    protected var lastBlend: BlendMode? = null
    protected var lastTextureId: String? = null

    /** True once a backend or game explicitly configured the camera viewport. */
    private var cameraViewportExplicit = false

    /**
     * The default camera covers exactly the render target. Backends that render at a different
     * logical resolution call [setCamera], which marks the viewport as explicit.
     */
    protected fun syncDefaultViewport() {
        if (cameraViewportExplicit) return
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        // Default camera shows world (0,0)..(width,height) with the origin at the top-left,
        // matching the way scenes and UI are authored.
        cameraPosition = Vec2(width / 2f, height / 2f)
        camera.position = cameraPosition
        camera.zoom = cameraZoom
        camera.rotation = cameraRotation
    }

    override fun beginFrame(clear: Color) {
        stats.reset(); lastBlend = null; lastTextureId = null
        syncDefaultViewport()
    }

    override fun setCamera(position: Vec2, zoom: Float, rotation: Float, viewportWidth: Float, viewportHeight: Float) {
        cameraViewportExplicit = true
        cameraPosition = position
        cameraZoom = if (zoom == 0f) 1f else zoom
        cameraRotation = rotation
        camera.viewportWidth = if (viewportWidth > 0) viewportWidth else width.toFloat()
        camera.viewportHeight = if (viewportHeight > 0) viewportHeight else height.toFloat()
        camera.position = position
        camera.zoom = cameraZoom
        camera.rotation = rotation
    }

    override fun currentCamera(): Camera2D = camera

    /** World -> screen transform including the current transform stack. */
    protected fun worldToScreen(x: Float, y: Float, out: FloatArray) {
        // Apply transform stack first (local -> world), then camera.
        var lx = x; var ly = y
        for (i in transformStack.indices) {
            val m = transformStack[i]
            val nx = m[0] * lx + m[1] * ly + m[2]
            val ny = m[3] * lx + m[4] * ly + m[5]
            lx = nx; ly = ny
        }
        val c = cameraPosition
        val dx = lx - c.x; val dy = ly - c.y
        val cos = kotlin.math.cos(-cameraRotation); val sin = kotlin.math.sin(-cameraRotation)
        val rx = dx * cos - dy * sin
        val ry = dx * sin + dy * cos
        out[0] = rx * cameraZoom + camera.viewportWidth / 2f
        out[1] = ry * cameraZoom + camera.viewportHeight / 2f
    }

    protected fun worldScale(): Float = cameraZoom

    protected fun countDraw(vertices: Int = 0, sprites: Int = 0) {
        stats.drawCalls++; stats.sprites += sprites; stats.vertices += vertices
    }

    protected fun trackBlend(blend: BlendMode) {
        if (lastBlend != blend) { stats.blendSwitches++; lastBlend = blend }
    }

    protected fun trackTexture(region: TextureRegion) {
        if (lastTextureId != region.texture.id) { stats.textureSwitches++; lastTextureId = region.texture.id }
    }

    override fun pushTransform(x: Float, y: Float, rotation: Float, scaleX: Float, scaleY: Float, originX: Float, originY: Float) {
        val c = kotlin.math.cos(rotation); val s = kotlin.math.sin(rotation)
        val m00 = c * scaleX; val m01 = -s * scaleY
        val m10 = s * scaleX; val m11 = c * scaleY
        val m02 = x - (originX * m00 + originY * m01)
        val m12 = y - (originX * m10 + originY * m11)
        transformStack.add(floatArrayOf(m00, m01, m02, m10, m11, m12))
    }

    override fun popTransform() { if (transformStack.isNotEmpty()) transformStack.removeAt(transformStack.size - 1) }

    override fun pushClipWorld(rect: Rect) {
        val tl = FloatArray(2); val br = FloatArray(2)
        worldToScreen(rect.left, rect.top, tl)
        worldToScreen(rect.right, rect.bottom, br)
        pushClipScreen(Rect(minOf(tl[0], br[0]), minOf(tl[1], br[1]), kotlin.math.abs(br[0] - tl[0]), kotlin.math.abs(br[1] - tl[1])))
    }

    override fun pushClipScreen(rect: Rect) {
        val current = clipStack.lastOrNull()
        clipStack.add(if (current == null) rect else current.intersection(rect))
        clipVersion++
    }

    override fun popClip() {
        if (clipStack.isNotEmpty()) clipStack.removeAt(clipStack.size - 1)
        clipVersion++
    }

    protected fun activeClip(): Rect? = clipStack.lastOrNull()

    protected fun inClip(px: Float, py: Float): Boolean {
        val c = clipStack.lastOrNull() ?: return true
        return px >= c.left && px <= c.right && py >= c.top && py <= c.bottom
    }

    protected fun transformPointToScreen(x: Float, y: Float, out: FloatArray) = worldToScreen(x, y, out)

    override fun captureFrame(): PixelBuffer? = null
}

/** Records every draw call as data — used for automated tests of what a scene renders. */
class RecordingRenderer(width: Int, height: Int) : BaseRenderer(width, height) {
    override val name = "recording"
    sealed interface Command
    data class Sprite(val region: String, val x: Float, val y: Float, val w: Float, val h: Float,
                      val rotation: Float, val tint: Color, val blend: BlendMode) : Command
    data class RectCmd(val rect: Rect, val color: Color, val filled: Boolean) : Command
    data class Text(val text: String, val x: Float, val y: Float, val color: Color) : Command
    data class Overlay(val color: Color) : Command
    data class CircleCmd(val centre: Vec2, val radius: Float, val color: Color, val filled: Boolean, val lineWidth: Float) : Command

    val commands = ArrayList<Command>()
    var clearColor: Color = Color.BLACK

    override fun beginFrame(clear: Color) { super.beginFrame(clear); commands.clear(); clearColor = clear }
    override fun endFrame() {}

    override fun drawSprite(region: TextureRegion, x: Float, y: Float, width: Float, height: Float, rotation: Float,
                            originX: Float, originY: Float, tint: Color, flipX: Boolean, flipY: Boolean, blend: BlendMode) {
        commands.add(Sprite(region.name.ifEmpty { region.texture.id }, x, y, width, height, rotation, tint, blend))
        countDraw(4, 1)
    }

    override fun drawNinePatch(region: TextureRegion, rect: Rect, insets: Rect, tint: Color, blend: BlendMode) {
        commands.add(RectCmd(rect, tint, true)); countDraw(36, 9)
    }

    override fun drawRect(rect: Rect, color: Color, filled: Boolean, lineWidth: Float) {
        commands.add(RectCmd(rect, color, filled)); countDraw(4)
    }
    override fun drawRotatedRect(rect: Rect, rotation: Float, color: Color, filled: Boolean, lineWidth: Float) {
        commands.add(RectCmd(rect, color, filled)); countDraw(4)
    }
    override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, width: Float) { countDraw(2) }
    override fun drawCircle(cx: Float, cy: Float, radius: Float, color: Color, filled: Boolean, lineWidth: Float) { countDraw(16) }
    override fun drawEllipse(cx: Float, cy: Float, rx: Float, ry: Float, color: Color, filled: Boolean, lineWidth: Float) { countDraw(16) }
    override fun drawPolygon(points: FloatArray, color: Color, filled: Boolean, lineWidth: Float) { countDraw(points.size / 2) }
    override fun drawArc(cx: Float, cy: Float, radius: Float, startAngle: Float, endAngle: Float, color: Color, width: Float) { countDraw(8) }

    override fun drawText(font: BitmapFont, text: String, x: Float, y: Float, scale: Float, color: Color,
                          align: TextAlign, vAlign: TextVAlign, maxWidth: Float, effect: Int) {
        commands.add(Text(text, x, y, color)); countDraw(0, text.length)
    }

    override fun drawOverlay(color: Color) { commands.add(Overlay(color)) }
    override fun drawRectScreen(rect: Rect, color: Color, filled: Boolean, lineWidth: Float) { commands.add(RectCmd(rect, color, filled)) }

    /** Backends that do not implement a native screen-space circle fall back to stacked spans. */
    override fun drawCircleScreen(x: Float, y: Float, radius: Float, color: Color, filled: Boolean, lineWidth: Float) {
        commands.add(CircleCmd(Vec2(x, y), radius, color, filled, lineWidth))
    }
    override fun drawTextScreen(font: BitmapFont, text: String, x: Float, y: Float, scale: Float, color: Color,
                                align: TextAlign, vAlign: TextVAlign, maxWidth: Float, effect: Int) {
        commands.add(Text(text, x, y, color))
    }
}
