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
import kotlin.math.max
import kotlin.math.min

/**
 * Draws translation boxes straight onto a Canvas. No child views, no layout pass:
 * every item already carries absolute screen coordinates, so the layout system
 * would have nothing to compute.
 *
 * Two paths. When the character-location API told us where the Japanese actually is,
 * we place the Korean on top of exactly that and cover exactly that. When it did not
 * — EditText hints are the only case seen so far — we fall back to estimating from the
 * node box, which needs the node-wide wash to hide a source we cannot locate.
 */
class OverlayView(context: Context) : View(context) {

    companion object {
        /** Fallback path only: hides a source whose position we do not know. */
        private val NODE_BG = Color.argb(160, 255, 255, 255)

        /** Opaque, over the source's ink and our own text. */
        private val GLYPH_BG = Color.rgb(255, 255, 255)

        private val TEXT_DONE = Color.rgb(24, 24, 28)
        private val TEXT_PENDING = Color.rgb(150, 150, 156)

        private const val MIN_TEXT_PX = 14f
        private const val GLYPH_PAD = 4f

        // Fallback-path estimates, unused when ink is available.
        private const val HEIGHT_RATIO = 0.62f
        private const val MAX_TEXT_PX = 60f

        /**
         * Debug: force an opaque backdrop, draw 1px rules at known absolute screen
         * coordinates and outline every node box, so a screenshot can be measured
         * against the bounds the tree reported. tools/measure.py reads the result.
         */
        const val DEBUG_GRID = false
        private const val RULE_X = 500f
        private const val RULE_Y = 1000f
    }

    private class Prepared(
        val bounds: Rect,
        /** The opaque area: the source's ink plus whatever our own text needs. */
        val band: Rect,
        val textLeft: Float,
        val textSize: Float,
        val translated: Boolean,
        val single: String?,
        val baseline: Float,
        val layout: StaticLayout?,
        val layoutTop: Float,
        /** Fallback items wash the whole node; ink-located items leave it alone. */
        val washNode: Boolean,
    )

    private var prepared: List<Prepared> = emptyList()
    private val originOnScreen = IntArray(2)

    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val measurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun setItems(items: List<RenderItem>) {
        // Fallback items still get their sizes evened out across equal-height nodes;
        // ink-located ones need no such help, since the Japanese they copy was uniform.
        val fallbackSize = HashMap<Int, Float>()
        for (item in items) {
            if (item.hasInk) continue
            val (s, fits) = estimatedSize(item)
            if (!fits) continue
            val h = item.bounds.height()
            val cur = fallbackSize[h]
            if (cur == null || s < cur) fallbackSize[h] = s
        }

        prepared = items.map { item ->
            if (item.hasInk) prepareFromInk(item) else prepareFromBox(item, fallbackSize)
        }
        invalidate()
    }

    /**
     * The measured path.
     *
     * Size comes from the source's line box rather than a guess: font metrics scale
     * linearly, so measuring our own line height at 100px and solving gives the size
     * whose lines match the Japanese exactly. Position likewise — the baseline is
     * pinned to the source's own line top, so no centring rule is involved at all.
     */
    private fun prepareFromInk(item: RenderItem): Prepared {
        val first = item.inkLines.first()
        val inkUnion = Rect(first)
        for (r in item.inkLines) inkUnion.union(r)

        val left = first.left.toFloat()
        val right = max(item.bounds.right, inkUnion.right)
        val available = (right - left).toInt().coerceAtLeast(1)

        var size = sizeForLineHeight(item.sourceLineHeight)
        while (size > MIN_TEXT_PX) {
            measurePaint.textSize = size
            if (measurePaint.measureText(item.display) <= available) break
            size -= 1f
        }
        measurePaint.textSize = size
        val fitsOneLine = measurePaint.measureText(item.display) <= available

        textPaint.textSize = size
        val fm = textPaint.fontMetrics

        val band = Rect(inkUnion)
        band.left = (left - GLYPH_PAD).toInt()

        if (fitsOneLine) {
            // Line top == the source's line top, so our text lands on its line.
            val baseline = first.top - fm.top
            val width = measurePaint.measureText(item.display)
            band.right = max(inkUnion.right, (left + width + GLYPH_PAD).toInt())
            band.bottom = max(inkUnion.bottom, (baseline + fm.bottom).toInt())
            return Prepared(
                item.bounds, band, left, size, item.translated,
                item.display, baseline, null, 0f, washNode = false,
            )
        }

        val layout = buildLayout(item.display, available, size)
        val top = first.top.toFloat()
        var widest = 0f
        for (i in 0 until layout.lineCount) widest = max(widest, layout.getLineWidth(i))
        band.right = max(inkUnion.right, (left + widest + GLYPH_PAD).toInt())
        band.bottom = max(inkUnion.bottom, (top + layout.height).toInt())
        return Prepared(
            item.bounds, band, left, size, item.translated,
            null, 0f, layout, top, washNode = false,
        )
    }

    /** Estimate from the node box. Only reached for text the API would not describe. */
    private fun prepareFromBox(item: RenderItem, groupSize: Map<Int, Float>): Prepared {
        val w = item.bounds.width()
        val h = item.bounds.height()
        val (natural, fitsOneLine) = estimatedSize(item)
        val size = if (fitsOneLine) min(natural, groupSize[h] ?: natural) else natural

        textPaint.textSize = size
        val fm = textPaint.fontMetrics
        val left = item.bounds.left.toFloat()

        if (fitsOneLine) {
            // Centre on top..bottom, not ascent..descent: TextView defaults to
            // includeFontPadding=true and uses the former, and centring the latter put
            // text ~0.075em high — a measured 3px at 36px.
            val lineH = fm.bottom - fm.top
            val baseline = item.bounds.top + (h - lineH) / 2f - fm.top
            val band = Rect(item.bounds.left, (baseline + fm.top).toInt(), item.bounds.right, (baseline + fm.bottom).toInt())
            return Prepared(
                item.bounds, band, left, size, item.translated,
                item.display, baseline, null, 0f, washNode = true,
            )
        }

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
        val layout = best ?: buildLayout(item.display, w, MIN_TEXT_PX)
        val top = item.bounds.top + (h - layout.height) / 2f
        val band = Rect(item.bounds.left, top.toInt(), item.bounds.right, (top + layout.height).toInt())
        return Prepared(
            item.bounds, band, left, bestSize, item.translated,
            null, 0f, layout, top, washNode = true,
        )
    }

    /** Start at 0.62 x box height, step down until it fits. Returns whether it does. */
    private fun estimatedSize(item: RenderItem): Pair<Float, Boolean> {
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

    /** Font metrics scale linearly with size, so one measurement inverts the relation. */
    private fun sizeForLineHeight(target: Float): Float {
        measurePaint.textSize = 100f
        val fm = measurePaint.fontMetrics
        val at100 = fm.bottom - fm.top
        if (at100 <= 0f) return MIN_TEXT_PX
        return (target * 100f / at100).coerceAtLeast(MIN_TEXT_PX)
    }

    private fun buildLayout(text: String, width: Int, size: Float): StaticLayout {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size }
        return StaticLayout.Builder
            .obtain(text, 0, text.length, p, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            // Matches the single-line path and TextView's own default.
            .setIncludePad(true)
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

        if (DEBUG_GRID) {
            fillPaint.color = Color.RED
            canvas.drawRect(RULE_X, 0f, RULE_X + 1f, 4000f, fillPaint)
            canvas.drawRect(0f, RULE_Y, 2000f, RULE_Y + 1f, fillPaint)
        }

        for (p in prepared) {
            if (p.washNode) {
                fillPaint.color = if (DEBUG_GRID) Color.WHITE else NODE_BG
                canvas.drawRect(p.bounds, fillPaint)
            }

            fillPaint.color = GLYPH_BG
            canvas.drawRect(p.band, fillPaint)

            textPaint.textSize = p.textSize
            textPaint.color = if (p.translated) TEXT_DONE else TEXT_PENDING

            val single = p.single
            if (single != null) {
                canvas.drawText(single, p.textLeft, p.baseline, textPaint)
            } else {
                val layout = p.layout!!
                layout.paint.color = textPaint.color
                canvas.save()
                canvas.translate(p.textLeft, p.layoutTop)
                layout.draw(canvas)
                canvas.restore()
            }
        }

        if (DEBUG_GRID) {
            fillPaint.color = Color.rgb(0, 160, 255)
            for (p in prepared) {
                val b = p.bounds
                canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + 1f, fillPaint)
                canvas.drawRect(b.left.toFloat(), b.bottom - 1f, b.right.toFloat(), b.bottom.toFloat(), fillPaint)
                canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + 1f, b.bottom.toFloat(), fillPaint)
                canvas.drawRect(b.right - 1f, b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), fillPaint)
            }
        }

        canvas.restore()
    }
}
