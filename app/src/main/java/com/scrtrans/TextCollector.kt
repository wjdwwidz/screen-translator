package com.scrtrans

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.ceil

/** Walks the accessibility tree and pulls out every visible Japanese string with its bounds. */
object TextCollector {

    private const val MAX_NODES = 4000

    /**
     * Ink geometry relative to the node's top-left, which scrolling does not change.
     *
     * [emSize] is the source's font size in pixels, or 0 when it could not be read;
     * see [emSize] for why a character's width gives it and [lineHeight] does not.
     */
    private class InkGeom(val lines: List<Rect>, val lineHeight: Float, val emSize: Float)

    /**
     * A node plus the boxes needed to say how its text is aligned.
     *
     * getParent() is a round trip into the app, and we are already walking downwards,
     * so the ancestry rides along on the stack instead. [inherited] carries the nearest
     * ancestor that was actually wider than the node it wrapped, which is what a chain
     * of hugging wrap_content wrappers would otherwise hide.
     */
    private class Frame(
        val node: AccessibilityNodeInfo,
        val parentBounds: Rect?,
        val inherited: Rect?,
    )

    /**
     * refreshWithExtraData is a synchronous call into the app's process and costs
     * 4-25ms a node; a full screen ran to 600ms, well past the 300ms debounce, and the
     * overlay visibly lagged a scroll. Scrolling only translates a node, so its ink
     * geometry relative to its own box is stable — cache that, keyed by the text and the
     * box size, and a scroll re-probes nothing.
     *
     * Only hints are cached as misses; see [inkGeom].
     */
    private val inkCache = object : LinkedHashMap<String, InkGeom?>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, InkGeom?>) =
            size > 400
    }

    /**
     * [probe] false means answer only from the ink cache and never call into the app.
     * A screenful of unseen strings costs ~450ms to probe, which is longer than the
     * debounce and shows up as the overlay trailing a scroll. So a scroll draws from
     * cache and estimates, and the caller schedules a probing pass once things settle.
     */
    fun collect(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
        probe: Boolean,
    ): List<TextItem> {
        val out = ArrayList<TextItem>(64)
        val seen = HashSet<String>(64)
        var visited = 0

        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, null, null))

        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val frame = stack.removeLast()
            val node = frame.node
            visited++

            val r = Rect()
            node.getBoundsInScreen(r)
            // A parent that merely hugs its child says nothing about alignment, so keep
            // looking up until one has slack in it.
            val container = when {
                frame.parentBounds != null && frame.parentBounds.width() > r.width() + 2 ->
                    frame.parentBounds
                else -> frame.inherited ?: r
            }

            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && worthTranslating(text)) {
                if (isDrawable(r, screenW, screenH)) {
                    // Same string at the same spot can appear twice (e.g. a label and
                    // its wrapper both carrying text); drawing it twice just darkens it.
                    val key = "$text@${r.left},${r.top},${r.right},${r.bottom}"
                    if (seen.add(key)) {
                        if (probe && ColorProbe.ENABLED) ColorProbe.inspectNode(node, text)
                        val geom = inkGeom(node, text, r, screenW, screenH, probe)
                        val lines = geom?.lines?.map {
                            Rect(it.left + r.left, it.top + r.top, it.right + r.left, it.bottom + r.top)
                        } ?: emptyList()
                        out.add(
                            TextItem(
                                text, r, lines,
                                geom?.lineHeight ?: 0f, geom?.emSize ?: 0f, container,
                            )
                        )
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(Frame(it, r, container)) }
            }
        }

        return out
    }

    /**
     * Ask the node where each character of its text actually sits.
     *
     * This is the API screen readers use to highlight the word being spoken, and
     * TextView implements it in the framework — the app did nothing to enable it.
     * Rects come back in screen coordinates, the same space as getBoundsInScreen, so
     * they need no conversion of their own.
     *
     * Unrelated to extraRenderingInfo.textSizeInPx, which is null on every node here.
     *
     * A miss is only worth remembering when it is going to happen again. It is, for a
     * hint: a hint is not the text, so there are no characters to locate and there never
     * will be. For anything else a miss is noise — a view that is mid-transition or not
     * laid out yet answers for no characters either, and caching *that* pins a perfectly
     * locatable label to OverlayView's estimate path, which covers the node box whole and
     * takes any leading icon with it. Measured on the style-search tab: 12 of 21 strings
     * stuck there against 2 of 21 on a freshly started service, the pin beside エリア and
     * the magnifier beside キーワード gone with them, and nothing short of restarting the
     * process brought them back.
     *
     * So a non-hint miss is simply not recorded, and the next settle pass asks again. That
     * costs 4-25ms a node per pass for as long as it keeps failing, which is affordable
     * because it is a handful of nodes; the cache exists to stop a *screenful* of them.
     */
    private fun inkGeom(
        node: AccessibilityNodeInfo,
        text: String,
        bounds: Rect,
        screenW: Int,
        screenH: Int,
        probe: Boolean,
    ): InkGeom? {
        val key = "$text|${bounds.width()}x${bounds.height()}"
        if (inkCache.containsKey(key)) return inkCache[key]
        // Not cached and not allowed to ask: leave the cache untouched so the settle
        // pass still tries, rather than recording a miss it never actually looked for.
        if (!probe) return null
        val geom = probeCharacterLocations(node, text, bounds, screenW, screenH)
        // refreshWithExtraData has just refreshed the node, so the hint flag is current.
        if (geom != null || node.isShowingHintText) inkCache[key] = geom
        return geom
    }

    private fun probeCharacterLocations(
        node: AccessibilityNodeInfo,
        text: String,
        bounds: Rect,
        screenW: Int,
        screenH: Int,
    ): InkGeom? {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, text.length)
        }
        val ok = try {
            node.refreshWithExtraData(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args
            )
        } catch (e: Exception) {
            logw("refreshWithExtraData failed", e)
            false
        }
        if (!ok) return null

        val raw = node.extras
            ?.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
            ?: return null

        // Characters scrolled out of view or clipped come back degenerate. Index is kept
        // rather than filtered away, because reading a width means knowing which
        // character of [text] it belongs to.
        val chars = ArrayList<RectF>(raw.size)
        val fullWidths = ArrayList<Float>(raw.size)
        for (i in raw.indices) {
            val c = raw[i] as? RectF ?: continue
            if (c.width() <= 0f || c.height() <= 0f) continue
            if (c.right <= 0f || c.bottom <= 0f) continue
            if (c.left >= screenW || c.top >= screenH) continue
            chars.add(c)
            if (i < text.length && isFullWidth(text[i])) fullWidths.add(c.width())
        }
        if (chars.isEmpty()) return null

        val lineHeight = chars[0].height()
        val lines = ArrayList<Rect>(2)
        var current: Rect? = null
        var currentTop = 0f
        // A new line starts where the tops jump by more than half a line.
        // Stored relative to the node box so a scrolled node can reuse it.
        for (c in chars.sortedBy { it.top }) {
            val box = Rect(
                c.left.toInt() - bounds.left, c.top.toInt() - bounds.top,
                ceil(c.right).toInt() - bounds.left, ceil(c.bottom).toInt() - bounds.top,
            )
            val cur = current
            if (cur == null || c.top > currentTop + lineHeight * 0.5f) {
                lines.add(box)
                current = box
                currentTop = c.top
            } else {
                cur.union(box)
            }
        }
        return InkGeom(lines, lineHeight, emSize(fullWidths, lineHeight))
    }

    /**
     * The source's font size in pixels, read off the width of its own glyphs.
     *
     * Kana and ideographs are laid out on a full em square, so a full-width character's
     * advance *is* the font size. Height cannot do this job: the rects come back with
     * the line's top and bottom, so their height carries the widget's lineSpacing as
     * well, and inverting it also assumes the source draws in the same typeface we do.
     * Width is free of both.
     *
     * Widths far from the line height are dropped before the median: the last character
     * of a wrapped line can measure to the line end rather than to its own advance, and
     * on a two-character label one such outlier would otherwise be the median. Ordinary
     * fonts put an em somewhere near 0.6-0.9 of the line box, so the window is wide
     * enough to keep every honest sample.
     */
    private fun emSize(widths: List<Float>, lineHeight: Float): Float {
        val plausible = widths.filter { it > lineHeight * 0.35f && it < lineHeight * 1.2f }
        if (plausible.isEmpty()) return 0f
        return plausible.sorted()[plausible.size / 2]
    }

    /**
     * Strings with no Japanese in them — "10", "¥5,500", "OPEN" — are left alone:
     * covering them would only clutter the screen and waste engine calls.
     *
     * Katakana-only strings are left alone too, unless the glossary knows them. They are
     * loanwords, the engine mistranslates about half of them, and its failure mode is
     * confident nonsense — "프랑스 쾅" over フレンチバング. The source is the better
     * answer there: it is at least the term the user is looking at. See [isKatakanaOnly].
     * Deciding here rather than in the translator keeps our pixels off those labels
     * entirely, so the app's own rendering shows through untouched.
     */
    private fun worthTranslating(text: String): Boolean {
        if (!containsJapanese(text)) return false
        if (isKatakanaOnly(text) && Glossary.lookup(text) == null) return false
        return true
    }

    private fun isDrawable(r: Rect, screenW: Int, screenH: Int): Boolean {
        if (r.width() <= 0 || r.height() <= 0) return false
        // Fully off-screen, or a degenerate sliver we could never fit text into.
        if (r.right <= 0 || r.bottom <= 0 || r.left >= screenW || r.top >= screenH) return false
        if (r.height() < 8) return false
        return true
    }
}
