/**
 * Lumen2D — images, textures and atlas support.
 *
 * Contains a dependency-free PNG codec (java.util.zip is available on Android and the JVM),
 * the [PixelBuffer] image-processing toolbox used by the editor's asset tools, and the
 * runtime texture/atlas data model consumed by every renderer backend.
 */
package dev.lumen2d.core.render

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.MathUtil
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.util.Log
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

// ------------------------------------------------------------------------ pixel buffer

/** A CPU-side ARGB image. Engine asset tools and the software renderer operate on these. */
class PixelBuffer(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {

    val isEmpty: Boolean get() = width <= 0 || height <= 0

    operator fun get(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) 0 else pixels[y * width + x]

    operator fun set(x: Int, y: Int, argb: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        pixels[y * width + x] = argb
    }

    /** Bilinear-aware sample that clamps outside the edge (used for scaling and soft brushes). */
    fun sampleBilinear(fx: Float, fy: Float): Int {
        val x0 = kotlin.math.floor(fx - 0.5f).toInt(); val y0 = kotlin.math.floor(fy - 0.5f).toInt()
        val tx = fx - 0.5f - x0; val ty = fy - 0.5f - y0
        val c00 = this[x0, y0]; val c10 = this[x0 + 1, y0]
        val c01 = this[x0, y0 + 1]; val c11 = this[x0 + 1, y0 + 1]
        fun ch(c: Int, shift: Int) = ((c shr shift) and 0xFF) * (1 - tx) * (1 - ty) +
            ((c10 shr shift) and 0xFF) * tx * (1 - ty) +
            ((c01 shr shift) and 0xFF) * (1 - tx) * ty +
            ((c11 shr shift) and 0xFF) * tx * ty
        val a = ch(c00, 24).toInt().coerceIn(0, 255)
        val r = ch(c00, 16).toInt().coerceIn(0, 255)
        val g = ch(c00, 8).toInt().coerceIn(0, 255)
        val b = ch(c00, 0).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun copy(): PixelBuffer = PixelBuffer(width, height, pixels.copyOf())

    fun fill(argb: Int) { java.util.Arrays.fill(pixels, argb) }

    fun clear() = fill(0)

    fun crop(rect: Rect): PixelBuffer {
        val x0 = rect.x.toInt().coerceIn(0, width); val y0 = rect.y.toInt().coerceIn(0, height)
        val w = rect.w.toInt().coerceIn(1, width - x0); val h = rect.h.toInt().coerceIn(1, height - y0)
        val out = PixelBuffer(w, h)
        for (y in 0 until h) System.arraycopy(pixels, (y0 + y) * width + x0, out.pixels, y * w, w)
        return out
    }

    fun scaled(newWidth: Int, newHeight: Int, linear: Boolean = false): PixelBuffer {
        val out = PixelBuffer(newWidth, newHeight)
        val sx = width.toFloat() / newWidth; val sy = height.toFloat() / newHeight
        for (y in 0 until newHeight) {
            for (x in 0 until newWidth) {
                out.pixels[y * newWidth + x] = if (linear) sampleBilinear((x + 0.5f) * sx, (y + 0.5f) * sy)
                else this[(x * sx).toInt().coerceIn(0, width - 1), (y * sy).toInt().coerceIn(0, height - 1)]
            }
        }
        return out
    }

    /** Nearest-neighbour integer scale (pixel-art upscaling). */
    fun upscaled(factor: Int): PixelBuffer {
        if (factor <= 1) return copy()
        val out = PixelBuffer(width * factor, height * factor)
        for (y in 0 until height) for (x in 0 until width) {
            val c = this[x, y]
            for (dy in 0 until factor) for (dx in 0 until factor)
                out.pixels[(y * factor + dy) * out.width + x * factor + dx] = c
        }
        return out
    }

    fun flippedHorizontally(): PixelBuffer {
        val out = PixelBuffer(width, height)
        for (y in 0 until height) for (x in 0 until width) out.pixels[y * width + x] = this[width - 1 - x, y]
        return out
    }

    fun flippedVertically(): PixelBuffer {
        val out = PixelBuffer(width, height)
        for (y in 0 until height) for (x in 0 until width) out.pixels[y * width + x] = this[x, height - 1 - y]
        return out
    }

    fun rotated90(clockwise: Boolean): PixelBuffer {
        val out = PixelBuffer(height, width)
        for (y in 0 until height) for (x in 0 until width) {
            val c = this[x, y]
            if (clockwise) out[height - 1 - y, x] = c else out[y, width - 1 - x] = c
        }
        return out
    }

    /** Multiplies every channel by [tint] (alpha aware). */
    fun tinted(tint: Color): PixelBuffer {
        val out = PixelBuffer(width, height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            if (a == 0) continue
            val r = (((c shr 16) and 0xFF) * tint.r).toInt().coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) * tint.g).toInt().coerceIn(0, 255)
            val b = ((c and 0xFF) * tint.b).toInt().coerceIn(0, 255)
            out.pixels[i] = (((a * tint.a).toInt().coerceIn(0, 255)) shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /** Replaces every pixel equal to [from] with [to] — the palette-swap tool in the asset editor. */
    fun replaceColor(from: Int, to: Int, tolerance: Int = 0): PixelBuffer {
        val out = copy()
        fun close(a: Int, b: Int): Boolean {
            if (tolerance == 0) return a == b
            return kotlin.math.abs(((a shr 24) and 0xFF) - ((b shr 24) and 0xFF)) <= tolerance &&
                kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tolerance &&
                kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tolerance &&
                kotlin.math.abs((a and 0xFF) - (b and 0xFF)) <= tolerance
        }
        for (i in out.pixels.indices) if (close(out.pixels[i], from)) out.pixels[i] = to
        return out
    }

    /** Builds a 1px silhouette — handy for shadows, flashes and hit effects. */
    fun silhouetted(color: Int): PixelBuffer {
        val out = PixelBuffer(width, height)
        for (i in pixels.indices) {
            val a = (pixels[i] ushr 24) and 0xFF
            if (a > 0) out.pixels[i] = (a shl 24) or (color and 0xFFFFFF)
        }
        return out
    }

    /** Adds a hard 1px outline around opaque pixels (asset-editor "outline" tool). */
    fun outlined(color: Int, thickness: Int = 1): PixelBuffer {
        val out = PixelBuffer(width, height, pixels.copyOf())
        for (y in 0 until height) for (x in 0 until width) {
            if (((this[x, y] ushr 24) and 0xFF) != 0) continue
            var touching = false
            for (d in 1..thickness) {
                if (((this[x - d, y] ushr 24) and 0xFF) != 0 || ((this[x + d, y] ushr 24) and 0xFF) != 0 ||
                    ((this[x, y - d] ushr 24) and 0xFF) != 0 || ((this[x, y + d] ushr 24) and 0xFF) != 0) {
                    touching = true; break
                }
            }
            if (touching) out[x, y] = color
        }
        return out
    }

    /** Autocrops fully transparent borders — used when slicing sprite sheets. */
    fun trimmed(alphaThreshold: Int = 8): Rect {
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (((this[x, y] ushr 24) and 0xFF) > alphaThreshold) {
                if (x < minX) minX = x; if (y < minY) minY = y
                if (x > maxX) maxX = x; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return Rect(0f, 0f, 0f, 0f)
        return Rect.fromLTRB(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
    }

    /** Unique colors, most frequent first — powers the palette editor. */
    fun palette(alphaThreshold: Int = 0, limit: Int = 256): List<Int> {
        val counts = HashMap<Int, Int>(512)
        for (p in pixels) if (((p ushr 24) and 0xFF) > alphaThreshold) counts[p] = (counts[p] ?: 0) + 1
        return counts.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /** Blits [other] at (x, y) with alpha compositing. */
    fun blit(other: PixelBuffer, ox: Int, oy: Int, alpha: Float = 1f) {
        for (y in 0 until other.height) for (x in 0 until other.width) {
            val src = other.pixels[y * other.width + x]
            var a = (src ushr 24) and 0xFF
            if (a == 0) continue
            a = (a * alpha).toInt().coerceIn(0, 255)
            val dx = ox + x; val dy = oy + y
            if (dx < 0 || dy < 0 || dx >= width || dy >= height) continue
            if (a == 255) { pixels[dy * width + dx] = src; continue }
            val dst = pixels[dy * width + dx]
            pixels[dy * width + dx] = blendOver(dst, (a shl 24) or (src and 0xFFFFFF), BlendMode.NORMAL)
        }
    }

    fun toRgbHexPaletteText(): String = palette().joinToString("\n") { "#%08X".format(it) }
}

/** Standard non-separable compositing modes supported by every renderer backend. */
enum class BlendMode(val id: Int, val label: String) {
    NORMAL(0, "Normal"),
    ADD(1, "Add"),
    MULTIPLY(2, "Multiply"),
    SCREEN(3, "Screen"),
    SUBTRACT(4, "Subtract"),
    OVERLAY(5, "Overlay"),
    PREMULTIPLIED(6, "Premultiplied"),
    DARKEN(7, "Darken"),
    LIGHTEN(8, "Lighten");

    companion object {
        fun byId(id: Int) = entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

/** Composites [src] (ARGB, straight alpha) over [dst] using the given blend mode. */
fun blendOver(dst: Int, src: Int, mode: BlendMode): Int {
    val sa = (src ushr 24) and 0xFF
    if (sa == 255 && mode == BlendMode.NORMAL) return src
    if (sa == 0) return dst
    val da = (dst ushr 24) and 0xFF
    val sr = (src shr 16) and 0xFF; val sg = (src shr 8) and 0xFF; val sb = src and 0xFF
    val dr = (dst shr 16) and 0xFF; val dg = (dst shr 8) and 0xFF; val db = dst and 0xFF
    var r: Int; var g: Int; var b: Int
    when (mode) {
        BlendMode.NORMAL, BlendMode.PREMULTIPLIED -> { r = sr; g = sg; b = sb }
        BlendMode.ADD -> { r = minOf(255, dr + sr); g = minOf(255, dg + sg); b = minOf(255, db + sb) }
        BlendMode.MULTIPLY -> { r = dr * sr / 255; g = dg * sg / 255; b = db * sb / 255 }
        BlendMode.SCREEN -> {
            r = 255 - (255 - dr) * (255 - sr) / 255; g = 255 - (255 - dg) * (255 - sg) / 255
            b = 255 - (255 - db) * (255 - sb) / 255
        }
        BlendMode.SUBTRACT -> { r = maxOf(0, dr - sr); g = maxOf(0, dg - sg); b = maxOf(0, db - sb) }
        BlendMode.OVERLAY -> {
            fun ov(d: Int, s: Int) = if (d < 128) 2 * d * s / 255 else 255 - 2 * (255 - d) * (255 - s) / 255
            r = ov(dr, sr); g = ov(dg, sg); b = ov(db, sb)
        }
        BlendMode.DARKEN -> { r = minOf(dr, sr); g = minOf(dg, sg); b = minOf(db, sb) }
        BlendMode.LIGHTEN -> { r = maxOf(dr, sr); g = maxOf(dg, sg); b = maxOf(db, sb) }
    }
    val outA = sa + da * (255 - sa) / 255
    if (outA == 0) return 0
    fun mix(d: Int, s: Int, daA: Int): Int = (s * sa * 255 + d * daA * (255 - sa)) / (outA * 255)
    r = mix(dr, r, da); g = mix(dg, g, da); b = mix(db, b, da)
    return ((outA and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}

// ------------------------------------------------------------------------------ png

/**
 * Minimal but complete PNG reader/writer.
 *
 * Decoding covers the full spec subset that image tools emit: bit depths 1/2/4/8/16,
 * colour types 0/2/3/4/6, all five scanline filters, palette + tRNS and Adam7? (no — interlace
 * is rejected with a clear error). Encoding always writes 8-bit RGBA with filter 0, which
 * every decoder accepts and which keeps the editor's "export sprite" path simple.
 */
object PngCodec {

    class PngException(message: String) : RuntimeException(message)

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

    fun decode(bytes: ByteArray): PixelBuffer {
        if (!isPng(bytes)) throw PngException("Not a PNG file (bad signature)")
        var offset = 8
        var width = 0; var height = 0; var bitDepth = 8; var colorType = 6; var interlace = 0
        var palette: IntArray? = null
        var transparency: IntArray? = null
        val idat = java.io.ByteArrayOutputStream(bytes.size / 2)

        while (offset + 8 <= bytes.size) {
            val length = readInt(bytes, offset); offset += 4
            val type = String(bytes, offset, 4, Charsets.US_ASCII); offset += 4
            if (offset + length > bytes.size) throw PngException("Truncated PNG chunk $type")
            when (type) {
                "IHDR" -> {
                    width = readInt(bytes, offset); height = readInt(bytes, offset + 4)
                    bitDepth = bytes[offset + 8].toInt() and 0xFF
                    colorType = bytes[offset + 9].toInt() and 0xFF
                    interlace = bytes[offset + 12].toInt() and 0xFF
                }
                "PLTE" -> {
                    val count = length / 3
                    palette = IntArray(count)
                    for (i in 0 until count) {
                        val r = bytes[offset + i * 3].toInt() and 0xFF
                        val g = bytes[offset + i * 3 + 1].toInt() and 0xFF
                        val b = bytes[offset + i * 3 + 2].toInt() and 0xFF
                        palette[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                "tRNS" -> {
                    transparency = IntArray(length) { bytes[offset + it].toInt() and 0xFF }
                }
                "IDAT" -> idat.write(bytes, offset, length)
                "IEND" -> { offset = bytes.size }
            }
            offset += length + 4
        }
        if (width <= 0 || height <= 0) throw PngException("PNG has no IHDR dimensions")
        if (interlace != 0) throw PngException("Interlaced PNGs are not supported; re-save without Adam7")

        val raw = inflate(idat.toByteArray())
        val channels = when (colorType) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> throw PngException("Unsupported colour type $colorType") }
        val bitsPerPixel = channels * bitDepth
        val bytesPerPixel = maxOf(1, bitsPerPixel / 8)
        val bytesPerRow = (width * bitsPerPixel + 7) / 8
        val out = PixelBuffer(width, height)

        val previous = ByteArray(bytesPerRow)
        val current = ByteArray(bytesPerRow)
        var pos = 0
        for (y in 0 until height) {
            if (pos >= raw.size) throw PngException("PNG data ended early at row $y")
            val filter = raw[pos++].toInt() and 0xFF
            System.arraycopy(raw, pos, current, 0, bytesPerRow); pos += bytesPerRow
            unfilter(filter, current, previous, bytesPerRow, bytesPerPixel)
            for (x in 0 until width) {
                val argb = when (colorType) {
                    3 -> {                                  // palette
                        val idx = readSample(current, x, bitDepth)
                        val base = palette?.getOrNull(idx) ?: 0
                        val alpha = transparency?.getOrNull(idx) ?: 255
                        (alpha shl 24) or (base and 0xFFFFFF)
                    }
                    0 -> {                                  // grayscale
                        val v = readSample16(current, x, bitDepth)
                        val gray = (v and 0xFF)
                        val alpha = if (transparency != null && v == transparency[0]) 0 else 255
                        (alpha shl 24) or (gray shl 16) or (gray shl 8) or gray
                    }
                    4 -> {                                  // gray + alpha
                        val g = readSample16(current, x * 2, bitDepth) and 0xFF
                        val a = readSample16(current, x * 2 + 1, bitDepth) and 0xFF
                        (a shl 24) or (g shl 16) or (g shl 8) or g
                    }
                    2 -> {                                  // rgb
                        val r = readSample16(current, x * 3, bitDepth) and 0xFF
                        val g = readSample16(current, x * 3 + 1, bitDepth) and 0xFF
                        val b = readSample16(current, x * 3 + 2, bitDepth) and 0xFF
                        val a = if (transparency != null && r == transparency[0] && g == transparency[1] && b == transparency[2]) 0 else 255
                        (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    else -> {                               // rgba
                        val r = readSample16(current, x * 4, bitDepth) and 0xFF
                        val g = readSample16(current, x * 4 + 1, bitDepth) and 0xFF
                        val b = readSample16(current, x * 4 + 2, bitDepth) and 0xFF
                        val a = readSample16(current, x * 4 + 3, bitDepth) and 0xFF
                        (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                out[x, y] = argb
            }
            System.arraycopy(current, 0, previous, 0, bytesPerRow)
        }
        return out
    }

    private fun readSample(row: ByteArray, index: Int, bitDepth: Int): Int = when (bitDepth) {
        8 -> row[index].toInt() and 0xFF
        4 -> { val b = row[index / 2].toInt() and 0xFF; if (index % 2 == 0) (b shr 4) and 0x0F else b and 0x0F }
        2 -> { val b = row[index / 4].toInt() and 0xFF; (b shr (6 - (index % 4) * 2)) and 0x03 }
        1 -> { val b = row[index / 8].toInt() and 0xFF; (b shr (7 - (index % 8))) and 0x01 }
        else -> 0
    }

    private fun readSample16(row: ByteArray, index: Int, bitDepth: Int): Int =
        if (bitDepth == 16) (row[index * 2].toInt() and 0xFF) else readSample(row, index, bitDepth)

    private fun unfilter(filter: Int, current: ByteArray, previous: ByteArray, length: Int, bpp: Int) {
        when (filter) {
            0 -> {}
            1 -> for (i in bpp until length) current[i] = (current[i] + current[i - bpp]).toByte()
            2 -> for (i in 0 until length) current[i] = (current[i] + previous[i]).toByte()
            3 -> for (i in 0 until length) {
                val a = if (i >= bpp) current[i - bpp].toInt() and 0xFF else 0
                val b = previous[i].toInt() and 0xFF
                current[i] = (current[i] + ((a + b) shr 1)).toByte()
            }
            4 -> for (i in 0 until length) {
                val a = if (i >= bpp) current[i - bpp].toInt() and 0xFF else 0
                val b = previous[i].toInt() and 0xFF
                val c = if (i >= bpp) previous[i - bpp].toInt() and 0xFF else 0
                val p = a + b - c
                val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
                val pred = when { pa <= pb && pa <= pc -> a; pb <= pc -> b; else -> c }
                current[i] = (current[i] + pred).toByte()
            }
            else -> throw PngException("Unknown PNG filter type $filter")
        }
    }

    /** Encodes [buffer] as an 8-bit RGBA PNG. */
    fun encode(buffer: PixelBuffer): ByteArray {
        val raw = java.io.ByteArrayOutputStream(buffer.height * (buffer.width * 4 + 1))
        for (y in 0 until buffer.height) {
            raw.write(0) // filter: none
            for (x in 0 until buffer.width) {
                val c = buffer[x, y]
                raw.write((c shr 16) and 0xFF); raw.write((c shr 8) and 0xFF)
                raw.write(c and 0xFF); raw.write((c ushr 24) and 0xFF)
            }
        }
        val out = java.io.ByteArrayOutputStream(raw.size() / 2)
        out.write(SIGNATURE)
        val ihdr = java.io.ByteArrayOutputStream(13)
        writeInt(ihdr, buffer.width); writeInt(ihdr, buffer.height)
        ihdr.write(8); ihdr.write(6); ihdr.write(0); ihdr.write(0); ihdr.write(0)
        chunk(out, "IHDR", ihdr.toByteArray())
        chunk(out, "IDAT", deflate(raw.toByteArray()))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** Encodes an indexed-colour PNG (colour type 3) — smaller files for pixel art. */
    fun encodeIndexed(buffer: PixelBuffer, palette: List<Int>): ByteArray {
        val indexOf = HashMap<Int, Int>(palette.size * 2)
        palette.forEachIndexed { i, c -> indexOf.putIfAbsent(c, i) }
        val pal = palette.take(256)
        val rows = java.io.ByteArrayOutputStream(buffer.height * (buffer.width + 1))
        for (y in 0 until buffer.height) {
            rows.write(0)
            for (x in 0 until buffer.width) {
                val c = buffer[x, y]
                val alpha = (c ushr 24) and 0xFF
                rows.write(if (alpha < 8) 0 else indexOf[c] ?: 0)
            }
        }
        val out = java.io.ByteArrayOutputStream(rows.size() / 2)
        out.write(SIGNATURE)
        val ihdr = java.io.ByteArrayOutputStream(13)
        writeInt(ihdr, buffer.width); writeInt(ihdr, buffer.height)
        ihdr.write(8); ihdr.write(3); ihdr.write(0); ihdr.write(0); ihdr.write(0)
        chunk(out, "IHDR", ihdr.toByteArray())
        val plte = ByteArray(pal.size * 3)
        pal.forEachIndexed { i, c ->
            plte[i * 3] = ((c shr 16) and 0xFF).toByte()
            plte[i * 3 + 1] = ((c shr 8) and 0xFF).toByte()
            plte[i * 3 + 2] = (c and 0xFF).toByte()
        }
        chunk(out, "PLTE", plte)
        chunk(out, "tRNS", ByteArray(pal.size) { 255.toByte() })
        chunk(out, "IDAT", deflate(rows.toByteArray()))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: java.io.ByteArrayOutputStream, type: String, data: ByteArray) {
        writeInt(out, data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes); out.write(data)
        val crc = CRC32()
        crc.update(typeBytes); crc.update(data)
        writeInt(out, crc.value.toInt())
    }

    private fun writeInt(out: java.io.OutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF); out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF); out.write(value and 0xFF)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(data.size * 4)
        val buffer = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) { if (inflater.needsInput() || inflater.needsDictionary()) break else continue }
                out.write(buffer, 0, n)
            }
        } finally { inflater.end() }
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data); deflater.finish()
        val out = java.io.ByteArrayOutputStream(data.size / 3)
        val buffer = ByteArray(64 * 1024)
        try {
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
        } finally { deflater.end() }
        return out.toByteArray()
    }
}

// -------------------------------------------------------------------------- textures

enum class TextureFilter(val id: Int, val label: String) {
    NEAREST(0, "Nearest (pixel art)"),
    LINEAR(1, "Linear (smooth)");

    companion object { fun fromName(name: String?) = if (name?.lowercase() == "linear") LINEAR else NEAREST }
}

enum class TextureWrap { CLAMP, REPEAT, MIRROR }

/** One rectangular region inside a texture. */
data class TextureRegion(
    val texture: Texture2D,
    val x: Int, val y: Int, val width: Int, val height: Int,
    val name: String = "",
    /** Trimmed atlas entries remember their original frame size + pivot offset. */
    val sourceWidth: Int = width,
    val sourceHeight: Int = height,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
) {
    val rect: Rect get() = Rect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat())
    val uvLeft: Float get() = x.toFloat() / texture.width
    val uvTop: Float get() = y.toFloat() / texture.height
    val uvRight: Float get() = (x + width).toFloat() / texture.width
    val uvBottom: Float get() = (y + height).toFloat() / texture.height
    val pivotU: Float get() = (width / 2f) / texture.width
    val pivotV: Float get() = (height / 2f) / texture.height

    fun withName(newName: String) = copy(name = newName)
    override fun toString() = "Region($name ${width}x$height @ $x,$y)"
}

/**
 * A texture as the engine sees it. Backends keep a parallel map from [id] to their native
 * image object (android.graphics.Bitmap, BufferedImage, or a software [PixelBuffer]).
 */
class Texture2D(
    val id: String,
    val width: Int,
    val height: Int,
    var filter: TextureFilter = TextureFilter.NEAREST,
    var wrap: TextureWrap = TextureWrap.CLAMP,
    /** CPU copy, present for software rendering, thumbnails and asset tools. */
    var pixels: PixelBuffer? = null,
    /** 9-slice insets for UI panels (0 = not a nine-patch). */
    var nineSlice: Rect? = null,
) {
    /** Regions declared by an atlas, or the whole texture as a single region. */
    val regions = LinkedHashMap<String, TextureRegion>()
    val animations = LinkedHashMap<String, List<TextureRegion>>()

    val whole: TextureRegion by lazy { TextureRegion(this, 0, 0, width, height, id) }
    var referenceCount: Int = 0

    fun region(name: String): TextureRegion? = regions[name]
    fun regionOrWhole(name: String?): TextureRegion = (name?.let { regions[it] }) ?: whole

    fun defineRegion(name: String, x: Int, y: Int, w: Int, h: Int,
                     sourceW: Int = w, sourceH: Int = h, ox: Int = 0, oy: Int = 0): TextureRegion {
        val r = TextureRegion(this, x, y, w, h, name, sourceW, sourceH, ox, oy)
        regions[name] = r
        return r
    }

    fun defineAnimation(name: String, frames: List<TextureRegion>) { animations[name] = frames }
    fun defineAnimation(name: String, vararg frameNames: String) {
        animations[name] = frameNames.mapNotNull { regions[it] }
    }

    fun sliceGrid(tileWidth: Int, tileHeight: Int, margin: Int = 0, spacing: Int = 0, namePrefix: String = "tile"): List<TextureRegion> {
        val out = ArrayList<TextureRegion>()
        var index = 0
        var y = margin
        while (y + tileHeight <= height - margin) {
            var x = margin
            while (x + tileWidth <= width - margin) {
                out.add(defineRegion("%s_%d".format(namePrefix, index), x, y, tileWidth, tileHeight))
                index++; x += tileWidth + spacing
            }
            y += tileHeight + spacing
        }
        return out
    }

    override fun toString() = "Texture2D($id ${width}x$height regions=${regions.size})"
}

/** A named, importable collection of regions — the engine's `.atlas.json` format. */
class TextureAtlas(val texture: Texture2D, val name: String = texture.id) {
    val regions: Map<String, TextureRegion> get() = texture.regions
    val animations: Map<String, List<TextureRegion>> get() = texture.animations
    fun framesOf(animation: String): List<TextureRegion> = texture.animations[animation] ?: emptyList()
    override fun toString() = "TextureAtlas($name regions=${regions.size} animations=${animations.size})"
}

/**
 * Texture manager: owns the mapping between logical texture ids ("player/idle"),
 * decoded pixels and backend handles. Backends register a loader that uploads an image.
 */
class TextureManager {
    private val textures = LinkedHashMap<String, Texture2D>()
    private val pixelSources = LinkedHashMap<String, () -> ByteArray>()
    /** Backend hook: called when pixels become available/change. */
    var onTextureLoaded: ((Texture2D) -> Unit)? = null
    var onTextureUnloaded: ((Texture2D) -> Unit)? = null
    /** Fallback texture used when art is missing (magenta checker) so bugs are visible. */
    val missing: Texture2D by lazy {
        val size = 16
        val buf = PixelBuffer(size, size)
        for (y in 0 until size) for (x in 0 until size) {
            val checker = ((x / 8) + (y / 8)) % 2 == 0
            buf[x, y] = if (checker) 0xFFFF00FF.toInt() else 0xFF101010.toInt()
        }
        Texture2D("<missing>", size, size, TextureFilter.NEAREST, pixels = buf).also { onTextureLoaded?.invoke(it) }
    }

    val count: Int get() = textures.size
    fun all(): Collection<Texture2D> = textures.values
    fun find(id: String): Texture2D? = textures[id]
    fun getOrNull(id: String): Texture2D? = textures[id]
    fun get(id: String): Texture2D = textures[id] ?: missing

    /** Registers a lazy pixel source; decoding happens on first actual use. */
    fun registerSource(id: String, source: () -> ByteArray) { pixelSources[id] = source }

    fun load(id: String): Texture2D {
        textures[id]?.let { it.referenceCount++; return it }
        val supplier = pixelSources[id] ?: pixelSources["$id.png"]
        val buffer = try {
            supplier?.invoke()?.let { bytes ->
                if (PngCodec.isPng(bytes)) PngCodec.decode(bytes) else null
            }
        } catch (t: Throwable) {
            Log.e("TextureManager", "Failed to decode texture '$id'", t); null
        }
        val texture = if (buffer != null) Texture2D(id, buffer.width, buffer.height, pixels = buffer)
        else Texture2D(id, 8, 8, TextureFilter.NEAREST, pixels = PixelBuffer(8, 8).also { b ->
            for (y in 0 until 8) for (x in 0 until 8) b[x, y] = if ((x + y) % 2 == 0) 0xFF6366F1.toInt() else 0xFF1E1B4B.toInt()
        })
        texture.referenceCount = 1
        textures[id] = texture
        onTextureLoaded?.invoke(texture)
        return texture
    }

    /** Registers an already-decoded image (used by importers and generated content). */
    fun add(id: String, pixels: PixelBuffer, filter: TextureFilter = TextureFilter.NEAREST): Texture2D {
        textures[id]?.let { return it }
        val texture = Texture2D(id, pixels.width, pixels.height, filter, pixels = pixels)
        textures[id] = texture
        onTextureLoaded?.invoke(texture)
        return texture
    }

    fun release(id: String) {
        val t = textures[id] ?: return
        t.referenceCount--
        if (t.referenceCount <= 0) {
            textures.remove(id)
            onTextureUnloaded?.invoke(t)
        }
    }

    fun clear() {
        val snapshot = textures.values.toList()
        textures.clear(); pixelSources.clear()
        snapshot.forEach { onTextureUnloaded?.invoke(it) }
    }

    fun totalBytes(): Long = textures.values.sumOf { (it.pixels?.pixels?.size ?: 0).toLong() * 4 }
}

// --------------------------------------------------------------------- image toolbox

/** Higher level image operations used by the asset editor and the asset pipeline. */
object ImageOps {
    /** Slices a sheet into evenly sized frames recorded on the texture. */
    fun sliceGrid(texture: Texture2D, tileWidth: Int, tileHeight: Int, margin: Int = 0, spacing: Int = 0) =
        texture.sliceGrid(tileWidth, tileHeight, margin, spacing)

    /** Auto-slices a sprite sheet by finding transparent "gutters" between sprites. */
    fun autoSlice(texture: Texture2D, namePrefix: String = "sprite", minSize: Int = 2): List<TextureRegion> {
        val buf = texture.pixels ?: return emptyList()
        val columns = BooleanArray(buf.width)   // column has content?
        val rows = BooleanArray(buf.height)
        for (y in 0 until buf.height) for (x in 0 until buf.width) {
            if (((buf[x, y] ushr 24) and 0xFF) > 8) { columns[x] = true; rows[y] = true }
        }
        val colRanges = runs(columns, minSize)
        val rowRanges = runs(rows, minSize)
        val out = ArrayList<TextureRegion>()
        var index = 0
        for ((y0, y1) in rowRanges) for ((x0, x1) in colRanges) {
            // Skip fully empty cells.
            var hasContent = false
            loop@ for (y in y0 until y1) for (x in x0 until x1) {
                if (((buf[x, y] ushr 24) and 0xFF) > 8) { hasContent = true; break@loop }
            }
            if (!hasContent) continue
            out.add(texture.defineRegion("%s_%d".format(namePrefix, index++), x0, y0, x1 - x0, y1 - y0))
        }
        return out
    }

    private fun runs(flags: BooleanArray, minSize: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = -1
        for (i in flags.indices) {
            if (flags[i] && start < 0) start = i
            else if (!flags[i] && start >= 0) {
                if (i - start >= minSize) out.add(start to i); start = -1
            }
        }
        if (start >= 0 && flags.size - start >= minSize) out.add(start to flags.size)
        return out
    }

    /**
     * Packs [buffers] into a single atlas with a simple skyline/shelf algorithm.
     * Returns the packed image plus the rectangle assigned to each input.
     */
    fun pack(buffers: List<PixelBuffer>, padding: Int = 1, maxWidth: Int = 2048): PackResult {
        class Entry(val index: Int, val w: Int, val h: Int) { var x = 0; var y = 0 }
        val entries = buffers.mapIndexed { i, b -> Entry(i, b.width + padding, b.height + padding) }
            .sortedByDescending { it.h }
        var x = 0; var y = 0; var shelfHeight = 0; var atlasWidth = 0
        for (e in entries) {
            if (x + e.w > maxWidth) { x = 0; y += shelfHeight; shelfHeight = 0 }
            e.x = x; e.y = y
            x += e.w; shelfHeight = maxOf(shelfHeight, e.h)
            atlasWidth = maxOf(atlasWidth, x)
        }
        val atlasHeight = y + shelfHeight
        val atlas = PixelBuffer(MathUtil.nextPowerOfTwo(atlasWidth), MathUtil.nextPowerOfTwo(atlasHeight))
        val rects = arrayOfNulls<Rect>(buffers.size)
        for (e in entries) {
            atlas.blit(buffers[e.index], e.x, e.y)
            rects[e.index] = Rect(e.x.toFloat(), e.y.toFloat(),
                buffers[e.index].width.toFloat(), buffers[e.index].height.toFloat())
        }
        return PackResult(atlas, rects.map { it ?: Rect.ZERO })
    }

    data class PackResult(val atlas: PixelBuffer, val rects: List<Rect>)

    /** Generates a normal map from a height map (used by the lighting/2D-shading tool). */
    fun normalMapFrom(height: PixelBuffer, strength: Float = 1f): PixelBuffer {
        val out = PixelBuffer(height.width, height.height)
        fun h(x: Int, y: Int): Float = (((height[x.coerceIn(0, height.width - 1), y.coerceIn(0, height.height - 1)] shr 16) and 0xFF)) / 255f
        for (y in 0 until height.height) for (x in 0 until height.width) {
            val dx = (h(x + 1, y) - h(x - 1, y)) * strength
            val dy = (h(x, y + 1) - h(x, y - 1)) * strength
            val len = kotlin.math.sqrt(dx * dx + dy * dy + 1f)
            val nx = (-dx / len * 0.5f + 0.5f)
            val ny = (-dy / len * 0.5f + 0.5f)
            val nz = (1f / len * 0.5f + 0.5f)
            out[x, y] = 0xFF000000.toInt() or ((nx * 255).toInt().coerceIn(0, 255) shl 16) or
                ((ny * 255).toInt().coerceIn(0, 255) shl 8) or (nz * 255).toInt().coerceIn(0, 255)
        }
        return out
    }

    /** Box-blurred silhouette used as a cheap glow/shadow layer. */
    fun blurredAlpha(source: PixelBuffer, radius: Int): PixelBuffer {
        val out = PixelBuffer(source.width, source.height)
        val alpha = IntArray(source.width * source.height)
        for (i in source.pixels.indices) alpha[i] = (source.pixels[i] ushr 24) and 0xFF
        val tmp = IntArray(alpha.size)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            var sum = 0; var count = 0
            for (dx in -radius..radius) {
                val sx = x + dx
                if (sx in 0 until source.width) { sum += alpha[y * source.width + sx]; count++ }
            }
            tmp[y * source.width + x] = sum / maxOf(1, count)
        }
        for (y in 0 until source.height) for (x in 0 until source.width) {
            var sum = 0; var count = 0
            for (dy in -radius..radius) {
                val sy = y + dy
                if (sy in 0 until source.height) { sum += tmp[sy * source.width + x]; count++ }
            }
            val a = sum / maxOf(1, count)
            out[x, y] = (a shl 24) or (source[x, y] and 0xFFFFFF)
        }
        return out
    }

    /** Downsamples with gamma correction for clean thumbnail generation. */
    fun thumbnail(source: PixelBuffer, maxSize: Int): PixelBuffer {
        if (source.width <= maxSize && source.height <= maxSize) return source
        val scale = maxSize.toFloat() / maxOf(source.width, source.height)
        return source.scaled((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), linear = true)
    }
}
