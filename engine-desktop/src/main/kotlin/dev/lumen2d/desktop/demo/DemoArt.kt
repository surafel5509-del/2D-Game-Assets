/**
 * Lumen2D — procedural demo art.
 *
 * The engine's documentation previews must render without shipping binary assets (the repo keeps
 * generated art out of Git), so the demo scenes art-direct themselves: every sprite below is
 * painted with [PixelBuffer] at boot and registered with the [dev.lumen2d.core.render.TextureManager].
 *
 * This doubles as a practical example of the drawing helpers
 * ([PixelBuffer.replaceColor], `outlined`, `palette`, ...) that the asset editor exposes.
 */
package dev.lumen2d.desktop.demo

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.render.PixelBuffer
import dev.lumen2d.core.render.TextureManager

/** Named palette shared by every demo scene (a chunky, readable 16-colour set). */
object DemoPalette {
    const val SKY_TOP = 0xFF1B2A4A.toInt()
    const val SKY_BOTTOM = 0xFF3C5A8C.toInt()
    const val HILL_FAR = 0xFF2E4A6B.toInt()
    const val HILL_NEAR = 0xFF24405C.toInt()
    const val GRASS = 0xFF4CAF50.toInt()
    const val GRASS_DARK = 0xFF2E7D32.toInt()
    const val DIRT = 0xFF7A5230.toInt()
    const val DIRT_DARK = 0xFF5A3A22.toInt()
    const val STONE = 0xFF7E8CA0.toInt()
    const val STONE_DARK = 0xFF5A6577.toInt()
    const val SKIN = 0xFFFFC9A3.toInt()
    const val SHIRT = 0xFF4C6EF5.toInt()
    const val PANTS = 0xFF2B2D42.toInt()
    const val COIN = 0xFFFFD166.toInt()
    const val COIN_DARK = 0xFFE0A82E.toInt()
    const val OUTLINE = 0xFF141721.toInt()
    const val UI_BG = 0xFF1B2033.toInt()
    const val UI_ACCENT = 0xFF6C8CFF.toInt()
    const val UI_TEXT = 0xFFE8ECFF.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()
}

/**
 * Paints and registers every demo texture.
 *
 * Texture ids are deliberately simple file-like names (`player.png`, `tiles.png`) because that is
 * how projects reference imported art; the demo just skips the disk.
 */
object DemoArt {

    const val TILES = "tiles.png"
    const val PLAYER = "player.png"
    const val COIN = "coin.png"
    const val ENEMY = "slime.png"
    const val PARTICLES = "spark.png"
    const val BG_HILLS = "hills.png"
    const val CHECKPOINT = "flag.png"
    const val COIN_ICON = "coin_icon.png"

    /**
     * Registers every texture into [textures]; returns the number created.
     * [width]/[height] size the full-screen sky gradient the demos use as a backdrop.
     */
    fun install(textures: TextureManager, width: Int = 480, height: Int = 270): Int {
        var count = 0
        val assets = mapOf(
            "sky.png" to { skyGradient(width, height) },
            TILES to { tileSet() },
            PLAYER to { playerSheet() },
            COIN to { coinSheet() },
            ENEMY to { slimeSheet() },
            PARTICLES to { spark() },
            BG_HILLS to { hills() },
            CHECKPOINT to { flag() },
            COIN_ICON to { coinIcon() },
        )
        for ((id, builder) in assets) {
            if (textures.getOrNull(id) != null) continue
            textures.add(id, builder())
            count++
        }
        return count
    }

    // ------------------------------------------------------------------ tiles (4x3 grid of 16px)

    /** 4 columns x 3 rows of 16x16 tiles: grass, dirt, stone, platforms, spikes, decor. */
    fun tileSet(): PixelBuffer {
        val buffer = PixelBuffer(64, 48)
        paintTile(buffer, 0, 0) { x, y -> tileTop(x, y, DemoPalette.GRASS, DemoPalette.GRASS_DARK, DemoPalette.DIRT) }
        paintTile(buffer, 1, 0) { x, y -> tileTop(x, y, DemoPalette.DIRT, DemoPalette.DIRT_DARK, DemoPalette.DIRT) }
        paintTile(buffer, 2, 0) { x, y -> stoneTile(x, y) }
        paintTile(buffer, 3, 0) { x, y -> platformTile(x, y) }

        paintTile(buffer, 0, 1) { x, y -> tileTop(x, y, DemoPalette.GRASS, DemoPalette.GRASS_DARK, DemoPalette.DIRT, capHeight = 3) }
        paintTile(buffer, 1, 1) { x, y -> stoneTile(x, y, darker = true) }
        paintTile(buffer, 2, 1) { x, y -> spikesTile(x, y) }
        paintTile(buffer, 3, 1) { x, y -> ladderTile(x, y) }

        paintTile(buffer, 0, 2) { x, y -> waterTile(x, y) }
        paintTile(buffer, 1, 2) { x, y -> bushTile(x, y) }
        paintTile(buffer, 2, 2) { x, y -> cloudTile(x, y) }
        paintTile(buffer, 3, 2) { x, y -> gemTile(x, y) }
        return buffer
    }

    private fun paintTile(buffer: PixelBuffer, column: Int, row: Int, painter: (Int, Int) -> Int) {
        val ox = column * 16
        val oy = row * 16
        for (y in 0 until 16) for (x in 0 until 16) buffer[ox + x, oy + y] = painter(x, y)
    }

    private fun tileTop(x: Int, y: Int, capColor: Int, capHighlight: Int, body: Int, capHeight: Int = 5): Int = when {
        y < capHeight -> if ((x + y) % 5 == 0) capHighlight else capColor
        y == capHeight -> DemoPalette.DIRT_DARK
        (x / 4 + y / 4) % 2 == 0 -> body
        else -> darken(body, 0.85f)
    }

    private fun stoneTile(x: Int, y: Int, darker: Boolean = false): Int {
        val base = if (darker) DemoPalette.STONE_DARK else DemoPalette.STONE
        val mortar = darken(base, 0.62f)
        return when {
            y % 8 == 0 -> mortar
            (x + (if (y / 8 % 2 == 0) 0 else 8)) % 16 == 0 -> mortar
            (x + y) % 7 == 0 -> darken(base, 0.9f)
            else -> base
        }
    }

    private fun platformTile(x: Int, y: Int): Int = when {
        y < 4 -> DemoPalette.DIRT_DARK
        y < 6 -> DemoPalette.DIRT
        y == 6 -> darken(DemoPalette.DIRT, 0.7f)
        else -> 0
    }

    private fun spikesTile(x: Int, y: Int): Int {
        val spike = (x % 8) in 3..5 && y > 6 - (x % 8).let { kotlin.math.abs(it - 4) } * 2
        return if (spike) DemoPalette.STONE else 0
    }

    private fun ladderTile(x: Int, y: Int): Int = when {
        x in 2..3 || x in 12..13 -> DemoPalette.DIRT
        y % 5 == 0 -> DemoPalette.DIRT_DARK
        else -> 0
    }

    private fun waterTile(x: Int, y: Int): Int = when {
        y < 3 -> 0
        (x + y) % 6 == 0 -> 0xFF63C7FF.toInt()
        else -> 0xFF2F80ED.toInt()
    }

    private fun bushTile(x: Int, y: Int): Int = when {
        y < 5 -> 0
        (x * x + (y - 10) * (y - 10)) < 40 -> DemoPalette.GRASS_DARK
        (x * x + (y - 10) * (y - 10)) < 52 -> DemoPalette.GRASS
        else -> 0
    }

    private fun cloudTile(x: Int, y: Int): Int = when {
        (x * x + (y - 8) * (y - 8)) < 42 -> 0xFFF4F7FF.toInt()
        (x * x + (y - 8) * (y - 8)) < 52 -> 0xFFC7D3F0.toInt()
        else -> 0
    }

    private fun gemTile(x: Int, y: Int): Int {
        val dx = kotlin.math.abs(x - 7.5f)
        val dy = kotlin.math.abs(y - 8f)
        return when {
            dx + dy < 3f -> 0xFFFFFFFF.toInt()
            dx + dy < 7f -> 0xFF7BF1FF.toInt()
            dx + dy < 9f -> 0xFF3A86FF.toInt()
            else -> 0
        }
    }

    // ------------------------------------------------------------------------ characters

    /** Player sprite sheet: 4 idle/run frames of 16x20 on a 3x2 grid of cells. */
    fun playerSheet(): PixelBuffer {
        val cellWidth = 16
        val cellHeight = 20
        val buffer = PixelBuffer(cellWidth * 4, cellHeight * 2)
        for (frame in 0 until 4) {
            val run = frame > 0
            drawPlayer(buffer, frame * cellWidth, 0, legOffset = if (run) (frame % 2) * 2 - 1 else 0, armsUp = false)
            drawPlayer(buffer, frame * cellWidth, cellHeight, legOffset = frame % 2, armsUp = true)
        }
        return buffer
    }

    private fun drawPlayer(buffer: PixelBuffer, ox: Int, oy: Int, legOffset: Int, armsUp: Boolean) {
        fun px(x: Int, y: Int, color: Int) { if (x in 0 until 16 && y in 0 until 20) buffer[ox + x, oy + y] = color }
        // head + hair
        for (y in 1..6) for (x in 4..11) px(x, y, DemoPalette.SKIN)
        for (y in 0..3) for (x in 4..11) px(x, y, 0xFF3E2723.toInt())
        px(6, 4, DemoPalette.OUTLINE); px(9, 4, DemoPalette.OUTLINE)
        // body
        for (y in 7..14) for (x in 4..11) px(x, y, DemoPalette.SHIRT)
        // arms
        if (armsUp) {
            for (y in 4..8) { px(2, y, DemoPalette.SKIN); px(13, y, DemoPalette.SKIN) }
        } else {
            for (y in 8..12) { px(2, y, DemoPalette.SKIN); px(13, y, DemoPalette.SKIN) }
        }
        // legs
        for (y in 15..18) {
            px(5 + legOffset, y, DemoPalette.PANTS)
            px(10 - legOffset, y, DemoPalette.PANTS)
        }
        px(5 + legOffset, 19, DemoPalette.OUTLINE)
        px(10 - legOffset, 19, DemoPalette.OUTLINE)
        // outline the silhouette for readability at small sizes
        outline(buffer, ox, oy, 16, 20, DemoPalette.OUTLINE)
    }

    /** Slime enemy: 4 squash/stretch frames of 16x16. */
    fun slimeSheet(): PixelBuffer {
        val buffer = PixelBuffer(16 * 4, 16)
        for (frame in 0 until 4) {
            val squash = frame % 2
            val ox = frame * 16
            for (y in 0 until 16) for (x in 0 until 16) {
                val cx = x - 7.5f
                val cy = y - 9f
                val rx = 6f + squash
                val ry = 5f - squash * 0.5f
                if ((cx * cx) / (rx * rx) + (cy * cy) / (ry * ry) <= 1f) {
                    val highlight = (cx < -1f && cy < -1f)
                    buffer[ox + x, y] = if (highlight) 0xFF9AE66E.toInt() else 0xFF5ABA3C.toInt()
                }
            }
            // eyes
            buffer[ox + 5, 7] = DemoPalette.OUTLINE
            buffer[ox + 10, 7] = DemoPalette.OUTLINE
            outline(buffer, ox, 0, 16, 16, DemoPalette.OUTLINE)
        }
        return buffer
    }

    /** Coin pickup: 4 spin frames of 12x12. */
    fun coinSheet(): PixelBuffer {
        val buffer = PixelBuffer(12 * 4, 12)
        for (frame in 0 until 4) {
            val ox = frame * 12
            val width = 5f - frame
            for (y in 0 until 12) for (x in 0 until 12) {
                val cx = (x - 5.5f) / (5f)
                val cy = (y - 5.5f) / 5f
                val shape = cx * cx + cy * cy
                if (shape <= 1f) {
                    val rim = shape > 0.55f
                    val narrow = kotlin.math.abs(x - 5.5f) > width
                    if (!narrow) buffer[ox + x, y] = if (rim) DemoPalette.COIN_DARK else DemoPalette.COIN
                }
            }
            outline(buffer, ox, 0, 12, 12, DemoPalette.OUTLINE)
        }
        return buffer
    }

    /** Single-frame coin for HUD bars (the sheet above has four spin frames). */
    fun coinIcon(): PixelBuffer {
        val buffer = PixelBuffer(12, 12)
        for (y in 0 until 12) for (x in 0 until 12) {
            val dx = (x - 5.5f) / 5f
            val dy = (y - 5.5f) / 5f
            val shape = dx * dx + dy * dy
            if (shape <= 1f) buffer[x, y] = if (shape > 0.55f) DemoPalette.COIN_DARK else DemoPalette.COIN
        }
        outline(buffer, 0, 0, 12, 12, DemoPalette.OUTLINE)
        return buffer
    }

    /** Goal flag: 12x20. */
    fun flag(): PixelBuffer {
        val buffer = PixelBuffer(12, 20)
        for (y in 0 until 20) buffer[2, y] = DemoPalette.STONE_DARK
        for (y in 2..9) for (x in 3..10) {
            buffer[x, y] = if ((x + y) % 4 < 2) DemoPalette.DANGER else 0xFFF1F3FF.toInt()
        }
        outline(buffer, 0, 0, 12, 20, DemoPalette.OUTLINE)
        return buffer
    }

    /** Soft round particle (radial gradient with alpha). */
    fun spark(): PixelBuffer {
        val buffer = PixelBuffer(8, 8)
        for (y in 0 until 8) for (x in 0 until 8) {
            val dx = x - 3.5f
            val dy = y - 3.5f
            val d = kotlin.math.sqrt(dx * dx + dy * dy) / 3.4f
            if (d <= 1f) {
                val alpha = ((1f - d) * 255f).toInt().coerceIn(0, 255)
                buffer[x, y] = (alpha shl 24) or 0xFFFFFF
            }
        }
        return buffer
    }

    /** Seamless-ish parallax hills strip: 256x80. */
    fun hills(): PixelBuffer {
        val buffer = PixelBuffer(256, 80)
        for (x in 0 until 256) {
            val far = (40 + 14 * kotlin.math.sin(x * 0.045) + 6 * kotlin.math.sin(x * 0.11)).toInt()
            val near = (56 + 18 * kotlin.math.sin(x * 0.031 + 1.7) + 8 * kotlin.math.sin(x * 0.083)).toInt()
            for (y in 0 until 80) {
                buffer[x, y] = when {
                    y > near -> DemoPalette.HILL_NEAR
                    y > far -> DemoPalette.HILL_FAR
                    else -> 0
                }
            }
        }
        return buffer
    }

    /** Sky gradient used as a full-screen backdrop by the platformer demo. */
    fun skyGradient(width: Int, height: Int): PixelBuffer {
        val buffer = PixelBuffer(width, height)
        for (y in 0 until height) {
            val t = y.toFloat() / (height - 1).coerceAtLeast(1)
            val color = lerpArgb(DemoPalette.SKY_TOP, DemoPalette.SKY_BOTTOM, t)
            for (x in 0 until width) buffer[x, y] = color
        }
        // a few soft stars
        var seed = 12345
        for (i in 0 until width / 8) {
            seed = seed * 1103515245 + 12345
            val x = (seed ushr 16) % width
            val y = (seed ushr 8) % (height / 3)
            buffer[x, y] = 0x66FFFFFF
        }
        return buffer
    }

    // ------------------------------------------------------------------------- helpers

    private fun outline(buffer: PixelBuffer, ox: Int, oy: Int, width: Int, height: Int, color: Int) {
        val snapshot = buffer.copy()
        for (y in 0 until height) for (x in 0 until width) {
            val cx = ox + x
            val cy = oy + y
            if (((snapshot[cx, cy] ushr 24) and 0xFF) != 0) continue
            val touching = ((snapshot[cx - 1, cy] ushr 24) and 0xFF) != 0 ||
                ((snapshot[cx + 1, cy] ushr 24) and 0xFF) != 0 ||
                ((snapshot[cx, cy - 1] ushr 24) and 0xFF) != 0 ||
                ((snapshot[cx, cy + 1] ushr 24) and 0xFF) != 0
            if (touching) buffer[cx, cy] = color
        }
    }

    private fun darken(argb: Int, factor: Float): Int {
        val r = (((argb shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((argb shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerpArgb(from: Int, to: Int, t: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** Convenience for demo scenes: converts engine colours into ARGB ints. */
    fun argb(color: Color): Int = color.toArgb32()
}
