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
    /** See TextItem.sourceEmSize. 0 means fall back to deriving size from line height. */
    val sourceEmSize: Float = 0f,
    /** The box the source was laid out in; see TextItem.container. */
    val container: Rect = bounds,
    /** The source's own colours, or null to draw the default dark-on-white. */
    val colors: SourceColors? = null,
    /** See TextItem.inkLeft. */
    val inkLeft: Int = 0,
    val inkRight: Int = 0,
) {
    val hasInk get() = inkLines.isNotEmpty() && sourceLineHeight > 0f

    /** Whether the screenshot could say where inside [bounds] the source's text sits. */
    val hasSpan get() = inkLeft > 0 && inkRight > inkLeft
}
