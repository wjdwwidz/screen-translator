package com.scrtrans

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Walks the accessibility tree and pulls out every visible Japanese string with its bounds. */
object TextCollector {

    private const val MAX_NODES = 4000

    fun collect(root: AccessibilityNodeInfo, screenW: Int, screenH: Int): List<TextItem> {
        val out = ArrayList<TextItem>(64)
        val seen = HashSet<String>(64)
        var visited = 0

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++

            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && containsJapanese(text)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (isDrawable(r, screenW, screenH)) {
                    // Same string at the same spot can appear twice (e.g. a label and
                    // its wrapper both carrying text); drawing it twice just darkens it.
                    val key = "$text@${r.left},${r.top},${r.right},${r.bottom}"
                    if (seen.add(key)) out.add(TextItem(text, r))
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        return out
    }

    private fun isDrawable(r: Rect, screenW: Int, screenH: Int): Boolean {
        if (r.width() <= 0 || r.height() <= 0) return false
        // Fully off-screen, or a degenerate sliver we could never fit text into.
        if (r.right <= 0 || r.bottom <= 0 || r.left >= screenW || r.top >= screenH) return false
        if (r.height() < 8) return false
        return true
    }
}
