/**
 * Lumen2D Studio — the editor.
 *
 * One screen, five panels over a live viewport:
 *
 * ```
 * ┌── toolbar: project · scene · Play/Stop · Save · Undo/Redo · Export ─────────┐
 * │ viewport (LumenGameView running the real engine, tap to select nodes)       │
 * ├── tabs: Scene | Inspector | Assets | Scripts | Console ─────────────────────┤
 * │ active panel                                                                │
 * └─────────────────────────────────────────────────────────────────────────────┘
 * ```
 *
 * Because the viewport runs the engine itself, "Play" is not a separate mode with a separate
 * renderer: it simply lets the frame clock advance. Editing a property, renaming a node or moving it
 * in the tree goes through the same `EditAction` undo stack, and the viewport re-renders on demand.
 */
package dev.lumen2d.studio

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.lumen2d.android.AndroidFilePicker
import dev.lumen2d.android.AndroidPlatform
import dev.lumen2d.android.LumenAndroid
import dev.lumen2d.android.LumenGameView
import dev.lumen2d.android.ProjectArchive
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.scene.Node
import dev.lumen2d.core.scene.Node2D
import dev.lumen2d.core.scene.NodeRegistry
import java.io.File

class StudioActivity : Activity() {

    private lateinit var platform: AndroidPlatform
    private lateinit var session: StudioSession
    private lateinit var viewport: LumenGameView
    private lateinit var treeView: SceneTreeView
    private lateinit var inspector: InspectorView
    private lateinit var assetBrowser: AssetBrowserView
    private lateinit var scripts: ScriptEditorPanel
    private lateinit var console: ConsolePanel
    private lateinit var statusLabel: android.widget.TextView
    private lateinit var tabBar: LinearLayout
    private lateinit var panelHost: FrameLayout

    private var playing = false
    private var activeTab = TAB_SCENE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = LumenAndroid.platform(this)
        val directory = File(intent.getStringExtra(EXTRA_PROJECT_DIR) ?: "")
        val opened = StudioSession.open(platform, directory)
        if (opened == null) {
            platform.device.toast("Cannot open ${directory.absolutePath} — no ${"project.lumen"}")
            finish()
            return
        }
        session = opened
        session.onChanged = { onSessionChanged() }
        setContentView(buildUi())
        session.openScene(session.scenePath)
        session.selection = session.scene?.root
        val autoplay = intent.getBooleanExtra(EXTRA_AUTOPLAY, false)
        setPlaying(autoplay)
        showTab(TAB_SCENE)
        refreshAll()
    }

    override fun onPause() {
        super.onPause()
        viewport.pause()
    }

    override fun onResume() {
        super.onResume()
        if (playing) viewport.resume() else renderOnce()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewport.close()
        session.close()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT) {
            picker?.deliver(data, resultCode == RESULT_OK)
        }
    }

    private var picker: AndroidFilePicker? = null

    // --------------------------------------------------------------------- layout

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Studio.BG)

        root.addView(toolbar())

        val viewportHost = FrameLayout(this)
        viewport = LumenGameView(this)
        viewport.overlay = { canvas, _ -> drawEditorOverlay(canvas) }
        viewport.onDesignTouch = { design, down -> handleViewportTouch(design, down) }
        viewport.onDesignTap = { design -> tapSelect(design) }
        viewport.showPerformanceOverlay = false
        viewport.open(session.game, startNow = false)
        viewportHost.addView(viewport)
        viewportHost.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3.4f)
        root.addView(viewportHost)

        root.addView(tabStrip())

        panelHost = FrameLayout(this)
        panelHost.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2.6f)
        root.addView(panelHost)

        root.addView(statusBar())
        return root
    }

    private fun toolbar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))

        bar.addView(label(session.project.config.title, 15f, Studio.TEXT, bold = true))
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(0, dp(1f), 1f)
        bar.addView(spacer)

        bar.addView(button("◀ Hub") { finish() })
        bar.addView(button("Play") { setPlaying(true) })
        bar.addView(button("Pause") { setPlaying(false) })
        bar.addView(button("Save", primary = true) { saveScene() })
        bar.addView(button("Undo") { session.undo() })
        bar.addView(button("Redo") { session.redo() })
        bar.addView(button("Export") { exportProject() })
        return bar
    }

    private fun tabStrip(): View {
        tabBar = LinearLayout(this)
        tabBar.orientation = LinearLayout.HORIZONTAL
        tabBar.setPadding(dp(10f), 0, dp(10f), dp(6f))
        for ((id, name) in TABS) {
            tabBar.addView(button(name, primary = id == activeTab) { showTab(id) })
        }
        return tabBar
    }

    private fun statusBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(10f), dp(6f), dp(10f), dp(10f))
        statusLabel = dim("")
        statusLabel.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(statusLabel)
        bar.addView(dim(session.scenePath, 10f))
        return bar
    }

    // ---------------------------------------------------------------------- tabs

    private fun showTab(id: Int) {
        activeTab = id
        panelHost.removeAllViews()
        val panel: View = when (id) {
            TAB_SCENE -> scenePanel()
            TAB_INSPECTOR -> inspectorPanel()
            TAB_ASSETS -> assetsPanel()
            TAB_SCRIPTS -> scriptsPanel()
            else -> consolePanel()
        }
        panelHost.addView(panel)
        // Rebuild the tab strip so the active tab is highlighted.
        val strip = tabBar
        for (index in 0 until strip.childCount) {
            val child = strip.getChildAt(index) ?: continue
            child.background = roundedBackground(
                if (TABS[index].first == id) Studio.ACCENT_SOFT else Studio.PANEL_ALT, 8f, Studio.PANEL_HIGH, 1f,
            )
        }
        refreshAll()
    }

    private fun scenePanel(): View {
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.background = roundedBackground(Studio.PANEL)
        column.setPadding(dp(10f), dp(10f), dp(10f), dp(10f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.addView(label("Scene", 14f, Studio.TEXT, bold = true))
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(0, dp(1f), 1f)
        bar.addView(spacer)
        bar.addView(button("Open…") { sceneChooser() })
        bar.addView(button("+ Node") { nodeTypeChooser() })
        bar.addView(button("Duplicate") { session.duplicateNode(session.selection) })
        bar.addView(button("Rename") { renameDialog(session.selection) })
        bar.addView(button("Delete") { session.deleteNode(session.selection) })
        column.addView(bar)
        column.addView(divider())

        treeView = SceneTreeView(this)
        treeView.root = session.scene?.root
        treeView.selection = session.selection
        treeView.onSelect = { node ->
            session.selection = node
            treeView.selection = node
            viewport.selection = node as? Node2D
            inspector.show(node)
            refreshAll()
        }
        treeView.onMenu = { node -> nodeMenu(node) }
        treeView.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        column.addView(treeView)
        return column
    }

    private fun inspectorPanel(): View {
        inspector = InspectorView(this)
        inspector.onEdit = { node, property, value, _ ->
            // Property text edits arrive per keystroke: apply them straight to the node (cheap),
            // and only push an undo entry when the value actually differs.
            if (property == "name") {
                session.renameNode(node, value?.toString().orEmpty())
                treeView.refresh()
            } else if (node.getProperty(property) != value) {
                session.setProperty(node, property, value)
            }
            renderOnce()
        }
        inspector.show(session.selection)
        return scrolling(inspector.view)
    }

    private fun assetsPanel(): View {
        assetBrowser = AssetBrowserView(this, session)
        assetBrowser.onPick = { meta ->
            // Picking an asset copies its id — the same action as the desktop editor's
            // "copy path", which is what a textureId / scriptPath field expects.
            platform.device.setClipboard(meta.id)
            platform.device.toast("Copied ${meta.id}")
        }
        return assetBrowser.view
    }

    private fun scriptsPanel(): View {
        scripts = ScriptEditorPanel(this, session)
        scripts.onSaved = { path ->
            platform.device.toast("Saved $path")
            renderOnce()
        }
        return scripts.view
    }

    private fun consolePanel(): View {
        console = ConsolePanel(this, session)
        return console.view
    }

    // ------------------------------------------------------------------ playback

    private fun setPlaying(value: Boolean) {
        playing = value
        if (playing) {
            session.reloadScripts()
            viewport.resume()
        } else {
            viewport.pause()
            renderOnce()
        }
        refreshAll()
    }

    /** Renders one frame at delta 0 so edits appear immediately while paused. */
    private fun renderOnce() {
        runCatching { session.game.frame(0f) }
    }

    // -------------------------------------------------------------- interactions

    /**
     * While paused the viewport is the scene editor: dragging moves the node under the finger.
     * The move is applied live and the whole drag becomes a single undo entry when it ends.
     */
    private fun handleViewportTouch(design: Vec2, phase: Int): Boolean {
        if (playing) return false
        val world = viewport.designToWorld(design)
        when (phase) {
            LumenGameView.TOUCH_DOWN -> {
                val hit = pickNodeAt(world) as? Node2D ?: return false
                session.selection = hit
                treeView.selection = hit
                viewport.selection = hit
                inspector.show(hit)
                dragNode = hit
                dragStart = hit.globalPosition
                dragGrab = world - hit.globalPosition
                dragMoved = false
                return true
            }
            LumenGameView.TOUCH_MOVE -> {
                val node = dragNode ?: return false
                val target = world - dragGrab
                if (!dragMoved && (target - dragStart).length < 2f) return true
                dragMoved = true
                node.setGlobalPosition(target)
                renderOnce()
                return true
            }
            LumenGameView.TOUCH_UP -> {
                val node = dragNode ?: return false
                dragNode = null
                val from = dragStart
                val to = node.globalPosition
                if (dragMoved && to.distanceTo(from) > 0.5f) {
                    // Rewind, then apply through the undo stack so the drag is one entry.
                    node.setGlobalPosition(from)
                    session.perform(object : EditAction {
                        override val label: String = "Move ${node.name}"
                        override fun apply() { node.setGlobalPosition(to) }
                        override fun revert() { node.setGlobalPosition(from) }
                    })
                }
                viewport.selection = node
                inspector.show(node)
                refreshAll()
                return true
            }
        }
        return false
    }

    private var dragNode: Node2D? = null
    private var dragStart: Vec2 = Vec2.ZERO
    private var dragGrab: Vec2 = Vec2.ZERO
    private var dragMoved = false

    private fun tapSelect(design: Vec2): Boolean {
        if (playing) return false
        val world = viewport.designToWorld(design)
        val hit = pickNodeAt(world) ?: return false
        session.selection = hit
        treeView.selection = hit
        treeView.scrollSelectionIntoView()
        viewport.selection = hit as? Node2D
        inspector.show(hit)
        refreshAll()
        return true
    }

    /** Depth-first hit test in world space (topmost node wins). */
    private fun pickNodeAt(world: Vec2): Node? {
        val root = session.scene?.root ?: return null
        var best: Node? = null
        for (node in root.descendants()) {
            val position = (node as? Node2D)?.globalPosition ?: continue
            if (kotlin.math.abs(position.x - world.x) <= 12f && kotlin.math.abs(position.y - world.y) <= 12f) {
                best = node
            }
        }
        return best
    }

    private fun drawEditorOverlay(canvas: android.graphics.Canvas) {
        // The selection highlight itself is drawn by LumenGameView; the editor adds a paused badge.
        if (!playing) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = Studio.WARN
            paint.textSize = 11f * resources.displayMetrics.density
            canvas.drawText("paused — tap a node to select", 12f * resources.displayMetrics.density, 20f * resources.displayMetrics.density, paint)
        }
    }

    private fun nodeMenu(node: Node) {
        val options = arrayOf("Add child node…", "Duplicate", "Rename…", "Move to scene root", "Delete")
        AlertDialog.Builder(this)
            .setTitle(node.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> nodeTypeChooser(node)
                    1 -> session.duplicateNode(node)
                    2 -> renameDialog(node)
                    3 -> session.scene?.root?.let { session.moveNode(node, it) }
                    else -> session.deleteNode(node)
                }
                refreshAll()
            }
            .show()
    }

    private fun nodeTypeChooser(parent: Node? = session.selection) {
        val types = NodeRegistry.creatableTypes().map { it.name }.sorted()
        val labels = types.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("Add node")
            .setItems(labels) { _, which ->
                session.selection = parent
                session.addNode(types[which])
                refreshAll()
            }
            .show()
    }

    private fun renameDialog(node: Node?) {
        val target = node ?: return
        val field = textField(target.name)
        AlertDialog.Builder(this)
            .setTitle("Rename node")
            .setView(field)
            .setPositiveButton("Rename") { _, _ ->
                session.renameNode(target, field.text.toString())
                refreshAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sceneChooser() {
        val scenes = session.project.index().scenes
        if (scenes.isEmpty()) {
            platform.device.toast("No scenes in this project")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Open scene")
            .setItems(scenes.toTypedArray<CharSequence>()) { _, which ->
                if (session.openScene(scenes[which])) {
                    treeView.root = session.scene?.root
                    refreshAll()
                }
            }
            .show()
    }

    private fun saveScene() {
        val payload = saveSceneInternal()
        platform.device.toast(if (payload) "Saved ${session.scenePath}" else "Nothing to save")
    }

    private fun saveSceneInternal(): Boolean {
        val ok = session.save()
        if (ok) refreshAll()
        return ok
    }

    private fun exportProject() {
        val root = getExternalFilesDir(null)?.let { File(it, "exports") } ?: File(filesDir, "exports")
        root.mkdirs()
        val target = File(root, "${session.directory.name}.${ProjectArchive.EXTENSION}")
        val files = ProjectArchive.export(session.directory, target)
        platform.device.shareText(
            "Lumen2D project '${session.project.config.title}' — $files files, exported to ${target.absolutePath}",
            "Lumen2D project export",
        )
        platform.device.toast("Exported $files files")
    }

    // ------------------------------------------------------------------ refresh

    private fun onSessionChanged() {
        treeView?.let {
            it.root = session.scene?.root
            it.selection = session.selection
        }
        viewport.selection = session.selection as? Node2D
        refreshAll()
    }

    private fun refreshAll() {
        val node = session.selection
        statusLabel?.text = buildString {
            append(if (playing) "▶ playing" else "❚❚ paused")
            append("  ·  ")
            append("${session.scene?.root?.descendants()?.size ?: 0} nodes")
            append("  ·  ")
            append("${session.project.index().scenes.size} scenes")
            if (session.unsaved > 0) append("  ·  ${session.unsaved} unsaved")
            if (node != null) append("  ·  sel ${node.name}")
        }
    }

    companion object {
        const val EXTRA_PROJECT_DIR = "project_dir"
        const val EXTRA_AUTOPLAY = "autoplay"

        private const val TAB_SCENE = 0
        private const val TAB_INSPECTOR = 1
        private const val TAB_ASSETS = 2
        private const val TAB_SCRIPTS = 3
        private const val TAB_CONSOLE = 4
        private const val REQUEST_IMPORT = 0x4C12

        private val TABS = listOf(
            TAB_SCENE to "Scene",
            TAB_INSPECTOR to "Inspector",
            TAB_ASSETS to "Assets",
            TAB_SCRIPTS to "Scripts",
            TAB_CONSOLE to "Console",
        )
    }
}
