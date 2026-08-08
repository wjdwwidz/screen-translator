package com.scrtrans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/**
 * Draws translation boxes straight onto a Canvas. No child views, no layout pass:
 * every item already carries absolute screen coordinates, so the layout system
 * would have nothing to compute.
 */
class OverlayView(context: Context) : View(context) {

    companion object {
        /**
         * The node's own area. 160/255 = 63%.
         *
         * The alpha here trades two things against each other that share the one knob:
         * lower lets the app's colours back through, and lets the Japanese back through
         * with them. 120 (47%, the first value tried) left the source plainly readable
         * wherever the Korean was shorter; 255 hid everything but turned the 検索 button
         * and the selected tab into white boxes.
         */
        private val NODE_BG = Color.argb(160, 255, 255, 255)

        /**
         * Behind the glyphs, fully opaque — this is what keeps the Korean legible now
         * that the node behind it is only partly covered.
         */
        private val GLYPH_BG = Color.rgb(255, 255, 255)

        private val TEXT_DONE = Color.rgb(24, 24, 28)
        private val TEXT_PENDING = Color.rgb(150, 150, 156)

        private const val HEIGHT_RATIO = 0.62f
        private const val MIN_TEXT_PX = 14f

        /**
         * Box height is all we have to size text by — extraRenderingInfo.textSizeInPx
         * is null on every node here. It works for labels that hug their text, but a
         * padded button (the 検索 node is 144px tall around ~40px text) would get 89px
         * type, which both looks wrong and drags an oversized white patch behind it.
         * 60px ~ 20sp at this density, above any real UI label.
         */
        private const val MAX_TEXT_PX = 60f

        /** Breathing room either side of the glyphs. */
        private const val GLYPH_PAD = 4f
    }

    /** A single item with its text size and line breaking already decided. */
    private class Prepared(
        val bounds: Rect,
        val textSize: Float,
        val translated: Boolean,
        val single: String?,
        val layout: StaticLayout?,
    )

    private var prepared: List<Prepared> = emptyList()
    private val originOnScreen = IntArray(2)

    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val measurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun setItems(items: List<RenderItem>) {
        val size = FloatArray(items.size)
        val oneLine = BooleanArray(items.size)
        items.forEachIndexed { i, item ->
            val (s, fits) = naturalSize(item)
            size[i] = s
            oneLine[i] = fits
        }

        // Sibling labels rendered at their own natural sizes come out wildly uneven —
        // "핸섬 숏" large next to "보브 스타일링 헤어" small, because shrink-to-fit is
        // driven by how long each translation happens to be, while the Japanese they
        // replace was all one size. Nodes of equal height held equal-sized text, so
        // group by height and drop the whole group to its smallest member.
        //
        // Only one-line items get a vote. An item that overflows even at the floor is a
        // sentence, not a label, and would otherwise drag its whole group down with it.
        val groupSize = HashMap<Int, Float>()
        items.forEachIndexed { i, item ->
            if (!oneLine[i]) return@forEachIndexed
            val h = item.bounds.height()
            val current = groupSize[h]
            if (current == null || size[i] < current) groupSize[h] = size[i]
        }

        prepared = items.mapIndexed { i, item ->
            val chosen = if (oneLine[i]) groupSize[item.bounds.height()] ?: size[i] else size[i]
            prepare(item, chosen, oneLine[i])
        }

        if (items.isNotEmpty()) {
            logd("sizes: " + items.mapIndexed { i, item ->
                val chosen = if (oneLine[i]) groupSize[item.bounds.height()] ?: size[i] else size[i]
                "\"${item.display}\" h=${item.bounds.height()} ${size[i].toInt()}->${chosen.toInt()}"
            }.joinToString("; "))
        }

        invalidate()
    }

    /**
     * Start at 0.62 x box height and step down 1px at a time until the string fits the
     * box width on one line. Returns that size, and whether it actually fits — false
     * means even 14px overflows, so the string wants wrapping rather than shrinking.
     */
    private fun naturalSize(item: RenderItem): Pair<Float, Boolean> {
        val w = item.bounds.width()
        val h = item.bounds.height()
        var size = (h * HEIGHT_RATIO).coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)
        while (size > MIN_TEXT_PX) {
            measurePaint.textSize = size
            if (measurePaint.measureText(item.display) <= w) break
            size -= 1f
        }
        measurePaint.textSize = size
        return size to (measurePaint.measureText(item.display) <= w)
    }

    private fun prepare(item: RenderItem, size: Float, oneLine: Boolean): Prepared {
        if (oneLine) {
            return Prepared(item.bounds, size, item.translated, item.display, null)
        }

        // Too long for one line even at the floor: wrap, and take the largest size
        // whose wrapped block still fits the original's box height.
        val w = item.bounds.width()
        val h = item.bounds.height()
        var best: StaticLayout? = null
        var bestSize = MIN_TEXT_PX
        var s = (h * HEIGHT_RATIO).coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)
        while (s >= MIN_TEXT_PX) {
            val l = buildLayout(item.display, w, s)
            if (l.height <= h) {
                best = l
                bestSize = s
                break
            }
            s -= 1f
        }
        return Prepared(
            item.bounds, bestSize, item.translated, null,
            best ?: buildLayout(item.display, w, MIN_TEXT_PX),
        )
    }

    private fun buildLayout(text: String, width: Int, size: Float): StaticLayout {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size }
        return StaticLayout.Builder
            .obtain(text, 0, text.length, p, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (prepared.isEmpty()) return

        // Item bounds are absolute screen coordinates, but this window starts
        // below the status bar. Measure where we actually are and shift the
        // canvas up by that much, so the two coordinate spaces line up on any
        // device or OS version.
        getLocationOnScreen(originOnScreen)
        canvas.save()
        canvas.translate(-originOnScreen[0].toFloat(), -originOnScreen[1].toFloat())

        for (p in prepared) {
            fillPaint.color = NODE_BG
            canvas.drawRect(p.bounds, fillPaint)

            fillPaint.color = GLYPH_BG
            textPaint.textSize = p.textSize
            val textColor = if (p.translated) TEXT_DONE else TEXT_PENDING
            val left = p.bounds.left.toFloat()

            if (p.single != null) {
                val fm = textPaint.fontMetrics
                val lineH = fm.descent - fm.ascent
                val baseline = p.bounds.top + (p.bounds.height() - lineH) / 2f - fm.ascent
                val tw = textPaint.measureText(p.single)

                canvas.drawRect(
                    left - GLYPH_PAD,
                    baseline + fm.ascent,
                    left + tw + GLYPH_PAD,
                    baseline + fm.descent,
                    fillPaint,
                )
                textPaint.color = textColor
                canvas.drawText(p.single, left, baseline, textPaint)
            } else {
                val layout = p.layout!!
                val top = p.bounds.top + (p.bounds.height() - layout.height) / 2f

                for (i in 0 until layout.lineCount) {
                    val lw = layout.getLineWidth(i)
                    if (lw <= 0f) continue
                    val lineLeft = left + layout.getLineLeft(i)
                    canvas.drawRect(
                        lineLeft - GLYPH_PAD,
                        top + layout.getLineTop(i),
                        lineLeft + lw + GLYPH_PAD,
                        top + layout.getLineBottom(i),
                        fillPaint,
                    )
                }

                layout.paint.color = textColor
                canvas.save()
                canvas.translate(left, top)
                layout.draw(canvas)
                canvas.restore()
            }
        }

        canvas.restore()
    }
}
