/**
 * Lumen2D — seeding bundled content into writable storage.
 *
 * The engine ships sample games and asset packs inside whatever the platform bundled (`assets/`
 * in the APK, a folder on the desktop). On first launch they are copied into the folder the user
 * can edit. That is a tree copy between two [VirtualFileSystem]s, so it lives here in the engine
 * core, next to [Project], instead of inside the Android layer — and the offline test suite can
 * cover it.
 *
 * The rule this file depends on: a filesystem answers `walk` with paths **relative to its own
 * root**, so every path it hands out can be given straight back to `readBytes`. A filesystem that
 * answers with root-prefixed paths makes callers nest whole projects under their own folder, which
 * is exactly how a fresh install ended up with zero games in the hub.
 */
package dev.lumen2d.core.game

import dev.lumen2d.core.platform.VirtualFileSystem

object ProjectSeeder {

    /**
     * Copies every top-level folder of [source] that [target] does not already have, and returns
     * the ids it created.
     *
     * The manifest is written last: a folder only counts as a project once `project.lumen` is in
     * it, so an interrupted copy can never appear as a half-installed game, and a re-run finishes
     * the job instead of duplicating anything.
     */
    fun installSamples(source: VirtualFileSystem, target: VirtualFileSystem): List<String> {
        val created = ArrayList<String>()
        for (id in runCatching { source.list("") }.getOrDefault(emptyList())) {
            if (target.exists("$id/${Project.MANIFEST}")) continue
            var copied = 0
            var manifest: String? = null
            for (file in source.walk(id)) {
                val relative = inside(file, id) ?: continue
                if (relative == Project.MANIFEST) {
                    manifest = file
                } else if (copy(source, target, file, "$id/$relative")) {
                    copied++
                }
            }
            val manifestFile = manifest
            val stamped = manifestFile != null && copy(source, target, manifestFile, "$id/${Project.MANIFEST}")
            if (copied > 0 && stamped) created.add(id) else target.delete(id, recursive = true)
        }
        return created
    }

    /**
     * Copies the tree under [sourceRoot] of [source] into [targetRoot] of [target] — the same
     * shape as [installSamples] but without the manifest rule, for asset packs. Returns the number
     * of files copied.
     */
    fun copyFolder(
        source: VirtualFileSystem,
        sourceRoot: String,
        target: VirtualFileSystem,
        targetRoot: String,
    ): Int {
        var copied = 0
        for (file in source.walk(sourceRoot)) {
            val relative = inside(file, sourceRoot) ?: continue
            if (copy(source, target, file, "$targetRoot/$relative")) copied++
        }
        return copied
    }

    /** [file] below [root], or null when the walk answered with a path from outside of it. */
    private fun inside(file: String, root: String): String? {
        if (root.isEmpty()) return file.takeIf { it.isNotEmpty() }
        val relative = file.removePrefix("$root/")
        return relative.takeIf { it.isNotEmpty() && relative != file }
    }

    private fun copy(source: VirtualFileSystem, target: VirtualFileSystem, from: String, to: String): Boolean =
        runCatching {
            target.mkdirs(to.substringBeforeLast('/', ""))
            target.writeBytes(to, source.readBytes(from))
            true
        }.getOrDefault(false)
}
