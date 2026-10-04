/**
 * Lumen2D — script hosting.
 *
 * Wires the [LumenScriptRuntime] into a [Game]. Scripts are resolved from the in-memory registry
 * first (tools and demos), then from the project folder, then from the asset database. Keeping this
 * in the core means the desktop player, the Android runtime, the editor and the CI preview
 * generator all run exactly the same gameplay code.
 */
package dev.lumen2d.core.script

import dev.lumen2d.core.game.Game
import dev.lumen2d.core.util.Log

/** Installs the scripting runtime on [game] and returns it. */
fun installLumenScripts(game: Game): LumenScriptRuntime {
    val runtime = LumenScriptRuntime(
        sourceProvider = { path -> resolveLumenScript(game, path) },
        sceneProvider = { path -> game.project?.loadScene(path) },
        changeScene = { path -> game.changeScene(path) },
        playSound = { id, volume -> game.resources.audioClip(id)?.let { game.mixer.play(it, "SFX", volume) } },
    )
    game.scriptRuntime = runtime
    Log.i("Script", "Script runtime installed (${game.scriptSources.size} registered source(s))")
    return runtime
}

/**
 * Finds a script's text.
 *
 * Resolution order: registered in-memory source → project file (with and without the `.lumen`
 * extension, `scripts/` prefix included) → asset library entry.
 */
fun resolveLumenScript(game: Game, path: String): String? {
    val trimmed = path.trim()
    val withExtension = when {
        trimmed.endsWith(".lumen") -> trimmed
        trimmed.contains('.') -> trimmed
        else -> "$trimmed.lumen"
    }
    game.scriptSources[trimmed]?.let { if (it.isNotEmpty()) return it }
    game.scriptSources[withExtension]?.let { if (it.isNotEmpty()) return it }

    val candidates = linkedSetOf(trimmed, withExtension, "scripts/$trimmed", "scripts/$withExtension")
    for (candidate in candidates) {
        game.project?.readText(candidate)?.let { if (it.isNotEmpty()) return it }
    }
    game.database.all().firstOrNull { meta ->
        candidates.any { candidate -> meta.path.endsWith("/$candidate") || meta.path == candidate }
    }?.let { meta -> return game.database.readText(meta.id) }
    return null
}
