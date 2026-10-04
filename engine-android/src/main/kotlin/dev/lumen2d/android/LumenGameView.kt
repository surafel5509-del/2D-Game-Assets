/**
 * Lumen2D — Android game view.
 *
 * Runs a [Game] inside a plain [View], exactly like `LumenWindow` does on the desktop: the software
 * framebuffer is blitted to a [Bitmap] and scaled into the view with the project's stretch mode,
 * nearest-neighbour filtered for pixel art (`config.pixelArt`).
 *
 * Why a software-rendered View instead of a GLSurfaceView: the whole engine (renderer, particles,
 * lighting, text) is CPU-rendered on both platforms, which means the editor, the exported game and
 * the CI screenshots all produce identical pixels. The view is driven by
 * [Choreographer], so it ticks on the display's vsync — the same cadence a hardware-accelerated
 * renderer would get, with no thread and no wake locks of its own.
 *
 * The view is also the editor's viewport: [onDesignTap] lets the studio select nodes by tapping the
 * running scene, [selection] draws a highlight around the selected node and [overlay] renders
 * editor UI (grid, gizmos, debug text) in view space.
 */
package dev.lumen2d.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import dev.lumen2d.core.game.Game
import dev.lumen2d.core.game.StretchMath
import dev.lumen2d.core.input.InputSource
import dev.lumen2d.core.input.KeyCode
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.scene.Node2D
import dev.lumen2d.core.util.Log

open class LumenGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    /** The running game; null until [open] is called. */
    var game: Game? = null
        private set

    private val choreographer: Choreographer = Choreographer.getInstance()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint()
    private val viewportRect = AndroidRect()

    private var frameBitmap: Bitmap? = null
    private var running = false
    private var callbackScheduled = false
    private var lastFrameNanos = 0L
    private var accumulator = 0.0

    private var frameCounter = 0L
    private var fpsStartNanos = 0L

    /** Measured frames per second, refreshed twice a second. */
    var fps: Float = 0f
        private set

    /** Called after every presented frame; the studio uses it for its status bar. */
    var onFrame: ((Float) -> Unit)? = null

    /** Draw performance/entity counters over the frame (the editor's F3). */
    var showPerformanceOverlay: Boolean = false

    /** Editor hook: draw anything on top of the frame, in view coordinates. */
    var overlay: ((Canvas, AndroidRect) -> Unit)? = null

    /**
     * Editor hook: a tap in *design* coordinates. Return true when the tap was consumed by the
     * editor so it is not forwarded to the game's input map.
     */
    var onDesignTap: ((Vec2) -> Boolean)? = null

    /**
     * Editor hook: every touch in design coordinates. [phase] is [TOUCH_DOWN], [TOUCH_MOVE] or
     * [TOUCH_UP]; return true to consume the gesture so the game's input map never sees it.
     */
    var onDesignTouch: ((Vec2, Int) -> Boolean)? = null

    /** Node highlighted by the editor. */
    var selection: Node2D? = null

    /** Highlight colour of [selection]. */
    var selectionColor: Int = Color.parseColor("#7CF7C4")

    /** True while the frame loop is alive. */
    val isRunning: Boolean get() = running

    /** Viewport actually covered by the game inside this view (letterbox aware). */
    val viewport: Rect
        get() {
            val game = game ?: return Rect(0f, 0f, width.toFloat(), height.toFloat())
            return StretchMath.viewport(game.config, width, height)
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        setBackgroundColor(Color.BLACK)
    }

    // ------------------------------------------------------------------ lifecycle

    /** Attaches a prepared game (renderer + scripts already installed) and starts the frame loop. */
    open fun open(game: Game, startNow: Boolean = true) {
        this.game = game
        syncInputSurface()
        if (startNow) resume()
    }

    /** Stops the frame loop but keeps the game state (stretch of the editor). */
    fun pause() {
        running = false
        if (callbackScheduled) {
            choreographer.removeFrameCallback(this)
            callbackScheduled = false
        }
    }

    fun resume() {
        if (game == null || running) return
        running = true
        lastFrameNanos = 0L
        accumulator = 0.0
        schedule()
    }

    /** Tears the view down: stops the loop and releases the game's audio device. */
    fun close() {
        pause()
        game?.requestQuit()
        game = null
        frameBitmap?.recycle()
        frameBitmap = null
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    private fun schedule() {
        if (callbackScheduled || !running) return
        callbackScheduled = true
        choreographer.postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        callbackScheduled = false
        if (!running) return
        val game = game ?: return

        if (lastFrameNanos == 0L) {
            lastFrameNanos = frameTimeNanos
            schedule()
            return
        }
        val delta = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).coerceIn(0.0, 0.25)
        lastFrameNanos = frameTimeNanos

        // Fixed-step updates at the project's target FPS keep physics deterministic; rendering
        // happens once per displayed frame (which is what vsync gives us anyway).
        val targetDelta = 1.0 / game.config.targetFps.coerceAtLeast(15)
        accumulator += delta
        if (accumulator >= targetDelta) {
            val step = accumulator.coerceAtMost(targetDelta * 4)
            accumulator = 0.0
            game.frame(step.toFloat())
            present()
            frameCounter++
            if (fpsStartNanos == 0L) fpsStartNanos = frameTimeNanos
            val elapsed = (frameTimeNanos - fpsStartNanos) / 1_000_000_000.0
            if (elapsed >= 0.5) {
                fps = (frameCounter / elapsed).toFloat()
                frameCounter = 0
                fpsStartNanos = frameTimeNanos
                onFrame?.invoke(fps)
            }
        }
        if (game.quitRequested) {
            running = false
            return
        }
        schedule()
    }

    // ------------------------------------------------------------------- painting

    private fun present() {
        val game = game ?: return
        val buffer = game.captureFrame() ?: return
        var bitmap = frameBitmap
        if (bitmap == null || bitmap.width != buffer.width || bitmap.height != buffer.height) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(buffer.width, buffer.height, Bitmap.Config.ARGB_8888)
            frameBitmap = bitmap
        }
        bitmap.setPixels(buffer.pixels, 0, buffer.width, 0, 0, buffer.width, buffer.height)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val game = game
        val design: Rect = StretchMath.viewport(game?.config ?: return, width, height)
        viewportRect.set(design.x.toInt(), design.y.toInt(), design.right.toInt(), design.bottom.toInt())

        canvas.drawColor(Color.BLACK)
        val bitmap = frameBitmap
        if (bitmap != null) {
            paint.isFilterBitmap = game != null && !game.config.pixelArt
            canvas.drawBitmap(bitmap, null, RectF(viewportRect), paint)
        }

        drawSelection(canvas)
        overlay?.invoke(canvas, viewportRect)
        if (showPerformanceOverlay) drawPerformance(canvas, viewportRect, game)
    }

    private fun drawSelection(canvas: Canvas) {
        val node = selection ?: return
        val design = game?.config ?: return
        val center = worldToView(node.globalPosition)
        val size = nodeSize(node)
        val scaleX = viewportRect.width().toFloat() / design.designWidth.coerceAtLeast(1)
        val scaleY = viewportRect.height().toFloat() / design.designHeight.coerceAtLeast(1)
        val density = resources.displayMetrics.density
        val zoom = game?.tree?.activeCamera?.camera?.zoom ?: 1f

        viewportPaint()
        overlayPaint.color = selectionColor
        val halfW = size.x * 0.5f * scaleX * zoom
        val halfH = size.y * 0.5f * scaleY * zoom
        canvas.drawRect(center.x - halfW, center.y - halfH, center.x + halfW, center.y + halfH, overlayPaint)
        overlayPaint.style = Paint.Style.FILL
        canvas.drawCircle(center.x, center.y, 2.5f * density, overlayPaint)
    }

    /** Rendered size of a node in design units, used by the editor's selection box. */
    private fun nodeSize(node: Node2D): Vec2 {
        val sprite = node as? dev.lumen2d.core.scene.Sprite2D
        val region = sprite?.resolveRegion()
        if (region != null && region.width > 0 && region.height > 0) {
            return Vec2(region.width.toFloat(), region.height.toFloat())
        }
        sprite?.let { if (it.drawSize.x > 0f && it.drawSize.y > 0f) return it.drawSize }
        return Vec2(16f, 16f)
    }

    private fun drawPerformance(canvas: Canvas, area: AndroidRect, game: Game?) {
        if (game == null) return
        val summary = game.performanceSummary()
        val density = resources.displayMetrics.density
        overlayPaint.color = Color.parseColor("#9CE88A")
        overlayPaint.textSize = 11f * density
        overlayPaint.typeface = Typeface.MONOSPACE
        val lines = listOf(
            "fps ${fps.toInt()}",
            "frame ${"%.1f".format(summary["frameMs"] ?: 0f)} ms",
            "nodes ${summary["nodes"]?.toInt() ?: 0}  draws ${summary["drawCalls"]?.toInt() ?: 0}",
            "sprites ${summary["sprites"]?.toInt() ?: 0}  bodies ${summary["bodies"]?.toInt() ?: 0}",
        )
        var y = area.top + 18f * density
        overlayPaint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRect(area.left.toFloat(), y - 12f * density, area.left + 170f * density, y + lines.size * 15f * density, overlayPaint)
        overlayPaint.color = Color.parseColor("#9CE88A")
        for (line in lines) {
            canvas.drawText(line, area.left + 8f * density, y, overlayPaint)
            y += 15f * density
        }
    }

    private fun viewportPaint() {
        overlayPaint.style = Paint.Style.STROKE
        overlayPaint.strokeWidth = 1.5f * resources.displayMetrics.density
        overlayPaint.isAntiAlias = true
    }

    // ---------------------------------------------------------------------- input

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        syncInputSurface()
    }

    private fun syncInputSurface() {
        val input = game?.tree?.input ?: return
        input.screenWidth = width.toFloat().coerceAtLeast(1f)
        input.screenHeight = height.toFloat().coerceAtLeast(1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val game = game ?: return false
        val input = game.tree.input

        // The editor sees the touch first (selection, gizmos); when it consumes the gesture the
        // game does not receive it, exactly like clicking a panel in a desktop editor.
        val design = toDesign(event.x, event.y)
        var consumedByEditor = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> consumedByEditor = onDesignTouch?.invoke(design, TOUCH_DOWN) == true
            MotionEvent.ACTION_MOVE -> consumedByEditor = onDesignTouch?.invoke(design, TOUCH_MOVE) == true
            MotionEvent.ACTION_UP -> {
                consumedByEditor = onDesignTouch?.invoke(design, TOUCH_UP) == true
                if (!consumedByEditor && event.pointerCount <= 1) onDesignTap?.invoke(design)
            }
        }
        if (consumedByEditor) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                input.onPointerDown(event.getPointerId(index), event.getX(index), event.getY(index), InputSource.TOUCH)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    input.onPointerMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                input.onPointerUp(event.getPointerId(event.actionIndex))
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) input.onPointerUp(event.getPointerId(i))
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val game = game ?: return super.onKeyDown(keyCode, event)
        val mapped = KeyCode.fromAndroidKeyCode(keyCode)
        if (mapped == KeyCode.NONE) return super.onKeyDown(keyCode, event)
        game.tree.input.onKeyDown(mapped)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val game = game ?: return super.onKeyUp(keyCode, event)
        val mapped = KeyCode.fromAndroidKeyCode(keyCode)
        if (mapped == KeyCode.NONE) return super.onKeyUp(keyCode, event)
        game.tree.input.onKeyUp(mapped)
        return true
    }

    // ---------------------------------------------------------- coordinate helpers

    /** Converts a view point into the game's design resolution (letterbox aware). */
    fun toDesign(x: Float, y: Float): Vec2 {
        val game = game ?: return Vec2(x, y)
        return StretchMath.screenToDesign(game.config, width, height, Vec2(x, y))
    }

    /** Converts a design-space point into view coordinates. */
    fun worldToView(position: Vec2): Vec2 {
        val game = game ?: return position
        val area = StretchMath.viewport(game.config, width, height)
        val scaleX = area.w / game.config.designWidth.coerceAtLeast(1)
        val scaleY = area.h / game.config.designHeight.coerceAtLeast(1)
        val camera = game.tree.activeCamera?.camera
        val onScreen = if (camera != null && game.currentScene != null) {
            // Camera transform is applied by the renderer; mirror it for editor overlays.
            val zoom = camera.zoom
            Vec2(
                (position.x - camera.effectivePosition.x) * zoom + game.config.designWidth / 2f,
                (position.y - camera.effectivePosition.y) * zoom + game.config.designHeight / 2f,
            )
        } else {
            position
        }
        return Vec2(area.x + onScreen.x * scaleX, area.y + onScreen.y * scaleY)
    }

    /** Design point (0..designWidth) for a view point; used by the editor's tap-to-select. */
    fun designToWorld(point: Vec2): Vec2 {
        val game = game ?: return point
        val camera = game.tree.activeCamera?.camera ?: return point
        val zoom = camera.zoom
        return Vec2(
            (point.x - game.config.designWidth / 2f) / zoom + camera.effectivePosition.x,
            (point.y - game.config.designHeight / 2f) / zoom + camera.effectivePosition.y,
        )
    }

    /** Logs the view's configuration; called by the studio's diagnostics screen. */
    fun describe(): String =
        "view ${width}x${height} · ${if (running) "running" else "paused"} · ${"%.1f".format(fps)} fps" +
            (game?.let { " · '${it.config.title}'" } ?: " · no game")

    companion object {
        const val TOUCH_DOWN = 0
        const val TOUCH_MOVE = 1
        const val TOUCH_UP = 2
    }

    init {
        Log.d("Android", "LumenGameView created")
    }
}
