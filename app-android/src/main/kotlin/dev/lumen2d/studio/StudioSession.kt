/**
 * Lumen2D Studio — an open project plus its undo history.
 *
 * The editor works on the *same* [Game]/[Project] objects the runtime uses: a scene is a scene tree,
 * a node is a node, a property is a `PropertyDef`. That means the inspector, the tree view and the
 * viewport can never drift from what the engine will do at runtime — and it means the undo stack is
 * just a list of edits applied to the live tree.
 */
package dev.lumen2d.studio

import dev.lumen2d.android.AndroidPlatform
import dev.lumen2d.android.LumenAndroid
import dev.lumen2d.core.game.Game
import dev.lumen2d.core.game.Project
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.scene.Node
import dev.lumen2d.core.scene.Node2D
import dev.lumen2d.core.scene.NodeRegistry
import dev.lumen2d.core.scene.Scene
import java.io.File

/** One undoable edit. `apply()` is used both for "do" and "redo". */
interface EditAction {
    val label: String
    fun apply()
    fun revert()
}

class AddNodeAction(private val parent: Node, private val node: Node, private val index: Int) : EditAction {
    override val label: String get() = "Add ${node.name}"
    override fun apply() = parent.addChild(node, index)
    override fun revert() = parent.removeChild(node)
}

class RemoveNodeAction(private val parent: Node, private val node: Node, private val index: Int) : EditAction {
    override val label: String get() = "Delete ${node.name}"
    override fun apply() = parent.removeChild(node)
    override fun revert() = parent.addChild(node, index)
}

class RenameNodeAction(private val node: Node, private val from: String, private val to: String) : EditAction {
    override val label: String get() = "Rename $from"
    override fun apply() { node.name = to }
    override fun revert() { node.name = from }
}

class SetPropertyAction(private val node: Node, private val property: String, private val from: Any?, private val to: Any?) : EditAction {
    override val label: String get() = "Set $property"
    override fun apply() { node.applyProperty(property, to) }
    override fun revert() { node.applyProperty(property, from) }
}

class StudioSession private constructor(
    val platform: AndroidPlatform,
    val directory: File,
    val project: Project,
    val game: Game,
) {

    /** Path of the scene currently open in the viewport, relative to the project folder. */
    var scenePath: String = project.config.startScene

    /** Currently selected node (inspector + viewport highlight). */
    var selection: Node? = null

    private val undoStack = ArrayList<EditAction>()
    private val redoStack = ArrayList<EditAction>()

    /** Edits since the last save. */
    var unsaved: Int = 0
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    val scene: Scene? get() = game.currentScene

    /** Broadcast so the tree/inspector/status bar can refresh after an edit. */
    var onChanged: (() -> Unit)? = null

    init {
        if (project.config.startScene.isNotBlank()) scenePath = project.config.startScene
    }

    fun openScene(path: String? = null): Boolean {
        val target = path ?: scenePath
        if (target.isBlank()) return false
        val loaded = game.changeScene(target)
        if (loaded) {
            scenePath = target
            selection = game.currentScene?.root
            onChanged?.invoke()
        }
        return loaded
    }

    fun select(node: Node?) {
        selection = node
        onChanged?.invoke()
    }

    // ------------------------------------------------------------------- editing

    fun perform(action: EditAction) {
        action.apply()
        undoStack.add(action)
        redoStack.clear()
        unsaved++
        onChanged?.invoke()
    }

    fun undo() {
        val action = undoStack.removeLastOrNull() ?: return
        action.revert()
        redoStack.add(action)
        unsaved++
        onChanged?.invoke()
    }

    fun redo() {
        val action = redoStack.removeLastOrNull() ?: return
        action.apply()
        undoStack.add(action)
        unsaved++
        onChanged?.invoke()
    }

    /** Adds a node of [typeName] under the selection (or the scene root). */
    fun addNode(typeName: String): Node? {
        val root = scene?.root ?: return null
        val parent = selection ?: root
        val node = NodeRegistry.create(typeName) ?: return null
        node.name = uniqueName(parent, typeName)
        val index = parent.children.size
        perform(AddNodeAction(parent, node, index))
        selection = node
        return node
    }

    fun deleteNode(node: Node?) {
        val target = node ?: return
        val parent = target.parent ?: return
        val index = parent.children.indexOf(target)
        perform(RemoveNodeAction(parent, target, index))
        if (selection === target) selection = parent
    }

    fun duplicateNode(node: Node?) {
        val target = node ?: return
        val parent = target.parent ?: return
        val copy = target.duplicateNode()
        copy.name = uniqueName(parent, target.name)
        perform(AddNodeAction(parent, copy, parent.children.indexOf(target) + 1))
        selection = copy
    }

    fun renameNode(node: Node?, name: String) {
        val target = node ?: return
        val clean = name.trim().ifEmpty { target.name }
        if (clean == target.name) return
        perform(RenameNodeAction(target, target.name, clean))
    }

    fun setProperty(node: Node?, property: String, value: Any?) {
        val target = node ?: return
        val current = target.getProperty(property)
        perform(SetPropertyAction(target, property, current, value))
    }

    /** Moves [node] to [newParent]; used by the tree's "move up/down" and long-press drag. */
    fun moveNode(node: Node?, newParent: Node) {
        val target = node ?: return
        val oldParent = target.parent ?: return
        if (newParent === target) return
        var cursor: Node? = newParent
        while (cursor != null) {
            if (cursor === target) return   // refuse to create a cycle
            cursor = cursor.parent
        }
        val oldIndex = oldParent.children.indexOf(target)
        perform(object : EditAction {
            override val label: String = "Move ${target.name}"
            override fun apply() {
                oldParent.removeChild(target)
                newParent.addChild(target)
            }
            override fun revert() {
                newParent.removeChild(target)
                oldParent.addChild(target, oldIndex.coerceAtLeast(0))
            }
        })
    }

    fun uniqueName(parent: Node, wanted: String): String {
        val base = wanted.removeSuffix("Node").ifEmpty { "Node" }
        var candidate = base
        var index = 2
        while (parent.child(candidate) != null) {
            candidate = "${base}${index}"
            index++
        }
        return candidate
    }

    // ------------------------------------------------------------------ persistence

    /** Saves the open scene (and the project manifest) through the normal project API. */
    fun save(): Boolean {
        val root = scene ?: return false
        project.saveScene(root, scenePath)
        project.save()
        unsaved = 0
        onChanged?.invoke()
        return true
    }

    /** Registers every script of the project so the running game reloads edited sources. */
    fun reloadScripts() {
        for (path in project.index().scripts) {
            project.readText(path)?.let { game.registerScript(path, it) }
        }
    }

    fun close() {
        runCatching { game.requestQuit() }
    }

    companion object {
        /** Opens a project folder and prepares a game for the viewport. */
        fun open(platform: AndroidPlatform, directory: File): StudioSession? {
            val project = LumenAndroid.openProject(directory) ?: return null
            val game = LumenAndroid.openGame(platform, directory) ?: return null
            val session = StudioSession(platform, directory, project, game)
            session.reloadScripts()
            return session
        }
    }
}

/** Convenience for the viewport overlays: world position of a node, or zero. */
fun Node.worldPosition(): Vec2 = (this as? Node2D)?.globalPosition ?: Vec2.ZERO
