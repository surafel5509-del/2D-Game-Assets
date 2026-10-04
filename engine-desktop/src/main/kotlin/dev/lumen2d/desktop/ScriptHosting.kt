/**
 * Lumen2D — desktop script hosting.
 *
 * Thin wrapper kept for the desktop CLI and tools: the implementation lives in the engine core
 * ([dev.lumen2d.core.script.installLumenScripts]) so Android, desktop and the editor share it.
 */
package dev.lumen2d.desktop

import dev.lumen2d.core.game.Game
import dev.lumen2d.core.script.LumenScriptRuntime

/** Installs the scripting runtime on [game] and returns it. */
fun installLumenScripts(game: Game): LumenScriptRuntime = dev.lumen2d.core.script.installLumenScripts(game)
