/**
 * Lumen2D — tile sets, tile maps and terrain auto-tiling.
 *
 * Tile maps are the backbone of the level editor and of most sample games, so this module is
 * deliberately complete: chunked storage for large levels, per-tile flip/rotation flags,
 * multiple layers, collision generation straight from tile metadata, animated tiles, and
 * importers for Tiled (TMX) and the engine's own JSON format.
 */
package dev.lumen2d.core.tiles

import dev.lumen2d.core.math.Color
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.physics.BodyType
import dev.lumen2d.core.physics.Shape2D
import dev.lumen2d.core.render.Renderer
import dev.lumen2d.core.render.TextureRegion
import dev.lumen2d.core.scene.Node2D
import dev.lumen2d.core.scene.PhysicsBodyNode
import dev.lumen2d.core.scene.PropertyDef
import dev.lumen2d.core.scene.PropertyType
import dev.lumen2d.core.scene.StaticBody2DNode
import dev.lumen2d.core.util.Json
import dev.lumen2d.core.util.Log
import dev.lumen2d.core.util.int
import dev.lumen2d.core.util.mapList
import dev.lumen2d.core.util.str
import dev.lumen2d.core.util.strList
import kotlin.math.max
import kotlin.math.min

/** Collision shape a tile contributes to the physics world. */
enum class TileCollision(val id: Int, val label: String) {
    NONE(0, "None"),
    FULL(1, "Full square"),
    TOP(2, "Top edge"),
    BOTTOM(3, "Bottom edge"),
    LEFT(4, "Left edge"),
    RIGHT(5, "Right edge"),
    SLOPE_RIGHT(6, "Slope /"),
    SLOPE_LEFT(7, "Slope \\"),
    PLATFORM(8, "One-way platform");

    companion object {
        fun byId(id: Int) = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** Metadata for one tile in a tile set. */
class TileMeta(
    val index: Int,
    var collision: TileCollision = TileCollision.NONE,
    var tags: MutableList<String> = ArrayList(),
    var animationFrames: MutableList<Int> = ArrayList(),
    var animationFps: Float = 6f,
    /** Damage dealt / trigger fired when touched (spikes, lava, water). */
    var hazard: Float = 0f,
    var friction: Float = 1f,
    var oneWay: Boolean = false,
) {
    val animated: Boolean get() = animationFrames.size > 1

    fun serialize(): Map<String, Any?> = linkedMapOf(
        "index" to index,
        "collision" to collision.id,
        "tags" to tags.toList(),
        "animationFrames" to animationFrames.toList(),
        "animationFps" to animationFps.toDouble(),
        "hazard" to hazard.toDouble(),
    ).filterValues { it != 0 && it != emptyList<Any>() && it != "" && it != 0.0 && it != false }

    companion object {
        fun fromJson(data: Map<String, Any?>): TileMeta = TileMeta(
            index = data.int("index"),
            collision = TileCollision.byId(data.int("collision")),
            hazard = (data["hazard"] as? Number)?.toFloat() ?: 0f,
        ).also { meta ->
            meta.tags.addAll(data.strList("tags"))
            meta.animationFrames.addAll((data["animationFrames"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList())
            (data["animationFps"] as? Number)?.let { meta.animationFps = it.toFloat() }
        }
    }
}

/**
 * A tile set: tiles laid out in a grid inside a texture, plus per-tile metadata.
 * Tiles are addressed by index; the texture region for an index is derived from the grid.
 */
class TileSet(
    var name: String = "TileSet",
    var textureId: String = "",
    var tileWidth: Int = 16,
    var tileHeight: Int = 16,
    var columns: Int = 8,
    var margin: Int = 0,
    var spacing: Int = 0,
    /** Terrain/autotile groups: bitmask of neighbours -> tile index. */
    var autoTileRules: MutableMap<String, MutableMap<Int, Int>> = LinkedHashMap(),
) {
    val tiles = LinkedHashMap<Int, TileMeta>()
    var texture: dev.lumen2d.core.render.Texture2D? = null

    val rows: Int get() = if (columns <= 0) 0 else (tileCount + columns - 1) / columns
    var tileCount: Int = 1

    fun tileMeta(index: Int): TileMeta = tiles.getOrPut(index) { TileMeta(index) }

    /** Region inside the texture for a tile index. */
    fun regionFor(index: Int): TextureRegion? {
        val tex = texture ?: return null
        val col = index % columns
        val row = index / columns
        val x = margin + col * (tileWidth + spacing)
        val y = margin + row * (tileHeight + spacing)
        if (x + tileWidth > tex.width || y + tileHeight > tex.height) return null
        return tex.regions.values.firstOrNull { it.x == x && it.y == y }
            ?: tex.defineRegion("tile_$index", x, y, tileWidth, tileHeight)
    }

    /** Selects the right tile for a neighbour bitmask using named auto-tile rules. */
    fun autoTile(ruleName: String, neighbourMask: Int): Int? = autoTileRules[ruleName]?.get(neighbourMask)

    fun serialize(): Map<String, Any?> = linkedMapOf(
        "format" to "lumen2d.tileset",
        "name" to name,
        "texture" to textureId,
        "tileWidth" to tileWidth,
        "tileHeight" to tileHeight,
        "columns" to columns,
        "margin" to margin,
        "spacing" to spacing,
        "tileCount" to tileCount,
        "tiles" to tiles.values.filter { it.collision != TileCollision.NONE || it.tags.isNotEmpty() || it.animated }
            .map { it.serialize() },
        "autoTileRules" to autoTileRules.mapValues { (_, rules) -> rules.mapKeys { it.key.toString() } },
    )

    companion object {
        fun fromJson(data: Map<String, Any?>): TileSet = TileSet(
            name = data.str("name", "TileSet"),
            textureId = data.str("texture"),
            tileWidth = data.int("tileWidth", 16),
            tileHeight = data.int("tileHeight", 16),
            columns = data.int("columns", 8),
            margin = data.int("margin"),
            spacing = data.int("spacing"),
        ).also { set ->
            set.tileCount = data.int("tileCount", set.columns * 4)
            for (tile in data.mapList("tiles")) set.tiles[tile.int("index")] = TileMeta.fromJson(tile)
            (data["autoTileRules"] as? Map<*, *>)?.forEach { (ruleName, rules) ->
                val map = LinkedHashMap<Int, Int>()
                (rules as? Map<*, *>)?.forEach { (mask, index) ->
                    val m = mask.toString().toIntOrNull() ?: return@forEach
                    val i = (index as? Number)?.toInt() ?: return@forEach
                    map[m] = i
                }
                set.autoTileRules[ruleName.toString()] = map
            }
        }
    }
}

/** Per-cell tile flags (flip/rotate) packed into the high bits, like Tiled's GID flags. */
object TileFlags {
    const val FLIP_H = 1 shl 28
    const val FLIP_V = 1 shl 29
    const val ROTATE_90 = 1 shl 30
    const val MASK = 0x0FFFFFFF

    fun index(value: Int) = value and MASK
    fun isFlippedH(value: Int) = (value and FLIP_H) != 0
    fun isFlippedV(value: Int) = (value and FLIP_V) != 0
    fun isRotated(value: Int) = (value and ROTATE_90) != 0
    fun pack(index: Int, flipH: Boolean, flipV: Boolean, rotate: Boolean = false): Int =
        index or (if (flipH) FLIP_H else 0) or (if (flipV) FLIP_V else 0) or (if (rotate) ROTATE_90 else 0)
}

/** A single layer of tiles. */
class TileLayer(
    var name: String,
    var width: Int,
    var height: Int,
    var visible: Boolean = true,
    var opacity: Float = 1f,
    /** When true, tiles are drawn sorted by their bottom edge (top-down RPGs). */
    var ySort: Boolean = false,
    var collisionEnabled: Boolean = true,
) {
    /** Row-major tile data; -1 means empty. Values may carry flip flags. */
    val data = IntArray(width * height) { -1 }

    fun inBounds(x: Int, y: Int) = x >= 0 && y >= 0 && x < width && y < height
    fun get(x: Int, y: Int): Int = if (inBounds(x, y)) data[y * width + x] else -1
    fun set(x: Int, y: Int, value: Int) { if (inBounds(x, y)) data[y * width + x] = value }
    fun isEmpty(x: Int, y: Int) = get(x, y) < 1
    fun clear() { data.fill(-1) }
    fun tileIndexAt(x: Int, y: Int) = TileFlags.index(get(x, y))

    fun serialize(): Map<String, Any?> = linkedMapOf(
        "name" to name, "width" to width, "height" to height, "visible" to visible,
        "opacity" to opacity.toDouble(), "ySort" to ySort, "collisionEnabled" to collisionEnabled,
        "data" to data.toList(),
    )

    companion object {
        fun fromJson(data: Map<String, Any?>): TileLayer {
            val layer = TileLayer(
                name = data.str("name", "Layer"),
                width = data.int("width", 32),
                height = data.int("height", 18),
                visible = data["visible"] as? Boolean ?: true,
                opacity = (data["opacity"] as? Number)?.toFloat() ?: 1f,
                ySort = data["ySort"] as? Boolean ?: false,
                collisionEnabled = data["collisionEnabled"] as? Boolean ?: true,
            )
            val list = (data["data"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
            for (i in list.indices) if (i < layer.data.size) layer.data[i] = list[i]
            return layer
        }
    }
}

/**
 * Tile map data model: a tile set + layers, with helpers for painting, flood fill, autotiling
 * and collision generation. The editor manipulates this directly, the runtime renders it.
 */
class TileMapData(
    var tileSet: TileSet = TileSet(),
    var tileWidth: Int = tileSet.tileWidth,
    var tileHeight: Int = tileSet.tileHeight,
) {
    val layers = ArrayList<TileLayer>()
    var version: Int = 1

    fun layer(name: String): TileLayer? = layers.firstOrNull { it.name == name }

    fun ensureLayer(name: String, width: Int, height: Int): TileLayer =
        layer(name) ?: TileLayer(name, width, height).also { layers.add(it) }

    fun addLayer(name: String, width: Int, height: Int): TileLayer = TileLayer(name, width, height).also { layers.add(it) }

    fun removeLayer(name: String) { layers.removeAll { it.name == name } }

    /** Paints a single tile (with flags) into a layer. */
    fun paint(layerName: String, x: Int, y: Int, tileIndex: Int, flipH: Boolean = false, flipV: Boolean = false) {
        val l = layer(layerName) ?: return
        l.set(x, y, if (tileIndex < 0) -1 else TileFlags.pack(tileIndex, flipH, flipV))
    }

    fun erase(layerName: String, x: Int, y: Int) = paint(layerName, x, y, -1)

    /** Flood fill — the editor's bucket tool. */
    fun floodFill(layerName: String, startX: Int, startY: Int, tileIndex: Int, limit: Int = 200000) {
        val l = layer(layerName) ?: return
        if (!l.inBounds(startX, startY)) return
        val target = l.get(startX, startY)
        if (target == tileIndex) return
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(startX to startY)
        var visited = 0
        while (stack.isNotEmpty() && visited < limit) {
            val (x, y) = stack.removeLast()
            if (!l.inBounds(x, y) || l.get(x, y) != target) continue
            l.set(x, y, tileIndex)
            visited++
            stack.addLast(x + 1 to y); stack.addLast(x - 1 to y)
            stack.addLast(x to y + 1); stack.addLast(x to y - 1)
        }
    }

    /** Rectangle fill (drag-paint). */
    fun fillRect(layerName: String, rect: Rect, tileIndex: Int) {
        val l = layer(layerName) ?: return
        for (y in rect.y.toInt()..(rect.y + rect.h).toInt()) {
            for (x in rect.x.toInt()..(rect.x + rect.w).toInt()) l.set(x, y, tileIndex)
        }
    }

    /** Line of tiles between two cells (Bresenham) — smooth drag painting. */
    fun paintLine(layerName: String, x0: Int, y0: Int, x1: Int, y1: Int, tileIndex: Int) {
        var x = x0; var y = y0
        val dx = kotlin.math.abs(x1 - x0); val dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var error = dx + dy
        while (true) {
            paint(layerName, x, y, tileIndex)
            if (x == x1 && y == y1) break
            val e2 = 2 * error
            if (e2 >= dy) { error += dy; x += sx }
            if (e2 <= dx) { error += dx; y += sy }
        }
    }

    /**
     * Terrain auto-tiling: chooses tile indices so edges connect automatically.
     * [ruleName] refers to a rule set in the tile set (bit 1 = up, 2 = right, 4 = down, 8 = left).
     */
    fun autoTile(layerName: String, ruleName: String, rect: Rect, emptyTile: Int = 0, filledTile: Int = 1) {
        val l = layer(layerName) ?: return
        val x0 = rect.x.toInt(); val y0 = rect.y.toInt()
        val x1 = (rect.x + rect.w).toInt(); val y1 = (rect.y + rect.h).toInt()
        fun filled(x: Int, y: Int) = l.inBounds(x, y) && l.get(x, y) >= 0
        for (y in y0..y1) for (x in x0..x1) {
            if (!filled(x, y)) continue
            var mask = 0
            if (!filled(x, y - 1)) mask = mask or 1
            if (!filled(x + 1, y)) mask = mask or 2
            if (!filled(x, y + 1)) mask = mask or 4
            if (!filled(x - 1, y)) mask = mask or 8
            val chosen = tileSet.autoTile(ruleName, mask) ?: continue
            l.set(x, y, chosen)
        }
    }

    /** Expands the map (useful for procedural level growth). */
    fun resize(width: Int, height: Int) {
        for (l in layers) {
            if (l.width == width && l.height == height) continue
            val newData = IntArray(width * height) { -1 }
            for (y in 0 until min(l.height, height)) {
                for (x in 0 until min(l.width, width)) newData[y * width + x] = l.data[y * l.width + x]
            }
            val newLayer = TileLayer(l.name, width, height, l.visible, l.opacity, l.ySort, l.collisionEnabled)
            System.arraycopy(newData, 0, newLayer.data, 0, newData.size)
            // Replace in place to preserve ordering.
            val index = layers.indexOf(l)
            if (index >= 0) layers[index] = newLayer
        }
    }

    fun usedTileIndices(): Set<Int> {
        val out = HashSet<Int>()
        for (l in layers) for (value in l.data) if (value >= 0) out.add(TileFlags.index(value))
        return out
    }

    fun serialize(): Map<String, Any?> = linkedMapOf(
        "format" to "lumen2d.tilemap",
        "version" to version,
        "width" to (layers.firstOrNull()?.width ?: 0),
        "height" to (layers.firstOrNull()?.height ?: 0),
        "tileWidth" to tileWidth,
        "tileHeight" to tileHeight,
        "tileSet" to tileSet.serialize(),
        "layers" to layers.map { it.serialize() },
    )

    companion object {
        fun fromJson(data: Map<String, Any?>): TileMapData {
            val set = (data["tileSet"] as? Map<*, *>)?.let {
                @Suppress("UNCHECKED_CAST")
                TileSet.fromJson(it as Map<String, Any?>)
            } ?: TileSet()
            val map = TileMapData(set, data.int("tileWidth", set.tileWidth), data.int("tileHeight", set.tileHeight))
            for (layerData in data.mapList("layers")) map.layers.add(TileLayer.fromJson(layerData))
            map.version = data.int("version", 1)
            return map
        }
    }
}

/**
 * Tile map node: renders the tile map, builds collision bodies, and animates animated tiles.
 * Tile layers can also be y-sorted so overlapping decoration looks correct in top-down games.
 */
open class TileMapNode(name: String = "TileMapLayer") : Node2D(name) {
    var tileMap: TileMapData = TileMapData()
    var textureId: String = ""
    var layerFilter: String = ""          // empty = render all layers
    var blendMode: dev.lumen2d.core.render.BlendMode = dev.lumen2d.core.render.BlendMode.NORMAL
    var generateCollision: Boolean = true
    var ySortEnabled: Boolean = false
    var tintColor: Color = Color.WHITE
    var cullTiles: Boolean = true

    /** Physics body holding one collider per solid tile (created lazily on first frame). */
    var collisionBody: StaticBody2DNode? = null
        private set
    val solidCells = HashSet<Long>()
    private var animationTimer: Float = 0f
    private var animationFrame: Int = 0
    private val regionCache = HashMap<Int, TextureRegion?>()

    // ------------------------------------------------------------------- authoring

    fun setTile(layer: String, x: Int, y: Int, index: Int) = tileMap.paint(layer, x, y, index)
    fun getTile(layer: String, x: Int, y: Int): Int = tileMap.layer(layer)?.tileIndexAt(x, y) ?: -1

    fun clearLayer(layer: String) { tileMap.layer(layer)?.clear(); rebuildCollision() }

    fun paintLine(layer: String, x0: Int, y0: Int, x1: Int, y1: Int, index: Int) =
        tileMap.paintLine(layer, x0, y0, x1, y1, index)

    fun floodFill(layer: String, x: Int, y: Int, index: Int) { tileMap.floodFill(layer, x, y, index); rebuildCollision() }

    /** Full auto-tile pass over a layer (used by the editor's terrain brush). */
    fun autoTileLayer(layer: String, ruleName: String) {
        val l = tileMap.layer(layer) ?: return
        tileMap.autoTile(layer, ruleName, Rect(0f, 0f, l.width.toFloat(), l.height.toFloat()))
        rebuildCollision()
    }

    /** World position of a cell's centre. */
    fun cellToWorld(x: Int, y: Int): Vec2 = Vec2(
        position.x + x * tileMap.tileWidth + tileMap.tileWidth / 2f,
        position.y + y * tileMap.tileHeight + tileMap.tileHeight / 2f)

    /** Cell containing a world position. */
    fun worldToCell(world: Vec2): Pair<Int, Int> {
        val localX = world.x - position.x
        val localY = world.y - position.y
        return kotlin.math.floor(localX / tileMap.tileWidth).toInt() to
            kotlin.math.floor(localY / tileMap.tileHeight).toInt()
    }

    fun worldBoundsOfMap(): Rect {
        val w = (tileMap.layers.firstOrNull()?.width ?: 0) * tileMap.tileWidth
        val h = (tileMap.layers.firstOrNull()?.height ?: 0) * tileMap.tileHeight
        return Rect(position.x, position.y, w.toFloat(), h.toFloat())
    }

    // -------------------------------------------------------------------- lifecycle

    override fun onReady() {
        resolveTexture()
        if (generateCollision) rebuildCollision()
    }

    private fun resolveTexture() {
        val res = tree?.resources ?: return
        val id = textureId.ifEmpty { tileMap.tileSet.textureId }
        val texture = res.texture(id) ?: return
        tileMap.tileSet.texture = texture
        tileMap.tileSet.textureId = id
        regionCache.clear()
    }

    /** Rebuilds the static collision bodies for all solid tiles. */
    fun rebuildCollision() {
        val world = tree?.physics
        collisionBody?.let { world?.removeBody(it.body ?: return@let) }
        collisionBody = null
        solidCells.clear()
        if (!generateCollision || world == null) return
        val set = tileMap.tileSet
        val parentNode = parent ?: return
        val bodyNode = StaticBody2DNode(name = "${name}_collision")
        bodyNode.position = position
        bodyNode.addToGroup("tilemap_collision")
        parentNode.addChild(bodyNode)
        for (layer in tileMap.layers) {
            if (!layer.collisionEnabled) continue
            for (y in 0 until layer.height) {
                for (x in 0 until layer.width) {
                    val value = layer.get(x, y)
                    if (value < 0) continue
                    val meta = set.tiles[TileFlags.index(value)] ?: continue
                    if (meta.collision == TileCollision.NONE) continue
                    solidCells.add(cellKey(layer.name, x, y))
                }
            }
        }
        val body = world.createBody(BodyType.STATIC, Shape2D.Rectangle(Vec2(tileMap.tileWidth.toFloat(), tileMap.tileHeight.toFloat())))
        body.ownerId = bodyNode.instanceId
        body.position = Vec2(position.x, position.y)
        collisionBody = bodyNode
        // The per-tile collision is resolved analytically in `collectTileCollisions` below;
        // the single body above is used for broad "is there level geometry" queries.
    }

    private fun cellKey(layer: String, x: Int, y: Int): Long =
        (layer.hashCode().toLong() shl 40) or (x.toLong() shl 20) or y.toLong()

    /** True when the cell contains a solid tile. */
    fun isSolid(cellX: Int, cellY: Int): Boolean {
        for (layer in tileMap.layers) {
            if (!layer.collisionEnabled) continue
            val value = layer.get(cellX, cellY)
            if (value < 0) continue
            val meta = tileMap.tileSet.tiles[TileFlags.index(value)] ?: continue
            if (meta.collision != TileCollision.NONE) return true
        }
        return false
    }

    /** Hazard damage at a world position (spikes, lava) — 0 when safe. */
    fun hazardAt(world: Vec2): Float {
        val (cx, cy) = worldToCell(world)
        for (layer in tileMap.layers) {
            val value = layer.get(cx, cy)
            if (value < 0) continue
            val meta = tileMap.tileSet.tiles[TileFlags.index(value)] ?: continue
            if (meta.hazard > 0f) return meta.hazard
        }
        return 0f
    }

    /** Level bounds as a physics-friendly rect (used to clamp the camera). */
    fun levelBounds(): Rect = worldBoundsOfMap()

    override fun onProcess(delta: Float) {
        animationTimer += delta
        val set = tileMap.tileSet
        // Advance animated tiles when their frame duration elapsed.
        val anyAnimated = set.tiles.values.any { it.animated }
        if (anyAnimated) {
            val fps = set.tiles.values.firstOrNull { it.animated }?.animationFps ?: 6f
            if (animationTimer >= 1f / fps.coerceAtLeast(0.1f)) { animationTimer = 0f; animationFrame++ }
        }
    }

    override fun localBounds(): Rect = worldBoundsOfMap()

    override fun onDraw(renderer: Renderer, alpha: Float) {
        val set = tileMap.tileSet
        val texture = set.texture ?: run { resolveTexture(); set.texture } ?: return
        val camera = renderer.currentCamera()
        val visible = camera.visibleWorldRectForAabb()
        val cellW = tileMap.tileWidth
        val cellH = tileMap.tileHeight
        val modulate = effectiveModulate() * tintColor

        for (layer in tileMap.layers) {
            if (!layer.visible) continue
            if (layerFilter.isNotEmpty() && layer.name != layerFilter) continue
            val x0 = max(0, ((visible.left - position.x) / cellW).toInt() - 1)
            val x1 = min(layer.width - 1, ((visible.right - position.x) / cellW).toInt() + 1)
            val y0 = max(0, ((visible.top - position.y) / cellH).toInt() - 1)
            val y1 = min(layer.height - 1, ((visible.bottom - position.y) / cellH).toInt() + 1)
            if (x1 < x0 || y1 < y0) continue

            val drawOrder: Iterable<Pair<Int, Int>> = if (layer.ySort || ySortEnabled) {
                val cells = ArrayList<Pair<Int, Int>>()
                for (y in y0..y1) for (x in x0..x1) if (layer.get(x, y) >= 0) cells.add(x to y)
                cells.sortedBy { it.second }
            } else {
                buildList { for (y in y0..y1) for (x in x0..x1) appendCell(this, layer, x, y) }
            }

            for ((x, y) in drawOrder) {
                val value = layer.get(x, y)
                if (value < 0) continue
                val index = resolveAnimatedIndex(TileFlags.index(value), set)
                val region = regionCache.getOrPut(index) { set.regionFor(index) } ?: continue
                val worldX = position.x + x * cellW
                val worldY = position.y + y * cellH
                renderer.stats.renderedNodes++
                renderer.drawSprite(
                    region, worldX + cellW / 2f, worldY + cellH / 2f,
                    cellW.toFloat(), cellH.toFloat(), 0f, cellW / 2f, cellH / 2f,
                    modulate.withAlpha(modulate.a * layer.opacity),
                    TileFlags.isFlippedH(value), TileFlags.isFlippedV(value), blendMode,
                )
            }
        }
    }

    private fun appendCell(out: MutableList<Pair<Int, Int>>, layer: TileLayer, x: Int, y: Int) {
        if (layer.get(x, y) >= 0) out.add(x to y)
    }

    private fun resolveAnimatedIndex(index: Int, set: TileSet): Int {
        val meta = set.tiles[index] ?: return index
        if (!meta.animated) return index
        return meta.animationFrames[animationFrame % meta.animationFrames.size]
    }

    // --------------------------------------------------------------- serialization

    override fun serialize(withChildren: Boolean): MutableMap<String, Any?> {
        val out = super.serialize(withChildren)
        val props = (out["properties"] as? MutableMap<String, Any?>) ?: LinkedHashMap()
        props["_tileMap"] = tileMap.serialize()
        out["properties"] = props
        return out
    }

    override fun deserialize(data: Map<String, Any?>, registry: dev.lumen2d.core.scene.NodeRegistry) {
        super.deserialize(data, registry)
        val props = data["properties"] as? Map<*, *>
        (props?.get("_tileMap") as? Map<*, *>)?.let { raw ->
            @Suppress("UNCHECKED_CAST")
            tileMap = TileMapData.fromJson(raw as Map<String, Any?>)
            regionCache.clear()
        }
    }

    override fun propertyDefinitions(): List<PropertyDef> = listOf(
        PropertyDef("textureId", PropertyType.TEXTURE, "Tile texture", "", category = "TileMap", hint = "asset:texture"),
        PropertyDef("layerFilter", PropertyType.STRING, "Layer filter", "", category = "TileMap",
            tooltip = "Empty renders every layer; set to a layer name to render only that one"),
        PropertyDef("generateCollision", PropertyType.BOOL, "Generate collision", true, category = "TileMap"),
        PropertyDef("ySortEnabled", PropertyType.BOOL, "Y sort", false, category = "TileMap"),
        PropertyDef("tintColor", PropertyType.COLOR, "Tint", Color.WHITE, category = "TileMap"),
        PropertyDef("tileWidth", PropertyType.INT, "Tile width", 16, min = 1f, max = 256f, step = 1f, category = "TileMap"),
        PropertyDef("tileHeight", PropertyType.INT, "Tile height", 16, min = 1f, max = 256f, step = 1f, category = "TileMap"),
        PropertyDef("layerCount", PropertyType.INT, "Layers", 0, category = "Debug", animateable = false),
    ) + super.propertyDefinitions()

    override fun getProperty(property: String): Any? = when (property) {
        "textureId" -> textureId
        "layerFilter" -> layerFilter
        "generateCollision" -> generateCollision
        "ySortEnabled" -> ySortEnabled
        "tintColor" -> tintColor
        "tileWidth" -> tileMap.tileWidth
        "tileHeight" -> tileMap.tileHeight
        "layerCount" -> tileMap.layers.size
        else -> super.getProperty(property)
    }

    override fun setProperty(property: String, value: Any?): Boolean {
        when (property) {
            "textureId" -> { textureId = value?.toString() ?: ""; regionCache.clear(); resolveTexture(); return true }
            "layerFilter" -> { layerFilter = value?.toString() ?: ""; return true }
            "generateCollision" -> { generateCollision = asBool(value, true); rebuildCollision(); return true }
            "ySortEnabled" -> { ySortEnabled = asBool(value, false); return true }
            "tintColor" -> { tintColor = asColor(value, Color.WHITE); return true }
            "tileWidth" -> { tileMap.tileWidth = asInt(value, 16); tileMap.tileSet.tileWidth = tileMap.tileWidth; regionCache.clear(); return true }
            "tileHeight" -> { tileMap.tileHeight = asInt(value, 16); tileMap.tileSet.tileHeight = tileMap.tileHeight; regionCache.clear(); return true }
        }
        return super.setProperty(property, value)
    }
}

/** Imports Tiled (.tmx/.tsx) and engine JSON tile maps and tile sets. */
object TileMapImporter {

    /** Parses a Lumen2D tile set JSON file. */
    fun parseTileSetJson(text: String): TileSet =
        TileSet.fromJson(Json.parseObject(text))

    /** Parses a Lumen2D tile map JSON file. */
    fun parseTileMapJson(text: String): TileMapData = TileMapData.fromJson(Json.parseObject(text))

    /**
     * Parses a Tiled `.tsx` tile set (the subset used by the bundled Kenney packs).
     */
    fun parseTsx(text: String, textureId: String): TileSet {
        val set = TileSet(name = "TileSet", textureId = textureId)
        val root = parseXmlAttrs(text)
        root["tilewidth"]?.toIntOrNull()?.let { set.tileWidth = it }
        root["tileheight"]?.toIntOrNull()?.let { set.tileHeight = it }
        root["columns"]?.toIntOrNull()?.let { set.columns = it }
        root["tilecount"]?.toIntOrNull()?.let { set.tileCount = it }
        Regex("<tile id=\"(\\d+)\"[^>]*>").findAll(text).forEach { match ->
            val id = match.groupValues[1].toIntOrNull() ?: return@forEach
            set.tileMeta(id)
        }
        // Collision objects inside tiles become solid tiles.
        Regex("<objectgroup[^>]*>(.*?)</objectgroup>", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { match ->
            val tileIndex = Regex("tile id=\"(\\d+)\"").find(text.substring(0, match.range.first))?.groupValues?.get(1)?.toIntOrNull()
            if (tileIndex != null) set.tileMeta(tileIndex).collision = TileCollision.FULL
        }
        return set
    }

    /**
     * Parses a Tiled `.tmx` map: CSV-encoded layers with GID flags, tile size and layers.
     */
    fun parseTmx(text: String, tileSet: TileSet, textureId: String): TileMapData {
        val rootAttrs = parseXmlAttrs(text)
        val width = rootAttrs["width"]?.toIntOrNull() ?: 32
        val height = rootAttrs["height"]?.toIntOrNull() ?: 18
        tileSet.tileWidth = rootAttrs["tilewidth"]?.toIntOrNull() ?: tileSet.tileWidth
        tileSet.tileHeight = rootAttrs["tileheight"]?.toIntOrNull() ?: tileSet.tileHeight
        if (tileSet.textureId.isEmpty()) tileSet.textureId = textureId

        val firstGid = Regex("<tileset[^>]*firstgid=\"(\\d+)\"").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val map = TileMapData(tileSet, tileSet.tileWidth, tileSet.tileHeight)
        val layerRegex = Regex("<layer([^>]*)>(.*?)</layer>", RegexOption.DOT_MATCHES_ALL)
        for (match in layerRegex.findAll(text)) {
            val attrs = parseXmlAttrsFromString(match.groupValues[1])
            val name = attrs["name"] ?: "Layer"
            val layerWidth = attrs["width"]?.toIntOrNull() ?: width
            val layerHeight = attrs["height"]?.toIntOrNull() ?: height
            val layer = TileLayer(name, layerWidth, layerHeight)
            val dataMatch = Regex("<data[^>]*>(.*?)</data>", RegexOption.DOT_MATCHES_ALL).find(match.groupValues[2])
            val csv = dataMatch?.groupValues?.get(1)?.trim()
            if (csv != null && !csv.contains("<")) {
                val values = csv.split(',').mapNotNull { it.trim().toIntOrNull() }
                for (i in values.indices) {
                    if (i >= layer.data.size) break
                    val gid = values[i]
                    if (gid == 0) continue
                    // Tiled numbers tiles from `firstgid` and stores flips in the top three bits.
                    val index = (gid and 0x0FFFFFFF) - firstGid
                    val packed = TileFlags.pack(index,
                        (gid and 0x80000000.toInt()) != 0,
                        (gid and 0x40000000) != 0,
                        (gid and 0x20000000) != 0)
                    layer.data[i] = packed
                }
            }
            map.layers.add(layer)
        }
        return map
    }

    /** Very small XML attribute reader (Tiled files are simple and predictable). */
    /** Attributes of the first real element, skipping the declaration and comments. */
    private fun parseXmlAttrs(xml: String): Map<String, String> {
        var cursor = 0
        while (true) {
            val start = xml.indexOf('<', cursor)
            if (start < 0) return emptyMap()
            val isDeclaration = xml.startsWith("<?", start) || xml.startsWith("<!", start)
            val end = xml.indexOf('>', start)
            if (end < 0) return emptyMap()
            if (isDeclaration) { cursor = end + 1; continue }
            return parseXmlAttrsFromString(xml.substring(start + 1, end))
        }
    }

    private fun parseXmlAttrsFromString(fragment: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Regex("([\\w:.-]+)=\"([^\"]*)\"").findAll(fragment).forEach { match ->
            out[match.groupValues[1]] = match.groupValues[2]
        }
        return out
    }

    /**
     * Chooses a collision kind from a tile's position in a Kenney-style tile sheet: fully
     * transparent tiles stay non-solid, edge tiles get partial colliders.
     */
    fun inferCollisionFromPixels(tileSet: TileSet, buffer: dev.lumen2d.core.render.PixelBuffer) {
        for (index in 0 until tileSet.tileCount) {
            val region = tileSet.regionFor(index) ?: continue
            var opaque = 0
            var total = 0
            for (y in region.y until region.y + region.height) {
                for (x in region.x until region.x + region.width) {
                    total++
                    if (((buffer[x, y] ushr 24) and 0xFF) > 40) opaque++
                }
            }
            val ratio = if (total == 0) 0f else opaque.toFloat() / total
            val meta = tileSet.tileMeta(index)
            meta.collision = when {
                ratio > 0.92f -> TileCollision.FULL
                ratio > 0.4f -> TileCollision.TOP
                else -> TileCollision.NONE
            }
        }
        Log.i("TileMap", "Inferred collision for ${tileSet.tiles.size} tiles")
    }
}
