/**
 * Lumen2D — the asset system.
 *
 * Every piece of content a project uses (sprites, atlases, tile sets, animations, audio,
 * fonts, scenes, prefabs, scripts, data tables) is described by an [AssetMeta] record inside
 * an [AssetDatabase]. That gives the engine and the editor:
 *  * a searchable, taggable content browser with thumbnails,
 *  * per-asset import settings (atlas grid, nine-patch insets, audio gain, ...),
 *  * dependency tracking (which scene uses which sprite) for safe renaming and for builds,
 *  * provenance + licence metadata, because the bundled library only ships free content,
 *  * hot reload, so editing a PNG or script updates the running game instantly.
 *
 * Assets live in two places: the project (`project://assets/...`) and the shared library
 * (`lib://...`) that ships with the app.
 */
package dev.lumen2d.core.assets

import dev.lumen2d.core.audio.AudioClip
import dev.lumen2d.core.audio.WavCodec
import dev.lumen2d.core.platform.VirtualFileSystem
import dev.lumen2d.core.render.BitmapFont
import dev.lumen2d.core.render.FontManager
import dev.lumen2d.core.render.PixelBuffer
import dev.lumen2d.core.render.PngCodec
import dev.lumen2d.core.render.Texture2D
import dev.lumen2d.core.render.TextureFilter
import dev.lumen2d.core.render.TextureManager
import dev.lumen2d.core.scene.Scene
import dev.lumen2d.core.util.Json
import dev.lumen2d.core.util.Log
import dev.lumen2d.core.util.bool
import dev.lumen2d.core.util.flt
import dev.lumen2d.core.util.int
import dev.lumen2d.core.util.mapList
import dev.lumen2d.core.util.str
import dev.lumen2d.core.util.strList
import java.security.MessageDigest

enum class AssetType(val id: String, val label: String, val extensions: List<String>) {
    TEXTURE("texture", "Image", listOf("png")),
    ATLAS("atlas", "Atlas", listOf("atlas.json")),
    TILESET("tileset", "Tile set", listOf("tileset.json", "tsx")),
    TILEMAP("tilemap", "Tile map", listOf("tmx", "tilemap.json")),
    ANIMATION("animation", "Animation", listOf("anim.json")),
    FONT("font", "Font", listOf("font.json", "fnt")),
    AUDIO("audio", "Sound", listOf("wav")),
    MUSIC("music", "Music", listOf("wav", "music.json")),
    SCENE("scene", "Scene", listOf("scene.json")),
    PREFAB("prefab", "Prefab", listOf("prefab.json")),
    SCRIPT("script", "Script", listOf("lumen", "lumens", "lua")),
    SHADER("shader", "Effect", listOf("effect.json")),
    DATA("data", "Data", listOf("json", "csv")),
    THEME("theme", "UI theme", listOf("theme.json")),
    PARTICLE_PRESET("particles", "Particles", listOf("particles.json")),
    MATERIAL("material", "Material", listOf("material.json")),
    AUDIO_BUS("audiobus", "Audio mix", listOf("mix.json")),
    UNKNOWN("unknown", "File", emptyList());

    companion object {
        fun fromPath(path: String): AssetType {
            val lower = path.lowercase()
            return entries.firstOrNull { type -> type.extensions.any { lower.endsWith(".$it") } } ?: UNKNOWN
        }
    }
}

/** Colour-coded category used by the asset browser. */
enum class AssetCategory(val label: String, val types: List<AssetType>) {
    IMAGES("Images", listOf(AssetType.TEXTURE, AssetType.ATLAS)),
    TILES("Tiles", listOf(AssetType.TILESET, AssetType.TILEMAP)),
    ANIMATION("Animation", listOf(AssetType.ANIMATION, AssetType.PARTICLE_PRESET)),
    AUDIO("Audio", listOf(AssetType.AUDIO, AssetType.MUSIC, AssetType.AUDIO_BUS)),
    FONTS("Fonts", listOf(AssetType.FONT)),
    SCENES("Scenes", listOf(AssetType.SCENE, AssetType.PREFAB)),
    CODE("Scripts", listOf(AssetType.SCRIPT)),
    DATA("Data", listOf(AssetType.DATA, AssetType.THEME, AssetType.SHADER, AssetType.MATERIAL)),
}

/**
 * One asset record. `id` is stable across renames (derived from the pack + relative path),
 * which is what scenes and scripts reference.
 */
class AssetMeta(
    val id: String,
    val path: String,
    var type: AssetType,
    var displayName: String = path.substringAfterLast('/'),
    var tags: MutableList<String> = ArrayList(),
    var license: String = "CC0",
    var source: String = "",
    var author: String = "",
    var sourceUrl: String = "",
    var sizeBytes: Long = 0,
    var hash: String = "",
    var importSettings: MutableMap<String, Any?> = LinkedHashMap(),
    var dependencies: MutableSet<String> = LinkedHashSet(),
    /** Optional border insets for UI nine-patches. */
    var nineSlice: dev.lumen2d.core.math.Rect? = null,
    var thumbnailPath: String? = null,
    var packed: Boolean = false,
    var favourite: Boolean = false,
    var notes: String = "",
) {
    val fileName: String get() = path.substringAfterLast('/')
    val folder: String get() = path.substringBeforeLast('/', "")
    val extension: String get() = fileName.substringAfterLast('.', "")

    fun hasTag(tag: String): Boolean = tags.any { it.equals(tag, true) }

    fun serialize(): MutableMap<String, Any?> = linkedMapOf(
        "id" to id, "path" to path, "type" to type.id, "displayName" to displayName,
        "tags" to tags.toList(), "license" to license, "source" to source, "author" to author,
        "sourceUrl" to sourceUrl, "size" to sizeBytes, "hash" to hash,
        "import" to (if (importSettings.isEmpty()) null else importSettings),
        "dependencies" to dependencies.toList(),
        "nineSlice" to nineSlice?.let { listOf(it.x, it.y, it.w, it.h) },
        "packed" to packed, "favourite" to favourite,
    ).filterValues { it != null }.toMutableMap()

    companion object {
        fun fromJson(data: Map<String, Any?>): AssetMeta = AssetMeta(
            id = data.str("id"),
            path = data.str("path"),
            type = AssetType.entries.firstOrNull { it.id == data.str("type") } ?: AssetType.fromPath(data.str("path")),
            displayName = data.str("displayName").ifEmpty { data.str("path").substringAfterLast('/') },
            tags = data.strList("tags").toMutableList(),
            license = data.str("license", "CC0"),
            source = data.str("source"),
            author = data.str("author"),
            sourceUrl = data.str("sourceUrl"),
            sizeBytes = data["size"]?.let { (it as Number).toLong() } ?: 0L,
            hash = data.str("hash"),
        ).also { meta ->
            (data["import"] as? Map<*, *>)?.forEach { (k, v) -> meta.importSettings[k.toString()] = v }
            meta.dependencies.addAll(data.strList("dependencies"))
            (data["nineSlice"] as? List<*>)?.takeIf { it.size == 4 }?.let {
                meta.nineSlice = dev.lumen2d.core.math.Rect(
                    (it[0] as? Number)?.toFloat() ?: 0f, (it[1] as? Number)?.toFloat() ?: 0f,
                    (it[2] as? Number)?.toFloat() ?: 0f, (it[3] as? Number)?.toFloat() ?: 0f)
            }
            meta.packed = data.bool("packed")
            meta.favourite = data.bool("favourite")
        }
    }
}

/** Result of a scan, used by the editor's import dialog. */
class ScanResult(
    val added: List<AssetMeta>,
    val updated: List<AssetMeta>,
    val unchanged: Int,
    val errors: List<String>,
)

/**
 * The asset database: scanning, metadata, resolution and loading.
 */
/**
 * One mounted asset root.
 *
 * @param mount virtual scheme prefix the filesystem answers to (`project://`, `lib://`, `user://`),
 * @param fileSystem the storage behind it,
 * @param idRoot folder inside the filesystem that pack ids are relative to. Engine content is
 *        mounted as `lib://` over the content root, which holds `packs/` and `sources/`; declaring
 *        `idRoot = "packs"` keeps asset ids in their documented `pack:path` shape
 *        (`base:sprites/player.png`) instead of leaking the folder layout (`packs:base/...`).
 */
class AssetMount(
    val mount: String,
    val fileSystem: VirtualFileSystem,
    val idRoot: String = "",
) {
    /** Full virtual path of [path] inside this mount (`lib://packs/base/sprites/player.png`). */
    fun virtualPath(path: String): String = mount + path.trimStart('/')

    /** Strips [idRoot] so ids stay stable when content moves inside the filesystem. */
    fun idPath(path: String): String {
        val clean = path.trimStart('/')
        if (idRoot.isEmpty()) return clean
        return clean.removePrefix(idRoot.trim('/') + "/").ifEmpty { clean }
    }
}

class AssetDatabase(
    private val mounts: List<AssetMount>,
    val textures: TextureManager = TextureManager(),
    val fonts: FontManager = FontManager(textures),
) {
    private val assets = LinkedHashMap<String, AssetMeta>()
    private val byPath = HashMap<String, String>()
    /** Full virtual path (`lib://packs/base/sprites/player.png`) -> asset id. */
    private val byVirtualPath = HashMap<String, String>()
    /** Mount prefix each asset id was discovered under ("project://", "lib://", ...). */
    private val mountOf = HashMap<String, String>()
    companion object {
        /**
         * Builds a database over whole filesystems (no pack root), the shape simple callers and
         * tests use: `AssetDatabase.of(listOf("lib://" to fileSystem))`.
         */
        fun of(
            fileSystems: List<Pair<String, VirtualFileSystem>>,
            textures: TextureManager = TextureManager(),
            fonts: FontManager = FontManager(textures),
        ): AssetDatabase = AssetDatabase(fileSystems.map { AssetMount(it.first, it.second) }, textures, fonts)
    }

    private val audioCache = HashMap<String, AudioClip>()
    private val scriptCache = HashMap<String, String>()
    private val listeners = ArrayList<(AssetEvent) -> Unit>(4)

    /** Fired for hot reload and for the editor's browser refresh. */
    fun addListener(listener: (AssetEvent) -> Unit) { listeners.add(listener) }
    private fun emit(event: AssetEvent) { listeners.toList().forEach { runCatching { it(event) } } }

    val size: Int get() = assets.size
    fun all(): Collection<AssetMeta> = assets.values
    fun get(id: String): AssetMeta? = assets[id]
    fun byPath(path: String): AssetMeta? = byPath[path]?.let { assets[it] }

    /** Looks an asset up by the path a scene or script would write (`lib://packs/base/...`). */
    fun byVirtualPath(path: String): AssetMeta? = byVirtualPath[path]?.let { assets[it] }

    /**
     * Finds an asset by any of the ways projects and scripts refer to one:
     *
     *  * `pack:relative/path.png` — the canonical asset id,
     *  * `relative/path.png` — path inside its mount,
     *  * `lib://packs/base/sprites/player.png` — a full virtual path,
     *  * `player.png` — a bare file name (last resort, first match wins).
     */
    fun find(reference: String): AssetMeta? {
        if (reference.isEmpty()) return null
        // 1. an asset id (`base:sprites/player.png`) — the stable, unambiguous form,
        assets[reference]?.let { return it }
        // 2. a full virtual path (`lib://packs/base/sprites/player.png`) — what scenes and scripts
        //    write. Matching the whole path is what keeps two packs that both ship a `tileset.png`
        //    apart: the reference either names a file that exists, or it resolves to nothing.
        if (reference.contains("://")) {
            byVirtualPath[reference]?.let { return assets[it] }
            return null
        }
        // 3. a path relative to its mount (`sprites/player.png`, `scenes/level_1.scene.json`),
        byPath[reference]?.let { return assets[it] }
        val path = reference.trimStart('/')
        assets.values.firstOrNull { it.path == path }?.let { return it }
        if (path.contains('/')) {
            assets.values.firstOrNull { it.path.endsWith("/$path") }?.let { return it }
            return null
        }
        // 4. a bare file name, the last resort that lets `textureId = "player.png"` work in a
        //    freshly scaffolded project. Never used for paths, so it cannot cross packs.
        return assets.values.firstOrNull { it.fileName == path }
    }

    /** Writes [reference]'s bytes into [target], flattening schemes and pack folders. */
    fun exportAsset(reference: String, target: VirtualFileSystem, path: String): Boolean {
        val bytes = readBytes(reference) ?: return false
        target.mkdirs(path.substringBeforeLast('/', ""))
        target.writeBytes(path, bytes)
        return true
    }

    fun byType(type: AssetType): List<AssetMeta> = assets.values.filter { it.type == type }

    fun byCategory(category: AssetCategory): List<AssetMeta> =
        category.types.flatMap { type -> byType(type) }

    fun search(
        query: String = "",
        type: AssetType? = null,
        category: AssetCategory? = null,
        tags: List<String> = emptyList(),
        folder: String? = null,
        favouriteOnly: Boolean = false,
    ): List<AssetMeta> {
        val q = query.trim().lowercase()
        return assets.values.filter { asset ->
            if (type != null && asset.type != type) return@filter false
            if (category != null && asset.type !in category.types) return@filter false
            if (favouriteOnly && !asset.favourite) return@filter false
            if (folder != null && !asset.path.startsWith(folder)) return@filter false
            if (tags.isNotEmpty() && !tags.all { asset.hasTag(it) }) return@filter false
            if (q.isNotEmpty() && !(asset.displayName.lowercase().contains(q) || asset.path.lowercase().contains(q) ||
                    asset.tags.any { it.lowercase().contains(q) })) return@filter false
            true
        }
    }

    /** All distinct tags in the database (asset browser filter chips). */
    fun allTags(): List<String> = assets.values.flatMap { it.tags }.distinct().sorted()

    fun folders(): List<String> = assets.values.map { it.folder }.distinct().sorted()

    // ------------------------------------------------------------------------ scanning

    /**
     * Scans mounted roots and (re)builds metadata.
     * @param extractMetadata reads sidecar `*.meta.json` files next to assets when present.
     */
    fun scan(roots: List<Pair<String, String>> = defaultRoots(), extractMetadata: Boolean = true): ScanResult {
        val added = ArrayList<AssetMeta>()
        val updated = ArrayList<AssetMeta>()
        var unchanged = 0
        val errors = ArrayList<String>()
        val seen = HashSet<String>()

        for ((mount, root) in roots) {
            val entry = mountFor(mount) ?: continue
            val fs = entry.fileSystem
            val files = try { fs.walk(root.trimEnd('/')) } catch (t: Throwable) { errors.add("$mount: ${t.message}"); continue }
            for (rawPath in files) {
                // Filesystem walks may hand back paths with a leading slash; ids and lookups
                // are always based on the canonical relative form.
                val path = rawPath.trimStart('/')
                var type = AssetType.fromPath(path)
                if (type == AssetType.UNKNOWN) continue
                if (path.endsWith(".meta.json") || path.contains("/.thumbnails/")) continue
                if (extractMetadata) type = sniffType(fs, path, type)
                val id = idFor(entry, path)
                seen.add(id)
                val existing = assets[id]
                val size = runCatching { fs.size(path) }.getOrDefault(0L)
                if (existing == null) {
                    val meta = AssetMeta(id = id, path = path, type = type, sizeBytes = size)
                    applyPackMetadata(meta, mount, path)
                    if (extractMetadata) readSidecar(meta, mount, path)
                    meta.tags.addAll(defaultTagsFor(meta))
                    assets[id] = meta; byPath[path] = id; mountOf[id] = mount
                    byVirtualPath[entry.virtualPath(path)] = id
                    added.add(meta)
                } else if (existing.sizeBytes != size) {
                    existing.sizeBytes = size
                    updated.add(existing)
                } else unchanged++
            }
        }
        // Purge entries whose file disappeared.
        val removed = assets.keys.filter { it !in seen }
        for (id in removed) {
            assets.remove(id); audioCache.remove(id); scriptCache.remove(id)
            byVirtualPath.entries.removeAll { it.value == id }
        }
        if (added.isNotEmpty() || removed.isNotEmpty()) emit(AssetEvent.DatabaseChanged)
        Log.i("Assets", "Scanned ${assets.size} assets (+${added.size} new, ${updated.size} changed, -${removed.size} removed)")
        return ScanResult(added, updated, unchanged, errors)
    }

    /** Default scan roots: the whole of every mounted filesystem. */
    private fun defaultRoots(): List<Pair<String, String>> = mounts.map { it.mount to "" }

    /**
     * The mount that answers [reference], longest match first, so `lib://packs/base` beats `lib://`.
     */
    private fun mountFor(reference: String): AssetMount? =
        mounts.filter { reference.startsWith(it.mount) }.maxByOrNull { it.mount.length }

    private fun fileSystemFor(reference: String): VirtualFileSystem? = mountFor(reference)?.fileSystem

    /**
     * Asset ids are `pack:relative/path.ext`. The pack is the first folder inside the mount
     * (asset packs and projects are folders), so ids stay stable when a pack is moved.
     */
    private fun idFor(entry: AssetMount, path: String): String {
        val clean = entry.idPath(path)
        val segments = clean.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return clean
        val mountName = entry.mount.substringBefore("://").ifEmpty { "assets" }
        return if (segments.size == 1) "$mountName:${segments[0]}"
        else "${segments[0]}:${segments.drop(1).joinToString("/")}"
    }

    /**
     * Refines JSON metadata by peeking inside: atlas descriptors and scenes are both `.json`
     * files, and the extension alone cannot tell them apart.
     */
    private fun sniffType(fs: VirtualFileSystem, path: String, current: AssetType): AssetType {
        if (current != AssetType.DATA || !path.endsWith(".json")) return current
        val text = runCatching { fs.readText(path) }.getOrNull() ?: return current
        return when {
            text.contains("\"regions\"") && text.contains("\"texture\"") -> AssetType.ATLAS
            text.contains("\"nodes\"") || text.contains("\"root\"") -> AssetType.SCENE
            else -> current
        }
    }

    private fun defaultTagsFor(meta: AssetMeta): List<String> {
        val tags = ArrayList<String>()
        when (meta.type) {
            AssetType.TEXTURE -> tags.add("image")
            AssetType.AUDIO, AssetType.MUSIC -> tags.add("sound")
            AssetType.SCENE -> tags.add("level")
            AssetType.SCRIPT -> tags.add("code")
            else -> {}
        }
        val name = meta.fileName.lowercase()
        for (keyword in listOf("player", "enemy", "tile", "ui", "icon", "explosion", "coin", "jump", "music",
                "ambient", "hit", "button", "panel", "background", "font", "particle")) {
            if (name.contains(keyword)) tags.add(keyword)
        }
        return tags
    }

    /** Applies the pack's catalog.json provenance (licence, author, source url). */
    private fun applyPackMetadata(meta: AssetMeta, mount: String, path: String) {
        val fs = fileSystemFor(mount) ?: return
        // The first path segment inside the mount is the pack folder (it holds catalog.json).
        val packDir = path.trimStart('/').substringBefore('/')
        val catalogPath = if (packDir.isEmpty()) "catalog.json" else "$packDir/catalog.json"
        val catalog = runCatching { Json.parseObject(fs.readText(catalogPath)) }.getOrNull() ?: return
        meta.license = catalog.str("license", meta.license)
        meta.source = catalog.str("name", meta.source)
        meta.author = catalog.str("author", meta.author)
        meta.sourceUrl = catalog.str("sourceUrl", meta.sourceUrl)
        val relative = path.removePrefix(packDir).trimStart('/')
        val entry = catalog.mapList("assets").firstOrNull { it.str("path") == relative }
        if (entry != null) {
            meta.displayName = entry.str("name", meta.displayName)
            meta.tags.addAll(entry.strList("tags"))
            entry.str("license").takeIf { it.isNotEmpty() }?.let { meta.license = it }
        }
    }

    private fun readSidecar(meta: AssetMeta, mount: String, path: String) {
        val fs = fileSystemFor(mount) ?: return
        val sidecar = "$path.meta.json"
        if (!fs.exists(sidecar)) return
        val data = runCatching { Json.parseObject(fs.readText(sidecar)) }.getOrNull() ?: return
        meta.tags.addAll(data.strList("tags"))
        (data["import"] as? Map<*, *>)?.forEach { (k, v) -> meta.importSettings[k.toString()] = v }
        data.str("name").takeIf { it.isNotEmpty() }?.let { meta.displayName = it }
        data.str("author").takeIf { it.isNotEmpty() }?.let { meta.author = it }
        data.str("license").takeIf { it.isNotEmpty() }?.let { meta.license = it }
        data.str("source").takeIf { it.isNotEmpty() }?.let { meta.source = it }
    }

    // ------------------------------------------------------------------------ writing

    fun writeSidecar(meta: AssetMeta) {
        val fs = resolve(meta) ?: return
        val data = linkedMapOf<String, Any?>(
            "tags" to meta.tags.toList(),
            "license" to meta.license,
            "source" to meta.source,
            "import" to meta.importSettings,
        )
        runCatching { fs.writeText("${meta.path}.meta.json", Json.stringify(data)) }
            .onFailure { Log.w("Assets", "Could not write metadata for ${meta.path}: ${it.message}") }
    }

    fun saveCatalog(path: String) {
        val data = linkedMapOf<String, Any?>(
            "format" to "lumen2d.catalog",
            "version" to 1,
            "assetCount" to assets.size,
            "assets" to assets.values.map { it.serialize() },
        )
        val fs = mounts.firstOrNull()?.fileSystem
        runCatching { fs?.writeText(path, Json.stringify(data)) }
    }

    fun loadCatalog(path: String) {
        val fs = mounts.firstOrNull()?.fileSystem ?: return
        if (!fs.exists(path)) return
        val data = runCatching { Json.parseObject(fs.readText(path)) }.getOrNull() ?: return
        for (entry in data.mapList("assets")) {
            val meta = AssetMeta.fromJson(entry)
            if (meta.id.isNotEmpty()) {
                assets[meta.id] = meta
                byPath[meta.path] = meta.id
                mountFor(meta.path)?.let { byVirtualPath[it.virtualPath(meta.path)] = meta.id }
            }
        }
    }

    // ------------------------------------------------------------------------- loading

    fun resolve(meta: AssetMeta): VirtualFileSystem? {
        mountOf[meta.id]?.let { mount -> fileSystemFor(mount)?.let { return it } }
        return fileSystemFor(meta.path) ?: mounts.firstOrNull()?.fileSystem
    }

    fun readBytes(id: String): ByteArray? {
        val meta = find(id) ?: return null
        val fs = resolve(meta) ?: return null
        return runCatching { fs.readBytes(meta.path) }.getOrNull()
    }

    fun readText(id: String): String? = readBytes(id)?.toString(Charsets.UTF_8)

    /** Loads (and caches) a texture, wiring it into the shared [TextureManager]. */
    fun loadTexture(id: String): Texture2D? {
        val meta = find(id) ?: return null
        // Atlas descriptors are JSON files that describe a texture plus named regions.
        if (meta.type == AssetType.ATLAS) return loadAtlas(meta.id)
        val existing = textures.getOrNull(meta.id)
        if (existing != null) return existing
        val bytes = readBytes(meta.id) ?: return null
        return try {
            val pixels = PngCodec.decode(bytes)
            val texture = textures.add(meta.id, pixels, TextureFilter.fromName(meta.importSettings["filter"]?.toString()))
            val grid = meta.importSettings["sliceGrid"] as? Map<*, *>
            if (grid != null) {
                texture.sliceGrid(
                    (grid["tileWidth"] as? Number)?.toInt() ?: 16,
                    (grid["tileHeight"] as? Number)?.toInt() ?: 16,
                    (grid["margin"] as? Number)?.toInt() ?: 0,
                    (grid["spacing"] as? Number)?.toInt() ?: 0,
                )
            }
            meta.nineSlice?.let { texture.nineSlice = it }
            emit(AssetEvent.Loaded(meta))
            texture
        } catch (t: Throwable) {
            Log.e("Assets", "Failed to load texture ${meta.path}", t); null
        }
    }

    /** Loads the texture referenced by an atlas asset and applies its region definitions. */
    fun loadAtlas(id: String): Texture2D? {
        val meta = assets[id] ?: return null
        val json = readText(meta.id) ?: return null
        val data = runCatching { Json.parseObject(json) }.getOrNull() ?: return null
        val textureId = data.str("texture")
        val atlasDir = meta.path.substringBeforeLast('/', "")
        val textureMeta = assets.values.firstOrNull { it.path == textureId }
            ?: assets.values.firstOrNull { it.path == (if (atlasDir.isEmpty()) textureId else "$atlasDir/$textureId") }
            ?: assets.values.firstOrNull { it.fileName == textureId }
            ?: return null
        val texture = loadTexture(textureMeta.id) ?: return null
        for (region in data.mapList("regions")) {
            texture.defineRegion(
                region.str("name"),
                region.int("x"), region.int("y"),
                region.int("width", region.int("w")), region.int("height", region.int("h")),
                region.int("sourceWidth", region.int("width", region.int("w"))),
                region.int("sourceHeight", region.int("height", region.int("h"))),
                region.int("offsetX"), region.int("offsetY"),
            )
        }
        val animations = data["animations"]
        if (animations is Map<*, *>) {
            // {"coin": ["coin_0", "coin_1"]} — the compact atlas format.
            for ((key, value) in animations) {
                val frames = (value as? List<*>)?.mapNotNull { raw ->
                    val name = raw?.toString() ?: return@mapNotNull null
                    texture.regions[name] ?: texture.regions[name.substringAfterLast('/')]
                } ?: emptyList()
                if (frames.isNotEmpty()) texture.defineAnimation(key.toString(), frames)
            }
        } else {
            for (animation in data.mapList("animations")) {
                val frames = animation.strList("frames").mapNotNull { name ->
                    texture.regions[name] ?: texture.regions[name.substringAfterLast('/')]
                }
                if (frames.isNotEmpty()) texture.defineAnimation(animation.str("name"), frames)
            }
        }
        return texture
    }

    fun loadAudio(id: String): AudioClip? {
        audioCache[id]?.let { return it }
        val meta = find(id) ?: return null
        val bytes = readBytes(meta.id) ?: return null
        val clip = try { WavCodec.decode(bytes, meta.displayName) } catch (t: Throwable) {
            Log.e("Assets", "Failed to decode audio ${meta.path}", t); return null
        }
        val gain = meta.importSettings["gain"]?.let { (it as? Number)?.toFloat() } ?: 1f
        val result = if (gain != 1f) clip.withGain(gain) else clip
        audioCache[meta.id] = result
        return result
    }

    fun loadFont(id: String): BitmapFont? {
        fonts.getOrNull(id)?.let { return it }
        val meta = find(id) ?: return null
        val json = readText(meta.id) ?: return null
        return runCatching { fonts.load(meta.id, json) }
            .onFailure { Log.e("Assets", "Failed to load font ${meta.path}", it) }
            .getOrNull()
    }

    fun loadScriptSource(id: String): String? {
        scriptCache[id]?.let { return it }
        val meta = find(id) ?: return null
        val text = readText(meta.id) ?: return null
        scriptCache[meta.id] = text
        return text
    }

    fun loadScene(id: String): Scene? {
        val meta = find(id) ?: return null
        val json = readText(meta.id) ?: return null
        return runCatching { Scene.fromJson(Json.parseObject(json), meta.path) }
            .onFailure { Log.e("Assets", "Failed to load scene ${meta.path}", it) }
            .getOrNull()
    }

    fun loadJson(id: String): MutableMap<String, Any?>? = readText(id)?.let {
        runCatching { Json.parseObject(it) }.getOrNull()
    }

    // ------------------------------------------------------------------ dependencies

    fun registerDependency(assetId: String, dependsOn: String) {
        assets[assetId]?.dependencies?.add(dependsOn)
    }

    /** Assets that reference [assetId] (used before deleting/renaming). */
    fun dependents(assetId: String): List<AssetMeta> =
        assets.values.filter { assetId in it.dependencies }

    fun transitiveDependencies(assetId: String, out: MutableSet<String> = LinkedHashSet()): Set<String> {
        val meta = assets[assetId] ?: return out
        for (dep in meta.dependencies) if (out.add(dep)) transitiveDependencies(dep, out)
        return out
    }

    /** Unused assets — the editor's "clean up" report. */
    fun unusedAssets(excludeTypes: Set<AssetType> = setOf(AssetType.SCENE)): List<AssetMeta> {
        val referenced = HashSet<String>()
        for (asset in assets.values) referenced.addAll(asset.dependencies)
        for (scene in byType(AssetType.SCENE)) referenced.addAll(scene.dependencies)
        return assets.values.filter { it.id !in referenced && it.type !in excludeTypes }
    }

    // ------------------------------------------------------------------------- stats

    fun stats(): Map<String, Any> = mapOf(
        "assets" to assets.size,
        "textures" to byType(AssetType.TEXTURE).size,
        "audio" to (byType(AssetType.AUDIO).size + byType(AssetType.MUSIC).size),
        "scenes" to byType(AssetType.SCENE).size,
        "scripts" to byType(AssetType.SCRIPT).size,
        "sizeBytes" to assets.values.sumOf { it.sizeBytes },
        "textureMemory" to textures.totalBytes(),
    )

    fun clear() {
        assets.clear(); byPath.clear(); audioCache.clear(); scriptCache.clear(); textures.clear(); fonts.clear()
    }

    /** Reloads an asset from disk (hot reload after external edits). */
    fun reload(id: String) {
        val meta = assets[id] ?: return
        textures.release(meta.id)
        audioCache.remove(meta.id)
        scriptCache.remove(meta.id)
        resolve(meta)?.let { fs ->
            meta.sizeBytes = runCatching { fs.size(meta.path) }.getOrDefault(meta.sizeBytes)
        }
        meta.hash = computeHash(meta.id)
        emit(AssetEvent.Changed(meta))
    }

    /** SHA-256 of an asset's bytes — import caching and duplicate detection. */
    fun computeHash(id: String): String {
        val bytes = readBytes(id) ?: return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Decodes a PNG that is not in the database (drag-and-drop imports). */
    fun decodeExternalPng(bytes: ByteArray): PixelBuffer? = runCatching { PngCodec.decode(bytes) }.getOrNull()
}

/** Asset database events (drive the editor's live refresh). */
sealed class AssetEvent {
    object DatabaseChanged : AssetEvent()
    data class Added(val meta: AssetMeta) : AssetEvent()
    data class Loaded(val meta: AssetMeta) : AssetEvent()
    data class Changed(val meta: AssetMeta) : AssetEvent()
    data class Removed(val id: String) : AssetEvent()
}
