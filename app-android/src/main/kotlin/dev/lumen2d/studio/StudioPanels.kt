/**
 * Lumen2D Studio — asset browser, script editor and console.
 *
 * * [AssetBrowserView] renders the asset database (project files + `lib://` packs) as a grid of
 *   decoded thumbnails with their ids, types and sizes; tapping copies the id to the clipboard so
 *   it can be pasted straight into a `textureId` field.
 * * [ScriptEditorPanel] edits LumenScript files in place and re-registers them with the running
 *   game, which is the mobile equivalent of the editor's live-reload.
 * * [ConsolePanel] shows the engine log ring buffer (plus script errors) and per-frame counters.
 */
package dev.lumen2d.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.lumen2d.core.assets.AssetLibrary
import dev.lumen2d.core.assets.AssetMeta
import dev.lumen2d.core.assets.AssetSource
import dev.lumen2d.core.assets.AssetType
import dev.lumen2d.core.game.Game
import dev.lumen2d.core.render.Texture2D
import dev.lumen2d.android.LumenAndroid
import dev.lumen2d.core.util.Log
import dev.lumen2d.core.util.LogLevel

/** One asset row: decoded thumbnail + metadata. */
private class AssetCard(context: Context, val meta: AssetMeta) : LinearLayout(context) {
    var thumbnail: Bitmap? = null
}

class AssetBrowserView(private val context: Context, private val session: StudioSession) {

    /**
     * The engine's asset library (packs + provenance index) inside the APK. Loaded once: it is
     * what turns an asset card from a file name into "CC0-1.0, imported from Kenney".
     */
    private val library: AssetLibrary? by lazy {
        runCatching { LumenAndroid.library(session.platform) }.getOrNull()?.takeIf { it.packCount > 0 }
    }

    val view: LinearLayout = LinearLayout(context).also {
        it.orientation = LinearLayout.VERTICAL
        it.background = roundedBackground(Studio.PANEL)
        it.setPadding(context.dp(10f), context.dp(10f), context.dp(10f), context.dp(10f))
    }

    /** Which asset types are shown; empty means "everything". */
    var filter: Set<AssetType> = emptySet()

    var onPick: ((AssetMeta) -> Unit)? = null

    private val list = LinearLayout(context).also { it.orientation = LinearLayout.VERTICAL }

    init {
        val header = LinearLayout(context).also { it.orientation = LinearLayout.HORIZONTAL }
        header.addView(context.label("Assets", 14f, Studio.TEXT, bold = true))
        header.addView(context.pill("${session.game.database.size}"))
        val spacer = View(context)
        spacer.layoutParams = LinearLayout.LayoutParams(0, context.dp(1f), 1f)
        header.addView(spacer)
        header.addView(context.button("Rescan") { refresh() })
        view.addView(header)
        library?.let { lib ->
            val packs = lib.packs.joinToString(" · ") { pack ->
                "${pack.name} (${pack.license.ifEmpty { "?" }}, ${pack.manifest.fileCount})"
            }
            view.addView(context.dim("Library: ${lib.packCount} packs — $packs", 9f))
        } ?: view.addView(context.dim("Library: none bundled in this build", 9f))
        view.addView(context.divider())

        val scroll = ScrollView(context)
        scroll.isFillViewport = true
        scroll.addView(list)
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        view.addView(scroll)
        refresh()
    }

    fun refresh() {
        list.removeAllViews()
        session.game.database.scan(extractMetadata = true)
        val assets = session.game.database.all()
            .filter { filter.isEmpty() || it.type in filter }
            .sortedWith(compareBy({ it.type.id }, { it.displayName }))
        if (assets.isEmpty()) {
            list.addView(context.dim("No assets found. Import a pack or add files to the project."))
            return
        }
        var row: LinearLayout? = null
        for ((index, meta) in assets.withIndex()) {
            if (index % 4 == 0) {
                row = LinearLayout(context).also {
                    it.orientation = LinearLayout.HORIZONTAL
                    it.gravity = Gravity.TOP
                    list.addView(it)
                }
            }
            row?.addView(card(meta))
        }
    }

    /**
     * Provenance of the asset — which recipe or archive produced it, under which licence — or null
     * for project files the library knows nothing about. Library ids are `pack:path`, which is
     * exactly what the source index is keyed by, so the lookup is a map hit.
     */
    fun provenance(meta: AssetMeta): AssetSource? = library?.index?.provenance(meta.id)

    private fun card(meta: AssetMeta): View {
        val card = AssetCard(context, meta)
        card.orientation = LinearLayout.VERTICAL
        card.background = roundedBackground(Studio.PANEL_ALT)
        card.setPadding(context.dp(6f), context.dp(6f), context.dp(6f), context.dp(6f))
        card.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginEnd = context.dp(6f)
            it.bottomMargin = context.dp(6f)
        }

        val preview = ThumbnailView(context, meta, session.game)
        preview.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(56f))
        card.addView(preview)

        card.addView(context.label(meta.displayName.ifEmpty { meta.path.substringAfterLast('/') }, 11f, Studio.TEXT))
        card.addView(context.dim("${meta.type.id} · ${meta.sizeBytes} B", 9f))
        card.addView(context.dim(meta.id, 9f))
        provenance(meta)?.let { source ->
            // Licence and origin are the two things a shipping game has to be able to answer.
            card.addView(context.dim("${source.license.ifEmpty { "unknown" }} · ${source.origin.label}", 8f))
            if (source.recipe.isNotEmpty()) card.addView(context.dim(source.recipe, 8f))
        }
        if (isImported(meta)) {
            card.addView(context.pill("imported", Studio.WARN))
        }

        card.isClickable = true
        card.setOnClickListener { onPick?.invoke(meta) }
        card.setOnLongClickListener {
            val source = provenance(meta)
            if (source != null) {
                val text = "${meta.id}\n${source.license} — ${source.author}\n${source.describe()}"
                session.platform.device.setClipboard(text)
                session.platform.device.toast("Copied ${meta.id} with its licence")
            } else {
                session.platform.device.setClipboard(meta.id)
                session.platform.device.toast("Copied ${meta.id}")
            }
            true
        }
        return card
    }

    private fun isImported(meta: AssetMeta): Boolean =
        provenance(meta)?.origin == dev.lumen2d.core.assets.AssetOrigin.IMPORTED
}

/** Draws a decoded texture (or a colour swatch for non-image assets). */
private class ThumbnailView(
    context: Context,
    private val meta: AssetMeta,
    private val game: Game,
) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bitmap: Bitmap? = null
    private var tried = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Studio.PANEL_HIGH)
        ensureBitmap()
        val image = bitmap
        border.color = Studio.PANEL_HIGH
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), border.apply { style = Paint.Style.STROKE })
        if (image == null) {
            val tint = when (meta.type) {
                AssetType.AUDIO -> Studio.MINT
                AssetType.SCRIPT, AssetType.SCENE -> Studio.WARN
                AssetType.FONT -> Studio.ACCENT
                else -> Studio.TEXT_DIM
            }
            val label = meta.type.id.take(3).uppercase()
            paint.color = tint
            paint.textSize = height * 0.34f
            paint.typeface = Typeface.MONOSPACE
            val w = paint.measureText(label)
            canvas.drawText(label, (width - w) / 2f, height / 2f + paint.textSize * 0.35f, paint)
            return
        }
        // Fit the thumbnail inside the card, integer scaled so pixel art stays crisp.
        val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height).coerceAtLeast(0.1f)
        val drawW = image.width * scale
        val drawH = image.height * scale
        canvas.drawBitmap(image, (width - drawW) / 2f, (height - drawH) / 2f, paint)
    }

    private fun ensureBitmap() {
        if (tried) return
        tried = true
        if (meta.type != AssetType.TEXTURE && meta.type != AssetType.ATLAS) return
        val texture: Texture2D = runCatching { game.database.loadTexture(meta.id) }.getOrNull() ?: return
        val pixels = texture.pixels ?: return
        bitmap = Bitmap.createBitmap(pixels.pixels, texture.width, texture.height, Bitmap.Config.ARGB_8888)
    }
}

class ScriptEditorPanel(private val context: Context, private val session: StudioSession) {

    val view: LinearLayout = LinearLayout(context).also {
        it.orientation = LinearLayout.VERTICAL
        it.background = roundedBackground(Studio.PANEL)
        it.setPadding(context.dp(10f), context.dp(10f), context.dp(10f), context.dp(10f))
    }

    private val tabs = LinearLayout(context).also { it.orientation = LinearLayout.HORIZONTAL }
    private val editor = EditText(context).also {
        it.setTextColor(Studio.TEXT)
        it.textSize = 12f
        it.typeface = Typeface.MONOSPACE
        it.setBackgroundColor(Studio.PANEL_ALT)
        it.setPadding(context.dp(8f), context.dp(8f), context.dp(8f), context.dp(8f))
        it.gravity = Gravity.TOP or Gravity.START
        it.isSingleLine = false
    }
    private var currentPath: String? = null

    var onSaved: ((String) -> Unit)? = null

    init {
        val bar = LinearLayout(context).also { it.orientation = LinearLayout.HORIZONTAL }
        bar.addView(context.label("Scripts", 14f, Studio.TEXT, bold = true))
        val spacer = View(context)
        spacer.layoutParams = LinearLayout.LayoutParams(0, context.dp(1f), 1f)
        bar.addView(spacer)
        bar.addView(context.button("Save") { save() })
        bar.addView(context.button("Reload") { open(currentPath) })
        view.addView(bar)
        view.addView(context.divider())

        val tabScroll = HorizontalScrollView(context)
        tabScroll.addView(tabs)
        view.addView(tabScroll)

        val body = ScrollView(context)
        body.isFillViewport = true
        body.addView(editor)
        body.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        view.addView(body)

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Live reload: the running game picks the edit up on the next frame.
                val path = currentPath ?: return
                session.game.registerScript(path, s?.toString().orEmpty())
            }
        })

        refresh()
        session.project.index().scripts.firstOrNull()?.let { open(it) }
    }

    fun refresh() {
        tabs.removeAllViews()
        val scripts = session.project.index().scripts
        if (scripts.isEmpty()) {
            tabs.addView(context.dim("No scripts in this project."))
            return
        }
        for (path in scripts) {
            val name = path.substringAfterLast('/')
            val isCurrent = path == currentPath
            val button = context.button(name, primary = isCurrent) { open(path) }
            tabs.addView(button)
        }
    }

    fun open(path: String?) {
        val target = path ?: return
        val text = session.project.readText(target) ?: return
        currentPath = target
        editor.setText(text)
        editor.setSelection(0)
        session.game.registerScript(target, text)
        refresh()
    }

    fun save() {
        val path = currentPath ?: return
        val text = editor.text?.toString().orEmpty()
        session.project.saveText(path, text)
        session.game.registerScript(path, text)
        session.platform.device.toast("Saved $path")
        onSaved?.invoke(path)
    }
}

class ConsolePanel(private val context: Context, private val session: StudioSession) {

    val view: LinearLayout = LinearLayout(context).also {
        it.orientation = LinearLayout.VERTICAL
        it.background = roundedBackground(Studio.PANEL)
        it.setPadding(context.dp(10f), context.dp(10f), context.dp(10f), context.dp(10f))
    }

    private lateinit var outputScroll: ScrollView

    private val output = TextView(context).also {
        it.setTextColor(Studio.TEXT)
        it.textSize = 11f
        it.typeface = Typeface.MONOSPACE
    }
    private val stats = TextView(context).also {
        it.setTextColor(Studio.TEXT_DIM)
        it.textSize = 11f
        it.typeface = Typeface.MONOSPACE
    }
    private val lines = ArrayList<String>()

    init {
        val bar = LinearLayout(context).also { it.orientation = LinearLayout.HORIZONTAL }
        bar.addView(context.label("Console", 14f, Studio.TEXT, bold = true))
        val spacer = View(context)
        spacer.layoutParams = LinearLayout.LayoutParams(0, context.dp(1f), 1f)
        bar.addView(spacer)
        bar.addView(context.button("Clear") {
            lines.clear()
            Log.clear()
            output.setText("")
        })
        view.addView(bar)
        view.addView(stats)
        view.addView(context.divider())

        val scroll = ScrollView(context)
        scroll.isFillViewport = true
        scroll.addView(output)
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        outputScroll = scroll
        view.addView(scroll)

        Log.addListener { record ->
            val tag = when (record.level) {
                LogLevel.ERROR -> "E"
                LogLevel.WARN -> "W"
                LogLevel.INFO -> "I"
                else -> "D"
            }
            append("[$tag] ${record.tag}: ${record.message}")
        }
        refresh()
    }

    private fun append(line: String) {
        lines.add(line)
        while (lines.size > 400) lines.removeAt(0)
        output.setText(lines.joinToString("\n"))
        if (::outputScroll.isInitialized) outputScroll.fullScroll(ScrollView.FOCUS_DOWN)
    }

    fun refresh() {
        val summary = session.game.performanceSummary()
        stats.setText(buildString {
            append("fps ${summary["fps"]?.toInt() ?: 0}  ")
            append("frame ${"%.1f".format(summary["frameMs"] ?: 0f)} ms  ")
            append("nodes ${summary["nodes"]?.toInt() ?: 0}  ")
            append("draws ${summary["drawCalls"]?.toInt() ?: 0}  ")
            append("sprites ${summary["sprites"]?.toInt() ?: 0}  ")
            append("bodies ${summary["bodies"]?.toInt() ?: 0}  ")
            append("textures ${summary["textures"]?.toInt() ?: 0}  ")
            append("assets ${summary["assets"]?.toInt() ?: 0}")
        })
        if (lines.isEmpty()) {
            lines.add("Lumen2D Studio — project '${session.project.config.title}' loaded.")
            lines.add("Scene: ${session.scenePath}")
            output.setText(lines.joinToString("\n"))
        }
    }
}
