/**
 * Lumen2D — desktop window host.
 *
 * Runs a [Game] inside a resizable AWT window. The window owns the loop, forwards keyboard,
 * mouse and scroll input into the engine's [InputState], and blits the software framebuffer
 * with the project's stretch mode applied (letterboxing, integer scaling, ...).
 *
 * Why AWT and not LWJGL/SDL: the desktop build must stay dependency-free so it can run from a
 * plain JDK in CI (see `tools/build-local.sh`), and it is also the reference implementation of
 * the [dev.lumen2d.core.platform.Platform] contract used by the Android backend.
 */
package dev.lumen2d.desktop

import dev.lumen2d.core.game.Game
import dev.lumen2d.core.game.StretchMath
import dev.lumen2d.core.input.KeyCode
import dev.lumen2d.core.input.InputSource
import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.render.PixelBuffer
import dev.lumen2d.core.util.Log
import java.awt.BasicStroke
import java.awt.Canvas
import java.awt.Color as AwtColor
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferStrategy
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame

/**
 * Owns the AWT window, the render loop and the input translation.
 *
 * ```kotlin
 * LumenWindow(game, scale = 3).open()
 * ```
 */
class LumenWindow(
    private val game: Game,
    private val title: String = game.config.title,
    /** Initial integer scale applied to the design resolution. */
    private val scale: Int = 3,
    /** Called every frame with the achieved FPS; used by the editor's status bar. */
    private val onFrame: ((Float) -> Unit)? = null,
) {

    /** Drawing surface. Input listeners are attached in [open] once the adapters exist. */
    private val canvas = object : Canvas() {
        init {
            background = AwtColor.BLACK
            isFocusable = true
            preferredSize = Dimension(1, 1)
        }
    }

    private val frame = JFrame(title)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var image: BufferedImage? = null
    private var frames = 0L
    private var fpsTime = System.nanoTime()

    /** Last FPS measured by the presenter. */
    var fps: Float = 0f
        private set

    /** Set by the engine when a script or the debug overlay asks to quit. */
    var closeWhenQuitRequested: Boolean = true

    // ------------------------------------------------------------------ lifecycle

    fun open() {
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) { close() }
        })
        frame.isResizable = true
        val design = game.config
        canvas.addKeyListener(keyAdapter)
        canvas.addMouseListener(mouseAdapter)
        canvas.addMouseMotionListener(mouseAdapter)
        canvas.addMouseWheelListener(mouseAdapter)
        frame.add(canvas)
        frame.preferredSize = Dimension(design.designWidth * scale, design.designHeight * scale)
        frame.pack()
        frame.setLocationRelativeTo(null)
        canvas.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) { syncInputSurface() }
        })
        frame.isVisible = true
        canvas.requestFocus()
        syncInputSurface()
        running.set(true)
        thread = Thread({ loop() }, "lumen-render").also { it.isDaemon = true; it.start() }
    }

    fun close() {
        running.set(false)
        thread?.join(1500)
        runCatching { frame.isVisible = false }
        runCatching { frame.dispose() }
        game.requestQuit()
    }

    /** True while the window loop is alive. */
    val isRunning: Boolean get() = running.get()

    private fun syncInputSurface() {
        val size = canvas.size
        game.tree.input.screenWidth = size.width.toFloat().coerceAtLeast(1f)
        game.tree.input.screenHeight = size.height.toFloat().coerceAtLeast(1f)
    }

    // ------------------------------------------------------------------- the loop

    private fun loop() {
        Log.i("Window", "Desktop window opened for '${game.config.title}'")
        var last = System.nanoTime()
        var accumulator = 0.0
        val targetDelta = 1.0 / game.config.targetFps.coerceAtLeast(15)
        while (running.get()) {
            val now = System.nanoTime()
            val delta = ((now - last) / 1_000_000_000.0).coerceIn(0.0, 0.25)
            last = now
            accumulator += delta
            // Fixed-step updates at target FPS keep physics deterministic while still
            // rendering once per displayed frame.
            if (accumulator >= targetDelta) {
                val step = accumulator.coerceAtMost(targetDelta * 4)
                accumulator = 0.0
                game.frame(step.toFloat())
                present()
                frames++
                val elapsed = (now - fpsTime) / 1_000_000_000.0
                if (elapsed >= 0.5) {
                    fps = (frames / elapsed).toFloat()
                    frames = 0; fpsTime = now
                    onFrame?.invoke(fps)
                }
                if (game.quitRequested && closeWhenQuitRequested) {
                    running.set(false)
                    javax.swing.SwingUtilities.invokeLater { close() }
                }
            } else {
                Thread.sleep(1)
            }
        }
        Log.i("Window", "Desktop window closed")
    }

    private fun present() {
        val buffer = game.renderer?.captureFrame() ?: return
        val strategy: BufferStrategy = canvas.bufferStrategy ?: run {
            canvas.createBufferStrategy(2); canvas.bufferStrategy
        }
        val g = strategy.drawGraphics as Graphics2D
        try {
            drawFrame(g, buffer)
        } finally {
            g.dispose()
            strategy.show()
        }
    }

    /** Scales the framebuffer into the window honouring the project's stretch mode. */
    private fun drawFrame(g: Graphics2D, buffer: PixelBuffer) {
        val width = canvas.width
        val height = canvas.height
        g.color = AwtColor.BLACK
        g.fillRect(0, 0, width, height)

        val view: Rect = StretchMath.viewport(game.config, width, height)
        val target = imageFor(buffer, view.w.toInt().coerceAtLeast(1), view.h.toInt().coerceAtLeast(1))
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            if (game.config.pixelArt) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            else RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.drawImage(target, view.x.toInt(), view.y.toInt(), view.w.toInt(), view.h.toInt(), null)

        if (game.config.debugDraw) drawDebugOverlay(g, view)
    }

    private var cachedImage: BufferedImage? = null
    private var cachedWidth = 0
    private var cachedHeight = 0

    /** Reuses the AWT image between frames; only reallocates when the window is resized. */
    private fun imageFor(buffer: PixelBuffer, width: Int, height: Int): BufferedImage {
        if (cachedImage == null || cachedWidth != width || cachedHeight != height) {
            cachedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            cachedWidth = width; cachedHeight = height
            image = cachedImage
        }
        val target = cachedImage!!
        if (target.width == buffer.width && target.height == buffer.height) {
            target.setRGB(0, 0, buffer.width, buffer.height, buffer.pixels, 0, buffer.width)
        } else {
            // Design resolution differs from the framebuffer (internal scale): blit then let
            // the GPU-side drawImage do the scaling.
            val tmp = BufferedImage(buffer.width, buffer.height, BufferedImage.TYPE_INT_ARGB)
            tmp.setRGB(0, 0, buffer.width, buffer.height, buffer.pixels, 0, buffer.width)
            val g = target.createGraphics()
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (game.config.pixelArt) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                else RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.drawImage(tmp, 0, 0, target.width, target.height, null)
            g.dispose()
        }
        return target
    }

    private fun drawDebugOverlay(g: Graphics2D, view: Rect) {
        val summary = game.performanceSummary()
        val lines = listOf(
            "fps ${summary["fps"]?.toInt() ?: 0}",
            "frame ${"%.2f".format(summary["frameMs"] ?: 0f)} ms",
            "draws ${summary["drawCalls"]?.toInt() ?: 0}  sprites ${summary["sprites"]?.toInt() ?: 0}",
            "bodies ${summary["bodies"]?.toInt() ?: 0}  assets ${summary["assets"]?.toInt() ?: 0}",
        )
        g.font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        val metrics = g.fontMetrics
        var y = view.y.toInt() + 16
        for (line in lines) {
            val w = metrics.stringWidth(line)
            g.color = AwtColor(0, 0, 0, 170)
            g.fillRect(view.x.toInt() + 6, y - 12, w + 10, 16)
            g.color = AwtColor(0x9CE88A)
            g.drawString(line, view.x.toInt() + 11, y)
            y += 16
        }
        if (game.config.debugDraw) {
            g.color = AwtColor(255, 255, 255, 60)
            g.stroke = BasicStroke(1f)
            g.drawRect(view.x.toInt(), view.y.toInt(), view.w.toInt() - 1, view.h.toInt() - 1)
        }
    }

    // ------------------------------------------------------------------- input

    /** Maps an AWT key code onto the engine's cross-platform [KeyCode]. */
    private fun mapKey(e: KeyEvent): KeyCode = when (e.keyCode) {
        KeyEvent.VK_LEFT -> KeyCode.LEFT
        KeyEvent.VK_RIGHT -> KeyCode.RIGHT
        KeyEvent.VK_UP -> KeyCode.UP
        KeyEvent.VK_DOWN -> KeyCode.DOWN
        KeyEvent.VK_SPACE -> KeyCode.SPACE
        KeyEvent.VK_ENTER -> KeyCode.ENTER
        KeyEvent.VK_ESCAPE -> KeyCode.ESCAPE
        KeyEvent.VK_TAB -> KeyCode.TAB
        KeyEvent.VK_SHIFT -> KeyCode.SHIFT
        KeyEvent.VK_CONTROL -> KeyCode.CTRL
        KeyEvent.VK_ALT -> KeyCode.ALT
        KeyEvent.VK_BACK_SPACE -> KeyCode.BACKSPACE
        KeyEvent.VK_F1 -> KeyCode.F1
        KeyEvent.VK_F2 -> KeyCode.F2
        KeyEvent.VK_F3 -> KeyCode.F3
        KeyEvent.VK_F4 -> KeyCode.F4
        KeyEvent.VK_F5 -> KeyCode.F5
        else -> {
            val ch = e.keyChar.uppercaseChar()
            when {
                ch in 'A'..'Z' -> KeyCode.valueOf("KEY_$ch")
                ch in '0'..'9' -> KeyCode.valueOf("DIGIT_$ch")
                else -> KeyCode.NONE
            }
        }
    }

    private val keyAdapter: KeyAdapter = object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            val key = mapKey(e)
            if (key != KeyCode.NONE) game.tree.input.onKeyDown(key)
        }

        override fun keyReleased(e: KeyEvent) {
            val key = mapKey(e)
            if (key != KeyCode.NONE) game.tree.input.onKeyUp(key)
        }
    }

    private val mouseAdapter: MouseAdapter = object : MouseAdapter() {
        /** Converts an AWT point into design-space coordinates. */
        private fun toDesign(e: MouseEvent): Pair<Float, Float> {
            val view = StretchMath.viewport(game.config, canvas.width, canvas.height)
            if (view.w <= 0f || view.h <= 0f) return 0f to 0f
            val x = (e.x - view.x) / view.w * game.config.designWidth
            val y = (e.y - view.y) / view.h * game.config.designHeight
            return x to y
        }

        override fun mousePressed(e: MouseEvent) {
            val (x, y) = toDesign(e)
            game.tree.input.screenWidth = canvas.width.toFloat()
            game.tree.input.screenHeight = canvas.height.toFloat()
            val button = if (e.button == MouseEvent.BUTTON3) 2 else if (e.button == MouseEvent.BUTTON2) 1 else 0
            game.tree.input.onMouseButton(button, true, x, y)
            if (e.button == MouseEvent.BUTTON1) {
                game.tree.input.onPointerDown(0, x, y, InputSource.MOUSE)
                canvas.requestFocus()
            }
        }

        override fun mouseReleased(e: MouseEvent) {
            val (x, y) = toDesign(e)
            val button = if (e.button == MouseEvent.BUTTON3) 2 else if (e.button == MouseEvent.BUTTON2) 1 else 0
            game.tree.input.onMouseButton(button, false, x, y)
            if (e.button == MouseEvent.BUTTON1) game.tree.input.onPointerUp(0)
        }

        override fun mouseDragged(e: MouseEvent) {
            val (x, y) = toDesign(e)
            val id = if (e.button == MouseEvent.BUTTON3 || (e.modifiersEx and MouseEvent.BUTTON3_DOWN_MASK) != 0) 1 else 0
            game.tree.input.onPointerMove(id, x, y)
        }

        override fun mouseMoved(e: MouseEvent) {
            val (x, y) = toDesign(e)
            game.tree.input.onPointerMove(0, x, y)
        }

        override fun mouseWheelMoved(e: MouseWheelEvent) {
            game.tree.input.onScroll(e.wheelRotation.toFloat())
        }
    }
}
