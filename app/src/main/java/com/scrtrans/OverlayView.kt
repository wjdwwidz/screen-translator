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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws translation boxes straight onto a Canvas. No child views, no layout pass:
 * every item already carries absolute screen coordinates, so the layout system
 * would have nothing to compute.
 *
 * Two paths. When the character-location API told us where the Japanese actually is,
 * we place the Korean on top of exactly that and cover exactly that. When it did not —
 * EditText hints, which are not the text and so have no characters to locate — we fall
 * back to the node box: its height gives the line to centre on, and the screenshot gives
 * the columns the source's text occupied, which is what the wash covers.
 *
 * Colours come from [ColorSampler] when it could read them and default to dark-on-white
 * when it could not, so every item has to carry its own pair rather than share constants.
 */
class OverlayView(context: Context) : View(context) {

    companion object {
        /**
         * Fallback path, colour unknown: hides a source whose position we do not know.
         * Translucent because an opaque white block would be more conspicuous than the
         * Japanese showing through. When the surface colour *is* known it is painted
         * opaque instead, which hides the source outright and costs nothing.
         */
        private val NODE_BG = Color.argb(160, 255, 255, 255)

        /** Opaque, over the source's ink and our own text. */
        private val GLYPH_BG = Color.rgb(255, 255, 255)

        private val TEXT_DONE = Color.rgb(24, 24, 28)
        private val TEXT_PENDING = Color.rgb(150, 150, 156)

        private const val MIN_TEXT_PX = 14f
        private const val GLYPH_PAD = 4f

        /**
         * The sizes a translation may be drawn at, as fractions of the source's own.
         * Few and coarse on purpose: see [fitToWidth].
         */
        private val SHRINK_STEPS = floatArrayOf(1f, 0.85f, 0.7f, 0.55f, 0.45f)

        /** Lines a translation may use beyond what the source itself used. */
        private const val EXTRA_LINES = 1

        /** Below this many characters' width, a box is treated as clipped, not narrow. */
        private const val MIN_FIT_EMS = 3f

        // Fallback-path estimates, unused when ink is available.
        private const val HEIGHT_RATIO = 0.62f
        private const val MAX_TEXT_PX = 60f

        /** How far an estimate may be pulled onto a size the screen was measured at. */
        private const val SNAP_TOLERANCE = 0.35f

        /** A line box measures about this many times its font size, includePad and all. */
        private const val LINE_BOX_RATIO = 1.5f

        /**
         * Debug: force an opaque backdrop, draw 1px rules at known absolute screen
         * coordinates and outline every node box, so a screenshot can be measured
         * against the bounds the tree reported. tools/measure.py reads the result.
         */
        const val DEBUG_GRID = false

        /** Logs how each item's size was arrived at, for tuning the fitting rules. */
        const val DEBUG_SIZE = false
        private const val RULE_X = 500f
        private const val RULE_Y = 1000f
    }

    private class Prepared(
        val bounds: Rect,
        /** The opaque area: the source's ink plus whatever our own text needs. */
        val band: Rect,
        val textLeft: Float,
        val textSize: Float,
        val single: String?,
        val baseline: Float,
        val layout: StaticLayout?,
        val layoutTop: Float,
        /** Fallback items wash the whole node; ink-located items leave it alone. */
        val washNode: Boolean,
        /** What [washNode] paints: the source's own columns, so a leading icon survives. */
        val wash: Rect,
        val washColor: Int,
        val bandColor: Int,
        val textColor: Int,
    )

    private var prepared: List<Prepared> = emptyList()
    private val originOnScreen = IntArray(2)

    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val measurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun setItems(items: List<RenderItem>) {
        // The type scale this screen actually uses, read off the items the API did
        // describe. An estimate that lands near one of these is almost certainly meant
        // to be that size, and saying so is what keeps the guessed items from being the
        // one odd size on an otherwise even screen.
        val measured = items.filter { it.hasInk }.map { it.sourceEmSize }
        val scale = measured.distinct().sorted()
        // The size most of the screen is set in. Ties go to the smaller, since drawing a
        // guess too large is the more conspicuous way to be wrong.
        val body = measured.groupingBy { it }.eachCount().entries
            .maxWithOrNull(compareBy({ it.value }, { -it.key }))?.key ?: 0f

        prepared = items.map { item ->
            if (item.hasInk) prepareFromInk(item) else prepareFromBox(item, scale, body)
        }
        invalidate()
    }

    /**
     * The measured path.
     *
     * Size is the source's own font size, measured off its glyphs — see
     * TextCollector.emSize — so two labels drawn at the same size come out the same size
     * here, whatever their widgets differ in. Vertical position likewise: the baseline is
     * pinned to the source's own line top, so no centring rule is involved at all.
     *
     * Horizontally the source's own alignment has to be reproduced, because the
     * translation is rarely the same width: anchoring a centred label at its left edge
     * leaves a shorter Korean string sitting off to one side. A bottom-tab label is the
     * visible case — 북마크 drifted left of the icon it belongs to.
     */
    private fun prepareFromInk(item: RenderItem): Prepared {
        val first = item.inkLines.first()
        val inkUnion = Rect(first)
        for (r in item.inkLines) inkUnion.union(r)

        val centred = isCentred(inkUnion, item.container) &&
            // A wrap_content box hugs its text and can only say "centred"; one with room
            // to spare has to agree, or this is an icon pushing left-aligned text right.
            (item.bounds.width() < inkUnion.width() + 8 || isCentred(inkUnion, item.bounds))

        val cx = inkUnion.exactCenterX()
        val available = if (centred) {
            // The widest run still centred on cx that the container holds.
            (2f * min(cx - item.container.left, item.container.right - cx)).toInt()
        } else {
            max(item.bounds.right, inkUnion.right) - first.left
        }.coerceAtLeast(1)

        val source = sourceSize(item)
        val fit = fitToWidth(
            item.display, available, item.bounds.height(), source, item.inkLines.size, centred,
        )
        val size = fit.size
        if (DEBUG_SIZE) {
            logd(
                "size '${item.display.take(12)}' em=${item.sourceEmSize} " +
                    "lh=${item.sourceLineHeight} source=$source final=$size " +
                    "avail=$available lines=${fit.layout?.lineCount ?: 1}/${item.inkLines.size}"
            )
        }
        measurePaint.textSize = size
        val width = measurePaint.measureText(item.display)

        textPaint.textSize = size
        val fm = textPaint.fontMetrics

        val band = Rect(inkUnion)

        if (fit.layout == null) {
            // Line top == the source's line top, so our text lands on its line.
            val baseline = first.top - fm.top
            val left = if (centred) cx - width / 2f else first.left.toFloat()
            band.left = min(inkUnion.left, (left - GLYPH_PAD).toInt())
            band.right = max(inkUnion.right, (left + width + GLYPH_PAD).toInt())
            band.bottom = max(inkUnion.bottom, (baseline + fm.bottom).toInt())
            confine(band, inkUnion, item)
            return Prepared(
                item.bounds, band, left, size,
                item.display, baseline, null, 0f, washNode = false, wash = item.bounds,
                washColor = NODE_BG,
                bandColor = item.colors?.bg ?: GLYPH_BG, textColor = textColorFor(item),
            )
        }

        val layout = fit.layout
        val layoutLeft = if (centred) cx - available / 2f else first.left.toFloat()
        val top = first.top.toFloat()
        var widest = 0f
        for (i in 0 until layout.lineCount) widest = max(widest, layout.getLineWidth(i))
        // Centred lines sit in the middle of the layout box, not at its edge.
        val inkLeft = if (centred) cx - widest / 2f else layoutLeft
        band.left = min(inkUnion.left, (inkLeft - GLYPH_PAD).toInt())
        band.right = max(inkUnion.right, (inkLeft + widest + GLYPH_PAD).toInt())
        band.bottom = max(inkUnion.bottom, (top + layout.height).toInt())
        confine(band, inkUnion, item)
        return Prepared(
            item.bounds, band, layoutLeft, size,
            null, 0f, layout, top, washNode = false, wash = item.bounds,
            washColor = NODE_BG,
            bandColor = item.colors?.bg ?: GLYPH_BG, textColor = textColorFor(item),
        )
    }

    /**
     * A white band on a white page is invisible however far it runs, but one painted in
     * the source's own colour is not: a pink band that overshoots its button leaves a
     * pink rectangle sticking out of the corner. So a coloured band is held inside the
     * node box — the widget the colour came from — while still covering all of the ink.
     */
    private fun confine(band: Rect, inkUnion: Rect, item: RenderItem) {
        if (item.colors == null) return
        band.intersect(item.bounds)
        band.union(inkUnion)
    }

    /**
     * Pending text is the Japanese source, and drawing it in the source's own ink would
     * be indistinguishable from no overlay at all, so it is faded toward the surface —
     * the same signal the grey-on-white default gives.
     */
    private fun textColorFor(item: RenderItem): Int {
        val c = item.colors ?: return if (item.translated) TEXT_DONE else TEXT_PENDING
        return if (item.translated) c.ink else blend(c.ink, c.bg, 0.45f)
    }

    /** [t] of the way from [from] to [to]. */
    private fun blend(from: Int, to: Int, t: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt(),
    )

    /**
     * Equal slack on both sides of [box], and enough of it to be a decision rather than
     * padding. The tolerance scales with the box so a wide one is not held to a pixel.
     */
    private fun isCentred(ink: Rect, box: Rect): Boolean {
        val l = ink.left - box.left
        val r = box.right - ink.right
        val tol = (box.width() * 0.03f).coerceIn(2f, 12f)
        return l > tol && r > tol && abs(l - r) <= tol
    }

    /** Estimate from the node box. Only reached for text the API would not describe. */
    private fun prepareFromBox(
        item: RenderItem,
        scale: List<Float>,
        body: Float,
    ): Prepared {
        val h = item.bounds.height()
        // Where the source's text starts, and so where ours does. The screenshot says,
        // when it could read the box; otherwise the box's own edge has to do. It is known
        // before fitting because the room a translation has is the room to the right of
        // it — a leading icon takes its share out of the box first.
        val left = if (item.hasSpan) item.inkLeft.toFloat() else item.bounds.left.toFloat()
        val w = (item.bounds.right - left).toInt().coerceAtLeast(1)
        val base = estimatedSize(item, scale, body)
        val fit = fitToWidth(item.display, w, h, base, sourceLines = 1, centred = false)
        val size = fit.size
        if (DEBUG_SIZE) {
            logd(
                "box  '${item.display.take(12)}' h=$h w=$w raw=${h * HEIGHT_RATIO} " +
                    "body=$body base=$base final=$size scale=$scale"
            )
        }

        textPaint.textSize = size
        val fm = textPaint.fontMetrics

        if (fit.layout == null) {
            // Centre on top..bottom, not ascent..descent: TextView defaults to
            // includeFontPadding=true and uses the former, and centring the latter put
            // text ~0.075em high — a measured 3px at 36px.
            val lineH = fm.bottom - fm.top
            val baseline = item.bounds.top + (h - lineH) / 2f - fm.top
            measurePaint.textSize = size
            val wash = washFor(item, left, measurePaint.measureText(item.display))
            val band = Rect(wash.left, (baseline + fm.top).toInt(), wash.right, (baseline + fm.bottom).toInt())
            return Prepared(
                item.bounds, band, left, size,
                item.display, baseline, null, 0f, washNode = true, wash = wash,
                washColor = item.colors?.bg ?: NODE_BG,
                bandColor = item.colors?.bg ?: GLYPH_BG, textColor = textColorFor(item),
            )
        }

        val layout = fit.layout
        val top = item.bounds.top + (h - layout.height) / 2f
        var widest = 0f
        for (i in 0 until layout.lineCount) widest = max(widest, layout.getLineWidth(i))
        val wash = washFor(item, left, widest)
        val band = Rect(wash.left, top.toInt(), wash.right, (top + layout.height).toInt())
        return Prepared(
            item.bounds, band, left, size,
            null, 0f, layout, top, washNode = true, wash = wash,
            washColor = item.colors?.bg ?: NODE_BG,
            bandColor = item.colors?.bg ?: GLYPH_BG, textColor = textColorFor(item),
        )
    }

    /**
     * The box to paint over: from where the source's text began to wherever the source
     * and the translation end, whichever runs further.
     *
     * A search field draws its magnifier as a compound drawable of the EditText, so the
     * icon sits inside the box this path covers, and painting the whole box erases it.
     * [RenderItem.inkLeft] is where the screenshot found the source's text starting, so
     * the icon is simply everything to the left of it and never gets painted. Only a
     * glyph's worth of padding is added, for the antialiased edge of the first stroke;
     * nothing here is estimated, so no slack is needed to cover an estimate's error.
     */
    private fun washFor(item: RenderItem, left: Float, width: Float): Rect {
        val b = item.bounds
        if (!item.hasSpan) return b
        return Rect(
            (left - GLYPH_PAD).toInt().coerceAtLeast(b.left),
            b.top,
            max(item.inkRight, (left + width + GLYPH_PAD).toInt()).coerceAtMost(b.right),
            b.bottom,
        )
    }

    /**
     * A size for a box whose text we could not measure — in practice an EditText hint,
     * which the character-location API cannot describe because a hint is not the text.
     *
     * The box height barely constrains this. Two hint fields of the same drawn height sat
     * in boxes reported as 56px and 132px, so 0.62 x height put one at 37 and the other
     * at 82, and the taller one then snapped onto the section-header size — a placeholder
     * set larger than the heading above it.
     *
     * A hint is body text, so [body] is the better answer outright, and the height is
     * asked only whether it could possibly hold that: a line box runs about 1.5 x its
     * font size, so a box shorter than that is genuinely small text and gets the ratio.
     */
    private fun estimatedSize(item: RenderItem, scale: List<Float>, body: Float): Float {
        val h = item.bounds.height()
        if (body > 0f && h >= body * LINE_BOX_RATIO) return body
        return snapToScale((h * HEIGHT_RATIO).coerceIn(MIN_TEXT_PX, MAX_TEXT_PX), scale)
    }

    /** The chosen size, and the layout that made it fit — null when one line was enough. */
    private class Fit(val size: Float, val layout: StaticLayout?)

    /**
     * Fit the translation into [available] without letting it go a size of its own.
     *
     * Korean runs longer than Japanese far more often than not — 小顔ヘア becomes 얼굴 작아
     * 보이는 헤어 — so something has to give on the strings that outgrow their box. Giving
     * on size, one pixel at a time, is what made the screen look ragged: the short labels
     * kept the source's 37px while their neighbours dropped to 24, 22, 14, each to its own
     * arbitrary value.
     *
     * So wrapping gives first. The source usually wraps too, and its own line count says
     * how much room the layout expected to spend here, so one line beyond that is granted
     * before size is touched at all. Most strings never shrink under this rule.
     *
     * When even that is not enough, size steps down through [SHRINK_STEPS] rather than
     * sliding. Two labels that both had to shrink land on the same value instead of on
     * neighbouring ones, which is what reads as deliberate. The last step is used whether
     * it fits or not: past that the text is small enough to be worse than an overflow.
     */
    private fun fitToWidth(
        text: String,
        available: Int,
        availableHeight: Int,
        source: Float,
        sourceLines: Int,
        centred: Boolean,
    ): Fit {
        // A box with no room for even a few characters is one the screen edge cut off, not
        // a box the text has to be made to fit: the Japanese in it is half cut off too.
        // Wrapping such a box stacks the translation one character to a line, so let it
        // run off the edge instead and be clipped exactly where the source was.
        //
        // Unless the source wrapped in it. 再来 sits in a 72x325 badge as 再\n来, turned on
        // its side by the app on purpose — narrow is the design, not a clipped edge. Taking
        // the escape there drew 재방문 on one line straight across the coupon title beside it.
        if (sourceLines <= 1 && available < source * MIN_FIT_EMS) return Fit(source, null)

        val maxLines = sourceLines + EXTRA_LINES
        for ((i, scale) in SHRINK_STEPS.withIndex()) {
            val size = (source * scale).coerceAtLeast(MIN_TEXT_PX)
            measurePaint.textSize = size
            if (measurePaint.measureText(text) <= available) return Fit(size, null)
            val layout = buildLayout(text, available, size, centred)
            // Line count alone let 提示条件： become 프레젠테이션 조건 : on two lines inside a
            // 49px box, and the next label sits 61px below — so the wrapped block has to fit
            // the height the source had, not merely a line count budget.
            val fits = layout.lineCount <= maxLines && layout.height <= availableHeight
            if (fits || i == SHRINK_STEPS.lastIndex) {
                return Fit(size, layout)
            }
        }
        error("SHRINK_STEPS must not be empty")
    }

    /**
     * The size to draw the translation at, matching the Japanese it replaces.
     *
     * The measured em is the answer wherever it exists. It is missing only for a string
     * with no full-width character to measure — half-width katakana on its own — so the
     * old line-height inversion stays as the fallback for those. It is the weaker of the
     * two: the source's line height includes whatever lineSpacing its widget was given,
     * and inverting it through *our* font metrics assumes the source draws in the same
     * typeface. Both assumptions fail quietly, and unevenly, from one widget to the next.
     */
    private fun sourceSize(item: RenderItem): Float {
        val em = item.sourceEmSize
        if (em > 0f) return em.coerceAtLeast(MIN_TEXT_PX)
        return sizeForLineHeight(item.sourceLineHeight)
    }

    /**
     * Pull [estimate] onto [scale] when it is close enough to one of its sizes.
     *
     * Left alone when nothing is near, so a genuinely odd box keeps an honest guess
     * rather than being dragged to a size it was never drawn at.
     */
    private fun snapToScale(estimate: Float, scale: List<Float>): Float {
        val nearest = scale.minByOrNull { abs(it - estimate) } ?: return estimate
        return if (abs(nearest - estimate) <= estimate * SNAP_TOLERANCE) nearest else estimate
    }

    /** Font metrics scale linearly with size, so one measurement inverts the relation. */
    private fun sizeForLineHeight(target: Float): Float {
        measurePaint.textSize = 100f
        val fm = measurePaint.fontMetrics
        val at100 = fm.bottom - fm.top
        if (at100 <= 0f) return MIN_TEXT_PX
        return (target * 100f / at100).coerceAtLeast(MIN_TEXT_PX)
    }

    private fun buildLayout(
        text: String,
        width: Int,
        size: Float,
        centred: Boolean = false,
    ): StaticLayout {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size }
        return StaticLayout.Builder
            .obtain(text, 0, text.length, p, width.coerceAtLeast(1))
            .setAlignment(
                if (centred) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
            )
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
                fillPaint.color = if (DEBUG_GRID) Color.WHITE else p.washColor
                canvas.drawRect(p.wash, fillPaint)
            }

            fillPaint.color = if (DEBUG_GRID) GLYPH_BG else p.bandColor
            canvas.drawRect(p.band, fillPaint)

            textPaint.textSize = p.textSize
            // measure.py looks for dark ink on white, so the debug pass keeps the
            // default colours whatever the source was drawn in.
            textPaint.color = if (DEBUG_GRID) TEXT_DONE else p.textColor

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
