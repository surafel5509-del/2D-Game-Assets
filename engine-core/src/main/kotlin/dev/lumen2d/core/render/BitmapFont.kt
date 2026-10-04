/**
 * Lumen2D — bitmap fonts.
 *
 * Games and the editor's in-game UI render text through bitmap font atlases so that text
 * looks identical on every device and backend: the font is a PNG plus a JSON metrics file
 * produced by the asset pipeline (`assets-library/fonts/`), the same format BMFont emits.
 */
package dev.lumen2d.core.render

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.util.Json
import dev.lumen2d.core.util.int
import dev.lumen2d.core.util.mapList
import dev.lumen2d.core.util.str

/** A single glyph: its atlas region plus layout metrics. */
class Glyph(
    val codePoint: Int,
    val region: TextureRegion,
    val advance: Float,
    val bearingX: Float,
    val bearingY: Float,
) {
    val width: Float get() = region.width.toFloat()
    val height: Float get() = region.height.toFloat()
}

enum class TextAlign { LEFT, CENTER, RIGHT }
enum class TextVAlign { TOP, MIDDLE, BOTTOM }
/** Bit flags: 0=none, 1=shadow, 2=outline — combined as needed. */
object TextEffect {
    const val NONE = 0
    const val SHADOW = 1
    const val OUTLINE = 2
}

/**
 * A loaded bitmap font. Rendering is delegated through [drawGlyph] so the same font can be
 * drawn by the software rasterizer, Android Canvas, or any future backend.
 */
class BitmapFont(
    val name: String,
    val texture: Texture2D,
    val lineHeight: Float,
    val baseLine: Float,
    val sizePx: Float,
    private val glyphs: HashMap<Int, Glyph>,
    private val kerning: HashMap<Long, Float> = HashMap(),
) {
    var letterSpacing: Float = 0f
    var spaceAdvance: Float = glyphs[32]?.advance ?: (sizePx * 0.4f)

    fun glyph(codePoint: Int): Glyph? = glyphs[codePoint]

    fun hasGlyph(cp: Int) = glyphs.containsKey(cp)

    /** Horizontal advance for a code point, including kerning against the previous one. */
    fun advanceOf(cp: Int, previous: Int = -1): Float {
        val g = glyphs[cp]
        var a = g?.advance ?: spaceAdvance
        if (previous >= 0) a += kerning[(previous.toLong() shl 32) or cp.toLong()] ?: 0f
        return a + letterSpacing
    }

    fun measureWidth(text: String, scale: Float = 1f): Float {
        var w = 0f
        var prev = -1
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp == '\n'.code) { prev = -1; i++; continue }
            w += advanceOf(cp, prev)
            prev = cp
            i += Character.charCount(cp)
        }
        return w * scale
    }

    fun measureLineHeight(scale: Float = 1f): Float = lineHeight * scale

    fun measureHeight(text: String, scale: Float = 1f): Float {
        var lines = 1
        for (c in text) if (c == '\n') lines++
        return lines * lineHeight * scale
    }

    /** Greedy word wrap; respects explicit newlines. */
    fun wrap(text: String, maxWidth: Float, scale: Float = 1f): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) { out.add(""); continue }
            var line = StringBuilder()
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "${line} $word"
                if (measureWidth(candidate, scale) > maxWidth && line.isNotEmpty()) {
                    out.add(line.toString()); line = StringBuilder(word)
                } else {
                    if (line.isNotEmpty()) line.append(' ')
                    line.append(word)
                }
            }
            out.add(line.toString())
        }
        return out
    }

    /**
     * Lays out [text] and emits glyph quads through [drawGlyph].
     * @param drawGlyph receives (region, x, y, width, height) in the caller's space.
     */
    fun draw(
        text: String,
        x: Float, y: Float,
        scale: Float = 1f,
        color: Color = Color.WHITE,
        align: TextAlign = TextAlign.LEFT,
        vAlign: TextVAlign = TextVAlign.TOP,
        maxWidth: Float = 0f,
        effect: Int = TextEffect.NONE,
        shadowColor: Color = Color(0f, 0f, 0f, 0.6f),
        outlineColor: Color = Color.BLACK,
        lineSpacing: Float = 0f,
        drawGlyph: (TextureRegion, x: Float, y: Float, w: Float, h: Float, tint: Color) -> Unit,
    ): Float {
        val lines = if (maxWidth > 0f) wrap(text, maxWidth, scale) else text.split('\n')
        val totalHeight = lines.size * (lineHeight + lineSpacing) * scale
        val startY = when (vAlign) {
            TextVAlign.TOP -> y
            TextVAlign.MIDDLE -> y - totalHeight / 2f
            TextVAlign.BOTTOM -> y - totalHeight
        }
        var penY = startY
        for (line in lines) {
            val lineWidth = measureWidth(line, scale)
            var penX = when (align) {
                TextAlign.LEFT -> x
                TextAlign.CENTER -> x - lineWidth / 2f
                TextAlign.RIGHT -> x - lineWidth
            }
            var prev = -1
            var i = 0
            while (i < line.length) {
                val cp = line.codePointAt(i)
                val g = glyphs[cp]
                if (g != null) {
                    val gw = g.width * scale
                    val gh = g.height * scale
                    val gx = penX + g.bearingX * scale
                    val gy = penY + (baseLine - g.bearingY) * scale
                    if (effect and TextEffect.SHADOW != 0) {
                        drawGlyph(g.region, gx + scale, gy + scale, gw, gh, shadowColor)
                    }
                    if (effect and TextEffect.OUTLINE != 0) {
                        for (ox in -1..1) for (oy in -1..1) {
                            if (ox == 0 && oy == 0) continue
                            drawGlyph(g.region, gx + ox * scale, gy + oy * scale, gw, gh, outlineColor)
                        }
                    }
                    drawGlyph(g.region, gx, gy, gw, gh, color)
                }
                penX += advanceOf(cp, prev) * scale
                prev = cp
                i += Character.charCount(cp)
            }
            penY += (lineHeight + lineSpacing) * scale
        }
        return totalHeight
    }

    /** Bounding box of a rendered string — used by the editor's text tool and UI hit-testing. */
    fun bounds(text: String, x: Float, y: Float, scale: Float = 1f, maxWidth: Float = 0f): Rect {
        val lines = if (maxWidth > 0f) wrap(text, maxWidth, scale) else text.split('\n')
        val w = lines.maxOfOrNull { measureWidth(it, scale) } ?: 0f
        return Rect(x, y, w, lines.size * lineHeight * scale)
    }

    override fun toString() = "BitmapFont($name ${sizePx}px glyphs=${glyphs.size})"

    // ------------------------------------------------------------------ serialization

    fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "texture" to texture.id,
        "size" to sizePx,
        "lineHeight" to lineHeight,
        "baseLine" to baseLine,
        "glyphs" to glyphs.values.sortedBy { it.codePoint }.map { g ->
            linkedMapOf(
                "code" to g.codePoint,
                "x" to g.region.x, "y" to g.region.y,
                "width" to g.region.width, "height" to g.region.height,
                "advance" to g.advance.toDouble(),
                "bearingX" to g.bearingX.toDouble(),
                "bearingY" to g.bearingY.toDouble(),
            )
        },
        "kerning" to if (kerning.isEmpty()) emptyList<Any>() else kerning.entries.mapNotNull { (k, v) ->
            val first = (k shr 32).toInt(); val second = (k and 0xFFFFFFFFL).toInt()
            if (first == 0 && second == 0) null else linkedMapOf("first" to first, "second" to second, "amount" to v.toDouble())
        },
    )

    companion object {
        /** Loads a BMFont-style JSON + texture pair through [textures]. */
        fun load(name: String, jsonText: String, textures: TextureManager): BitmapFont {
            val root = Json.parseObject(jsonText)
            val textureId = root.str("texture", "$name.png")
            val texture = textures.load(textureId)
            val sizePx = root["size"]?.let { (it as Number).toFloat() } ?: 16f
            val lineHeight = root["lineHeight"]?.let { (it as Number).toFloat() } ?: (sizePx * 1.25f)
            val baseLine = root["baseLine"]?.let { (it as Number).toFloat() } ?: (sizePx * 0.8f)
            val glyphs = HashMap<Int, Glyph>(256)
            for (g in root.mapList("glyphs")) {
                val code = g.int("code", 63)
                val x = g.int("x"); val y = g.int("y")
                val w = g.int("width"); val h = g.int("height")
                val region = texture.defineRegion("glyph_$code", x, y, w, h)
                glyphs[code] = Glyph(
                    codePoint = code,
                    region = region,
                    advance = g["advance"]?.let { (it as Number).toFloat() } ?: w.toFloat(),
                    bearingX = g["bearingX"]?.let { (it as Number).toFloat() } ?: 0f,
                    bearingY = g["bearingY"]?.let { (it as Number).toFloat() } ?: h.toFloat(),
                )
            }
            val kerning = HashMap<Long, Float>()
            for (k in root.mapList("kerning")) {
                kerning[(k.int("first").toLong() shl 32) or (k.int("second").toLong() and 0xFFFFFFFFL)] =
                    k["amount"]?.let { (it as Number).toFloat() } ?: 0f
            }
            return BitmapFont(name, texture, lineHeight, baseLine, sizePx, glyphs, kerning)
        }

        /**
         * Builds a font from explicit glyph regions: the built-in 5x7 pixel font, generated
         * atlases and importers that already know each glyph box.
         */
        fun fromRegions(
            name: String,
            texture: Texture2D,
            sizePx: Float,
            lineHeight: Float,
            baseLine: Float,
            entries: List<Pair<Int, TextureRegion>>,
            advance: Float,
        ): BitmapFont {
            val glyphs = HashMap<Int, Glyph>(entries.size * 2)
            for ((code, region) in entries) {
                glyphs[code] = Glyph(code, region, advance, 0f, region.height.toFloat())
            }
            return BitmapFont(name, texture, lineHeight, baseLine, sizePx, glyphs)
        }

        /**
         * Builds a font from a fixed-width pixel grid — used for the engine's built-in
         * fallback font and for tests that must not depend on shipped assets.
         */
        fun fromGrid(
            name: String,
            texture: Texture2D,
            cellWidth: Int, cellHeight: Int,
            firstCode: Int = 32,
            lastCode: Int = 126,
            columns: Int = 16,
        ): BitmapFont {
            val glyphs = HashMap<Int, Glyph>(lastCode - firstCode + 1)
            for (code in firstCode..lastCode) {
                val index = code - firstCode
                val cx = (index % columns) * cellWidth
                val cy = (index / columns) * cellHeight
                if (cx + cellWidth > texture.width || cy + cellHeight > texture.height) break
                val region = texture.defineRegion("glyph_$code", cx, cy, cellWidth, cellHeight)
                glyphs[code] = Glyph(code, region, cellWidth.toFloat(), 0f, cellHeight.toFloat())
            }
            return BitmapFont(name, texture, cellHeight + 2f, cellHeight.toFloat(), cellHeight.toFloat(), glyphs)
        }
    }
}

/** Font library: keeps loaded fonts by name and provides the engine default. */
class FontManager(private val textures: TextureManager) {
    private val fonts = LinkedHashMap<String, BitmapFont>()
    var defaultFontName: String = "pixel6"

    val default: BitmapFont? get() = fonts[defaultFontName] ?: fonts.values.firstOrNull()

    fun add(font: BitmapFont) { fonts[font.name] = font }
    fun getOrNull(name: String): BitmapFont? = fonts[name]
    fun get(name: String?): BitmapFont? = (name?.let { fonts[it] }) ?: default
    fun all(): Collection<BitmapFont> = fonts.values
    fun load(name: String, jsonText: String): BitmapFont =
        BitmapFont.load(name, jsonText, textures).also { add(it) }
    fun clear() = fonts.clear()

    /**
     * Ensures at least one font exists: the engine's built-in 5x7 pixel font (see [PixelFont5x7]).
     * Imported `*.font.json` assets replace it as soon as they are loaded.
     */
    fun ensureFallback(): BitmapFont {
        default?.let { return it }
        val font = buildPixelFont(textures)
        fallbackFont = font
        add(font)
        defaultFontName = font.name
        return font
    }

    /** The built-in font, or null when a project supplied its own fonts. */
    var fallbackFont: BitmapFont? = null
        private set

    companion object {
        const val PIXEL_FONT_NAME = "pixel8"

        /**
         * Rasterises [PixelFont5x7] into a texture and returns a ready-to-use [BitmapFont].
         * Called by [ensureFallback] and by the asset-library generator, which writes the same
         * atlas to disk as `font.png` + `font.font.json`.
         */
        fun buildPixelFont(textures: TextureManager): BitmapFont {
            val textureId = "$PIXEL_FONT_NAME.png"
            val texture = textures.getOrNull(textureId) ?: textures.add(textureId, PixelFont5x7.buildAtlas())
            val entries = PixelFont5x7.characters.mapIndexed { index, glyph ->
                val cx = (index % PixelFont5x7.COLUMNS) * PixelFont5x7.CELL_WIDTH
                val cy = (index / PixelFont5x7.COLUMNS) * PixelFont5x7.CELL_HEIGHT
                glyph.code to texture.defineRegion(
                    "glyph_${glyph.code}", cx, cy, PixelFont5x7.GLYPH_WIDTH, PixelFont5x7.GLYPH_HEIGHT,
                )
            }
            return BitmapFont.fromRegions(
                name = PIXEL_FONT_NAME,
                texture = texture,
                sizePx = PixelFont5x7.SIZE,
                lineHeight = PixelFont5x7.LINE_HEIGHT,
                baseLine = PixelFont5x7.BASE_LINE,
                entries = entries,
                advance = PixelFont5x7.ADVANCE,
            )
        }
    }
}
