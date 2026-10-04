/**
 * Lumen2D Studio — theme and widget helpers.
 *
 * The editor is built from plain Android views with a hand-tuned dark theme (the same palette the
 * desktop previews and the documentation use), so the app has no UI dependency at all: no Compose,
 * no Material — nothing that can drift from the engine's own rendering.
 */
package dev.lumen2d.studio

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView

object Studio {

    // ------------------------------------------------------------------ palette
    const val BG = 0xFF0C0F19.toInt()
    const val PANEL = 0xFF141A28.toInt()
    const val PANEL_ALT = 0xFF1B2336.toInt()
    const val PANEL_HIGH = 0xFF232C44.toInt()
    const val ACCENT = 0xFF6C8CFF.toInt()
    const val ACCENT_SOFT = 0xFF2A3252.toInt()
    const val MINT = 0xFF7CF7C4.toInt()
    const val WARN = 0xFFFFC46B.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()
    const val TEXT = 0xFFE6EBFF.toInt()
    const val TEXT_DIM = 0xFF8A93B5.toInt()

    const val DARK_TAG = "Lumen2D Studio"
}

/** Density-independent pixels. */
fun Context.dp(value: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

/** A rounded panel background. */
fun roundedBackground(color: Int, radiusDp: Float = 10f, strokeColor: Int = 0, strokeDp: Float = 0f): GradientDrawable {
    val drawable = GradientDrawable()
    drawable.setColor(color)
    drawable.setCornerRadius(radiusDp)
    if (strokeColor != 0) drawable.setStroke(strokeDp.toInt().coerceAtLeast(1), strokeColor)
    return drawable
}

fun Context.panel(vertical: Boolean = true, paddingDp: Float = 10f): LinearLayout {
    val layout = LinearLayout(this)
    layout.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    layout.background = roundedBackground(Studio.PANEL)
    val pad = dp(paddingDp)
    layout.setPadding(pad, pad, pad, pad)
    return layout
}

fun Context.label(
    text: CharSequence,
    sizeDp: Float = 13f,
    color: Int = Studio.TEXT,
    bold: Boolean = false,
    monospace: Boolean = false,
): TextView {
    val view = TextView(this)
    view.text = text
    view.textColor = color
    view.textSize = sizeDp
    view.typeface = when {
        monospace -> Typeface.MONOSPACE
        bold -> Typeface.DEFAULT_BOLD
        else -> Typeface.DEFAULT
    }
    return view
}

fun Context.title(text: CharSequence): TextView = label(text, 18f, Studio.TEXT, bold = true)

fun Context.dim(text: CharSequence, sizeDp: Float = 11f): TextView = label(text, sizeDp, Studio.TEXT_DIM)

fun Context.button(
    text: CharSequence,
    primary: Boolean = false,
    onClick: (View?) -> Unit,
): Button {
    val view = Button(this)
    view.text = text
    view.isAllCaps = false
    view.textSize = 12f
    view.textColor = Studio.TEXT
    view.background = roundedBackground(if (primary) Studio.ACCENT_SOFT else Studio.PANEL_ALT, 8f, Studio.PANEL_HIGH, 1f)
    view.setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
    view.setOnClickListener { onClick(it) }
    return view
}

fun Context.textField(value: String, hintText: String = "", multiline: Boolean = false): EditText {
    val view = EditText(this)
    view.text = value
    view.hint = hintText
    view.textSize = 12f
    view.textColor = Studio.TEXT
    view.typeface = Typeface.MONOSPACE
    view.isSingleLine = !multiline
    view.background = roundedBackground(Studio.PANEL_HIGH, 6f)
    view.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
    return view
}

fun Context.spacer(heightDp: Float): Space {
    val view = Space(this)
    view.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    return view
}

fun Context.divider(): View {
    val view = View(this)
    view.setBackgroundColor(Studio.PANEL_HIGH)
    view.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)).also {
        it.topMargin = dp(6f)
        it.bottomMargin = dp(6f)
    }
    return view
}

fun Context.row(vararg children: View, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout {
    val layout = LinearLayout(this)
    layout.orientation = LinearLayout.HORIZONTAL
    layout.gravity = gravity
    for (child in children) layout.addView(child)
    return layout
}

/** A labelled slider row (0..1) used by the inspector for float properties. */
fun Context.sliderRow(
    labelText: String,
    value: Float,
    onChange: (Float) -> Unit,
): LinearLayout {
    val container = LinearLayout(this)
    container.orientation = LinearLayout.HORIZONTAL
    container.gravity = Gravity.CENTER_VERTICAL
    val caption = label(labelText, 12f, Studio.TEXT_DIM)
    caption.layoutParams = LinearLayout.LayoutParams(dp(96f), ViewGroup.LayoutParams.WRAP_CONTENT)
    val bar = SeekBar(this)
    bar.max = 1000
    bar.progress = (value.coerceIn(0f, 1f) * 1000f).toInt()
    bar.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    val readout = label("%.2f".format(value), 12f, Studio.TEXT)
    readout.layoutParams = LinearLayout.LayoutParams(dp(48f), ViewGroup.LayoutParams.WRAP_CONTENT)
    bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            val v = progress / 1000f
            readout.text = "%.2f".format(v)
            onChange(v)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })
    container.addView(caption)
    container.addView(bar)
    container.addView(readout)
    return container
}

/** Scroll container: what every editor panel returns. */
fun Context.scrolling(content: View): ScrollView {
    val scroll = ScrollView(this)
    scroll.isFillViewport = true
    scroll.background = roundedBackground(Studio.PANEL)
    val pad = dp(8f)
    scroll.setPadding(pad, pad, pad, pad)
    scroll.addView(content)
    return scroll
}

/** Small coloured status pill ("playing", "saved", "3 changes"). */
fun Context.pill(text: CharSequence, color: Int = Studio.MINT): TextView {
    val view = label(text, 10f, Studio.BG, bold = true)
    view.background = roundedBackground(color, 20f)
    view.setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
    return view
}
