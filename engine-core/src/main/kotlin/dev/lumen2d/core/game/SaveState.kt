/**
 * Lumen2D — save-game state.
 *
 * Any node that joins the **`save_state`** group (scripts usually do it in `ready`) takes part in
 * save games: every inspector property that differs from the node's serialised scene default is
 * written to the slot file, plus the node's script variables when the runtime exposes them.
 *
 * ```kotlin
 * func ready():
 *     add_to_group("save_state")
 * ```
 */
package dev.lumen2d.core.game

import dev.lumen2d.core.scene.Node
import dev.lumen2d.core.scene.SceneTree
import dev.lumen2d.core.util.Json
import dev.lumen2d.core.util.str

/** Group name that opts a node into save games. */
const val SAVE_STATE_GROUP = "save_state"

/** A captured node: path in the tree plus the properties that changed at runtime. */
class NodeSaveState(val path: String, val properties: MutableMap<String, Any?> = LinkedHashMap())

object SaveStateCodec {

    /** Restores a value produced by [encode]. */
    fun decode(value: Any?): Any? = when (value) {
        is List<*> -> when (value.size) {
            2 -> dev.lumen2d.core.math.Vec2(
                (value[0] as? Number)?.toFloat() ?: 0f, (value[1] as? Number)?.toFloat() ?: 0f)
            4 -> dev.lumen2d.core.math.Rect(
                (value[0] as? Number)?.toFloat() ?: 0f, (value[1] as? Number)?.toFloat() ?: 0f,
                (value[2] as? Number)?.toFloat() ?: 0f, (value[3] as? Number)?.toFloat() ?: 0f)
            else -> value
        }
        is String -> if (value.startsWith("#")) dev.lumen2d.core.math.Color.fromHex(value) else value
        else -> value
    }

    /** Node types that are pure runtime decoration and never need saving. */
    private val transientTypes = setOf("ParticlesNode", "AudioPlayerNode", "AudioPlayer2DNode", "TrailNode")

    fun shouldSave(node: Node): Boolean =
        !node.isQueuedForDeletion &&
            SAVE_STATE_GROUP in node.groups &&
            node.typeName !in transientTypes

    /** Properties worth persisting: transform, visibility and script-exported state. */
    fun captureProperties(node: Node): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (def in node.propertyDefinitions()) {
            if (!def.persist) continue
            val value = node.getProperty(def.name) ?: continue
            out[def.name] = encode(value)
        }
        node.scriptBehavior?.exportedProperties?.forEach { (name, value) ->
            out["script.$name"] = encode(value)
        }
        return out
    }

    fun encode(value: Any?): Any? = when (value) {
        is dev.lumen2d.core.math.Vec2 -> listOf(value.x, value.y)
        is dev.lumen2d.core.math.Color -> value.toHex(true)
        is dev.lumen2d.core.math.Rect -> listOf(value.x, value.y, value.w, value.h)
        is Boolean, is String, is Number -> value
        is Enum<*> -> value.name
        else -> value?.toString()
    }
}

/** Save-slot metadata shown by the editor's "continue game" screen and the player app. */
class SaveSlot(
    val index: Int,
    val exists: Boolean,
    val title: String = "",
    val scene: String = "",
    val playTimeSeconds: Float = 0f,
    val savedAtMillis: Long = 0L,
    val extra: Map<String, Any?> = emptyMap(),
) {
    val playTimeText: String
        get() {
            val total = playTimeSeconds.toInt()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

    /** Relative age ("2 hours ago") without pulling in a date library. */
    fun ageText(now: Long = System.currentTimeMillis()): String {
        if (savedAtMillis <= 0L) return "—"
        val seconds = (now - savedAtMillis) / 1000
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3600} h ago"
            else -> "${seconds / 86_400} d ago"
        }
    }

    companion object {
        fun parse(index: Int, text: String): SaveSlot {
            val data = runCatching { Json.parseObject(text) }.getOrNull()
                ?: return SaveSlot(index, true)
            return SaveSlot(
                index = index, exists = true,
                title = data.str("title"),
                scene = data.str("scene"),
                playTimeSeconds = (data["playTime"] as? Number)?.toFloat() ?: 0f,
                savedAtMillis = (data["savedAt"] as? Number)?.toLong() ?: 0L,
                extra = (data["extra"] as? Map<*, *>)?.let { map ->
                    map.entries.associate { it.key.toString() to it.value }
                } ?: emptyMap(),
            )
        }
    }
}
