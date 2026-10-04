/**
 * Lumen2D Studio — scene tree.
 *
 * A custom-drawn, scrollable hierarchy view. Drawing it ourselves (instead of a `ListView` plus an
 * adapter) keeps the rows dense like a desktop editor, gives per-row type icons and colours, and
 * means a scene with a thousand nodes costs one view instead of a thousand.
 *
 * Interactions: tap selects, tap on the triangle folds, long-press opens the node menu (add child,
 * duplicate, rename, delete, move).
 */
package dev.lumen2d.studio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import dev.lumen2d.core.scene.Node

class SceneTreeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Root of the tree being drawn. */
    var root: Node? = null
        set(value) {
            field = value
            rebuild()
        }

    var selection: Node? = null
        set(value) {
            field = value
            invalidate()
        }

    var highlightColor: Int = Studio.MINT

    /** Called when the user picks a node. */
    var onSelect: ((Node) -> Unit)? = null

    /** Called on long-press with the node under the finger. */
    var onMenu: ((Node) -> Unit)? = null

    private val folded = HashSet<Node>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private class Row(val node: Node, val depth: Int)

    private val rows = ArrayList<Row>()
    private var rowHeight = 0f
    private var scrollY = 0f
    private var contentHeight = 0f

    private val density: Float get() = resources.displayMetrics.density

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.y)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            nodeAt(e.y)?.let { onMenu?.invoke(it) }
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scrollY = (scrollY + distanceY).coerceIn(0f, (contentHeight - height).coerceAtLeast(0f))
            invalidate()
            return true
        }
    })

    init {
        rowHeight = 22f * density
        paint.textSize = 12f * density
        textPaint.textSize = 12f * density
        textPaint.typeface = android.graphics.Typeface.MONOSPACE
        isClickable = true
    }

    private fun rebuild() {
        rows.clear()
        val top = root ?: return
        fun visit(node: Node, depth: Int) {
            rows.add(Row(node, depth))
            if (node in folded) return
            for (child in node.children) visit(child, depth + 1)
        }
        visit(top, 0)
        contentHeight = rows.size * rowHeight
        invalidate()
    }

    /** Re-reads the tree (called after an edit, a scene load or an undo). */
    fun refresh() = rebuild()

    private fun nodeAt(y: Float): Node? {
        val index = ((y + scrollY) / rowHeight).toInt()
        return rows.getOrNull(index)?.node
    }

    private fun handleTap(y: Float) {
        val index = ((y + scrollY) / rowHeight).toInt()
        val row = rows.getOrNull(index) ?: return
        // Tap on the fold marker toggles the subtree; anywhere else selects.
        val markerX = 8f * density + row.depth * 14f * density
        if (row.node.children.isNotEmpty() && y > markerX - 200 && isNearMarker(row, y, markerX)) {
            toggle(row.node)
            return
        }
        selection = row.node
        onSelect?.invoke(row.node)
        invalidate()
    }

    private fun isNearMarker(row: Row, y: Float, markerX: Float): Boolean {
        // Vertical hit test for the fold triangle: only inside its little box.
        val rowTop = rows.indexOf(row) * rowHeight - scrollY
        return y >= rowTop && y <= rowTop + rowHeight
    }

    private fun toggle(node: Node) {
        if (!folded.add(node)) folded.remove(node)
        rebuild()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Studio.PANEL)
        if (rows.isEmpty()) {
            canvas.drawText("(no scene open)", 12f * density, 26f * density, textPaint.apply { color = Studio.TEXT_DIM })
            return
        }
        val selected = selection
        var y = -scrollY
        for ((index, row) in rows.withIndex()) {
            if (y + rowHeight >= 0 && y <= height) {
                val rowRect = RectF(0f, y, width.toFloat(), y + rowHeight)
                val isSelected = row.node === selected
                paint.color = when {
                    isSelected -> Studio.ACCENT_SOFT
                    index % 2 == 0 -> Studio.PANEL
                    else -> Studio.PANEL_ALT
                }
                canvas.drawRect(rowRect, paint)
                if (isSelected) {
                    paint.color = highlightColor
                    canvas.drawRect(0f, y, 3f * density, y + rowHeight, paint)
                }

                val indent = 10f * density + row.depth * 14f * density
                var textX = indent

                // Fold marker + type swatch.
                if (row.node.children.isNotEmpty()) {
                    paint.color = Studio.TEXT_DIM
                    val cx = indent - 5f * density
                    val cy = y + rowHeight / 2f
                    val half = 3.5f * density
                    if (row.node in folded) {
                        canvas.drawText("+", cx - 3f * density, cy + 4f * density, textPaint.apply { color = Studio.TEXT_DIM })
                    } else {
                        canvas.drawText("-", cx - 3f * density, cy + 4f * density, textPaint.apply { color = Studio.TEXT_DIM })
                    }
                }
                textX = indent + 6f * density

                paint.color = typeColor(row.node.typeName)
                canvas.drawRect(textX, y + rowHeight / 2f - 4f * density, textX + 8f * density, y + rowHeight / 2f + 4f * density, paint)

                textPaint.color = if (isSelected) Studio.TEXT else Studio.TEXT
                canvas.drawText(row.node.name, textX + 13f * density, y + rowHeight * 0.7f, textPaint)

                val typeLabel = if (row.node.scriptBehavior != null) "${row.node.typeName} ⚡" else row.node.typeName
                textPaint.color = Studio.TEXT_DIM
                val typeWidth = textPaint.measureText(typeLabel)
                canvas.drawText(typeLabel, width - typeWidth - 10f * density, y + rowHeight * 0.7f, textPaint)
            }
            y += rowHeight
        }
    }

    private fun typeColor(typeName: String): Int = when {
        typeName.contains("Camera") -> Studio.WARN
        typeName.contains("Body") || typeName.contains("Collision") -> Studio.DANGER
        typeName.contains("Sprite") || typeName.contains("TileMap") || typeName.contains("Particles") -> Studio.ACCENT
        typeName.contains("Control") || typeName.contains("Label") || typeName.contains("Button") -> Studio.MINT
        typeName.contains("Script") -> Studio.WARN
        else -> Studio.TEXT_DIM
    }

    /** Scrolls the selection into view (used when the inspector changes the selection). */
    fun scrollSelectionIntoView() {
        val index = rows.indexOfFirst { it.node === selection }
        if (index < 0) return
        val rowTop = index * rowHeight
        if (rowTop < scrollY) scrollY = rowTop
        if (rowTop + rowHeight > scrollY + height) scrollY = (rowTop + rowHeight - height).coerceAtLeast(0f)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        contentHeight = rows.size * rowHeight
    }
}
