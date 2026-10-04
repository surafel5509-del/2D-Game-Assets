/**
 * Lumen2D — Android storage helpers.
 *
 * Everything that moves engine content in and out of the device's storage:
 *
 *  * [SampleInstaller] seeds the projects that ship inside the APK (`assets/samples`) into
 *    `filesDir/lumen/projects` on first launch, so a fresh install already has three games to play;
 *  * [ProjectStore] lists, renames, duplicates and deletes projects;
 *  * [ProjectArchive] exports a project as a `.lumenzip` (a plain zip) and imports it back;
 *  * [PackInstaller] installs an imported asset pack into `user://packs/<id>` where the engine can
 *    mount it through `lib://`.
 *
 * All of it is plain `java.io`/`java.util.zip` — no AndroidX storage APIs and no permissions.
 */
package dev.lumen2d.android

import dev.lumen2d.core.game.GameConfig
import dev.lumen2d.core.game.Project
import dev.lumen2d.core.game.ProjectSeeder
import dev.lumen2d.core.platform.FileFileSystem
import dev.lumen2d.core.util.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A project folder as the studio's home screen lists it. */
data class ProjectEntry(
    val directory: File,
    val title: String,
    val slug: String,
    val scenes: Int,
    val scripts: Int,
    val assets: Int,
    val modified: Long,
    val config: GameConfig,
) {
    val id: String get() = directory.name
}

object ProjectStore {

    /** Scans [projectsDirectory] for project folders (anything with a `project.lumen`). */
    fun list(projectsDirectory: File): List<ProjectEntry> {
        val folders = projectsDirectory.listFiles()?.filter { it.isDirectory } ?: emptyList()
        return folders.mapNotNull { describe(it) }.sortedByDescending { it.modified }
    }

    /** Reads a single project folder; null when it is not a Lumen2D project. */
    fun describe(directory: File): ProjectEntry? {
        val vfs = FileFileSystem(directory)
        val project = Project.open(vfs, "", "project://") ?: return null
        val index = project.index()
        return ProjectEntry(
            directory = directory,
            title = project.config.title.ifBlank { directory.name },
            slug = directory.name,
            scenes = index.scenes.size,
            scripts = index.scripts.size,
            assets = index.assets.size,
            modified = directory.lastModified(),
            config = project.config,
        )
    }

    /** Creates `<projects>/<slug>` and returns it (slug is made unique with a numeric suffix). */
    fun newProjectFolder(projectsDirectory: File, title: String): File {
        val base = LumenAndroid.slug(title)
        var candidate = File(projectsDirectory, base)
        var index = 2
        while (candidate.exists()) {
            candidate = File(projectsDirectory, "${base}_$index")
            index++
        }
        return candidate
    }

    fun rename(entry: ProjectEntry, newTitle: String): ProjectEntry? {
        val project = LumenAndroid.openProject(entry.directory) ?: return null
        project.config.title = newTitle
        project.save()
        return describe(entry.directory)
    }

    fun duplicate(entry: ProjectEntry, projectsDirectory: File): ProjectEntry? {
        val target = newProjectFolder(projectsDirectory, "${entry.title} copy")
        ProjectArchive.copyFolder(entry.directory, target)
        return describe(target)
    }

    fun delete(entry: ProjectEntry): Boolean = ProjectArchive.deleteFolder(entry.directory)
}

object ProjectArchive {

    const val EXTENSION = "lumenzip"
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** Zips [projectDirectory] into [target] (a `.lumenzip`); returns the number of files written. */
    fun export(projectDirectory: File, target: File): Int {
        target.parentFile?.mkdirs()
        var count = 0
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            val root = projectDirectory.absolutePath
            projectDirectory.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.absolutePath.removePrefix(root).trimStart(File.separatorChar).replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(entryName))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        return count
    }

    /** Unpacks [archive] into `projectsDirectory/<name>`; returns the imported project, if valid. */
    fun import(archive: File, projectsDirectory: File, name: String = archive.nameWithoutExtension): ProjectEntry? {
        val target = ProjectStore.newProjectFolder(projectsDirectory, name)
        target.mkdirs()
        var written = 0
        ZipInputStream(FileInputStream(archive)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val safe = sanitize(entry.name)
                if (safe == null) {
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }
                val destination = File(target, safe)
                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    FileOutputStream(destination).use { out ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_ENTRY_BYTES) break
                            out.write(buffer, 0, read)
                        }
                        written++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (written == 0) {
            deleteFolder(target)
            return null
        }
        return ProjectStore.describe(target)
    }

    /** Installs a pack archive into `packs/<id>` inside [packsDirectory]; returns the pack id. */
    fun installPack(archive: File, packsDirectory: File, fallbackId: String = "imported"): String? {
        val probe = ZipInputStream(FileInputStream(archive))
        val id = peekPackId(probe) ?: fallbackId
        probe.close()
        val target = File(packsDirectory, id)
        target.mkdirs()
        ZipInputStream(FileInputStream(archive)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = sanitize(entry.name)
                if (name != null) {
                    // Packs may be zipped either flat or inside a single top folder; the flat
                    // layout is what the asset database expects, so strip one wrapper folder.
                    val relative = if (name.startsWith("$id/")) name.removePrefix("$id/") else name
                    val destination = File(target, relative)
                    if (entry.isDirectory) {
                        destination.mkdirs()
                    } else {
                        destination.parentFile?.mkdirs()
                        FileOutputStream(destination).use { out -> zip.copyTo(out) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return id
    }

    /** Reads `pack.json` from a pack archive (first match) to learn the pack id. */
    private fun peekPackId(zip: ZipInputStream): String? {
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name.endsWith("pack.json")) {
                val text = zip.readBytesLimited(64 * 1024)
                val json = runCatching { Json.parseObject(text) }.getOrNull()
                val id = json?.get("id")?.toString()
                if (!id.isNullOrBlank()) return sanitize(id)
            }
            entry = zip.nextEntry
        }
        return null
    }

    fun copyFolder(source: File, target: File): Int {
        var count = 0
        source.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.absolutePath.removePrefix(source.absolutePath).trimStart(File.separatorChar)
            val destination = File(target, relative)
            destination.parentFile?.mkdirs()
            file.copyTo(destination, overwrite = true)
            count++
        }
        return count
    }

    fun deleteFolder(directory: File): Boolean = directory.deleteRecursively()

    /** Rejects absolute paths and `..` escapes (zip-slip). */
    internal fun sanitize(name: String): String? {
        val cleaned = name.replace('\\', '/').trimStart('/')
        if (cleaned.isEmpty()) return null
        if (cleaned.contains("..")) return null
        if (cleaned.length > 200) return null
        return cleaned
    }

    private fun ZipInputStream.readBytesLimited(limit: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (total < limit) {
            val read = read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        closeEntry()
        return out.toString("UTF-8")
    }
}

/**
 * Seeds the games that ship inside the APK.
 *
 * The studio ships `assets/samples/{hello-lumen2d,pixel-platformer,neon-shooter}` — the exact
 * projects the engine exports with `--export-samples`, so what a player sees on a phone is what
 * `lumen2d --check` verified in CI.
 */
object SampleInstaller {

    /**
     * Copies missing samples into [projectsDirectory]; returns the folders it created.
     *
     * The copy itself lives in [ProjectSeeder] so the offline test suite can cover it; this method
     * only maps the result back onto `java.io.File`s for the studio's project list.
     */
    fun install(platform: AndroidPlatform, projectsDirectory: File): List<File> {
        projectsDirectory.mkdirs()
        val created = ProjectSeeder.installSamples(platform.bundledSamples, FileFileSystem(projectsDirectory))
        return created.map { File(projectsDirectory, it) }
    }

    /** Copies `assets/packs/<id>` into writable storage so the user can edit an imported pack. */
    fun installPack(platform: AndroidPlatform, packsDirectory: File, id: String): Int {
        packsDirectory.mkdirs()
        return ProjectSeeder.copyFolder(
            source = platform.bundledFileSystem,
            sourceRoot = "${AndroidPlatform.LIBRARY_FOLDER}/$id",
            target = FileFileSystem(packsDirectory),
            targetRoot = id,
        )
    }
}
