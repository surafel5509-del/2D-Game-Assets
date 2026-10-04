/**
 * The shipped asset library and its source index.
 *
 * These tests are the contract between the engine and `assets-library/`: the library is generated
 * output (art, sound, fonts and the provenance index), so if a change to the engine or to an
 * importer leaves the library half-rebuilt, the suite fails instead of the app shipping broken
 * assets. The disk half of the suite reports that it is skipping when the repository's
 * `assets-library/` folder is not reachable (for example when running the suite against a copy that
 * only contains the engine modules).
 */
package dev.lumen2d.core.test

import dev.lumen2d.core.assets.AssetDatabase
import dev.lumen2d.core.assets.AssetLibrary
import dev.lumen2d.core.assets.AssetOrigin
import dev.lumen2d.core.assets.AssetSourceIndex
import dev.lumen2d.core.platform.FileFileSystem
import dev.lumen2d.core.platform.InMemoryFileSystem
import dev.lumen2d.core.render.PngCodec
import dev.lumen2d.core.render.Texture2D
import dev.lumen2d.core.tiles.TileSet
import dev.lumen2d.core.util.Json
import dev.lumen2d.core.util.int
import dev.lumen2d.core.util.str
import java.io.File

/** Walks up from the working directory looking for the repository's `assets-library`. */
private fun findLibraryRoot(): File? {
    var directory: File? = File("").absoluteFile
    var levels = 0
    while (directory != null && levels < 6) {
        if (File(directory, "assets-library/packs/base/pack.json").isFile) {
            return File(directory, "assets-library")
        }
        directory = directory.parentFile
        levels++
    }
    return null
}

private fun findRepositoryRoot(): File? = findLibraryRoot()?.parentFile

fun libraryTests() {
    T.section("assets: library + sources")
    provenanceTests()
    shippedLibraryTests()
    importedPackTests()
    sampleReferenceTests()
}

// ------------------------------------------------------------------------ in-memory provenance

private fun provenanceTests() = T.test("asset sources: index parsing and lookup") {
    val vfs = InMemoryFileSystem()
    vfs.writeText(
        "sources/demo/index.json",
        """
        {
          "format": "lumen2d.asset-sources",
          "version": 1,
          "pack": "demo",
          "name": "Demo Pack",
          "description": "A pack built by a test.",
          "license": "CC0-1.0",
          "author": "Lumen2D contributors",
          "fileCount": 2,
          "files": [
            {"path": "sprites/hero.png", "origin": "generated", "recipe": "DemoArt.hero()",
             "source": "engine", "license": "CC0-1.0", "author": "Lumen2D contributors",
             "importSettings": {"frameWidth": 16}, "notes": ""},
            {"path": "audio/blip.wav", "origin": "generated", "recipe": "Tone.square(1200)",
             "source": "engine", "license": "CC0-1.0", "author": "Lumen2D contributors",
             "importSettings": {}, "notes": ""}
          ]
        }
        """.trimIndent(),
    )
    vfs.writeText(
        "sources/tiles/index.json",
        """
        {
          "format": "lumen2d.asset-sources", "version": 1, "pack": "tiles", "name": "Tiles",
          "description": "Imported", "license": "CC0-1.0", "author": "Someone",
          "files": [{"path": "tiles/sheet.png", "origin": "imported", "recipe": "Sheet/sheet.png",
                     "source": "vendor.zip", "license": "CC0-1.0", "author": "Someone",
                     "importSettings": {"tileWidth": 18}}]
        }
        """.trimIndent(),
    )
    vfs.writeText(
        "sources/tiles/sources.json",
        """
        {
          "format": "lumen2d.asset-source-archive", "version": 1, "id": "tiles", "name": "Vendor tiles",
          "upstream": "https://example.invalid/tiles", "license": "CC0-1.0", "author": "Someone",
          "archive": "vendor.zip", "archiveSha256": "abcdef0123456789",
          "tileWidth": 18, "tileHeight": 18, "spacing": 1, "sheetColumns": 20, "sheetRows": 9,
          "files": [{"name": "sheet.png", "bytes": 5913, "sha256": "a3bbff36"}]
        }
        """.trimIndent(),
    )
    vfs.writeText(
        "packs/demo/pack.json",
        """
        {"id": "demo", "name": "Demo Pack", "description": "A pack built by a test.",
         "version": "1.0.0", "license": "CC0-1.0", "author": "Lumen2D contributors",
         "engine": "1.0.0", "tags": ["test"], "files": ["sprites/hero.png", "audio/blip.wav"]}
        """.trimIndent(),
    )
    vfs.writeText(
        "packs/tiles/pack.json",
        """
        {"id": "tiles", "name": "Vendor Tiles", "version": "2.0.0", "license": "CC0-1.0",
         "author": "Someone", "tags": ["tileset"], "files": ["tiles/sheet.png"]}
        """.trimIndent(),
    )
    // A folder without a manifest must be ignored, not crash the loader.
    vfs.writeText("packs/scratch/notes.txt", "not a pack")

    val index = AssetSourceIndex.load(vfs, "sources")
    T.eq(2, index.packCount, "one index per pack folder")
    T.eq(3, index.fileCount, "three files across two packs")
    T.eq(listOf("CC0-1.0"), index.licenses(), "licences are collected from the packs")

    val hero = index.provenance("demo:sprites/hero.png")
    T.check(hero != null, "provenance lookup by asset id works")
    T.eq("DemoArt.hero()", hero?.recipe, "recipe survives the round trip")
    T.eq(AssetOrigin.GENERATED, hero?.origin, "generated origin survives")
    T.eq(16, (hero?.importSettings?.get("frameWidth") as? Number)?.toInt(), "import settings survive")
    T.check(index.provenance("demo:missing.png") == null, "unknown paths have no provenance")
    T.check(index.provenance("nope:sprites/hero.png") == null, "unknown packs have no provenance")

    val tiles = index.forPack("tiles")!!
    val archive = index.archiveFor("tiles")
    T.check(archive != null, "the vendored archive descriptor is read")
    T.eq(180, archive?.tileCount, "tile grid comes from the archive metrics")
    T.eq("vendor.zip", archive?.archive, "archive name is kept for re-import")
    T.check(tiles.archive === archive, "the archive is attached to its pack index")
    T.check(tiles.sourceOf("tiles/sheet.png")?.origin == AssetOrigin.IMPORTED, "imported origin survives")

    val library = AssetLibrary.load(vfs, "packs", "sources")
    T.eq(2, library.packCount, "pack folders with a manifest are discovered")
    T.check(library.pack("scratch") == null, "folders without a manifest are not packs")
    T.eq("CC0-1.0", library.pack("demo")?.license, "licence comes from the manifest")
    T.eq("Vendor tiles — CC0-1.0, 18x18 tiles, vendor.zip", archive?.describe(), "archive summary")
    T.eq("demo:sprites/hero.png", library.pack("demo")!!.assetId("sprites/hero.png"), "asset ids use the pack id")
    T.eq(1, library.imported().size, "exactly one imported pack")
    T.eq("tiles", library.imported().first().first.id, "the imported pack is the tiles pack")
    T.check(Json.stringify(mapOf("a" to 1), pretty = false).contains("\"a\""), "json writer still works")
}

// ------------------------------------------------------------------------ the shipped library

private fun shippedLibraryTests() = T.test("asset sources: the shipped library matches its index") {
    val libraryRoot = findLibraryRoot()
    if (libraryRoot == null) {
        println("  · assets-library/ not found next to the sources — skipping the on-disk checks")
        return@test
    }
    val packsRoot = File(libraryRoot, "packs")
    val sourcesRoot = File(libraryRoot, "sources")
    val disk = AssetLibrary.load(FileFileSystem(libraryRoot), "packs", "sources")

    T.check(disk.packCount >= 2, "the shipped library has at least two packs (found ${disk.packCount})")
    val base = disk.pack("base")
    T.check(base != null, "the generated base pack is published")
    if (base == null) return@test

    for (pack in disk.packs) {
        val folder = File(packsRoot, pack.folder)
        T.check(folder.isDirectory, "${pack.id}: the pack folder exists")
        for (relative in pack.manifest.files) {
            T.check(File(folder, relative).isFile, "${pack.id}/$relative is listed in pack.json and exists")
            // Every published asset carries a sidecar so the browser can show tags and licence.
            if (!relative.endsWith(".json") && !relative.endsWith(".md")) {
                T.check(
                    File(folder, "$relative.meta.json").isFile,
                    "${pack.id}/$relative has a *.meta.json sidecar",
                )
            }
        }
    }

    T.eq(17, base.manifest.fileCount, "the base pack publishes 17 assets")
    val baseSources = base.sources
    T.check(baseSources != null, "the base pack has a source index")
    if (baseSources == null) return@test
    T.eq(baseSources.fileCount, baseSources.generatedCount, "every base file is engine-generated")
    T.eq(
        base.manifest.files.sorted(),
        baseSources.files.map { it.path }.sorted(),
        "the source index lists exactly the assets the manifest publishes",
    )
    T.check(baseSources.recipes().contains("DemoArt.playerSheet()"), "player art provenance is recorded")
    T.check(baseSources.recipes().any { it.startsWith("Tone.") }, "sound provenance is recorded")
    T.check(
        baseSources.recipes().any { it.contains("PixelFont") || it.contains("buildPixelFont") },
        "font provenance is recorded",
    )
    for (source in baseSources.files) {
        T.check(source.recipe.isNotEmpty(), "provenance for ${source.path} names a recipe")
        T.check(source.license.isNotEmpty(), "provenance for ${source.path} names a licence")
        T.check(File(packsRoot, "base/${source.path}").isFile, "indexed file ${source.path} exists in the pack")
    }
    T.check(File(sourcesRoot, "base/README.md").isFile, "the human-readable source index is written")
    T.check(
        File(sourcesRoot, "base/index.json").readText().contains("\"lumen2d.asset-sources\""),
        "the source index declares its format",
    )

    val player = base.sourceOf("sprites/player.png")
    T.eq("DemoArt.playerSheet()", player?.recipe, "the shipped library records which recipe made the player sheet")
    T.eq("Generated by the engine", player?.origin?.label, "the shipped library marks generated art as such")
    T.eq(
        "sprites/player.png", disk.index.provenance("base:sprites/player.png")?.path,
        "provenance can be looked up from an asset id",
    )
}

// ------------------------------------------------------------------------- imported pack

private fun importedPackTests() = T.test("asset sources: the imported pack keeps its provenance") {
    val libraryRoot = findLibraryRoot() ?: return@test
    val packsRoot = File(libraryRoot, "packs")
    val sourcesRoot = File(libraryRoot, "sources")
    val disk = AssetLibrary.load(FileFileSystem(libraryRoot), "packs", "sources")
    val kenney = disk.pack("kenney")
    if (kenney == null) {
        println("  · the imported kenney pack is not vendored — skipping the import checks")
        return@test
    }

    T.eq("CC0-1.0", kenney.license, "the imported pack keeps its upstream licence")
    T.check(kenney.manifest.author.contains("Kenney"), "the imported pack credits its author")
    val attribution = File(packsRoot, "kenney/ATTRIBUTION.md")
    T.check(attribution.isFile, "the imported pack ships attribution")
    T.check(attribution.readText().contains("CC0"), "attribution states the licence")

    val sources = kenney.sources
    T.check(sources != null, "the imported pack has a source index")
    if (sources == null) return@test
    T.eq(2, sources.importedCount, "both imported files are marked as imported")
    T.eq(sources.fileCount, kenney.manifest.fileCount, "provenance covers every imported asset")
    val tileset = sources.sourceOf("tiles/tileset.png")
    T.eq("Tilemap/tilemap_packed.png", tileset?.recipe, "the imported file names its archive entry")
    T.check((tileset?.source ?: "").contains("sources/"), "the imported file points at the vendored source folder")
    T.eq("Imported from Tilemap/tilemap_packed.png", tileset?.describe()?.substringBefore(" ("),
        "the browser-facing summary names the import")
    T.check(File(sourcesRoot, "kenney/README.md").isFile, "the imported pack has a human-readable source index")

    val archive = disk.index.archiveFor("kenney")
    T.check(archive != null, "the imported pack records its source archive")
    if (archive == null) return@test
    T.eq(18, archive.tileWidth, "the tile grid of the archive is recorded")
    T.eq(180, archive.tileCount, "the archive describes all 180 tiles")
    T.check(archive.archiveSha256.length >= 16, "the archive hash is recorded")
    T.check(
        File(sourcesRoot, "kenney-pixel-platformer/sources.json").isFile,
        "the vendored source folder keeps its descriptor",
    )

    // The imported sheet must be usable as a real tileset, not just a PNG on disk.
    val metaFile = File(packsRoot, "kenney/tiles/tileset.png.meta.json")
    T.check(metaFile.isFile, "the imported tileset has a sidecar")
    val meta = Json.parseObject(metaFile.readText())
    val settings = meta["importSettings"] as Map<*, *>
    val columns = (settings["columns"] as Number).toInt()
    val rows = (settings["rows"] as Number).toInt()
    val set = TileSet(
        name = meta.str("displayName"),
        tileWidth = (settings["tileWidth"] as Number).toInt(),
        tileHeight = (settings["tileHeight"] as Number).toInt(),
        columns = columns,
    )
    set.tileCount = columns * rows
    val sheet = PngCodec.decode(File(packsRoot, "kenney/tiles/tileset.png").readBytes())
    set.texture = Texture2D("kenney:tiles/tileset.png", sheet.width, sheet.height, pixels = sheet)
    T.eq(180, set.tileCount, "the sidecar describes the whole sheet")
    T.eq(360, sheet.width, "the published sheet keeps its pixel size")
    val first = set.regionFor(0)
    T.check(first != null && first.width == 18 && first.height == 18, "tile 0 slices as 18x18")
    T.check(set.regionFor(179) != null, "the last tile of the sheet slices")
    T.check(set.regionFor(180) == null, "tiles beyond the sheet do not slice")
}

// ------------------------------------------------- sample games resolve their library references

private fun sampleReferenceTests() = T.test("sample games only reference library assets that exist") {
    val libraryRoot = findLibraryRoot() ?: return@test
    val repository = findRepositoryRoot() ?: return@test
    val gamesRoot = File(repository, "sample-games")
    val games = gamesRoot.listFiles()?.filter { File(it, "project.lumen").isFile }?.sortedBy { it.name }
        ?: emptyList()
    if (games.isEmpty()) {
        println("  · sample-games/ is empty — skipping the cross-check of sample references")
        return@test
    }

    // One database over `lib://`, exactly like the runtime mounts the library.
    val database = AssetDatabase(listOf("lib://" to FileFileSystem(libraryRoot)))
    database.scan(extractMetadata = false)
    T.check(database.size > 20, "the library scans into the asset database (${database.size} assets)")

    var references = 0
    for (game in games) {
        val scenes = File(game, "scenes")
        val sceneFiles = scenes.listFiles()?.filter { it.name.endsWith(".scene.json") } ?: emptyList()
        T.check(sceneFiles.isNotEmpty(), "${game.name} ships at least one scene")
        for (scene in sceneFiles) {
            for (match in Regex("\"lib://[^\"]+\"").findAll(scene.readText())) {
                val id = match.value.trim('"')
                references++
                T.check(
                    database.find(id) != null,
                    "${game.name}/${scene.name} references $id, which exists in the library",
                )
            }
        }
    }
    T.check(references > 0, "sample games reference the library ($references references)")
}
