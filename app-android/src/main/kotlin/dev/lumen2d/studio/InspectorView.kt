/**
 * Lumen2D Studio — inspector.
 *
 * Builds one row per property from the node's own `propertyDefinitions()`, so a node type declared
 * anywhere in the engine (or by a project) gets a correct inspector for free. Types map to editors:
 * numbers → sliders/number fields, booleans → check boxes, colours → hex field plus swatch, colours
 * and vectors → text fields with the engine's parsing rules, textures/scripts → asset pickers.
 */
package dev.lumen2d.studio

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType as AndroidInputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.lumen2d.core.math.Color as LumenColor
import dev.lumen2d.core.math.Rect
import dev.lumen2d.core.math.Vec2
import dev.lumen2d.core.scene.Node
import dev.lumen2d.core.scene.PropertyDef
import dev.lumen2d.core.scene.PropertyType

class InspectorView(private val context: Context) {

    /** The panel the activity puts on screen. */
    val view: LinearLayout = LinearLayout(context).also {
        it.orientation = LinearLayout.VERTICAL
        it.background = roundedBackground(Studio.PANEL)
        it.setPadding(context.dp(10f), context.dp(10f), context.dp(10f), context.dp(10f))
    }

    /** Called when the user edits a value: (node, property, newValue, isFinal). */
    var onEdit: ((Node, String, Any?, Boolean) -> Unit)? = null

    var node: Node? = null
        private set

    fun show(node: Node?) {
        this.node = node
        view.removeAllViews()
        if (node == null) {
            view.addView(context.dim("Select a node in the scene tree or tap it in the viewport."))
            return
        }
        view.addView(
            context.label("${node.name}  ·  ${node.typeName}", 14f, Studio.TEXT, bold = true),
        )
        view.addView(context.dim("path: ${runCatching { node.nodePath() }.getOrDefault("?")}"))
        view.addView(context.divider())

        val definitions = runCatching { node.propertyDefinitions() }.getOrDefault(emptyList())
            .filter { it.name != "name" }
        if (definitions.isEmpty()) {
            view.addView(context.dim("This node type has no editable properties."))
        }
        for (definition in definitions.sortedBy { it.category }) {
            addRow(node, definition)
        }

        view.addView(context.divider())
        addIdentityRows(node)
    }

    private fun addIdentityRows(node: Node) {
        val row = context.row(
            context.label("Name", 12f, Studio.TEXT_DIM),
        )
        val name = context.textField(node.name)
        name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.trim().orEmpty()
                if (value.isNotEmpty() && value != node.name) onEdit?.invoke(node, "name", value, true)
            }
        })
        row.addView(name)
        view.addView(row)

        val visible = CheckBox(context)
        visible.text = "Visible"
        visible.isChecked = node.visible
        visible.setTextColor(Studio.TEXT)
        visible.textSize = 12f
        visible.setOnCheckedChangeListener { _, checked -> onEdit?.invoke(node, "visible", checked, true) }
        view.addView(visible)
    }

    private fun addRow(node: Node, definition: PropertyDef) {
        val current = runCatching { node.getProperty(definition.name) }.getOrNull()
        val caption = context.row(
            context.label(definition.name, 12f, Studio.TEXT),
        )
        if (definition.category.isNotEmpty()) {
            val tag = context.label(definition.category, 9f, Studio.TEXT_DIM)
            tag.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            caption.addView(tag)
        }
        view.addView(caption)

        val editor: View = when (definition.type) {
            PropertyType.BOOL -> boolEditor(node, definition, current as? Boolean ?: false)
            PropertyType.FLOAT -> floatEditor(node, definition, (current as? Number)?.toFloat() ?: 0f)
            PropertyType.INT -> intEditor(node, definition, (current as? Number)?.toInt() ?: 0)
            PropertyType.COLOR -> colorEditor(node, definition, current)
            PropertyType.VECTOR2 -> vectorEditor(node, definition, current as? Vec2 ?: Vec2.ZERO)
            PropertyType.RECT -> rectEditor(node, definition, current as? Rect ?: Rect.ZERO)
            PropertyType.TEXTURE, PropertyType.REGION -> textEditor(node, definition, current?.toString().orEmpty(), "asset id (lib://packs/base/…)")
            PropertyType.NODE_PATH -> textEditor(node, definition, current?.toString().orEmpty(), "node path (Gameplay/Player)")
            PropertyType.ENUM, PropertyType.FLAGS -> enumEditor(node, definition, current?.toString().orEmpty())
            PropertyType.STRING -> textEditor(node, definition, current?.toString().orEmpty(), "")
            else -> textEditor(node, definition, current?.toString().orEmpty(), "")
        }
        view.addView(editor)
        view.addView(context.spacer(4f))
    }

    private fun boolEditor(node: Node, def: PropertyDef, value: Boolean): View {
        val box = CheckBox(context)
        box.isChecked = value
        box.text = if (value) "on" else "off"
        box.setTextColor(Studio.TEXT)
        box.textSize = 12f
        box.setOnCheckedChangeListener { button, checked ->
            button?.text = if (checked) "on" else "off"
            onEdit?.invoke(node, def.name, checked, true)
        }
        return box
    }

    private fun floatEditor(node: Node, def: PropertyDef, value: Float): View {
        val min = def.min ?: 0f
        val max = def.max ?: 0f
        // Sliders are only meaningful for bounded properties; everything else gets a number field.
        if (max > min) {
            val span = max - min
            return context.sliderRow(def.name, ((value - min) / span).coerceIn(0f, 1f)) { fraction ->
                onEdit?.invoke(node, def.name, min + fraction * span, false)
            }
        }
        return numberField(node, def, value.toString())
    }

    private fun intEditor(node: Node, def: PropertyDef, value: Int): View = numberField(node, def, value.toString())

    private fun numberField(node: Node, def: PropertyDef, initial: String): EditText {
        val field = context.textField(initial)
        field.inputType = AndroidInputType.TYPE_CLASS_NUMBER or AndroidInputType.TYPE_NUMBER_FLAG_DECIMAL or
            AndroidInputType.TYPE_NUMBER_FLAG_SIGNED
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim().orEmpty()
                if (text.isEmpty() || text == "-") return
                if (def.type == PropertyType.INT) {
                    text.toIntOrNull()?.let { onEdit?.invoke(node, def.name, it, true) }
                } else {
                    text.toFloatOrNull()?.let { onEdit?.invoke(node, def.name, it, true) }
                }
            }
        })
        return field
    }

    private fun textEditor(node: Node, def: PropertyDef, value: String, hintText: String): EditText {
        val field = context.textField(value, hintText)
        field.addTextChangedListener(debounced { text ->
            onEdit?.invoke(node, def.name, text, true)
        })
        return field
    }

    /** Fires on every keystroke, but the engine side is cheap (a property assignment). */
    private fun debounced(action: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            action(s?.toString().orEmpty())
        }
    }

    private fun colorEditor(node: Node, def: PropertyDef, value: Any?): View {
        val hex = when (value) {
            is LumenColor -> value.toHex(includeAlpha = true)
            null -> "#FFFFFFFF"
            else -> value.toString()
        }
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val swatch = View(context)
        val color = runCatching { LumenColor.fromHex(hex) }.getOrDefault(LumenColor.WHITE)
        val drawable = GradientDrawable()
        drawable.setColor((color.a * 255).toInt() shl 24 or ((color.r * 255).toInt() shl 16) or
            ((color.g * 255).toInt() shl 8) or (color.b * 255).toInt())
        drawable.setCornerRadius(context.dp(4f).toFloat())
        drawable.setStroke(1, Studio.PANEL_HIGH)
        swatch.background = drawable
        swatch.layoutParams = LinearLayout.LayoutParams(context.dp(28f), context.dp(20f))
        row.addView(swatch)

        val field = context.textField(hex, "#RRGGBB")
        field.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
            it.leftMargin = context.dp(8f)
        }
        field.addTextChangedListener(debounced { text ->
            val parsed = runCatching { LumenColor.fromHex(text.trim()) }.getOrNull() ?: return@debounced
            val updated = GradientDrawable()
            updated.setColor((parsed.a * 255).toInt() shl 24 or ((parsed.r * 255).toInt() shl 16) or
                ((parsed.g * 255).toInt() shl 8) or (parsed.b * 255).toInt())
            updated.setCornerRadius(context.dp(4f).toFloat())
            updated.setStroke(1, Studio.PANEL_HIGH)
            swatch.background = updated
            onEdit?.invoke(node, def.name, parsed, false)
        })
        row.addView(field)
        return row
    }

    private fun vectorEditor(node: Node, def: PropertyDef, value: Vec2): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        val x = context.textField(value.x.toString(), "x")
        x.inputType = AndroidInputType.TYPE_CLASS_NUMBER or AndroidInputType.TYPE_NUMBER_FLAG_DECIMAL or
            AndroidInputType.TYPE_NUMBER_FLAG_SIGNED
        val y = context.textField(value.y.toString(), "y")
        y.inputType = x.inputType
        x.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        y.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
            it.leftMargin = context.dp(8f)
        }
        fun apply() {
            val vx = x.text.toString().trim().toFloatOrNull() ?: return
            val vy = y.text.toString().trim().toFloatOrNull() ?: return
            onEdit?.invoke(node, def.name, Vec2(vx, vy), false)
        }
        x.addTextChangedListener(debounced { apply() })
        y.addTextChangedListener(debounced { apply() })
        row.addView(x)
        row.addView(y)
        return row
    }

    private fun rectEditor(node: Node, def: PropertyDef, value: Rect): View {
        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        val values = listOf(value.x, value.y, value.w, value.h)
        val fields = ArrayList<EditText>()
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        val hints = listOf("x", "y", "w", "h")
        for (index in 0 until 4) {
            val field = context.textField(values[index].toString(), hints[index])
            field.inputType = AndroidInputType.TYPE_CLASS_NUMBER or AndroidInputType.TYPE_NUMBER_FLAG_DECIMAL or
                AndroidInputType.TYPE_NUMBER_FLAG_SIGNED
            field.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                if (index > 0) it.leftMargin = context.dp(6f)
            }
            field.addTextChangedListener(debounced {
                val parsed = fields.map { f -> f.text.toString().trim().toFloatOrNull() }
                if (parsed.all { it != null }) {
                    onEdit?.invoke(node, def.name, Rect(parsed[0]!!, parsed[1]!!, parsed[2]!!, parsed[3]!!), false)
                }
            })
            fields.add(field)
            row.addView(field)
        }
        container.addView(row)
        container.addView(context.dim("x, y, width, height"))
        return container
    }

    private fun enumEditor(node: Node, def: PropertyDef, value: String): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        val field = context.textField(value, def.hint)
        field.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        field.addTextChangedListener(debounced { text -> onEdit?.invoke(node, def.name, text.trim(), true) })
        row.addView(field)
        val cycle = context.button("↻") { onEdit?.invoke(node, def.name, value, true) }
        row.addView(cycle)
        return row
    }

    /** Row used by the asset browser / node menu: label + value + action. */
    fun labelledRow(labelText: String, valueText: String, action: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val caption = context.label(labelText, 12f, Studio.TEXT_DIM)
        caption.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(caption)
        val value = TextView(context)
        value.text = valueText
        value.setTextColor(Studio.TEXT)
        value.textSize = 12f
        row.addView(value)
        if (action != null) {
            row.isClickable = true
            row.setOnClickListener { action() }
        }
        return row
    }
}
