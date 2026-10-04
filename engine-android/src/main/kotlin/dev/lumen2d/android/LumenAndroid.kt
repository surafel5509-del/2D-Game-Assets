/**
 * Lumen2D — Android runtime facade.
 *
 * One place that turns a project folder on the device into a running [Game]:
 *
 * ```kotlin
 * val platform = LumenAndroid.platform(context)
 * val game = LumenAndroid.openGame(platform, File(filesDir, "lumen/projects/hello-lumen2d"))
 * view.open(game)
 * ```
 *
 * It owns nothing that the caller cannot dispose: the studio creates one game per open project and
 * recreates it when the user switches projects, exactly like the desktop CLI does.
 */
package dev.lumen2d.android

import android.content.Context
import dev.lumen2d.core.game.Game
import dev.lumen2d.core.game.Project
import dev.lumen2d.core.platform.FileFileSystem
import dev.lumen2d.core.platform.VfsScheme
import dev.lumen2d.core.script.installLumenScripts
import dev.lumen2d.core.util.Log
import java.io.File

object LumenAndroid {

    /** Creates the platform for [context] (application context is used internally). */
    @JvmStatic
    fun platform(context: Context): AndroidPlatform = AndroidPlatform(context)

    /**
     * Opens the project in [directory] as a runnable game: software renderer at the design
     * resolution, input map from the project, scripts resolved from the project folder.
     *
     * @param bundledPack optional pack folder inside the APK to mount as the library (`lib://`).
     */
    @JvmStatic
    fun openGame(platform: AndroidPlatform, directory: File): Game? {
        val project = openProject(directory) ?: return null
        val game = Game(platform, project)
        game.useSoftwareRenderer()
        installLumenScripts(game)
        Log.i("Android", "Game ready: '${project.config.title}' in ${directory.absolutePath}")
        return game
    }

    /** Opens a project folder without starting a game (the project manager uses this for cards). */
    @JvmStatic
    fun openProject(directory: File): Project? {
        val vfs = FileFileSystem(directory)
        val project = Project.open(vfs, "", VfsScheme.PROJECT)
        if (project == null) Log.w("Android", "No ${Project.MANIFEST} in ${directory.absolutePath}")
        return project
    }

    /** Creates a new project folder on the device. */
    @JvmStatic
    fun createProject(directory: File, title: String): Project {
        directory.mkdirs()
        val config = dev.lumen2d.core.game.GameConfig().also {
            it.title = title
            it.packageId = "dev.lumen2d.projects.${slug(title)}"
            it.assetPacks = arrayListOf("lib://${AndroidPlatform.BASE_PACK}")
        }
        val project = Project.create(FileFileSystem(directory), "", title, config)
        Log.i("Android", "Created project '${title}' at ${directory.absolutePath}")
        return project
    }

    /** Wires a prepared [Game] into a view and starts the loop. */
    @JvmStatic
    fun play(view: LumenGameView, platform: AndroidPlatform, directory: File): Game? {
        val game = openGame(platform, directory) ?: return null
        view.open(game)
        return game
    }

    /**
     * The engine's asset library as the editor sees it: the packs inside the APK plus the
     * provenance index that says where every file came from (`lib://sources`).
     */
    @JvmStatic
    fun library(platform: AndroidPlatform): dev.lumen2d.core.assets.AssetLibrary =
        dev.lumen2d.core.assets.AssetLibrary.load(
            platform.bundledFileSystem,
            AndroidPlatform.LIBRARY_FOLDER,
            AndroidPlatform.SOURCES_FOLDER,
        )

    /** Folder holding every project the user created or imported. */
    @JvmStatic
    fun projectsDirectory(platform: AndroidPlatform): File =
        File(platform.dataDirectory, "projects").also { it.mkdirs() }

    /** Folder holding game saves (`user://`). */
    @JvmStatic
    fun savesDirectory(platform: AndroidPlatform): File =
        File(platform.dataDirectory, "user").also { it.mkdirs() }

    internal fun slug(title: String): String =
        title.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_').ifEmpty { "project" }
}
