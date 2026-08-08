package com.scrtrans

import android.graphics.Rect

/**
 * One box ready to be drawn.
 *
 * [display] is whatever should appear on screen right now — the Korean translation
 * once we have it, the Japanese source while we are still waiting.
 */
data class RenderItem(
    val display: String,
    val bounds: Rect,
    val translated: Boolean,
    val inkLines: List<Rect> = emptyList(),
    val sourceLineHeight: Float = 0f,
) {
    val hasInk get() = inkLines.isNotEmpty() && sourceLineHeight > 0f
}
