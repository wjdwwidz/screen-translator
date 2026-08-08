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
         * The node's own area, opaque.
         *
         * Two lighter versions came first and both leaked. A translucent wash left the
         * source readable at 47%; covering only the text line left multi-line sources
         * poking out above and below it — "ハイライトカ / 하이라이트 컬러 / ラー". Opaque
         * over the whole node is the only variant where no Japanese survives anywhere,
         * and the price is that the node's own colour goes with it.
         */
        private val NODE_BG = Color.rgb(255, 255, 255)

        /** Same white, behind the glyphs. Redundant while NODE_BG is opaque. */
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
        prepared = items.map { prepare(it) }
        invalidate()
    }

    /**
     * Start at 0.62 x box height and step down 1px at a time until the string fits
     * the box width on one line. If it still will not fit at 14px the text is a long
     * sentence, not a label — wrap it instead of shrinking into unreadability.
     */
    private fun prepare(item: RenderItem): Prepared {
        val w = item.bounds.width()
        val h = item.bounds.height()
        val startSize = (h * HEIGHT_RATIO).coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)

        var size = startSize
        while (size > MIN_TEXT_PX) {
            measurePaint.textSize = size
            if (measurePaint.measureText(item.display) <= w) break
            size -= 1f
        }
        measurePaint.textSize = size
        if (measurePaint.measureText(item.display) <= w) {
            return Prepared(item.bounds, size, item.translated, item.display, null)
        }

        // Too long for one line even at the floor: wrap, and take the largest size
        // whose wrapped block still fits the original's box height.
        var best: StaticLayout? = null
        var bestSize = MIN_TEXT_PX
        var s = startSize
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
