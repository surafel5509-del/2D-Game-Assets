/** Software renderer, textures, PNG codec, fonts and drawing primitives. */
package dev.lumen2d.core.test

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.render.BitmapFont
import dev.lumen2d.core.render.FontManager
import dev.lumen2d.core.render.PixelBuffer
import dev.lumen2d.core.render.PngCodec
import dev.lumen2d.core.render.SoftwareRenderer
import dev.lumen2d.core.render.TextAlign
import dev.lumen2d.core.render.TextureFilter
import dev.lumen2d.core.render.TextureManager

private fun argb(buffer: PixelBuffer, x: Int, y: Int): Int = buffer[x, y]

/** Puts the camera so world (0,0) maps to the top-left pixel — handy for pixel assertions. */
private fun SoftwareRenderer.topLeftCamera(): SoftwareRenderer {
    setCamera(Vec2(width / 2f, height / 2f), 1f, 0f, width.toFloat(), height.toFloat())
    return this
}

private fun solidTexture(manager: TextureManager, id: String, size: Int = 4): dev.lumen2d.core.render.Texture2D {
    val buffer = PixelBuffer(size, size)
    for (y in 0 until size) for (x in 0 until size) {
        buffer[x, y] = if (x < size / 2) 0xFFFF0000.toInt() else 0xFF00FF00.toInt()
    }
    return manager.add(id, buffer)
}

fun renderTests() {
    section("software renderer")

    test("beginFrame clears to the clear colour") {
        val renderer = SoftwareRenderer(16, 8).topLeftCamera()
        renderer.beginFrame(Color(0.5f, 0f, 0f))
        val pixel = Color.fromArgb32(argb(renderer.captureFrame(), 0, 0))
        near(0.5f, pixel.r, 0.02f, "cleared pixel is half red")
        near(0f, pixel.g, 0.02f)
        renderer.endFrame()
    }

    test("filled and outlined rectangles") {
        val renderer = SoftwareRenderer(32, 32).topLeftCamera()
        renderer.beginFrame(Color.BLACK)
        renderer.drawRect(Rect(8f, 8f, 16f, 16f), Color.WHITE)
        renderer.endFrame()
        val frame = renderer.captureFrame()
        near(1f, Color.fromArgb32(frame[16, 16]).r, 0.02f, "inside the rect")
        near(0f, Color.fromArgb32(frame[2, 2]).r, 0.02f, "outside the rect")
        check(renderer.stats.drawCalls >= 1, "draw call counted")
    }

    test("sprites sample the source texture and respect flips") {
        val textures = TextureManager()
        val texture = solidTexture(textures, "test", 4)
        val region = texture.whole
        val renderer = SoftwareRenderer(16, 8).topLeftCamera()
        renderer.beginFrame(Color.BLACK)
        renderer.drawSprite(region, 0f, 0f, 16f, 8f)
        renderer.endFrame()
        val left = Color.fromArgb32(renderer.captureFrame()[2, 4])
        val right = Color.fromArgb32(renderer.captureFrame()[13, 4])
        near(1f, left.r, 0.05f, "left half is red")
        near(1f, right.g, 0.05f, "right half is green")

        renderer.beginFrame(Color.BLACK)
        renderer.drawSprite(region, 0f, 0f, 16f, 8f, flipX = true)
        renderer.endFrame()
        val flippedLeft = Color.fromArgb32(renderer.captureFrame()[2, 4])
        near(1f, flippedLeft.g, 0.05f, "flipping swaps the halves")
    }

    test("sprite tint multiplies the source") {
        val textures = TextureManager()
        val texture = solidTexture(textures, "tint", 4)
        val renderer = SoftwareRenderer(8, 8).topLeftCamera()
        renderer.beginFrame(Color.BLACK)
        renderer.drawSprite(texture.whole, 0f, 0f, 8f, 8f, tint = Color(0f, 0f, 1f, 1f))
        renderer.endFrame()
        val pixel = Color.fromArgb32(renderer.captureFrame()[1, 1])
        near(0f, pixel.r, 0.05f, "red channel removed by the blue tint")
        near(0f, pixel.g, 0.05f)
    }

    test("clipping restricts drawing to the clip rectangle") {
        val renderer = SoftwareRenderer(32, 32).topLeftCamera()
        renderer.beginFrame(Color.BLACK)
        renderer.pushClipScreen(Rect(0f, 0f, 16f, 16f))
        renderer.drawRect(Rect(0f, 0f, 32f, 32f), Color.WHITE)
        renderer.popClip()
        renderer.endFrame()
        val frame = renderer.captureFrame()
        near(1f, Color.fromArgb32(frame[8, 8]).r, 0.05f, "inside the clip")
        near(0f, Color.fromArgb32(frame[24, 24]).r, 0.05f, "outside the clip")
        check(renderer.stats.clippedPixels > 0, "clipped pixel statistic recorded")
    }

    test("transform stack moves subsequent draws") {
        val renderer = SoftwareRenderer(32, 32).topLeftCamera()
        renderer.beginFrame(Color.BLACK)
        renderer.pushTransform(16f, 16f, 0f, 1f, 1f, 0f, 0f)
        renderer.drawRect(Rect(0f, 0f, 8f, 8f), Color.WHITE)
        renderer.popTransform()
        renderer.endFrame()
        val frame = renderer.captureFrame()
        near(1f, Color.fromArgb32(frame[18, 18]).r, 0.05f, "rect drawn at the translated origin")
        near(0f, Color.fromArgb32(frame[4, 4]).r, 0.05f, "nothing drawn at the old origin")
    }

    test("text renders pixels through a bitmap font") {
        val textures = TextureManager()
        val fonts = FontManager(textures)
        val font = fonts.ensureFallback()
        check(font.glyph('A'.code) != null, "fallback font has glyphs")
        val renderer = SoftwareRenderer(64, 16).topLeftCamera()
        renderer.beginFrame(Color.BLACK)
        renderer.drawText(font, "SPRITE", 0f, 0f, 1f, Color.WHITE)
        renderer.endFrame()
        val frame = renderer.captureFrame()
        var lit = 0
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            if (Color.fromArgb32(frame[x, y]).r > 0.2f) lit++
        }
        check(lit > 10, "text produced $lit lit pixels")
        check(font.measureWidth("AB", 1f) > 0f, "text measurement works")
        eq(2, font.wrap("hello world", font.measureWidth("hello", 1f) + 1f).size, "wrap splits words")
    }

    test("overlay tints the whole frame") {
        val renderer = SoftwareRenderer(8, 8).topLeftCamera()
        renderer.beginFrame(Color.BLACK)
        renderer.drawOverlay(Color(1f, 0f, 0f, 0.5f))
        renderer.endFrame()
        val pixel = Color.fromArgb32(renderer.captureFrame()[4, 4])
        check(pixel.r > 0.4f, "overlay brightened the frame")
    }

    test("circle, line and polygon primitives draw") {
        val renderer = SoftwareRenderer(24, 24).topLeftCamera()
        renderer.beginFrame(Color.BLACK)
        renderer.drawCircle(12f, 12f, 8f, Color.WHITE)
        renderer.drawLine(0f, 0f, 23f, 23f, Color(0f, 1f, 0f, 1f), 1f)
        renderer.drawPolygon(floatArrayOf(2f, 2f, 20f, 2f, 11f, 6f), Color(0f, 0f, 1f, 1f))
        renderer.drawEllipse(12f, 12f, 4f, 2f, Color.WHITE, filled = false)
        renderer.drawArc(12f, 12f, 10f, 0f, 90f, Color.WHITE, 2f)
        renderer.endFrame()
        val frame = renderer.captureFrame()
        check(Color.fromArgb32(frame[9, 12]).r > 0.5f, "circle interior filled (the 45-degree line crosses the centre)")
        check(renderer.stats.drawCalls >= 5, "each primitive counted a draw call")
    }

    test("nine-patch drawing keeps the corners unscaled") {
        val textures = TextureManager()
        val buffer = PixelBuffer(12, 12)
        for (y in 0 until 12) for (x in 0 until 12) {
            buffer[x, y] = if (x < 4 && y < 4) 0xFFFF0000.toInt() else 0x8000FF00.toInt()
        }
        val texture = textures.add("panel", buffer)
        val renderer = SoftwareRenderer(48, 48).topLeftCamera()
        renderer.beginFrame(Color.BLACK)
        renderer.drawNinePatch(texture.whole, Rect(0f, 0f, 40f, 40f), Rect(4f, 4f, 4f, 4f))
        renderer.endFrame()
        val frame = renderer.captureFrame()
        check(Color.fromArgb32(frame[1, 1]).r > 0.4f, "top-left corner painted")
        check(renderer.stats.drawCalls > 0, "nine-patch issued draw calls")
    }

    section("pixel buffer")

    test("pixel buffer image operations") {
        val buffer = PixelBuffer(8, 8)
        for (y in 0 until 8) for (x in 0 until 8) buffer[x, y] = if (x < 4) 0xFFFF0000.toInt() else 0xFF00FF00.toInt()
        val cropped = buffer.crop(Rect(0f, 0f, 4f, 8f))
        eq(4, cropped.width)
        near(1f, Color.fromArgb32(cropped[2, 2]).r, 0.01f, "crop kept the red half")
        val scaled = buffer.scaled(16, 16)
        eq(16, scaled.width)
        val flipped = buffer.flippedHorizontally()
        near(1f, Color.fromArgb32(flipped[1, 1]).g, 0.01f, "flip mirrored the columns")
        val tinted = buffer.tinted(Color(1f, 0f, 0f, 1f))
        near(0f, Color.fromArgb32(tinted[6, 6]).g, 0.01f, "tint zeroed the green channel")
        val trimmed = buffer.trimmed()
        eq(Rect(0f, 0f, 8f, 8f), trimmed, "fully opaque buffer has no trim")
        check(buffer.palette(limit = 8).isNotEmpty(), "palette extraction")
        val outlined = buffer.outlined(0xFFFFFFFF.toInt(), 1)
        eq(8, outlined.width)
        val upscaled = buffer.upscaled(2)
        eq(16, upscaled.width)
    }

    test("PNG encode/decode round-trips including alpha") {
        val buffer = PixelBuffer(5, 3)
        for (y in 0 until 3) for (x in 0 until 5) {
            buffer[x, y] = when {
                x == 0 -> 0x00000000
                x == 1 -> 0xFFFF0000.toInt()
                x == 2 -> 0xFF00FF00.toInt()
                x == 3 -> 0xFF0000FF.toInt()
                else -> 0x80FFFFFF.toInt()
            }
        }
        val bytes = PngCodec.encode(buffer)
        check(PngCodec.isPng(bytes), "encoded output has a PNG signature")
        val decoded = PngCodec.decode(bytes)
        eq(buffer.width, decoded.width)
        eq(buffer.height, decoded.height)
        for (y in 0 until 3) for (x in 0 until 5) {
            eq(buffer[x, y], decoded[x, y], "pixel $x,$y survives the round trip")
        }
    }

    section("textures")

    test("texture manager loads, slices and unloads") {
        val textures = TextureManager()
        val buffer = PixelBuffer(16, 16)
        for (y in 0 until 16) for (x in 0 until 16) buffer[x, y] = 0xFF203040.toInt()
        val bytes = PngCodec.encode(buffer)
        textures.registerSource("sprites/hero.png", { bytes })
        val texture = textures.load("sprites/hero.png")
        eq(16, texture.width)
        eq(16, texture.height)
        eq(1, textures.count)

        val regions = texture.sliceGrid(8, 8)
        eq(4, regions.size, "2x2 grid")
        eq("tile_0", regions[0].name)
        near(8f, regions[3].rect.x, 0.001f)

        texture.defineRegion("head", 0, 0, 8, 4)
        eq("head", texture.region("head")?.name)
        texture.defineAnimation("idle", "tile_0", "tile_1")
        eq(2, texture.animations["idle"]?.size)

        textures.release("sprites/hero.png")
        eq(0, textures.count, "released texture is unloaded")
    }

    test("texture regions compute UVs and pivots") {
        val textures = TextureManager()
        val texture = solidTexture(textures, "uv", 8)
        val region = dev.lumen2d.core.render.TextureRegion(texture, 2, 2, 4, 4, "mid")
        near(0.25f, region.uvLeft, 0.001f)
        near(0.75f, region.uvRight, 0.001f)
        near(2f, region.rect.x, 0.001f)
        eq(8 * 8, texture.width * texture.height)
    }

    test("texture filters and wrap modes parse from strings") {
        eq(TextureFilter.NEAREST, TextureFilter.fromName("nearest"))
        eq(TextureFilter.LINEAR, TextureFilter.fromName("LINEAR"))
        eq(TextureFilter.NEAREST, TextureFilter.fromName("nonsense"))
    }

    section("camera")

    test("camera maps world space to screen space") {
        val renderer = SoftwareRenderer(320, 180)
        val camera = renderer.currentCamera()
        camera.position = Vec2(100f, 50f)
        camera.zoom = 2f
        camera.viewportWidth = 320f
        camera.viewportHeight = 180f
        val screen = camera.worldToScreen(Vec2(100f, 50f))
        near(160f, screen[0], 0.5f, "world centre lands in the middle of the viewport")
        near(90f, screen[1], 0.5f)
        val back = camera.screenToWorld(screen[0], screen[1])
        near(100f, back.x, 0.5f)
        near(50f, back.y, 0.5f)
        val visible = camera.visibleWorldRect()
        near(160f, visible.w, 1f, "zoom shrinks the visible area")
    }

    test("camera shake decays over time") {
        val camera = dev.lumen2d.core.render.Camera2D()
        camera.shake(6f, 0.3f)
        camera.update(0.016f, Vec2.ZERO)
        val firstOffset = camera.shakeOffset.length
        check(firstOffset > 0f, "shake displaces the camera")
        repeat(30) { camera.update(0.016f, Vec2.ZERO) }
        near(0f, camera.shakeOffset.length, 0.001f, "shake settles")
    }
}
