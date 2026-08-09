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
import kotlin.math.floor
import kotlin.math.max

/**
 * Draws translation boxes straight onto a Canvas. No child views, no layout pass:
 * every item already carries absolute screen coordinates, so the layout system
 * would have nothing to compute.
 */
class OverlayView(context: Context) : View(context) {

    companion object {
        /** Whole-node wash. Lets the app's own colours — button pink, tab underline — through. */
        private val NODE_FILL = Color.argb(120, 255, 255, 255)

        /**
         * Opaque band behind the text, spanning the node's full width.
         *
         * Width-of-our-glyphs-only was the first attempt, on the theory that the two
         * goals collide solely where our text lands. On a device that turned out false:
         * Korean is usually shorter than the Japanese and the original is often centred,
         * so the tail of the source stayed legible through the 47% wash — "미디엄ディアム".
         * Full node width hides it; the untouched strips above and below keep the button
         * pink and the tab underline visible.
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
         * type. That both looks wrong and makes the opaque band thick enough to swallow
         * the button's pink. 60px ~ 20sp at this density, above any real UI label.
         */
        private const val MAX_TEXT_PX = 60f
        private const val GLYPH_PAD = 4f
    }

    /** A single item with its text size, line breaking and cover height already decided. */
    private class Prepared(
        val bounds: Rect,
        val textSize: Float,
        val translated: Boolean,
        val single: String?,
        val layout: StaticLayout?,
        /** Height of the opaque band, from how much of the node the Japanese occupies. */
        val bandHeight: Float,
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
        val fitsOneLine = measurePaint.measureText(item.display) <= w

        if (fitsOneLine) {
            val lineH = measurePaint.fontMetrics.let { it.descent - it.ascent }
            return Prepared(
                item.bounds, size, item.translated, item.display, null,
                bandHeight(item, w, h, size, lineH, ourLines = 1),
            )
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
        val layout = best ?: buildLayout(item.display, w, MIN_TEXT_PX)
        measurePaint.textSize = bestSize
        val lineH = measurePaint.fontMetrics.let { it.descent - it.ascent }
        return Prepared(
            item.bounds, bestSize, item.translated, null, layout,
            max(
                layout.height.toFloat() + GLYPH_PAD * 2,
                bandHeight(item, w, h, bestSize, lineH, ourLines = layout.lineCount),
            ),
        )
    }

    /**
     * How tall the cover has to be.
     *
     * The Japanese may well occupy more lines than the Korean does — "サロンからの /
     * メッセージ" takes two, "살롱에서 온 메시지" fits on one — and a one-line band leaves
     * the original poking out above and below. Nothing reports the source's line count,
     * so lay the source out at the size we chose and count. Laying it out rather than
     * dividing width by width matters: several of these strings carry a literal \n,
     * which measureText would happily ignore. Capped at what the node can actually
     * hold, so a tall padded button still gets a one-line band and keeps its colour.
     */
    private fun bandHeight(
        item: RenderItem,
        w: Int,
        h: Int,
        size: Float,
        lineH: Float,
        ourLines: Int,
    ): Float {
        val srcLines = buildLayout(item.source, w, size).lineCount.coerceAtLeast(1)

        // A multi-line source cannot be placed by guesswork. The message card's node is
        // [48,721][352,916] — 195px tall around ~92px of text that sits against the
        // bottom, not the middle — so a band centred in the node misses the second line.
        // Cover the node outright instead. Nothing is lost: the nodes that need their
        // colour showing through (buttons, tabs) are all single-line.
        if (srcLines > 1) return h.toFloat()

        val maxLines = max(1, floor(h / lineH).toInt())
        val lines = max(ourLines, srcLines).coerceAtMost(maxLines)
        return (lines * lineH + GLYPH_PAD * 2).coerceAtMost(h.toFloat())
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
            // (1) whole node area
            fillPaint.color = NODE_FILL
            canvas.drawRect(p.bounds, fillPaint)

            // (2) opaque band, full node width, tall enough to bury the Japanese
            val bandTop = p.bounds.top + (p.bounds.height() - p.bandHeight) / 2f
            fillPaint.color = GLYPH_BG
            canvas.drawRect(
                p.bounds.left.toFloat(),
                bandTop,
                p.bounds.right.toFloat(),
                bandTop + p.bandHeight,
                fillPaint,
            )

            textPaint.textSize = p.textSize
            val textColor = if (p.translated) TEXT_DONE else TEXT_PENDING
            val left = p.bounds.left.toFloat()

            if (p.single != null) {
                val fm = textPaint.fontMetrics
                val lineH = fm.descent - fm.ascent
                val baseline = p.bounds.top + (p.bounds.height() - lineH) / 2f - fm.ascent
                textPaint.color = textColor
                canvas.drawText(p.single, left, baseline, textPaint)
            } else {
                val layout = p.layout!!
                val top = p.bounds.top + (p.bounds.height() - layout.height) / 2f
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
