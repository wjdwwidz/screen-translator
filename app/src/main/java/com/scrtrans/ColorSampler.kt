package com.scrtrans

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Display
import kotlin.math.max

/**
 * Reads the source's own colours off the screen.
 *
 * The accessibility tree carries no colour at all and no node-level route to it exists
 * — see spec.md, "원문의 색", and [ColorProbe] for what was ruled out. Pixels are the
 * only way, and AccessibilityService.takeScreenshot is the one that needs no consent
 * dialog and leaves no capture indicator.
 *
 * Two things it costs. The screenshot is rate limited to one a second, so this runs on
 * the settle pass only, never while scrolling. And it composites our own overlay, so the
 * caller has to take the overlay down for the frame — that flicker is the price.
 */
object ColorSampler {

    const val ENABLED = true

    /**
     * How long the overlay has to be down before the cleared frame is actually on the
     * display. Measured: at 100ms the shot still caught our own text.
     */
    const val CLEAR_MS = 150L

    /** Sample a little wider than the ink, so the surface outnumbers the glyphs. */
    private const val PAD = 6

    /** Below this the box is a photo or a gradient, not a surface we can reproduce. */
    private const val MIN_BG_SHARE = 0.30f

    /** Squared RGB distance. Roughly 50 per channel — below it there is no text to see. */
    private const val MIN_CONTRAST = 3 * 50 * 50

    private const val OPAQUE = 0xFF shl 24

    /** Used only where the glyph colour could not be read; see [sampleSurface]. */
    private val DARK_INK = 0xFF18181C.toInt()
    private val LIGHT_INK = 0xFFFFFFFF.toInt()

    /** Says why an item came back uncoloured, which is the only way to tell the two
     *  reasons apart: no ink to sample at all, versus a sample we did not trust. */
    private const val LOG_MISSES = true

    /**
     * Takes one screenshot and returns [items] with [TextItem.colors] filled in wherever
     * the sample was trustworthy. Called back on a binder thread, not the main one.
     * Always calls back, including on failure, so the caller can restore the overlay.
     */
    fun sample(
        service: AccessibilityService,
        items: List<TextItem>,
        onResult: (List<TextItem>) -> Unit,
    ) {
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            { it.run() },
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (bmp == null) {
                        logw("colour sample: could not read the screenshot back")
                        onResult(items)
                        return
                    }
                    var got = 0
                    val out = items.map { item ->
                        val colors = if (item.hasInk) {
                            val box = Rect(item.inkLines.first())
                            for (r in item.inkLines) box.union(r)
                            sampleAt(bmp, box, item.text)
                        } else {
                            sampleSurface(bmp, item.bounds, item.text)
                        }
                        if (colors != null) got++
                        item.copy(colors = colors)
                    }
                    bmp.recycle()
                    logi("coloured $got/${items.size} items")
                    onResult(out)
                }

                override fun onFailure(errorCode: Int) {
                    logw("colour sample: takeScreenshot failed code=$errorCode")
                    onResult(items)
                }
            },
        )
    }

    /**
     * One pixel at the centre of the ink box is a coin flip — it lands in the gap
     * between two glyphs as often as on a stroke, which cannot tell white text from a
     * white surface. So read the whole box: the most common colour is the surface, and
     * the text is what stands furthest from it.
     */
    private fun sampleAt(bmp: Bitmap, ink: Rect, text: String): SourceColors? {
        val x0 = (ink.left - PAD).coerceIn(0, bmp.width - 1)
        val y0 = (ink.top - PAD).coerceIn(0, bmp.height - 1)
        val x1 = (ink.right + PAD).coerceIn(x0 + 1, bmp.width)
        val y1 = (ink.bottom + PAD).coerceIn(y0 + 1, bmp.height)
        val w = x1 - x0
        val h = y1 - y0
        val total = w * h
        if (total < 64) return null

        val px = IntArray(total)
        bmp.getPixels(px, 0, w, x0, y0, w, h)

        val hist = HashMap<Int, Int>(256)
        for (c in px) {
            val k = c or OPAQUE
            hist[k] = (hist[k] ?: 0) + 1
        }

        val surface = hist.maxByOrNull { it.value } ?: return null
        val share = surface.value.toFloat() / total
        if (share < MIN_BG_SHARE) {
            if (LOG_MISSES) logi("no colour \"$text\": surface only ${(share * 100).toInt()}%")
            return null
        }
        val bg = surface.key

        var farthest = 0
        for (c in hist.keys) farthest = max(farthest, dist(c, bg))
        if (farthest < MIN_CONTRAST) {
            if (LOG_MISSES) logi("no colour \"$text\": contrast $farthest under $MIN_CONTRAST")
            return null
        }

        // The glyph core rather than an antialiased edge: of the colours far enough from
        // the surface, the most common one is the stroke's own colour.
        val inkColor = hist.entries
            .filter { dist(it.key, bg) >= farthest / 2 }
            .maxByOrNull { it.value }?.key ?: return null

        return SourceColors(bg, inkColor)
    }

    /**
     * The fallback path's version: background only, from the node box.
     *
     * Without an ink box there is nowhere to look for the glyphs, so their colour cannot
     * be read — the node box holds icons and padding as well as text, and whatever
     * stands furthest from the surface in there is as likely to be an icon. But the
     * surface itself is still the box's most common colour, and that alone is what stops
     * the node-wide wash from being a white patch on a coloured widget. The text colour
     * is then simply whichever of black or white reads on that surface.
     */
    private fun sampleSurface(bmp: Bitmap, bounds: Rect, text: String): SourceColors? {
        val bg = surfaceOf(bmp, bounds) ?: run {
            if (LOG_MISSES) logi("no colour \"$text\": no flat surface in the node box")
            return null
        }
        return SourceColors(bg, if (isLight(bg)) DARK_INK else LIGHT_INK)
    }

    /** Most common colour in [box], or null if nothing is common enough to be a surface. */
    private fun surfaceOf(bmp: Bitmap, box: Rect): Int? {
        val x0 = box.left.coerceIn(0, bmp.width - 1)
        val y0 = box.top.coerceIn(0, bmp.height - 1)
        val x1 = box.right.coerceIn(x0 + 1, bmp.width)
        val y1 = box.bottom.coerceIn(y0 + 1, bmp.height)
        val w = x1 - x0
        val h = y1 - y0
        val total = w * h
        if (total < 64) return null

        val px = IntArray(total)
        bmp.getPixels(px, 0, w, x0, y0, w, h)
        val hist = HashMap<Int, Int>(256)
        for (c in px) {
            val k = c or OPAQUE
            hist[k] = (hist[k] ?: 0) + 1
        }
        val top = hist.maxByOrNull { it.value } ?: return null
        return if (top.value < total * MIN_BG_SHARE) null else top.key
    }

    private fun isLight(c: Int): Boolean {
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        return (r * 299 + g * 587 + b * 114) / 1000 > 140
    }

    private fun dist(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
        val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
        val db = (a and 255) - (b and 255)
        return dr * dr + dg * dg + db * db
    }
}
