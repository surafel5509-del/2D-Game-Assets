/**
 * Lumen2D — software renderer.
 *
 * A complete CPU rasterizer implementing [Renderer]: transformed sprite quads with tint,
 * flips, nine-patches, shapes, bitmap text, clipping and post effects. It is the engine's
 * reference renderer, which means:
 *  * CI and the offline `tools/build-local.sh` can render real gameplay frames to PNG/GIF,
 *  * the editor can produce scene thumbnails and asset previews without a GPU,
 *  * Android falls back to it if hardware acceleration is unavailable.
 */
package dev.lumen2d.core.render

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.MathUtil
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.TAU
import dev.lumen2d.core.math.Vec2

class SoftwareRenderer(
    override val width: Int,
    override val height: Int,
    /** Render at a lower internal resolution then upscale — the "performance mode" knob. */
    val internalScale: Float = 1f,
) : BaseRenderer(width, height) {

    override val name: String = "software"

    val renderWidth: Int = (width * internalScale).toInt().coerceAtLeast(1)
    val renderHeight: Int = (height * internalScale).toInt().coerceAtLeast(1)
    val buffer = PixelBuffer(renderWidth, renderHeight)
    /** Optional second buffer used by post effects and the editor's grid overlays. */
    private var scratch: PixelBuffer? = null

    private val scaleFactor = renderWidth.toFloat() / width

    private var cachedClip: Rect? = null
    private var clipCacheVersion = -1

    override fun beginFrame(clear: Color) {
        super.beginFrame(clear)
        buffer.fill(clear.toArgb32())
    }

    override fun endFrame() {
        applyPostEffects()
    }

    // ------------------------------------------------------------------- transforms

    /**
     * Maps a point in the sprite's local space (0..w, 0..h with the origin handled by the
     * caller) into framebuffer coordinates, applying renderer transforms and the camera.
     */
    private fun pointToScreen(lx: Float, ly: Float, out: FloatArray) {
        val fx = FloatArray(2)
        worldToScreen(lx, ly, fx)
        out[0] = fx[0] * scaleFactor
        out[1] = fx[1] * scaleFactor
    }

    // ---------------------------------------------------------------------- sprites

    override fun drawSprite(
        region: TextureRegion,
        x: Float, y: Float, width: Float, height: Float,
        rotation: Float,
        originX: Float, originY: Float,
        tint: Color,
        flipX: Boolean, flipY: Boolean,
        blend: BlendMode,
    ) {
        val source = region.texture.pixels
        if (source == null) {
            // No CPU pixels (texture only exists on the GPU): draw a placeholder block.
            drawOutlineQuad(x, y, width, height, rotation, originX, originY, Color.MAGENTA)
            return
        }
        if (tint.a <= 0.002f) return
        trackBlend(blend); trackTexture(region)
        countDraw(4, 1)

        val cos = kotlin.math.cos(rotation); val sin = kotlin.math.sin(rotation)

        fun spriteToScreen(lx: Float, ly: Float, out: FloatArray) {
            val dx = (lx - originX)
            val dy = (ly - originY)
            val wx = x + dx * cos - dy * sin
            val wy = y + dx * sin + dy * cos
            pointToScreen(wx, wy, out)
        }

        val p0 = FloatArray(2); val p1 = FloatArray(2); val p2 = FloatArray(2); val p3 = FloatArray(2)
        spriteToScreen(0f, 0f, p0)
        spriteToScreen(width, 0f, p1)
        spriteToScreen(0f, height, p2)
        spriteToScreen(width, height, p3)

        // Local -> screen affine: p(lx,ly) = p0 + lx*(p1-p0)/w + ly*(p2-p0)/h
        val ax = (p1[0] - p0[0]) / width; val ay = (p1[1] - p0[1]) / width
        val bx = (p2[0] - p0[0]) / height; val by = (p2[1] - p0[1]) / height
        val det = ax * by - ay * bx
        if (kotlin.math.abs(det) < 1e-9f) return
        val invDet = 1f / det

        val minX = kotlin.math.floor(minOf(p0[0], p1[0], p2[0], p3[0])).toInt().coerceAtLeast(0)
        val maxX = kotlin.math.ceil(maxOf(p0[0], p1[0], p2[0], p3[0])).toInt().coerceAtMost(renderWidth - 1)
        val minY = kotlin.math.floor(minOf(p0[1], p1[1], p2[1], p3[1])).toInt().coerceAtLeast(0)
        val maxY = kotlin.math.ceil(maxOf(p0[1], p1[1], p2[1], p3[1])).toInt().coerceAtMost(renderHeight - 1)
        if (maxX < minX || maxY < minY) return

        val clip: Rect? = bufferClip()
        val useLinear = region.texture.filter == TextureFilter.LINEAR ||
            (kotlin.math.abs(det) < 0.85f && region.texture.filter != TextureFilter.NEAREST)

        val pixels = buffer.pixels
        val srcX0 = region.x; val srcY0 = region.y
        val sw = region.width; val sh = region.height
        val texW = source.width
        val tr = tint.r; val tg = tint.g; val tb = tint.b; val ta = tint.a

        for (sy in minY..maxY) {
            if (clip != null && (sy < clip.top || sy > clip.bottom)) {
                stats.clippedPixels += maxX - minX + 1
                continue
            }
            val py = sy + 0.5f - p0[1]
            for (sx in minX..maxX) {
                if (clip != null && (sx < clip.left || sx > clip.right)) { stats.clippedPixels++; continue }
                val px = sx + 0.5f - p0[0]
                // Inverse affine: local = inv(M) * p
                val lx = (px * by - py * bx) * invDet
                if (lx < 0f || lx >= width) continue
                val ly = (py * ax - px * ay) * invDet
                if (ly < 0f || ly >= height) continue

                var u = if (flipX) width - lx else lx
                var v = if (flipY) height - ly else ly
                var src: Int
                if (useLinear) {
                    val uu = (u / width) * sw + srcX0 - 0.5f
                    val vv = (v / height) * sh + srcY0 - 0.5f
                    src = sampleBilinear(source, uu, vv)
                } else {
                    val iu = (u / width * sw).toInt().coerceIn(0, sw - 1) + srcX0
                    val iv = (v / height * sh).toInt().coerceIn(0, sh - 1) + srcY0
                    src = source[if (iu < texW) iu else iu, iv]
                }
                val sa = (src ushr 24) and 0xFF
                if (sa == 0) continue
                if (tr != 1f || tg != 1f || tb != 1f) {
                    val r = (((src shr 16) and 0xFF) * tr).toInt().coerceIn(0, 255)
                    val g = (((src shr 8) and 0xFF) * tg).toInt().coerceIn(0, 255)
                    val b = ((src and 0xFF) * tb).toInt().coerceIn(0, 255)
                    src = (((sa * ta).toInt().coerceIn(0, 255)) shl 24) or (r shl 16) or (g shl 8) or b
                } else if (ta < 1f) {
                    src = (((sa * ta).toInt().coerceIn(0, 255)) shl 24) or (src and 0xFFFFFF)
                }
                val index = sy * renderWidth + sx
                pixels[index] = blendOver(pixels[index], src, blend)
            }
        }
    }

    private fun sampleBilinear(source: PixelBuffer, fx: Float, fy: Float): Int {
        val x0 = kotlin.math.floor(fx).toInt(); val y0 = kotlin.math.floor(fy).toInt()
        val tx = fx - x0; val ty = fy - y0
        val c00 = source[x0, y0]; val c10 = source[x0 + 1, y0]
        val c01 = source[x0, y0 + 1]; val c11 = source[x0 + 1, y0 + 1]
        fun ch(shift: Int) = (((c00 shr shift) and 0xFF) * (1 - tx) * (1 - ty) +
            ((c10 shr shift) and 0xFF) * tx * (1 - ty) +
            ((c01 shr shift) and 0xFF) * (1 - tx) * ty +
            ((c11 shr shift) and 0xFF) * tx * ty).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    override fun drawNinePatch(region: TextureRegion, rect: Rect, insets: Rect, tint: Color, blend: BlendMode) {
        val l = insets.left; val t = insets.top; val r = insets.right; val b = insets.bottom
        val sx = region.x; val sy = region.y
        val sw = region.width; val sh = region.height
        val cw = rect.w - l - r
        val chh = rect.h - t - b
        val srcCenterW = sw - l.toInt() - r.toInt()
        val srcCenterH = sh - t.toInt() - b.toInt()

        fun piece(sxOff: Int, syOff: Int, swp: Int, shp: Int, dx: Float, dy: Float, dw: Float, dh: Float) {
            if (swp <= 0 || shp <= 0 || dw <= 0f || dh <= 0f) return
            val sub = TextureRegion(region.texture, sx + sxOff, sy + syOff, swp, shp, "${region.name}_9p")
            drawSprite(sub, rect.x + dx, rect.y + dy, dw, dh, 0f, 0f, 0f, tint, false, false, blend)
        }
        // corners
        piece(0, 0, l.toInt(), t.toInt(), 0f, 0f, l, t)
        piece(sw - r.toInt(), 0, r.toInt(), t.toInt(), rect.w - r, 0f, r, t)
        piece(0, sh - b.toInt(), l.toInt(), b.toInt(), 0f, rect.h - b, l, b)
        piece(sw - r.toInt(), sh - b.toInt(), r.toInt(), b.toInt(), rect.w - r, rect.h - b, r, b)
        // edges
        piece(l.toInt(), 0, srcCenterW, t.toInt(), l, 0f, cw, t)
        piece(l.toInt(), sh - b.toInt(), srcCenterW, b.toInt(), l, rect.h - b, cw, b)
        piece(0, t.toInt(), l.toInt(), srcCenterH, 0f, t, l, chh)
        piece(sw - r.toInt(), t.toInt(), r.toInt(), srcCenterH, rect.w - r, t, r, chh)
        // center
        piece(l.toInt(), t.toInt(), srcCenterW, srcCenterH, l, t, cw, chh)
    }

    private fun drawOutlineQuad(x: Float, y: Float, w: Float, h: Float, rotation: Float, ox: Float, oy: Float, color: Color) {
        val cos = kotlin.math.cos(rotation); val sin = kotlin.math.sin(rotation)
        fun pt(lx: Float, ly: Float): FloatArray {
            val dx = lx - ox; val dy = ly - oy
            val out = FloatArray(2)
            pointToScreen(x + dx * cos - dy * sin, y + dx * sin + dy * cos, out)
            return out
        }
        val a = pt(0f, 0f); val b = pt(w, 0f); val c = pt(w, h); val d = pt(0f, h)
        lineInBuffer(a[0], a[1], b[0], b[1], color, 1f)
        lineInBuffer(b[0], b[1], c[0], c[1], color, 1f)
        lineInBuffer(c[0], c[1], d[0], d[1], color, 1f)
        lineInBuffer(d[0], d[1], a[0], a[1], color, 1f)
    }

    // ----------------------------------------------------------------------- shapes

    override fun drawRect(rect: Rect, color: Color, filled: Boolean, lineWidth: Float) {
        countDraw(4)
        val a = FloatArray(2); val b = FloatArray(2); val c = FloatArray(2); val d = FloatArray(2)
        pointToScreen(rect.left, rect.top, a)
        pointToScreen(rect.right, rect.top, b)
        pointToScreen(rect.right, rect.bottom, c)
        pointToScreen(rect.left, rect.bottom, d)
        if (filled) fillQuad(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1], color)
        else strokeQuad(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1], color, lineWidth * scaleFactor * cameraZoom)
    }

    override fun drawRotatedRect(rect: Rect, rotation: Float, color: Color, filled: Boolean, lineWidth: Float) {
        countDraw(4)
        val cx = rect.centerX; val cy = rect.centerY
        val cos = kotlin.math.cos(rotation); val sin = kotlin.math.sin(rotation)
        fun rot(px: Float, py: Float): FloatArray {
            val dx = px - cx; val dy = py - cy
            return floatArrayOf(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
        }
        val sa = FloatArray(2); val sb = FloatArray(2); val sc = FloatArray(2); val sd = FloatArray(2)
        pointToScreen(rect.left, rect.top, sa); pointToScreen(rect.right, rect.top, sb)
        pointToScreen(rect.right, rect.bottom, sc); pointToScreen(rect.left, rect.bottom, sd)
        val l = rot(rect.left, rect.top); val r = rot(rect.right, rect.top)
        val bm = rot(rect.right, rect.bottom); val t = rot(rect.left, rect.bottom)
        val a = floatArrayOf(l[0] + sa[0] - rect.left, l[1] + sa[1] - rect.top)
        val b = floatArrayOf(r[0] + sb[0] - rect.right, r[1] + sb[1] - rect.top)
        val c = floatArrayOf(bm[0] + sc[0] - rect.right, bm[1] + sc[1] - rect.bottom)
        val d = floatArrayOf(t[0] + sd[0] - rect.left, t[1] + sd[1] - rect.bottom)
        if (filled) fillQuad(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1], color)
        else strokeQuad(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1], color, lineWidth)
    }

    override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, width: Float) {
        countDraw(2)
        val a = FloatArray(2); val b = FloatArray(2)
        pointToScreen(x1, y1, a); pointToScreen(x2, y2, b)
        lineInBuffer(a[0], a[1], b[0], b[1], color, width * scaleFactor * cameraZoom)
    }

    override fun drawCircle(cx: Float, cy: Float, radius: Float, color: Color, filled: Boolean, lineWidth: Float) {
        countDraw(3)
        val c = FloatArray(2); pointToScreen(cx, cy, c)
        val r = radius * cameraZoom * scaleFactor
        circleInBuffer(c[0], c[1], r, color, filled, lineWidth * scaleFactor * cameraZoom)
    }

    override fun drawCircleScreen(x: Float, y: Float, radius: Float, color: Color, filled: Boolean, lineWidth: Float) {
        countDraw(3)
        circleInBuffer(x * scaleFactor, y * scaleFactor, radius * scaleFactor, color, filled, lineWidth * scaleFactor)
    }

    override fun drawEllipse(cx: Float, cy: Float, rx: Float, ry: Float, color: Color, filled: Boolean, lineWidth: Float) {
        countDraw(3)
        val c = FloatArray(2); pointToScreen(cx, cy, c)
        val erx = rx * cameraZoom * scaleFactor; val ery = ry * cameraZoom * scaleFactor
        val steps = (kotlin.math.max(erx, ery) * 2f).toInt().coerceIn(8, 96)
        val xs = FloatArray(steps); val ys = FloatArray(steps)
        for (i in 0 until steps) {
            val t = i.toFloat() / steps * TAU
            xs[i] = c[0] + kotlin.math.cos(t) * erx
            ys[i] = c[1] + kotlin.math.sin(t) * ery
        }
        if (filled) fillConvexPolygon(xs, ys, color) else strokePolygon(xs, ys, color, lineWidth * cameraZoom * scaleFactor)
    }

    override fun drawPolygon(points: FloatArray, color: Color, filled: Boolean, lineWidth: Float) {
        countDraw(points.size / 2)
        val count = points.size / 2
        val xs = FloatArray(count); val ys = FloatArray(count)
        val out = FloatArray(2)
        for (i in 0 until count) {
            pointToScreen(points[i * 2], points[i * 2 + 1], out)
            xs[i] = out[0]; ys[i] = out[1]
        }
        if (filled) fillConvexPolygon(xs, ys, color) else strokePolygon(xs, ys, color, lineWidth * cameraZoom * scaleFactor)
    }

    override fun drawArc(cx: Float, cy: Float, radius: Float, startAngle: Float, endAngle: Float, color: Color, width: Float) {
        countDraw(3)
        val c = FloatArray(2); pointToScreen(cx, cy, c)
        val r = radius * cameraZoom * scaleFactor
        val steps = (kotlin.math.abs(endAngle - startAngle) * r / 4f).toInt().coerceIn(6, 128)
        var prevX = c[0] + kotlin.math.cos(startAngle) * r
        var prevY = c[1] + kotlin.math.sin(startAngle) * r
        for (i in 1..steps) {
            val t = MathUtil.lerp(startAngle, endAngle, i.toFloat() / steps)
            val nx = c[0] + kotlin.math.cos(t) * r
            val ny = c[1] + kotlin.math.sin(t) * r
            lineInBuffer(prevX, prevY, nx, ny, color, width * cameraZoom * scaleFactor)
            prevX = nx; prevY = ny
        }
    }

    // ------------------------------------------------------------------------- text

    override fun drawText(
        font: BitmapFont, text: String, x: Float, y: Float, scale: Float, color: Color,
        align: TextAlign, vAlign: TextVAlign, maxWidth: Float, effect: Int,
    ) {
        font.draw(text, x, y, scale, color, align, vAlign, maxWidth, effect,
            drawGlyph = { region, gx, gy, gw, gh, tint ->
                drawSprite(region, gx, gy, gw, gh, 0f, 0f, 0f, tint)
            })
    }

    override fun drawTextScreen(
        font: BitmapFont, text: String, x: Float, y: Float, scale: Float, color: Color,
        align: TextAlign, vAlign: TextVAlign, maxWidth: Float, effect: Int,
    ) {
        // Screen space text: bypass the camera. Putting the camera centre at the middle of the
        // viewport makes world coordinates equal screen coordinates.
        val savedPos = cameraPosition; val savedZoom = cameraZoom; val savedRot = cameraRotation
        cameraPosition = Vec2(camera.viewportWidth / 2f, camera.viewportHeight / 2f)
        cameraZoom = 1f; cameraRotation = 0f
        val savedStack = ArrayList(transformStack)
        transformStack.clear()
        font.draw(text, x, y, scale, color, align, vAlign, maxWidth, effect,
            drawGlyph = { region, gx, gy, gw, gh, tint ->
                drawSprite(region, gx, gy, gw, gh, 0f, 0f, 0f, tint)
            })
        cameraPosition = savedPos; cameraZoom = savedZoom; cameraRotation = savedRot
        transformStack.clear(); transformStack.addAll(savedStack)
    }

    // -------------------------------------------------------------- screen overlays

    override fun drawOverlay(color: Color) {
        if (color.a <= 0.002f) return
        val argb = color.toArgb32()
        val srcAlpha = (argb ushr 24) and 0xFF
        val pixels = buffer.pixels
        if (srcAlpha == 255) {
            java.util.Arrays.fill(pixels, argb)
            return
        }
        for (i in pixels.indices) pixels[i] = blendOver(pixels[i], argb, BlendMode.NORMAL)
    }

    override fun drawRectScreen(rect: Rect, color: Color, filled: Boolean, lineWidth: Float) {
        val x0 = (rect.x * scaleFactor).toInt(); val y0 = (rect.y * scaleFactor).toInt()
        val x1 = ((rect.x + rect.w) * scaleFactor).toInt(); val y1 = ((rect.y + rect.h) * scaleFactor).toInt()
        val argb = color.toArgb32()
        if (filled) {
            for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(renderHeight - 1)) {
                for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(renderWidth - 1)) {
                    buffer.pixels[y * renderWidth + x] = blendOver(buffer.pixels[y * renderWidth + x], argb, BlendMode.NORMAL)
                }
            }
        } else {
            for (x in x0..x1) { plotPixel(x, y0, argb); plotPixel(x, y1, argb) }
            for (y in y0..y1) { plotPixel(x0, y, argb); plotPixel(x1, y, argb) }
        }
    }

    // ------------------------------------------------------------- raster primitives

    private fun plotPixel(x: Int, y: Int, argb: Int, blend: BlendMode = BlendMode.NORMAL) {
        if (x < 0 || y < 0 || x >= renderWidth || y >= renderHeight) return
        val clip = bufferClip()
        if (clip != null && (x < clip.left || x > clip.right || y < clip.top || y > clip.bottom)) {
            stats.clippedPixels++
            return
        }
        val index = y * renderWidth + x
        buffer.pixels[index] = blendOver(buffer.pixels[index], argb, blend)
    }

    /** The active screen-space clip converted to framebuffer coordinates, cached per clip change. */
    private fun bufferClip(): Rect? {
        if (clipCacheVersion != clipVersion) {
            cachedClip = activeClipInBufferSpace()
            clipCacheVersion = clipVersion
        }
        return cachedClip
    }

    private fun lineInBuffer(x0: Float, y0: Float, x1: Float, y1: Float, color: Color, width: Float) {
        val argb = color.toArgb32()
        val dx = x1 - x0; val dy = y1 - y0
        val steps = kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy)).toInt().coerceAtLeast(1)
        val half = (width / 2f).coerceAtLeast(0.5f)
        if (width <= 1.5f) {
            for (i in 0..steps) {
                val x = x0 + dx * i / steps; val y = y0 + dy * i / steps
                plotPixel(x.toInt(), y.toInt(), argb)
            }
        } else {
            val nx = -dy / kotlin.math.sqrt(dx * dx + dy * dy + 1e-6f)
            val ny = dx / kotlin.math.sqrt(dx * dx + dy * dy + 1e-6f)
            for (i in 0..steps) {
                val x = x0 + dx * i / steps; val y = y0 + dy * i / steps
                var o = -half
                while (o <= half) {
                    plotPixel((x + nx * o).toInt(), (y + ny * o).toInt(), argb)
                    o += 1f
                }
            }
        }
    }

    /** Scanline fill for convex/arbitrary quads given in screen space. */
    private fun fillQuad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Color) {
        fillConvexPolygon(floatArrayOf(x0, x1, x2, x3), floatArrayOf(y0, y1, y2, y3), color)
    }

    private fun fillConvexPolygon(xs: FloatArray, ys: FloatArray, color: Color) {
        val argb = color.toArgb32()
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (v in ys) { if (v < minY) minY = v; if (v > maxY) maxY = v }
        val yStart = kotlin.math.floor(minY).toInt().coerceAtLeast(0)
        val yEnd = kotlin.math.ceil(maxY).toInt().coerceAtMost(renderHeight - 1)
        val intersections = ArrayList<Float>(8)
        val n = xs.size
        for (y in yStart..yEnd) {
            intersections.clear()
            val sy = y + 0.5f
            for (i in 0 until n) {
                val j = (i + 1) % n
                val yi = ys[i]; val yj = ys[j]
                if ((yi <= sy && yj > sy) || (yj <= sy && yi > sy)) {
                    val t = (sy - yi) / (yj - yi)
                    intersections.add(xs[i] + t * (xs[j] - xs[i]))
                }
            }
            if (intersections.size < 2) continue
            intersections.sort()
            var i = 0
            while (i + 1 < intersections.size) {
                val xa = kotlin.math.ceil(intersections[i] - 0.5f).toInt()
                val xb = kotlin.math.floor(intersections[i + 1] - 0.5f).toInt()
                for (x in xa..xb) plotPixel(x, y, argb)
                i += 2
            }
        }
    }

    private fun strokePolygon(xs: FloatArray, ys: FloatArray, color: Color, width: Float) {
        for (i in xs.indices) {
            val j = (i + 1) % xs.size
            lineInBuffer(xs[i], ys[i], xs[j], ys[j], color, width)
        }
    }

    private fun strokeQuad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Color, width: Float) {
        lineInBuffer(x0, y0, x1, y1, color, width); lineInBuffer(x1, y1, x2, y2, color, width)
        lineInBuffer(x2, y2, x3, y3, color, width); lineInBuffer(x3, y3, x0, y0, color, width)
    }

    private fun circleInBuffer(cx: Float, cy: Float, r: Float, color: Color, filled: Boolean, width: Float) {
        val argb = color.toArgb32()
        val radius = r.coerceAtLeast(0.5f)
        val x0 = kotlin.math.floor(cx - radius).toInt().coerceAtLeast(0)
        val x1 = kotlin.math.ceil(cx + radius).toInt().coerceAtMost(renderWidth - 1)
        val y0 = kotlin.math.floor(cy - radius).toInt().coerceAtLeast(0)
        val y1 = kotlin.math.ceil(cy + radius).toInt().coerceAtMost(renderHeight - 1)
        val inner = if (filled) -1f else (radius - width).coerceAtLeast(0f)
        for (y in y0..y1) {
            val dy = y + 0.5f - cy
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                val d2 = dx * dx + dy * dy
                if (d2 <= radius * radius && (filled || d2 >= inner * inner)) plotPixel(x, y, argb)
            }
        }
    }

    /** Converts the active screen-space clip into framebuffer coordinates. */
    private fun activeClipInBufferSpace(): Rect? {
        val clip = activeClip() ?: return null
        return Rect(clip.x * scaleFactor, clip.y * scaleFactor, clip.w * scaleFactor, clip.h * scaleFactor)
    }

    // ------------------------------------------------------------------ post effects

    private fun applyPostEffects() {
        val needsPost = saturation != 1f || brightness != 0f || contrast != 1f || vignette > 0f || scanlines > 0f
        if (!needsPost) return
        val pixels = buffer.pixels
        val w = renderWidth; val h = renderHeight
        val cx = w / 2f; val cy = h / 2f
        val maxDist = kotlin.math.sqrt(cx * cx + cy * cy)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var c = pixels[i]
                var r = ((c shr 16) and 0xFF) / 255f
                var g = ((c shr 8) and 0xFF) / 255f
                var b = (c and 0xFF) / 255f
                if (saturation != 1f) {
                    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    r = l + (r - l) * saturation; g = l + (g - l) * saturation; b = l + (b - l) * saturation
                }
                if (contrast != 1f) { r = (r - 0.5f) * contrast + 0.5f; g = (g - 0.5f) * contrast + 0.5f; b = (b - 0.5f) * contrast + 0.5f }
                if (brightness != 0f) { r += brightness; g += brightness; b += brightness }
                if (vignette > 0f) {
                    val dx = (x - cx) / maxDist; val dy = (y - cy) / maxDist
                    val d = kotlin.math.sqrt(dx * dx + dy * dy)
                    val f = (1f - vignette * MathUtil.clamp01(d * d)).coerceIn(0f, 1f)
                    r *= f; g *= f; b *= f
                }
                if (scanlines > 0f && y % 2 == 0) {
                    r *= (1f - 0.35f * scanlines); g *= (1f - 0.35f * scanlines); b *= (1f - 0.35f * scanlines)
                }
                val a = (c ushr 24) and 0xFF
                pixels[i] = (a shl 24) or
                    (MathUtil.clamp01(r) * 255).toInt().shl(16) or
                    (MathUtil.clamp01(g) * 255).toInt().shl(8) or
                    (MathUtil.clamp01(b) * 255).toInt()
            }
        }
    }

    // --------------------------------------------------------------------- capture

    override fun captureFrame(): PixelBuffer =
        if (internalScale == 1f) buffer.copy() else buffer.scaled(width, height, linear = true)

    /** Reads a pixel from the framebuffer (automated tests assert on rendered colours). */
    fun pixelAt(x: Int, y: Int): Int = buffer[x * renderWidth / width, y * renderHeight / height]

    /** Renders the frame into a freshly created texture (used for editor thumbnails). */
    fun toTexture(id: String, textureManager: TextureManager): Texture2D =
        textureManager.add(id, captureFrame(), TextureFilter.NEAREST)
}
